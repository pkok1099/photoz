# v0x04 Chunked GCM Smoke Test Plan

> **Date:** 2026-07-13
> **For:** Maintainer self-execution on 1 physical device
> **Device requirements:** Android 12+, arm64, debug build
> **Estimated execution time:** 1-2 hours
> **Related audit:** `2026-07-13-v0x04-chunked-gcm-audit.md`

---

## 0. Test Environment

### 0.1 Device
- Android 12+ (API 31+) physical device, arm64-v8a
- Sufficient free storage: 2GB for fixtures
- Wi-Fi connection (for progressive-download tests in §4)
- USB cable for `adb`

### 0.2 Build & Install

```bash
cd /home/z/my-project/repos/photoz
./gradlew installDebug
adb shell am start -n onlasdan.gallery/onlasdan.gallery.MainActivity
```

### 0.3 adb Setup

```bash
adb devices                                    # confirm device authorized
adb logcat -c                                  # clear logcat before each test
adb logcat -s ExoPlayerImpl:I RcloneController:I RcloneDiag:I ChunkedGcmInputStream:I ChunkedGcmRandomAccessDataSource:I AndroidRuntime:E *:F
```

Keep this logcat running in a separate terminal throughout all tests.

---

## 1. Pre-Test Setup

### 1.1 Fixture Matrix

Prepare these sample videos on the device (push via `adb push`):

| Fixture | Size | Format | Purpose |
|---|---|---|---|
| `small.mp4` | 5 MB | MP4 H.264 | Fast import, baseline playback |
| `medium.mp4` | 100 MB | MP4 H.264 | Progressive download test |
| `large.mp4` | 500 MB | MP4 H.264 | Boundary stress, chunk-index overflow check |
| `sample.mov` | 50 MB | MOV | Codec diversity (QuickTime container) |
| `short.mp4` | 2 MB | MP4 H.264 | Last-chunk edge case (file < 1 chunk) |
| `faststarted.mp4` | 10 MB | MP4 (already faststarted) | F-007 false-positive warning check |
| `legacy_v02.video` | (any) | v0x02 encrypted | F-012 compat check (if available from old vault) |

Push:
```bash
adb push fixtures/small.mp4 /sdcard/Download/
adb push fixtures/medium.mp4 /sdcard/Download/
adb push fixtures/large.mp4 /sdcard/Download/
adb push fixtures/sample.mov /sdcard/Download/
adb push fixtures/short.mp4 /sdcard/Download/
adb push fixtures/faststarted.mp4 /sdcard/Download/
```

### 1.2 Fresh Vault

1. Clear app data: `adb shell pm clear onlasdan.gallery`
2. Launch app, create new vault with password `test1234`
3. Skip biometric setup (optional)
4. Navigate to Gallery (empty)

---

## 2. Import Smoke Tests

### TC-001: Import small.mp4 — verify v0x04 header byte

**Precondition:** Fresh vault, Gallery empty.
**Steps:**
1. Tap "+" → "Import from device"
2. Select `/sdcard/Download/small.mp4`
3. Wait for import to complete
**Expected:** Import succeeds, thumbnail appears in Gallery, no error toast.
**adb check:**
```bash
adb shell run-as onlasdan.gallery ls -la files/
adb shell run-as onlasdan.gallery dd if=files/<uuid>.crypt bs=1 count=1 2>/dev/null | xxd
```
First byte must be `04`.
**Pass criteria:** First byte == `0x04`, file size > 13 bytes (header + at least one chunk).

### TC-002: Import large.mp4 — verify QtFastStart ran (MOOV in front)

**Precondition:** TC-001 passed.
**Steps:**
1. Import `/sdcard/Download/large.mp4`
2. Wait for import (may take 30+ seconds for 500MB)
**Expected:** Import succeeds, no "FastStart failed" toast.
**adb check:**
```bash
adb logcat -d -s FastStartUseCase:I | grep -E "(faststart|MOOV)"
```
Log should show "FastStart: success" or "FastStart: not needed" — NOT "FastStart failed".
**Pass criteria:** No FastStart failure log; playback can seek before full download.

### TC-003: Import faststarted.mp4 — verify no false-positive warning (F-007)

**Precondition:** TC-001 passed.
**Steps:**
1. Import `/sdcard/Download/faststarted.mp4`
2. Watch for warning toast
**Expected:** NO warning toast. Video imports silently.
**adb check:**
```bash
adb logcat -d -s FastStartUseCase:I | grep "FastStart failed"
```
Must return empty.
**Pass criteria:** No "FastStart failed" log line, no toast shown.

