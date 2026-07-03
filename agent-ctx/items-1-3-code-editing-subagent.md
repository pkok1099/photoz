# Items 1–3: .crypt deletion, thumbnails-only restore, stuck sync indicator

**Task ID**: items-1-3-code-editing-subagent
**Agent**: code-editing subagent
**Date**: 2026 build session
**Branch**: `main`
**Build status**: `BUILD SUCCESSFUL` (`:app:assembleFossDebug`, arm64-v8a)

## Summary

Three coupled fixes shipped in a single commit:

1. **Item 1 — Unconditional `.crypt` deletion after verified upload; thumbnails-only restore.**
2. **Item 2 — Mark `syncDeleteAfterUpload` setting as redundant; hide the toggle from Settings UI.**
3. **Item 3 — Stuck sync-status indicator; count `LOCAL_ONLY + UPLOAD_PENDING`; reword to "N photos to sync".**

All work is in the `onlasdan.gallery` package. No public-API changes; no schema migration
required (the SharedPreferences key is retained for backwards-compat with existing prefs).

---

## Item 1 — `.crypt` deletion after verified upload + thumbnails-only restore

### Files touched

- `app/src/main/java/onlasdan/gallery/sync/work/PhotoSyncWorker.kt`
- `app/src/main/java/onlasdan/gallery/gallery/ui/GalleryViewModel.kt`
- (`SyncRestorer.kt` left unchanged — `restoreAllOriginals()` retained as a utility, just
  no longer called from the restore-from-backup flow.)

### Change 1a — `PhotoSyncWorker.kt`

Removed the `if (config.syncDeleteAfterUpload)` guard around `deleteLocalOriginalAfterUpload(photo)`
inside the `outcome.isSuccess` branch of `doWorkInternal`. The deletion is now **unconditional**
after the photo transitions to `UPLOADED`. A multi-line comment explains the rationale and the
link to the (now-redundant) Config key.

Before:
```kotlin
if (config.syncDeleteAfterUpload) {
    deleteLocalOriginalAfterUpload(photo)
}
```

After:
```kotlin
// ─── Unconditional local-original deletion (Item 1) ───────────
// After a verified upload the local encrypted `.crypt` original
// is ALWAYS deleted. …
deleteLocalOriginalAfterUpload(photo)
```

