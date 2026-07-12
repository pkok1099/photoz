# v0x04 Chunked GCM Audit Report

> **Date:** 2026-07-13
> **Auditor:** Superpowers brainstorming → writing-plans → subagent-driven-development pipeline
> **Scope:** Video pipeline end-to-end (PhotoRepository.createPhotoFile → ExoPlayer render)
> **Methodology:** Angle-first — B (streaming) primary, A (crypto) / C (random-access) / D (compat) secondary
> **Pass criteria:** Zero Critical AND zero High findings (per spec §4.3)
> **Build commit hash:** `f71aa31dea2e`
> **Spec:** `docs/superpowers/specs/2026-07-13-v0x04-chunked-gcm-audit-design.md`
> **Plan:** `docs/plans/2026-07-13-v0x04-chunked-gcm-audit.md`

---

## 1. Executive Summary

**Verdict: PASS (post-fix re-verification)** — initial FAIL flipped to PASS after fix plan execution (7 commits). See §12.

The audit identified **17 findings** across the v0x04 chunked GCM pipeline. Severity distribution: **0 Critical, 2 High, 10 Medium, 4 Low, 1 Info**. Angle distribution: B=10, A=3, C=1, D=3.

The 2 High findings (F-001, F-002) trigger the fail gate per spec §4.3. Both have **effort S** (under 1 hour each) to fix. The audit's overall narrative: the v0x04 implementation is cryptographically sound (no Critical findings, GCM tags are verified correctly) but has two classes of correctness bugs — (1) exception-swallowing in close()/loadNextChunk() that masks storage failures as silent success, and (2) contract violations in open() (negative return past EOF, unwrapped AEADBadTagException, InterruptedException propagation) that can cause undefined ExoPlayer behavior on edge cases.

The Medium findings cluster around three themes: (a) silent-EOF anti-pattern in three read paths (F-003, F-005, F-010), (b) coroutine-cancellation hygiene (F-008), (c) UX regressions vs the legacy CBC path (F-006 first-frame latency, F-007 false-positive FastStart warning). The Low and Info findings are documentation gaps and minor invariant violations that do not block release.

Per spec §6.2 (FAIL branch), the next step is to invoke the `writing-plans` skill to produce an implementation plan covering the 2 High findings. Medium findings will be included in the same plan where the fix is in the same file (e.g. F-001 close() exception handling co-locates with F-003 loadNextChunk() exception handling). Low and Info findings become backlog items in the CSV (`status=open`).

---

## 2. Audit Scope

### 2.1 In Scope — Video Pipeline End-to-End

**Write path (import):**

| File | Role |
|---|---|
| `model/repositories/PhotoRepository.kt` | `createPhotoFile`, `useGcm=true`, 64KB buffer copy |
| `model/io/FastStartUseCase.kt` | QtFastStart pre-encrypt MOOV relocation |
| `io/VaultFileStorage.kt` | Chokepoint `openEncryptedOutput` / `openEncryptedInput` |
| `encryption/domain/crypto/HybridCryptoEngine.kt` | Version dispatch on read; v0x04 path on write |
| `encryption/domain/crypto/ChunkedGcmOutputStream.kt` | 1MB chunk writer, header patching on close() |
| `encryption/domain/models/Algorithm.kt` | `EncryptionVersionByte` enum, header sizes |
| `encryption/domain/crypto/Constants.kt` | `CHUNK_SIZE`, `GCM_IV_SIZE`, `GCM_TAG_SIZE`, `CHUNK_OVERHEAD` |

**Read path (ExoPlayer):**

| File | Role |
|---|---|
| `encryption/domain/crypto/ChunkedGcmInputStream.kt` | Sequential reader |
| `transcoding/data/VersionDispatchDataSourceFactory.kt` | Byte-0 dispatch |
| `transcoding/data/ChunkedGcmRandomAccessDataSource.kt` | O(1) chunk seek |
| `transcoding/data/AesCbcRandomAccessDataSource.kt` | Legacy CBC path (interop) |
| `imageviewer/ui/ImageViewerViewModel.kt` | `mediaSourceFactory`, `videoStreamState`, monitor coroutine |
| `imageviewer/ui/compose/ExoPlayerState.kt` + `ImageViewerPage.kt` | Compose render side |

**Thumbnails/previews (secondary):**

| File | Role |
|---|---|
| `model/io/CreateThumbnailsUseCase.kt` | Whole-file encrypt via `openEncryptedOutput` |
| `transcoding/data/EncryptedImageFetcher.kt` | Coil fetcher, whole-file decrypt to `ByteArray` |

### 2.2 Out of Scope

- Implementation of fixes (handled by `writing-plans` → `executing-plans` cycle)
- Running instrumented tests (audit environment has no Android SDK)
- Updating `ENCRYPTION.md` or `TODO1.md` (separate TODOs)
- Migrating v0x02 → v0x04 (separate TODO)
- SQLCipher audit, Argon2id audit (separate scopes)

---

## 3. Methodology

### 3.1 Approach — Angle-First

Audit was organized by threat angle rather than by file. Each angle scanned the relevant files for that angle's concerns. Order: B (primary) → A → C → D (secondary).

### 3.2 Severity Rubric

| Level | Definition |
|---|---|
| Critical | Data loss, security bypass, crash on common path, plaintext leak |
| High | Correctness bug on non-trivial path, very bad UX, potential data loss on edge case |
| Medium | Bad UX but recoverable, rare edge case, minor spec deviation |
| Low | Code smell, maintainability, stale documentation |
| Info | Observation without need for fix |

### 3.3 Pass Gate

- **PASS** — Zero Critical AND zero High
- **FAIL** — One or more Critical OR High

### 3.4 False-Positive Guard

Every finding has an Evidence field with a code excerpt or spec citation. Findings were cross-checked against `HybridCryptoEngine` KDoc and `ENCRYPTION.md` for spec/implementation gaps. Where a finding's status was ambiguous (bug vs design decision), severity was set conservatively (e.g. F-017 silent format upgrade on restore classified as Info because behavior is correct, only undocumented).

### 3.5 Execution

Two subagents were dispatched in sequence (subagent-driven-development pattern):

