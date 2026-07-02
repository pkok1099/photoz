# Step 0 — Progress-notification diagnostic instrumentation

**Task ID:** step0-progress-notification-diag
**Agent:** main (Super Z)
**Branch:** main
**Status:** Diagnostic instrumentation applied + pushed. Awaiting on-device
log capture from user before writing the fix.

## Why this round exists

The user reports that the sync notification only shows information *after*
the upload completes (success count, size), not live during the upload. A
prior round (v8) claimed `RcloneController.uploadFileWithProgress()` polls
`core/stats` every 500ms during an in-flight `operations/copyfile` and
feeds real percentage into `updateNotification()`, rate-limited to
2 updates/sec.

That claim cannot be verified from the existing log output, because
`updateNotification()` was gated to only log when `isStateChange ||
isComplete`. In-place progress ticks (where `text` is unchanged and
`progress < 100f`) were silently going through `nm.notify()` — so the log
gave no evidence one way or the other. This is exactly the kind of
"earlier round's report didn't hold up under device testing" pattern the
task brief warned about.

## What was changed

### `app/src/main/java/dev/leonlatsch/photok/sync/rclone/RcloneController.kt`

Inside `uploadFileWithProgress()` (the function the v8 round claimed polls
`core/stats` during `operations/copyfile`):

- **ENTRY log** at the very top of the function body: logs `localPath`,
  `size`, `remotePath`, `pollIntervalMs`. Proves the polling code path is
  actually reached during real uploads (vs. just existing in source but
  the call site bypasses it).
- **Per-poll log** (inside the `while (!uploadDeferred.isCompleted)` loop):
  logs every `core/stats` poll unconditionally with:
  - poll count, ms since upload start
  - whether `invokeRc` returned null (rcd error / non-200)
  - compact stats-object summary: `topKeys`, `transferring.size`,
    `firstEntryKeys`, raw `bytes` and `totalBytes` field values
  - computed percent, or `null` + the implicit reason
  - whether `onProgress` was actually called (separate counter)
- **Polling-loop EXIT log**: total poll count, total `onProgress` call
  count, total duration.
- **Function EXIT OK log**: same totals again, post-`uploadDeferred.await()`.

### `app/src/main/java/dev/leonlatsch/photok/sync/work/PhotoSyncWorker.kt`

Inside `updateNotification()`:

- **SKIPPED log**: every rate-limited drop is now logged with
  `msSinceLast`, `threshold`, `isStateChange`, `isComplete`. Previously
  this branch was silent — impossible to tell from the log whether ticks
  were being received but rate-limited away, vs. never received at all.
- **PROCESSING log**: every non-rate-limited call is logged (previously
  only `isStateChange || isComplete` calls were logged — in-place progress
  ticks were silently going through `nm.notify()` with no log evidence).
- **POSTED log**: unconditional, includes `progress`, `text`,
  `appEnabled`, `channelImportance`. Previously gated on
  `isStateChange || isComplete`.

## What we expect to learn from the on-device log

After the user reproduces with a real multi-photo batch upload and
captures the log, we should be able to definitively answer:

1. **Is `uploadFileWithProgress: ENTRY` appearing in the log?**
   - YES → the progress code path IS being exercised during real uploads.
   - NO → wiring bug (the call site isn't actually invoking
     `uploadFileWithProgress`; e.g. some batching logic bypasses it, or
     `performUpload` short-circuits before reaching it).

2. **What does `transferring.size` show during the upload?**
   - `> 0` → `core/stats` IS tracking the in-flight `copyfile` transfer.
     Progress percentage should be non-null and changing.
   - `== 0` → `core/stats` is NOT tracking `operations/copyfile`. The
     `transferring[]` array is intended for rclone's async transfer
     pipeline (`sync/copy`, `operations/copy`); the synchronous
     `operations/copyfile` endpoint may not register there. This is the
     most likely root cause — `onProgress` would never be called because
     `computeUploadProgressPercent` returns null every tick.

3. **Are `bytes` / `totalBytes` present and non-zero in the first
   `transferring` entry?**
   - If `transferring.size > 0` but `bytes`/`totalBytes` are missing or
     zero, the divide-by-zero guard returns null and `onProgress` is
     skipped. Different fix path.

4. **When `onProgress` IS called, is the percent actually changing?**
   - YES → real progress. If the user still doesn't see live updates, the
     bottleneck is `updateNotification`'s rate-limiter or
     `NotificationManager.notify()`'s own coalescing — a timing tune.
   - NO (stuck at same value) → `core/stats` is returning stale stats;
     need a different progress source.

5. **Is `updateNotification: PROCESSING` being called during the upload?**
   - YES → the worker IS trying to update the notification mid-upload.
   - NO → the onProgress callback isn't firing (back to questions 2/3).

6. **Is `updateNotification: SKIPPED (rate-limited)` appearing?**
   - YES, frequently → the 500ms rate-limiter is dropping ticks. With a
     500ms poll interval, this should be rare (~every other tick at
     worst). If it's dropping most ticks, the rate-limit threshold is
     too aggressive — tune.
   - NO → not the bottleneck.

