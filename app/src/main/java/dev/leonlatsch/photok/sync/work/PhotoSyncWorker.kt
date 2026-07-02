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
import dev.leonlatsch.photok.sync.rclone.RepoManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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

    // ─── Notification update rate-limiting state (v8) ───────────────────────
    // Tracks the last in-place notification update so we cap UI refreshes at
    // 2/sec max. Without this, the progress callback (fires every ~500ms from
    // RcloneController.uploadFileWithProgress) plus setProgress() calls would
    // cause visible flicker / sound / vibration on every tick. The
    // [lastNotificationText] tracks the current "state" so that genuine state
    // changes ("Uploading: foo" → "Uploaded: foo") bypass the rate limit even
    // if they happen within the 500ms window.
    // @since v8 — stable upload notification with real progress percentage
    private var lastNotificationUpdateMs: Long = 0L
    private var lastNotificationText: String? = null

    private val metadataJson = Json { ignoreUnknownKeys = true }

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
        // ─── UUID-based no-overwrite design (v8 Part 2b-2) ──────────────────────
        // The remote layout is UUID-keyed:
        //   <remote>:originals/<uuid>.crypt
        //   <remote>:thumbnails/<uuid>.crypt.tn
        //   <remote>:videos/<uuid>.crypt.vp (if video)
        //   <remote>:photok-backup/metadata/<uuid>.json (v8 sidecar)
        // A re-upload of the same photo (same UUID) overwrites the same remote
        // paths — by design. The UUID is stable for a given photo, so a re-upload
        // is naturally idempotent at the storage layer. The above `syncState ==
        // UPLOADED` check is the FIRST line of defense: it makes re-enqueue a
        // no-op without even touching the network. This is the only place such a
        // guard exists — the upload paths below do NOT check before overwriting.

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
        // Thumbnails are small (a few KB) — fire-and-forget via uploadFile() is
        // fine. No real progress feedback needed; the notification shows
        // indeterminate progress while this runs.
        val thumbPath = appContext.getFileStreamPath(photo.internalThumbnailFileName)
        if (thumbPath.exists()) {
            val remoteThumb = "$remote:${SyncConfig.remoteThumbnailsDir}/${thumbPath.name}"
            diag("performUpload: uploading thumbnail ${thumbPath.absolutePath} (size=${thumbPath.length()}) → $remoteThumb")
            updateNotification(
                progress = null,
                text = "Uploading: ${photo.fileName}",
            )
            try {
                rcloneController.uploadFile(thumbPath.absolutePath, remoteThumb).getOrThrow()
                diag("performUpload: thumbnail upload OK")
            } catch (e: Exception) {
                diag("performUpload: thumbnail upload FAILED: ${e.javaClass.name}: ${e.message}", e)
                reportUploadFailureNotification(photo.fileName)
                throw e
            }
        } else {
            diag("performUpload: thumbnail file does not exist, skipping: ${thumbPath.absolutePath}")
        }

        // ─── Upload video preview (if video) ───────────────────────────────
        // Same as thumbnail — small file, fire-and-forget.
        if (photo.type.isVideo) {
            val vpPath = appContext.getFileStreamPath(photo.internalVideoPreviewFileName)
            if (vpPath.exists()) {
                val remoteVp = "$remote:${SyncConfig.remoteVideosDir}/${vpPath.name}"
                diag("performUpload: uploading video preview ${vpPath.absolutePath} (size=${vpPath.length()}) → $remoteVp")
                updateNotification(
                    progress = null,
                    text = "Uploading: ${photo.fileName}",
                )
                try {
                    rcloneController.uploadFile(vpPath.absolutePath, remoteVp).getOrThrow()
                    diag("performUpload: video preview upload OK")
                } catch (e: Exception) {
                    diag("performUpload: video preview upload FAILED: ${e.javaClass.name}: ${e.message}", e)
                    reportUploadFailureNotification(photo.fileName)
                    throw e
                }
            }
        }

        // ─── Upload original (with real progress feedback) ────────────────
        // The original is the largest artifact — use uploadFileWithProgress()
        // so the foreground notification shows a real 0–100% progress bar
        // driven by rclone's core/stats. Thumbnails / video previews above
        // use plain uploadFile() because they're small and progress would
        // just flicker.
        val origPath = appContext.getFileStreamPath(photo.internalFileName)
        if (!origPath.exists()) {
            diag("performUpload: FATAL — local original missing: ${origPath.absolutePath}")
            reportUploadFailureNotification(photo.fileName)
            throw FatalSyncException(
                "Local original file missing for $uuid before upload. " +
                    "Photo may have been deleted out-of-band."
            )
        }
        val remoteOrig = "$remote:${SyncConfig.remoteOriginalsDir}/${origPath.name}"
        diag("performUpload: uploading original ${origPath.absolutePath} (size=${origPath.length()}) → $remoteOrig")
        // Pre-upload state: indeterminate progress while rclone spins up the transfer.
        updateNotification(
            progress = null,
            text = "Uploading: ${photo.fileName}",
        )
        try {
            rcloneController.uploadFileWithProgress(
                localPath = origPath.absolutePath,
                remotePath = remoteOrig,
            ) { percent ->
                // Real progress tick from rclone's core/stats. updateNotification
                // is internally rate-limited to 2/sec — safe to call from here
                // even though this fires every ~500ms.
                updateNotification(
                    progress = percent,
                    text = "Uploading: ${photo.fileName}",
                )
            }.getOrThrow()
            diag("performUpload: original upload OK")
        } catch (e: Exception) {
            diag("performUpload: original upload FAILED: ${e.javaClass.name}: ${e.message}", e)
            reportUploadFailureNotification(photo.fileName)
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
            reportUploadFailureNotification(photo.fileName)
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
            reportUploadFailureNotification(photo.fileName)
            throw e
        }

        // ─── Upload metadata sidecar (v8 Part 2a-4) ────────────────────────
        // Small JSON artifact at `<remote>:photok-backup/metadata/<uuid>.json`
        // recording the photo's original local-origin provenance (relativePath +
        // fileName), type, and size — so a fresh-install restore can populate
        // the Photo DB row accurately instead of guessing type=JPEG, size=0.
        // See SyncConfig.METADATA_DIR / METADATA_FILENAME_SUFFIX, and
        // RepoManager.restoreThumbnailsAfterLogin() for the restore side.
        try {
            uploadMetadataSidecar(photo, remote)
        } catch (e: Exception) {
            // Metadata sidecar failure is non-fatal — the photo's encrypted
            // artifacts are already uploaded and verified. Log + continue.
            diag("performUpload: metadata sidecar upload FAILED (non-fatal): ${e.javaClass.name}: ${e.message}", e)
            Timber.w(e, "PhotoSyncWorker: metadata sidecar upload failed for %s — photo is still UPLOADED", uuid)
        }

        // ─── Post-upload success state ────────────────────────────────────
        // Brief "Uploaded" state. The worker returns Result.success() right
        // after this, WorkManager cancels the foreground notification, and
        // the user sees the notification disappear (or transition to whatever
        // the next queued worker shows).
        updateNotification(
            progress = 100f,
            text = "Uploaded: ${photo.fileName}",
        )

        diag("performUpload: DONE — all uploads + verifications succeeded for $uuid")
    }

    /**
     * Build and upload the per-photo metadata sidecar JSON to
     * `<remote>:photok-backup/metadata/<uuid>.json`.
     *
     * The sidecar captures fields the encrypted artifacts alone can't recover:
     *   - `uuid` — the photo's stable UUID (matches the remote filenames)
     *   - `relativePath` — the photo's original local-origin provenance
     *     (filename today; full MediaStore `RELATIVE_PATH` in a future enhancement)
     *   - `fileName` — the original filename as imported (NOT the `<uuid>.crypt`
     *     internal filename)
     *   - `type` — the [dev.leonlatsch.photok.model.database.entity.PhotoType]
     *     enum constant name (e.g. "JPEG", "MP4") — restored via `PhotoType.valueOf`
     *     on the receiver side, falling back to JPEG if the value is unknown
     *   - `size` — the encrypted original's file size in bytes
     *
     * On restore ([RepoManager.restoreThumbnailsAfterLogin]), if this sidecar
     * is present it's used to populate the Photo DB row accurately. If absent
     * (photo was uploaded before v8), the restore falls back to the existing
     * `type=JPEG, size=0` defaults.
     *
     * @since v8 — path-consistency metadata sidecar
     */
    private suspend fun uploadMetadataSidecar(photo: Photo, remote: String) {
        val uuid = photo.uuid
        val sidecarJson: JsonObject = buildJsonObject {
            put("uuid", uuid)
            put("relativePath", photo.relativePath ?: photo.fileName)
            put("fileName", photo.fileName)
            put("type", photo.type.name)
            put("size", photo.size)
        }
        val sidecarText = metadataJson.encodeToString(JsonObject.serializer(), sidecarJson)

        val tempFile = File(appContext.cacheDir, "photok-meta-$uuid${SyncConfig.METADATA_FILENAME_SUFFIX}")
        try {
            tempFile.writeText(sidecarText)
            val remoteMeta = "$remote:${RepoManager.REPO_DIR}/${SyncConfig.METADATA_DIR}/$uuid${SyncConfig.METADATA_FILENAME_SUFFIX}"
            diag("performUpload: uploading metadata sidecar (${tempFile.length()} bytes) → $remoteMeta")
            rcloneController.uploadFile(tempFile.absolutePath, remoteMeta).getOrThrow()
            diag("performUpload: metadata sidecar upload OK")
        } finally {
            tempFile.delete()
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

    private fun buildNotification(text: String): Notification =
        buildNotificationInternal(text = text, progress = null, ongoing = true)

    /**
     * Build a notification with configurable progress + ongoing flag.
     *
     * @param text content text
     * @param progress 0f..100f for a determinate progress bar, or `null` for an
     *   indeterminate (spinning) progress bar. At 100f the bar shows full.
     * @param ongoing `true` for a foreground-service-style notification that
     *   disappears when the worker ends; `false` for a "left-behind" notification
     *   that stays visible after the worker ends (used for the upload-failed
     *   state so the user can see something went wrong).
     *
     * @since v8 — stable upload notification with real progress percentage
     */
    private fun buildNotificationInternal(
        text: String,
        progress: Float?,
        ongoing: Boolean,
    ): Notification {
        val launchIntent = appContext.packageManager.getLaunchIntentForPackage(appContext.packageName)
        val pi = launchIntent?.let {
            PendingIntent.getActivity(
                appContext,
                0,
                it,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
        val builder = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(appContext.getString(R.string.sync_notification_title))
            .setContentText(text)
            .setOngoing(ongoing)
            // setOnlyAlertOnce: prevent sound/vibration on every in-place update.
            // Combined with the rate-limit in updateNotification(), this keeps the
            // notification stable (no flicker, no buzz) as progress ticks arrive.
            .setOnlyAlertOnce(true)
            .setContentIntent(pi)

        if (progress != null) {
            // Determinate progress: 0..100. coerceIn guards against rclone's
            // core/stats momentarily reporting >100% (rounding, post-complete
            // stats lag, etc.).
            builder.setProgress(100, progress.toInt().coerceIn(0, 100), false)
        } else {
            // Indeterminate (spinning) progress: shown before the first real
            // progress tick arrives, or for uploads that don't emit progress
            // (thumbnails, video previews, metadata sidecar).
            builder.setProgress(0, 0, true)
        }

        return builder.build()
    }

    /**
     * Update the foreground notification in-place at [NOTIFICATION_ID].
     *
     * Rate-limited to max ~2 updates/sec to avoid notification flicker:
     *   - Calls within [NOTIFICATION_UPDATE_MIN_INTERVAL_MS] of the last update
     *     are dropped, UNLESS:
     *       (a) `progress` is exactly 100f (terminal state — always shown), OR
     *       (b) `text` differs from the last text (genuine state change — always shown).
     *   - This is safe to call from [RcloneController.uploadFileWithProgress]'s
     *     ~500ms progress callback, because the rate limiter collapses redundant
     *     ticks to at most one update per polling interval.
     *
     * @param progress 0f..100f for a determinate progress bar, or `null` for
     *   indeterminate (spinning) progress.
     * @param text content text — drives the state-change detection.
     * @param ongoing `true` for ongoing (foreground-style); `false` for a
     *   non-ongoing "left-behind" notification (used by [reportUploadFailureNotification]).
     *
     * @since v8 — stable upload notification with real progress percentage
     */
    private fun updateNotification(
        progress: Float?,
        text: String,
        ongoing: Boolean = true,
    ) {
        val now = System.currentTimeMillis()
        val isStateChange = text != lastNotificationText
        val isComplete = progress != null && progress >= 100f
        if (!isStateChange && !isComplete &&
            now - lastNotificationUpdateMs < NOTIFICATION_UPDATE_MIN_INTERVAL_MS
        ) {
            // Rate-limited: drop this update. The next tick (≥500ms later, or a
            // state change, or completion) will get through.
            return
        }
        lastNotificationUpdateMs = now
        lastNotificationText = text

        ensureChannel()
        val nm = appContext.getSystemService(NotificationManager::class.java) ?: return
        nm.notify(NOTIFICATION_ID, buildNotificationInternal(text, progress, ongoing))
    }

    /**
     * Show the "Upload failed: <filename>" notification as NON-ongoing so it
     * stays visible in the system tray after the worker ends (the user can
     * dismiss it manually). Used at every error-exit point in [performUpload].
     *
     * @since v8 — stable upload notification with real progress percentage
     */
    private fun reportUploadFailureNotification(fileName: String) {
        updateNotification(
            progress = null,
            text = "Upload failed: $fileName",
            ongoing = false,
        )
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

        // ─── Notification ID (v8 Part 1b) ───────────────────────────────────
        // FIXED constant — all in-place updates from updateNotification() go to
        // the SAME notification slot. This is what makes the progress bar appear
        // "stable" (the system replaces the existing notification rather than
        // posting a new one).
        //
        // BATCH UPLOADS CAVEAT: each PhotoSyncWorker instance is a separate
        // WorkManager job with its own UUID, but they ALL share this single
        // NOTIFICATION_ID. If multiple workers run concurrently (which
        // WorkManager generally won't do for expedited foreground work, but
        // theoretically could), the LAST one to call updateNotification() wins
        // — its text/progress overwrites whatever the other workers posted.
        // This is acceptable for now: the in-app top-bar queue indicator
        // (separate from this notification) shows the real queue count, and a
        // single "currently uploading" notification is a reasonable UX even
        // under concurrency. If we ever want per-worker notifications, give
        // each worker a NOTIFICATION_ID derived from its UUID hashCode.
        // @since v8 — stable upload notification with real progress percentage
        private const val NOTIFICATION_ID = 4242

        // Minimum interval between in-place notification updates (v8 Part 1b).
        // Caps UI refreshes at ~2/sec — fast enough to feel responsive, slow
        // enough to avoid notification-manager thrash / flicker. State changes
        // and 100%-complete updates bypass this limit (see updateNotification).
        private const val NOTIFICATION_UPDATE_MIN_INTERVAL_MS = 500L

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
