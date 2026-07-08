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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import onlasdan.gallery.model.database.dao.PhotoDao
import onlasdan.gallery.model.database.entity.internalFileName
import onlasdan.gallery.settings.data.Config
import onlasdan.gallery.sync.domain.SyncConfig
import onlasdan.gallery.sync.domain.SyncState
import onlasdan.gallery.sync.rclone.RcloneController
import timber.log.Timber
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-demand restore of an encrypted original file from the rclone remote.
 *
 * Used by EncryptedImageFetcher (image viewer path) and ImageViewerViewModel (video player
 * path) to transparently fetch a remote-only original back to local storage before display.
 */
@Singleton
class SyncRestorer
        @Inject
        constructor(
                private val app: Application,
                private val photoDao: PhotoDao,
                private val rcloneController: RcloneController,
                private val config: Config,
        ) {
                suspend fun ensureLocalOriginal(uuid: String): Result<Unit> =
                        withContext(Dispatchers.IO) {
                                runCatching {
                                        val photo =
                                                try {
                                                        photoDao.get(uuid)
                                                } catch (e: Exception) {
                                                        Timber.w(e, "SyncRestorer: photo %s not in DB; cannot restore", uuid)
                                                        return@runCatching
                                                }

                                        // Sprint 10+ / M10 Part 3 fix: use photo.internalFileName which
                                        // resolves canonicalUuid (symlink). If this photo is a symlink,
                                        // the file lives under the canonical's UUID on the remote — not
                                        // under this photo's UUID. Using raw internalFileName(uuid) would
                                        // try to download from the wrong remote path → 404.
                                        val localFile = File(app.filesDir, photo.internalFileName)
                                        if (localFile.exists() && localFile.length() > 0) {
                                                return@runCatching
                                        }

                                        if (photo.syncState != SyncState.UPLOADED) {
                                                // F-UV-014: Surface error instead of swallowing
                                                throw IOException("Photo $uuid has syncState=${photo.syncState}; cannot restore")
                                        }

                                        val remote = config.syncChosenRemote
                                        if (remote == null) {
                                                throw IOException("No remote chosen; cannot restore $uuid")
                                        }

                                        val remoteOrig = "$remote:${SyncConfig.remoteOriginalsDir}/${localFile.name}"
                                        Timber.i("SyncRestorer: downloading %s ← %s", localFile.absolutePath, remoteOrig)
                                        // F-UV-015 + FLOWD-002: Clean up partial download on failure
                                        try {
                                                rcloneController.downloadFile(remoteOrig, localFile.absolutePath).getOrThrow()
                                                // FLOWD-001: Verify file integrity
                                                if (!localFile.exists() || localFile.length() == 0L) {
                                                        localFile.delete()
                                                        throw IOException("Downloaded file is empty for $uuid")
                                                }
                                        } catch (e: Exception) {
                                                localFile.delete()
                                                throw e
                                        }
                                }
                        }

                /**
                 * On-demand restore of an encrypted original from the remote, with
                 * periodic progress callbacks.
                 *
                 * Same as [ensureLocalOriginal] but emits download-progress estimates to
                 * [onProgress] while the rclone `operations/copyfile` call is in flight.
                 * The progress is a size-based estimate (same approach as
                 * [onlasdan.gallery.sync.rclone.RcloneController.uploadFileWithProgress])
                 * because rclone's `core/stats` doesn't track `operations/copyfile`
                 * transfers — the estimate under-promises on fast networks (the bar
                 * jumps to 100% before the estimate would have reached it) and slightly
                 * over-promises on very slow networks (the bar sits at 95% for a while
                 * before the real download finishes). Both failure modes are acceptable —
                 * the alternative is an indeterminate spinner, which gives the user zero
                 * feedback.
                 *
                 * Used by the video viewer path
                 * ([onlasdan.gallery.imageviewer.ui.ImageViewerViewModel]) so the user
                 * sees a determinate "Downloading video…" progress bar instead of an
                 * indeterminate spinner while a remote-only video is fetched back to
                 * local storage before ExoPlayer can play it.
                 *
                 * @param uuid the photo UUID to restore
                 * @param onProgress invoked on the same dispatcher (Dispatchers.IO) as
                 *   the download. May be called zero or more times; never called with a
                 *   value outside `0f..100f`. The final call (after the download
                 *   completes) is always `100f`. Callers are responsible for
                 *   rate-limiting UI updates triggered from this callback.
                 *
                 * @since video-loading-indicator feature — on-demand video download with
                 *   visible progress
                 */
                suspend fun ensureLocalOriginalWithProgress(
                        uuid: String,
                        onProgress: (Float) -> Unit,
                ): Result<Unit> =
                        withContext(Dispatchers.IO) {
                                runCatching {
                                        val photo =
                                                try {
                                                        photoDao.get(uuid)
                                                } catch (e: Exception) {
                                                        return@runCatching // F-UV-014: photo not in DB — non-fatal, silent return OK
                                                }

                                        // Sprint 10+ / M10 Part 3 fix: resolve symlink via photo.internalFileName
                                        val localFile = File(app.filesDir, photo.internalFileName)
                                        if (localFile.exists() && localFile.length() > 0) {
                                                onProgress(100f)
                                                return@runCatching
                                        }

                                        if (photo.syncState != SyncState.UPLOADED) {
                                                // F-UV-014: Surface error instead of swallowing as success
                                                throw IOException("Photo $uuid has syncState=${photo.syncState} (not UPLOADED); cannot restore from remote")
                                        }

                                        val remote = config.syncChosenRemote
                                        if (remote == null) {
                                                throw IOException("No remote chosen; cannot restore $uuid")
                                        }

                                        val remoteOrig = "$remote:${SyncConfig.remoteOriginalsDir}/${localFile.name}"
                                        Timber.i("SyncRestorer: downloading %s ← %s (with progress)", localFile.absolutePath, remoteOrig)
                                        // F-SYNC-021: use async RC API with job/status polling for real progress.
                                        // F-UV-015 + FLOWD-002: Clean up partial download on failure.
                                        try {
                                                rcloneController
                                                        .downloadFileWithProgress(
                                                                remotePath = remoteOrig,
                                                                localPath = localFile.absolutePath,
                                                                onProgress = onProgress,
                                                        ).getOrThrow()

                                                // FLOWD-001: Verify downloaded file integrity (size check).
                                                // If the local file is empty or significantly smaller than expected,
                                                // the download may have been truncated.
                                                if (!localFile.exists() || localFile.length() == 0L) {
                                                        localFile.delete()
                                                        throw IOException("Downloaded file is empty or missing for $uuid")
                                                }
                                                if (photo.size > 0 && localFile.length() < photo.size / 2) {
                                                        Timber.w("SyncRestorer: downloaded file for %s is %d bytes but expected ~%d — may be truncated", uuid, localFile.length(), photo.size)
                                                        // Non-fatal warning — encrypted file size != plaintext size, but
                                                        // a 50%+ discrepancy suggests truncation. Let GCM auth tag catch it.
                                                }
                                        } catch (e: Exception) {
                                                // F-UV-015 + FLOWD-002: Delete partial download to prevent
                                                // future calls from seeing a non-empty file and short-circuiting.
                                                localFile.delete()
                                                throw e
                                        }
                                        onProgress(100f)
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
                suspend fun restoreAllOriginals(): Int =
                        withContext(Dispatchers.IO) {
                                val remote = config.syncChosenRemote
                                if (remote.isNullOrBlank()) {
                                        android.util.Log.e("RcloneDiag", "[SyncRestorer] restoreAllOriginals: no remote chosen")
                                        return@withContext 0
                                }

                                val photos =
                                        try {
                                                photoDao.getAll()
                                        } catch (e: Exception) {
                                                android.util.Log.e("RcloneDiag", "[SyncRestorer] restoreAllOriginals: failed to query photos: ${e.message}", e)
                                                return@withContext 0
                                        }

                                var restored = 0
                                for (photo in photos) {
                                        if (photo.syncState != SyncState.UPLOADED) continue

                                        val localFile = File(app.filesDir, photo.internalFileName)
                                        if (localFile.exists() && localFile.length() > 0) continue // already local

                                        val remoteOrig = "$remote:${SyncConfig.remoteOriginalsDir}/${localFile.name}"
                                        try {
                                                android.util.Log.e(
                                                        "RcloneDiag",
                                                        "[SyncRestorer] restoreAllOriginals: downloading ${photo.uuid} (${photo.fileName}) ← $remoteOrig",
                                                )
                                                rcloneController.downloadFile(remoteOrig, localFile.absolutePath).getOrThrow()
                                                restored++
                                                android.util.Log.e(
                                                        "RcloneDiag",
                                                        "[SyncRestorer] restoreAllOriginals: OK ${photo.uuid} (${localFile.length()} bytes)",
                                                )
                                        } catch (e: Exception) {
                                                android.util.Log.e(
                                                        "RcloneDiag",
                                                        "[SyncRestorer] restoreAllOriginals: FAILED for ${photo.uuid}: ${e.message}",
                                                        e,
                                                )
                                        }
                                }

                                android.util.Log.e(
                                        "RcloneDiag",
                                        "[SyncRestorer] restoreAllOriginals: DONE — restored $restored of ${photos.size} photos",
                                )
                                restored
                        }
        }
