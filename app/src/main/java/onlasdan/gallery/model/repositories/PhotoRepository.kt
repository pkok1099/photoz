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

package onlasdan.gallery.model.repositories

import android.app.Application
import android.net.Uri
import onlasdan.gallery.io.IO
import onlasdan.gallery.io.VaultFileStorage
import onlasdan.gallery.model.database.dao.AlbumDao
import onlasdan.gallery.model.database.dao.PhotoDao
import onlasdan.gallery.model.database.entity.Photo
import onlasdan.gallery.model.database.entity.PhotoType
import onlasdan.gallery.model.io.CreateThumbnailsUseCase
import onlasdan.gallery.other.extensions.empty
import onlasdan.gallery.other.extensions.lazyClose
import onlasdan.gallery.other.getMetadataFor
import onlasdan.gallery.settings.data.Config
import onlasdan.gallery.sort.domain.Sort
import onlasdan.gallery.sync.domain.SyncConfig
import onlasdan.gallery.sync.work.PhotoSyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine


/**
 * Repository for [Photo].
 * Uses [PhotoDao] and accesses the filesystem to read and write encrypted photos.
 *
 * @since 1.0.0
 * @author PhotoZ
 */
class PhotoRepository @Inject constructor(
    private val photoDao: PhotoDao,
    private val albumDao: AlbumDao,
    private val vaultFileStorage: VaultFileStorage,
    private val createThumbnail: CreateThumbnailsUseCase,
    private val app: Application,
    private val config: Config,
    private val io: IO,
) {

    // region DATABASE

    /**
     * @see PhotoDao.insert
     */
    suspend fun insert(photo: Photo) = photoDao.insert(photo)

    /**
     * @see PhotoDao.delete
     */
    private suspend fun delete(photo: Photo) = photoDao.delete(photo)

    /**
     * @see PhotoDao.deleteAll
     */
    suspend fun deleteAll() = photoDao.deleteAll()

    suspend fun get(uuid: String) = photoDao.get(uuid)

    /**
     * @see PhotoDao.findAllPhotosByImportDateDesc
     */
    suspend fun findAllPhotosByImportDateDesc() = photoDao.findAllPhotosByImportDateDesc()

    fun observeAll(sort: Sort) = photoDao.observeAllSorted(sort)

    /**
     * @see PhotoDao.countAll
     */
    suspend fun countAll() = photoDao.countAll()

    // endregion

    // region IO

    // region WRITE

    /**
     * Import a photo from a url.
     *
     * Collects meta data and calls [safeCreatePhoto].
     * Returns re created uuid
     */
    suspend fun safeImportPhoto(sourceUri: Uri, importSource: ImportSource): String {
        val metaData = app.contentResolver.getMetadataFor(sourceUri)

        val type = PhotoType.fromMimeType(metaData.mimeType)
        if (type == PhotoType.UNDEFINED) return String.empty

        // ─── v9 dedup: compute SHA-256 of the plaintext source bytes ───────
        // The hash is computed INCREMENTALLY as the source stream is copied to
        // the encrypted destination (see [createPhotoFile]) — no extra disk
        // read and no extra memory beyond the digest's internal state. The
        // resulting hex string is the dedup key consulted by the upload worker
        // before transferring the original to the remote: if the same hash is
        // already on the remote under a different UUID, the upload is skipped.
        //
        // `null` if the source stream can't be read (the import will fail
        // downstream anyway) — the worker treats null hash as "no dedup,
        // upload normally".
        val sha256 = runCatching { MessageDigest.getInstance("SHA-256") }.getOrNull()

        val inputStream = io.openFileInput(sourceUri)
        val photo = Photo(
            fileName = metaData.fileName ?: UUID.randomUUID().toString(),
            importedAt = System.currentTimeMillis(),
            lastModified = metaData.lastModified,
            type = type,
            size = metaData.size ?: 0,
            // ─── Path-consistency metadata (v8) ───────────────────────────────
            // Capture the photo's original local-origin provenance for the user's
            // reference. The vault is its own managed encrypted storage — this field
            // is metadata only, NOT a filesystem path the photo gets written to.
            //
            // TODO(v8-followup): For gallery-sourced imports (ImportSource.InApp
            //   with a `content://media/...` URI), we could query the MediaStore
            //   `RELATIVE_PATH` column (e.g. `DCIM/Camera`, `Pictures/Screenshots`)
            //   to capture the full original subfolder rather than just the
            //   filename. The current `FileMetaData` from `getMetadataFor()` only
            //   exposes `DISPLAY_NAME`/`SIZE`/`COLUMN_LAST_MODIFIED` — it doesn't
            //   read `RELATIVE_PATH`. Until that's extended, the filename itself
            //   is the most meaningful provenance we have.
            //   See onlasdan.gallery.other.getMetadataFor.
            relativePath = metaData.relativePath ?: metaData.fileName,
            // ─── v9 dedup: album-path from MediaStore RELATIVE_PATH ──────────
            // metaData.relativePath captures the folder structure (e.g. "Download",
            // "DCIM/Camera") from MediaStore. Falls back to filename if not available
            // (SAF URIs from file pickers may not expose RELATIVE_PATH).
            albumPath = metaData.relativePath ?: metaData.fileName,
        )

        val created = safeCreatePhoto(photo, inputStream, sourceUri, sha256)
        inputStream?.lazyClose()

        // ─── v9 dedup: finalize the SHA-256 hash and stash it on the Photo ──
        // The digest was updated incrementally inside createPhotoFile() as the
        // plaintext bytes were streamed through. If the import succeeded, the
        // hash now represents the photo's unencrypted content — store its hex
        // string on the Photo row so the upload worker can consult the dedup
        // registry without re-reading the file.
        if (created && sha256 != null) {
            val hashHex = sha256.digest().joinToString("") { "%02x".format(it) }
            try {
                photoDao.updateContentHash(photo.uuid, hashHex)

                // ─── Bug 1 fix: dedup at import time — don't create a duplicate ──
                // If another Photo row with the same content_hash already exists,
                // delete the just-imported duplicate (local file + DB row) so the
                // gallery doesn't show two entries for the same file.
                val existing = photoDao.findByContentHash(hashHex, excludeUuid = photo.uuid)
                if (existing != null) {
                    Timber.i("Dedup at import: content_hash=%s already exists as uuid=%s — deleting duplicate %s",
                        hashHex, existing.uuid, photo.uuid)
                    deleteInternalPhotoData(photo)
                    photoDao.delete(photo)
                    return String.empty // signal: import was a duplicate, not created
                }
            } catch (e: Exception) {
                Timber.w(e, "Failed to persist contentHash or dedup check for %s", photo.uuid)
            }
        }

        if (!created) {
            return String.empty
        }

        if (!config.deleteImportedFiles || importSource == ImportSource.Share) {
            return photo.uuid
        }

        val deleted = io.deleteFile(sourceUri)
        return if (deleted == true) photo.uuid else String.empty
    }

    /**
     * Writes and encrypts the [source] into internal storage.
     * Saves the [photo] afterwords.
     * It is up to the caller to close the [source].
     * Does create a thumbnail, IF [origUri] is specified.
     *
     * @param digest optional [MessageDigest] — if non-null, it is updated
     *   incrementally as the plaintext source bytes are streamed through to
     *   the encrypted destination. The caller finalizes the digest
     *   (`digest.digest()`) after this returns to obtain the SHA-256 hash of
     *   the photo's unencrypted content (used as the v9 dedup key).
     *   `null` for callers that don't need the hash (e.g. legacy restore).
     *
     * @return true, if everything worked
     */
    private suspend fun safeCreatePhoto(
        photo: Photo,
        source: InputStream?,
        origUri: Uri? = null,
        digest: MessageDigest? = null,
    ): Boolean {
        val fileLen = createPhotoFile(photo, source, digest)
        var success = fileLen != -1L

        if (success) {
            photo.size = fileLen

            if (origUri != null) {
                createThumbnail(photo, origUri)
            }

            val photoId = insert(photo)
            success = photoId != -1L
        }

        if (!success) {
            deleteInternalPhotoData(photo)
            return false
        }

        // ─── Sync hook (PR1) ────────────────────────────────────────────────────
        // After the DB row is committed, enqueue a WorkManager job to push the encrypted
        // original + thumbnail to the rclone remote. Idempotent via ExistingWorkPolicy.KEEP.
        if (SyncConfig.autoUploadEnabled) {
            runCatching {
                PhotoSyncWorker.enqueue(app, photo)
            }.onFailure { e ->
                Timber.w(e, "Failed to enqueue sync job for %s", photo.uuid)
            }
        }

        return true
    }

    /**
     * Create the internal file for a photo.
     *
     * @param digest optional [MessageDigest] — if non-null, it is updated
     *   incrementally as the plaintext source bytes are read (BEFORE they're
     *   handed to the encrypted destination). This lets the caller compute
     *   the SHA-256 of the plaintext — the v9 dedup key — without an extra
     *   disk read. The caller finalizes the digest after this returns.
     */
    fun createPhotoFile(photo: Photo, source: InputStream?, digest: MessageDigest? = null): Long {
        try {
            val encryptedDestination = vaultFileStorage.openEncryptedOutput(photo.internalFileName)

            source ?: return -1L
            encryptedDestination ?: return -1L

            // ─── v9 dedup: stream-copy with optional digest update ──────────
            // Replaces `source.copyTo(encryptedDestination)` with a manual
            // byte-buffer loop so we can feed each chunk through the digest
            // BEFORE it goes to the encrypted destination. The digest sees
            // the plaintext bytes; the destination sees the AES-CBC-encrypted
            // version of the same bytes (the encryption happens inside
            // `encryptedDestination.write`, transparently to this loop).
            //
            // 64 KB buffer matches the default used by Kotlin's `copyTo`.
            val buf = ByteArray(64 * 1024)
            var fileLen = 0L
            while (true) {
                val n = source.read(buf)
                if (n <= 0) break
                if (digest != null) digest.update(buf, 0, n)
                encryptedDestination.write(buf, 0, n)
                fileLen += n
            }
            encryptedDestination.lazyClose()

            return fileLen
        } catch (e: IOException) {
            Timber.e("Error while writing file: $e")
            return -1L
        }
    }

    // endregion

    // region DELETE

    /**
     * Delete a photo from the filesystem. On success, delete it in the database.
     *
     * @return true, if the photo was successfully deleted on disk and in db.
     */
    suspend fun safeDeletePhoto(photo: Photo): Boolean {
        val deletedElements = delete(photo)
        val success = deletedElements != -1

        if (success) {
            deleteInternalPhotoData(photo)
            albumDao.unlink(photo.uuid)
        }

        return success
    }

    /**
     * Delete a photos bytes and thumbnail bytes on the filesystem.
     *
     * @param photo the photo to delete
     *
     * @return true, if photo and thumbnail could be deleted
     */
    fun deleteInternalPhotoData(photo: Photo): Boolean =
        vaultFileStorage.deleteEncryptedFile(photo.internalFileName)
                && vaultFileStorage.deleteEncryptedFile(photo.internalThumbnailFileName)
                && (!photo.type.isVideo || vaultFileStorage.deleteEncryptedFile(photo.internalVideoPreviewFileName))


    // endregion

    // region EXPORT

    /**
     * Export a photo to a specific directory.
     *
     * @param photo The Photo to be saved
     */
    suspend fun exportPhoto(photo: Photo, target: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val inputStream =
                vaultFileStorage.openEncryptedInput(photo.internalFileName)
            inputStream ?: return@withContext false

            val outputStream = createExternalOutputStream(photo, target)
            outputStream ?: return@withContext false

            val wrote = suspendCancellableCoroutine { continuation ->
                val wrote = inputStream.copyTo(outputStream)
                continuation.resume(wrote)
            }

            outputStream.lazyClose()

            var deleted = true
            if (config.deleteExportedFiles) {
                deleted = safeDeletePhoto(photo)
            }

            wrote != -1L && deleted
        } catch (e: IOException) {
            Timber.d("Error exporting file: ${photo.fileName} $e")
            false
        }
    }

    private fun createExternalOutputStream(photo: Photo, uri: Uri): OutputStream? {
        val fileName = photo.fileName
        val mimeType = photo.type.mimeType

        return io.openFileOutput(
            app.contentResolver,
            fileName,
            mimeType,
            uri,
        )
    }

    // endregion
    // endregion
}