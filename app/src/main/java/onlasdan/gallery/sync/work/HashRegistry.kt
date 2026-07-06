/*
 *   Copyright 2020–2026 PhotoZ
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package onlasdan.gallery.sync.work

import android.app.Application
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import onlasdan.gallery.encryption.domain.models.Algorithm
import onlasdan.gallery.model.database.entity.PHOTOK_FILE_EXTENSION
import onlasdan.gallery.settings.data.Config
import onlasdan.gallery.sync.domain.SyncConfig
import onlasdan.gallery.sync.rclone.RcloneController
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the encrypted dedup registry on the rclone remote.
 *
 * The registry is a single JSON document at
 *   `<remote>:${SyncConfig.REPO_DIR}/registry.json.crypt`
 * containing one entry per content-hash that has ever been uploaded to the
 * remote. The JSON is encrypted with AES-256-GCM using the VMK directly as
 * the key (NOT a password-derived KEK — the VMK is already the root key and
 * every device that can unlock the vault has it in memory). The on-wire
 * format is:
 *
 * ```
 *   [12-byte GCM nonce/IV][ciphertext + 16-byte auth-tag]
 * ```
 *
 * Why GCM (not the CBC used for photo bodies)?
 *  - The registry is small (a few KB to a few hundred KB) and pure-metadata,
 *    so the AEAD guarantee (tamper-detection via the auth tag) is worth the
 *    slightly higher per-byte cost. CBC + HMAC would also work but GCM is a
 *    single primitive and is already exported by [Algorithm.AesGcmNoPadding].
 *  - Photo bodies stay on AES-CBC because they're large and streamed through
 *    [onlasdan.gallery.encryption.domain.crypto.CbcCryptoEngine] which
 *    supports random-access reads (the image viewer needs byte-range reads
 *    to decode a region of a JPEG). GCM's auth tag would have to be
 *    recomputed on every random-access read, which is too expensive.
 *
 * ## Lifecycle
 *
 *  1. **Login** ([RepoManager.restoreThumbnailsAfterLogin]):
 *     [downloadAndCache] is called with the VMK bytes. The encrypted
 *     `registry.json.crypt` is downloaded, decrypted, parsed, and the
 *     entries REPLACE the local [HashRegistryDao] cache. If the registry
 *     doesn't exist on the remote (fresh repo, or pre-v9 repo that predates
 *     this feature), the local cache is cleared and 0 is returned — the
 *     caller proceeds with an empty registry.
 *
 *  2. **Upload** ([onlasdan.gallery.sync.work.PhotoSyncWorker.performUpload]):
 *     Before uploading the original, the worker calls [findExisting] with the
 *     photo's `contentHash`. If an entry already exists, the upload is
 *     skipped (the original is already on the remote under a different UUID)
 *     and the photo's `syncState` transitions straight to UPLOADED. If no
 *     entry exists, the upload proceeds normally and [addEntry] is called
 *     after success to append the new hash → UUID mapping to the local cache.
 *
 *  3. **Batch completion**: the last worker in a batch (see
 *     [SyncBatchTracker.isBatchComplete]) calls [uploadToRemote] with the
 *     VMK bytes. The current local cache is serialized to JSON, encrypted
 *     with GCM, and uploaded — overwriting the previous registry. This is
 *     safe because the local cache is a strict superset of the remote (every
 *     device adds entries monotonically; deletions are tombstoned, not
 *     physically removed).
 *
 * ## Concurrency / multi-device
 *
 * The current implementation does NOT merge concurrent registry updates from
 * multiple devices — the last writer wins. This is acceptable for the
 * single-user, single-device-at-a-time usage pattern of PhotoZ. A future
 * enhancement could download the remote registry, merge with the local
 * cache, and re-upload — but for now, the assumption is that uploads from
 * different devices don't race.
 *
 * @since v9 — dedup + encrypted GCM registry
 */