1. **Phase B subagent** — executed Tasks B1-B10 (streaming & progressive-download, primary). Output: 10 findings, scratch file `findings-scratch-phase-b.md`.
2. **Phase A+C+D subagent** — executed Tasks A1-A4, C1-C5, D1-D4 (secondary). Output: 7 findings, scratch file `findings-scratch-phase-acd.md`.

Both subagents logged to `/home/z/my-project/worklog.md`. The main agent consolidated the scratch files into the three deliverables, assigning stable IDs F-001 through F-017.

---

## 4. Findings — Angle B (Streaming & Progressive-Download, PRIMARY)

### F-001: ChunkedGcmOutputStream.close() swallows all exceptions, masking final-chunk write failures

- **Angle:** B
- **Severity:** **High**
- **File:** `encryption/domain/crypto/ChunkedGcmOutputStream.kt:123-141`
- **Description:** close() wraps writeHeaderIfNeeded(), flushChunk(), output.flush(), and patchTotalPlaintextSizeIfPossible() in a try/catch (e: Exception) { Timber.e(...) } that swallows every exception. The caller's use/lazyClose() sees no error, so PhotoRepository.createPhotoFile returns the plaintext byte count as if the write succeeded — but the on-disk file is truncated mid-final-chunk. The corruption is only detectable later on read (GCM tag verification or EOF mid-nonce).
- **Evidence:**

```kotlin
override fun close() {
    if (closed) return
    closed = true
    try {
        writeHeaderIfNeeded()
        flushChunk()
        output.flush()
        patchTotalPlaintextSizeIfPossible()
    } catch (e: Exception) {
        Timber.e(e, "ChunkedGcmOutputStream: close failed")   // swallowed
    } finally {
        output.close()
    }
}
```
Caller in PhotoRepository.createPhotoFile returns fileLen as success.

- **Impact:** Disk-full or storage-IO failure during the final chunk write produces a silently-corrupt encrypted file. The import UI reports success; the user discovers the corruption only when playback/export later throws AEADBadTagException or returns EOF. Potential data loss on edge case — the file is unrecoverable without re-import.
- **Recommendation:** Re-throw IOException (at minimum) from close(). Keep patchTotalPlaintextSizeIfPossible() failure non-fatal (it already has its own inner try/catch), but do not swallow exceptions from flushChunk() or output.flush(). Alternative: after close(), verify output channel size matches expected, and throw if mismatch.
- **Effort:** S

---

### F-002: ChunkedGcmRandomAccessDataSource.open() returns negative Long when dataSpec.position > totalPlaintextSize

- **Angle:** B
- **Severity:** **High**
- **File:** `transcoding/data/ChunkedGcmRandomAccessDataSource.kt:135-142`
- **Description:** open() computes return as totalPlaintextSize - plainOffset when totalPlaintextSize > 0. If dataSpec.position > totalPlaintextSize (seek past EOF), this returns a negative Long. ExoPlayer's DataSource.open contract requires non-negative byte count or C.LENGTH_UNBOUNDED (= Long.MAX_VALUE); negative is undefined behavior. Additionally, chunkIndex = (plainOffset / chunkSize).toInt() (line 116) silently truncates on huge plainOffset (Long → Int), which can produce a negative chunk index and an invalid channel.position(chunkOffset) call.
- **Evidence:**

```kotlin
return if (totalPlaintextSize > 0) {
    totalPlaintextSize - plainOffset        // negative if plainOffset > totalPlaintextSize
} else {
    java.lang.Long.MAX_VALUE
}
```
Chunk-index math:
```kotlin
val chunkIndex = (plainOffset / chunkSize).toInt()    // Long→Int truncation on huge seeks
```

- **Impact:** ExoPlayer behavior on negative open() return is undefined — may crash loading thread, treat as 0-byte EOF, or loop. Int truncation on huge seeks can produce negative chunkOffset, causing channel.position(negative) to throw IllegalArgumentException. Either way: playback failure with non-obvious root cause.
- **Recommendation:** Clamp: return (totalPlaintextSize - plainOffset).coerceAtLeast(0L). Add explicit guard: if plainOffset >= totalPlaintextSize && totalPlaintextSize > 0, throw IOException("seek past EOF"). Validate plainOffset fits in Int range before .toInt() cast.
- **Effort:** S

---

### F-003: ChunkedGcmInputStream.loadNextChunk swallows non-AEAD exceptions as silent EOF

- **Angle:** B
- **Severity:** **Medium**
- **File:** `encryption/domain/crypto/ChunkedGcmInputStream.kt:112-171`
- **Description:** loadNextChunk() catches AEADBadTagException and rethrows as IOException (correct). However, the subsequent generic catch (e: Exception) { Timber.e(...); return false } swallows every other exception — including IOException from a truncated read. return false is interpreted by read() as clean EOF, so the caller sees a normal end-of-stream instead of a corruption signal.
- **Evidence:**

```kotlin
} catch (e: AEADBadTagException) {
    throw IOException("...GCM auth tag verification failed...", e)
} catch (e: Exception) {
    Timber.e(e, "loadNextChunk failed")
    false        // swallowed; read() treats as clean EOF
}
```
read() caller:
```kotlin
if (!loadNextChunk()) { eof = true; return -1 }   // silent EOF
```

- **Impact:** A file truncated mid-chunk (write interrupted, disk-full, partial download) decrypts and returns data up to the truncation, then silently ends. Caller cannot distinguish 'clean EOF at chunk boundary' from 'corrupt truncation mid-chunk'. For sequential reads (thumbnails, exports), this means a truncated export/thumb is produced without error.
- **Recommendation:** Distinguish EOF (backing stream returns -1 at chunk boundary — clean) from short-read mid-chunk (returns -1 mid-nonce or mid-ciphertext — corrupt). Throw IOException("truncated chunk") for the latter. At minimum, rethrow IOException from the generic catch rather than swallowing.
- **Effort:** M

---

### F-004: ChunkedGcmRandomAccessDataSource.waitForBytesAvailable does not handle InterruptedException

