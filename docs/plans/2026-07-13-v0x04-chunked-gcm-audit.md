# v0x04 Chunked GCM Audit Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Audit the v0x04 chunked GCM encryption pipeline end-to-end (write path → ExoPlayer render), produce findings CSV + audit report + smoke test plan, decide PASS/FAIL against the zero-Critical-zero-High gate.

**Architecture:** Angle-first audit. Walk a fixed checklist of 23 items grouped by threat angle (B streaming primary, A crypto / C random-access / D compat secondary). Every checklist item produces 0-2 findings with mandatory evidence. Aggregate findings into 3 deliverables. If PASS, hand off smoke test plan. If FAIL, transition to a separate writing-plans cycle for fix.

**Tech Stack:** Kotlin / Android / AES-GCM / ExoPlayer / Room / Hilt / Coil. No build is run during audit (env has no Android SDK); verification is static code analysis + spec cross-check.

**Spec:** `docs/superpowers/specs/2026-07-13-v0x04-chunked-gcm-audit-design.md` (approved)

---

## Pre-Flight

### Task 0: Read spec + ground truth files

**Files:**
- Read: `docs/superpowers/specs/2026-07-13-v0x04-chunked-gcm-audit-design.md`
- Read: `ENCRYPTION.md` (stale spec — used as baseline reference only)
- Read: `encryption/domain/crypto/Constants.kt` (chunk size, GCM IV/tag sizes)
- Read: `encryption/domain/models/Algorithm.kt` (version byte enum)

**Step 1:** Read all four files in full.

**Step 2:** Capture into scratch notes:
- `CHUNK_SIZE`, `GCM_IV_SIZE`, `GCM_TAG_SIZE`, `CHUNK_OVERHEAD` values
- All `EncryptionVersionByte` enum entries + their `headerSize`
- Stale-vs-actual gaps in `ENCRYPTION.md`

**Step 3:** No commit — these are scratch notes only.

---

## Phase B — Streaming & Progressive-Download (PRIMARY)

### Task B1: Audit `ChunkedGcmOutputStream.close()` header patching

**Files:**
- Read: `encryption/domain/crypto/ChunkedGcmOutputStream.kt` (full)
- Reference: `model/repositories/PhotoRepository.kt#createPhotoFile` (to see what backing stream is passed)

**Step 1:** Read `ChunkedGcmOutputStream.kt` end-to-end. Note the `patchTotalPlaintextSizeIfPossible` logic.

**Step 2:** Identify the type of backing `OutputStream` passed by `openEncryptedOutput`. If it's always a `FileOutputStream` (seekable), patch works. If it could be a non-seekable stream (e.g. network/pipe), what happens?

**Step 3:** Cross-check with `VaultFileStorage.openEncryptedOutput` to confirm backing stream type.

**Step 4:** If a finding exists, draft it using the standard format from spec §4.1.

**Step 5:** Append finding (if any) to `docs/superpowers/specs/findings-scratch.md` (scratch file, not committed yet).

### Task B2: Audit `ChunkedGcmInputStream` blocking behavior

**Files:**
- Read: `encryption/domain/crypto/ChunkedGcmInputStream.kt` (full)

**Step 1:** Read end-to-end.

**Step 2:** Trace: what happens if backing `InputStream` returns -1 mid-chunk (truncated file)? Is `IOException` propagated or swallowed?

**Step 3:** Check: does the reader verify the GCM auth tag at chunk boundaries? Where exactly? What exception class?

**Step 4:** Draft findings, append to scratch file.

### Task B3: Audit `ChunkedGcmRandomAccessDataSource.open()` contract

**Files:**
- Read: `transcoding/data/ChunkedGcmRandomAccessDataSource.kt` (full)

**Step 1:** Read end-to-end.

**Step 2:** For each case, document the return value of `open(dataSpec)`:
- (a) File complete on disk
- (b) File mid-download (`availableBytes < fileSize`)
- (c) Empty file (size 0)
- (d) `dataSpec.position >= totalSize`

