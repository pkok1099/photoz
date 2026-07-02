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
import onlasdan.gallery.model.database.dao.PhotoDao
import onlasdan.gallery.model.database.entity.internalFileName
import onlasdan.gallery.settings.data.Config
import onlasdan.gallery.sync.domain.SyncConfig
import onlasdan.gallery.sync.domain.SyncState
import onlasdan.gallery.sync.rclone.RcloneController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-demand restore of an encrypted original file from the rclone remote.
 *
 * Used by EncryptedImageFetcher (image viewer path) and ImageViewerViewModel (video player
 * path) to transparently fetch a remote-only original back to local storage before display.
 */
@Singleton
class SyncRestorer @Inject constructor(
    private val app: Application,
    private val photoDao: PhotoDao,
    private val rcloneController: RcloneController,
    private val config: Config,
) {

    suspend fun ensureLocalOriginal(uuid: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val localFile = File(app.filesDir, internalFileName(uuid))
            if (localFile.exists() && localFile.length() > 0) {
                return@runCatching
            }

            val photo = try {
                photoDao.get(uuid)
            } catch (e: Exception) {
                Timber.w(e, "SyncRestorer: photo %s not in DB; cannot restore", uuid)
                return@runCatching
            }

            if (photo.syncState != SyncState.UPLOADED) {
                Timber.d(
                    "SyncRestorer: %s has syncState=%s (not UPLOADED); cannot restore from remote",
                    uuid,
                    photo.syncState,
                )
                return@runCatching
            }

            val remote = config.syncChosenRemote
            if (remote == null) {
                Timber.w("SyncRestorer: no remote chosen; cannot restore %s", uuid)
                return@runCatching
            }

            val remoteOrig = "$remote:${SyncConfig.remoteOriginalsDir}/${localFile.name}"
            Timber.i("SyncRestorer: downloading %s ← %s", localFile.absolutePath, remoteOrig)
            rcloneController.downloadFile(remoteOrig, localFile.absolutePath).getOrThrow()
        }
    }

    /**
     * Restore ALL uploaded photos from the remote back to local storage.
     *
     * Downloads every photo whose syncState is UPLOADED but whose local original
     * file is missing (e.g. after a fresh-install login, or after local files
     * were cleared). The decrypted files land in the app's private storage
     * (filesDir), preserving the 1:1 path structure via [Photo.albumPath] —
     * the gallery's album view reflects the original folder structure.
     *
     * This is the "Restore from backup" action: remote → local, path 1:1,
     * inside PhotoZ's managed storage (NOT the public filesystem).
     *
     * @return count of originals successfully downloaded
     */
    suspend fun restoreAllOriginals(): Int = withContext(Dispatchers.IO) {
        val remote = config.syncChosenRemote
        if (remote.isNullOrBlank()) {
            android.util.Log.e("RcloneDiag", "[SyncRestorer] restoreAllOriginals: no remote chosen")
            return@withContext 0
        }

        val photos = try {
            photoDao.getAll()
        } catch (e: Exception) {
            android.util.Log.e("RcloneDiag", "[SyncRestorer] restoreAllOriginals: failed to query photos: ${e.message}", e)
            return@withContext 0
        }

        var restored = 0
        for (photo in photos) {
            if (photo.syncState != SyncState.UPLOADED) continue

            val localFile = File(app.filesDir, internalFileName(photo.uuid))
            if (localFile.exists() && localFile.length() > 0) continue // already local

            val remoteOrig = "$remote:${SyncConfig.remoteOriginalsDir}/${localFile.name}"
            try {
                android.util.Log.e("RcloneDiag",
                    "[SyncRestorer] restoreAllOriginals: downloading ${photo.uuid} (${photo.fileName}) ← $remoteOrig")
                rcloneController.downloadFile(remoteOrig, localFile.absolutePath).getOrThrow()
                restored++
                android.util.Log.e("RcloneDiag",
                    "[SyncRestorer] restoreAllOriginals: OK ${photo.uuid} (${localFile.length()} bytes)")
            } catch (e: Exception) {
                android.util.Log.e("RcloneDiag",
                    "[SyncRestorer] restoreAllOriginals: FAILED for ${photo.uuid}: ${e.message}", e)
            }
        }

        android.util.Log.e("RcloneDiag",
            "[SyncRestorer] restoreAllOriginals: DONE — restored $restored of ${photos.size} photos")
        restored
    }
}
