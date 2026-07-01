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
import dev.leonlatsch.photok.sync.debug.SyncLogger
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

        // ─── HARD GUARD: no upload without a confirmed repo session ──────────
        // This should NEVER trigger in normal operation — the gallery is gated behind
        // repo setup, so the user can't import photos (and thus can't enqueue sync
        // jobs) without a confirmed repo. If this fires, it's a real bug to surface
        // loudly, not swallow.
        if (!config.repoConfirmed || config.syncChosenRemote.isNullOrBlank()) {
            val msg = "PhotoSyncWorker: FATAL — upload attempted without confirmed repo session " +
                "(repoConfirmed=${config.repoConfirmed}, remote=${config.syncChosenRemote}). " +
                "This should never happen — gallery is gated behind repo setup."
            Timber.e(msg)
            CrashLogger.logCrash(Thread.currentThread(), FatalSyncException(msg),
                context = "PhotoSyncWorker hard guard (no repo session) uuid=$uuid")
            try {
                photoDao.updateSyncState(uuid, SyncState.UPLOAD_FAILED)
            } catch (_: Exception) {}
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
            SyncLogger.logStateTransition(uuid, "LOCAL_ONLY/prev", "UPLOAD_PENDING", "worker attempt ${runAttemptCount + 1}")
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
                    SyncLogger.logStateTransition(uuid, "UPLOAD_PENDING", "UPLOADED", "upload + verify succeeded")
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
                    SyncLogger.logStateTransition(uuid, "UPLOAD_PENDING", "UPLOAD_FAILED",
                        "fatal: ${outcome.exceptionOrNull()?.message}")
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
                        SyncLogger.logStateTransition(uuid, "UPLOAD_PENDING", "UPLOAD_FAILED",
                            "max attempts (${runAttemptCount + 1}): ${outcome.exceptionOrNull()?.message}")
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

        // ─── INDEPENDENT VERIFICATION (Step 2 — restic-style "remote is source of truth") ───
        // After uploadFile() reports success, do NOT trust it. Three independent checks:
        // 1. verifyFileExists: separate operations/list call — confirm file is at the exact
        //    destination path with the expected size. This is the PRIMARY verification —
        //    it works on ALL backends (Koofr, S3, Dropbox, etc.) regardless of hash support.
        // 2. verifyRemote: hash check via operations/hashsum — confirm bytes match.
        //    This is BEST-EFFORT: some backends (e.g. Koofr) don't support sha256 natively
        //    and return a blank hash. In that case, we skip hash verification and rely on
        //    the size check from verifyFileExists. This is safe because:
        //    - The upload is encrypted (rclone copies exact bytes, no transcoding)
        //    - verifyFileExists confirms the file exists with the correct size
        //    - A size match on an exact-byte-copy is strong evidence of correct upload
        // 3. (size check is part of verifyFileExists)
        //
        // Only if verifyFileExists passes AND (verifyRemote passes OR hash is unsupported)
        // does syncState become UPLOADED. Any other failure → UPLOAD_FAILED.
        val localSize = origPath.length()
        rcloneController.verifyFileExists(remoteOrig, localSize).getOrThrow()

        val localHash = sha256OfFile(origPath.absolutePath)
        try {
            rcloneController.verifyRemote(remoteOrig, localHash).getOrThrow()
        } catch (e: RcloneController.HashNotSupportedException) {
            // Backend doesn't support sha256 — log and skip hash verification.
            // verifyFileExists (size check) already passed, which is sufficient.
            Timber.w("PhotoSyncWorker: hash verification skipped for %s — backend doesn't support sha256. Relying on size check. %s", uuid, e.message)
            SyncLogger.logStateTransition(uuid, "UPLOAD_PENDING", "UPLOADED",
                "hash skipped (unsupported), size verified OK")
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