### TC-004: Import short.mp4 (2MB, < 1 chunk) — verify last-chunk handling

**Precondition:** TC-001 passed.
**Steps:**
1. Import `/sdcard/Download/short.mp4`
2. Open the video in viewer
3. Tap play
**Expected:** Video plays to completion without error.
**adb check:**
```bash
adb logcat -d -s ChunkedGcmRandomAccessDataSource:I ExoPlayerImpl:I
```
No "truncated chunk" or "chunk too small" errors.
**Pass criteria:** Video plays end-to-end, no logcat errors.

### TC-005: Import sample.mov — verify codec diversity

**Precondition:** TC-001 passed.
**Steps:**
1. Import `/sdcard/Download/sample.mov`
2. Open in viewer, tap play
**Expected:** Video plays (MOV container, H.264 codec supported).
**Pass criteria:** Playback works, no error.

### TC-006: Import during low storage — verify F-001 fix (close() exception)

**Precondition:** Device storage filled to ~99% (push large junk files to /sdcard).
**Steps:**
1. Attempt to import `medium.mp4`
2. Watch for behavior
**Expected (before fix):** Import reports success, but file is silently truncated. (F-001 bug)
**Expected (after fix):** Import reports failure ("Insufficient storage" or similar), no partial file written.
**adb check:**
```bash
adb shell run-as onlasdan.gallery ls -la files/
adb logcat -d -s ChunkedGcmOutputStream:I PhotoRepository:I
```
**Pass criteria:** Failure is reported to UI; no silent truncation.

### TC-007: Import while app is killed mid-write — verify recovery

**Precondition:** TC-001 passed.
**Steps:**
1. Start importing `large.mp4`
2. During import (after ~50MB written), force-kill app: `adb shell am force-stop onlasdan.gallery`
3. Re-launch app, unlock vault
4. Check Gallery
**Expected:** Partial import is either cleaned up (no entry) or marked as failed. No silently-corrupt entry.
**Pass criteria:** No corrupt entry in Gallery; if entry exists, opening it shows error, not silent playback failure.

### TC-008: Import 5 videos rapidly — verify concurrent import

**Precondition:** TC-001 passed.
**Steps:**
1. Multi-select `small.mp4`, `short.mp4`, `sample.mov`, `faststarted.mp4`, and another `small.mp4`
2. Import all 5 at once
3. Wait for completion
**Expected:** All 5 import successfully, 5 thumbnails in Gallery.
**Pass criteria:** 5 valid v0x04 files on disk, all playable.

### TC-009: Verify v0x04 header structure

**Precondition:** TC-001 passed.
**Steps:**
```bash
adb shell run-as onlasdan.gallery dd if=files/<uuid>.crypt bs=1 count=13 2>/dev/null | xxd
```
**Expected:** First 13 bytes:
```
04                # version byte
00 10 00 00       # chunk_size = 1048576 (1MB, big-endian)
00 00 00 00 XX XX XX XX  # total_plaintext_size (8 bytes BE)
```
**Pass criteria:** Header matches v0x04 format spec.

### TC-010: Verify chunk structure (per-chunk nonce + ciphertext + tag)

**Precondition:** TC-001 passed.
**Steps:**
```bash
# Read bytes 13 to 13+28+16 (first chunk header + first 16 bytes of ciphertext)
adb shell run-as onlasdan.gallery dd if=files/<uuid>.crypt bs=1 skip=13 count=44 2>/dev/null | xxd
```
**Expected:** First 12 bytes after header = random nonce. Next 16 bytes = ciphertext start. Last 16 bytes of chunk = GCM tag (verify by reading chunk end).
**Pass criteria:** Nonce appears random (not all-zero, not all-same-byte).

---

## 3. Playback Smoke Tests (no network)

### TC-101: Play small.mp4 — instant playback

**Precondition:** TC-001 passed, video in Gallery.
**Steps:**
1. Tap video thumbnail
2. Tap play
**Expected:** Playback starts within 1 second.
**adb check:**
```bash
adb logcat -d -s ExoPlayerImpl:I | grep -E "(state|rendered)"
```
**Pass criteria:** First frame rendered < 2s, no buffering.

### TC-102: Seek to 50% — verify chunk dispatch