**Step 3:** Cross-check return values against ExoPlayer's `DataSource.open` contract (returns `Long`: bytes remaining, or `C.LENGTH_UNBOUNDED` = `Long.MAX_VALUE`).

**Step 4:** Draft findings, append to scratch file.

### Task B4: Audit `waitForBytesAvailable` polling

**Files:**
- Read: `transcoding/data/ChunkedGcmRandomAccessDataSource.kt` (focus on waiting logic)
- Read: `transcoding/data/AesCbcRandomAccessDataSource.kt` (focus on `BlockingInputStream`)

**Step 1:** Identify the polling/timeout mechanism in `ChunkedGcmRandomAccessDataSource`. Does it exist? What interval? What timeout?

**Step 2:** Compare with `AesCbcRandomAccessDataSource`'s `BlockingInputStream` (50ms poll, 10-min timeout per spec).

**Step 3:** If asymmetric, draft finding about behavioral inconsistency.

**Step 4:** Append findings to scratch file.

### Task B5: Audit `videoStreamState` ConcurrentHashMap race

**Files:**
- Read: `imageviewer/ui/ImageViewerViewModel.kt` (full)

**Step 1:** Locate `videoStreamState: ConcurrentHashMap<String, StreamState>`. Find all `put`, `remove`, `get` sites.

**Step 2:** Trace: who puts entries? When? Who removes? Is there a leak path if the viewer closes before download completes?

**Step 3:** Trace: is `downloadComplete=true` set BEFORE or AFTER `ExoPlayer.setMediaItem` / `prepare`? Race if set after.

**Step 4:** Trace: rapid open/close (user scrolls gallery in <100ms). Is the previous entry removed? Does the monitor coroutine get cancelled?

**Step 5:** Draft findings, append to scratch file.

### Task B6: Audit monitor coroutine polling `File.length()`

**Files:**
- Read: `imageviewer/ui/ImageViewerViewModel.kt` (focus on monitor coroutine, `launch`/`poll`)

**Step 1:** Identify the coroutine that polls `File.length()` every ~100ms. Document its lifecycle.

**Step 2:** Edge case: `File.length()` returns a value smaller than the previous call (filesystem race or file truncation). Is the new value accepted, ignored, or does it crash?

**Step 3:** Edge case: file size doesn't grow for >X seconds (stalled download). Is there backoff or does it spin forever?

**Step 4:** Cancellation: is the coroutine cancelled when the viewer closes? On what scope?

**Step 5:** Draft findings, append to scratch file.

### Task B7: Audit `availableBytesProvider` / `downloadCompleteProvider` semantics

**Files:**
- Read: `transcoding/data/VersionDispatchDataSourceFactory.kt` (full)
- Read: `imageviewer/ui/ImageViewerViewModel.kt` (focus on lambda wiring)

**Step 1:** Document the exact lambdas passed to `VersionDispatchDataSourceFactory`.

**Step 2:** What does `lookupStreamState(uri)?.availableBytes?.get() ?: -1L` mean to ExoPlayer? Is `-1L` `C.LENGTH_UNBOUNDED` (= "all bytes available") or "unknown"?

**Step 3:** Who registers the entry first? `setMediaItem` happens before or after the entry is in the map?

**Step 4:** Draft findings, append to scratch file.

### Task B8: Audit ExoPlayer `ProgressiveMediaSource` contract compliance

**Files:**
- Read: `imageviewer/ui/ImageViewerViewModel.kt` (focus on `mediaSourceFactory`)
- Reference: ExoPlayer docs for `ProgressiveMediaSource.Factory` and `DataSource.open` contract

**Step 1:** Document how `ProgressiveMediaSource.Factory(factory)` is constructed.

**Step 2:** ExoPlayer contract: `DataSource.open` must block until bytes are available OR return immediately with `C.LENGTH_UNBOUNDED`. Which does our impl do?

**Step 3:** If our impl returns immediately without bytes available, ExoPlayer will fail fast — is that the intent?

**Step 4:** Draft findings, append to scratch file.

