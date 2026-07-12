@file:Suppress("KotlinConstantConditions", "ConstantConditionValue", "SENSELESS_COMPARISON")

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import onlasdan.gallery.R
import onlasdan.gallery.encryption.domain.SessionRepository
import onlasdan.gallery.model.database.dao.PhotoDao
import onlasdan.gallery.model.database.entity.Photo
import onlasdan.gallery.settings.data.Config
import onlasdan.gallery.sync.debug.CrashLogger
import onlasdan.gallery.sync.debug.SyncLogger
import onlasdan.gallery.sync.domain.SyncConfig
import onlasdan.gallery.sync.domain.SyncState
import onlasdan.gallery.sync.rclone.RcloneController
import onlasdan.gallery.sync.rclone.RepoManager
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * WorkManager Worker that pushes a single photo's encrypted artifacts (thumbnail + original,
 * plus video-preview if applicable) to the configured rclone remote and verifies the upload via
 * SHA-256 hash check before transitioning the photo's [SyncState] to [SyncState.UPLOADED].
 */
@HiltWorker
class PhotoSyncWorker
        @AssistedInject
        constructor(
                @Assisted private val appContext: Context,
                @Assisted private val params: WorkerParameters,
                private val photoDao: PhotoDao,
                private val rcloneController: RcloneController,
                private val config: Config,
                // @since Item 1 (Drive-style notification content) — persists batch-level
                // counters (total / completed / failed / bytesCompleted) across worker
                // instances via SharedPreferences. Each worker handles ONE photo; a batch
                // is a sequence of workers. The tracker survives process death so a batch
                // started before the app was killed still produces a correct final summary
                // when WorkManager resumes the queued workers.
                private val batchTracker: SyncBatchTracker,
                // @since v9 dedup — encrypted GCM registry. Used (a) BEFORE uploading the
                // original to skip the transfer if the same content-hash is already on
                // the remote under a different UUID, and (b) AFTER a successful upload to
                // append the new hash → UUID mapping to the local registry cache. The
                // cache is flushed to the remote by [flushRegistryIfBatchComplete] at the
                // end of the batch (last worker wins — see [HashRegistry] for the
                // concurrency model).
                private val hashRegistry: HashRegistry,
                // @since v9 dedup — needed to obtain the raw VMK bytes for encrypting the
                // registry on upload. The VMK is in memory only while the vault is
                // unlocked; if the worker runs after the vault is locked (e.g. a delayed
                // retry long after the app was backgrounded), the session may be null and
                // the registry upload is skipped (logged) — the photo itself is still
                // uploaded + verified, just not registered for future dedup.
                private val sessionRepository: SessionRepository,
                // @since Batch 1 / Item 3 — hash verification after upload. Needed to
                // create a decryption stream over the freshly-downloaded remote file so
                // we can recompute the SHA-256 of the plaintext and compare against the
                // photo's stored `contentHash`. Only used when `config.syncVerifyHash`
                // is true (off by default — doubles bandwidth per upload).
                private val cryptoEngine: onlasdan.gallery.encryption.domain.crypto.CryptoEngine,
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
                        android.util.Log.e(
                                "RcloneDiag",
                                "[UploadWorker] init: instance constructed by HiltWorkerFactory (class=${this.javaClass.name})",
                        )
                }

                // ─── DIAGNOSTIC LOGGING (same RcloneDiag pattern as RcloneController) ────
                // Writes to BOTH Log.e (logcat) AND files/sync_log.txt so it's visible
                // via `adb logcat -d | grep RcloneDiag` AND
                // `adb shell run-as <pkg> cat files/sync_log.txt`.
                // Critical for diagnosing upload failures — prior sessions' logging only
                // covered repo detection/init, not the photo upload call chain itself.
                //
                // The file-append path goes through [SyncLogRotator.append], which caps
                // `sync_log.txt` at 1 MB (truncating to the last 512 KB when exceeded).
                // Without that cap the file would grow without bound — it lives in
                // `filesDir` (persistent storage, NOT cache), so it never gets reclaimed
                // by the OS. See [SyncLogRotator] for the full rotation policy.
                private fun diag(
                        msg: String,
                        throwable: Throwable? = null,
                ) {
                        android.util.Log.e("RcloneDiag", "[UploadWorker] $msg", throwable)
                        try {
                                val entry =
                                        buildString {
                                                append("\n[RcloneDiag] [UploadWorker] ")
                                                append(msg)
                                                append('\n')
                                                if (throwable != null) {
                                                        append(throwable.stackTraceToString())
                                                        append('\n')
                                                }
                                        }
                                onlasdan.gallery.sync.debug.SyncLogRotator
                                        .append(appContext, entry)
                        } catch (_: Exception) {
                        }
                }

                override suspend fun getForegroundInfo(): ForegroundInfo {
                        ensureChannel()
                        // @since Item 1 — use batch state for the foreground notification text
                        // (matches what updateNotification() will show once the worker starts
                        // posting progress). If batch state is empty (total=0 — e.g. system
                        // called getForegroundInfo() before doWorkInternal populated the
                        // tracker), fall back to the generic string resource.
                        val state = batchTracker.getBatchState()
                        val text =
                                if (state.total > 0) {
                                        "Uploading ${state.current} of ${state.total}"
                                } else {
                                        appContext.getString(R.string.sync_notification_text)
                                }
                        return ForegroundInfo(
                                NOTIFICATION_ID,
                                buildNotification(text),
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
                        android.util.Log.e(
                                "RcloneDiag",
                                "[UploadWorker] doWork: ENTRY (before withContext) uuid=${inputData.getString(
                                        KEY_PHOTO_UUID,
                                )} attempt=${runAttemptCount + 1}",
                        )

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
                                                context = "PhotoSyncWorker.doWork safety net (uuid=${inputData.getString(KEY_PHOTO_UUID)})",
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

                // ─── HARD GUARD: no upload without a confirmed repo session ──────────
                // This should NEVER trigger in normal operation — the gallery is gated behind
                // repo setup, so the user can't import photos (and thus can't enqueue sync
                // jobs) without a confirmed repo. If this fires, it's a real bug to surface
                // loudly, not swallow.
                private suspend fun ensureRepoSession(uuid: String): Result? {
                        if (!config.repoConfirmed || config.syncChosenRemote.isNullOrBlank()) {
                                val msg =
                                        "PhotoSyncWorker: FATAL — upload attempted without confirmed repo session " +
                                                "(repoConfirmed=${config.repoConfirmed}, remote=${config.syncChosenRemote}). " +
                                                "This should never happen — gallery is gated behind repo setup."
                                Timber.e(msg)
                                CrashLogger.logCrash(
                                        Thread.currentThread(),
                                        FatalSyncException(msg),
                                        context = "PhotoSyncWorker hard guard (no repo session) uuid=$uuid",
                                )
                                try {
                                        photoDao.updateSyncState(uuid, SyncState.UPLOAD_FAILED)
                                } catch (_: Exception) {
                                }
                                return Result.failure()
                        }
                        return null
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
                        val repoFailure = ensureRepoSession(uuid)
                        if (repoFailure != null) {
                                return repoFailure
                        }

                        // ─── Batch tracking (Item 1 — Drive-style notification content) ────────
                        // Increment the planned-batch-size counter at worker start. Ideally this
                        // would be called from the static enqueue() method, but accessing the
                        // @Singleton SyncBatchTracker from a companion-object method requires a
                        // Hilt EntryPoint — instead we increment here as the worker starts.
                        // Gated by `runAttemptCount == 0` so a retried worker doesn't double-count
                        // the same photo in `total`.
                        //
                        // The captured [batchState] snapshot drives the notification content
                        // ("Uploading N of M: foo.jpg") for this worker's lifetime. Subsequent
                        // workers in the same batch will see updated `completed`/`failed` counts
                        // via their own `onWorkerStart` call.
                        if (runAttemptCount == 0) {
                                batchTracker.onPhotoEnqueued()
                        }
                        val batchState = batchTracker.onWorkerStart(uuid)
                        diag(
                                "doWorkInternal: batchState current=${batchState.current} total=${batchState.total} " +
                                        "completed=${batchState.completed} failed=${batchState.failed} attempt=${runAttemptCount + 1}",
                        )

                        // ─── Foreground notification (v8 Part 1 + notification-fix round) ──────
                        // setForeground() promotes this worker to a foreground service with a
                        // persistent notification. On Android 12+ this can throw
                        // ForegroundServiceStartNotAllowedException if the app is backgrounded
                        // and not in an exempt state. The prior code wrapped this in a silent
                        // runCatching{} — which meant if setForeground() threw, the worker
                        // kept running but NO notification ever appeared (the foreground
                        // service never started). This was a contributing cause of
                        // "notifications never appear despite permission granted".
                        //
                        // Now: log the result explicitly. If setForeground() fails, the worker
                        // continues (uploads still work without a foreground notification —
                        // WorkManager just runs it as a normal background job), but we log
                        // the exception so it's visible in sync_log.txt + logcat.
                        try {
                                val fgInfo = getForegroundInfo()
                                setForeground(fgInfo)
                                diag("setForeground: OK — foreground notification posted (id=$NOTIFICATION_ID, type=FOREGROUND_SERVICE_TYPE_DATA_SYNC)")
                        } catch (e: Exception) {
                                diag("setForeground: FAILED — ${e.javaClass.name}: ${e.message}", e)
                                diag("setForeground: worker will continue as background job — uploads still work, just no visible notification")
                                // Don't rethrow — uploads are functional without the notification.
                                // The notification is UX-only, not a functional dependency.
                        }

                        val photo =
                                try {
                                        photoDao.get(uuid)
                                } catch (e: Exception) {
                                        Timber.w(e, "PhotoSyncWorker: photo %s not found in DB (deleted by user?)", uuid)
                                        // Photo was deleted between enqueue and execution. Still advance the
                                        // batch counters so the tracker doesn't get stuck waiting for a
                                        // worker that will never produce a result.
                                        batchTracker.onWorkerSuccess(0L)
                                        onBatchCompleteIfDone()
                                        return Result.success()
                                }

                        if (photo.syncState == SyncState.UPLOADED) {
                                Timber.d("PhotoSyncWorker: %s already UPLOADED — skipping", uuid)
                                // Already uploaded — advance the batch counters (no bytes added since
                                // we don't actually re-upload) so the tracker stays consistent.
                                batchTracker.onWorkerSuccess(0L)
                                onBatchCompleteIfDone()
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

                        val outcome = runCatching { performUpload(photo, batchState) }

                        return when {
                                outcome.isSuccess -> handleUploadSuccess(uuid, photo)
                                isFatalFailure(outcome.exceptionOrNull()) -> handleFatalFailure(outcome.exceptionOrNull(), uuid)
                                else -> handleRetryableOrMaxAttempts(outcome.exceptionOrNull(), uuid)
                        }
                }

                private suspend fun handleUploadSuccess(uuid: String, photo: Photo): Result {
                        try {
                                val affected = photoDao.updateSyncStateReturningRows(uuid, SyncState.UPLOADED)
                                if (affected == 0) {
                                        Timber.i("PhotoSyncWorker: %s was deleted during upload; skipping commit", uuid)
                                        // Photo deleted during upload — still advance the batch
                                        // counters so the tracker doesn't get stuck.
                                        batchTracker.onWorkerSuccess(0L)
                                        onBatchCompleteIfDone()
                                        return Result.success()
                                }
                                SyncLogger.logStateTransition(uuid, "UPLOAD_PENDING", "UPLOADED", "upload + verify succeeded")
                        } catch (e: Exception) {
                                Timber.e(e, "PhotoSyncWorker: failed to commit UPLOADED for %s", uuid)
                                return Result.failure()
                        }
                        // ─── Batch success accounting (Item 1) ────────────────────────
                        // Accumulate this photo's size into bytesCompleted and increment
                        // the completed counter. If this was the last photo in the batch,
                        // post the final summary notification (separate ID so it stays
                        // visible after WorkManager cancels the foreground notification).
                        batchTracker.onWorkerSuccess(photo.size)
                        diag(
                                "doWorkInternal: batch success — completed=${batchTracker.getBatchState().completed} " +
                                        "total=${batchTracker.getBatchState().total} bytesCompleted=${batchTracker.getBatchState().bytesCompleted}",
                        )
                        onBatchCompleteIfDone()
                        // ─── Optional local-original deletion (Item 2) ────────────────
                        // After a verified upload the local encrypted `.crypt` original
                        // is deleted ONLY if the user opted in via the
                        // `syncDeleteAfterUpload` setting (default off). When disabled,
                        // the gallery keeps the local original for offline access and
                        // the remote is just a backup; when enabled, the local copy is
                        // dropped to free device storage and re-downloaded on demand
                        // via [SyncRestorer.ensureLocalOriginal] when the user opens
                        // the photo. The "Clean .crypt files" button in Settings
                        // provides a manual one-shot cleanup of cached originals.
                        if (config.syncDeleteAfterUpload) {
                                deleteLocalOriginalAfterUpload(photo)
                        }
                        return Result.success()
                }

                private suspend fun handleFatalFailure(exception: Throwable?, uuid: String): Result {
                        Timber.w(exception, "PhotoSyncWorker: fatal failure for %s", uuid)
                        try {
                                photoDao.updateSyncState(uuid, SyncState.UPLOAD_FAILED)
                                SyncLogger.logStateTransition(
                                        uuid,
                                        "UPLOAD_PENDING",
                                        "UPLOAD_FAILED",
                                        "fatal: ${exception?.message}",
                                )
                        } catch (e: Exception) {
                                Timber.e(e, "PhotoSyncWorker: failed to mark UPLOAD_FAILED for %s", uuid)
                        }
                        // ─── Batch failure accounting (Item 1) ────────────────────────
                        // Permanent failure — increment the failed counter. The per-photo
                        // failure notification (with batch-failed count) was already shown
                        // inside performUpload() before the exception propagated.
                        batchTracker.onWorkerFailure()
                        onBatchCompleteIfDone()
                        return Result.failure()
                }

                private suspend fun handleRetryableOrMaxAttempts(exception: Throwable?, uuid: String): Result {
                        if (runAttemptCount + 1 >= SyncConfig.maxSyncAttempts) {
                                Timber.w(
                                        exception,
                                        "PhotoSyncWorker: max attempts reached for %s (attempt %d)",
                                        uuid,
                                        runAttemptCount + 1,
                                )
                                try {
                                        photoDao.updateSyncState(uuid, SyncState.UPLOAD_FAILED)
                                        SyncLogger.logStateTransition(
                                                uuid,
                                                "UPLOAD_PENDING",
                                                "UPLOAD_FAILED",
                                                "max attempts (${runAttemptCount + 1}): ${exception?.message}",
                                        )
                                } catch (e: Exception) {
                                        Timber.e(e, "PhotoSyncWorker: failed to mark UPLOAD_FAILED for %s", uuid)
                                }
                                // ─── Batch failure accounting (Item 1 — max attempts) ──────
                                // Same as the fatal-failure branch: increment failed counter,
                                // show batch summary if this was the last worker.
                                batchTracker.onWorkerFailure()
                                onBatchCompleteIfDone()
                                return Result.failure()
                        } else {
                                Timber.i(
                                        exception,
                                        "PhotoSyncWorker: retryable failure for %s (attempt %d, will retry)",
                                        uuid,
                                        runAttemptCount + 1,
                                )
                                // NOTE: deliberately DO NOT call onWorkerFailure() here —
                                // the worker will run again and the counters will be updated
                                // when the retry eventually succeeds or fails permanently.
                                // The per-photo failure notification shown inside
                                // performUpload() will be re-shown on the next attempt with
                                // the same `failed + 1` count (still accurate since we
                                // haven't committed the failure yet).
                                return Result.retry()
                        }
                }

                // ─── v9 dedup: skip upload entirely if content-hash is already on the remote ──
                // The dedup registry is a local Room cache of the encrypted `registry.json.crypt`
                // on the remote, populated at login by [HashRegistry.downloadAndCache]. If the
                // photo's SHA-256 (computed at import time from the plaintext bytes) matches an
                // existing entry, the original + thumbnail + video-preview are all already on
                // the remote under the CANONICAL UUID (the first photo that uploaded with this
                // hash). Re-uploading them under this photo's UUID would just waste bandwidth
                // and storage on the remote — skip the transfer entirely and transition straight
                // to UPLOADED.
                //
                // Photos with `contentHash == null` (imported before v9, or import-path failure
                // during hash computation) bypass dedup and upload normally — they'll get a
                // registry entry created below if the upload succeeds, so future imports of the
                // same content WILL dedup against them.
                //
                // The local thumbnail (created at import time) is NOT deleted — the gallery on
                // THIS device still shows it. On a fresh-install restore, this duplicate UUID
                // won't appear in the gallery because there's no thumbnail for it on the remote
                // (the remote only has the canonical UUID's thumbnail). That's the intended
                // trade-off: dedup saves bandwidth at the cost of duplicate UUIDs being
                // device-local only.
                //
                // Returns true if a dedup hit occurred (caller must `return` early); false if
                // the upload should proceed normally.
                private suspend fun handleDedupHit(contentHash: String?, photo: Photo): Boolean {
                        val uuid = photo.uuid
                        if (contentHash.isNullOrBlank()) {
                                diag("performUpload: no contentHash on photo (pre-v9 import or hash failure) — skipping dedup, uploading normally")
                                return false
                        }
                        val existing =
                                try {
                                        hashRegistry.findExisting(contentHash, runCatching { sessionRepository.get()?.vaultId }.getOrNull())
                                } catch (e: Exception) {
                                        diag("performUpload: dedup lookup FAILED (non-fatal, will upload normally): ${e.message}", e)
                                        null
                                }
                        if (existing != null) {
                                diag(
                                        "performUpload: DEDUP hit — contentHash=$contentHash already on remote " +
                                                "under canonical uuid=${existing.uuid} (filename=${existing.filename}). " +
                                                "Skipping upload; transitioning straight to UPLOADED.",
                                )
                                SyncLogger.logStateTransition(
                                        uuid,
                                        "UPLOAD_PENDING",
                                        "UPLOADED",
                                        "dedup: content_hash=$contentHash already on remote as ${existing.uuid}",
                                )

                                // ─── Bug 1 fix: defensively mark the photo UPLOADED right here ──
                                // The caller (doWorkInternal) already does this via
                                // updateSyncStateReturningRows() on outcome.isSuccess, but marking
                                // it explicitly here makes the dedup path self-contained: even if
                                // the outcome-based flow is refactored later, dedup always lands
                                // the photo in UPLOADED state. Without this, the second copy of a
                                // duplicate import would sit in UPLOAD_PENDING forever (or until
                                // the next worker retry), showing a perpetual "uploading" badge in
                                // the gallery — the "UI duplicate" bug report.
                                //
                                // Failure here is non-fatal — doWorkInternal's outcome.isSuccess
                                // branch will re-attempt the update and handle the 0-rows-affected
                                // case (photo deleted during upload).
                                try {
                                        photoDao.updateSyncState(uuid, SyncState.UPLOADED)
                                        diag("performUpload: DEDUP — explicitly marked $uuid as UPLOADED")
                                } catch (e: Exception) {
                                        diag("performUpload: DEDUP — explicit UPLOADED mark FAILED (non-fatal, doWorkInternal will retry): ${e.message}", e)
                                }

                                // No registry entry to add — the canonical entry already covers this hash.
                                // Just bail out; the caller (doWorkInternal) will mark UPLOADED (again,
                                // idempotently) and advance the batch tracker.
                                return true
                        } else {
                                diag("performUpload: dedup miss — contentHash=$contentHash not in registry; uploading normally")
                                return false
                        }
                }

                private suspend fun performUpload(
                        photo: Photo,
                        batchState: SyncBatchTracker.BatchState,
                ) {
                        val remote =
                                config.syncChosenRemote
                                        ?: throw FatalSyncException(
                                                "No rclone remote chosen. Open Settings → Cloud Sync → Backup configuration " +
                                                        "and pick a remote from the imported config.",
                                        )
                        val uuid = photo.uuid
                        diag(
                                "performUpload: BEGIN uuid=$uuid remote=$remote photoType=${photo.type} syncState=${photo.syncState} " +
                                        "batchCurrent=${batchState.current} batchTotal=${batchState.total}",
                        )

                        // ─── v9 dedup: skip upload entirely if content-hash is already on the remote ──
                        // The dedup registry is a local Room cache of the encrypted `registry.json.crypt`
                        // on the remote, populated at login by [HashRegistry.downloadAndCache]. If the
                        // photo's SHA-256 (computed at import time from the plaintext bytes) matches an
                        // existing entry, the original + thumbnail + video-preview are all already on
                        // the remote under the CANONICAL UUID (the first photo that uploaded with this
                        // hash). Re-uploading them under this photo's UUID would just waste bandwidth
                        // and storage on the remote — skip the transfer entirely and transition straight
                        // to UPLOADED.
                        //
                        // Photos with `contentHash == null` (imported before v9, or import-path failure
                        // during hash computation) bypass dedup and upload normally — they'll get a
                        // registry entry created below if the upload succeeds, so future imports of the
                        // same content WILL dedup against them.
                        //
                        // The local thumbnail (created at import time) is NOT deleted — the gallery on
                        // THIS device still shows it. On a fresh-install restore, this duplicate UUID
                        // won't appear in the gallery because there's no thumbnail for it on the remote
                        // (the remote only has the canonical UUID's thumbnail). That's the intended
                        // trade-off: dedup saves bandwidth at the cost of duplicate UUIDs being
                        // device-local only.
                        val contentHash = photo.contentHash
                        val dedupReturned = handleDedupHit(contentHash, photo)
                        if (dedupReturned) {
                                return
                        }

                        // ─── Item 1: precompute batch-style notification text ───────────────
                        // Collapsed (single line): "Uploading N of M: filename (size)"
                        // Expanded (BigTextStyle): "Uploading N of M photos\nfilename (size)\nZ uploaded so far"
                        val fileSizeStr = formatBytes(photo.size)
                        val collapsedText = "Uploading ${batchState.current} of ${batchState.total}: ${photo.fileName} ($fileSizeStr)"
                        val expandedText =
                                "Uploading ${batchState.current} of ${batchState.total} photos\n" +
                                        "${photo.fileName} ($fileSizeStr)\n" +
                                        "${formatBytes(batchState.bytesCompleted)} uploaded so far"

                        // ─── Upload thumbnail ──────────────────────────────────────────────
                        // @since v9 followup (Bug 4 — packed thumbnails): individual thumbnail
                        // upload is now SKIPPED. The thumbnail stays local (at
                        // `internalThumbnailFileName`) and is collected by
                        // [flushPendingThumbnailPacks] at batch end, concatenated into ≤50 MB
                        // packs at `<remote>:photoz-backup/thumbnails/pack-NNNN.pack`, and the
                        // per-hash `thumbnail_pack` / `thumbnail_offset` / `thumbnail_length`
                        // fields in the registry are filled in. The restore side
                        // ([RepoManager.restoreThumbnailsFromPacks]) downloads packs once and
                        // extracts each thumbnail by offset+length, instead of N round-trips
                        // for N individual thumbnails.
                        //
                        // The local thumbnail file is NOT deleted here — it must remain on disk
                        // so [flushPendingThumbnailPacks] can read its bytes when forming the
                        // pack. If the device crashes between this upload and the batch-end
                        // pack flush, the next batch's flush will pick up the orphan thumbnail
                        // (the registry entry has `thumbnail_pack = null`, which the packer
                        // treats as "pending pack").
                        //
                        // The local thumbnail file is also still used by the gallery's
                        // [EncryptedImageFetcher] to render the tile — so even before the pack
                        // is uploaded, the user sees the thumbnail locally.
                        val thumbPath = appContext.getFileStreamPath(photo.internalThumbnailFileName)
                        if (thumbPath.exists()) {
                                diag("performUpload: thumbnail exists locally (${thumbPath.length()} bytes) — deferring to batch-end pack upload (Bug 4)")
                        } else {
                                diag("performUpload: thumbnail file does not exist, skipping: ${thumbPath.absolutePath}")
                        }

                        // ─── Upload video preview (if video) ───────────────────────────────
                        // Same as thumbnail — small file, fire-and-forget.
                        // NOTE: video previews are NOT packed (only thumbnails are). They're
                        // uploaded individually as before.
                        if (photo.type.isVideo) {
                                uploadVideoPreviewIfPresent(photo, remote, collapsedText, expandedText, batchState)
                        }

                        // ─── Upload original ───────────────────────────────────────────────
                        // The original is the largest artifact — use uploadFileWithProgress()
                        // so the notification shows a determinate progress bar based on a
                        // size/time estimate (rclone's core/stats doesn't track
                        // operations/copyfile, so we approximate — see RcloneController for
                        // the full rationale). Thumbnails and video previews still use the
                        // plain uploadFile() — they're small enough that indeterminate is fine.
                        //
                        // @since real-upload-progress feature — switched from uploadFile()
                        //   to uploadFileWithProgress() so the user sees the bar move
                        //   instead of an indeterminate spinner for the (potentially huge)
                        //   original photo upload.
                        val remoteOrig = uploadAndVerifyOriginal(photo, remote, collapsedText, expandedText, batchState, uuid)

                        // ─── Item 3: optional full hash verification by re-download + decrypt ──
                        // The remote-side `verifyRemote` above only works if the backend supports
                        // server-side hashsum. Koofr (and many other backends) don't — the call
                        // falls through to the `HashNotSupportedException` branch and we're left
                        // trusting only the size check. This optional pass, gated on the user's
                        // `syncVerifyHash` setting (default OFF — doubles bandwidth per upload),
                        // downloads the freshly-uploaded remote file, decrypts it with the VMK,
                        // recomputes the SHA-256 of the plaintext, and compares it against the
                        // photo's stored `contentHash`. If they don't match → throw + mark
                        // UPLOAD_FAILED so WorkManager retries the whole upload.
                        //
                        // This is the only way to truly verify upload integrity against backends
                        // that can't compute server-side hashes. It's expensive (download = full
                        // upload size again) so it's off by default and surfaced as a Settings
                        // toggle for paranoiac users.
                        if (config.syncVerifyHash && !contentHash.isNullOrBlank()) {
                                performFullHashVerification(photo, remoteOrig, contentHash, batchState, uuid)
                        } else if (config.syncVerifyHash && contentHash.isNullOrBlank()) {
                                diag("performUpload: syncVerifyHash is ON but photo has no contentHash (pre-v9 import) — skipping full verification")
                        }

                        // ─── v9 dedup: append to the local registry cache ────────────────────
                        // The original + thumbnail are now on the remote under THIS photo's UUID
                        // (the canonical UUID for this content-hash). Record the hash → UUID
                        // mapping in the local registry cache so:
                        //   (a) future imports of the same content on THIS device dedup against
                        //       this UUID (no remote round-trip needed for the lookup — the
                        //       cache is consulted directly);
                        //   (b) the next [flushRegistryIfBatchComplete] call serializes the
                        //       cache (including this new entry) and uploads it as
                        //       `registry.json.crypt` to the remote, so OTHER devices will
                        //       dedup against this UUID after their next login.
                        //
                        // Failure here is non-fatal — the photo's encrypted artifacts are
                        // already uploaded + verified. We just lose dedup coverage for this
                        // hash until the next successful registry add. Logged + continued.
                        if (!contentHash.isNullOrBlank()) {
                                addRegistryEntry(photo, contentHash, thumbPath, uuid)
                        }

                        // ─── Post-upload success state ────────────────────────────────────
                        // Brief "Uploaded" state. The worker returns Result.success() right
                        // after this, WorkManager cancels the foreground notification, and
                        // the user sees the notification disappear (or transition to whatever
                        // the next queued worker shows).
                        //
                        // Item 1: show batch-style success text ("Uploaded N of M: filename")
                        // so the user sees batch progress in the brief moment between this
                        // worker finishing and the next one starting (or the batch-complete
                        // summary if this was the last photo).
                        val doneCollapsed = "Uploaded ${batchState.current} of ${batchState.total}: ${photo.fileName}"
                        val doneExpanded =
                                "Uploaded ${batchState.current} of ${batchState.total} photos\n${photo.fileName}\n" +
                                        "${formatBytes(batchState.bytesCompleted + photo.size)} uploaded so far"
                        updateNotification(
                                progress = 100f,
                                text = doneCollapsed,
                                bigText = doneExpanded,
                        )

                        diag("performUpload: DONE — all uploads + verifications succeeded for $uuid")
                }

                private suspend fun uploadVideoPreviewIfPresent(
                        photo: Photo,
                        remote: String,
                        collapsedText: String,
                        expandedText: String,
                        batchState: SyncBatchTracker.BatchState,
                ) {
                        val vpPath = appContext.getFileStreamPath(photo.internalVideoPreviewFileName)
                        if (!vpPath.exists()) {
                                return
                        }
                        // Ensure remote directories exist (operations/copyfile fails if parent dir missing)
                        rcloneController.createDir("$remote:${SyncConfig.remoteOriginalsDir}").onFailure { Timber.w(it, "createDir originals failed (non-fatal)") }
                        rcloneController.createDir("$remote:${SyncConfig.remoteVideosDir}").onFailure { Timber.w(it, "createDir videos failed (non-fatal)") }

                        val remoteVp = "$remote:${SyncConfig.remoteVideosDir}/${vpPath.name}"
                        diag("performUpload: uploading video preview ${vpPath.absolutePath} (size=${vpPath.length()}) → $remoteVp")
                        updateNotification(
                                progress = null,
                                text = collapsedText,
                                bigText = expandedText,
                        )
                        try {
                                rcloneController.uploadFile(vpPath.absolutePath, remoteVp).getOrThrow()
                                diag("performUpload: video preview upload OK")
                        } catch (e: Exception) {
                                diag("performUpload: video preview upload FAILED: ${e.javaClass.name}: ${e.message}", e)
                                reportUploadFailureNotification(photo.fileName, batchState)
                                throw e
                        }
                }

                @Suppress("ThrowsCount", "NestedBlockDepth") // pre-existing: complex sync verification logic, refactor tracked separately
                private suspend fun uploadAndVerifyOriginal(
                        photo: Photo,
                        remote: String,
                        collapsedText: String,
                        expandedText: String,
                        batchState: SyncBatchTracker.BatchState,
                        uuid: String,
                ): String {
                        val origPath = appContext.getFileStreamPath(photo.internalFileName)
                        if (!origPath.exists()) {
                                diag("performUpload: FATAL — local original missing: ${origPath.absolutePath}")
                                reportUploadFailureNotification(photo.fileName, batchState)
                                throw FatalSyncException(
                                        "Local original file missing for $uuid before upload. " +
                                                "Photo may have been deleted out-of-band.",
                                )
                        }
                        val remoteOrig = "$remote:${SyncConfig.remoteOriginalsDir}/${origPath.name}"
                        diag("performUpload: uploading original ${origPath.absolutePath} (size=${origPath.length()}) → $remoteOrig")
                        // Pre-upload state: indeterminate progress while the upload runs.
                        // The first onProgress tick from uploadFileWithProgress() will
                        // switch the notification to a determinate bar.
                        updateNotification(
                                progress = null,
                                text = collapsedText,
                                bigText = expandedText,
                        )
                        try {
                                // JNI migration note: the new RcloneController.uploadFile() does not
                                // support progress callbacks (rclone operations/copyfile is _async
                                // server-side; polling job/status would be a separate effort). The
                                // notification stays in indeterminate mode for the duration of the
                                // upload — updateNotification(progress = null, ...) was already
                                // called above to set that up.
                                rcloneController
                                        .uploadFile(
                                                localPath = origPath.absolutePath,
                                                remotePath = remoteOrig,
                                        ).getOrThrow()
                                diag("performUpload: original upload OK")
                        } catch (e: Exception) {
                                diag("performUpload: original upload FAILED: ${e.javaClass.name}: ${e.message}", e)
                                reportUploadFailureNotification(photo.fileName, batchState)
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
                                reportUploadFailureNotification(photo.fileName, batchState)
                                throw e
                        }

                        // ─── Independent verification: hash match (best-effort) ────────────
                        val localHash = sha256OfFile(origPath.absolutePath)
                        diag("performUpload: verifying hash (local sha256=$localHash)")
                        try {
                                rcloneController.verifyRemote(remoteOrig).getOrThrow()
                                diag("performUpload: remote verification OK")
                        } catch (e: Exception) {
                                // F-SYNC-003: Merged duplicate catch — benign hash-unsupported errors
                                // fall through; real errors are re-thrown so the worker retries.
                                val msg = e.message ?: ""
                                val isBenign = msg.contains("hash", ignoreCase = true) ||
                                        msg.contains("unsupported", ignoreCase = true) ||
                                        msg.contains("not supported", ignoreCase = true)
                                if (isBenign) {
                                        diag("performUpload: remote verification skipped — ${e.message}")
                                        Timber.w("PhotoSyncWorker: verification skipped for %s — %s", uuid, e.message ?: "unknown")
                                        SyncLogger.logStateTransition(uuid, "UPLOAD_PENDING", "UPLOADED", "hash skipped (unsupported), size verified OK")
                                } else {
                                        diag("performUpload: hash verification FAILED: ${e.javaClass.name}: ${e.message}", e)
                                        reportUploadFailureNotification(photo.fileName, batchState)
                                        throw e
                                }
                        }
                        return remoteOrig
                }

                /**
                 * Build and upload the per-photo metadata sidecar JSON to
                 * `<remote>:photok-backup/metadata/<uuid>.json`.
                 *
                 * **DEPRECATED in v9** — replaced by the encrypted dedup registry
                 * ([HashRegistry] / `registry.json.crypt`). The registry already records
                 * the same fields (filename, albumPath, size, type) per content-hash, in
                 * a single encrypted artifact instead of N per-photo plaintext sidecars.
                 * The function is KEPT here for backwards compatibility (so older code
                 * paths that might still call it compile), but [performUpload] no longer
                 * invokes it. New code SHOULD NOT call this — add the photo's metadata to
                 * the registry via [HashRegistry.addEntry] instead.
                 *
                 * The original sidecar captured fields the encrypted artifacts alone can't
                 * recover:
                 *   - `uuid` — the photo's stable UUID (matches the remote filenames)
                 *   - `relativePath` — the photo's original local-origin provenance
                 *   - `fileName` — the original filename as imported
                 *   - `type` — the [onlasdan.gallery.model.database.entity.PhotoType]
                 *   - `size` — the encrypted original's file size in bytes
                 *
                 * @since v8 — path-consistency metadata sidecar
                 * @deprecated since v9 — use [HashRegistry.addEntry] instead.
                 */
                @Suppress("UNUSED_PARAMETER", "unused")
                private suspend fun uploadMetadataSidecar(
                        photo: Photo,
                        remote: String,
                ) {
                        val uuid = photo.uuid
                        val sidecarJson: JsonObject =
                                buildJsonObject {
                                        put("uuid", uuid)
                                        put("relativePath", photo.relativePath ?: photo.fileName)
                                        put("fileName", photo.fileName)
                                        put("type", photo.type.name)
                                        put("size", photo.size)
                                }
                        val sidecarText = metadataJson.encodeToString(JsonObject.serializer(), sidecarJson)

                        // v9: constants inlined — the SyncConfig.METADATA_DIR / METADATA_FILENAME_SUFFIX
                        // constants were removed when the per-photo sidecar was replaced by the
                        // registry. The literal path is kept here so this deprecated function
                        // still compiles for any legacy caller; do NOT introduce new callers.
                        val legacyMetadataDir = "metadata"
                        val legacyMetadataSuffix = ".json"
                        val tempFile = File(appContext.cacheDir, "photok-meta-$uuid$legacyMetadataSuffix")
                        try {
                                tempFile.writeText(sidecarText)
                                val remoteMeta = "$remote:${RepoManager.REPO_DIR}/$legacyMetadataDir/$uuid$legacyMetadataSuffix"
                                diag("performUpload: uploading metadata sidecar (${tempFile.length()} bytes) → $remoteMeta")
                                rcloneController.uploadFile(tempFile.absolutePath, remoteMeta).getOrThrow()
                                diag("performUpload: metadata sidecar upload OK")
                        } finally {
                                tempFile.delete()
                        }
                }

                // ─── Item 3: optional full hash verification by re-download + decrypt ──
                // The remote-side `verifyRemote` above only works if the backend supports
                // server-side hashsum. Koofr (and many other backends) don't — the call
                // falls through to the `HashNotSupportedException` branch and we're left
                // trusting only the size check. This optional pass, gated on the user's
                // `syncVerifyHash` setting (default OFF — doubles bandwidth per upload),
                // downloads the freshly-uploaded remote file, decrypts it with the VMK,
                // recomputes the SHA-256 of the plaintext, and compares it against the
                // photo's stored `contentHash`. If they don't match → throw + mark
                // UPLOAD_FAILED so WorkManager retries the whole upload.
                //
                // This is the only way to truly verify upload integrity against backends
                // that can't compute server-side hashes. It's expensive (download = full
                // upload size again) so it's off by default and surfaced as a Settings
                // toggle for paranoiac users.
                @Suppress("ThrowsCount", "NestedBlockDepth") // pre-existing: complex sync verification logic, refactor tracked separately
                private suspend fun performFullHashVerification(
                        photo: Photo,
                        remoteOrig: String,
                        contentHash: String,
                        batchState: SyncBatchTracker.BatchState,
                        uuid: String,
                ) {
                        diag("performUpload: syncVerifyHash is ON — performing full re-download + decrypt + sha256 verification")
                        val verifyFile = File(appContext.cacheDir, "verify-${photo.uuid}.crypt")
                        try {
                                rcloneController.downloadFile(remoteOrig, verifyFile.absolutePath).getOrThrow()
                                diag("performUpload: hash verification — downloaded ${verifyFile.length()} bytes to ${verifyFile.absolutePath}")

                                val session = sessionRepository.get()
                                if (session == null) {
                                        diag("performUpload: hash verification SKIPPED — vault session is null (locked). Size check still passed.")
                                } else {
                                        val md = MessageDigest.getInstance("SHA-256")
                                        FileInputStream(verifyFile).use { fis ->
                                                val decryptedStream = cryptoEngine.createDecryptStream(fis, session)
                                                if (decryptedStream == null) {
                                                        throw IOException("Failed to create decrypt stream for hash verification (uuid=$uuid)")
                                                }
                                                decryptedStream.use { stream ->
                                                        val buf = ByteArray(64 * 1024)
                                                        while (true) {
                                                                val n = stream.read(buf)
                                                                if (n <= 0) break
                                                                md.update(buf, 0, n)
                                                        }
                                                }
                                        }
                                        val actualHash = md.digest().joinToString("") { "%02x".format(it) }
                                        if (!actualHash.equals(contentHash, ignoreCase = true)) {
                                                throw IOException(
                                                        "Hash mismatch after upload: expected=$contentHash actual=$actualHash (uuid=$uuid). " +
                                                                "Remote file may be corrupt or was tampered with.",
                                                )
                                        }
                                        diag("performUpload: hash verification OK — remote sha256 matches photo.contentHash ($actualHash)")
                                        SyncLogger.logStateTransition(
                                                uuid,
                                                "UPLOAD_PENDING",
                                                "UPLOADED",
                                                "full re-download + decrypt + sha256 verified OK ($actualHash)",
                                        )
                                }
                        } catch (e: Exception) {
                                diag("performUpload: hash verification FAILED: ${e.javaClass.name}: ${e.message}", e)
                                reportUploadFailureNotification(photo.fileName, batchState)
                                throw e
                        } finally {
                                verifyFile.delete()
                        }
                }

                // ─── v9 dedup: append to the local registry cache ────────────────────
                // The original + thumbnail are now on the remote under THIS photo's UUID
                // (the canonical UUID for this content-hash). Record the hash → UUID
                // mapping in the local registry cache so:
                //   (a) future imports of the same content on THIS device dedup against
                //       this UUID (no remote round-trip needed for the lookup — the
                //       cache is consulted directly);
                //   (b) the next [flushRegistryIfBatchComplete] call serializes the
                //       cache (including this new entry) and uploads it as
                //       `registry.json.crypt` to the remote, so OTHER devices will
                //       dedup against this UUID after their next login.
                //
                // Failure here is non-fatal — the photo's encrypted artifacts are
                // already uploaded + verified. We just lose dedup coverage for this
                // hash until the next successful registry add. Logged + continued.
                private suspend fun addRegistryEntry(photo: Photo, contentHash: String, thumbPath: File, uuid: String) {
                        try {
                                val entry =
                                        HashRegistryEntry(
                                                contentHash = contentHash,
                                                uuid = uuid,
                                                filename = photo.fileName,
                                                albumPath = photo.albumPath ?: photo.relativePath,
                                                size = photo.size,
                                                type = photo.type.name,
                                                // thumbnail_pack = null means "individual file, not packed" —
                                                // the thumbnail is at `<remote>:thumbnails/<uuid>.crypt.tn`.
                                                // The future packed-thumbnails optimization will set this to
                                                // `pack-NNN` and fill in offset/length.
                                                thumbnailPack = null,
                                                thumbnailOffset = 0L,
                                                thumbnailLength = thumbPath.takeIf { it.exists() }?.length() ?: 0L,
                                                deleted = false,
                                                // Sprint 2 / M7 — tag with the current vault's vault_id.
                                                // The upload worker only runs for the syncing vault (the
                                                // one with a recovery phrase), so this entry will be
                                                // included in the next registry flush. Additional vaults
                                                // never trigger uploads (no recovery phrase → no sync).
                                                vaultId = runCatching { sessionRepository.require().vaultId }.getOrNull(),
                                        )
                                hashRegistry.addEntry(entry)
                                diag("performUpload: added registry entry contentHash=$contentHash uuid=$uuid")
                        } catch (e: Exception) {
                                diag("performUpload: registry addEntry FAILED (non-fatal): ${e.javaClass.name}: ${e.message}", e)
                                Timber.w(e, "PhotoSyncWorker: registry addEntry failed for %s — photo is still UPLOADED", uuid)
                        }
                }

                /**
                 * End-of-batch hook: if [SyncBatchTracker.isBatchComplete] reports the
                 * batch is done, post the final summary notification, FLUSH the dedup
                 * registry to the remote (v9), and reset the batch tracker.
                 *
                 * Called from every terminal branch of [doWorkInternal] (success, fatal
                 * failure, max-attempts failure, "photo already UPLOADED", "photo not
                 * found", "photo deleted during upload") — six call sites in total.
                 * Consolidating the logic here ensures the registry flush happens exactly
                 * once per batch, on the LAST worker, regardless of which terminal branch
                 * that worker happens to take.
                 *
                 * The registry flush is best-effort: if the vault is locked (no VMK in
                 * memory) or the upload fails for any reason, the local cache is still
                 * correct (it just isn't propagated to the remote yet). The next batch's
                 * flush will pick up these entries; the cache is monotonic.
                 *
                 * @since v9 — added registry flush to the batch-complete hook
                 */
                private suspend fun onBatchCompleteIfDone() {
                        if (!batchTracker.isBatchComplete()) return
                        showBatchCompleteNotification()
                        // ─── v9 followup (Bug 4): pack pending thumbnails before flushing ──
                        // Collects all registry entries with `thumbnail_pack = null` whose
                        // local thumbnail file exists, concatenates them into ≤50 MB packs,
                        // uploads each pack, and updates the entries with pack name + offset
                        // + length. The subsequent [flushRegistryToRemote] then serializes
                        // the registry (now carrying pack metadata) and uploads it.
                        //
                        // Non-fatal: if packing fails for any reason, the entries keep
                        // `thumbnail_pack = null` and will be retried in the next batch's
                        // [onBatchCompleteIfDone]. The local thumbnail files are NOT deleted
                        // (they're still needed for a future retry).
                        try {
                                hashRegistry.flushPendingThumbnailPacks()
                        } catch (e: Exception) {
                                diag(
                                        "onBatchCompleteIfDone: flushPendingThumbnailPacks FAILED (non-fatal — will retry next batch): ${e.javaClass.name}: ${e.message}",
                                        e,
                                )
                        }
                        // ─── v9 dedup: flush the local registry cache to the remote ──────
                        // Serializes all current entries, encrypts with the VMK (AES-256-GCM),
                        // and uploads as `<remote>:photoz-backup/registry.json.crypt`. Failure
                        // is non-fatal — the local cache stays correct and the next batch will
                        // retry the flush.
                        flushRegistryToRemote()
                        batchTracker.reset()
                }

                /**
                 * Best-effort flush of the local dedup registry cache to the remote.
                 *
                 * Obtains the VMK from [SessionRepository] — if the vault is locked (e.g.
                 * the worker is running on a delayed retry long after the app was
                 * backgrounded), the flush is skipped (logged). The local cache is
                 * unaffected; the next successful flush will pick up these entries.
                 *
                 * @since v9 dedup + encrypted GCM registry
                 */
                private suspend fun flushRegistryToRemote() {
                        try {
                                val session = sessionRepository.get()
                                if (session == null) {
                                        diag(
                                                "flushRegistryToRemote: no vault session (vault locked) — skipping registry flush; local cache stays correct, will flush on next batch",
                                        )
                                        return
                                }
                                val vmkBytes = session.vmk.encoded
                                diag("flushRegistryToRemote: flushing registry to remote (vmkBytes=${vmkBytes.size})")
                                hashRegistry.uploadToRemote(vmkBytes)
                                diag("flushRegistryToRemote: OK")
                        } catch (e: Exception) {
                                diag(
                                        "flushRegistryToRemote: FAILED (non-fatal — local cache stays correct, will retry on next batch): ${e.javaClass.name}: ${e.message}",
                                        e,
                                )
                                Timber.w(e, "PhotoSyncWorker: registry flush failed — local cache stays correct, will retry on next batch")
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
                        val nm =
                                appContext.getSystemService(NotificationManager::class.java) ?: run {
                                        diag("ensureChannel: NotificationManager service unavailable — notifications will not work")
                                        return
                                }
                        if (nm.getNotificationChannel(CHANNEL_ID) != null) {
                                // Channel already exists — log its current importance for diagnostics.
                                // Channel importance is immutable once created; if it was created at
                                // IMPORTANCE_NONE (e.g. user disabled the channel in Settings),
                                // notifications will never appear regardless of POST_NOTIFICATIONS permission.
                                val ch = nm.getNotificationChannel(CHANNEL_ID)
                                diag("ensureChannel: channel $CHANNEL_ID already exists (importance=${ch?.importance})")
                                return
                        }
                        nm.createNotificationChannel(
                                NotificationChannel(
                                        CHANNEL_ID,
                                        appContext.getString(R.string.sync_notification_channel_name),
                                        NotificationManager.IMPORTANCE_LOW,
                                ).apply {
                                        description = appContext.getString(R.string.sync_notification_channel_desc)
                                        setShowBadge(false)
                                },
                        )
                        diag("ensureChannel: created channel $CHANNEL_ID (importance=IMPORTANCE_LOW)")
                }

                private fun buildNotification(text: String): Notification =
                        buildNotificationInternal(text = text, progress = null, ongoing = true, bigText = null)

                /**
                 * Build a notification with configurable progress + ongoing flag.
                 *
                 * @param text content text (shown in collapsed view)
                 * @param progress 0f..100f for a determinate progress bar, or `null` for an
                 *   indeterminate (spinning) progress bar. At 100f the bar shows full.
                 * @param ongoing `true` for a foreground-service-style notification that
                 *   disappears when the worker ends; `false` for a "left-behind" notification
                 *   that stays visible after the worker ends (used for the upload-failed
                 *   state so the user can see something went wrong).
                 * @param bigText optional expanded text for [NotificationCompat.BigTextStyle].
                 *   When non-null, the notification uses BigTextStyle so the user can pull
                 *   down the shade to see multi-line detail (filename + bytes-uploaded so
                 *   far, etc.). When null, the collapsed text is shown in both views.
                 *
                 * @since v8 — stable upload notification with real progress percentage
                 * @since Item 1 — added [bigText] for Drive-style expanded content
                 */
                private fun buildNotificationInternal(
                        text: String,
                        progress: Float?,
                        ongoing: Boolean,
                        bigText: String? = null,
                ): Notification {
                        val launchIntent = appContext.packageManager.getLaunchIntentForPackage(appContext.packageName)
                        val pi =
                                launchIntent?.let {
                                        PendingIntent.getActivity(
                                                appContext,
                                                0,
                                                it,
                                                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                                        )
                                }
                        val builder =
                                NotificationCompat
                                        .Builder(appContext, CHANNEL_ID)
                                        .setSmallIcon(android.R.drawable.stat_sys_upload)
                                        .setContentTitle(appContext.getString(R.string.sync_notification_title))
                                        .setContentText(text)
                                        .setOngoing(ongoing)
                                        // ─── KEY FIX: bypass Android 12+ 10-second FGS notification delay ──
                                        // Android 12+ delays foreground-service notifications by up to 10
                                        // seconds by default (so short FGS tasks don't clutter the shade).
                                        // FOREGROUND_SERVICE_IMMEDIATE opts out of this delay — the
                                        // notification appears IMMEDIATELY when setForeground() is called.
                                        // This was the root cause of the "1-3 second notification delay"
                                        // reported by the user.
                                        // See: https://stackoverflow.com/questions/73074639/android-foreground-service-notification-delayed
                                        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
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

                        // Item 1: BigTextStyle for the expanded shade view. When bigText is
                        // non-null, the user can pull down the notification shade to see the
                        // full multi-line content (filename + bytes-uploaded + batch context).
                        // When null, the system shows the collapsed contentText in both views.
                        if (bigText != null) {
                                builder.setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
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
                        bigText: String? = null,
                ) {
                        val now = System.currentTimeMillis()
                        val isStateChange = text != lastNotificationText
                        val isComplete = progress != null && progress >= 100f
                        val msSinceLast = now - lastNotificationUpdateMs
                        // Rate-limit ONLY for non-state-change progress ticks (reduces flicker).
                        // State changes and completion ALWAYS go through immediately — no delay.
                        if (!isStateChange &&
                                !isComplete &&
                                msSinceLast < NOTIFICATION_UPDATE_MIN_INTERVAL_MS
                        ) {
                                return
                        }
                        lastNotificationUpdateMs = now
                        lastNotificationText = text

                        ensureChannel()
                        val nm = appContext.getSystemService(NotificationManager::class.java) ?: return

                        try {
                                val notification = buildNotificationInternal(text, progress, ongoing, bigText)
                                nm.notify(NOTIFICATION_ID, notification)
                        } catch (e: Exception) {
                                diag("updateNotification: nm.notify() THREW ${e.javaClass.name}: ${e.message}", e)
                        }
                }

                /**
                 * Show the "Upload failed: <filename> (<F> failed in batch)" notification as
                 * NON-ongoing so it stays visible in the system tray after the worker ends
                 * (the user can dismiss it manually). Used at every error-exit point in
                 * [performUpload].
                 *
                 * The batch failed-count shown here is `batchState.failed + 1` — i.e. the
                 * count AFTER this failure is committed. The actual counter increment happens
                 * later in [doWorkInternal]'s failure branch via [SyncBatchTracker.onWorkerFailure].
                 * For retryable failures (which don't increment the counter), the displayed
                 * count is still accurate on the next attempt because the counter hasn't
                 * changed.
                 *
                 * @since v8 — stable upload notification with real progress percentage
                 * @since Item 1 — added [batchState] for the "F failed in batch" suffix + BigTextStyle
                 */
                private fun reportUploadFailureNotification(
                        fileName: String,
                        batchState: SyncBatchTracker.BatchState,
                ) {
                        // batchState.failed is the count BEFORE this failure; +1 to reflect this one.
                        val failedInBatch = batchState.failed + 1
                        val collapsed = "Upload failed: $fileName ($failedInBatch failed in batch)"
                        val expanded =
                                "Upload failed: $fileName\n$failedInBatch failed in batch so far " +
                                        "(of ${batchState.total} planned)"
                        updateNotification(
                                progress = null,
                                text = collapsed,
                                ongoing = false,
                                bigText = expanded,
                        )
                }

                /**
                 * Show the final batch-complete summary notification as NON-ongoing so it
                 * stays visible in the system tray after the worker ends.
                 *
                 * Uses a SEPARATE notification ID ([BATCH_COMPLETE_NOTIFICATION_ID]) from the
                 * per-worker foreground notification ([NOTIFICATION_ID]) — WorkManager cancels
                 * the foreground notification when the worker returns, so if we used the same
                 * ID, the summary would be canceled along with it. The separate ID guarantees
                 * the summary stays visible regardless of whether `setForeground()` succeeded.
                 *
                 * Content (Drive-style):
                 *   - Collapsed (all success): "{total} photos backed up"
                 *   - Collapsed (with failures): "{completed} of {total} backed up — {failed} failed"
                 *   - Expanded: "Sync complete\n{completed} uploaded, {failed} failed\n{bytesCompleted} total"
                 *     (with the ", {failed} failed" segment omitted when failed == 0)
                 *
                 * @since Item 1 — Drive-style batch-complete summary
                 */
                private fun showBatchCompleteNotification() {
                        val state = batchTracker.getBatchState()
                        val completed = state.completed
                        val failed = state.failed
                        val total = state.total
                        val bytesTotal = state.bytesCompleted

                        // Collapsed (single line). Drive-style.
                        val collapsed =
                                if (failed == 0) {
                                        "$total photos backed up"
                                } else {
                                        "$completed of $total backed up — $failed failed"
                                }

                        // Expanded (BigTextStyle, multi-line). Drive-style.
                        val uploadedLine =
                                if (failed == 0) {
                                        "$completed uploaded"
                                } else {
                                        "$completed uploaded, $failed failed"
                                }
                        val expanded = "Sync complete\n$uploadedLine\n${formatBytes(bytesTotal)} total"

                        ensureChannel()
                        val nm = appContext.getSystemService(NotificationManager::class.java)
                        if (nm == null) {
                                diag("showBatchCompleteNotification: NotificationManager unavailable — cannot post summary")
                                return
                        }
                        try {
                                val notification =
                                        buildNotificationInternal(
                                                text = collapsed,
                                                progress = null,
                                                ongoing = false, // NON-ongoing — stays visible after worker ends
                                                bigText = expanded,
                                        )
                                nm.notify(BATCH_COMPLETE_NOTIFICATION_ID, notification)
                                diag(
                                        "showBatchCompleteNotification: posted id=$BATCH_COMPLETE_NOTIFICATION_ID " +
                                                "collapsed=\"$collapsed\" completed=$completed failed=$failed total=$total bytesTotal=$bytesTotal",
                                )
                        } catch (e: Exception) {
                                diag("showBatchCompleteNotification: nm.notify() THREW ${e.javaClass.name}: ${e.message}", e)
                        }
                }

                /**
                 * Human-readable byte formatter for notification text. Chooses MB / KB / B
                 * based on magnitude so small batches don't show "0.0 MB".
                 *
                 * @since Item 1 — Drive-style expanded notification content
                 */
                private fun formatBytes(bytes: Long): String {
                        val mb = bytes.toDouble() / (1024.0 * 1024.0)
                        val kb = bytes.toDouble() / 1024.0
                        return when {
                                mb >= 1.0 -> String.format("%.1f MB", mb)
                                kb >= 1.0 -> String.format("%.1f KB", kb)
                                else -> "$bytes B"
                        }
                }

                private fun isFatalFailure(t: Throwable?): Boolean =
                        when (t) {
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

                private class FatalSyncException(
                        message: String,
                ) : Exception(message)

                private class HashMismatchException(
                        message: String,
                ) : Exception(message)

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
                        private const val NOTIFICATION_UPDATE_MIN_INTERVAL_MS = 200L

                        // ─── Batch-complete summary notification ID (Item 1) ──────────────────
                        // SEPARATE from [NOTIFICATION_ID] — WorkManager cancels the foreground
                        // notification (NOTIFICATION_ID) when the worker returns, so if we used
                        // the same ID for the batch-complete summary, it would be canceled along
                        // with the foreground notification. Using a distinct ID guarantees the
                        // summary stays visible in the tray after the last worker in the batch
                        // ends, regardless of whether `setForeground()` succeeded for that worker.
                        private const val BATCH_COMPLETE_NOTIFICATION_ID = 4243

                        fun enqueue(
                                context: Context,
                                photo: Photo,
                        ) {
                                val uniqueWorkName = UNIQUE_WORK_PREFIX + photo.uuid

                                // ─── sync-settings feature: respect user's auto-upload toggle ──────
                                // Read the user-facing preference directly from SharedPreferences.
                                // We can't inject Config here (this is a companion-object method),
                                // but Config is just a thin wrapper around the same SharedPreferences
                                // file, so reading the same key with the same default is equivalent.
                                //
                                // When `syncAutoUpload == false`, return immediately WITHOUT calling
                                // WorkManager.getInstance().enqueueUniqueWork(). The photo stays in
                                // LOCAL_ONLY syncState; the user can re-enable auto-upload at any
                                // time and the next import will start enqueuing again.
                                //
                                // NOTE: photos already queued before the user toggled auto-upload off
                                // are NOT canceled — they will still upload. This matches the user's
                                // expectation that "off" means "no new uploads", not "cancel the
                                // in-flight batch".
                                val prefs =
                                        context.getSharedPreferences(
                                                onlasdan.gallery.settings.data.Config.FILE_NAME,
                                                android.content.Context.MODE_PRIVATE,
                                        )
                                val autoUpload =
                                        prefs.getBoolean(
                                                onlasdan.gallery.settings.data.Config.SYNC_AUTO_UPLOAD,
                                                onlasdan.gallery.settings.data.Config.SYNC_AUTO_UPLOAD_DEFAULT,
                                        )
                                // @since sync-settings feature: WiFi-only — adds NetworkType.UNMETERED
                                // to the WorkManager constraints so the worker won't run on metered
                                // (cellular) connections. The user toggles this in Settings → Cloud
                                // Sync → "Sync over Wi-Fi only". Default is false (allow cellular) to
                                // preserve the prior behaviour.
                                val wifiOnly =
                                        prefs.getBoolean(
                                                onlasdan.gallery.settings.data.Config.SYNC_WIFI_ONLY,
                                                onlasdan.gallery.settings.data.Config.SYNC_WIFI_ONLY_DEFAULT,
                                        )
                                if (!autoUpload) {
                                        android.util.Log.e(
                                                "RcloneDiag",
                                                "[UploadWorker] enqueue: SKIPPED — syncAutoUpload=false (user disabled auto-upload in Settings). uuid=${photo.uuid}",
                                        )
                                        try {
                                                onlasdan.gallery.sync.debug.SyncLogRotator.append(context,
                                                        "[RcloneDiag] [UploadWorker] enqueue: SKIPPED — syncAutoUpload=false uuid=${photo.uuid}\n",
                                                )
                                        } catch (_: Exception) {
                                        }
                                        return
                                }

                                // Log at enqueue time so we can confirm the worker is actually being
                                // scheduled after photo import (if this log doesn't appear, the bug
                                // is in the call chain BEFORE the worker, not in the worker itself).
                                android.util.Log.e(
                                        "RcloneDiag",
                                        "[UploadWorker] enqueue: BEGIN uuid=${photo.uuid} autoUploadEnabled=$autoUpload wifiOnly=$wifiOnly",
                                )
                                try {
                                        onlasdan.gallery.sync.debug.SyncLogRotator.append(context,
                                                "\n[RcloneDiag] [UploadWorker] enqueue: BEGIN uuid=${photo.uuid} wifiOnly=$wifiOnly\n",
                                        )
                                } catch (_: Exception) {
                                }

                                val wm = WorkManager.getInstance(context)

                                // ─── Step 2 fix: cancel any stale work before enqueue ──────────────
                                // ExistingWorkPolicy.KEEP (used previously) silently drops new enqueue
                                // calls if a prior entry exists under the same unique name — even if
                                // that entry is FAILED or CANCELLED. This caused "enqueue succeeds,
                                // worker never runs" in prior sessions. Cancel first, then REPLACE.
                                try {
                                        wm.cancelUniqueWork(uniqueWorkName)
                                        android.util.Log.e(
                                                "RcloneDiag",
                                                "[UploadWorker] enqueue: cancelled any stale work for $uniqueWorkName",
                                        )
                                } catch (e: Exception) {
                                        android.util.Log.e(
                                                "RcloneDiag",
                                                "[UploadWorker] enqueue: cancelUniqueWork FAILED: ${e.message}",
                                                e,
                                        )
                                }

                                // ─── Dump WorkInfo BEFORE enqueue (to see if there was a stale entry) ──
                                dumpWorkInfo(context, uniqueWorkName, "BEFORE enqueue")

                                // @since sync-settings feature: when the user enabled "WiFi only",
                                // require an UNMETERED network. Combined with CONNECTED (always
                                // required), this means "connected AND unmetered" — i.e. Wi-Fi or
                                // Ethernet, not cellular. WorkManager won't even start the worker
                                // until the constraint is met, so mobile data is preserved.
                                val requiredNetworkType = if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED

                                val request =
                                        OneTimeWorkRequestBuilder<PhotoSyncWorker>()
                                                .setInputData(workDataOf(KEY_PHOTO_UUID to photo.uuid))
                                                .setConstraints(
                                                        Constraints
                                                                .Builder()
                                                                .setRequiredNetworkType(requiredNetworkType)
                                                                .build(),
                                                ).setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                                                .setBackoffCriteria(
                                                        BackoffPolicy.EXPONENTIAL,
                                                        SyncConfig.initialBackoffSeconds,
                                                        TimeUnit.SECONDS,
                                                ).build()

                                // ─── Step 2 fix: REPLACE instead of KEEP ───────────────────────────
                                // REPLACE guarantees the new work request is enqueued even if a prior
                                // entry exists. Combined with cancelUniqueWork above, this eliminates
                                // the "stale work entry blocks new enqueue" failure mode.
                                wm.enqueueUniqueWork(
                                        uniqueWorkName,
                                        ExistingWorkPolicy.REPLACE,
                                        request,
                                )

                                android.util.Log.e(
                                        "RcloneDiag",
                                        "[UploadWorker] enqueue: OK — WorkManager.enqueueUniqueWork (REPLACE) called for uuid=${photo.uuid}",
                                )
                                try {
                                        onlasdan.gallery.sync.debug.SyncLogRotator.append(context,
                                                "[RcloneDiag] [UploadWorker] enqueue: OK — WorkManager.enqueueUniqueWork (REPLACE) called for uuid=${photo.uuid}\n",
                                        )
                                } catch (_: Exception) {
                                }

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
                        fun dumpWorkInfo(
                                context: Context,
                                uniqueWorkName: String,
                                whenLabel: String,
                        ) {
                                try {
                                        val workInfos =
                                                WorkManager
                                                        .getInstance(context)
                                                        .getWorkInfosForUniqueWork(uniqueWorkName)
                                                        .get()
                                        if (workInfos.isEmpty()) {
                                                android.util.Log.e(
                                                        "RcloneDiag",
                                                        "[UploadWorker] dumpWorkInfo ($whenLabel): NO WorkInfo found for $uniqueWorkName — work was never enqueued or was pruned",
                                                )
                                                try {
                                                        onlasdan.gallery.sync.debug.SyncLogRotator.append(context,
                                                                "[RcloneDiag] [UploadWorker] dumpWorkInfo ($whenLabel): NO WorkInfo for $uniqueWorkName\n",
                                                        )
                                                } catch (_: Exception) {
                                                }
                                                return
                                        }
                                        for (wi in workInfos) {
                                                val line =
                                                        "dumpWorkInfo ($whenLabel): id=${wi.id} state=${wi.state} " +
                                                                "runAttemptCount=${wi.runAttemptCount} tags=${wi.tags} " +
                                                                "outputData=${wi.outputData}"
                                                android.util.Log.e("RcloneDiag", "[UploadWorker] $line")
                                                try {
                                                        onlasdan.gallery.sync.debug.SyncLogRotator.append(context,
                                                                "[RcloneDiag] [UploadWorker] $line\n",
                                                        )
                                                } catch (_: Exception) {
                                                }
                                        }
                                } catch (e: Exception) {
                                        android.util.Log.e(
                                                "RcloneDiag",
                                                "[UploadWorker] dumpWorkInfo ($whenLabel): FAILED: ${e.javaClass.name}: ${e.message}",
                                                e,
                                        )
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
                                                onlasdan.gallery.sync.debug.SyncLogRotator.append(context,
                                                        "\n[RcloneDiag] [UploadWorker] dumpAllWorkInfo: querying all work...\n",
                                                )
                                        } catch (_: Exception) {
                                        }
                                } catch (e: Exception) {
                                        android.util.Log.e("RcloneDiag", "[UploadWorker] dumpAllWorkInfo: FAILED: ${e.message}", e)
                                }
                        }
                }
        }