**Precondition:** TC-001 passed.
**Steps:**
1. Open video, tap play
2. After 2 seconds, tap seek bar at 50%
**Expected:** Seek completes, playback resumes from 50%.
**adb check:**
```bash
adb logcat -d -s ChunkedGcmRandomAccessDataSource:I
```
Log shows `loadChunk(chunkIndex=N)` where N > 0.
**Pass criteria:** No error, playback resumes.

### TC-103: Seek to 95% (last chunk) — verify <1MB handling (F-002 edge case)

**Precondition:** TC-001 passed.
**Steps:**
1. Open video, tap play
2. After 2 seconds, tap seek bar at 95%
**Expected:** Seek to last chunk, playback resumes.
**adb check:**
```bash
adb logcat -d -s ChunkedGcmRandomAccessDataSource:I ExoPlayerImpl:E
```
No negative-return errors, no `IllegalArgumentException` from `channel.position()`.
**Pass criteria:** No crash, no logcat error.

### TC-104: Seek past EOF — verify F-002 fix (negative return guard)

**Precondition:** TC-001 passed.
**Steps:**
1. Open video in viewer (do not tap play yet)
2. Via adb, simulate seek past end:
   ```bash
   adb shell input tap <seekbar_x_end> <seekbar_y>
   ```
   (Tap far right of seek bar, beyond 100%)
**Expected (before fix):** ExoPlayer may crash or hang. (F-002 bug)
**Expected (after fix):** Seek clamped to end, or graceful "cannot seek past end" error.
**adb check:**
```bash
adb logcat -d -s ExoPlayerImpl:E AndroidRuntime:E
```
No `IllegalArgumentException`, no uncaught exception.
**Pass criteria:** No crash; either seek clamped or error shown gracefully.

### TC-105: Pause + resume

**Precondition:** TC-101 passed.
**Steps:**
1. Play video
2. After 5 seconds, tap pause
3. Wait 10 seconds
4. Tap play
**Expected:** Playback resumes from paused position.
**Pass criteria:** No re-buffering, position preserved.

### TC-106: Background → foreground

**Precondition:** TC-101 passed.
**Steps:**
1. Play video
2. Press Home (app backgrounded)
3. Wait 30 seconds
4. Bring app to foreground (recent apps)
**Expected:** Playback paused on background, resumes on foreground (or stays paused — depends on app design).
**Pass criteria:** No crash, no silent corruption, state preserved.

### TC-107: Close viewer mid-playback — verify cleanup (F-004, F-008)

**Precondition:** TC-101 passed.
**Steps:**
1. Play video
2. After 3 seconds, swipe back / tap close
**Expected:** Viewer closes cleanly, no error toast, no crash.
**adb check:**
```bash
adb logcat -d -s ChunkedGcmRandomAccessDataSource:I ImageViewerViewModel:I AndroidRuntime:E
```
No `InterruptedException` unwrapped, no `CancellationException` swallowed warning, no crash.
**Pass criteria:** Clean close, no logcat errors.

### TC-108: Play short.mp4 (2MB) — verify single-chunk file

**Precondition:** TC-004 passed.
**Steps:**
1. Open `short.mp4`, tap play
2. Let it play to end
**Expected:** Plays to end, no error at end.
**adb check:**
```bash
adb logcat -d -s ChunkedGcmRandomAccessDataSource:I
```
Only 1 `loadChunk` call (chunkIndex=0).
**Pass criteria:** No "truncated chunk" error, clean EOF.

### TC-109: Rapid seek (stress test) — verify no race

**Precondition:** TC-101 passed.
**Steps:**
1. Open video, tap play
2. Tap seek bar rapidly 10 times at random positions within 5 seconds
**Expected:** ExoPlayer handles rapid seeks, settles on last seek position.
**adb check:**
```bash
adb logcat -d -s ExoPlayerImpl:E AndroidRuntime:E
```
**Pass criteria:** No crash, no ANR, final position matches last tap.

### TC-110: Verify GCM tag failure surfaces as error (F-009, F-010)

