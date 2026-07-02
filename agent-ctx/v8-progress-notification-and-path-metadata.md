# Task v8-progress-notification-and-path-metadata — Work Record

**Agent:** code-editing subagent
**Task ID:** v8-progress-notification-and-path-metadata
**Scope:** Stable upload notification with real progress percentage (Part 1) +
local/remote path consistency via per-photo metadata sidecar (Part 2).
**Outcome:** All edits applied; `./gradlew compileFossDebugKotlin --rerun-tasks`
succeeds with only pre-existing warnings (no new errors). No commit / push
performed, as instructed.

This file is the canonical reference for what changed in v8. Read it before
touching any of the files listed below.

---

## Summary of changes by file

### 1. `app/src/main/java/dev/leonlatsch/photok/model/database/entity/Photo.kt`

**Part 2a-1 — Added `relativePath` column to the `Photo` entity.**

- Added new field after `syncState` (data class is named-arg style, so the new
  defaulted parameter doesn't break any existing call sites):
  ```kotlin
  @ColumnInfo(name = COL_RELATIVE_PATH, defaultValue = "NULL")
  @Expose
  var relativePath: String? = null,
  ```
- Added the matching companion constant `COL_RELATIVE_PATH = "relativePath"`.
- KDoc explains the semantics: metadata-only provenance tag, NOT a filesystem
  path the photo gets written to (Photok's vault is its own managed encrypted
  storage; this column records where the photo came from).

### 2. `app/src/main/java/dev/leonlatsch/photok/model/database/PhotokDatabase.kt`

**Part 2a-2 — Bumped DATABASE_VERSION 7 → 8 + added AutoMigration.**

- `private const val DATABASE_VERSION = 8`
- New `AutoMigration(from = 7, to = 8)` entry in `autoMigrations`.
- No `AutoMigrationSpec` needed — Room auto-generates the
  `ALTER TABLE photo ADD COLUMN relativePath TEXT DEFAULT NULL` for a new
  nullable column with a default NULL. Schema export is configured in
  `app/build.gradle.kts` (`room.schemaLocation = $projectDir/schemas`), so
  the existing `7.json` snapshot drives the AutoMigration codegen. The
  `8.json` snapshot will be generated on the next full build.

### 3. `app/src/main/java/dev/leonlatsch/photok/sync/domain/SyncConfig.kt`

**Part 2a-4 — Added metadata sidecar constants.**

- `const val METADATA_DIR: String = "metadata"`
- `const val METADATA_FILENAME_SUFFIX: String = ".json"`
- KDoc explains the remote layout: sidecar lives at
  `<remote>:photok-backup/metadata/<uuid>.json` (note the `photok-backup/`
  prefix — the metadata dir lives UNDER the repo root, alongside
  `vault-protection/`. The encrypted originals/thumbnails/videos live at the
  REMOTE ROOT — no prefix — for backwards compat with PR1/PR4 installs).

### 4. `app/src/main/java/dev/leonlatsch/photok/model/repositories/PhotoRepository.kt`

**Part 2a-3 — Captured `relativePath` at import time in `safeImportPhoto()`.**

- Added `relativePath = metaData.fileName` to the `Photo(...)` constructor
  call. The current `FileMetaData` (from `getMetadataFor()` in
  `other/Utils.kt`) doesn't expose MediaStore's `RELATIVE_PATH` column, so
  the filename itself is the most meaningful provenance we have today.
- Added a TODO(v8-followup) comment block explaining that a future enhancement
  could query the MediaStore `RELATIVE_PATH` column (e.g. `DCIM/Camera`,
  `Pictures/Screenshots`) for gallery-sourced imports once `FileMetaData`
  is extended. Shares the rationale and points at `getMetadataFor` so the
  follow-up engineer knows where to plug in.

### 5. `app/src/main/java/dev/leonlatsch/photok/sync/rclone/RcloneController.kt`

**Part 1a-1 — Added `uploadFileWithProgress()` + `computeUploadProgressPercent()`
helper, plus supporting imports + constant.**

- Added imports:
  - `kotlinx.coroutines.async`
  - `kotlinx.coroutines.coroutineScope`
  - `kotlinx.serialization.json.JsonPrimitive`
- New public suspend fn `uploadFileWithProgress(localPath, remotePath, onProgress)`:
  1. Launches `operations/copyfile` in a child `async` coroutine that holds
     `callMutex` for its full duration (preserves the same serialization
     contract as the existing `uploadFile()`).
  2. Concurrently polls `core/stats` every `PROGRESS_POLL_INTERVAL_MS` (500ms)
     via `invokeRc("core/stats", buildJsonObject{})` — bypasses `callMutex`
     so the poll can actually run while the upload holds the mutex. Safe
     because `invokeRc` is a single stateless HTTP POST and `core/stats` is
     read-only. (Full rationale in the function kdoc.)
  3. Parses `transferring[0].bytes` and `transferring[0].totalBytes` via
     `computeUploadProgressPercent()`. Returns `null` if:
     - `transferring` is missing/empty (upload hasn't registered yet, or
       already finished) — `onProgress` NOT called.
     - `bytes` / `totalBytes` are missing or non-numeric — `onProgress` NOT called.
     - `totalBytes <= 0` (divide-by-zero guard) — `onProgress` NOT called.
     Otherwise returns `bytes / totalBytes * 100f` clamped to `0f..100f`.
  4. Calls `onProgress(percent)` on each successful poll.
  5. Stops polling when `uploadDeferred.isCompleted`; awaits the deferred
     to propagate any exception (incl. `CancellationException`).
  6. Stats poll failures are logged via `diag()` but don't break the upload.
- The existing `uploadFile()` is unchanged — callers that don't need
  progress feedback (thumbnail / video-preview / metadata-sidecar uploads)
  continue to use it.
- New private fn `computeUploadProgressPercent(stats: JsonObject): Float?`:
  extracts `bytes` / `totalBytes` from the first entry of the `transferring`
  array, applies the divide-by-zero guard, clamps to 0..100. Documented
  null-return cases.
- New companion constant `PROGRESS_POLL_INTERVAL_MS = 500L`.

### 6. `app/src/main/java/dev/leonlatsch/photok/sync/work/PhotoSyncWorker.kt`

**Part 1a-2 + Part 1b (stability) + Part 2a-4 (sidecar upload) +
Part 2b-2 (no-overwrite comment).**

Major changes:

- **Imports added:**
  - `dev.leonlatsch.photok.sync.rclone.RepoManager` — for `REPO_DIR` constant
    used to build the sidecar remote path.
  - `kotlinx.serialization.json.Json`, `JsonObject`, `buildJsonObject`, `put` —
    for building the sidecar JSON.

- **Instance fields added:**
  - `lastNotificationUpdateMs: Long = 0L` — rate-limit state for
    `updateNotification`.
  - `lastNotificationText: String? = null` — state-change detector for
    `updateNotification` (so a genuine "Uploading: foo" → "Uploaded: foo"
    transition bypasses the rate limit even within the 500ms window).
  - `metadataJson = Json { ignoreUnknownKeys = true }` — serializer for the
    metadata sidecar (mirrors the pattern in `RcloneController`).

- **`performUpload()` reworked:**
  - Thumbnail / video-preview uploads: each is preceded by
    `updateNotification(progress = null, text = "Uploading: <fileName>")`
    (indeterminate progress). On failure, calls
    `reportUploadFailureNotification(photo.fileName)` then rethrows.
  - Original upload: switched from `uploadFile()` to `uploadFileWithProgress()`.
    Pre-upload state is indeterminate progress; the progress callback calls
    `updateNotification(progress = percent, text = "Uploading: <fileName>")`.
    On failure, `reportUploadFailureNotification` + rethrow.
  - `verifyFileExists` / `verifyRemote` failure paths also call
    `reportUploadFailureNotification` + rethrow.
  - **New step (Part 2a-4): after original upload + verification,
    `uploadMetadataSidecar(photo, remote)`. Non-fatal — failures are logged
    via `diag()` + `Timber.w` but do NOT fail the worker (the photo's
    encrypted artifacts are already uploaded and verified).
  - Post-upload success state: `updateNotification(progress = 100f, text =
    "Uploaded: <fileName>")` — brief "Uploaded" state, then the worker
    returns `Result.success()` and WorkManager cancels the foreground
    notification.

- **New private suspend fn `uploadMetadataSidecar(photo, remote)`:**
  - Builds JSON: `{"uuid","relativePath","fileName","type","size"}`.
  - `relativePath` falls back to `photo.fileName` if the column is null
    (forward-compat with v7-imported photos that don't have the column
    populated until re-import).
  - `type` is the enum constant name (`photo.type.name`, e.g. "JPEG", "MP4")
    — restored via `PhotoType.valueOf(name)` on the receiver side, falling
    back to JPEG if the value is unknown.
  - Writes the JSON to `cacheDir/photok-meta-<uuid>.json`, uploads via
    `rcloneController.uploadFile()` to
    `<remote>:photok-backup/metadata/<uuid>.json`, deletes the temp file in
    a `finally` block.

- **`buildNotification(text)` refactored to delegate to new
  `buildNotificationInternal(text, progress, ongoing)`** which supports:
  - `progress: Float?` — null = indeterminate (spinning); non-null = determinate
    0..100 (with `coerceIn(0, 100)` to guard against rclone momentarily
    reporting >100%).
  - `ongoing: Boolean` — `true` for foreground-style (auto-cancel when worker
    ends); `false` for "left-behind" failure notification that stays visible.
  - `setOnlyAlertOnce(true)` always set (prevents sound/vibration on every
    in-place update).

- **New fn `updateNotification(progress, text, ongoing = true)`:**
  - Rate-limit: drops calls within `NOTIFICATION_UPDATE_MIN_INTERVAL_MS`
    (500ms) of the last update, UNLESS:
    - `progress >= 100f` (terminal state — always shown), OR
    - `text != lastNotificationText` (genuine state change — always shown).
  - Calls `notificationManager.notify(NOTIFICATION_ID, buildNotificationInternal(...))`
    to update the existing notification in-place (same slot).

- **New fn `reportUploadFailureNotification(fileName)`:** thin wrapper that
  calls `updateNotification(progress = null, text = "Upload failed:
  <fileName>", ongoing = false)`. Non-ongoing so the notification stays
  visible in the system tray after the worker ends.

- **Companion object additions:**
  - `NOTIFICATION_ID = 4242` now has a multi-paragraph comment explaining:
    - Fixed constant → all in-place updates go to the same slot (stability).
    - **Batch uploads caveat:** each PhotoSyncWorker instance is a separate
      WorkManager job, but they all share this single NOTIFICATION_ID.
      If multiple workers run concurrently, the LAST one to call
      `updateNotification()` wins. Acceptable for now because the in-app
      top-bar queue indicator (separate from this notification) shows the
      real queue count. Future enhancement: per-worker NOTIFICATION_ID
      derived from UUID hashCode.
  - New constant `NOTIFICATION_UPDATE_MIN_INTERVAL_MS = 500L`.

- **Part 2b-2 (no-overwrite comment):** added a multi-line comment at the
  existing `if (photo.syncState == SyncState.UPLOADED) { return
  Result.success() }` guard explaining the UUID-based no-overwrite design:
  remote layout is UUID-keyed (`originals/<uuid>.crypt`,
  `thumbnails/<uuid>.crypt.tn`, `videos/<uuid>.crypt.vp`,
  `photok-backup/metadata/<uuid>.json`), a re-upload of the same UUID
  overwrites the same remote paths by design, and the `syncState == UPLOADED`
  check is the FIRST line of defense that makes re-enqueue a no-op without
  touching the network.

### 7. `app/src/main/java/dev/leonlatsch/photok/sync/rclone/RepoManager.kt`

**Part 2b — Restore uses metadata sidecar; idempotency unchanged.**

- `restoreThumbnailsAfterLogin()` enhanced: after each thumbnail download
  succeeds, calls new `tryDownloadPhotoMetadata(remote, uuid)` to best-effort
  fetch the metadata sidecar. The Photo row is then constructed with:
  - `type = metadata?.type ?: PhotoType.JPEG`
  - `size = metadata?.size ?: 0L`
  - `relativePath = metadata?.relativePath` (nullable)
  - `fileName` / `importedAt` / `lastModified` / `uuid` / `syncState` are
    unchanged from the pre-v8 PR4 behavior.
- The diag log line for the DB insert now includes the resolved
  type/size/relativePath + a `metaSource` indicator (`sidecar` vs `defaults`)
  so restore-time metadata availability is observable from `sync_log.txt`.
- **Idempotency check left intact** — the `photoDao.get(uuid)` check that
  skips already-existing rows is unchanged. Only the Photo row *construction*
  was enhanced to use the metadata when available.
- Removed the TODO comment about "PhotoType cannot be inferred from the
  thumbnail filename alone" — the v8 metadata sidecar IS the persistence
  mechanism the TODO was asking for.

- **New private data class `PhotoMetadata(relativePath, type, size)`:**
  fields are nullable / defaulted to mirror the sidecar's optional nature.
  `uuid` / `fileName` from the sidecar are intentionally NOT exposed here
  (caller already knows the uuid; fileName on the DB row stays as the
  internal-storage pattern for PR4-compat — future enhancement noted in kdoc).

- **New private suspend fn `tryDownloadPhotoMetadata(remote, uuid): PhotoMetadata?`:**
  - Downloads `<remote>:photok-backup/metadata/<uuid>.json` to a temp file
    in `cacheDir`.
  - On download failure (incl. "file not found" for pre-v8 photos), logs
    at debug level and returns `null`. Restore MUST NOT block on metadata.
  - On success, reads the file and delegates to `parsePhotoMetadata()`.
  - Always deletes the temp file in a `finally` block.

- **New private fn `parsePhotoMetadata(json: String): PhotoMetadata?`:**
  - Regex-based, no external dependency — mirrors the style of the existing
    `parseMarker()` and `parseVaultProtection()` helpers.
  - Extracts `relativePath` (string, nullable), `type` (string, required),
    `size` (long, defaults to 0 if missing).
  - `type` is parsed via `runCatching { PhotoType.valueOf(typeStr) }.getOrDefault(PhotoType.JPEG)`
    — forward-compat with future PhotoType entries the receiver doesn't know
    about (degrades gracefully to JPEG, same as the pre-v8 default).
  - Returns `null` if `type` is missing (without it, the sidecar is no
    better than the pre-v8 defaults).

---

## Build verification

```
$ ./gradlew compileFossDebugKotlin --offline --no-daemon --rerun-tasks
BUILD SUCCESSFUL in 1m 1s
21 actionable tasks: 21 executed
```

Only pre-existing warnings (Kotlin annotation-target defaults, deprecated
`FragmentStatePagerAdapter`, etc.) — no new errors or warnings introduced by
this change set.

Lint task (`./gradlew lintFossDebug`) cannot run in this sandbox — the
OpenJDK 21 install is missing `JAVA_COMPILER` capabilities for the
`compileFossDebugJavaWithJavac` task. This is a tooling limitation, not a
code issue.

---

## Explicitly out of scope (per task spec)

- `.crypt` / `.tn` local storage cleanup — NOT touched.
- Manual-sha256 metadata (the `sha256OfFile` / `verifyRemote` flow) — NOT
  touched. The new metadata sidecar is a separate, additive artifact.
- `uploadFile()` semantics — UNCHANGED. Callers that don't need progress
  feedback (thumbnails, video previews, metadata sidecar itself) continue
  to use it.
- `parseMarker` / `parseVaultProtection` regex style — preserved in the new
  `parsePhotoMetadata` (no new external JSON dependency added to
  `RepoManager`).
- Backup file format (`PhotoBackup` / `BackupMappers`) — NOT touched. The
  `relativePath` column is metadata-only; the cloud sync metadata sidecar
  is the canonical source. Local backup/restore will lose this field, which
  is acceptable per the design (it's reconstructed from the sidecar on
  cloud restore).
- DB schema export — the `8.json` schema snapshot will be auto-generated
  on the next full build (kapt runs Room's schema export during compilation).
  The existing `7.json` snapshot drives the v7→v8 AutoMigration codegen.

---

## Files modified (summary list)

1. `app/src/main/java/dev/leonlatsch/photok/model/database/entity/Photo.kt`
2. `app/src/main/java/dev/leonlatsch/photok/model/database/PhotokDatabase.kt`
3. `app/src/main/java/dev/leonlatsch/photok/sync/domain/SyncConfig.kt`
4. `app/src/main/java/dev/leonlatsch/photok/model/repositories/PhotoRepository.kt`
5. `app/src/main/java/dev/leonlatsch/photok/sync/rclone/RcloneController.kt`
6. `app/src/main/java/dev/leonlatsch/photok/sync/work/PhotoSyncWorker.kt`
7. `app/src/main/java/dev/leonlatsch/photok/sync/rclone/RepoManager.kt`

No new files created. No deletions. No commit / push.
