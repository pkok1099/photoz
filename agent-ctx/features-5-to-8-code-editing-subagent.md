# Features 5-8 Implementation Work Record

## Task ID: features-5-to-8
## Agent: code-editing subagent
## Date: 2026-07-02

## Summary

Implemented features 5-8 against the photok-sync-fork Android app
(package `onlasdan.gallery`), each committed and pushed to GitHub as a
separate checkpoint. Each commit was built with the standard
`./gradlew :app:assembleFossDebug --no-daemon --console=plain
-Pandroid.injected.build.abi=arm64-v8a` invocation, and every build
completed successfully (`BUILD SUCCESSFUL`).

## Per-feature details

### Feature 5: Registry Garbage Collection (commit `854af0fd`)

**soft delete + thumbnail repack + remote cleanup.**

- `RcloneController.kt` — added `deleteFile(remotePath)` wrapping rclone's
  `operations/deletefile` rc call. Idempotent-ish: caller treats "file
  already gone" as success.
- `HashRegistryDao.kt` — added `findByHashIncludingDeleted(hash)`,
  `getAllIncludingDeleted()`, and `deleteByHash(hash)` so the GC can
  enumerate tombstones and physically remove rows after the remote
  original is gone.
- `HashRegistry.kt`:
  - Constructor now takes a `SessionRepository` (Hilt-provided) so the GC
    path can encrypt + flush the registry to the remote after compaction.
  - `softDelete(contentHash)` — marks the entry `deleted=true` in the
    local cache + best-effort flushes the updated registry to the remote
    so other devices see the tombstone on their next login. Idempotent.
  - `gcThumbnailPacks()` — iterates each pack referenced by any registry
    entry, computes the % of tombstoned entries, and if >30% are dead:
    re-downloads the pack, extracts ONLY the live entries, uploads them
    as a fresh pack, updates each live entry's pack name / offset /
    length, clears the tombstoned entries' pack fields, then deletes the
    OLD pack from the remote. 100%-dead packs are deleted outright.
    Registry is flushed to the remote at the end.
  - `gcOriginals()` — iterates tombstoned entries, deletes the remote
    `<remote>:photoz-backup/originals/<uuid>.crypt` (and the legacy
    individual thumbnail at `<remote>:thumbnails/<uuid>.crypt.tn`), then
    physically removes the registry row (tombstone → no row, since the
    original is gone). Registry is flushed at the end.
- `SyncConfig.kt` — added `ORIGINAL_FILE_SUFFIX = ".crypt"` and
  `THUMBNAIL_FILE_SUFFIX = ".crypt.tn"` constants used by `gcOriginals`
  to build the remote paths.
- `PhotoRepository.kt` — `safeDeletePhoto` now calls
  `hashRegistry.softDelete(contentHash)` BEFORE the local encrypted file
  is removed. Non-fatal: if the tombstone fails, the local delete still
  proceeds (the orphaned remote original is reclaimable later via the
  Settings → "Clean up backup" button).
- `SettingsFragment.kt` + `PreferenceScreenConfig.kt` — added a
  "Clean up backup" preference row in the Cloud Sync section.
- `SettingsViewModel.kt` — added `cleanupBackup()` that runs
  `gcThumbnailPacks()` + `gcOriginals()` and surfaces the result via
  toast (started / success with counts / failed / nothing to do).
- `SettingsScreen.kt` — registered the `KEY_ACTION_CLEANUP_BACKUP`
  preference callback.
- 5 new string resources for the cleanup UI.

### Feature 6: Search & Filter (commit `1a5ed7b0`)

**filename search + All/Photos/Videos/Files type filter chips.**

- `GalleryFilter` enum extended from `PHOTOS / FILES` to
  `ALL / PHOTOS / VIDEOS / FILES`. `PHOTOS` is now images-only (videos
  moved to their own `VIDEOS` chip). `ALL` is the new default.
- `GalleryUiState.Content` — added `searchQuery: String` field.
- `GalleryUiStateFactory.create` — added `searchQuery` parameter and a
  client-side filename-contains filter (case-insensitive). Applied AFTER
  the type filter so the two compose.
- `GalleryUiEvent` — added `SearchQueryChanged(query)` event.
- `GalleryViewModel` — added `searchQueryFlow: MutableStateFlow<String>`
  and wired it into the `combine()` that builds `uiState`. Default
  filter changed from `PHOTOS` to `ALL`.
- `GalleryContent.kt` — added a `GallerySearchBar` composable
  (`OutlinedTextField` with leading search icon + trailing clear-X
  IconButton) above the filter chip row. Extended `GalleryFilterRow`
  from 2 chips to 4 (All / Photos / Videos / Files).
- 3 new string resources (`gallery_filter_all`, `gallery_filter_videos`,
  `gallery_search_placeholder`, `gallery_search_clear`).

