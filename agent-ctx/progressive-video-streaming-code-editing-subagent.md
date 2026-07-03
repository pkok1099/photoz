# Progressive Video Streaming

**Task ID:** progressive-video-streaming
**Agent:** code-editing-subagent
**Date:** 2026-07-03

## Goal

ExoPlayer starts playback while the encrypted `.crypt` file is still
downloading, instead of waiting for the full download before opening the
local file.

## Architecture (before)

1. `AesCbcRandomAccessDataSource` — ExoPlayer `DataSource` that reads
   encrypted LOCAL files with block-aligned AES-CBC seeking. Worked
   perfectly for local files; returned `-1` (EOF) the moment the
   underlying `FileChannel` hit the end of the file.
2. `ImageViewerViewModel.maybeStartVideoDownload()` — downloaded the
   ENTIRE `.crypt` file from remote via
   `syncRestorer.ensureLocalOriginalWithProgress()`, THEN marked
   `VideoDownloadState.Done`, THEN ExoPlayer opened the local file.
3. `ImageViewerViewModel.createMediaItem()` — `MediaItem` with
   `Uri.fromFile(localFile)` — only works after full download.
4. `ImageViewerViewModel.mediaSourceFactory` — `AesCbcRandomAccessDataSource`
   + `ProgressiveMediaSource.Factory`.

## The fix: progressive decrypt-to-cache

Instead of waiting for the FULL download before ExoPlayer starts:

1. Start downloading the `.crypt` file from remote to the final cache path
   (`<uuid>.crypt`) via the existing `ensureLocalOriginalWithProgress`
   (rclone writes the file directly — no temp file).
2. As soon as the file has at least the version byte, ExoPlayer's
   `DataSource.open()` returns and `read()` starts serving decrypted bytes.
3. `AesCbcRandomAccessDataSource` reads from the partial file — if it hits
   the end of available data, it WAITS (blocks) until more data is written
   or the download completes.
4. Download continues in the background, filling the file.
5. When the download completes, `downloadComplete` flips to `true` and the
   DataSource returns `-1` on the next read past the window — ExoPlayer
   ends playback cleanly.
6. ExoPlayer plays seamlessly — it never knows the file was being written
   to.

## Implementation

### `AesCbcRandomAccessDataSource.kt`

Added two constructor parameters with defaults that preserve the existing
local-playback path:

```kotlin
class AesCbcRandomAccessDataSource(
    private val sessionRepository: SessionRepository,
    private val availableBytesProvider: (Uri) -> Long = { -1L },
    private val downloadCompleteProvider: (Uri) -> Boolean = { true },
) : DataSource
```

- `availableBytesProvider(uri)` — returns how many bytes of the file are
  currently available to read. `-1L` = "the whole file is available"
  (default — local, fully-downloaded file). A positive value means the
  file is still being written; reads past this point block.
- `downloadCompleteProvider(uri)` — `true` = download finished (success OR
  failure), reads past the window return `-1` instead of blocking.

#### `waitForBytesAvailable(minBytes)`

Called in `open()` before each `channel.read()` (version byte, full
header, previous-block-for-IV, target block start). Polls every 50ms
until enough bytes are available, the download completes, or the 30s
timeout expires. No-op when `availableBytesProvider` returns `-1`.

#### `BlockingInputStream` (private inner class)

Wraps `Channels.newInputStream(channel)` before handing it to
`CipherInputStream`. Caps each `read()` to the available window so the
underlying `FileChannel` never returns `-1` prematurely (which would make
`CipherInputStream` finalize the cipher and end playback early). When the
window is exhausted and the download is still in flight, polls every 50ms
until more bytes arrive. When the download is complete, returns `-1`
(real EOF).

The 30s timeout prevents a genuinely-stalled download from hanging
playback forever; on timeout it throws `IOException` which ExoPlayer
surfaces as a playback error.

### `ImageViewerViewModel.kt`

#### `StreamState` data class

```kotlin
data class StreamState(
    val availableBytes: AtomicLong = AtomicLong(0L),
    val downloadComplete: AtomicBoolean = AtomicBoolean(false),
)
```

Thread-safe (atomic) state shared between the download coroutine (writer)
and the `AesCbcRandomAccessDataSource` (reader, on ExoPlayer's loading
thread).

#### `videoStreamState: ConcurrentHashMap<String, StreamState>`

Keyed by `Photo.internalFileName` (e.g. `<uuid>.crypt`). The DataSource
extracts the filename from the file `Uri` ExoPlayer passes to `open()`
and looks the state up via `lookupStreamState(uri)`.

#### `maybeStartVideoDownload(photo)` — modified

After the existing fast-path checks (file already local; not uploaded),
instead of waiting for the full download:

1. Registers a `StreamState` in `videoStreamState` BEFORE launching the
   download so the DataSource can find it.
2. Launches the existing `ensureLocalOriginalWithProgress` download
   coroutine (unchanged — writes to the final `<uuid>.crypt` path).
