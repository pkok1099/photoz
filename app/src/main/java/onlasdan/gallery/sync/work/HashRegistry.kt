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
import onlasdan.gallery.encryption.domain.models.Algorithm
import onlasdan.gallery.sync.domain.SyncConfig
import onlasdan.gallery.sync.rclone.RcloneController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.SecureRandom
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
class HashRegistry @Inject constructor(
    private val app: Application,
    private val rcloneController: RcloneController,
    private val dao: HashRegistryDao,
) {
    companion object {
        private const val TAG = "RcloneDiag"
        private const val REGISTRY_REMOTE_PATH = "${SyncConfig.REPO_DIR}/registry.json.crypt"
        private const val GCM_NONCE_SIZE = 12 // bytes (96 bits, standard for GCM)
        private const val GCM_TAG_SIZE = 128 // bits
    }

    private fun diag(msg: String, t: Throwable? = null) {
        Log.e(TAG, "[HashRegistry] $msg", t)
        try {
            File(app.filesDir, "sync_log.txt").appendText("\n[$TAG] [HashRegistry] $msg\n")
            if (t != null) File(app.filesDir, "sync_log.txt").appendText(t.stackTraceToString() + "\n")
        } catch (_: Exception) {}
    }

    /**
     * Check if a content hash already exists in the local registry cache.
     * Returns the existing entry if found, null if not (new file).
     */
    suspend fun findExisting(contentHash: String): HashRegistryEntry? = withContext(Dispatchers.IO) {
        dao.findByHash(contentHash)
    }

    /**
     * Add a new entry to the local registry cache.
     */
    suspend fun addEntry(entry: HashRegistryEntry) = withContext(Dispatchers.IO) {
        dao.upsert(entry)
    }

    /**
     * Download the encrypted registry from the remote, decrypt it, and cache all entries
     * in the local Room DB. Called during login.
     *
     * @param vmkBytes The raw VMK key bytes (from `SessionRepository.get().vmk.encoded`)
     * @return count of entries loaded, or 0 if registry doesn't exist yet (new repo)
     */
    suspend fun downloadAndCache(vmkBytes: ByteArray): Int = withContext(Dispatchers.IO) {
        val remote = try {
            val config = app.getSharedPreferences(
                "onlasdan.gallery_preferences",
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
                diag("downloadAndCache: registry not on remote (new repo or pre-dedup feature) — treating as 0 entries")
                return@withContext 0
            }

            val encryptedData = tempFile.readBytes()
            if (encryptedData.size < GCM_NONCE_SIZE) {
                diag("downloadAndCache: registry file too small (${encryptedData.size} bytes) — treating as empty")
                return@withContext 0
            }

            // Decrypt: [nonce(12)][ciphertext+tag]
            val nonce = encryptedData.copyOfRange(0, GCM_NONCE_SIZE)
            val ciphertext = encryptedData.copyOfRange(GCM_NONCE_SIZE, encryptedData.size)

            val key = SecretKeySpec(vmkBytes, "AES")
            val cipher = Cipher.getInstance(Algorithm.AesGcmNoPadding.value)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_SIZE, nonce))
            val plaintext = cipher.doFinal(ciphertext)

            val json = String(plaintext, Charsets.UTF_8)
            diag("downloadAndCache: decrypted registry (${plaintext.size} bytes)")

            val entries = parseRegistryJson(json)
            diag("downloadAndCache: parsed ${entries.size} entries")

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
    suspend fun uploadToRemote(vmkBytes: ByteArray) = withContext(Dispatchers.IO) {
        val remote = try {
            val config = app.getSharedPreferences(
                "onlasdan.gallery_preferences",
                android.content.Context.MODE_PRIVATE,
            )
            config.getString("sync^chosenRemote", null)
        } catch (e: Exception) {
            null
        } ?: run {
            diag("uploadToRemote: no remote chosen — skipping")
            return@withContext
        }

        val entries = dao.getAll()
        diag("uploadToRemote: serializing ${entries.size} entries")

        val json = serializeRegistry(entries)
        val plaintext = json.toByteArray(Charsets.UTF_8)

        // Encrypt with AES-256-GCM: [nonce(12)][ciphertext+tag]
        val nonce = ByteArray(GCM_NONCE_SIZE).also { SecureRandom().nextBytes(it) }
        val key = SecretKeySpec(vmkBytes, "AES")
        val cipher = Cipher.getInstance(Algorithm.AesGcmNoPadding.value)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_SIZE, nonce))
        val ciphertext = cipher.doFinal(plaintext)

        val encryptedData = ByteArray(GCM_NONCE_SIZE + ciphertext.size)
        System.arraycopy(nonce, 0, encryptedData, 0, GCM_NONCE_SIZE)
        System.arraycopy(ciphertext, 0, encryptedData, GCM_NONCE_SIZE, ciphertext.size)

        val tempFile = File(app.cacheDir, "registry-upload-${System.currentTimeMillis()}.crypt")
        try {
            tempFile.writeBytes(encryptedData)
            val remotePath = "$remote:$REGISTRY_REMOTE_PATH"
            diag("uploadToRemote: uploading ${encryptedData.size} bytes → $remotePath")
            rcloneController.uploadFile(tempFile.absolutePath, remotePath).getOrThrow()
            diag("uploadToRemote: OK")
        } finally {
            tempFile.delete()
        }
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
                val hash = Regex("\"content_hash\"\\s*:\\s*\"([^\"]+)\"")
                    .find(entryJson)?.groupValues?.get(1) ?: continue
                val uuid = Regex("\"uuid\"\\s*:\\s*\"([^\"]+)\"")
                    .find(entryJson)?.groupValues?.get(1) ?: continue
                val filename = Regex("\"filename\"\\s*:\\s*\"([^\"]*)\"")
                    .find(entryJson)?.groupValues?.get(1) ?: ""
                val albumPath = Regex("\"album_path\"\\s*:\\s*\"([^\"]+)\"")
                    .find(entryJson)?.groupValues?.get(1)
                val size = Regex("\"size\"\\s*:\\s*(\\d+)")
                    .find(entryJson)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                val type = Regex("\"type\"\\s*:\\s*\"([^\"]+)\"")
                    .find(entryJson)?.groupValues?.get(1) ?: "JPEG"
                val thumbPack = Regex("\"thumbnail_pack\"\\s*:\\s*\"([^\"]+)\"")
                    .find(entryJson)?.groupValues?.get(1)
                val thumbOffset = Regex("\"thumbnail_offset\"\\s*:\\s*(\\d+)")
                    .find(entryJson)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                val thumbLength = Regex("\"thumbnail_length\"\\s*:\\s*(\\d+)")
                    .find(entryJson)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                val deleted = Regex("\"deleted\"\\s*:\\s*(true|false)")
                    .find(entryJson)?.groupValues?.get(1)?.toBoolean() ?: false
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
                    )
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
        s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
}