### Feature 7: Video loading indicator (commit `4b4c7c79`)

**on-demand video download with visible progress bar.**

The existing `ImageViewerViewModel` already kicked off
`SyncRestorer.ensureLocalOriginal(uuid)` for remote-only videos, but the
viewer's shutter showed only an indeterminate spinner. This feature
adds a per-UUID download state and a determinate progress bar.

- `RcloneController.kt` — added `downloadFileWithProgress(remotePath,
  localPath, expectedSize, onProgress)` mirroring the size-based
  estimate approach used by `uploadFileWithProgress`. Capped at 95%
  until the real download completes (then jumps to 100%). Unknown-size
  path degrades to 0% → 100% with no estimate loop.
- `SyncRestorer.kt` — added `ensureLocalOriginalWithProgress(uuid,
  onProgress)` that delegates to `downloadFileWithProgress` using the
  photo's stored `size` column as the expected size.
- `ImageViewerViewModel.kt`:
  - Added `VideoDownloadState` sealed interface
    (`Idle / Downloading(progress, lastUpdateMs) / Done / Failed`).
  - Added `videoDownloadsFlow: MutableStateFlow<Map<String,
    VideoDownloadState>>` and an `inflightDownloads` set to prevent
    re-launching the same download on every flow emission.
  - Added `maybeStartVideoDownload(photo)` — idempotent; if the local
    file exists, marks `Done` immediately; otherwise kicks off the
    download with progress callbacks (rate-limited to ~5/sec via
    `lastUpdateMs`).
  - `uiState` now combines `videoDownloadsFlow` so the viewer shutter
    can observe per-UUID download state.
- `ImageViewerScreen.kt` — the pager-settle `LaunchedEffect` now calls
  `viewModel.maybeStartVideoDownload(item.photo)` BEFORE handing off to
  ExoPlayer. Passes `downloadState = uiState.videoDownloads[uuid]` to
  `ImageViewerVideoPage`.
- `ImageViewerPage.kt` — `ImageViewerVideoPage` accepts an optional
  `downloadState: VideoDownloadState?`. The shutter renders three
  branches: `Downloading` → "Downloading video…" with a
  `LinearProgressIndicator` (determinate if % > 0, indeterminate
  otherwise) + the percentage label; `Failed` → "Couldn't download
  video" + the error message; `null/Idle/Done` → original
  `CircularProgressIndicator` (ExoPlayer is buffering the local file).
- 2 new string resources (`video_downloading`, `video_download_failed`).

### Feature 8: Batch operations (commit `943d7640`)

**multi-select delete/export/move — verified existing + improved UX.**

The existing `PhotoGallery.kt` already had Delete, Export, and
Add-to-album batch actions wired through the multi-selection toolbar's
"More" dropdown. Verified each works end-to-end:

- **Delete**: `GalleryUiEvent.OnDelete` → `PhotoAction.DeletePhotos` →
  `DeleteBottomSheetDialogFragment` → `DeleteViewModel.processItem`
  calls `photoRepository.safeDeletePhoto(item)` for each photo. (Now
  also tombstones the registry entry via Feature 5.)
- **Export**: `GalleryUiEvent.OnExport` → `PhotoAction.ExportPhotos` →
  `ExportBottomSheetDialogFragment` → `ExportViewModel.processItem`
  calls `photoRepository.exportPhoto(item, target)` for each photo.
- **Move to album**: `GalleryUiEvent.OnAddToAlbum` →
  `showAlbumSelectionDialog = true` → `AlbumPickerDialog` → user picks
  album → `AlbumPickerViewModel` calls
  `albumRepository.link(photoUuids, albumUuid)` (links ALL selected
  photos in one call).

UX improvement: surfaced the three most common batch actions (Delete,
Export, Add-to-album) as **one-tap icon buttons directly on the
multi-selection bar** — was previously only reachable via the More
dropdown.

- `MultiSelectionMenu.kt` — added optional
  `barActions: @Composable (RowScope.() -> Unit)?` parameter rendered
  between the "N item(s) selected" label and the More button. Backwards
  compatible (defaults to `null`).
- `PhotoGallery.kt` — passes Delete/Export/Add-to-album IconButtons in
  `barActions`. Added `onAddToAlbum: () -> Unit = {}` parameter so the
  gallery can wire the icon to its album-picker trigger. The More
  dropdown retains Select All + text-label versions of the same actions
  for users who prefer the menu.
- `GalleryContent.kt` — passes `onAddToAlbum = { handleUiEvent(
  GalleryUiEvent.OnAddToAlbum) }` to `PhotoGallery`.
- `AlbumDetailContent.kt` — same: passes `onAddToAlbum = {
  showAlbumSelection = true }` so the album-detail screen also gets the
  one-tap icon.

