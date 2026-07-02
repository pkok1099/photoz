/*
 *   Copyright 2020–2026 Leon Latsch
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
import java.util.UUID
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine


/**
 * Repository for [Photo].
 * Uses [PhotoDao] and accesses the filesystem to read and write encrypted photos.
 *
 * @since 1.0.0
 * @author Leon Latsch
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
            relativePath = metaData.fileName,
        )

        val created = safeCreatePhoto(photo, inputStream, sourceUri)
        inputStream?.lazyClose()

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
     * @return true, if everything worked
     */
    private suspend fun safeCreatePhoto(
        photo: Photo,
        source: InputStream?,
        origUri: Uri? = null
    ): Boolean {
        val fileLen = createPhotoFile(photo, source)
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
     */
    fun createPhotoFile(photo: Photo, source: InputStream?): Long {
        try {
            val encryptedDestination = vaultFileStorage.openEncryptedOutput(photo.internalFileName)

            source ?: return -1L
            encryptedDestination ?: return -1L

            val fileLen = source.copyTo(encryptedDestination)
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