### Task B9: Audit QtFastStart + chunked GCM interaction

**Files:**
- Read: `model/io/FastStartUseCase.kt` (full)
- Read: `model/repositories/PhotoRepository.kt#createPhotoFile` (QtFastStart invocation point)

**Step 1:** Confirm QtFastStart runs on plaintext BEFORE encryption.

**Step 2:** What happens if QtFastStart determines MOOV is already at the front? Does it skip processing (no-op), or rewrite the file anyway?

**Step 3:** Edge case: video with no MOOV atom (rare, but possible for some MKV remuxed to MP4). What happens?

**Step 4:** Draft findings, append to scratch file.

### Task B10: Audit cancellation & resource cleanup

**Files:**
- Read: `imageviewer/ui/ImageViewerViewModel.kt` (focus on `onCleared`, `release`)
- Read: `transcoding/data/ChunkedGcmRandomAccessDataSource.kt` (focus on `close`)

**Step 1:** Trace viewer close path: `ExoPlayer.release()` → `DataSource.close()` → underlying stream close.

**Step 2:** If `BlockingInputStream` is mid-poll, does `close()` interrupt it? Or does it spin until timeout?

**Step 3:** Is the monitor coroutine cancelled via `viewModelScope` cancellation? Or does it have its own scope that leaks?

**Step 4:** Draft findings, append to scratch file.

---

## Phase A — Cryptographic (SECONDARY)

### Task A1: Audit nonce uniqueness

**Files:**
- Read: `encryption/domain/crypto/ChunkedGcmOutputStream.kt` (focus on `flushChunk`)

**Step 1:** Document the exact nonce generation: `SecureRandom.nextBytes(12)` per chunk.

**Step 2:** Compute birthday-paradox collision probability for: (a) typical 100MB video (100 chunks), (b) extreme 100GB video (100k chunks).

**Step 3:** Is the same `SecureRandom` instance reused across chunks/files, or fresh per chunk? Does it matter?

**Step 4:** Draft finding (likely Info unless probability is concerning).

### Task A2: Audit GCM tag verification on decrypt

**Files:**
- Read: `encryption/domain/crypto/ChunkedGcmInputStream.kt` (focus on `doFinal` calls)
- Read: `transcoding/data/ChunkedGcmRandomAccessDataSource.kt` (focus on `doFinal` calls)

**Step 1:** Locate every `cipher.doFinal(...)` call in both files.

**Step 2:** For each: is the call wrapped in `try/catch`? What exception types are caught? Is `AEADBadTagException` (subclass of `GeneralSecurityException`) caught and swallowed, or propagated?

**Step 3:** **CRITICAL:** If `AEADBadTagException` is swallowed anywhere → finding F-XX is Critical (silent corruption).

**Step 4:** Draft findings, append to scratch file.

### Task A3: Audit AAD usage

**Files:**
- Read: `encryption/domain/crypto/ChunkedGcmOutputStream.kt` (focus on `cipher.init`)
- Read: `encryption/domain/crypto/ChunkedGcmInputStream.kt` (focus on `cipher.init`)

**Step 1:** Confirm: is `GCMParameterSpec` constructed with AAD, or only tLen + IV?

**Step 2:** If no AAD: an attacker who can rewrite the file header (e.g. change `totalPlaintextSize` to lie about file size) can potentially confuse the reader. Severity?

**Step 3:** Draft finding (likely Medium — AAD is best practice but not strictly required for confidentiality).

### Task A4: Audit VMK zeroing

**Files:**
- Read: `encryption/domain/crypto/ChunkedGcmOutputStream.kt` (focus on `close`)
- Read: `encryption/domain/crypto/ChunkedGcmInputStream.kt` (focus on `close`)
- Reference: `TODO1.md` #7 (marked DONE)

**Step 1:** After `close()`, is the `vmk` ByteArray explicitly zeroed (`.fill(0)`)?

**Step 2:** If not — is it because VMK is owned by the session and zeroed elsewhere (e.g. on vault lock)? Trace ownership.

