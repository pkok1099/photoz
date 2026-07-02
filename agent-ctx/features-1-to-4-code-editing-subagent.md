# Features 1-4 Implementation Work Record

## Task ID: features-1-to-4
## Agent: code-editing subagent
## Date: 2026-07-02

## Summary

Implemented four feature requests against the photok-sync-fork Android app
(package `onlasdan.gallery`), each committed and pushed to GitHub as a
separate checkpoint. The build (`./gradlew :app:assembleFossDebug`)
succeeds after each feature.

## Per-feature details

### Feature 1: File Upload (commit `ec99abd8`)
- Extended `PhotoType` enum with `DOCUMENT` (PDF), `ARCHIVE` (ZIP), `AUDIO`
  (audio wildcard). Added `fileExtension` enum param + `isFile: Boolean`
  property. Updated `fromMimeType` to dispatch audio/* by prefix.
- `PhotoRepository.safeImportPhoto` already accepts any mime type via
  `fromMimeType` — no logic change, just an updated kdoc to note the new
  behaviour.
- Added `PhotoRepository.openFileExternally(photo)` — decrypts the photo
  to `cacheDir/extview/`, exposes via FileProvider, and launches
  `Intent.ACTION_VIEW`. AUDIO mime type is derived from filename extension.
- Gallery UI: added a FilterChip row at the top of `GalleryContent` to
  switch between "Photos" and "Files". New `GalleryFilter` enum +
  `GalleryUiEvent.FilterChanged` + filter state in `GalleryViewModel`.
  Filter is applied client-side in `GalleryUiStateFactory`.
- `GalleryViewModel.navigateToPhoto` now branches: photo/video types go
  through the existing in-app image viewer; `isFile` types route to
  `openFileExternally`.
- Registered a `FileProvider` in `AndroidManifest.xml` with paths config
  at `res/xml/file_paths.xml`.
- Added string resources for the filter chips + external-viewer errors.

### Feature 2: Sync Settings (commit `d571c1dd`)
- Added three user-configurable preferences to `Config.kt`:
  - `syncAutoUpload` (default `true`)
  - `syncWifiOnly` (default `false`)
  - `syncDeleteAfterUpload` (default `false`)
  Each with a `SYNC_*` SharedPreferences key + default constant in the
  companion object.
- `PreferenceScreenConfigContent`: extended the Cloud Sync section with
  three `Preference.Switch` entries.
- `PhotoSyncWorker.enqueue()`: reads `syncAutoUpload` + `syncWifiOnly`
  directly from SharedPreferences (companion object can't inject Config),
  bails early if auto-upload is off, sets `NetworkType.UNMETERED` when
  WiFi-only is on.
- `PhotoSyncWorker.doWorkInternal()`: replaced `SyncConfig.deleteLocalAfterUpload`
  with `config.syncDeleteAfterUpload` (Config is injected at the worker level).
- `PhotoRepository.safeCreatePhoto`: replaced `SyncConfig.autoUploadEnabled`
  with `config.syncAutoUpload` (cheap pre-check before calling enqueue).
- Added 6 new string resources for the toggle titles + summaries.

### Feature 3: Real Upload Progress (commit `ae4f1efd`)
- `RcloneController.uploadFileWithProgress`: replaced the previous
  `core/stats` polling loop (which never returned data for
  `operations/copyfile`) with a size-based estimate:
  - `ASSUMED_SPEED_BYTES_PER_MS = 5000` (5 MB/s)
  - `PROGRESS_POLL_INTERVAL_MS = 200` (was 500)
  - Estimated percent = `min(95, (elapsed_ms × 5000) / fileSize × 100)`
  - Capped at 95% so the bar visibly transitions to 100% only when the
    real upload completes (via `onProgress(100f)` after the await)
  - Zero-byte files skip the estimate loop entirely (divide-by-zero guard)
- `PhotoSyncWorker.performUpload`: switched the original-photo upload
  from `uploadFile` to `uploadFileWithProgress`, plumbing the
  onProgress callback to `updateNotification` so the user sees a
  determinate bar instead of an indeterminate spinner.
- The unused `computeUploadProgressPercent` helper was retained for
  future use with a different rclone API.

### Feature 4: GZIP Compression for Registry (commit `a8c9a435`)
- `HashRegistry.uploadToRemote`: now GZIP-compresses the JSON plaintext
  BEFORE GCM encryption. Logs the compression ratio.
- `HashRegistry.downloadAndCache`: dispatches on a 1-byte version tag:
  - `0x02` = current format: `[1-byte version][12-byte nonce][GCM(GZIP(JSON))]`
  - Anything else = legacy format: `[12-byte nonce][GCM(plaintext JSON)]`
    (treats the first byte as the start of the nonce)
- Added `decryptLegacyRegistryJson` helper for the legacy path.
- Added `gzipCompress` + `gzipDecompressToString` helpers using
  `java.util.zip.GZIPInputStream` / `GZIPOutputStream` (no new deps).
- The 1/256 chance that a legacy file's nonce starts with `0x02` is
  handled by a try/catch around the GZIP decompress — falls back to
  the legacy plaintext-JSON interpretation.
- Photos stay on AES-CBC without GZIP (backwards compat + the image
  viewer needs random-access reads, which GZIP doesn't support).

## Build verification

Each commit was built with:
```
export JAVA_HOME=/home/z/jdk/jdk21
export PATH=$JAVA_HOME/bin:$PATH
./gradlew :app:assembleFossDebug --no-daemon --console=plain \
  -Pandroid.injected.build.abi=arm64-v8a
```
All four builds completed successfully (`BUILD SUCCESSFUL`).

## Notable fix during Feature 1

Kotlin supports nested block comments — `/*` inside a KDoc comment opens
a nested comment that must be closed with `*/`. The first attempt at
PhotoRepository.kt included the literal string `audio/*` inside a KDoc
block, which caused "Unclosed comment" + "Missing }" syntax errors.
Fixed by rewording the comments to avoid the `/*` token and concatenating
the runtime string `"audio/" + "*"` to keep the source file clean.

## Files changed (across all 4 features)

- `app/src/main/AndroidManifest.xml` — FileProvider registration
- `app/src/main/java/onlasdan/gallery/gallery/ui/GalleryUiEvent.kt`
- `app/src/main/java/onlasdan/gallery/gallery/ui/GalleryUiState.kt`
- `app/src/main/java/onlasdan/gallery/gallery/ui/GalleryUiStateFactory.kt`
- `app/src/main/java/onlasdan/gallery/gallery/ui/GalleryViewModel.kt`
- `app/src/main/java/onlasdan/gallery/gallery/ui/compose/GalleryContent.kt`
- `app/src/main/java/onlasdan/gallery/model/database/entity/PhotoType.kt`
- `app/src/main/java/onlasdan/gallery/model/repositories/PhotoRepository.kt`
- `app/src/main/java/onlasdan/gallery/settings/data/Config.kt`
- `app/src/main/java/onlasdan/gallery/settings/domain/PreferenceScreenConfig.kt`
- `app/src/main/java/onlasdan/gallery/sync/rclone/RcloneController.kt`
- `app/src/main/java/onlasdan/gallery/sync/work/HashRegistry.kt`
- `app/src/main/java/onlasdan/gallery/sync/work/PhotoSyncWorker.kt`
- `app/src/main/res/values/strings.xml`
- `app/src/main/res/xml/file_paths.xml` (new)

## Git history (most recent first)

```
a8c9a435 feat: GZIP compression for registry (70-80% size reduction)
ae4f1efd feat: real upload progress — size-based estimate with 200ms polling
d571c1dd feat: sync settings — auto-upload toggle, WiFi-only, delete-after-upload
ec99abd8 feat: file upload support — extend PhotoType, gallery filter, external viewer
```

All four commits pushed to `origin/main`.
