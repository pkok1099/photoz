# Task: notification-permission-fix-and-batch-tracking

Agent: code-editing subagent
Scope: Item 1 (Drive-style notification content + batch tracking) + Item 2 (fix permission request not firing on both register & login branches)
Repo: /home/z/my-project/repo/photok-sync-fork
Status: NOT pushed / NOT committed (per instructions)

## Files changed

### NEW: `app/src/main/java/dev/leonlatsch/photok/sync/work/SyncBatchTracker.kt`
- `@Singleton class SyncBatchTracker @Inject constructor(@ApplicationContext context: Context)`
- SharedPreferences-backed (`sync_batch_tracker` prefs file) so batch state survives worker-instance recreation AND process death.
- Public API: `onPhotoEnqueued`, `onWorkerStart(uuid)`, `onWorkerSuccess(size)`, `onWorkerFailure`, `isBatchComplete`, `getBatchState`, `reset`.
- `BatchState` data class: `current`, `total`, `completed`, `failed`, `bytesCompleted`.
- No DI module changes needed — `@Singleton @Inject constructor` is auto-bound by Hilt. `SyncModule.kt` was NOT modified.

### MODIFIED: `app/src/main/java/dev/leonlatsch/photok/sync/work/PhotoSyncWorker.kt`
- Added `private val batchTracker: SyncBatchTracker` to `@AssistedInject` constructor.
- `getForegroundInfo()` now uses `batchTracker.getBatchState()` for the foreground notification text ("Uploading N of M"), falling back to the generic string resource when batch state is empty (total=0).
- `doWorkInternal()` now:
  - Calls `batchTracker.onPhotoEnqueued()` gated by `runAttemptCount == 0` (so retries don't double-count `total`).
  - Captures `batchState = batchTracker.onWorkerStart(uuid)` and passes it to `performUpload`.
  - All early-exit branches (photo not found, already UPLOADED, photo deleted during upload) call `batchTracker.onWorkerSuccess(0L)` + `isBatchComplete()` check + `showBatchCompleteNotification()` + `reset()` so the tracker doesn't get stuck.
  - Success branch: `batchTracker.onWorkerSuccess(photo.size)` + batch-complete check.
  - Fatal-failure branch: `batchTracker.onWorkerFailure()` + batch-complete check.
  - Max-attempts branch: `batchTracker.onWorkerFailure()` + batch-complete check.
  - Retryable branch: deliberately does NOT call `onWorkerFailure()` (counter updated when the retry eventually succeeds or fails permanently).
- `performUpload(photo, batchState)`:
  - New 2-arg signature.
  - Builds collapsed text `"Uploading N of M: filename"` and expanded text `"Uploading N of M photos\nfilename\nZ uploaded so far"` (Z = bytesCompleted).
  - All `updateNotification(...)` calls now pass `bigText = expandedText`.
  - All `reportUploadFailureNotification(...)` calls now pass `batchState`.
  - Final success notification: `"Uploaded N of M: filename"` collapsed + `"Uploaded N of M photos\nfilename\nZ uploaded so far"` expanded.
- `buildNotificationInternal(text, progress, ongoing, bigText = null)`:
  - Added `bigText: String? = null` parameter.
  - When non-null, sets `NotificationCompat.BigTextStyle().bigText(bigText)`.
- `buildNotification(text)` updated to pass `bigText = null` explicitly.
- `updateNotification(progress, text, ongoing = true, bigText = null)`:
  - Added `bigText` parameter, passed through to `buildNotificationInternal`.
  - Existing rate-limiting (`NOTIFICATION_UPDATE_MIN_INTERVAL_MS = 500L`) and `setOnlyAlertOnce(true)` preserved.
- `reportUploadFailureNotification(fileName, batchState)`:
  - New 2-arg signature.
  - Collapsed: `"Upload failed: <filename> (<F> failed in batch)"` (F = `batchState.failed + 1`).
  - Expanded: `"Upload failed: <filename>\n<F> failed in batch so far (of <total> planned)"`.
  - Still uses `NOTIFICATION_ID` + `ongoing=false` (existing behavior — may or may not stay visible depending on whether `setForeground()` succeeded).
- NEW `showBatchCompleteNotification()`:
  - Uses SEPARATE `BATCH_COMPLETE_NOTIFICATION_ID = 4243` (NOT `NOTIFICATION_ID = 4242`) so the summary stays visible after WorkManager cancels the foreground notification when the worker returns.
  - `ongoing=false` (NON-ongoing per spec — stays visible after worker ends).
  - Collapsed: `"<total> photos backed up"` (all success) OR `"<completed> of <total> backed up — <failed> failed"` (with failures).
  - Expanded: `"Sync complete\n<completed> uploaded[, <failed> failed]\n<bytesCompleted> total"`.
- NEW `formatBytes(bytes: Long)`: picks MB / KB / B based on magnitude (avoids "0.0 MB" for small batches).
- Added `private const val BATCH_COMPLETE_NOTIFICATION_ID = 4243` in companion.

### MODIFIED: `app/src/main/java/dev/leonlatsch/photok/setup/ui/RecoveryPhraseSetupFragment.kt`
- REMOVED the `LaunchedEffect(Unit) { ... }` block that auto-fired the permission request at fragment creation (Item 2 root cause for the register branch).
- REMOVED the now-unused `import androidx.compose.runtime.LaunchedEffect`.
- The `onContinue` callback (already present in the prior code) is now the SOLE entry point for the permission request — fires only when the user taps "Continue" after seeing their recovery phrase.
- Added `android.util.Log.e("RcloneDiag", ...)` logging:
  - In the launcher result callback (confirms callback fires + grants/denies).
  - BEFORE `notificationPermissionLauncher.launch(...)` (confirms we reached the launch point).
  - AFTER `launch()` returns (confirms launch didn't throw — the result callback fires async later).
  - In the "already granted / SDK < 33" else-branch (confirms we navigated without launching).
- Kept `Timber.i(...)` alongside the new `android.util.Log.e("RcloneDiag", ...)` for consistency with the rest of the codebase's RcloneDiag pattern (per the prompt's code-block example, `android.util.Log.e` is used, not `Timber.tag("RcloneDiag").e`).
- `navigateToGalleryOrPop()` unchanged.

### MODIFIED: `app/src/main/java/dev/leonlatsch/photok/reposetup/ui/RepoSetupFragment.kt`
- Added imports: `android.Manifest`, `android.content.pm.PackageManager`, `android.os.Build`, `androidx.core.content.ContextCompat`, `timber.log.Timber`.
- Registered `notificationPermissionLauncher` via `rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission())` INSIDE the `setContent { AppTheme { ... } }` block (must be called from a `@Composable` context).
- The launcher's result callback navigates to gallery (`navigateToGallery(findNavController())`) regardless of granted/denied — uploads work either way.
- `onUnlocked` lambda now (Item 2 fix for login branch):
  - Checks `POST_NOTIFICATIONS` permission (gated by `Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU`).
  - If permission needed: `notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)` — the result callback navigates to gallery.
  - If already granted (or SDK < 33): `navigateToGallery(findNavController())` directly.
- Added `android.util.Log.e("RcloneDiag", ...)` logging at the same three points as RecoveryPhraseSetupFragment (before launch, after launch returns, in result callback, in else-branch).
- Other `RepoSetupScreen` params (`onCompleted`, `onBack`) unchanged. The `LaunchedEffect(state)` inside `RepoSetupScreen` (used for `onCompleted`/`onUnlocked` dispatch) is unrelated to the removed `LaunchedEffect(Unit)` in RecoveryPhraseSetupFragment and was preserved.

## NOT changed
- `app/src/main/java/dev/leonlatsch/photok/sync/di/SyncModule.kt` — `SyncBatchTracker` has `@Singleton @Inject constructor`, so Hilt auto-binds it. No `@Provides` method needed.
- `AndroidManifest.xml` — foreground service type & POST_NOTIFICATIONS declaration already correct per the prompt.
- `RcloneController`, `RepoManager`, etc. — out of scope.

## Build verification
- Could not run a full Gradle build (no Android SDK installed at `/opt/android-sdk`, `~/Android`, etc.; `ANDROID_HOME` unset; offline Gradle can't resolve `org.jetbrains.kotlin:kotlin-gradle-plugin:2.2.21`).
- Verified changes by manual review:
  - All `reportUploadFailureNotification` call sites use the new 2-arg signature `(photo.fileName, batchState)`.
  - All `updateNotification` calls use named args (`progress`, `text`, `ongoing`, `bigText`).
  - `SyncBatchTracker` is in the same package as `PhotoSyncWorker` (`dev.leonlatsch.photok.sync.work`) — no import needed.
  - `NotificationCompat.BigTextStyle` accessible via the existing `androidx.core.app.NotificationCompat` import.
  - No remaining `LaunchedEffect` references in `RecoveryPhraseSetupFragment.kt` (only in comments).
  - `Timber` import added to `RepoSetupFragment.kt` and used in the result callback.
  - File ends correctly (class + companion object closed).

## Behavior summary (post-fix)

### Item 2 — Permission request flow
- **Register branch**: user goes through onboarding → sees recovery phrase → taps "Continue" → POST_NOTIFICATIONS dialog appears (if SDK ≥ 33 and not already granted) → after result, navigate to gallery. If already granted (or SDK < 33), navigate immediately on Continue tap. RcloneDiag log lines confirm each step.
- **Login branch**: user enters recovery phrase / password → unlock succeeds → `onUnlocked` fires → POST_NOTIFICATIONS dialog appears (if SDK ≥ 33 and not already granted) → after result, navigate to gallery. RcloneDiag log lines confirm each step.
- Both branches now have the permission request. Denial is non-blocking — gallery is reached either way.

### Item 1 — Drive-style notification content
- **During upload (collapsed)**: `"Uploading N of M: <filename>"` + progress bar (rate-limited to 2/sec, `setOnlyAlertOnce(true)`).
- **During upload (expanded)**: `"Uploading N of M photos\n<filename>\n<bytesCompleted> uploaded so far"`.
- **Per-photo failure (collapsed)**: `"Upload failed: <filename> (<F> failed in batch)"`.
- **Per-photo failure (expanded)**: `"Upload failed: <filename>\n<F> failed in batch so far (of <total> planned)"`.
- **Batch complete, all success (collapsed)**: `"<total> photos backed up"`.
- **Batch complete, with failures (collapsed)**: `"<completed> of <total> backed up — <failed> failed"`.
- **Batch complete (expanded)**: `"Sync complete\n<completed> uploaded[, <failed> failed]\n<bytesCompleted> total"`.
- Batch-complete notification uses `BATCH_COMPLETE_NOTIFICATION_ID = 4243` (separate from per-worker `NOTIFICATION_ID = 4242`) so it stays visible after WorkManager cancels the foreground notification.
- `SyncBatchTracker` is reset after the batch completes, so the next batch starts fresh.

## Known approximations / trade-offs (per prompt spec)
- `onPhotoEnqueued` is called from `doWorkInternal()` (gated by `runAttemptCount == 0`) instead of from the static `enqueue()` method — the prompt explicitly chose this trade-off because accessing a `@Singleton` from a companion-object method requires a Hilt EntryPoint. Consequence: `total` grows incrementally as workers start, so `current == total` in the common no-failures case (the "N of M" display is approximate). The prompt accepted this.
- Per-photo failure notification uses `NOTIFICATION_ID` (same as foreground notification) — if `setForeground()` succeeded for that worker, WorkManager will cancel it when the worker returns, so the per-photo failure notification may be short-lived. The batch-complete summary (separate ID) is the authoritative "what happened" notification that's guaranteed to stay visible.