## Three failure modes the log will disambiguate

The user's symptom ("notification only shows result, not live progress")
could be any of:

| Failure mode | Log signature |
|---|---|
| (A) `core/stats` returning empty `transferring[]` because `copyfile` doesn't register | `uploadFileWithProgress: stats poll #N — percent=null (onProgress NOT called) — transferring.size=0` repeated every 500ms for the whole upload. `updateNotification: PROCESSING` only appears at start (text change) and end (text change). |
| (B) Polling IS working, percent IS changing, but rate-limiter drops too many ticks | `uploadFileWithProgress: stats poll #N — percent=X% — CALLING onProgress` with X varying, plus frequent `updateNotification: SKIPPED (rate-limited) msSinceLast=~450 threshold=500`. |
| (C) Wiring bug — `uploadFileWithProgress` never invoked | `uploadFileWithProgress: ENTRY` does NOT appear in log. Only `performUpload: uploading original` + `performUpload: original upload OK` appear, with no polling- loop logs between them. |

The fix is different for each:
- (A) → switch from `core/stats` polling to a different progress source
  (e.g. wrap the source stream to count bytes sent, or use rclone's async
  `sync/copy` API with `job/status` polling).
- (B) → tune `NOTIFICATION_UPDATE_MIN_INTERVAL_MS` (500ms) and/or
  `PROGRESS_POLL_INTERVAL_MS` (500ms).
- (C) → wire `uploadFileWithProgress` into the actual call site (find
  where `performUpload` was bypassed, fix the bypass).

## Step 1 — second progress indicator (also addressed this round)

A separate exploration of all UI progress-indicator-like components found
that the user's "mystery" second indicator is **almost certainly
`ImportBottomSheetDialogFragment`** — the photo-import progress bottom
sheet. It shows a horizontal progress bar + percentage + N/M counter +
spinner at the bottom of the gallery during photo import.

It looks like a sync indicator because it overlaps with sync: after each
photo's import, `PhotoRepository.safeImportPhoto()` enqueues
`PhotoSyncWorker` immediately. So during a multi-photo import, the
import sheet is showing "5 of 12" while the sync notification starts
posting "Uploading 1 of 5: foo.jpg" — two unrelated progress bars
counting different things (import progress vs. sync progress).

Other candidates ruled out:
- `MigrationService` foreground notification (channel `BackgroundTasks`,
  ID 1001, icon `ic_database`) — only during legacy encryption
  migration, not sync.
- `RestoreBackupDialogFragment` — only during backup restore.
- `RcloneController.state: StateFlow<RcdState>` — exposed but has zero
  UI consumers (dead from the UI side).
- `WorkInfo.State.RUNNING` — only consumed by `dumpWorkInfo` logcat
  logging, no UI.
- `SyncBatchTracker.getBatchState()` — only consumed by
  `PhotoSyncWorker` for notification content, no UI.

Confirmation question for the user: where on screen is the mystery
indicator? If it's a bottom sheet with "Importing File(s)" + a
horizontal bar + "%" + "N / M" + Abort button, that's
`ImportBottomSheetDialogFragment` — it's NOT actually a sync indicator,
it's an import-progress indicator that happens to overlap with sync
because import enqueues sync immediately after each photo commit.

## Files modified

1. `app/src/main/java/dev/leonlatsch/photok/sync/rclone/RcloneController.kt`
   — added ENTRY / per-poll / EXIT logging inside
   `uploadFileWithProgress()`.
2. `app/src/main/java/dev/leonlatsch/photok/sync/work/PhotoSyncWorker.kt`
   — added SKIPPED / PROCESSING / unconditional POSTED logging inside
   `updateNotification()`.

## Build verification

Sandbox has no Android SDK installed (`ANDROID_HOME` unset, no
`/opt/android-sdk` or `~/Android`). Cannot run
`./gradlew compileFossDebugKotlin` here. User must build on their side.

Manual review confirms:
- All `JsonArray` / `JsonObject` / `JsonPrimitive` accesses use the
  already-imported `kotlinx.serialization.json` types (imports were
  present from the v8 round).
- No new top-level symbols; all changes are inside existing functions.
- `diag()` is the existing RcloneDiag-pattern logging helper (writes to
  both `Log.e` and `files/sync_log.txt`).

## What the user needs to do next

1. Pull this commit on `main`.
2. Build: `./gradlew assembleFossDebug` (or whichever variant they
   normally install).
3. Install on device.
4. Reproduce: import a batch of at least 5–10 photos large enough that
   the upload takes several seconds (a few MB each is enough).
5. Capture the log via:
   ```
   adb logcat -d | grep RcloneDiag > step0-diag.log
   # OR
   adb shell run-as dev.leonlatsch.photok cat files/sync_log.txt > step0-diag.log
   ```
6. Send `step0-diag.log` back.

Once the log is in hand, the fix (Step 2) will be written based on which
of the three failure modes the log proves.