**Step 3:** Draft finding (likely Info if VMK lifecycle is handled by session).

---

## Phase C — Random-Access Seek (SECONDARY)

### Task C1: Audit chunk boundary seek

**Files:**
- Read: `transcoding/data/ChunkedGcmRandomAccessDataSource.kt` (focus on chunk index math)

**Step 1:** Document `chunkIndex = position / chunkSize` math.

**Step 2:** Trace for `position == 0` (first chunk, offset 0).
**Step 3:** Trace for `position == chunkSize` (boundary — should map to chunk 1, offset 0).
**Step 4:** Trace for `position == totalSize - 1` (last byte of last chunk).
**Step 5:** Draft findings (if any boundary is off-by-one).

### Task C2: Audit last chunk < 1MB handling

**Files:**
- Read: `transcoding/data/ChunkedGcmRandomAccessDataSource.kt`
- Read: `encryption/domain/crypto/ChunkedGcmInputStream.kt`

**Step 1:** How does the reader know the size of the last chunk? Two options: (a) read `totalPlaintextSize` from header and compute, (b) read until EOF + GCM tag tells us where ciphertext ends.

**Step 2:** If (a): is `totalPlaintextSize` reliable (B1 audit covers header patching)?
**Step 3:** If (b): how does it handle the GCM tag — is the last 16 bytes always assumed to be the tag?
**Step 4:** Draft findings.

### Task C3: Audit out-of-range seek

**Files:**
- Read: `transcoding/data/ChunkedGcmRandomAccessDataSource.kt`

**Step 1:** What happens when `dataSpec.position >= totalPlaintextSize`? Return -1? Throw `EOFException`? Return 0 bytes?

**Step 2:** ExoPlayer expects either an exception or `C.LENGTH_UNBOUNDED`. Verify behavior matches contract.

**Step 3:** Draft findings.

### Task C4: Audit seek-during-download

**Files:**
- Read: `transcoding/data/ChunkedGcmRandomAccessDataSource.kt`
- Read: `transcoding/data/AesCbcRandomAccessDataSource.kt` (for comparison)

**Step 1:** User seeks to position 90% while download is at 30%. Does the read block until bytes available, or fail fast?

**Step 2:** If blocks: timeout? Cancellable?

**Step 3:** Compare with CBC path behavior. Inconsistency = finding.

**Step 4:** Draft findings.

### Task C5: Audit `dataSpec.length` honoring

**Files:**
- Read: `transcoding/data/ChunkedGcmRandomAccessDataSource.kt`

**Step 1:** Does the reader honor `dataSpec.length` (request a specific byte range), or always return the entire rest of the chunk?

**Step 2:** ExoPlayer sometimes requests small ranges for metadata extraction. If we always return full chunk, performance impact?

**Step 3:** Draft finding (likely Low — perf, not correctness).

---

## Phase D — Backwards Compat (SECONDARY)

### Task D1: Audit `VersionDispatchDataSource.open()` dispatch table

**Files:**
- Read: `transcoding/data/VersionDispatchDataSourceFactory.kt` + `VersionDispatchDataSource.kt` (full)

**Step 1:** Document the exact `when (firstByte[0])` branches.

**Step 2:** Trace `0x03.toByte()` path — does it fall into `else` (CBC) and then throw inside `AesCbcRandomAccessDataSource`?

**Step 3:** Is this intentional (v0x03 is for non-video files, so should never reach video DataSource) or a bug?

**Step 4:** Draft finding (likely Info if intentional, Medium if accidental).

### Task D2: Audit v0x02 video playback

**Files:**
- Read: `transcoding/data/AesCbcRandomAccessDataSource.kt`

**Step 1:** Confirm v0x02 path still works for progressive streaming.

**Step 2:** Check: can the 10-minute timeout trigger on legitimate slow networks? Is it user-cancellable?

**Step 3:** Draft finding (likely Medium — UX issue for slow networks).

### Task D3: Audit backup V5 ZIP round-trip