- **Angle:** B
- **Severity:** **Medium**
- **File:** `transcoding/data/ChunkedGcmRandomAccessDataSource.kt:241-254`
- **Description:** waitForBytesAvailable() calls Thread.sleep(50) in a poll loop but does not catch InterruptedException. When ExoPlayer releases the player, it interrupts its loading thread. InterruptedException propagates raw through loadChunk → open()/read() → ExoPlayer. DataSource.open/read declare throws IOException; an undeclared InterruptedException violates the contract. The sibling AesCbcRandomAccessDataSource.waitForBytesAvailable (line 320-325) and BlockingInputStream (line 415-420) handle this correctly: Thread.currentThread().interrupt(); throw IOException("Interrupted...", e).
- **Evidence:**

```kotlin
private fun waitForBytesAvailable(minBytes: Long) {
    if (availableBytesProvider(uri) < 0) return
    val deadline = System.currentTimeMillis() + 600_000L
    while (true) {
        ...
        Thread.sleep(50)    // no try/catch for InterruptedException
    }
}
```
CBC path:
```kotlin
try { Thread.sleep(WAIT_POLL_INTERVAL_MS) }
catch (e: InterruptedException) {
    Thread.currentThread().interrupt()
    throw IOException("Interrupted while waiting for download", e)
}
```

- **Impact:** When user closes video viewer mid-download, ExoPlayer interrupts the loading thread. Raw InterruptedException escapes the DataSource call and is not wrapped as IOException. ExoPlayer's Loader may treat this as unhandled error rather than clean cancellation, potentially surfacing a crash or error toast on benign user action. Interrupt flag is also not re-set.
- **Recommendation:** Wrap Thread.sleep(50) in try/catch (e: InterruptedException) { Thread.currentThread().interrupt(); throw IOException("Interrupted while waiting for download", e) }, mirroring CBC path exactly.
- **Effort:** S

---

### F-005: videoStreamState entry removed after failed download, DataSource loses partial-availability tracking

- **Angle:** B
- **Severity:** **Medium**
- **File:** `imageviewer/ui/ImageViewerViewModel.kt:446-462`
- **Description:** Download coroutine's finally block removes videoStreamState entry unconditionally — for both success and failure. After removal, availableBytesProvider and downloadCompleteProvider fall back to defaults (-1L / true = 'fully available'). For a failed download with partial file on disk, this is a lie. Subsequent waitForBytesAvailable returns immediately, loadChunk reads past partial end, gets EOF, and read()'s generic catch returns -1 silently to ExoPlayer.
- **Evidence:**

```kotlin
} finally {
    videoStreamState.remove(photo.internalFileName)   // removed even on failure
    ...
}
```
Default providers:
```kotlin
availableBytesProvider = { uri -> lookupStreamState(uri)?.availableBytes?.get() ?: -1L }
downloadCompleteProvider = { uri -> lookupStreamState(uri)?.downloadComplete?.get() ?: true }
```

- **Impact:** Failed progressive download leaves partial encrypted file on disk. ExoPlayer reads until truncation and sees silent EOF — user observes video 'just stops' partway through. Worse: re-opening the video sees localFile.exists() && length() > 0 and marks it Done without re-downloading — partial file permanently stuck.
- **Recommendation:** On download failure, either (a) delete the partial file so next open re-downloads, or (b) keep videoStreamState entry with downloadComplete=true and availableBytes=partial size. Option (a) is simpler and matches 'failed import = no file' contract.
- **Effort:** M

---

### F-006: ChunkedGcmRandomAccessDataSource.open() pre-loads a full 1MB chunk before returning, delaying first frame on slow networks

- **Angle:** B
- **Severity:** **Medium**
- **File:** `transcoding/data/ChunkedGcmRandomAccessDataSource.kt:128-143`
- **Description:** open() calls loadChunk(chunkIndex, channel) before returning. loadChunk calls waitForBytesAvailable(chunkOffset + chunkEncSize), which blocks until the entire 1MB chunk (12 + 1,048,576 + 16 = 1,048,604 bytes for chunk 0) is on disk. ExoPlayer's open() therefore blocks until ~1MB has downloaded. The CBC path, by contrast, only waits for 32 bytes (target block + previous block for IV) before returning. For a 500 kbps connection, chunked-GCM playback starts after ~16 seconds (1MB), while CBC starts after ~0.5 seconds.
- **Evidence:**

GCM open() pre-loads full chunk:
```kotlin
loadChunk(chunkIndex, channel)
currentChunkPos = offsetInChunk
```
GCM loadChunk waits for full chunk:
```kotlin
val chunkEncSize = GCM_IV_SIZE + chunkSize + GCM_TAG_SIZE   // 1,048,604 bytes
waitForBytesAvailable(chunkOffset + chunkEncSize)           // blocks for 1MB
```
CBC open() waits for one block:
```kotlin
waitForBytesAvailable(minBytes = targetCipherOffset + 1L)
```

- **Impact:** UX regression for slow-network progressive playback of v0x04 videos vs v0x02. User sees 'Downloading video…' spinner for ~16× longer before first frame. Inherent to GCM (tag verification requires full ciphertext) — cannot be fully eliminated, but chunk size (1MB) is a tunable. Also: open() blocking for 1MB means ExoPlayer cannot read MOOV atom until 1MB arrives.
- **Recommendation:** Short-term: document the trade-off. Medium-term: smaller 'first chunk' size (e.g. 64KB) so MOOV can be read quickly, with subsequent chunks at 1MB. Long-term: streaming GCM with partial-block decryption (loses tag-per-chunk simplicity).
- **Effort:** L

---

### F-007: FastStartUseCase mis-handles 'MOOV already at front' — false-positive warning toast

- **Angle:** B
- **Severity:** **Medium**
- **File:** `model/io/FastStartUseCase.kt:86-143`
- **Description:** The catch (QtFastStart.UnsupportedFileException) block assumes that exception is thrown when 'MOOV is already at front'. This is incorrect. QtFastStart.fastStartImpl returns false (not throws) when the last atom is not MOOV — exactly the 'MOOV already at front' case. When fastStart returns false, the library deletes the output file. FastStartUseCase then checks tempOutput.exists() && length() > 0, which is false, and shows a false-positive warning toast. The user sees a scary warning for a video that is already optimized.
- **Evidence:**

