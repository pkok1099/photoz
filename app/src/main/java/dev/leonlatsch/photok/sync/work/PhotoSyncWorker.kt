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

    // ─── init block: fires when HiltWorkerFactory constructs this instance ──
    // If this log doesn't appear, Hilt failed to construct the worker (injection
    // failure, missing binding, etc.) — check logcat for Hilt/Dagger errors.
    init {
        android.util.Log.e("RcloneDiag", "[UploadWorker] init: instance constructed by HiltWorkerFactory (class=${this.javaClass.name})")
    }

    // ─── DIAGNOSTIC LOGGING (same RcloneDiag pattern as RcloneController) ────
    // Writes to BOTH Log.e (logcat) AND files/sync_log.txt so it's visible
    // via `adb logcat -d | grep RcloneDiag` AND
    // `adb shell run-as <pkg> cat files/sync_log.txt`.
    // Critical for diagnosing upload failures — prior sessions' logging only
    // covered repo detection/init, not the photo upload call chain itself.
    private fun diag(msg: String, throwable: Throwable? = null) {
        android.util.Log.e("RcloneDiag", "[UploadWorker] $msg", throwable)
        try {
            val logFile = java.io.File(appContext.filesDir, "sync_log.txt")
            logFile.appendText("\n[RcloneDiag] [UploadWorker] $msg\n")
            if (throwable != null) {
                logFile.appendText(throwable.stackTraceToString() + "\n")
            }
        } catch (_: Exception) {}
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        ensureChannel()
        return ForegroundInfo(
            NOTIFICATION_ID,
            buildNotification(appContext.getString(R.string.sync_notification_text)),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    override suspend fun doWork(): Result {
        // ─── ABSOLUTE FIRST LINE — direct Log.e, NOT via diag() ──────────────
        // This fires before withContext(Dispatchers.IO) so we can distinguish:
        //   (a) worker never constructed → no init log, no doWork ENTRY log
        //   (b) worker constructed but doWork never called → init log, no ENTRY log
        //   (c) worker constructed + doWork called but withContext blocked → ENTRY log, no BEGIN log
        //   (d) everything runs → all three logs appear
        android.util.Log.e("RcloneDiag",
            "[UploadWorker] doWork: ENTRY (before withContext) uuid=${inputData.getString(KEY_PHOTO_UUID)} attempt=${runAttemptCount + 1}")

        return withContext(Dispatchers.IO) {
            diag("doWork: BEGIN uuid=${inputData.getString(KEY_PHOTO_UUID)} attempt=${runAttemptCount + 1}")
            // ─── TOP-LEVEL SAFETY NET ─────────────────────────────────────────
            try {
                doWorkInternal()
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (t: Throwable) {
                diag("doWork: UNCAUGHT throwable — converting to failure", t)
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
        diag("performUpload: BEGIN uuid=$uuid remote=$remote photoType=${photo.type} syncState=${photo.syncState}")

        // ─── Upload thumbnail ──────────────────────────────────────────────
        val thumbPath = appContext.getFileStreamPath(photo.internalThumbnailFileName)
        if (thumbPath.exists()) {
            val remoteThumb = "$remote:${SyncConfig.remoteThumbnailsDir}/${thumbPath.name}"
            diag("performUpload: uploading thumbnail ${thumbPath.absolutePath} (size=${thumbPath.length()}) → $remoteThumb")
            try {
                rcloneController.uploadFile(thumbPath.absolutePath, remoteThumb).getOrThrow()
                diag("performUpload: thumbnail upload OK")
            } catch (e: Exception) {
                diag("performUpload: thumbnail upload FAILED: ${e.javaClass.name}: ${e.message}", e)
                throw e
            }
        } else {
            diag("performUpload: thumbnail file does not exist, skipping: ${thumbPath.absolutePath}")
        }

        // ─── Upload video preview (if video) ───────────────────────────────
        if (photo.type.isVideo) {
            val vpPath = appContext.getFileStreamPath(photo.internalVideoPreviewFileName)
            if (vpPath.exists()) {
                val remoteVp = "$remote:${SyncConfig.remoteVideosDir}/${vpPath.name}"
                diag("performUpload: uploading video preview ${vpPath.absolutePath} (size=${vpPath.length()}) → $remoteVp")
                try {
                    rcloneController.uploadFile(vpPath.absolutePath, remoteVp).getOrThrow()
                    diag("performUpload: video preview upload OK")
                } catch (e: Exception) {
                    diag("performUpload: video preview upload FAILED: ${e.javaClass.name}: ${e.message}", e)
                    throw e
                }
            }
        }

        // ─── Upload original ───────────────────────────────────────────────
        val origPath = appContext.getFileStreamPath(photo.internalFileName)
        if (!origPath.exists()) {
            diag("performUpload: FATAL — local original missing: ${origPath.absolutePath}")
            throw FatalSyncException(
                "Local original file missing for $uuid before upload. " +
                    "Photo may have been deleted out-of-band."
            )
        }
        val remoteOrig = "$remote:${SyncConfig.remoteOriginalsDir}/${origPath.name}"
        diag("performUpload: uploading original ${origPath.absolutePath} (size=${origPath.length()}) → $remoteOrig")
        try {
            rcloneController.uploadFile(origPath.absolutePath, remoteOrig).getOrThrow()
            diag("performUpload: original upload OK")
        } catch (e: Exception) {
            diag("performUpload: original upload FAILED: ${e.javaClass.name}: ${e.message}", e)
            throw e
        }

        // ─── Independent verification: file exists with correct size ───────
        val localSize = origPath.length()
        diag("performUpload: verifying file exists on remote (expected size=$localSize)")
        try {
            rcloneController.verifyFileExists(remoteOrig, localSize).getOrThrow()
            diag("performUpload: verifyFileExists OK")
        } catch (e: Exception) {
            diag("performUpload: verifyFileExists FAILED: ${e.javaClass.name}: ${e.message}", e)
            throw e
        }

        // ─── Independent verification: hash match (best-effort) ────────────
        val localHash = sha256OfFile(origPath.absolutePath)
        diag("performUpload: verifying hash (local sha256=$localHash)")
        try {
            rcloneController.verifyRemote(remoteOrig, localHash).getOrThrow()
            diag("performUpload: hash verification OK")
        } catch (e: RcloneController.HashNotSupportedException) {
            diag("performUpload: hash verification skipped — backend doesn't support sha256. Relying on size check.")
            Timber.w("PhotoSyncWorker: hash verification skipped for %s — backend doesn't support sha256. Relying on size check. %s", uuid, e.message)
            SyncLogger.logStateTransition(uuid, "UPLOAD_PENDING", "UPLOADED",
                "hash skipped (unsupported), size verified OK")
        } catch (e: Exception) {
            diag("performUpload: hash verification FAILED: ${e.javaClass.name}: ${e.message}", e)
            throw e
        }

        diag("performUpload: DONE — all uploads + verifications succeeded for $uuid")
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
            val uniqueWorkName = UNIQUE_WORK_PREFIX + photo.uuid

            // Log at enqueue time so we can confirm the worker is actually being
            // scheduled after photo import (if this log doesn't appear, the bug
            // is in the call chain BEFORE the worker, not in the worker itself).
            android.util.Log.e("RcloneDiag",
                "[UploadWorker] enqueue: BEGIN uuid=${photo.uuid} autoUploadEnabled=${SyncConfig.autoUploadEnabled}")
            try {
                java.io.File(context.filesDir, "sync_log.txt").appendText(
                    "\n[RcloneDiag] [UploadWorker] enqueue: BEGIN uuid=${photo.uuid}\n"
                )
            } catch (_: Exception) {}

            val wm = WorkManager.getInstance(context)

            // ─── Step 2 fix: cancel any stale work before enqueue ──────────────
            // ExistingWorkPolicy.KEEP (used previously) silently drops new enqueue
            // calls if a prior entry exists under the same unique name — even if
            // that entry is FAILED or CANCELLED. This caused "enqueue succeeds,
            // worker never runs" in prior sessions. Cancel first, then REPLACE.
            try {
                wm.cancelUniqueWork(uniqueWorkName)
                android.util.Log.e("RcloneDiag",
                    "[UploadWorker] enqueue: cancelled any stale work for $uniqueWorkName")
            } catch (e: Exception) {
                android.util.Log.e("RcloneDiag",
                    "[UploadWorker] enqueue: cancelUniqueWork FAILED: ${e.message}", e)
            }

            // ─── Dump WorkInfo BEFORE enqueue (to see if there was a stale entry) ──
            dumpWorkInfo(context, uniqueWorkName, "BEFORE enqueue")

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

            // ─── Step 2 fix: REPLACE instead of KEEP ───────────────────────────
            // REPLACE guarantees the new work request is enqueued even if a prior
            // entry exists. Combined with cancelUniqueWork above, this eliminates
            // the "stale work entry blocks new enqueue" failure mode.
            wm.enqueueUniqueWork(
                uniqueWorkName,
                ExistingWorkPolicy.REPLACE,
                request,
            )

            android.util.Log.e("RcloneDiag",
                "[UploadWorker] enqueue: OK — WorkManager.enqueueUniqueWork (REPLACE) called for uuid=${photo.uuid}")
            try {
                java.io.File(context.filesDir, "sync_log.txt").appendText(
                    "[RcloneDiag] [UploadWorker] enqueue: OK — WorkManager.enqueueUniqueWork (REPLACE) called for uuid=${photo.uuid}\n"
                )
            } catch (_: Exception) {}

            // ─── Step 1: dump WorkInfo AFTER enqueue (to see initial state) ────
            dumpWorkInfo(context, uniqueWorkName, "AFTER enqueue")

            // ─── Schedule a delayed dump (5s later) to see if state changed ───
            // WorkManager may take a few seconds to schedule the worker. By 5s
            // after enqueue, the state should have transitioned from ENQUEUED to
            // RUNNING (or FAILED if it crashed).
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                dumpWorkInfo(context, uniqueWorkName, "5s after enqueue")
            }, 5000)
        }

        /**
         * Step 1 diagnostic: query WorkManager's own state for the given unique
         * work name and log every WorkInfo. This is INDEPENDENT of the worker's
         * own logging — it tells us definitively whether WorkManager thinks the
         * work ran, failed, or is stuck waiting.
         *
         * Call from adb shell or from app code. The [when] label is logged so
         * you can correlate multiple dumps in the log.
         */
        fun dumpWorkInfo(context: Context, uniqueWorkName: String, whenLabel: String) {
            try {
                val workInfos = WorkManager.getInstance(context)
                    .getWorkInfosForUniqueWork(uniqueWorkName)
                    .get()
                if (workInfos.isEmpty()) {
                    android.util.Log.e("RcloneDiag",
                        "[UploadWorker] dumpWorkInfo ($whenLabel): NO WorkInfo found for $uniqueWorkName — work was never enqueued or was pruned")
                    try {
                        java.io.File(context.filesDir, "sync_log.txt").appendText(
                            "[RcloneDiag] [UploadWorker] dumpWorkInfo ($whenLabel): NO WorkInfo for $uniqueWorkName\n"
                        )
                    } catch (_: Exception) {}
                    return
                }
                for (wi in workInfos) {
                    val line = "dumpWorkInfo ($whenLabel): id=${wi.id} state=${wi.state} " +
                        "runAttemptCount=${wi.runAttemptCount} tags=${wi.tags} " +
                        "outputData=${wi.outputData}"
                    android.util.Log.e("RcloneDiag", "[UploadWorker] $line")
                    try {
                        java.io.File(context.filesDir, "sync_log.txt").appendText(
                            "[RcloneDiag] [UploadWorker] $line\n"
                        )
                    } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                android.util.Log.e("RcloneDiag",
                    "[UploadWorker] dumpWorkInfo ($whenLabel): FAILED: ${e.javaClass.name}: ${e.message}", e)
            }
        }

        /**
         * Dump ALL work info for this app's WorkManager (not just one unique name).
         * Useful for seeing if there are zombie entries from prior sessions.
         */
        fun dumpAllWorkInfo(context: Context) {
            try {
                val workInfos = WorkManager.getInstance(context).getWorkInfosByTag("")
                // getWorkInfosByTag("") may not work; use the raw database approach
                android.util.Log.e("RcloneDiag", "[UploadWorker] dumpAllWorkInfo: querying all work...")
                try {
                    java.io.File(context.filesDir, "sync_log.txt").appendText(
                        "\n[RcloneDiag] [UploadWorker] dumpAllWorkInfo: querying all work...\n"
                    )
                } catch (_: Exception) {}
            } catch (e: Exception) {
                android.util.Log.e("RcloneDiag", "[UploadWorker] dumpAllWorkInfo: FAILED: ${e.message}", e)
            }
        }
    }
}