**Files:**
- Read: `backup/` folder (search for V5 backup code)
- Reference: `ENCRYPTION.md` §"Backup Format V5"

**Step 1:** Confirm: backup ZIP stores files as opaque blobs — no transformation of version byte or chunk header.

**Step 2:** Restore path: file is written byte-for-byte to vault. Verify no transform.

**Step 3:** Draft finding (likely Info — pass-through by design).

### Task D4: Audit mixed-vault scenario

**Files:**
- Read: `transcoding/data/VersionDispatchDataSourceFactory.kt`

**Step 1:** Vault contains both v0x02 and v0x04 videos. User scrolls gallery and opens each in turn.

**Step 2:** Each `open()` re-reads version byte and dispatches fresh. Confirm no cached state leaks between opens.

**Step 3:** Draft finding (likely Info).

---

## Phase Write-Up

### Task W1: Consolidate scratch findings into `findings.csv`

**Files:**
- Create: `docs/superpowers/specs/2026-07-13-v0x04-findings.csv`

**Step 1:** Read `findings-scratch.md`.

**Step 2:** Deduplicate, sort by severity (Critical → High → Medium → Low → Info), then by angle (B → A → C → D).

**Step 3:** Assign stable IDs `F-001`, `F-002`, ... in that order.

**Step 4:** Write CSV with header:
```csv
id,title,angle,severity,file,line,description,evidence,impact,recommendation,effort,status
```

**Step 5:** Set `status=open` for all rows.

**Step 6:** Verify CSV row count == findings count in scratch file.

### Task W2: Write `audit.md`

**Files:**
- Create: `docs/superpowers/specs/2026-07-13-v0x04-chunked-gcm-audit.md`

**Step 1:** Write frontmatter (date, auditor, scope ref, methodology ref, pass criteria ref).

**Step 2:** Write Executive Summary: count findings per severity + per angle. Compute verdict (PASS if 0 Critical + 0 High, else FAIL). One-paragraph narrative.

**Step 3:** Write §2 Audit Scope — reference spec §2.

**Step 4:** Write §3 Methodology — reference spec §3 + §4.

**Step 5:** Write §4 Findings — Angle B. For each F-NNN with angle=B, write full block (Severity, File:line, Description, Evidence, Impact, Recommendation, Effort).

**Step 6:** Write §5 Findings — Angle A. Same format.

**Step 7:** Write §6 Findings — Angle C. Same format.

**Step 8:** Write §7 Findings — Angle D. Same format.

**Step 9:** Write §8 Cross-Angle Findings — findings that span multiple angles (e.g. B5 + B6 + B10 all about `videoStreamState` lifecycle).

**Step 10:** Write §9 Recommendations Summary — markdown table: ID | Severity | Effort | Recommended Action | Owner.

**Step 11:** Write §10 Verdict — PASS or FAIL, one-paragraph justification, branch note (if FAIL: "will invoke writing-plans for fix").

**Step 12:** Write §11 Appendix — spec citations (ENCRYPTION.md sections, RFC 5116 for GCM, ExoPlayer DataSource contract), file inventory (list of files audited with brief role), build commit hash (`git rev-parse HEAD`).

**Step 13:** Verify word count is in 3000-4500 range per spec §5.2.

### Task W3: Write `smoke-test-plan.md`

**Files:**
- Create: `docs/superpowers/specs/2026-07-13-v0x04-smoke-test-plan.md`

**Step 1:** Write §0 Test Environment — device requirements (Android 12+, arm64, debug build), adb setup commands.

**Step 2:** Write §1 Pre-Test Setup — build & install steps, fixture matrix (small MP4 5MB, medium MP4 100MB, large MP4 500MB, MOV 50MB, short 2MB), fresh vault init.

**Step 3:** Write §2 Import Smoke Tests — 8-10 test cases (TC-001..TC-010). Each test case in standard format (Precondition, Steps, Expected, adb check, Pass criteria).