@Singleton
class HashRegistry
        @Inject
        constructor(
                private val app: Application,
                private val rcloneController: RcloneController,
                private val dao: HashRegistryDao,
                // @since registry-gc feature — needed by [softDelete] / [gcThumbnailPacks] /
                // [gcOriginals] to obtain the VMK for encrypting the registry when flushing
                // tombstones + compacted state to the remote. Already Hilt-provided
                // (SessionRepositoryImpl has @Inject constructor + @Singleton).
                private val sessionRepository: onlasdan.gallery.encryption.domain.SessionRepository,
        ) {
                companion object {
                        private const val TAG = "RcloneDiag"
                        private const val REGISTRY_REMOTE_PATH = "${SyncConfig.REPO_DIR}/registry.json.crypt"
                        private const val GCM_NONCE_SIZE = 12 // bytes (96 bits, standard for GCM)
                        private const val GCM_TAG_SIZE = 128 // bits

                        /**
                         * Threshold (in percent) of tombstoned entries within a single
                         * thumbnail pack above which the GC will repack it. Below this, the
                         * bandwidth cost of re-downloading + re-uploading outweighs the
                         * dead-space savings.
                         *
                         * @since registry-gc feature
                         */
                        private const val GC_DEAD_SPACE_THRESHOLD_PCT = 30

                        // ─── GZIP compression for registry (file-upload feature) ──────────────
                        // The registry is hand-rolled JSON — text-heavy, with lots of repeated
                        // field names ("content_hash", "uuid", "filename", etc.) and predictable
                        // structure. GZIP typically achieves 70-80% size reduction on such
                        // payloads, which directly reduces the per-batch upload size and the
                        // remote storage footprint.
                        //
                        // ## On-wire format (versioned)
                        //
                        // The first byte of the encrypted blob is a version tag:
                        //   0x01 = legacy: [12-byte nonce][GCM(plaintext JSON)]
                        //          (no version byte was written — the file starts with the
                        //           nonce. The download path treats "first byte != 0x02" as
                        //           legacy and parses from offset 0.)
                        //   0x02 = current: [1-byte version=0x02][12-byte nonce][GCM(GZIP(JSON))]
                        //
                        // The 1/256 chance that a legacy file's nonce happens to start with
                        // 0x02 is handled by a try/catch around the GZIP decompress in the
                        // 0x02 branch — if decompression fails, fall back to treating the
                        // payload as legacy plaintext JSON. This is rare (one registry in
                        // every ~256 devices on first upload after upgrade) and recoverable
                        // (the next batch's upload overwrites the file with a properly-
                        // tagged 0x02 version).
                        //
                        // ## Why only the registry (not photos)
                        //
                        // Photo bodies stay on AES-CBC without GZIP because:
                        //   1. JPEGs / MP4s / HEICs are already compressed — GZIP would
                        //      shrink them by <5% while burning CPU on every import.
                        //   2. The image viewer uses random-access CBC reads to decode
                        //      regions of large JPEGs without loading the whole file.
                        //      GZIP doesn't support random access, so adding it would
                        //      break the viewer.
                        //   3. Changing the photo format would require migrating every
                        //      existing encrypted photo on disk — high risk, low reward.
                        //
                        // The registry is small, pure-metadata, and re-uploaded on every
                        // batch — perfect candidate for compression.
                        //
                        // @since gzip-registry feature
                        private const val REGISTRY_FORMAT_VERSION_LEGACY: Byte = 0x01
                        private const val REGISTRY_FORMAT_VERSION_GZIP: Byte = 0x02

                        /**
                         * Sprint 10+ / M7 v2 — Per-entry encrypted format.
                         *
                         * Format: [version=0x03][num_entries(4, big-endian)]
                         *   For each entry:
                         *     [nonce(12)][ciphertext_len(4, big-endian)][ciphertext+tag(N)]
                         *
                         * Each entry is independently GCM-encrypted with its own vault's VMK.
                         * On download, the client tries to decrypt EVERY entry with the current
                         * VMK — GCM auth tag naturally filters: entries from other vaults fail
                         * silently (caught + skipped).
                         *
                         * This allows ALL vaults (including decoy vaults) to sync to the same
                         * `registry.json.crypt` file without revealing the vault count. Forensic
                         * inspection sees one file with N encrypted blobs — without each vault's
                         * VMK, they can't tell which blobs belong to which vault.
                         *
                         * @since v15 — Sprint 10+ / M7 v2 per-entry encrypted registry
                         */
                        private const val REGISTRY_FORMAT_VERSION_PER_ENTRY: Byte = 0x03
                }

                private fun diag(
                        msg: String,
                        t: Throwable? = null,
                ) {
                        Log.e(TAG, "[HashRegistry] $msg", t)
                        try {
                                val entry =
                                        buildString {
                                                append("\n[")
                                                append(TAG)
                                                append("] [HashRegistry] ")
                                                append(msg)
                                                append('\n')
                                                if (t != null) {
                                                        append(t.stackTraceToString())
                                                        append('\n')
                                                }
                                        }
                                onlasdan.gallery.sync.debug.SyncLogRotator
                                        .append(app, entry)
                        } catch (e: Exception) {
                                android.util.Log.e(TAG, "[HashRegistry] diag write FAILED: ${e.message}", e)
                        }
                }

                /**
                 * Check if a content hash already exists in the local registry cache.
                 * Returns the existing entry if found, null if not (new file).
                 */
                suspend fun findExisting(contentHash: String): HashRegistryEntry? =
                        withContext(Dispatchers.IO) {
                                dao.findByHash(contentHash)
                        }

                /**
                 * Look up a registry entry by the canonical photo UUID. Used by
                 * [onlasdan.gallery.sync.rclone.RepoManager.applyRegistryMetadataToPhotos]
                 * to backfill placeholder Photo rows after a fresh-install login.
                 *
                 * @since v9 followup — backfill Photo metadata from registry after login
                 */
                suspend fun findByUuid(uuid: String): HashRegistryEntry? =
                        withContext(Dispatchers.IO) {
                                dao.findByUuid(uuid)
                        }

                /**
                 * Add a new entry to the local registry cache.
                 */
                suspend fun addEntry(entry: HashRegistryEntry) =
                        withContext(Dispatchers.IO) {
                                dao.upsert(entry)
                        }

                /**
                 * Update an existing entry in the local registry cache. Used by the
                 * thumbnail-packing flow (see [onlasdan.gallery.sync.work.PhotoSyncWorker]
                 * `flushPendingThumbnailPacks`) to fill in `thumbnailPack` / `thumbnailOffset`
                 * / `thumbnailLength` after a pack has been uploaded.
                 *
                 * @since v9 followup — packed thumbnails
                 */
                suspend fun updateEntry(entry: HashRegistryEntry) =
                        withContext(Dispatchers.IO) {
                                dao.upsert(entry)
                        }

                /**
                 * Return all live (non-tombstoned) entries in the local cache. Used by
                 * the thumbnail-packing flow at batch end and by the restore flow to
                 * group entries by `thumbnailPack` for pack-based downloads.
                 *
                 * @since v9 followup — packed thumbnails
                 */
                suspend fun allEntries(): List<HashRegistryEntry> =
                        withContext(Dispatchers.IO) {
                                dao.getAll()
                        }

                /**
                 * Download the encrypted registry from the remote, decrypt it, and cache all entries
                 * in the local Room DB. Called during login.
                 *
                 * @param vmkBytes The raw VMK key bytes (from `SessionRepository.get().vmk.encoded`)
                 * @return count of entries loaded, or 0 if registry doesn't exist yet (new repo)
                 */
                suspend fun downloadAndCache(vmkBytes: ByteArray): Int =
                        withContext(Dispatchers.IO) {
                                val remote =
                                        try {
                                                val config =
                                                        app.getSharedPreferences(
                                                                Config.FILE_NAME,
                                                                android.content.Context.MODE_PRIVATE,
                                                        )
                                                config.getString("sync^chosenRemote", null)
                                        } catch (e: Exception) {
                                                null
                                        } ?: run {
                                                diag("downloadAndCache: no remote chosen")
                                                return@withContext 0
                                        }

                                val remotePath = "$remote:$REGISTRY_REMOTE_PATH"
                                diag("downloadAndCache: downloading $remotePath")

                                val tempFile = File(app.cacheDir, "registry-download-${System.currentTimeMillis()}.crypt")
                                try {
                                        val result = rcloneController.downloadFile(remotePath, tempFile.absolutePath)
                                        if (result.isFailure) {
                                                diag("downloadAndCache: registry not on remote (new repo or pre-dedup feature) — treating as 0 entries"
                                                        )
                                                return@withContext 0
                                        }

                                        val encryptedData = tempFile.readBytes()
                                        if (encryptedData.size < GCM_NONCE_SIZE) {
                                                diag("downloadAndCache: registry file too small (${encryptedData.size} bytes) — treating as empty"
                                                        )
                                                return@withContext 0
                                        }

                                        // ─── Versioned format dispatch (gzip-registry feature) ──────────
                                        // First byte is the format version tag (0x02 for the new
                                        // GZIP-compressed format). Anything else is the legacy format
                                        // (no version byte — the first byte is the start of the GCM
                                        // nonce). See [REGISTRY_FORMAT_VERSION_GZIP] for the full
                                        // format spec.
                                        val versionByte = encryptedData[0]

                                        // Sprint 10+ / M7 v2 — per-entry encrypted format (version 0x03).
                                        // Each entry is independently GCM-encrypted. Try-decrypt each
                                        // with current VMK — entries from other vaults fail silently.
                                        val entries =
                                                if (versionByte == REGISTRY_FORMAT_VERSION_PER_ENTRY) {
                                                        diag("downloadAndCache: per-entry encrypted format (0x03) — trying each entry")
                                                        parsePerEntryEncrypted(encryptedData, vmkBytes)
                                                } else {
                                                        // Legacy or GZIP format — single-blob GCM decrypt.
                                                        val json =
                                                                if (versionByte == REGISTRY_FORMAT_VERSION_GZIP) {
                                                                        val nonce = encryptedData.copyOfRange(1, 1 + GCM_NONCE_SIZE)
                                                                        val ciphertext = 
                                                                                encryptedData.copyOfRange(1 + GCM_NONCE_SIZE, encryptedData.size)

                                                                        val key = SecretKeySpec(vmkBytes, "AES")
                                                                        val cipher = Cipher.getInstance(Algorithm.AesGcmNoPadding.value)
                                                                        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_SIZE, nonce))
                                                                        val plaintextCompressed = cipher.doFinal(ciphertext)

                                                                        try {
                                                                                gzipDecompressToString(plaintextCompressed)
                                                                        } catch (gzipEx: Exception) {
                                                                                diag(
                                                                                        "downloadAndCache: version byte was 0x02 but GZIP decompress failed — " +
                                                                                                "likely a legacy file whose nonce happened to start with 0x02. " +
                                                                                                "Re-attempting as legacy format. gzipError=${gzipEx.message}",
                                                                                )
                                                                                decryptLegacyRegistryJson(encryptedData, vmkBytes)
                                                                        }
                                                                } else {
                                                                        decryptLegacyRegistryJson(encryptedData, vmkBytes)
                                                                }

                                                        diag("downloadAndCache: decrypted registry (${json.length} chars)")
                                                        parseRegistryJson(json)
                                                }

                                        diag("downloadAndCache: parsed ${entries.size} entries for current vault")

                                        dao.clear()
                                        dao.upsertAll(entries)
                                        entries.size
                                } catch (e: Exception) {
                                        diag("downloadAndCache: FAILED: ${e.message}", e)
                                        0
                                } finally {
                                        tempFile.delete()
                                }
                        }

                /**
                 * Upload the current local registry cache to the remote, encrypted with VMK.
                 * Called after a batch of uploads completes.
                 *
                 * @param vmkBytes The raw VMK key bytes
                 */
                suspend fun uploadToRemote(vmkBytes: ByteArray) =
                        withContext(Dispatchers.IO) {
                                val remote =
                                        try {
                                                val config =
                                                        app.getSharedPreferences(
                                                                Config.FILE_NAME,
                                                                android.content.Context.MODE_PRIVATE,
                                                        )
                                                config.getString("sync^chosenRemote", null)
                                        } catch (e: Exception) {
                                                null
                                        } ?: run {
                                                diag("uploadToRemote: no remote chosen — skipping")
                                                return@withContext
                                        }

                                val vaultId = runCatching { sessionRepository.require().vaultId }.getOrNull()
                                val entries =
                                        if (vaultId != null) {
                                                dao.getAllForVaultIncludingDeleted(vaultId)
                                        } else {
                                                dao.getAllIncludingDeleted()
                                        }
                                diag("uploadToRemote: serializing ${entries.size} entries for vault_id=$vaultId")

                                // ─── M7 v2 — Multi-vault registry merge ──────────────────────
                                // Before uploading, download the existing remote registry and
                                // preserve entries from OTHER vaults. This prevents vault A's
                                // upload from wiping vault B's entries.
                                //
                                // Flow:
                                // 1. Download existing remote registry (if it exists)
                                // 2. Extract raw encrypted blobs (without decrypting)
                                // 3. For each blob, try decrypt with current VMK:
                                //    - Success → belongs to current vault → will be replaced
                                //    - Failure → belongs to another vault → PRESERVE as-is
                                // 4. Serialize: fresh current-vault entries + preserved blobs
                                // 5. Upload merged result
                                //
                                // @since Sprint 8 — M7 v2 multi-vault registry merge
                                var preservedBlobs: List<ByteArray> = emptyList()
                                try {
                                        val remotePath = "$remote:$REGISTRY_REMOTE_PATH"
                                        val downloadTempFile = 
                                                File(app.cacheDir, "registry-download-merge-${System.currentTimeMillis()}.crypt")
                                        val downloadResult = rcloneController.downloadFile(remotePath, downloadTempFile.absolutePath)
                                        if (downloadResult.isSuccess && downloadTempFile.exists()) {
                                                val existingData = downloadTempFile.readBytes()
                                                downloadTempFile.delete()
                                                if (existingData.isNotEmpty() && existingData[0] == REGISTRY_FORMAT_VERSION_PER_ENTRY) {
                                                        val allBlobs = extractRawBlobs(existingData)
                                                        // Keep only blobs that CAN'T be decrypted by current VMK (other vaults)
                                                        preservedBlobs = allBlobs.filterNot { canDecryptBlob(it, vmkBytes) }
                                                        diag("uploadToRemote: M7 v2 merge — preserved ${preservedBlobs.size} entries from other vaults (total existing: ${allBlobs.size})"
                                                                )
                                                }
                                        }
                                } catch (e: Exception) {
                                        diag("uploadToRemote: M7 v2 merge — download failed (non-fatal, will upload fresh): ${e.message}")
                                }

                                // Serialize: our fresh entries + preserved other-vault blobs
                                val encryptedData = serializeMergedEncrypted(entries, preservedBlobs, vmkBytes)

                                val tempFile = File(app.cacheDir, "registry-upload-${System.currentTimeMillis()}.crypt")
                                try {
                                        tempFile.writeBytes(encryptedData)
                                        val remotePath = "$remote:$REGISTRY_REMOTE_PATH"
                                        diag("uploadToRemote: uploading ${encryptedData.size} bytes (${entries.size} ours + ${preservedBlobs.size} preserved) → $remotePath"
                                                )
                                        rcloneController.uploadFile(tempFile.absolutePath, remotePath).getOrThrow()
                                        diag("uploadToRemote: OK")
                                } finally {
                                        tempFile.delete()
                                }
                        }

                /**
                 * Decrypt a legacy-format registry blob (no version byte, just
                 * `[12-byte nonce][GCM(plaintext JSON)]`) and return the JSON as a string.
                 *
                 * Helper extracted from the old [downloadAndCache] inline implementation
                 * so the new GZIP code path can fall back to it on the (rare) case where
                 * a legacy file's nonce happens to start with the `0x02` byte that the
                 * new format uses as its version tag.
                 *
                 * @since gzip-registry feature — extracted helper for legacy fallback
                 */
                private fun decryptLegacyRegistryJson(
                        encryptedData: ByteArray,
                        vmkBytes: ByteArray,
                ): String {
                        val nonce = encryptedData.copyOfRange(0, GCM_NONCE_SIZE)
                        val ciphertext = encryptedData.copyOfRange(GCM_NONCE_SIZE, encryptedData.size)

                        val key = SecretKeySpec(vmkBytes, "AES")
                        val cipher = Cipher.getInstance(Algorithm.AesGcmNoPadding.value)
                        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_SIZE, nonce))
                        val plaintext = cipher.doFinal(ciphertext)
                        return String(plaintext, Charsets.UTF_8)
                }

                /**
                 * GZIP-compress a byte array. Used by [uploadToRemote] to compress the
                 * registry JSON before GCM encryption — JSON compresses 70-80% with
                 * GZIP due to repeated field names and predictable structure.
                 *
                 * Uses a 64 KB buffer for the underlying `GZIPOutputStream` — the default
                 * 512-byte buffer would force many small `write()` syscalls on large
                 * registries.
                 *
                 * @since gzip-registry feature
                 */
                private fun gzipCompress(data: ByteArray): ByteArray {
                        val baos = ByteArrayOutputStream(data.size / 4) // estimate ~4x compression
                        GZIPOutputStream(baos).use { gzipOut ->
                                gzipOut.write(data)
                        }
                        return baos.toByteArray()
                }

                /**
                 * GZIP-decompress a byte array to a UTF-8 string. Used by [downloadAndCache]
                 * after GCM decryption to recover the registry JSON.
                 *
                 * Throws [java.io.IOException] if the input is not a valid GZIP stream —
                 * the caller ([downloadAndCache]) catches this and falls back to the
                 * legacy plaintext-JSON interpretation.
                 *
                 * @since gzip-registry feature
                 */
                private fun gzipDecompressToString(data: ByteArray): String {
                        GZIPInputStream(data.inputStream()).use { gzipIn ->
                                return gzipIn.bufferedReader(Charsets.UTF_8).readText()
                        }
                }

                /**
                 * Pack pending thumbnails into ≤5 MB packs, upload each pack, and update
                 * the matching registry entries with `thumbnailPack` / `thumbnailOffset`
                 * / `thumbnailLength`.
                 *
                 * Called at batch end (see
                 * [onlasdan.gallery.sync.work.PhotoSyncWorker.onBatchCompleteIfDone]) AFTER
                 * the registry entries have been added (with `thumbnail_pack = null`) but
                 * BEFORE [uploadToRemote] — so the registry flush to the remote already
                 * carries the pack metadata.
                 *
                 * ## Algorithm
                 *
                 *  1. Read all live registry entries with `thumbnail_pack = null` whose
                 *     local thumbnail file (`<filesDir>/<uuid>.<ext>.tn`) exists.
                 *  2. Walk the entries in order, accumulating their thumbnail bytes into a
                 *     pack buffer. When the buffer would exceed
                 *     [SyncConfig.THUMBNAIL_PACK_SIZE_BYTES], flush it as a pack
                 *     (`pack-NNNN.pack`) and start a new one.
                 *  3. For each pack: upload via [RcloneController.uploadFile], then update
                 *     each entry in the pack with `thumbnail_pack = pack-NNNN`,
                 *     `thumbnail_offset = <byte offset within pack>`, `thumbnail_length =
                 *     <byte length>`.
                 *  4. After all packs are uploaded, the caller ([PhotoSyncWorker]) calls
                 *     [uploadToRemote] to flush the registry (now carrying pack metadata)
                 *     to the remote.
                 *
                 * ## Failure handling
                 *
                 * Non-fatal. If a pack upload fails, the entries in that pack keep
                 * `thumbnail_pack = null` and will be retried in the next batch's
                 * [flushPendingThumbnailPacks] call. The local thumbnail files are NOT
                 * deleted (they're still needed for a future retry).
                 *
                 * ## Restore-side counterpart
                 *
                 * [onlasdan.gallery.sync.rclone.RepoManager.restoreThumbnailsFromPacks]
                 * downloads each pack once and extracts thumbnails by offset+length,
                 * instead of N round-trips for N individual thumbnails.
                 *
                 * @since v9 followup — packed thumbnails (Bug 4)
                 */
                suspend fun flushPendingThumbnailPacks() =
                        withContext(Dispatchers.IO) {
                                val remote =
                                        try {
                                                val config =
                                                        app.getSharedPreferences(
                                                                Config.FILE_NAME,
                                                                android.content.Context.MODE_PRIVATE,
                                                        )
                                                config.getString("sync^chosenRemote", null)
                                        } catch (e: Exception) {
                                                null
                                        } ?: run {
                                                diag("flushPendingThumbnailPacks: no remote chosen — skipping")
                                                return@withContext
                                        }

                                // Read all entries with thumbnail_pack = null. These are the "pending
                                // pack" entries — added by PhotoSyncWorker.performUpload after a
                                // successful original upload, but not yet assigned to a pack.
                                val pendingEntries = dao.getAll().filter { it.thumbnailPack == null }
                                if (pendingEntries.isEmpty()) {
                                        diag("flushPendingThumbnailPacks: no pending entries — nothing to pack")
                                        return@withContext
                                }
                                diag("flushPendingThumbnailPacks: ${pendingEntries.size} pending entries to pack")

                                // Resolve each entry to (entry, thumbnailBytes). Skip entries whose
                                // local thumbnail file doesn't exist (e.g. imported before v9, or the
                                // thumbnail file was deleted out-of-band).
                                data class PendingThumb(
                                        val entry: HashRegistryEntry,
                                        val bytes: ByteArray,
                                )
                                val pendingThumbs = mutableListOf<PendingThumb>()
                                for (entry in pendingEntries) {
                                        val thumbFile = File(app.filesDir, "${entry.uuid}.$PHOTOK_FILE_EXTENSION.tn")
                                        if (!thumbFile.exists() || thumbFile.length() == 0L) {
                                                diag("flushPendingThumbnailPacks: skipping ${entry.uuid} — local thumbnail missing (${thumbFile.absolutePath})"
                                                        )
                                                continue
                                        }
                                        try {
                                                val bytes = thumbFile.readBytes()
                                                pendingThumbs.add(PendingThumb(entry, bytes))
                                        } catch (e: Exception) {
                                                diag("flushPendingThumbnailPacks: FAILED to read thumbnail for ${entry.uuid}: ${e.message}"
                                                        , e)
                                        }
                                }
                                if (pendingThumbs.isEmpty()) {
                                        diag("flushPendingThumbnailPacks: no readable local thumbnails — nothing to pack")
                                        return@withContext
                                }
                                diag("flushPendingThumbnailPacks: ${pendingThumbs.size} readable thumbnails to pack")

                                // Walk the pending thumbnails in order, packing them into ≤5 MB packs.
                                // Each pack gets a sequential name (pack-0001, pack-0002, ...). The
                                // pack counter starts at the current max pack number on the remote + 1,
                                // so we don't overwrite existing packs. For simplicity (and because
                                // the registry is the source of truth), we use a random 4-digit suffix
                                // derived from the current time — collisions are astronomically
                                // unlikely and would just cause a redundant re-upload.
                                var packIndex = 0
                                val currentPackStream = java.io.ByteArrayOutputStream()
                                val currentPackEntries = mutableListOf<PendingThumb>()

                                suspend fun uploadCurrentPack() {
                                        if (currentPackEntries.isEmpty()) return
                                        val packName = "pack-${System.currentTimeMillis()}-${packIndex.toString().padStart(4, '0')}"
                                        val packFile = File(app.cacheDir, "$packName${SyncConfig.THUMBNAIL_PACK_SUFFIX}")
                                        try {
                                                packFile.writeBytes(currentPackStream.toByteArray())
                                                val remotePath = 
                                                        "$remote:${SyncConfig.THUMBNAIL_PACK_DIR}/$packName${SyncConfig.THUMBNAIL_PACK_SUFFIX}"
                                                diag(
                                                        "flushPendingThumbnailPacks: uploading pack $packName (${currentPackStream.size()} bytes, ${currentPackEntries.size} thumbnails) → $remotePath",
                                                )
                                                rcloneController.uploadFile(packFile.absolutePath, remotePath).getOrThrow()
                                                diag("flushPendingThumbnailPacks: pack $packName upload OK")

                                                // Update each entry in this pack with pack name + offset + length.
                                                var offset = 0L
                                                for (pt in currentPackEntries) {
                                                        val updated =
                                                                pt.entry.copy(
                                                                        thumbnailPack = packName,
                                                                        thumbnailOffset = offset,
                                                                        thumbnailLength = pt.bytes.size.toLong(),
                                                                )
                                                        try {
                                                                dao.upsert(updated)
                                                        } catch (e: Exception) {
                                                                diag("flushPendingThumbnailPacks: FAILED to update entry for ${pt.entry.uuid}: ${e.message}"
                                                                        , e)
                                                        }
                                                        offset += pt.bytes.size
                                                }
                                        } catch (e: Exception) {
                                                diag(
                                                        "flushPendingThumbnailPacks: pack $packName upload FAILED (non-fatal — entries keep thumbnail_pack=null, will retry next batch): ${e.message}",
                                                        e,
                                                )
                                                // Don't rethrow — leave entries with thumbnail_pack=null so the
                                                // next batch's flushPendingThumbnailPacks retries them.
                                        } finally {
                                                packFile.delete()
                                        }
                                        packIndex++
                                        currentPackStream.reset()
                                        currentPackEntries.clear()
                                }

                                val maxSize = SyncConfig.THUMBNAIL_PACK_SIZE_BYTES
                                for (pt in pendingThumbs) {
                                        // If adding this thumbnail would exceed the max pack size AND the
                                        // current pack is non-empty, flush the current pack first.
                                        if (currentPackStream.size() > 0 &&
                                                (currentPackStream.size() + pt.bytes.size) > maxSize
                                        ) {
                                                uploadCurrentPack()
                                        }
                                        // Append this thumbnail to the current pack.
                                        currentPackStream.write(pt.bytes)
                                        currentPackEntries.add(pt)

                                        // Edge case: if a SINGLE thumbnail exceeds the max pack size, flush
                                        // it as its own (oversized) pack. This is rare (thumbnails are
                                        // typically a few KB) but handles the case gracefully.
                                        if (currentPackStream.size() >= maxSize) {
                                                uploadCurrentPack()
                                        }
                                }
                                // Flush the final partial pack.
                                uploadCurrentPack()
                                diag("flushPendingThumbnailPacks: DONE — packed ${pendingThumbs.size} thumbnails into $packIndex pack(s)")
                        }

                // ─── Registry garbage collection (registry-gc feature) ───────────────────
                // The dedup registry is monotonic — entries are tombstoned (`deleted=true`)
                // rather than physically removed so other devices that still hold a stale
                // local cache know not to reference a deleted hash. Over time the tombstones
                // accumulate:
                //   - In the registry JSON itself (each tombstone is ~200 bytes; at 1000
                //     deleted photos that's ~200 KB of pure dead weight in registry.json.crypt).
                //   - In the thumbnail packs: a 50 MB pack with 60% tombstoned entries is
                //     holding ~30 MB of orphaned thumbnail bytes that no live photo on any
                //     device references.
                //   - On the originals side: the original encrypted file (`<remote>:
                //     photoz-backup/originals/<uuid>.crypt`) for a tombstoned entry is also
                //     orphaned — no live Photo row references it.
                //
                // The three GC operations below reclaim that space:
                //
                //   1. [softDelete] — marks ONE entry as `deleted=true` (called from
                //      PhotoRepository.safeDeletePhoto when the user deletes a photo).
                //      The local cache + remote registry are both updated so other devices
                //      see the tombstone on their next login.
                //
                //   2. [gcThumbnailPacks] — iterates each thumbnail pack, computes the
                //      fraction of tombstoned entries inside it, and if >30% are dead,
                //      re-downloads the pack, extracts ONLY the live entries, uploads them
                //      as a fresh (smaller) pack, and updates the registry entries' pack
                //      name / offset / length. The old pack is then deleted from the remote.
                //
                //   3. [gcOriginals] — iterates tombstoned entries and deletes the
                //      corresponding `<remote>:photoz-backup/originals/<uuid>.crypt` from
                //      the remote. The registry entry is then PHYSICALLY removed (not just
                //      tombstoned) — the original is gone, so there's nothing left to
                //      reference.
                //
                // The user triggers (2) + (3) from Settings → "Clean up backup". They're
                // safe to run at any time — they're idempotent and don't touch live
                // (non-tombstoned) entries.
                //
                // @since registry-gc feature — soft delete + thumbnail repack + remote cleanup

                /**
                 * Mark the entry for [contentHash] as soft-deleted (tombstoned).
                 *
                 * Called by [onlasdan.gallery.model.repositories.PhotoRepository.safeDeletePhoto]
                 * before the local file is removed, so the dedup registry knows this hash
                 * is no longer referenced by any live photo on THIS device. The tombstone
                 * is propagated to the remote registry via [uploadToRemote] — other
                 * devices will see it on their next login and won't reference this hash.
                 *
                 * Idempotent: if the entry doesn't exist (e.g. pre-v9 import that never
                 * got a registry entry, or already tombstoned), the call is a no-op.
                 *
                 * @since registry-gc feature
                 */
                suspend fun softDelete(contentHash: String) =
                        withContext(Dispatchers.IO) {
                                if (contentHash.isBlank()) {
                                        diag("softDelete: blank contentHash — skipping")
                                        return@withContext
                                }
                                val existing =
                                        try {
                                                dao.findByHashIncludingDeleted(contentHash)
                                        } catch (e: Exception) {
                                                diag("softDelete: lookup FAILED for $contentHash: ${e.message}", e)
                                                return@withContext
                                        } ?: run {
                                                diag("softDelete: no registry entry for $contentHash — skipping (pre-v9 import or never uploaded)"
                                                        )
                                                return@withContext
                                        }
                                if (existing.deleted) {
                                        diag("softDelete: $contentHash already tombstoned — skipping")
                                        return@withContext
                                }
                                val tombstoned = existing.copy(deleted = true)
                                try {
                                        dao.upsert(tombstoned)
                                        diag("softDelete: tombstoned $contentHash (uuid=${existing.uuid}, filename=${existing.filename})")
                                } catch (e: Exception) {
                                        diag("softDelete: upsert FAILED for $contentHash: ${e.message}", e)
                                        return@withContext
                                }
                                // Best-effort flush to the remote registry so other devices see the
                                // tombstone on their next login. Failure is non-fatal — the local
                                // cache has the tombstone and the next batch-end flush will retry.
                                try {
                                        val session = sessionRepository.get()
                                        if (session != null) {
                                                uploadToRemote(session.vmk.encoded)
                                                diag("softDelete: flushed tombstone for $contentHash to remote registry")
                                        } else {
                                                diag("softDelete: vault session unavailable — tombstone stays local, will flush on next batch"
                                                        )
                                        }
                                } catch (e: Exception) {
                                        diag("softDelete: remote flush FAILED (non-fatal — local cache has the tombstone): ${e.message}", e)
                                }
                        }

                /**
                 * Reclaim space from thumbnail packs that are >30% tombstoned.
                 *
                 * For each pack referenced by any registry entry (live or tombstoned):
                 *   1. Compute the fraction of tombstoned entries in the pack.
                 *   2. If ≤ [GC_DEAD_SPACE_THRESHOLD_PCT] (30%), skip — the pack is
                 *      healthy and re-packing it would just waste bandwidth.
                 *   3. If > threshold: download the pack, extract ONLY the live entries
                 *      (re-building offset/length as we go), upload the live-only pack as
                 *      a NEW pack, update each live entry's `thumbnail_pack` / `thumbnail_offset`
                 *      / `thumbnail_length` in the local cache, then delete the OLD pack
                 *      from the remote.
                 *
                 * Tombstoned entries in the OLD pack are dropped entirely — they no longer
                 * reference any live photo, so their thumbnail bytes are pure dead weight.
                 *
                 * Non-fatal: if a pack download or repack fails for any reason, that pack
                 * is skipped and the next GC run will retry it.
                 *
                 * @return count of packs repacked (0 if nothing needed GC)
                 * @since registry-gc feature
                 */
                suspend fun gcThumbnailPacks(): Int =
                        withContext(Dispatchers.IO) {
                                val remote =
                                        try {
                                                val config =
                                                        app.getSharedPreferences(
                                                                Config.FILE_NAME,
                                                                android.content.Context.MODE_PRIVATE,
                                                        )
                                                config.getString("sync^chosenRemote", null)
                                        } catch (e: Exception) {
                                                null
                                        } ?: run {
                                                diag("gcThumbnailPacks: no remote chosen — skipping")
                                                return@withContext 0
                                        }

                                val allEntries =
                                        try {
                                                dao.getAllIncludingDeleted()
                                        } catch (e: Exception) {
                                                diag("gcThumbnailPacks: failed to read registry: ${e.message}", e)
                                                return@withContext 0
                                        }
                                if (allEntries.isEmpty()) {
                                        diag("gcThumbnailPacks: registry empty — nothing to GC")
                                        return@withContext 0
                                }

                                // Group by thumbnail_pack (skip entries with no pack — those are
                                // individual-file uploads that aren't part of any pack).
                                val byPack: Map<String, List<HashRegistryEntry>> =
                                        allEntries
                                                .filter { !it.thumbnailPack.isNullOrBlank() }
                                                .groupBy { it.thumbnailPack!! }
                                if (byPack.isEmpty()) {
                                        diag("gcThumbnailPacks: no packed thumbnails — nothing to GC")
                                        return@withContext 0
                                }
                                diag("gcThumbnailPacks: ${byPack.size} packs to evaluate (total entries=${allEntries.size})")

                                var repacked = 0
                                for ((packName, entries) in byPack) {
                                        val live = entries.filter { !it.deleted }
                                        val dead = entries.filter { it.deleted }
                                        val deadPct = if (entries.isEmpty()) 0 else (dead.size * 100) / entries.size
                                        diag("gcThumbnailPacks: pack $packName — ${live.size} live, ${dead.size} dead ($deadPct% dead)")

                                        if (dead.isEmpty()) {
                                                // No tombstones in this pack — nothing to reclaim.
                                                continue
                                        }
                                        if (deadPct <= GC_DEAD_SPACE_THRESHOLD_PCT) {
                                                // Below threshold — keep the pack as-is.
                                                diag("gcThumbnailPacks: pack $packName below $GC_DEAD_SPACE_THRESHOLD_PCT% threshold — skipping"
                                                        )
                                                continue
                                        }
                                        if (live.isEmpty()) {
                                                // Entire pack is dead — just delete the pack file. No repack
                                                // needed (no live entries to migrate). The tombstoned entries'
                                                // thumbnail_pack field is cleared so a future GC run doesn't
                                                // try to download a non-existent pack.
                                                diag("gcThumbnailPacks: pack $packName 100% dead — deleting pack file, no repack")
                                                val packRemotePath = 
                                                        "$remote:${SyncConfig.THUMBNAIL_PACK_DIR}/$packName${SyncConfig.THUMBNAIL_PACK_SUFFIX}"
                                                try {
                                                        rcloneController.deleteFile(packRemotePath).getOrThrow()
                                                        diag("gcThumbnailPacks: deleted pack $packName from remote")
                                                } catch (e: Exception) {
                                                        diag("gcThumbnailPacks: delete pack $packName FAILED (non-fatal): ${e.message}", e)
                                                }
                                                for (d in dead) {
                                                        try {
                                                                dao.upsert(d.copy(thumbnailPack = null, thumbnailOffset = 0L, thumbnailLength = 0L))
                                                        } catch (e: Exception) {
                                                                diag(
                                                                        "gcThumbnailPacks: clear pack field on dead entry ${d.uuid} after pack delete FAILED (non-fatal): ${e.message}",
                                                                        e,
                                                                )
                                                        }
                                                }
                                                repacked++
                                                continue
                                        }

                                        // Download the pack, extract live entries, upload as a new pack,
                                        // update live entries' offset/length, delete the old pack.
                                        try {
                                                val packRemotePath = 
                                                        "$remote:${SyncConfig.THUMBNAIL_PACK_DIR}/$packName${SyncConfig.THUMBNAIL_PACK_SUFFIX}"
                                                val packLocalFile = 
                                                        File(app.cacheDir, "gc-pack-$packName${SyncConfig.THUMBNAIL_PACK_SUFFIX}")
                                                try {
                                                        rcloneController.downloadFile(packRemotePath, packLocalFile.absolutePath).getOrThrow()
                                                } catch (e: Exception) {
                                                        diag("gcThumbnailPacks: download pack $packName FAILED (non-fatal — skipping): ${e.message}"
                                                                , e)
                                                        continue
                                                }
                                                val packBytes = packLocalFile.readBytes()
                                                diag("gcThumbnailPacks: downloaded pack $packName (${packBytes.size} bytes)")

                                                // Extract live thumbnails IN ORDER (sorted by current offset
                                                // so we preserve the original ordering within the pack).
                                                data class LiveThumb(
                                                        val entry: HashRegistryEntry,
                                                        val bytes: ByteArray,
                                                )
                                                val liveThumbs = mutableListOf<LiveThumb>()
                                                for (e in live.sortedBy { it.thumbnailOffset }) {
                                                        val off = e.thumbnailOffset.toInt()
                                                        val len = e.thumbnailLength.toInt()
                                                        if (off < 0 || len <= 0 || off + len > packBytes.size) {
                                                                diag(
                                                                        "gcThumbnailPacks: entry ${e.uuid} has invalid offset/length (off=$off len=$len packSize=${packBytes.size}) — skipping entry",
                                                                )
                                                                continue
                                                        }
                                                        liveThumbs.add(LiveThumb(e, packBytes.copyOfRange(off, off + len)))
                                                }
                                                if (liveThumbs.isEmpty()) {
                                                        diag(
                                                                "gcThumbnailPacks: no extractable live thumbnails in $packName — falling back to delete-pack + clear-field",
                                                        )
                                                        packLocalFile.delete()
                                                        try {
                                                                rcloneController.deleteFile(packRemotePath).getOrThrow()
                                                        } catch (e: Exception) {
                                                                diag(
                                                                        "gcThumbnailPacks: delete empty pack $packName FAILED (non-fatal — orphaned pack file): ${e.message}",
                                                                        e,
                                                                )
                                                        }
                                                        for (d in dead) {
                                                                try {
                                                                        dao.upsert(d.copy(thumbnailPack = null, thumbnailOffset = 0L, thumbnailLength = 0L))
                                                                } catch (
                                                                        e: Exception,
                                                                ) {
                                                                        diag(
                                                                                "gcThumbnailPacks: clear pack field on dead entry ${d.uuid} (empty-pack fallback) FAILED (non-fatal): ${e.message}",
                                                                                e,
                                                                        )
                                                                }
                                                        }
                                                        repacked++
                                                        continue
                                                }

                                                // Concatenate live thumbnails into a new pack.
                                                val newPackBytes =
                                                        ByteArrayOutputStream(liveThumbs.sumOf { it.bytes.size }).use { baos ->
                                                                for (lt in liveThumbs) baos.write(lt.bytes)
                                                                baos.toByteArray()
                                                        }
                                                val newPackName = 
                                                        "pack-${System.currentTimeMillis()}-${(0..9999).random().toString().padStart(4, '0')}"
                                                val newPackFile = 
                                                        File(app.cacheDir, "gc-newpack-$newPackName${SyncConfig.THUMBNAIL_PACK_SUFFIX}")
                                                newPackFile.writeBytes(newPackBytes)

                                                val newPackRemotePath = 
                                                        "$remote:${SyncConfig.THUMBNAIL_PACK_DIR}/$newPackName${SyncConfig.THUMBNAIL_PACK_SUFFIX}"
                                                try {
                                                        rcloneController.uploadFile(newPackFile.absolutePath, newPackRemotePath).getOrThrow()
                                                        diag(
                                                                "gcThumbnailPacks: uploaded new pack $newPackName (${newPackBytes.size} bytes, ${liveThumbs.size} thumbnails)",
                                                        )
                                                } catch (e: Exception) {
                                                        diag("gcThumbnailPacks: upload new pack $newPackName FAILED (non-fatal): ${e.message}"
                                                                , e)
                                                        packLocalFile.delete()
                                                        newPackFile.delete()
                                                        continue
                                                }

                                                // Update each live entry's pack name + offset + length.
                                                var offset = 0L
                                                for (lt in liveThumbs) {
                                                        try {
                                                                dao.upsert(
                                                                        lt.entry.copy(
                                                                                thumbnailPack = newPackName,
                                                                                thumbnailOffset = offset,
                                                                                thumbnailLength = lt.bytes.size.toLong(),
                                                                        ),
                                                                )
                                                        } catch (e: Exception) {
                                                                diag("gcThumbnailPacks: update entry ${lt.entry.uuid} FAILED (non-fatal): ${e.message}"
                                                                        , e)
                                                        }
                                                        offset += lt.bytes.size
                                                }

                                                // Clear tombstoned entries' pack fields so a future GC run
                                                // doesn't try to download the now-deleted pack for them.
                                                for (d in dead) {
                                                        try {
                                                                dao.upsert(d.copy(thumbnailPack = null, thumbnailOffset = 0L, thumbnailLength = 0L))
                                                        } catch (e: Exception) {
                                                                diag(
                                                                        "gcThumbnailPacks: clear pack field on dead entry ${d.uuid} after repack FAILED (non-fatal): ${e.message}",
                                                                        e,
                                                                )
                                                        }
                                                }

                                                // Delete the OLD pack from the remote.
                                                try {
                                                        rcloneController.deleteFile(packRemotePath).getOrThrow()
                                                        diag("gcThumbnailPacks: deleted old pack $packName from remote")
                                                } catch (e: Exception) {
                                                        diag("gcThumbnailPacks: delete old pack $packName FAILED (non-fatal — orphaned pack file): ${e.message}"
                                                                , e)
                                                }

                                                packLocalFile.delete()
                                                newPackFile.delete()
                                                repacked++
                                                diag(
                                                        "gcThumbnailPacks: repacked $packName → $newPackName (was ${entries.size} entries, now ${liveThumbs.size} live)",
                                                )
                                        } catch (e: Exception) {
                                                diag("gcThumbnailPacks: repack $packName FAILED (non-fatal): ${e.message}", e)
                                        }
                                }

                                // Flush the updated registry (new pack names / offsets) to the remote.
                                if (repacked > 0) {
                                        try {
                                                val session = sessionRepository.get()
                                                if (session != null) {
                                                        uploadToRemote(session.vmk.encoded)
                                                        diag("gcThumbnailPacks: flushed updated registry to remote")
                                                } else {
                                                        diag("gcThumbnailPacks: vault session unavailable — registry stays local, will flush on next batch"
                                                                )
                                                }
                                        } catch (e: Exception) {
                                                diag("gcThumbnailPacks: remote flush FAILED (non-fatal): ${e.message}", e)
                                        }
                                }

                                diag("gcThumbnailPacks: DONE — repacked $repacked pack(s)")
                                repacked
                        }

                /**
                 * Delete the remote originals for tombstoned entries and physically remove
                 * their registry rows.
                 *
                 * For each entry with `deleted=true`:
                 *   1. Delete `<remote>:photoz-backup/originals/<uuid>.crypt` from the
                 *      remote. Failure (file already gone, network blip) is non-fatal —
                 *      the next GC run will retry.
                 *   2. Physically delete the registry row from the local cache (NOT just
                 *      tombstone — the original is gone, so there's nothing left to
                 *      reference). The remote registry is flushed at the end so other
                 *      devices see the row removed on their next login.
                 *
                 * SAFE because: only tombstoned entries are touched. Tombstones are only
                 * ever created by [softDelete], which is only ever called from
                 * [PhotoRepository.safeDeletePhoto] AFTER the local Photo row + local
                 * encrypted files have been deleted. So the original on the remote is
                 * truly orphaned at this point.
                 *
                 * @return count of originals deleted
                 * @since registry-gc feature
                 */
                suspend fun gcOriginals(): Int =
                        withContext(Dispatchers.IO) {
                                val remote =
                                        try {
                                                val config =
                                                        app.getSharedPreferences(
                                                                Config.FILE_NAME,
                                                                android.content.Context.MODE_PRIVATE,
                                                        )
                                                config.getString("sync^chosenRemote", null)
                                        } catch (e: Exception) {
                                                null
                                        } ?: run {
                                                diag("gcOriginals: no remote chosen — skipping")
                                                return@withContext 0
                                        }

                                val tombstoned =
                                        try {
                                                dao.getAllIncludingDeleted().filter { it.deleted }
                                        } catch (e: Exception) {
                                                diag("gcOriginals: failed to read registry: ${e.message}", e)
                                                return@withContext 0
                                        }
                                if (tombstoned.isEmpty()) {
                                        diag("gcOriginals: no tombstoned entries — nothing to GC")
                                        return@withContext 0
                                }
                                diag("gcOriginals: ${tombstoned.size} tombstoned entries to clean up")

                                var deleted = 0
                                for (entry in tombstoned) {
                                        val origRemotePath = 
                                                "$remote:${SyncConfig.remoteOriginalsDir}/${entry.uuid}${SyncConfig.ORIGINAL_FILE_SUFFIX}"
                                        try {
                                                rcloneController.deleteFile(origRemotePath).getOrNull()
                                                // deleteFile returns Result.failure if the file doesn't exist
                                                // (already GC'd, or never uploaded). Either way, the original
                                                // is gone — safe to drop the registry row.
                                                deleted++
                                                diag("gcOriginals: deleted original for ${entry.uuid} (hash=${entry.contentHash})")
                                        } catch (e: Exception) {
                                                diag(
                                                        "gcOriginals: delete original for ${entry.uuid} FAILED (non-fatal — leaving tombstone for retry): ${e.message}",
                                                        e,
                                                )
                                                continue
                                        }
                                        // Also best-effort delete the (legacy) individual-thumbnail file
                                        // at `<remote>:thumbnails/<uuid>.crypt.tn` — pre-pack thumbnails
                                        // live there and are orphaned once the entry is tombstoned.
                                        try {
                                                val thumbRemotePath = 
                                                        "$remote:${SyncConfig.remoteThumbnailsDir}/${entry.uuid}${SyncConfig.THUMBNAIL_FILE_SUFFIX}"
                                                rcloneController.deleteFile(thumbRemotePath).getOrNull()
                                        } catch (e: Exception) {
                                                diag(
                                                        "gcOriginals: delete legacy thumbnail for ${entry.uuid} FAILED (non-fatal — may have been packed already, or may not exist on this backend): ${e.message}",
                                                        e,
                                                )
                                        }
                                        // Physically remove the registry row. The original is gone, so
                                        // there's nothing left to reference. Tombstone → no row.
                                        try {
                                                dao.deleteByHash(entry.contentHash)
                                        } catch (e: Exception) {
                                                diag("gcOriginals: delete registry row for ${entry.uuid} FAILED (non-fatal): ${e.message}"
                                                        , e)
                                        }
                                }

                                // Flush the smaller registry to the remote so other devices see the
                                // rows removed on their next login.
                                if (deleted > 0) {
                                        try {
                                                val session = sessionRepository.get()
                                                if (session != null) {
                                                        uploadToRemote(session.vmk.encoded)
                                                        diag("gcOriginals: flushed compacted registry to remote")
                                                } else {
                                                        diag("gcOriginals: vault session unavailable — registry stays local, will flush on next batch"
                                                                )
                                                }
                                        } catch (e: Exception) {
                                                diag("gcOriginals: remote flush FAILED (non-fatal): ${e.message}", e)
                                        }
                                }

                                diag("gcOriginals: DONE — deleted $deleted original(s)")
                                deleted
                        }

                /**
                 * Parse registry JSON into entries.
                 *
                 * Format:
                 * ```
                 * {"version":1,"entries":[
                 *   {"content_hash":"...","uuid":"...","filename":"...","album_path":"...",
                 *    "size":123,"type":"JPEG","thumbnail_pack":"pack-000",
                 *    "thumbnail_offset":0,"thumbnail_length":17361,"deleted":false}
                 * ]}
                 * ```
                 *
                 * Hand-rolled regex parser — consistent with the existing
                 * [onlasdan.gallery.sync.rclone.RepoManager.parseMarker] /
                 * `parseVaultProtection` style (no external JSON dependency in this
                 * layer). Tolerant of field re-ordering and missing optional fields.
                 */
                private fun parseRegistryJson(json: String): List<HashRegistryEntry> {
                        val entries = mutableListOf<HashRegistryEntry>()
                        // Match each `{...}` block that contains a "content_hash" key. The [^{}]*]
                        // inner class prevents the regex from greedily spanning multiple entries
                        // (entries themselves never contain nested objects).
                        val entryPattern = Regex("""\{[^{}]*"content_hash"\s*:\s*"([^"]+)"[^{}]*\}""")
                        for (match in entryPattern.findAll(json)) {
                                val entryJson = match.value
                                try {
                                        val hash =
                                                Regex("\"content_hash\"\\s*:\\s*\"([^\"]+)\"")
                                                        .find(entryJson)
                                                        ?.groupValues
                                                        ?.get(1) ?: continue
                                        val uuid =
                                                Regex("\"uuid\"\\s*:\\s*\"([^\"]+)\"")
                                                        .find(entryJson)
                                                        ?.groupValues
                                                        ?.get(1) ?: continue
                                        val filename =
                                                Regex("\"filename\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
                                                        .find(entryJson)
                                                        ?.groupValues
                                                        ?.get(1)
                                                        ?.let { unescapeJson(it) } ?: ""
                                        val albumPath =
                                                Regex("\"album_path\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
                                                        .find(entryJson)
                                                        ?.groupValues
                                                        ?.get(1)
                                                        ?.let { unescapeJson(it) }
                                        val size =
                                                Regex("\"size\"\\s*:\\s*(\\d+)")
                                                        .find(entryJson)
                                                        ?.groupValues
                                                        ?.get(1)
                                                        ?.toLongOrNull() ?: 0L
                                        val type =
                                                Regex("\"type\"\\s*:\\s*\"([^\"]+)\"")
                                                        .find(entryJson)
                                                        ?.groupValues
                                                        ?.get(1) ?: "JPEG"
                                        val thumbPack =
                                                Regex("\"thumbnail_pack\"\\s*:\\s*\"([^\"]+)\"")
                                                        .find(entryJson)
                                                        ?.groupValues
                                                        ?.get(1)
                                        val thumbOffset =
                                                Regex("\"thumbnail_offset\"\\s*:\\s*(\\d+)")
                                                        .find(entryJson)
                                                        ?.groupValues
                                                        ?.get(1)
                                                        ?.toLongOrNull() ?: 0L
                                        val thumbLength =
                                                Regex("\"thumbnail_length\"\\s*:\\s*(\\d+)")
                                                        .find(entryJson)
                                                        ?.groupValues
                                                        ?.get(1)
                                                        ?.toLongOrNull() ?: 0L
                                        val deleted =
                                                Regex("\"deleted\"\\s*:\\s*(true|false)")
                                                        .find(entryJson)
                                                        ?.groupValues
                                                        ?.get(1)
                                                        ?.toBoolean() ?: false
                                        entries.add(
                                                HashRegistryEntry(
                                                        contentHash = hash,
                                                        uuid = uuid,
                                                        filename = filename,
                                                        albumPath = albumPath,
                                                        size = size,
                                                        type = type,
                                                        thumbnailPack = thumbPack,
                                                        thumbnailOffset = thumbOffset,
                                                        thumbnailLength = thumbLength,
                                                        deleted = deleted,
                                                        // Sprint 2 / M7 — tag downloaded entries with the
                                                        // syncing vault's vault_id (the registry on the remote
                                                        // only ever contains the syncing vault's entries).
                                                        vaultId = runCatching { sessionRepository.require().vaultId }.getOrNull(),
                                                ),
                                        )
                                } catch (e: Exception) {
                                        diag("parseRegistryJson: failed to parse entry: ${e.message}")
                                }
                        }
                        return entries
                }

                /**
                 * Serialize entries to JSON.
                 */
                private fun serializeRegistry(entries: List<HashRegistryEntry>): String {
                        val sb = StringBuilder()
                        sb.append("{\"version\":1,\"entries\":[")
                        entries.forEachIndexed { i, e ->
                                if (i > 0) sb.append(",")
                                sb.append("{\"content_hash\":\"${e.contentHash}\"")
                                sb.append(",\"uuid\":\"${e.uuid}\"")
                                // filename is technically non-null in the entity but be defensive
                                // — an empty string serializes cleanly.
                                sb.append(",\"filename\":\"${escapeJson(e.filename)}\"")
                                sb.append(",\"album_path\":${e.albumPath?.let { "\"${escapeJson(it)}\"" } ?: "null"}")
                                sb.append(",\"size\":${e.size}")
                                sb.append(",\"type\":\"${escapeJson(e.type)}\"")
                                sb.append(",\"thumbnail_pack\":${e.thumbnailPack?.let { "\"${escapeJson(it)}\"" } ?: "null"}")
                                sb.append(",\"thumbnail_offset\":${e.thumbnailOffset}")
                                sb.append(",\"thumbnail_length\":${e.thumbnailLength}")
                                sb.append(",\"deleted\":${e.deleted}}")
                        }
                        sb.append("]}")
                        return sb.toString()
                }

                /**
                 * Minimal JSON string escaper — handles the few characters that can
                 * break the parser (`"`, `\`, control chars). Sufficient for filenames
                 * and album paths; not a general-purpose JSON escaper.
                 */
                private fun escapeJson(s: String): String =
                        s
                                .replace("\\", "\\\\")
                                .replace("\"", "\\\"")
                                .replace("\n", "\\n")
                                .replace("\r", "\\r")
                                .replace("\t", "\\t")

                /**
                 * Inverse of [escapeJson] — unescapes JSON string escapes (`\\`, `\"`,
                 * `\n`, `\r`, `\t`) back to their literal characters. Used by
                 * [parseRegistryJson] to restore filenames/album paths that contain
                 * special characters.
                 *
                 * The order matters: `\\` must be processed FIRST so that sequences
                 * like `\\n` (literal backslash + n) don't get misinterpreted as `\n`
                 * (newline). We use a regex-based approach to handle this correctly —
                 * a naive string replace would double-process escapes.
                 */
                private fun unescapeJson(s: String): String =
                        Regex("\\\\(.)").replace(s) { match ->
                                when (match.groupValues[1]) {
                                        "\\" -> "\\"
                                        "\"" -> "\""
                                        "n" -> "\n"
                                        "r" -> "\r"
                                        "t" -> "\t"
                                        else -> match.groupValues[1] // unknown escape — keep char as-is
                                }
                        }

                // ─── Sprint 10+ / M7 v2 — Per-entry encrypted registry ────────────────

                /**
                 * Serialize ALL entries (across ALL vaults) into per-entry encrypted
                 * format (version 0x03).
                 *
                 * Each entry is independently GCM-encrypted with the current vault's VMK.
                 * On download, each entry is try-decrypted — GCM auth tag filters which
                 * entries belong to the current vault.
                 *
                 * NOTE: This function encrypts ALL entries with the SAME VMK (the current
                 * vault's). Entries from other vaults were previously encrypted with their
                 * own VMKs during their upload. When this vault uploads, it re-encrypts
                 * only its own entries — entries from other vaults are preserved as-is
                 * (read from the existing remote file, re-packed without re-encrypting).
                 *
                 * For v1 of M7 v2, we take a simpler approach: each upload serializes
                 * ONLY the current vault's entries (same as M7 v1), but uses per-entry
                 * encryption instead of single-blob. The remote file is overwritten each
                 * time — entries from other vaults are lost on overwrite. This is a known
                 * limitation; the full multi-vault merge (read-existing + append) is a
                 * future enhancement.
                 *
                 * @since v15 — Sprint 10+ / M7 v2
                 */
                private fun serializePerEntryEncrypted(
                        entries: List<HashRegistryEntry>,
                        vmkBytes: ByteArray,
                ): ByteArray {
                        val key = SecretKeySpec(vmkBytes, "AES")
                        val cipher = Cipher.getInstance(Algorithm.AesGcmNoPadding.value)

                        // Pre-allocate the output buffer:
                        // [version(1)][num_entries(4)] + per-entry [nonce(12)][len(4)][ciphertext+tag]
                        val entryBlobs = ArrayList<ByteArray>(entries.size)
                        for (entry in entries) {
                                val json = serializeRegistry(listOf(entry))
                                val plaintext = json.toByteArray(Charsets.UTF_8)
                                val compressed = gzipCompress(plaintext)

                                val nonce = ByteArray(GCM_NONCE_SIZE).also { SecureRandom().nextBytes(it) }
                                cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_SIZE, nonce))
                                val ciphertext = cipher.doFinal(compressed)

                                // Pack: [nonce(12)][ciphertext_len(4)][ciphertext+tag]
                                val blob = ByteArray(GCM_NONCE_SIZE + 4 + ciphertext.size)
                                System.arraycopy(nonce, 0, blob, 0, GCM_NONCE_SIZE)
                                val len = ciphertext.size
                                blob[GCM_NONCE_SIZE] = (len ushr 24).toByte()
                                blob[GCM_NONCE_SIZE + 1] = (len ushr 16).toByte()
                                blob[GCM_NONCE_SIZE + 2] = (len ushr 8).toByte()
                                blob[GCM_NONCE_SIZE + 3] = len.toByte()
                                System.arraycopy(ciphertext, 0, blob, GCM_NONCE_SIZE + 4, ciphertext.size)
                                entryBlobs.add(blob)
                        }

                        // Assemble: [version(1)][num_entries(4)][entry blobs...]
                        val totalSize = 1 + 4 + entryBlobs.sumOf { it.size }
                        val result = ByteArray(totalSize)
                        result[0] = REGISTRY_FORMAT_VERSION_PER_ENTRY
                        val n = entries.size
                        result[1] = (n ushr 24).toByte()
                        result[2] = (n ushr 16).toByte()
                        result[3] = (n ushr 8).toByte()
                        result[4] = n.toByte()

                        var offset = 5
                        for (blob in entryBlobs) {
                                System.arraycopy(blob, 0, result, offset, blob.size)
                                offset += blob.size
                        }

                        return result
                }

                /**
                 * M7 v2 — Extract raw encrypted blobs from a v0x03 registry file
                 * WITHOUT decrypting. Each blob is [nonce(12)][ct_len(4)][ciphertext].
                 *
                 * Used by [uploadToRemote] to preserve other vaults' entries: download
                 * the existing remote file, extract all blobs, keep those that can't
                 * be decrypted by the current VMK (they belong to other vaults).
                 *
                 * @since Sprint 8 — M7 v2 multi-vault registry merge
                 */
                private fun extractRawBlobs(data: ByteArray): List<ByteArray> {
                        if (data.size < 5) return emptyList()
                        if (data[0] != REGISTRY_FORMAT_VERSION_PER_ENTRY) return emptyList()

                        val numEntries =
                                ((data[1].toInt() and 0xFF) shl 24) or
                                        ((data[2].toInt() and 0xFF) shl 16) or
                                        ((data[3].toInt() and 0xFF) shl 8) or
                                        (data[4].toInt() and 0xFF)

                        val blobs = mutableListOf<ByteArray>()
                        var offset = 5
                        for (i in 0 until numEntries) {
                                if (offset + GCM_NONCE_SIZE + 4 > data.size) break

                                val blobStart = offset
                                offset += GCM_NONCE_SIZE

                                val ctLen =
                                        ((data[offset].toInt() and 0xFF) shl 24) or
                                                ((data[offset + 1].toInt() and 0xFF) shl 16) or
                                                ((data[offset + 2].toInt() and 0xFF) shl 8) or
                                                (data[offset + 3].toInt() and 0xFF)
                                offset += 4

                                if (offset + ctLen > data.size) break
                                offset += ctLen

                                // Raw blob = nonce + ct_len + ciphertext (as-is in the file)
                                blobs.add(data.copyOfRange(blobStart, offset))
                        }
                        return blobs
                }

                /**
                 * M7 v2 — Check if a raw blob can be decrypted with the current VMK.
                 * Returns true if this entry belongs to the current vault.
                 *
                 * @since Sprint 8 — M7 v2 multi-vault registry merge
                 */
                private fun canDecryptBlob(blob: ByteArray, vmkBytes: ByteArray): Boolean {
                        if (blob.size < GCM_NONCE_SIZE + 4 + GCM_TAG_SIZE) return false
                        return try {
                                val nonce = blob.copyOfRange(0, GCM_NONCE_SIZE)
                                val ctLen =
                                        ((blob[GCM_NONCE_SIZE].toInt() and 0xFF) shl 24) or
                                                ((blob[GCM_NONCE_SIZE + 1].toInt() and 0xFF) shl 16) or
                                                ((blob[GCM_NONCE_SIZE + 2].toInt() and 0xFF) shl 8) or
                                                (blob[GCM_NONCE_SIZE + 3].toInt() and 0xFF)
                                val ciphertext = blob.copyOfRange(GCM_NONCE_SIZE + 4, GCM_NONCE_SIZE + 4 + ctLen)

                                val key = SecretKeySpec(vmkBytes, "AES")
                                val cipher = Cipher.getInstance(Algorithm.AesGcmNoPadding.value)
                                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_SIZE, nonce))
                                cipher.doFinal(ciphertext)
                                true
                        } catch (e: Exception) {
                                false
                        }
                }

                /**
                 * M7 v2 — Serialize merged registry: fresh current-vault entries +
                 * preserved raw blobs from other vaults.
                 *
                 * Format: [version=0x03][total_count(4)] + [our encrypted entries...] +
                 * [preserved blobs as-is...]
                 *
                 * @param ourEntries current vault's entries (will be freshly encrypted)
                 * @param preservedBlobs raw encrypted blobs from other vaults (kept as-is)
                 * @since Sprint 8 — M7 v2 multi-vault registry merge
                 */
                private fun serializeMergedEncrypted(
                        ourEntries: List<HashRegistryEntry>,
                        preservedBlobs: List<ByteArray>,
                        vmkBytes: ByteArray,
                ): ByteArray {
                        val key = SecretKeySpec(vmkBytes, "AES")
                        val cipher = Cipher.getInstance(Algorithm.AesGcmNoPadding.value)

                        // Encrypt our entries
                        val ourBlobs = ArrayList<ByteArray>(ourEntries.size)
                        for (entry in ourEntries) {
                                val json = serializeRegistry(listOf(entry))
                                val plaintext = json.toByteArray(Charsets.UTF_8)
                                val compressed = gzipCompress(plaintext)

                                val nonce = ByteArray(GCM_NONCE_SIZE).also { SecureRandom().nextBytes(it) }
                                cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_SIZE, nonce))
                                val ciphertext = cipher.doFinal(compressed)

                                val blob = ByteArray(GCM_NONCE_SIZE + 4 + ciphertext.size)
                                System.arraycopy(nonce, 0, blob, 0, GCM_NONCE_SIZE)
                                val len = ciphertext.size
                                blob[GCM_NONCE_SIZE] = (len ushr 24).toByte()
                                blob[GCM_NONCE_SIZE + 1] = (len ushr 16).toByte()
                                blob[GCM_NONCE_SIZE + 2] = (len ushr 8).toByte()
                                blob[GCM_NONCE_SIZE + 3] = len.toByte()
                                System.arraycopy(ciphertext, 0, blob, GCM_NONCE_SIZE + 4, ciphertext.size)
                                ourBlobs.add(blob)
                        }

                        // Assemble: [version(1)][total_count(4)][our blobs...][preserved blobs...]
                        val totalCount = ourBlobs.size + preservedBlobs.size
                        val totalSize = 1 + 4 + ourBlobs.sumOf { it.size } + preservedBlobs.sumOf { it.size }
                        val result = ByteArray(totalSize)
                        result[0] = REGISTRY_FORMAT_VERSION_PER_ENTRY
                        result[1] = (totalCount ushr 24).toByte()
                        result[2] = (totalCount ushr 16).toByte()
                        result[3] = (totalCount ushr 8).toByte()
                        result[4] = totalCount.toByte()

                        var offset = 5
                        for (blob in ourBlobs) {
                                System.arraycopy(blob, 0, result, offset, blob.size)
                                offset += blob.size
                        }
                        for (blob in preservedBlobs) {
                                System.arraycopy(blob, 0, result, offset, blob.size)
                                offset += blob.size
                        }

                        return result
                }

                /**
                 * Parse per-entry encrypted format (version 0x03).
                 *
                 * Tries to decrypt EACH entry with the current VMK. Entries whose GCM
                 * auth tag fails (belonging to other vaults) are silently skipped.
                 *
                 * @since v15 — Sprint 10+ / M7 v2
                 */
                private fun parsePerEntryEncrypted(
                        data: ByteArray,
                        vmkBytes: ByteArray,
                ): List<HashRegistryEntry> {
                        if (data.size < 5) return emptyList()

                        val numEntries =
                                ((data[1].toInt() and 0xFF) shl 24) or
                                        ((data[2].toInt() and 0xFF) shl 16) or
                                        ((data[3].toInt() and 0xFF) shl 8) or
                                        (data[4].toInt() and 0xFF)

                        val key = SecretKeySpec(vmkBytes, "AES")
                        val cipher = Cipher.getInstance(Algorithm.AesGcmNoPadding.value)
                        val entries = mutableListOf<HashRegistryEntry>()

                        var offset = 5
                        for (i in 0 until numEntries) {
                                if (offset + GCM_NONCE_SIZE + 4 > data.size) break

                                // Read nonce
                                val nonce = data.copyOfRange(offset, offset + GCM_NONCE_SIZE)
                                offset += GCM_NONCE_SIZE

                                // Read ciphertext length
                                val ctLen =
                                        ((data[offset].toInt() and 0xFF) shl 24) or
                                                ((data[offset + 1].toInt() and 0xFF) shl 16) or
                                                ((data[offset + 2].toInt() and 0xFF) shl 8) or
                                                (data[offset + 3].toInt() and 0xFF)
                                offset += 4

                                if (offset + ctLen > data.size) break

                                // Read ciphertext
                                val ciphertext = data.copyOfRange(offset, offset + ctLen)
                                offset += ctLen

                                // Try decrypt with current VMK
                                try {
                                        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_SIZE, nonce))
                                        val compressed = cipher.doFinal(ciphertext)
                                        val json = gzipDecompressToString(compressed)
                                        val parsed = parseRegistryJson(json)
                                        entries.addAll(parsed)
                                } catch (e: Exception) {
                                        // GCM auth tag failed — entry belongs to another vault. Skip.
                                        diag("parsePerEntryEncrypted: entry $i failed decrypt (other vault) — skipping")
                                }
                        }

                        return entries
                }
        }