## Build verification

Each commit was built with:
```
export JAVA_HOME=/home/z/jdk/jdk21
export PATH=$JAVA_HOME/bin:$PATH
./gradlew :app:assembleFossDebug --no-daemon --console=plain \
  -Pandroid.injected.build.abi=arm64-v8a
```
All four builds completed successfully (`BUILD SUCCESSFUL`).

## Notable fixes during this run

- **Feature 8 compile error**: First build of Feature 8 failed with
  "Unresolved reference 'IconButton'" — I'd added `IconButton` usages
  to `PhotoGallery.kt`'s `barActions` slot but forgotten to add the
  `import androidx.compose.material3.IconButton`. Added the import and
  the build went green.

## Files changed (across all 4 features)

Feature 5 (11 files):
- `app/src/main/java/onlasdan/gallery/sync/rclone/RcloneController.kt`
- `app/src/main/java/onlasdan/gallery/sync/work/HashRegistry.kt`
- `app/src/main/java/onlasdan/gallery/sync/work/HashRegistryDao.kt`
- `app/src/main/java/onlasdan/gallery/sync/work/HashRegistryEntry.kt` (no edit, just reviewed)
- `app/src/main/java/onlasdan/gallery/sync/domain/SyncConfig.kt`
- `app/src/main/java/onlasdan/gallery/model/repositories/PhotoRepository.kt`
- `app/src/main/java/onlasdan/gallery/settings/ui/SettingsFragment.kt`
- `app/src/main/java/onlasdan/gallery/settings/domain/PreferenceScreenConfig.kt`
- `app/src/main/java/onlasdan/gallery/settings/ui/compose/SettingsViewModel.kt`
- `app/src/main/java/onlasdan/gallery/settings/ui/compose/SettingsScreen.kt`
- `app/src/main/res/values/strings.xml`

Feature 6 (6 files):
- `app/src/main/java/onlasdan/gallery/gallery/ui/GalleryUiState.kt`
- `app/src/main/java/onlasdan/gallery/gallery/ui/GalleryUiStateFactory.kt`
- `app/src/main/java/onlasdan/gallery/gallery/ui/GalleryUiEvent.kt`
- `app/src/main/java/onlasdan/gallery/gallery/ui/GalleryViewModel.kt`
- `app/src/main/java/onlasdan/gallery/gallery/ui/compose/GalleryContent.kt`
- `app/src/main/res/values/strings.xml`

Feature 7 (6 files):
- `app/src/main/java/onlasdan/gallery/sync/rclone/RcloneController.kt`
- `app/src/main/java/onlasdan/gallery/sync/work/SyncRestorer.kt`
- `app/src/main/java/onlasdan/gallery/imageviewer/ui/ImageViewerViewModel.kt`
- `app/src/main/java/onlasdan/gallery/imageviewer/ui/compose/ImageViewerScreen.kt`
- `app/src/main/java/onlasdan/gallery/imageviewer/ui/compose/ImageViewerPage.kt`
- `app/src/main/res/values/strings.xml`

Feature 8 (4 files):
- `app/src/main/java/onlasdan/gallery/ui/components/MultiSelectionMenu.kt`
- `app/src/main/java/onlasdan/gallery/gallery/components/PhotoGallery.kt`
- `app/src/main/java/onlasdan/gallery/gallery/ui/compose/GalleryContent.kt`
- `app/src/main/java/onlasdan/gallery/gallery/albums/detail/ui/compose/AlbumDetailContent.kt`

## Git history (most recent first)

```
943d7640 feat: batch operations — multi-select delete/export/move
4b4c7c79 feat: video loading indicator during on-demand download
1a5ed7b0 feat: search & filter — filename search + type filter chips
854af0fd feat: registry GC — soft delete + thumbnail repack + remote cleanup
```

All four commits pushed to `origin/main`.

## Notes / what's not implemented

- **Feature 7 (video streaming via rclone serve http)**: not
  implemented — the encrypted-at-rest format means rclone serve would
  hand out ciphertext, which ExoPlayer can't decode. Per the task spec's
  minimal-implementation guidance, the chosen approach is "download
  first, then play" with a visible progress bar. True streaming would
  require either re-encrypting on-the-fly through a local HTTP proxy
  with the VMK, or migrating videos to a streamable cipher (CTR / GCM
  with chunked auth tags) — both significant undertakings beyond the
  minimal scope.
- **Feature 5 (registry GC) concurrency**: the GC is single-device.
  If two devices run cleanup concurrently, the last registry-flush
  wins. This matches the existing single-writer concurrency model
  documented in `HashRegistry`'s class kdoc.
- **Feature 8 (batch operations)**: didn't add a "Deselect all" action
  — the existing Close button on the multi-selection bar already
  cancels the entire selection, which serves the same purpose.