The `deleteLocalOriginalAfterUpload(photo)` function itself is unchanged — it lives at the
bottom of `PhotoSyncWorker.kt` and uses `appContext.getFileStreamPath(photo.internalFileName)`
+ `origPath.delete()`. Thumbnails are still kept locally (they're not touched by this path).

### Change 1b — `GalleryViewModel.kt`

`onRestoreFromBackup()` no longer calls `syncRestorer.restoreAllOriginals()`. It now only
invokes `repoManager.restoreThumbnailsFromPacks()` and reports that count in the success
toast. The previous `thumbsRestored + originalsRestored` sum collapses to just
`thumbsRestored`.

The `syncRestorer` constructor parameter is kept (Hilt still wires it up; the
`SyncRestorer.restoreAllOriginals()` function is preserved as a utility for future use per
the task instructions). The field is annotated `@Suppress("unused")` because it is no longer
referenced inside this VM; the image viewer and video player still call
`SyncRestorer.ensureLocalOriginal*()` directly (on-demand fetch when a photo is opened).

A large comment block at the call site explains the new thumbnails-only semantics and the
on-demand fetch path for originals.

### Why this is correct

- The remote (Koofr/whatever rclone backend) is now the source of truth after upload; the
  local `.crypt` is a transient pre-upload cache.
- Thumbnails stay local so the gallery grid renders instantly. Originals are downloaded
  transparently when the user opens a photo (already implemented in `EncryptedImageFetcher`
  + `ImageViewerViewModel` via `SyncRestorer.ensureLocalOriginal*`).
- Restore-from-backup previously did eager bulk downloads of *every* original — gigabytes of
  data on a metered connection. Now it only restores thumbnails + DB rows; originals are
  fetched on-demand.

---

## Item 2 — `syncDeleteAfterUpload` setting is now redundant

### Files touched

- `app/src/main/java/onlasdan/gallery/settings/data/Config.kt`
- `app/src/main/java/onlasdan/gallery/settings/domain/PreferenceScreenConfig.kt`

### Change 2a — `Config.kt`

The `var syncDeleteAfterUpload` property and the companion-object constants
(`SYNC_DELETE_AFTER_UPLOAD`, `SYNC_DELETE_AFTER_UPLOAD_DEFAULT`) are **kept** for
backwards-compat with existing prefs files (an upgrade shouldn't blow away the stored
value). The property is annotated `@Suppress("unused")` and the KDoc is expanded with a
clear "NOW UNUSED" section pointing at `PhotoSyncWorker`'s unconditional deletion.

### Change 2b — `PreferenceScreenConfig.kt`

The `Preference.Switch` entry for `SYNC_DELETE_AFTER_UPLOAD` under the Cloud Sync section
is **commented out** (not deleted). Commented-out rather than removed so the diff is small
and a future maintainer can see exactly what was there. The unused imports for
`SYNC_DELETE_AFTER_UPLOAD` and `SYNC_DELETE_AFTER_UPLOAD_DEFAULT` are removed to keep
the import block clean.

The corresponding `<string>` resources (`settings_sync_delete_after_upload_title`,
`settings_sync_delete_after_upload_summary`) are left in `strings.xml` — they're harmless
and removing them would create churn in the translations pipeline.

### Finding (reported, no defaults flipped)

> The dedup registry now stores `content_hash` (sha256 of plaintext, computed at import
> time). This is a **local** hash — it confirms the file we encrypted matches what the user
> originally selected, but it is NOT a remote-side hash. Upload verification today uses
> `verifyFileExists` (a size check) + `verifyRemote` (sha256 hash check, but Koofr doesn't
> support remote hashing so it's skipped). For true upload verification we'd need to
> download the remote file back and hash it locally (expensive, defeats the point of
> on-demand fetch). The safety concern from earlier code review (size-only verification
> post-upload) still applies — but with the new dedup registry, we at least know the
> `content_hash` matches what was intended at import time.
>
> Per task instructions: **reported but NOT acted on**. No defaults flipped.

---

## Item 3 — Stuck sync-status indicator

### Files touched

- `app/src/main/java/onlasdan/gallery/gallery/ui/GalleryUiStateFactory.kt`
- `app/src/main/java/onlasdan/gallery/gallery/ui/compose/GalleryScreen.kt`
- `app/src/main/res/values/strings.xml`

### Change 3a — `GalleryUiStateFactory.kt`

The `filteredPending` count now includes both `LOCAL_ONLY` and `UPLOAD_PENDING` photos:

```kotlin
val filteredPending = filteredPhotos.count {
    it.syncState == SyncState.UPLOAD_PENDING ||
        it.syncState == SyncState.LOCAL_ONLY
}
```

Before, it counted only `UPLOAD_PENDING`. That caused the indicator to flicker/stick:

- A freshly-imported photo sits in `LOCAL_ONLY` until the worker flips it to
  `UPLOAD_PENDING` (line ~307 in `PhotoSyncWorker`).
- During WorkManager's scheduling delay, and during retry-backoff, the photo is in
  `UPLOAD_PENDING` but no upload is actively running — so the chip would say
  "Syncing N…" indefinitely.

The new semantics: anything that isn't `UPLOADED` or `UPLOAD_FAILED` is "pending sync".
The indicator appears at import time (LOCAL_ONLY) and clears only when every photo has
reached a terminal state.

### Change 3b — `strings.xml`

`gallery_sync_status_syncing` changed from `"Syncing %1$d…"` to `"%1$d photos to sync"`.

"Syncing…" implied an active upload — not always true (scheduling delay, retry backoff,
LOCAL_ONLY pre-enqueue). "N photos to sync" is honest for both `LOCAL_ONLY` and
`UPLOAD_PENDING` cases.

### Change 3c — `GalleryScreen.kt`

The KDoc on `GallerySyncStatusIndicator` is expanded to explain the new count semantics
and the string reword. The Composable body is unchanged — it already uses the string
resource, so it picks up the new wording automatically.

---

## Build

```
export JAVA_HOME=/home/z/jdk/jdk21
export PATH=$JAVA_HOME/bin:$PATH
./gradlew :app:assembleFossDebug --no-daemon --console=plain -Pandroid.injected.build.abi=arm64-v8a
```

Result: **BUILD SUCCESSFUL in 40s**, 45 actionable tasks (15 executed, 30 up-to-date).

Pre-existing non-fatal warnings (unchanged, not introduced by this commit):
- `string/migration_running_progress` Multiple substitutions in non-positional format
  (pre-existing).
- KAPT warning about `[room.schemaLocation, kapt.kotlin.generated, room.incremental]` not
  recognized (pre-existing).
- Java source/target 8 deprecation warnings (pre-existing; toolchain config).

No new warnings introduced by these edits.

---

## Commit

```
fix: unconditional .crypt deletion after upload, restore thumbnails only, fix stuck sync indicator
```

## Files changed (final list)

```
app/src/main/java/onlasdan/gallery/sync/work/PhotoSyncWorker.kt
app/src/main/java/onlasdan/gallery/gallery/ui/GalleryViewModel.kt
app/src/main/java/onlasdan/gallery/settings/data/Config.kt
app/src/main/java/onlasdan/gallery/settings/domain/PreferenceScreenConfig.kt
app/src/main/java/onlasdan/gallery/gallery/ui/GalleryUiStateFactory.kt
app/src/main/java/onlasdan/gallery/gallery/ui/compose/GalleryScreen.kt
app/src/main/res/values/strings.xml
```

## What was NOT touched (per task instructions)

- `SyncRestorer.kt` — `ensureLocalOriginal()` / `ensureLocalOriginalWithProgress()` /
  `restoreAllOriginals()` all unchanged. `restoreAllOriginals()` is preserved as a utility
  for future use; it's just no longer called from the restore-from-backup flow.
- `Config.SYNC_DELETE_AFTER_UPLOAD` constant + the `var syncDeleteAfterUpload` property —
  kept for backwards-compat with existing prefs.
- Upload verification defaults — NOT flipped (size-only verification remains; reported as
  a finding only).