FastStartUseCase assumption:
```kotlin
} catch (e: QtFastStart.UnsupportedFileException) {
    // This is thrown when MOOV is already at front OR format is unsupported.
```
QtFastStart.java actual behavior:
```java
if (atomType != MOOV_ATOM) {
    printf("last atom in file was not a moov atom");
    return false;     // returns, does NOT throw
}
```
False-positive path:
```kotlin
if (tempOutput.exists() && tempOutput.length() > 0) { ... }
else {
    showWarning("⚠ '$name': FastStart failed — video will need full download before playback")
    null
}
```

- **Impact:** Every already-faststarted MP4 (modern device videos, ffmpeg -movflags +faststart) triggers misleading 'FastStart failed' toast on import. Users may believe their video is broken. Erodes trust in import flow. QtFastStart's Boolean return value is ignored.
- **Recommendation:** Capture and check Boolean return from QtFastStart.fastStart(). If false, treat as 'no faststart needed' (log + return null without warning). Only show warning when fastStart throws or returns true but output is empty. Remove stale comment.
- **Effort:** S

---

### F-008: Download coroutine's runCatching swallows CancellationException, breaking structured concurrency

- **Angle:** B
- **Severity:** **Medium**
- **File:** `imageviewer/ui/ImageViewerViewModel.kt:406-463`
- **Description:** Download coroutine wraps syncRestorer.ensureLocalOriginalWithProgress(...) in runCatching { ... }. runCatching is a plain try/catch (Throwable) — it catches CancellationException, which is the coroutine-cancellation signal. When the ViewModel is cleared (viewModelScope cancelled) mid-download, the CancellationException is caught by runCatching instead of propagating. The coroutine continues to execute code after runCatching (set downloadComplete, set availableBytes, update videoDownloadsFlow to Failed), and the finally block. Parent coroutine machinery sees normal completion rather than cancellation — structured concurrency is broken.
- **Evidence:**

```kotlin
viewModelScope.launch {
    try {
        val result = runCatching {
            syncRestorer.ensureLocalOriginalWithProgress(uuid) { ... }
        }
        // runCatching catches CancellationException
        streamState.downloadComplete.set(true)
        if (result.isSuccess) { ... } else { ... }   // CancellationException → 'Failed'
    } finally {
        videoStreamState.remove(photo.internalFileName)
    }
}
```

- **Impact:** On viewer close mid-download, coroutine does extra work (state updates, videoDownloadsFlow emission to Failed) after being cancelled — wasted CPU and spurious 'Failed' UI flash. CancellationException not re-thrown, so coroutine's Job transitions to Completed instead of Cancelled. Practical symptom: transient 'Download failed' toast/state on close.
- **Recommendation:** Replace runCatching with explicit try/catch that re-throws CancellationException:
```kotlin
val result = try {
    Result.success(syncRestorer.ensureLocalOriginalWithProgress(uuid) { ... })
} catch (e: CancellationException) { throw e }
  catch (e: Throwable) { Result.failure(e) }
```
- **Effort:** S

---

### F-013: ChunkedGcmRandomAccessDataSource.waitForBytesAvailable has no progress logging, unlike CBC path

- **Angle:** B
- **Severity:** **Low**
- **File:** `transcoding/data/ChunkedGcmRandomAccessDataSource.kt:241-254`
- **Description:** CBC waitForBytesAvailable and BlockingInputStream emit Log.i('RcloneDiag', '[VideoStream] ...') lines every 2 seconds while waiting, and on each successful unblock. Chunked-GCM waitForBytesAvailable is completely silent. When chunked-GCM video stalls mid-download, logcat shows nothing from GCM path — operator cannot tell whether DataSource is blocked, how many bytes it needs, or how many are available.
- **Evidence:**

CBC:
```kotlin
Log.i("RcloneDiag", "[VideoStream] waitForBytesAvailable: need=$minBytes available=${availableBytesProvider(uri)}")
```
GCM (ChunkedGcmRandomAccessDataSource.kt:241-254): no Log.* calls at all.

- **Impact:** Diagnosing progressive-download playback issues for v0x04 videos is significantly harder than for v0x02. Field reports of 'video won't play' for new-format videos will lack polling trail that exists for old-format videos.
- **Recommendation:** Add same RcloneDiag log lines as CBC path (entry, periodic 2s progress, unblock, timeout). Effort trivial; pay-off large for support tickets.
- **Effort:** S

---

### F-014: monitorFileSize final sync uses set() not max(), breaking documented monotonic invariant

- **Angle:** B
- **Severity:** **Low**
- **File:** `imageviewer/ui/ImageViewerViewModel.kt:478-501`
- **Description:** Monitor coroutine maintains monotonic availableBytes watermark inside loop: state.availableBytes.updateAndGet { old -> if (len > old) len else old }, with explicit comment that File.length() can transiently read 0 and we 'never want to regress the watermark'. However, final sync after loop (line 495) and download coroutine's final sync (line 438) both call state.availableBytes.set(file.length()) directly — overwriting monotonic max with whatever File.length() returns at that instant. If file was truncated or deleted between last poll and final sync, availableBytes regresses.
- **Evidence:**

Monotonic inside loop:
```kotlin
state.availableBytes.updateAndGet { old -> if (len > old) len else old }
```
Non-monotonic final sync:
```kotlin
state.availableBytes.set(file.length())   // overwrites, no max()
```

- **Impact:** In practice benign because downloadComplete=true is set before final sync, so waitForBytesAvailable short-circuits on downloadCompleteProvider check and never observes regressed availableBytes. But invariant is broken, and future refactor that reorders downloadComplete.set(true) / availableBytes.set(...) could surface real bug. Defensive code should preserve invariant.
- **Recommendation:** Replace both final set(file.length()) calls with updateAndGet { old -> maxOf(old, file.length()) }. Effort trivial.
- **Effort:** S

---

## 5. Findings — Angle A (Cryptographic)

### F-009: ChunkedGcmRandomAccessDataSource.open() does not wrap AEADBadTagException from loadChunk() as IOException — ExoPlayer contract violation

