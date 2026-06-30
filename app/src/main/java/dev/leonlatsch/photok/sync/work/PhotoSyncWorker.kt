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

package dev.leonlatsch.photok.sync.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.leonlatsch.photok.R
import dev.leonlatsch.photok.model.database.dao.PhotoDao
import dev.leonlatsch.photok.model.database.entity.Photo
import dev.leonlatsch.photok.settings.data.Config
import dev.leonlatsch.photok.sync.debug.CrashLogger
import dev.leonlatsch.photok.sync.domain.SyncConfig
import dev.leonlatsch.photok.sync.domain.SyncState
import dev.leonlatsch.photok.sync.rclone.RcloneController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * WorkManager Worker that pushes a single photo's encrypted artifacts (thumbnail + original,
 * plus video-preview if applicable) to the configured rclone remote and verifies the upload via
 * SHA-256 hash check before transitioning the photo's [SyncState] to [SyncState.UPLOADED].
 */
@HiltWorker
class PhotoSyncWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted private val params: WorkerParameters,
    private val photoDao: PhotoDao,
    private val rcloneController: RcloneController,
    private val config: Config,
) : CoroutineWorker(appContext, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo {
        ensureChannel()
        return ForegroundInfo(
            NOTIFICATION_ID,
            buildNotification(appContext.getString(R.string.sync_notification_text)),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        // ─── TOP-LEVEL SAFETY NET ─────────────────────────────────────────────
        // Catch ALL throwables (including Error subclasses) to prevent the worker from crashing
        // the app process. CancellationException is re-thrown to respect coroutine cancellation.
        try {
            doWorkInternal()
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Timber.e(t, "PhotoSyncWorker: UNCAUGHT throwable in doWork — converting to failure")
            CrashLogger.logCrash(
                Thread.currentThread(),
                t,
                context = "PhotoSyncWorker.doWork safety net (uuid=${inputData.getString(KEY_PHOTO_UUID)})"
            )
            val uuid = inputData.getString(KEY_PHOTO_UUID)
            if (uuid != null) {
                try {
                    photoDao.updateSyncState(uuid, SyncState.UPLOAD_FAILED)
                } catch (dbEx: Throwable) {
                    Timber.e(dbEx, "PhotoSyncWorker: failed to mark UPLOAD_FAILED after uncaught error")
                }
            }
            Result.failure()
        }
    }

    private suspend fun doWorkInternal(): Result {
        val uuid = inputData.getString(KEY_PHOTO_UUID)
        if (uuid.isNullOrBlank()) {
            Timber.e("PhotoSyncWorker: missing $KEY_PHOTO_UUID in input data")
            return Result.failure()
        }

        runCatching { setForeground(getForegroundInfo()) }

        val photo = try {
            photoDao.get(uuid)
        } catch (e: Exception) {
            Timber.w(e, "PhotoSyncWorker: photo %s not found in DB (deleted by user?)", uuid)
            return Result.success()
        }

        if (photo.syncState == SyncState.UPLOADED) {
            Timber.d("PhotoSyncWorker: %s already UPLOADED — skipping", uuid)
            return Result.success()
        }

        try {
            photoDao.updateSyncState(uuid, SyncState.UPLOAD_PENDING)
        } catch (e: Exception) {
            Timber.e(e, "PhotoSyncWorker: failed to mark UPLOAD_PENDING for %s", uuid)
            return Result.failure()
        }

        val outcome = runCatching { performUpload(photo) }

        return when {
            outcome.isSuccess -> {
                try {
                    val affected = photoDao.updateSyncStateReturningRows(uuid, SyncState.UPLOADED)
                    if (affected == 0) {
                        Timber.i("PhotoSyncWorker: %s was deleted during upload; skipping commit", uuid)
                        return Result.success()
                    }
                } catch (e: Exception) {
                    Timber.e(e, "PhotoSyncWorker: failed to commit UPLOADED for %s", uuid)
                    return Result.failure()
                }
                if (SyncConfig.deleteLocalAfterUpload) {
                    deleteLocalOriginalAfterUpload(photo)
                }
                Result.success()
            }

            isFatalFailure(outcome.exceptionOrNull()) -> {
                Timber.w(outcome.exceptionOrNull(), "PhotoSyncWorker: fatal failure for %s", uuid)
                try {
                    photoDao.updateSyncState(uuid, SyncState.UPLOAD_FAILED)
                } catch (e: Exception) {
                    Timber.e(e, "PhotoSyncWorker: failed to mark UPLOAD_FAILED for %s", uuid)
                }
                Result.failure()
            }

            else -> {
                if (runAttemptCount + 1 >= SyncConfig.maxSyncAttempts) {
                    Timber.w(
                        outcome.exceptionOrNull(),
                        "PhotoSyncWorker: max attempts reached for %s (attempt %d)",
                        uuid,
                        runAttemptCount + 1,
                    )
                    try {
                        photoDao.updateSyncState(uuid, SyncState.UPLOAD_FAILED)
                    } catch (e: Exception) {
                        Timber.e(e, "PhotoSyncWorker: failed to mark UPLOAD_FAILED for %s", uuid)
                    }
                    Result.failure()
                } else {
                    Timber.i(
                        outcome.exceptionOrNull(),
                        "PhotoSyncWorker: retryable failure for %s (attempt %d, will retry)",
                        uuid,
                        runAttemptCount + 1,
                    )
                    Result.retry()
                }
            }
        }
    }

    private suspend fun performUpload(photo: Photo) {
        val remote = config.syncChosenRemote
            ?: throw FatalSyncException(
                "No rclone remote chosen. Open Settings → Cloud Sync → Backup configuration " +
                    "and pick a remote from the imported config."
            )
        val uuid = photo.uuid

        val thumbPath = appContext.getFileStreamPath(photo.internalThumbnailFileName)
        if (thumbPath.exists()) {
            val remoteThumb = "$remote:${SyncConfig.remoteThumbnailsDir}/${thumbPath.name}"
            rcloneController.uploadFile(thumbPath.absolutePath, remoteThumb).getOrThrow()
        }

        if (photo.type.isVideo) {
            val vpPath = appContext.getFileStreamPath(photo.internalVideoPreviewFileName)
            if (vpPath.exists()) {
                val remoteVp = "$remote:${SyncConfig.remoteVideosDir}/${vpPath.name}"
                rcloneController.uploadFile(vpPath.absolutePath, remoteVp).getOrThrow()
            }
        }

        val origPath = appContext.getFileStreamPath(photo.internalFileName)
        if (!origPath.exists()) {
            throw FatalSyncException(
                "Local original file missing for $uuid before upload. " +
                    "Photo may have been deleted out-of-band."
            )
        }
        val remoteOrig = "$remote:${SyncConfig.remoteOriginalsDir}/${origPath.name}"
        rcloneController.uploadFile(origPath.absolutePath, remoteOrig).getOrThrow()

        val localHash = sha256OfFile(origPath.absolutePath)
        val verified = rcloneController.verifyRemote(remoteOrig, localHash).getOrThrow()
        if (!verified) {
            throw HashMismatchException(
                "Hash mismatch after upload for $uuid. Local=$localHash"
            )
        }
    }

    private fun deleteLocalOriginalAfterUpload(photo: Photo) {
        val origPath = appContext.getFileStreamPath(photo.internalFileName)
        if (origPath.exists()) {
            val deleted = origPath.delete()
            if (!deleted) {
                Timber.w("Failed to delete local original after upload: %s", origPath)
            }
        }
    }

    private fun ensureChannel() {
        val nm = appContext.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                appContext.getString(R.string.sync_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = appContext.getString(R.string.sync_notification_channel_desc)
                setShowBadge(false)
            }
        )
    }

    private fun buildNotification(text: String): Notification {
        val launchIntent = appContext.packageManager.getLaunchIntentForPackage(appContext.packageName)
        val pi = launchIntent?.let {
            PendingIntent.getActivity(
                appContext,
                0,
                it,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
        return NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(appContext.getString(R.string.sync_notification_title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pi)
            .build()
    }

    private fun isFatalFailure(t: Throwable?): Boolean = when (t) {
        is FatalSyncException -> true
        is IllegalArgumentException -> true
        else -> false
    }

    private fun sha256OfFile(path: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        appContext.openFileInput(File(path).name).use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private class FatalSyncException(message: String) : Exception(message)
    private class HashMismatchException(message: String) : Exception(message)

    companion object {
        const val KEY_PHOTO_UUID = "photoUuid"
        const val UNIQUE_WORK_PREFIX = "photok-sync-"
        private const val CHANNEL_ID = "photok-sync"
        private const val NOTIFICATION_ID = 4242

        fun enqueue(context: Context, photo: Photo) {
            val request = OneTimeWorkRequestBuilder<PhotoSyncWorker>()
                .setInputData(workDataOf(KEY_PHOTO_UUID to photo.uuid))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    SyncConfig.initialBackoffSeconds,
                    TimeUnit.SECONDS,
                )
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_PREFIX + photo.uuid,
                ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }
}