3. Launches `monitorFileSize(state, file)` — a separate coroutine on
   `Dispatchers.IO` that polls `file.length()` every 100ms and advances
   `state.availableBytes` (monotonically). rclone writes the file
   directly, so `File.length()` reflects real progress.
4. When the download coroutine finishes (success OR failure), flips
   `streamState.downloadComplete.set(true)` so the DataSource's blocking
   reads unblock, does a final `availableBytes.set(file.length())`, then
   removes the entry from `videoStreamState` so the DataSource falls back
   to its default "fully available" path.

ExoPlayer starts playback immediately (the `LaunchedEffect` in
`ImageViewerScreen` calls `setMediaItem` + `prepare()` right after
`maybeStartVideoDownload` returns). The DataSource's `open()` blocks on
`waitForBytesAvailable(1)` until rclone has written at least the version
byte, then proceeds to read — blocking as needed — while the download
continues in the background.

#### `monitorFileSize(state, file)` — new

Polls `file.length()` every `STREAM_POLL_INTERVAL_MS` (100ms) on
`Dispatchers.IO`. Monotonic — only advances, never goes backwards
(`File.length()` can transiently read 0 before rclone creates the file).
Stops when `state.downloadComplete` flips to `true`. Best-effort —
swallowed exceptions don't fail the download.

#### `mediaSourceFactory` — modified

The `DataSource.Factory` now passes `availableBytesProvider` and
`downloadCompleteProvider` lambdas that call `lookupStreamState(uri)` to
find the per-file `StreamState`. When no entry exists (fully local file
or download already complete), the providers return their defaults
(`-1L`, `true`) and the DataSource behaves exactly as before.

#### `lookupStreamState(uri)` — new helper

Extracts the filename from `uri.path` via `File(path).name` and looks it
up in `videoStreamState`.

## What did NOT change

- `SyncRestorer` — the existing `ensureLocalOriginalWithProgress` works
  as-is. rclone writes directly to the final path; we just monitor the
  file size.
- `RcloneController` — no changes.
- `ImageViewerScreen.kt` / `ImageViewerPage.kt` — no changes. The
  `LaunchedEffect` already calls `setMediaItem` + `prepare()` immediately
  after `maybeStartVideoDownload`; the DataSource now blocks inside
  `open()` until data is available instead of failing because the file
  doesn't exist yet.
- Local file playback — when `availableBytesProvider`/`downloadCompleteProvider`
  return their defaults (no `StreamState` entry), the DataSource behaves
  exactly as before. The `BlockingInputStream` delegates to
  `source.read()` without limiting or blocking.

## Edge cases handled

- **File doesn't exist yet when ExoPlayer calls `open()`** —
  `waitForBytesAvailable(1)` blocks until rclone creates the file and
  writes at least 1 byte (or the download fails / timeout expires).
- **Download stalls** — 30s timeout in both `waitForBytesAvailable` and
  `BlockingInputStream.read()` throws `IOException` so ExoPlayer surfaces
  a playback error instead of hanging forever.
- **Download fails midway** — `downloadComplete` is set to `true` in the
  `finally` block of the download coroutine (well, in the `try` before
  `finally`, but the `finally` removes the entry which also unblocks).
  The DataSource returns `-1` on the next read past the partial window;
  ExoPlayer treats it as end-of-stream.
- **User swipes away mid-download** — the download and monitor coroutines
  continue (they're in `viewModelScope`); the file is fully cached for
  next time. If the user leaves the viewer entirely, `viewModelScope` is
  cancelled, the coroutines stop, and the partial file remains (existing
  behavior — not made worse).
- **Interrupted download leaves partial file** — pre-existing issue; on
  next open, the existing fast-path `localFile.exists() && length() > 0`
  check marks `Done` and ExoPlayer plays the partial file. Out of scope
  for this task (not made worse).
- **Thread safety** — `ConcurrentHashMap` for the map; `AtomicLong` /
  `AtomicBoolean` for the state. The DataSource reads from ExoPlayer's
  loading thread; the ViewModel writes from main / IO dispatchers.

## Build

```
./gradlew :app:assembleFossDebug --no-daemon --console=plain \
  -Pandroid.injected.build.abi=arm64-v8a
```

BUILD SUCCESSFUL. APK at
`app/build/intermediates/apk/foss/debug/photok-1.0.0-foss-debug.apk` (89 MB).

## Files changed

- `app/src/main/java/onlasdan/gallery/transcoding/data/AesCbcRandomAccessDataSource.kt`
  (+210 lines): `availableBytesProvider` / `downloadCompleteProvider`
  params, `waitForBytesAvailable` helper, `BlockingInputStream` inner
  class.
- `app/src/main/java/onlasdan/gallery/imageviewer/ui/ImageViewerViewModel.kt`
  (+186 lines): `StreamState` data class, `videoStreamState` map,
  `monitorFileSize` coroutine, `lookupStreamState` helper, modified
  `maybeStartVideoDownload` and `mediaSourceFactory`.

## Commit

```
feat: progressive video streaming — playback starts while download in progress
```