- **Angle:** A
- **Severity:** **Medium**
- **File:** `transcoding/data/ChunkedGcmRandomAccessDataSource.kt:129, 192`
- **Description:** loadChunk() calls cipher.doFinal(actualCipher) at line 192 with NO local try/catch. When loadChunk() is invoked from open() (line 129), the call is inside try { ... } finally { channel.close() } — but finally only closes the channel; there is no catch. If the first chunk's GCM auth tag verification fails, AEADBadTagException (a GeneralSecurityException, NOT an IOException) propagates raw through open() to ExoPlayer. DataSource.open() declares throws IOException; undeclared GeneralSecurityException violates contract. The sibling call site in read() (line 211-213) handles this correctly, so only open() path is broken.
- **Evidence:**

open() call site:
```kotlin
loadChunk(chunkIndex, channel)
currentChunkPos = offsetInChunk
} finally { channel.close() }
```
loadChunk() doFinal — no try/catch:
```kotlin
currentChunkData = cipher.doFinal(actualCipher)   // unwrapped; throws AEADBadTagException
```

- **Impact:** If first chunk of v0x04 file is corrupted (bit-rot, storage error, wrong VMK after password change race), playback crashes with unhandled GeneralSecurityException instead of graceful 'video corrupted' message. ExoPlayer's error handler expects IOException subclasses; unexpected RuntimeException-family may propagate to global crash handler in release builds.
- **Recommendation:** Wrap loadChunk call in open() with same try/catch (e: AEADBadTagException) { throw IOException("GCM tag verification failed on first chunk", e) } as read() does. Better: move wrapping inside loadChunk itself so both call sites are protected.
- **Effort:** S

---

### F-010: ChunkedGcmRandomAccessDataSource.read() swallows non-AEAD exceptions as silent EOF — video silently truncates on corrupted mid-stream chunks

- **Angle:** A
- **Severity:** **Medium**
- **File:** `transcoding/data/ChunkedGcmRandomAccessDataSource.kt:211-216`
- **Description:** read() has generic catch (e: Exception) { return -1 } after the AEADBadTagException handler. loadChunk() throws IOException('truncated nonce') and IOException('chunk too small') on truncated reads (partial download, file truncated mid-chunk). Both are caught by generic Exception handler and converted to clean EOF (return -1). User observes video 'just stops' partway through with no error indication. Same anti-pattern as F-003, but in random-access video-playback path.
- **Evidence:**

```kotlin
} catch (e: AEADBadTagException) {
    throw IOException("...GCM auth tag verification failed...", e)
} catch (e: Exception) {
    return -1   // truncated nonce / chunk too small / any IOException → silent EOF
}
```
loadChunk throws:
```kotlin
if (read < GCM_IV_SIZE) throw IOException("truncated nonce")
if (read < GCM_IV_SIZE + GCM_TAG_SIZE) throw IOException("chunk too small")
```

- **Impact:** Silently truncates video on corrupted mid-stream chunks. User cannot distinguish 'video ended normally' from 'video corrupted mid-stream'. For vault storing irreplaceable videos, silent corruption is data-integrity footgun — user may not realize video is damaged until they try to export.
- **Recommendation:** Distinguish EOF at chunk boundary (clean — ExoPlayer requests next chunk) from IOException mid-chunk (corrupt — rethrow). Replace generic catch with explicit handling: catch (e: IOException) { if (e.message?.contains("truncated") == true) throw e else return -1 }. At minimum, log swallowed exception at WARN level.
- **Effort:** M

---

### F-011: No AAD (Additional Authenticated Data) bound to GCM — file header is not integrity-protected

- **Angle:** A
- **Severity:** **Medium**
- **File:** `encryption/domain/crypto/ChunkedGcmOutputStream.kt:109`
- **Description:** All three GCM cipher.init(...) call sites construct GCMParameterSpec(GCM_TAG_SIZE * 8, nonce) with ONLY tag length and IV — no cipher.updateAAD(...) call anywhere. The 13-byte file header ([version=0x04][chunk_size(4)][total_plaintext_size(8)]) is NOT integrity-protected by GCM auth tag. An attacker (or buggy writer, or bit-rot in first 13 bytes) can modify totalPlaintextSize or chunkSize WITHOUT failing GCM tag verification on any chunk. Reader trusts header values for: (a) open() return value, (b) ExoPlayer's reported duration, (c) chunkEncSize computation in loadChunk.
- **Evidence:**

Encrypt (ChunkedGcmOutputStream.kt:107-110):
```kotlin
val cipher = Cipher.getInstance(Algorithm.AesGcmNoPadding.value).apply {
    init(Cipher.ENCRYPT_MODE, vmk, GCMParameterSpec(GCM_TAG_SIZE * 8, nonce))
}
// no cipher.updateAAD(headerBytes) — header is not bound to the tag
```
Same pattern at ChunkedGcmInputStream.kt:153 and ChunkedGcmRandomAccessDataSource.kt:190.

- **Impact:** Attacker with file-write access can shrink totalPlaintextSize to silently truncate video, or grow it to cause seek-past-EOF errors — without failing GCM tag verification on any chunk. chunkSize tampering causes loadChunk to compute wrong chunkEncSize, leading to misaligned reads. Threat model assumes attacker with file-write access can already delete files, so not a critical confidentiality breach — but a data-integrity gap that AAD would close for free.
- **Recommendation:** Bind file header as AAD on every GCM operation. In OutputStream: after writing header, call cipher.updateAAD(headerBytes) before doFinal. In InputStream and RA DataSource: read header first, then cipher.updateAAD(headerBytes) before doFinal. Note: backwards-incompatible format change — either bump version byte to 0x05 (preferred) or accept v0x04 files remain un-AAD'd and only new writes get AAD (mixed-version dispatch).
- **Effort:** L

---

## 6. Findings — Angle C (Random-Access Seek)

### F-015: ChunkedGcmRandomAccessDataSource.open() returns totalPlaintextSize - plainOffset instead of dataSpec.length, inconsistent with CBC path