**Step 4:** Write §3 Playback Smoke Tests — no network — 10-12 test cases (TC-101..TC-112). Cover: instant playback, seek 50%, seek 95% (last chunk), seek out-of-range, pause/resume, background/foreground, etc.

**Step 5:** Write §4 Progressive Download Smoke Tests — 8-10 test cases (TC-201..TC-210). Cover: start playback mid-import, network drop mid-download, seek to undownloaded region, close viewer mid-download, etc.

**Step 6:** Write §5 Backwards Compat Smoke Tests — 3-5 test cases (TC-301..TC-305). Cover: play v0x02 video (if available), backup→restore v0x04, mixed-vault scroll.

**Step 7:** Write §6 Regression Matrix — empty table for user to fill in during execution. Columns: TC ID | Result (pass/fail/block) | Notes.

**Step 8:** Write §7 Sign-Off — verdict line, signature, date.

**Step 9:** Verify test case count is ~30 per spec §5.3.

### Task W4: Self-review deliverables

**Files:**
- Read: all three new files in `docs/superpowers/specs/`

**Step 1:** Placeholder scan — grep for `TBD`, `TODO`, `FIXME`, `XXX`, `???`. Fix any hits (except legitimate references to `TODO1.md`).

**Step 2:** Internal consistency — count findings in `audit.md` §4-7 == row count in `findings.csv` == number of unique F-NNN IDs.

**Step 3:** Scope check — all findings reference files within spec §2.1 scope. No findings about out-of-scope files.

**Step 4:** Ambiguity check — every finding has unambiguous `File:line`. Every severity matches rubric §4.2.

**Step 5:** Smoke test plan — every TC has explicit pass criteria, not vague "works correctly".

**Step 6:** Fix any issues inline. No re-review.

### Task W5: Commit deliverables

**Step 1:** Stage all three files:
```bash
cd /home/z/my-project/repos/photoz
git add docs/superpowers/specs/2026-07-13-v0x04-findings.csv \
        docs/superpowers/specs/2026-07-13-v0x04-chunked-gcm-audit.md \
        docs/superpowers/specs/2026-07-13-v0x04-smoke-test-plan.md
```

**Step 2:** Commit:
```bash
git commit -m "docs(audit): v0x04 chunked GCM audit + smoke test plan

- Audit report covering Angle B (primary), A/C/D (secondary) against
  video pipeline end-to-end
- Smoke test plan for maintainer on 1 physical device (~30 test cases)
- Findings CSV stub for issue tracker import
- Verdict: <PASS|FAIL>"
```
Replace `<PASS|FAIL>` with the actual verdict from §10 of audit.md.

**Step 3:** Clean up scratch file:
```bash
rm -f docs/superpowers/specs/findings-scratch.md
```

### Task W6: Present verdict to user

**Step 1:** Output to user:
- Verdict (PASS / FAIL)
- Finding counts per severity
- Finding counts per angle
- Top 3 highest-severity findings (one-line each)
- Path to audit.md, smoke-test-plan.md, findings.csv
- Next steps (PASS → smoke test plan handoff; FAIL → invoke writing-plans for fix)

**Step 2:** Wait for user response. If user requests changes → edit + re-commit. If user approves → branch per spec §6.2.

---

## Verdict Branches

### Branch PASS (zero Critical + zero High)

Brainstorming + audit cycle complete. Hand off smoke test plan to user for self-execution. Do NOT invoke writing-plans.

### Branch FAIL (one or more Critical OR High)

Invoke `superpowers:writing-plans` skill in a new plan file `docs/plans/YYYY-MM-DD-v0x04-fixes.md` covering ONLY Critical + High findings. Medium/Low/Info stay in `findings.csv` as backlog.

---

## Out-of-Scope Reminders

- Do NOT implement any fix inline during this audit cycle. Findings only.
- Do NOT run `./gradlew` (env has no Android SDK).
- Do NOT update `ENCRYPTION.md` or `TODO1.md` — separate TODOs.
- Do NOT migrate v0x02 videos to v0x04 — separate TODO.
- Do NOT spend time on findings outside spec §2.1 file list.