**Precondition:** TC-001 passed.
**Steps:**
1. Corrupt a video file on disk:
   ```bash
   adb shell run-as onlasdan.gallery sh -c 'dd if=/dev/urandom of=files/<uuid>.crypt bs=1 seek=20 count=16 conv=notrunc'
   ```
   (Overwrite 16 bytes of first chunk's ciphertext)
2. Open video, tap play
**Expected (after fix):** Error toast "Video corrupted" or playback fails with IOException.
**Expected (before fix):** May crash with `AEADBadTagException` (F-009) or silently truncate (F-010).
**adb check:**
```bash
adb logcat -d -s ChunkedGcmRandomAccessDataSource:I ExoPlayerImpl:E
```
**Pass criteria:** Graceful error, no uncaught exception, no silent truncation.

### TC-111: Play large.mp4 (500MB) — verify chunk-index math (F-002 Int overflow)

**Precondition:** TC-002 passed.
**Steps:**
1. Open `large.mp4`, tap play
2. Seek to 99%
**Expected:** Seek works, no crash.
**adb check:**
```bash
adb logcat -d -s ChunkedGcmRandomAccessDataSource:I AndroidRuntime:E
```
No `IllegalArgumentException` from `channel.position()`, no negative `chunkOffset`.
**Pass criteria:** Seek to 99% works on 500MB file.

### TC-112: Verify thumbnail generation for video

**Precondition:** TC-001 passed.
**Steps:**
1. After import, observe thumbnail in Gallery
**Expected:** Thumbnail shows first frame of video.
**adb check:**
```bash
adb shell run-as onlasdan.gallery ls -la files/<uuid>.crypt.tn
adb shell run-as onlasdan.gallery dd if=files/<uuid>.crypt.tn bs=1 count=1 2>/dev/null | xxd
```
First byte = `04` (v0x04 thumbnail).
**Pass criteria:** Thumbnail file exists, v0x04 header, displays correctly.

---

## 4. Progressive Download Smoke Tests

### TC-201: Start playback while import still running

**Precondition:** Vault empty.
**Steps:**
1. Start importing `medium.mp4` (100MB)
2. While import progress < 50%, navigate to Gallery
3. Tap the (still-importing) video thumbnail
**Expected:** Either (a) viewer opens but shows "Downloading…" spinner, or (b) viewer blocked until import completes. Either is acceptable; crash is not.
**adb check:**
```bash
adb logcat -d -s ChunkedGcmRandomAccessDataSource:I ImageViewerViewModel:I
```
**Pass criteria:** No crash, behavior matches design intent.

### TC-202: Network drop mid-download — verify timeout (F-016)

**Precondition:** Video partially downloaded (cloud sync scenario).
**Steps:**
1. Start playing a cloud-synced video (rclone backend)
2. After 5 seconds, disable Wi-Fi on device
3. Wait for timeout
**Expected:** After ~60-120s (post-fix) or 10min (pre-fix), error surfaces. No infinite spinner.
**adb check:**
```bash
adb logcat -d -s RcloneController:I ChunkedGcmRandomAccessDataSource:I
```
**Pass criteria:** Error surfaces within reasonable time, no infinite hang.

### TC-203: Seek to undownloaded region — verify blocking behavior

**Precondition:** TC-201 setup, video partially downloaded.
**Steps:**
1. While video is downloading (30% complete), seek to 90%
**Expected:** Playback blocks until chunk at 90% is downloaded, then resumes. No crash.
**adb check:**
```bash
adb logcat -d -s ChunkedGcmRandomAccessDataSource:I
```
Log shows `waitForBytesAvailable` blocking, then unblocking.
**Pass criteria:** No crash, eventually resumes.

### TC-204: Close viewer mid-download — verify resource cleanup (F-008)

**Precondition:** TC-201 setup.
**Steps:**
1. While video is downloading (50% complete), close viewer (swipe back)
2. Check logcat for cleanup
**Expected:** Download coroutine is cancelled, `videoStreamState` entry removed, no leaked coroutine.
**adb check:**
```bash
adb logcat -d -s ImageViewerViewModel:I
```
No "Failed" state emission after close (F-008 symptom).
**Pass criteria:** Clean cancellation, no spurious failure toast.

### TC-205: Re-open partially-downloaded video — verify F-005 fix

**Precondition:** TC-202 setup, partial file on disk after failed download.
**Steps:**
1. Re-open the video that failed to download in TC-202
2. Tap play
**Expected (before fix):** Video plays partial content then silently stops (F-005 bug). Re-open sees partial file, marks as "Done", never re-downloads.
**Expected (after fix):** Either (a) partial file deleted, re-download starts; or (b) video shows error "Download incomplete, retry?".
**adb check:**
```bash
adb shell run-as onlasdan.gallery ls -la files/<uuid>.crypt
adb logcat -d -s ImageViewerViewModel:I
```
**Pass criteria:** No silent truncation; partial state is recovered or re-attempted.

### TC-206: Slow network simulation — verify first-frame latency (F-006)

**Precondition:** `adb` network throttling available (or use Android emulator with network speed setting).
**Steps:**
1. Set network speed to 500kbps:
   ```bash
   adb shell settings put global captive_portal_mode 0  # if needed
   # Or use emulator: telnet localhost 5554 → "network speed 500"
   ```
2. Open cloud-synced `medium.mp4`, tap play
3. Measure time to first frame
**Expected:** First frame in ~16s (1MB chunk at 500kbps). Document this as known trade-off.
**adb check:**
```bash
adb logcat -d -s ExoPlayerImpl:I | grep "rendered"
```
**Pass criteria:** First frame appears; time-to-first-frame documented.

### TC-207: Download completes mid-playback — verify seamless transition

**Precondition:** Video downloading, playback started.
**Steps:**
1. Start playing downloading video
2. Let download complete while playing
3. Continue playing past download-completion point
**Expected:** No interruption, playback continues seamlessly.
**adb check:**
```bash
adb logcat -d -s ChunkedGcmRandomAccessDataSource:I
```
`waitForBytesAvailable` stops blocking after `downloadComplete=true`.
**Pass criteria:** No interruption at download-complete boundary.

### TC-208: Multiple videos downloading concurrently

**Precondition:** Multiple cloud-synced videos.
**Steps:**
1. Open video A, start playback
2. While A downloading, swipe back, open video B
3. While B downloading, swipe back, open video C
**Expected:** Each video downloads independently, no interference.
**adb check:**
```bash
adb logcat -d -s ImageViewerViewModel:I
```
**Pass criteria:** Each video plays; no cross-video state leak.

### TC-209: Download with corrupted first chunk — verify F-009 fix

**Precondition:** Cloud-synced video, first chunk corrupted (inject via rclone config or test fixture).
**Steps:**
1. Open corrupted video, tap play
**Expected (before fix):** Crash with `AEADBadTagException` (F-009).
**Expected (after fix):** Graceful error "Video corrupted, cannot play".
**adb check:**
```bash
adb logcat -d -s ChunkedGcmRandomAccessDataSource:I ExoPlayerImpl:E AndroidRuntime:E
```
**Pass criteria:** No uncaught exception, error UI shown.

### TC-210: Progressive download logcat visibility (F-013)

**Precondition:** TC-201 setup.
**Steps:**
1. While video is downloading and playback is blocking, observe logcat
**Expected (before fix):** No log output from `ChunkedGcmRandomAccessDataSource.waitForBytesAvailable`.
**Expected (after fix):** Periodic `RcloneDiag` log lines showing wait progress.
**adb check:**
```bash
adb logcat -d -s RcloneDiag:I | grep "VideoStream"
```
**Pass criteria:** Log lines present (after fix), or absence documented (before fix).

---

## 5. Backwards Compat Smoke Tests

### TC-301: Play v0x02 legacy video — verify CBC path still works

**Precondition:** Legacy v0x02 encrypted video in vault (from pre-v0x04 app version, or restored from old backup).
**Steps:**
1. Open v0x02 video, tap play
2. Seek to 50%
**Expected:** Playback works via `AesCbcRandomAccessDataSource`.
**adb check:**
```bash
adb shell run-as onlasdan/gallery dd if=files/<uuid>.crypt bs=1 count=1 2>/dev/null | xxd
# First byte must be 02, not 04
adb logcat -d -s AesCbcRandomAccessDataSource:I
```
**Pass criteria:** First byte = `0x02`, playback works, seek works.

### TC-302: Backup → restore v0x04 video — verify round-trip (F-017)

**Precondition:** TC-001 passed, v0x04 video in vault.
**Steps:**
1. Create backup (Settings → Backup)
2. Clear app data: `adb shell pm clear onlasdan.gallery`
3. Restore from backup
4. Open restored video, tap play
**Expected:** Video plays correctly. (F-017 notes file may be re-encrypted to local VMK, but playback works.)
**adb check:**
```bash
adb shell run-as onlasdan/gallery dd if=files/<uuid>.crypt bs=1 count=1 2>/dev/null | xxd
# First byte should still be 04 (re-encrypted with v0x04)
```
**Pass criteria:** Playback works, format is v0x04.

### TC-303: Mixed-vault — v0x02 + v0x04 videos

**Precondition:** Both v0x02 legacy video and v0x04 new video in vault.
**Steps:**
1. Open v0x02 video, play 5 seconds, close
2. Open v0x04 video, play 5 seconds, close
3. Open v0x02 video again
**Expected:** Both play correctly. No cached state leaks between versions.
**adb check:**
```bash
adb logcat -d -s VersionDispatchDataSource:I
```
Each open shows correct dispatch (`0x02 → CBC`, `0x04 → GCM`).
**Pass criteria:** Both versions play, no interference.

### TC-304: Open unsupported version byte (F-012)

**Precondition:** Manually craft a file with version byte `0x05`.
**Steps:**
```bash
adb shell run-as onlasdan.gallery sh -c 'printf "\x05\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00" > files/test_v05.crypt'
# Add a fake DB entry pointing to test_v05.crypt (or use an existing entry's UUID)
```
1. Open the file in viewer
**Expected (before fix):** `IllegalStateException` crash (F-012).
**Expected (after fix):** Graceful error "Unsupported encryption version".
**adb check:**
```bash
adb logcat -d -s AndroidRuntime:E VersionDispatchDataSource:I
```
**Pass criteria:** No uncaught exception, error UI shown.

### TC-305: Backup V5 with mixed v0x02 + v0x04 — restore both

**Precondition:** TC-303 setup (mixed vault).
**Steps:**
1. Create backup
2. Clear app data
3. Restore
4. Open both videos
**Expected:** Both videos play correctly after restore.
**Pass criteria:** Both v0x02 and v0x04 videos restored and playable.

---

## 6. Regression Matrix

Fill in during execution. One row per test case.

| TC ID | Result (pass/fail/block) | Notes |
|---|---|---|
| TC-001 | | |
| TC-002 | | |
| TC-003 | | |
| TC-004 | | |
| TC-005 | | |
| TC-006 | | |
| TC-007 | | |
| TC-008 | | |
| TC-009 | | |
| TC-010 | | |
| TC-101 | | |
| TC-102 | | |
| TC-103 | | |
| TC-104 | | |
| TC-105 | | |
| TC-106 | | |
| TC-107 | | |
| TC-108 | | |
| TC-109 | | |
| TC-110 | | |
| TC-111 | | |
| TC-112 | | |
| TC-201 | | |
| TC-202 | | |
| TC-203 | | |
| TC-204 | | |
| TC-205 | | |
| TC-206 | | |
| TC-207 | | |
| TC-208 | | |
| TC-209 | | |
| TC-210 | | |
| TC-301 | | |
| TC-302 | | |
| TC-303 | | |
| TC-304 | | |
| TC-305 | | |

---

## 7. Sign-Off

| Field | Value |
|---|---|
| Tester | ___________________________ |
| Date | ___________________________ |
| Device | ___________________________ |
| Build commit | ___________________________ |
| Total pass | _____ / 36 |
| Critical failures | _____ |
| Verdict | PASS / FAIL |

**Sign-off notes:**

_______________________________________________

_______________________________________________

---

## Appendix: Finding-to-Test-Case Traceability

| Finding | Severity | Test Cases |
|---|---|---|
| F-001 (close() swallows exceptions) | High | TC-006, TC-007 |
| F-002 (negative return past EOF) | High | TC-104, TC-111 |
| F-003 (loadNextChunk silent EOF) | Medium | TC-007, TC-110 |
| F-004 (InterruptedException) | Medium | TC-107, TC-204 |
| F-005 (failed download silent EOF) | Medium | TC-205 |
| F-006 (1MB first-chunk latency) | Medium | TC-206 |
| F-007 (FastStart false warning) | Medium | TC-003 |
| F-008 (CancellationException swallowed) | Medium | TC-204 |
| F-009 (unwrapped AEADBadTagException in open) | Medium | TC-110, TC-209 |
| F-010 (read() silent EOF) | Medium | TC-110 |
| F-011 (no AAD) | Medium | (no runtime test — code inspection only) |
| F-012 (unknown version byte crash) | Medium | TC-304 |
| F-013 (no wait logging) | Low | TC-210 |
| F-014 (monotonic invariant) | Low | (no runtime test — code inspection only) |
| F-015 (dataSpec.length ignored) | Low | (no runtime test — minor perf) |
| F-016 (10-min timeout UX) | Low | TC-202 |
| F-017 (silent format upgrade on restore) | Info | TC-302 |