- **Angle:** C
- **Severity:** **Low**
- **File:** `transcoding/data/ChunkedGcmRandomAccessDataSource.kt:135-142`
- **Description:** open() returns totalPlaintextSize - plainOffset (remaining bytes from seek position to end of plaintext) when totalPlaintextSize > 0. If dataSpec.length is also set (ExoPlayer requests specific byte range), returned value does NOT honor dataSpec.length — always returns full remaining size. CBC path has same behavior, so consistent with legacy, but both paths ignore dataSpec.length. ExoPlayer docs: 'If length is unknown, return C.LENGTH_UNBOUNDED. Otherwise, return length of data that can be read from current position' — strictly interpreted, should be min(remaining, dataSpec.length).
- **Evidence:**

GCM open() return:
```kotlin
return if (totalPlaintextSize > 0) {
    totalPlaintextSize - plainOffset        // ignores dataSpec.length
} else {
    java.lang.Long.MAX_VALUE
}
```
dataSpec.length is never read in this file.

- **Impact:** ExoPlayer may read more bytes than it needs when issuing range request (e.g. metadata extraction), wasting I/O. In practice, ProgressiveMediaSource rarely uses bounded dataSpec.length, so impact is minor. But it is contract deviation that could surface in edge cases.
- **Recommendation:** Honor dataSpec.length: return if (dataSpec.length != C.LENGTH_UNBOUNDED) dataSpec.length else (totalPlaintextSize - plainOffset).coerceAtLeast(0L). Or document explicitly that this DataSource ignores bounded ranges.
- **Effort:** S

---

## 7. Findings — Angle D (Backwards Compat)

### F-012: Unknown version byte (0x05+) dispatched to CBC path throws IllegalStateException, not IOException — ExoPlayer contract violation

- **Angle:** D
- **Severity:** **Medium**
- **File:** `transcoding/data/VersionDispatchDataSourceFactory.kt:87-109`
- **Description:** VersionDispatchDataSource.open() reads first byte and dispatches via when (firstByte[0]). The else branch (catches 0x03 and any future 0x05+) instantiates AesCbcRandomAccessDataSource. AesCbcRandomAccessDataSource.open() then checks version and throws IllegalStateException('version-X must be handled by ChunkedGcmRandomAccessDataSource') for v0x03, and similar for unknown bytes. IllegalStateException is a RuntimeException, NOT IOException — violates DataSource.open/read contract that declares throws IOException.
- **Evidence:**

VersionDispatchDataSource.open() dispatch:
```kotlin
delegate = when (firstByte[0]) {
    0x04.toByte() -> ChunkedGcmRandomAccessDataSource(...)
    else          -> AesCbcRandomAccessDataSource(...)   // 0x03, 0x05+ all go here
}
```
AesCbcRandomAccessDataSource throws on v0x03:
```kotlin
if (version != EncryptionVersionByte.One && version != EncryptionVersionByte.Two) {
    throw IllegalStateException("$version must be handled by ChunkedGcmRandomAccessDataSource")
}
```

- **Impact:** If a future v0x05+ file is opened (partial format upgrade, backup restore from newer app version), playback crashes with IllegalStateException instead of graceful 'unsupported format' message. For v0x03 files mistakenly played as video (shouldn't happen per design, but defensive coding matters), same crash. Release builds may see this surface to Play Console crash reports as unhandled RuntimeException.
- **Recommendation:** In AesCbcRandomAccessDataSource.open(), throw IOException('unsupported encryption version: X') instead of IllegalStateException. Better: in VersionDispatchDataSource.open(), add explicit else branch that throws IOException('unknown version byte: X') before instantiating any DataSource — fail fast at dispatch layer.
- **Effort:** S

---

### F-016: v0x02 CBC path's 10-minute wait timeout has no progress UI and no user-extendable retry — bad UX on slow/stalled networks

- **Angle:** D
- **Severity:** **Low**
- **File:** `transcoding/data/AesCbcRandomAccessDataSource.kt:285-330`
- **Description:** Both waitForBytesAvailable implementations (CBC and GCM) use 10-minute (600_000ms) hard timeout. If download stalls (network drop, rclone backend hang), user sees spinner for 10 minutes before error surfaces. No progress UI beyond spinner, no 'still waiting, retry?' prompt, no way for user to extend or cancel wait short of killing viewer. 10-minute window is hardcoded — no Config entry to tune it.
- **Evidence:**

```kotlin
val deadline = System.currentTimeMillis() + 600_000L
while (true) {
    if (System.currentTimeMillis() > deadline) {
        throw IOException("Timeout waiting for download")
    }
    Thread.sleep(50)
}
```
No Config entry for WAIT_TIMEOUT_MS. No UI callback to surface 'still waiting' state.

- **Impact:** On stalled network, user waits 10 minutes with only spinner before learning video won't play. Cannot cancel early without killing app. Bad UX, especially for users on metered/intermittent connections (target audience for encrypted photo vault).
- **Recommendation:** Short-term: shorten timeout to 60-120s and surface 'Download stalled, retry?' dialog. Medium-term: add Config entry for WAIT_TIMEOUT_MS, surface progress in UI, let user extend or cancel. Long-term: integrate with WorkManager for resumable background downloads.
- **Effort:** M

---

### F-017: Backup V5 ZIP stores opaque encrypted blobs, but restore path decrypts + re-encrypts with local VMK — silent format upgrade to v0x04

- **Angle:** D
- **Severity:** **Info**
- **File:** `backup/restore/RestoreService.kt:(multiple)`
- **Description:** Backup V5 ZIP stores encrypted files as opaque blobs (no transformation of version byte or chunk header). However, restore path does NOT write file byte-for-byte to vault — it decrypts via source VMK (from backup's wrappedVmk) and re-encrypts via local VMK (restoring device's vault key). This means: (a) v0x02 backup restored on v0x04-capable app becomes v0x04 file on disk (silent upgrade), (b) file size may change (CBC padding differs from GCM tag overhead), (c) chunk layout is fresh (different nonces). This is deliberate design choice (local VMK must wrap all files) and is correct, but undocumented in ENCRYPTION.md and may surprise users who expect 'restore = exact file copy'.
- **Evidence:**

Restore flow:
```kotlin
val sourceInputStream = backupCryptoEngine.createDecryptStream(zipEntryInputStream, sourceVaultSession)
val localOutputStream = vaultFileStorage.openEncryptedOutput(targetFileName, useGcm = true)
// bytes decrypted from source, re-encrypted to local
```
ENCRYPTION.md §Backup Format V5 does not mention re-encryption step.

- **Impact:** None functional — re-encryption is correct. Documentation gap only. User inspecting vault after restore will see all v0x04 files even if backup was v0x02, which may confuse forensic analysis or future migration efforts.
- **Recommendation:** Document re-encryption step in ENCRYPTION.md §Backup Format V5: 'Restored files are re-encrypted with local vault's VMK; format version may differ from backup'. No code change needed.
- **Effort:** S

---

## 8. Cross-Angle Findings

Three clusters span multiple angles:

1. **`ChunkedGcmRandomAccessDataSource.open()` return-and-exception path** — F-002 (B, High: negative return past EOF), F-009 (A, Medium: unwrapped AEADBadTagException), F-012 (D, Medium: unknown version byte throws IllegalStateException), F-015 (C, Low: ignores dataSpec.length). All four live in `open()` and its immediate callees. A single fix pass over `open()` should address all four — clamp return, wrap AEADBadTagException as IOException, validate version byte at dispatch layer, honor dataSpec.length.

2. **Silent-EOF anti-pattern in read paths** — F-003 (B, Medium: `ChunkedGcmInputStream.loadNextChunk` swallows non-AEAD exceptions), F-005 (B, Medium: failed download removes `videoStreamState` entry → silent EOF), F-010 (A, Medium: `ChunkedGcmRandomAccessDataSource.read()` swallows non-AEAD exceptions). All three convert corruption signals to clean EOF. A shared helper `distinguishEOFfromCorruption()` would prevent further drift.

3. **Progressive-download polling divergence** — Three independent wait-loop implementations exist: `AesCbcRandomAccessDataSource.waitForBytesAvailable` (50ms/10min, with interrupt handling + logging), `ChunkedGcmRandomAccessDataSource.waitForBytesAvailable` (50ms/10min, NO interrupt handling, NO logging), `VersionDispatchDataSource.open()` inline file-existence poll (100ms/10s, NO interrupt handling). Findings F-004 (B, Medium: missing InterruptedException handling) and F-013 (B, Low: missing progress logging) are symptoms; the root cause is code duplication. Unifying into a single `ProgressiveDownloadWaiter` helper would prevent further drift.

---

## 9. Recommendations Summary

| ID | Severity | Angle | Effort | Recommended Action |
|---|---|---|---|---|
| F-001 | High | B | S | Re-throw IOException (at minimum) from close(). Keep patchTotalPlaintextSizeIfPossible() failure non-fatal (it already h... |
| F-002 | High | B | S | Clamp: return (totalPlaintextSize - plainOffset).coerceAtLeast(0L). Add explicit guard: if plainOffset >= totalPlaintext... |
| F-003 | Medium | B | M | Distinguish EOF (backing stream returns -1 at chunk boundary — clean) from short-read mid-chunk (returns -1 mid-nonce or... |
| F-004 | Medium | B | S | Wrap Thread.sleep(50) in try/catch (e: InterruptedException) { Thread.currentThread().interrupt(); throw IOException("In... |
| F-005 | Medium | B | M | On download failure, either (a) delete the partial file so next open re-downloads, or (b) keep videoStreamState entry wi... |
| F-006 | Medium | B | L | Short-term: document the trade-off. Medium-term: smaller 'first chunk' size (e.g. 64KB) so MOOV can be read quickly, wit... |
| F-007 | Medium | B | S | Capture and check Boolean return from QtFastStart.fastStart(). If false, treat as 'no faststart needed' (log + return nu... |
| F-008 | Medium | B | S | Replace runCatching with explicit try/catch that re-throws CancellationException:
```kotlin
val result = try {
    Resul... |
| F-009 | Medium | A | S | Wrap loadChunk call in open() with same try/catch (e: AEADBadTagException) { throw IOException("GCM tag verification fai... |
| F-010 | Medium | A | M | Distinguish EOF at chunk boundary (clean — ExoPlayer requests next chunk) from IOException mid-chunk (corrupt — rethrow)... |
| F-011 | Medium | A | L | Bind file header as AAD on every GCM operation. In OutputStream: after writing header, call cipher.updateAAD(headerBytes... |
| F-012 | Medium | D | S | In AesCbcRandomAccessDataSource.open(), throw IOException('unsupported encryption version: X') instead of IllegalStateEx... |
| F-013 | Low | B | S | Add same RcloneDiag log lines as CBC path (entry, periodic 2s progress, unblock, timeout). Effort trivial; pay-off large... |
| F-014 | Low | B | S | Replace both final set(file.length()) calls with updateAndGet { old -> maxOf(old, file.length()) }. Effort trivial. |
| F-015 | Low | C | S | Honor dataSpec.length: return if (dataSpec.length != C.LENGTH_UNBOUNDED) dataSpec.length else (totalPlaintextSize - plai... |
| F-016 | Low | D | M | Short-term: shorten timeout to 60-120s and surface 'Download stalled, retry?' dialog. Medium-term: add Config entry for ... |
| F-017 | Info | D | S | Document re-encryption step in ENCRYPTION.md §Backup Format V5: 'Restored files are re-encrypted with local vault's VMK;... |


**Fix priority for FAIL branch:**
- F-001 (High, effort S) — `ChunkedGcmOutputStream.close()` exception handling
- F-002 (High, effort S) — `ChunkedGcmRandomAccessDataSource.open()` clamp + guard

**Include in same fix cycle (co-located files):**
- F-003 (Medium, M) — `ChunkedGcmInputStream.loadNextChunk` exception handling
- F-004 (Medium, S) — `waitForBytesAvailable` InterruptedException handling
- F-009 (Medium, S) — `open()` wrap AEADBadTagException
- F-010 (Medium, M) — `read()` exception handling
- F-012 (Medium, S) — `VersionDispatchDataSource` version byte validation
- F-015 (Low, S) — `open()` honor dataSpec.length

**Backlog (separate cycles):**
- F-005, F-006, F-007, F-008, F-011, F-013, F-014, F-016, F-017

---

## 10. Verdict

**FAIL (initial audit)** → **PASS (post-fix re-verification)**

Initial verdict was FAIL due to 2 High findings (F-001, F-002). After executing the fix plan at `docs/plans/2026-07-13-v0x04-fixes.md` (7 commits: `3b0c4371`, `4a986952`, `c6e17d5c`, `637bf879`, `8028c3a7`, `f78125db`, `c7f9865d`), both High findings are resolved plus 6 co-located Medium/Low findings. See §12 for the full re-verification details.

The remaining 9 findings (F-005, F-006, F-007, F-008, F-011, F-013, F-014, F-016, F-017) are non-blocking backlog — Medium/Low/Info severity, no Critical or High remaining. Smoke test plan at `2026-07-13-v0x04-smoke-test-plan.md` should be executed on a physical device to confirm runtime behavior.

---



---

## 12. Post-Fix Re-Verification

After executing the fix plan at `docs/plans/2026-07-13-v0x04-fixes.md` (7 commits, 8 findings fixed):

**Fixed (8 findings):**

| ID | Severity | File | Commit |
|---|---|---|---|
| F-001 | High | `ChunkedGcmOutputStream.kt` close() | `3b0c4371` |
| F-002 | High | `ChunkedGcmRandomAccessDataSource.kt` open() | `4a986952` |
| F-003 | Medium | `ChunkedGcmInputStream.kt` loadNextChunk() | `8028c3a7` |
| F-004 | Medium | `ChunkedGcmRandomAccessDataSource.kt` waitForBytesAvailable() | `c6e17d5c` |
| F-009 | Medium | `ChunkedGcmRandomAccessDataSource.kt` open() AEAD wrap | `4a986952` |
| F-010 | Medium | `ChunkedGcmRandomAccessDataSource.kt` read() + loadChunk() | `637bf879` |
| F-012 | Medium | `VersionDispatchDataSourceFactory.kt` open() | `f78125db` |
| F-015 | Low | `ChunkedGcmRandomAccessDataSource.kt` open() dataSpec.length | `c7f9865d` |

**Remaining open (9 findings — backlog, non-blocking):**

| ID | Severity | Reason for backlog |
|---|---|---|
| F-005 | Medium | Requires `ImageViewerViewModel` refactor — separate cycle |
| F-006 | Medium | Design decision + format change (smaller first-chunk) — separate cycle |
| F-007 | Medium | `FastStartUseCase` rewrite to check Boolean return — separate cycle |
| F-008 | Medium | Coroutine hygiene (`runCatching` → explicit try/catch) — separate cycle |
| F-011 | Medium | Backwards-incompatible format change (v0x05 with AAD) — separate cycle |
| F-013 | Low | Diagnostic logging improvement — separate cycle |
| F-014 | Low | Defensive code (monotonic invariant) — separate cycle |
| F-016 | Low | UX redesign (10-min timeout → 60-120s + retry dialog) — separate cycle |
| F-017 | Info | Documentation gap only (`ENCRYPTION.md` §Backup V5) — separate cycle |

**Re-verification verdict:** **PASS**

- Critical: 0 (was 0) ✓
- High: 0 (was 2 — F-001, F-002 both fixed) ✓
- Medium: 6 (was 10 — 4 fixed: F-003, F-009, F-010, F-012) — backlog, non-blocking
- Low: 3 (was 4 — 1 fixed: F-015) — backlog, non-blocking
- Info: 1 (was 1) — backlog, non-blocking

Gate per spec §4.3 (zero Critical AND zero High): **SATISFIED**.

**Verification caveat:** Fixes were committed via static code analysis and TDD test authoring. The audit environment has no Android SDK — `./gradlew :app:testDebugUnitTest` could not be run. Test execution is deferred to:
1. CI (GitHub Actions on PR)
2. Physical device (smoke test plan at `2026-07-13-v0x04-smoke-test-plan.md`)

Most important smoke test cases to verify the fixes:
- TC-006, TC-007 — F-001 (close() exception propagation)
- TC-104, TC-111 — F-002 (negative return past EOF, Int overflow)
- TC-110 — F-009 + F-010 (AEAD wrap, silent EOF on corruption)
- TC-304 — F-012 (unknown version byte → IOException)
- TC-107, TC-204 — F-004 (InterruptedException wrapping)

## 11. Appendix

### 11.1 Spec Citations

- **ENCRYPTION.md** — §"Version 3.x.x Encryption (Current)" documents v0x02 (CBC + IV). Does NOT document v0x03 (GCM whole-file) or v0x04 (chunked GCM). Document is stale.
- **RFC 5116** — "An Interface and Algorithms for Authenticated Encryption" — defines GCM IV size (12 bytes recommended), tag size (16 bytes recommended), and AAD semantics. F-011 (no AAD) cites §2.2.
- **ExoPlayer DataSource contract** — `androidx.media3.datasource.DataSource.open(DataSpec)` declares `throws IOException`. Returns `Long`: bytes remaining, or `C.LENGTH_UNBOUNDED` (= `Long.MAX_VALUE`) for unknown length. Must not return negative. F-002, F-009, F-012 cite this contract.
- **Kotlin coroutines structured concurrency** — `runCatching` is a plain `try/catch (Throwable)` and catches `CancellationException`. The `kotlinx.coroutines` documentation explicitly warns against this pattern. F-008 cites this.

### 11.2 File Inventory

14 files audited across Phase B and Phase A+C+D subagents. Full inventory in scratch files `findings-scratch-phase-b.md` and `findings-scratch-phase-acd.md`.

### 11.3 Build Commit Hash

`f71aa31dea2e` (HEAD at audit execution time). All file:line references are against this commit.

### 11.4 Scratch Files

- `docs/superpowers/specs/findings-scratch-phase-b.md` — Phase B subagent raw output (10 findings)
- `docs/superpowers/specs/findings-scratch-phase-acd.md` — Phase A+C+D subagent raw output (7 findings)

These scratch files are kept for traceability. The authoritative findings list is in `2026-07-13-v0x04-findings.csv` (CSV) and this document (narrative).

### 11.5 Worklog

Multi-agent worklog at `/home/z/my-project/worklog.md` contains entries for:
- Task ID PHASE-B (Phase B audit subagent)
- Task ID PHASE-ACD (Phase A+C+D audit subagent)
