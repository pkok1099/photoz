# Design Spec — v0x04 Chunked GCM Audit & Verification

> **Date:** 2026-07-13
> **Author:** Superpowers brainstorming session
> **Status:** Awaiting user review
> **Related:** `TODO1.md` item #2, `ENCRYPTION.md` (stale — does not document v0x03 GCM or v0x04 chunked GCM)

---

## 1. Background & Problem Statement

`TODO1.md` item #2 ("Chunked streaming encryption for large video files") is marked as **TODO**, but a code exploration of the photoz repository reveals that the feature has in fact already shipped:

| Component | File | Status |
|---|---|---|
| v0x04 chunked GCM writer | `encryption/domain/crypto/ChunkedGcmOutputStream.kt` | Shipped |
| Sequential reader | `encryption/domain/crypto/ChunkedGcmInputStream.kt` | Shipped |
| Random-access reader (ExoPlayer) | `transcoding/data/ChunkedGcmRandomAccessDataSource.kt` | Shipped |
| ExoPlayer version dispatch | `transcoding/data/VersionDispatchDataSourceFactory.kt` | Shipped |
| Version byte enum | `encryption/domain/models/Algorithm.kt` (`EncryptionVersionByte.Four(0x04)`) | Shipped |
| Encrypt/decrypt dispatch | `encryption/domain/crypto/HybridCryptoEngine.kt` | Shipped |
| Photo import integration | `model/repositories/PhotoRepository.kt` (`useGcm = true` hardcoded) | Shipped |

The on-disk format is:
```
[0x04][chunk_size(4, BE)][total_plaintext_size(8, BE)]   ← 13-byte header
[nonce(12)] [ciphertext(chunkSize)] [tag(16)]             ← per chunk, repeated
[nonce(12)] [ciphertext(N)]   [tag(16)]                   ← last chunk (may be < chunkSize)
```

Because the feature shipped without a formal verification pass — and because the existing documentation (`ENCRYPTION.md`, `TODO1.md`) does not acknowledge it — there is a need to audit the implementation against its design intent, document the actual on-disk format, and produce a smoke test plan that can be run on a physical device to confirm runtime behavior.

---

## 2. Audit Scope

### 2.1 In Scope — Video Pipeline End-to-End

The audit covers the full pipeline from plaintext import to ExoPlayer render, focusing on the v0x04 chunked GCM path but also covering the v0x02 CBC legacy path that coexists in the codebase (for interop auditing).

**Write path (import):**

| File | Role |
|---|---|
| `model/repositories/PhotoRepository.kt` | Entry point `createPhotoFile`, hardcoded `useGcm=true`, 64KB buffer copy, optional SHA-256 dedup digest |
| `model/io/FastStartUseCase.kt` | QtFastStart pre-encrypt MOOV relocation |
| `io/VaultFileStorage.kt` | Chokepoint `openEncryptedOutput` / `openEncryptedInput` |
| `encryption/domain/crypto/HybridCryptoEngine.kt` | Version dispatch on read, v0x04 path on write |
| `encryption/domain/crypto/ChunkedGcmOutputStream.kt` | 1MB chunk writer, header patching on `close()` |
| `encryption/domain/models/Algorithm.kt` | `EncryptionVersionByte` enum, header sizes |
| `encryption/domain/crypto/Constants.kt` | `CHUNK_SIZE`, `GCM_IV_SIZE`, `GCM_TAG_SIZE`, `CHUNK_OVERHEAD` |

**Read path (ExoPlayer):**

| File | Role |
|---|---|
| `encryption/domain/crypto/ChunkedGcmInputStream.kt` | Sequential reader |
| `transcoding/data/VersionDispatchDataSourceFactory.kt` (+ `VersionDispatchDataSource`) | Byte-0 dispatch |
| `transcoding/data/ChunkedGcmRandomAccessDataSource.kt` | O(1) chunk seek |
| `transcoding/data/AesCbcRandomAccessDataSource.kt` | Legacy CBC path (interop audit) |
| `imageviewer/ui/ImageViewerViewModel.kt` | `mediaSourceFactory`, `videoStreamState` ConcurrentHashMap, monitor coroutine polling `File.length()` every 100ms |
| `imageviewer/ui/compose/ExoPlayerState.kt` + `ImageViewerPage.kt` | Compose render side |

**Thumbnails/previews (secondary, for interop):**

| File | Role |
|---|---|
| `model/io/CreateThumbnailsUseCase.kt` | Whole-file encrypt via `openEncryptedOutput` |
| `transcoding/data/EncryptedImageFetcher.kt` | Coil fetcher, whole-file decrypt to `ByteArray` |

### 2.2 Out of Scope

- Implementation of any fix (handled by `writing-plans` skill if audit fails the gate)
- Running instrumented tests (the audit environment has no Android SDK)
- Updating `ENCRYPTION.md` (separate TODO)
- Updating `TODO1.md` (separate TODO)
- Migrating legacy v0x02 videos to v0x04 (separate TODO)
- SQLCipher audit (already covered in `ROADMAP2.md` Phase 1.1)
- Argon2id KDF audit (`TODO1.md` #3, separate scope)

---

## 3. Methodology

### 3.1 Approach — Threat-Angle First, File Second

The audit is organized by **threat angle** rather than by file. Each angle scans the relevant files for that angle's concerns. This prioritizes findings by risk category (matching the user's stated priority of streaming correctness) and surfaces cross-cutting concerns that a linear file walk might miss.

The four angles, in execution order:

1. **Angle B — Streaming & Progressive-Download (PRIMARY, ~60% effort)**
2. **Angle A — Cryptographic Correctness (SECONDARY, ~15% effort)**
3. **Angle C — Random-Access Seek (SECONDARY, ~15% effort)**
4. **Angle D — Backwards Compatibility (SECONDARY, ~10% effort)**

### 3.2 Per-Angle Checklist

#### Angle B — Streaming & Progressive-Download

- **B1.** `ChunkedGcmOutputStream.close()` contract — header patching for `totalPlaintextSize` only works if the backing stream is a `FileOutputStream` (seekable). What happens with non-seekable streams (network/pipe)?
- **B2.** `ChunkedGcmInputStream` blocking behavior — does it block forever on truncated input? Does it swallow `IOException`?
- **B3.** `ChunkedGcmRandomAccessDataSource.open()` contract — return value for (a) full file, (b) mid-download, (c) empty file, (d) seek past EOF.
- **B4.** `waitForBytesAvailable` polling loop — `AesCbcRandomAccessDataSource` has a 50ms-poll / 10-minute-timeout `BlockingInputStream`. Does `ChunkedGcmRandomAccessDataSource` have an equivalent? Is behavior consistent?
- **B5.** `videoStreamState` ConcurrentHashMap race — who puts/removes entries? Does rapid viewer open/close (100ms) leak entries? Is `downloadComplete=true` set before `ExoPlayer.open()`?
- **B6.** Monitor coroutine polling `File.length()` — what if `File.length()` returns a smaller value than before? Is the coroutine cancelled on viewer close? Backoff on stalled download?
- **B7.** `availableBytesProvider` / `downloadCompleteProvider` callback semantics — what does `-1L` mean to ExoPlayer? Who registers entries first?
- **B8.** ExoPlayer `ProgressiveMediaSource` contract — does `DataSource.open` block until bytes are available or timeout, per ExoPlayer's contract?
- **B9.** QtFastStart + chunked GCM interaction — does QtFastStart always relocate MOOV, or skip when already in front?
- **B10.** Cancellation & resource cleanup — on viewer close mid-playback: ExoPlayer `release()` → `DataSource.close()` → is `BlockingInputStream` thread interrupted? Is monitor coroutine cancelled?

#### Angle A — Cryptographic Correctness

- **A1.** Nonce uniqueness — `SecureRandom.nextBytes(12)` per chunk. Birthday-paradox collision probability for 100k chunks (~100GB video) ≈ 10⁻⁷. Acceptable, or should a counter-based nonce be used?
- **A2.** GCM tag verification on decrypt — does `ChunkedGcmInputStream` and `ChunkedGcmRandomAccessDataSource` propagate `AEADBadTagException`, or swallow it? Swallowing = silent corruption (Critical).
- **A3.** AAD usage — v0x04 does not use AAD. Should the header (version + chunk_size + total_size) be bound as AAD to prevent header tampering?
- **A4.** Key handling — is `vmk` zeroed from memory on `close()`? (Related to `TODO1.md` #7, marked DONE — verify.)

#### Angle C — Random-Access Seek

- **C1.** Chunk boundary seek — `chunkIndex = position / chunkSize`. Verify for `position == 0`, `position == chunkSize` (exact boundary), `position == totalSize - 1`.
- **C2.** Last chunk < 1MB — does the reader use `totalPlaintextSize` to compute last-chunk size?
- **C3.** Out-of-range seek — `position >= totalSize`. Return -1, throw, or return 0 bytes? Per ExoPlayer contract.
- **C4.** Seek-during-download — does it block until bytes are available or fail fast?
- **C5.** `open()` with `dataSpec.length != C.LENGTH_UNBOUNDED` — does the reader honor the requested range, or always return the entire chunk?

#### Angle D — Backwards Compatibility

- **D1.** `VersionDispatchDataSource.open()` dispatch table — `0x01`/`0x02` → CBC; `0x04` → chunked GCM; `0x03` → falls into `else` → CBC path → CBC throws on v3. Intentional or bug?
- **D2.** v0x02 video playback — does `AesCbcRandomAccessDataSource` still function for progressive streaming? Can the 10-minute timeout trigger on large videos + slow networks?
- **D3.** Backup V5 ZIP round-trip — does the version byte + chunk header survive backup/restore without transformation?
- **D4.** Mixed-vault scenario — vault with mixed v0x02 + v0x04 files. Does dispatch work correctly when user scrolls the gallery?

---

## 4. Findings Taxonomy & Severity Rubric

### 4.1 Finding Format

Every finding follows the standard format:

```
F-<seq>: <title>
  Angle: B (streaming) | A (crypto) | C (random-access) | D (compat)
  Severity: Critical | High | Medium | Low | Info
  File: <path>:<line>
  Description: <what is wrong>
  Evidence: <code excerpt or spec citation>
  Impact: <concrete consequence to user/data>
  Recommendation: <proposed fix>
  Effort: <S/M/L>
```

### 4.2 Severity Rubric

| Level | Definition | Examples |
|---|---|---|
| **Critical** | Data loss, security bypass, crash on common path, plaintext leak | GCM tag not verified → silent corruption; race condition crashing ExoPlayer on 100MB+ videos; nonce reuse |
| **High** | Correctness bug on non-trivial path, very bad UX, potential data loss on edge case | Progressive download stuck forever on network drop; seek to last chunk crashes; video preview thumbnail corrupt |
| **Medium** | Bad UX but recoverable, rare edge case, minor spec deviation | Uncancelable 10-minute timeout; logcat verbose leak of paths; slow seek on large videos |
| **Low** | Code smell, maintainability, stale documentation | Magic number without constant; outdated comment; dead code |
| **Info** | Observation without need for fix | Design decision notes, intentional trade-offs |

### 4.3 Pass Gate

- **PASS** — Zero Critical findings AND zero High findings
- **FAIL** — One or more Critical OR High findings

Medium / Low / Info findings do not affect the gate. They are recorded in the CSV as `status=open` follow-up issues.

### 4.4 False-Positive Guard

- Every finding must have **evidence** in the form of a code excerpt or spec citation, not opinion.
- If it is unclear whether something is a bug or an intentional design decision, classify as `Info` and surface it in the user review gate.
- No finding without a recommendation (minimum: "investigate further").

---

## 5. Deliverables

Three artifacts, all under `docs/superpowers/specs/`:

### 5.1 Audit Report — `2026-07-13-v0x04-chunked-gcm-audit.md`

Structure:

```
0. Frontmatter (date, auditor, scope, methodology, pass criteria)
1. Executive Summary (verdict, finding counts, narrative)
2. Audit Scope (file list, out-of-scope)
3. Methodology (angle-first, severity rubric, checklist)
4. Findings — Angle B (Streaming, PRIMARY)
5. Findings — Angle A (Crypto)
6. Findings — Angle C (Random-Access)
7. Findings — Angle D (Compat)
8. Cross-Angle Findings
9. Recommendations Summary (table: ID | severity | effort | action | owner)
10. Verdict (PASS / FAIL)
11. Appendix (spec citations, file inventory, build commit hash)
```

Estimated length: 3000–4500 words.

### 5.2 Smoke Test Plan — `2026-07-13-v0x04-smoke-test-plan.md`

For maintainer execution on 1 physical device (Android 12+, arm64, debug build). Structure:

```
0. Test Environment (device, build, adb setup)
1. Pre-Test Setup (build & install, fixture matrix, fresh vault)
2. Import Smoke Tests (TC-001..TC-010)
3. Playback Smoke Tests — no network (TC-101..TC-112)
4. Progressive Download Smoke Tests (TC-201..TC-210)
5. Backwards Compat Smoke Tests (TC-301..TC-305)
6. Regression Matrix (per-TC pass/fail/block + notes)
7. Sign-Off
```

Test case format:

```
TC-XXX: <title>
  Precondition: <state>
  Steps:
    1. ...
  Expected: <result>
  adb check: <command, e.g. `adb logcat -s ExoPlayerImpl:I`>
  Pass criteria: <explicit>
```

Fixture matrix:
- Small MP4 5MB (fast import, baseline)
- Medium MP4 100MB (progressive download)
- Large MP4 500MB (boundary stress)
- MOV 50MB (codec diversity)
- Short video 2MB (last-chunk edge case)

Estimated length: 2500–3500 words + ~30 test cases.

### 5.3 Findings CSV — `2026-07-13-v0x04-findings.csv`

CSV stub for issue tracker import (GitHub Issues / Linear / Jira). Header:

```csv
id,title,angle,severity,file,line,description,evidence,impact,recommendation,effort,status
```

- `id` — `F-NNN`, zero-padded
- `angle` — B / A / C / D
- `severity` — Critical / High / Medium / Low / Info
- `effort` — S (<1h) / M (1–4h) / L (4h+)
- `status` — `open` (default), `fixed`, `wontfix`, `investigating`

Estimated: 10–20 rows.

### 5.4 Commit Strategy

Single commit for all three artifacts:

```
docs(audit): v0x04 chunked GCM audit + smoke test plan

- Audit report (Markdown) covering Angle B (primary), A/C/D (secondary)
  against video pipeline end-to-end
- Smoke test plan for maintainer on 1 physical device (~30 test cases)
- Findings CSV stub for issue tracker import
- Verdict: <PASS|FAIL> based on zero Critical + zero High gate
```

This design spec itself is committed in a separate prior commit so the user can review the design before the audit is executed.

---

## 6. Post-Audit Flow

### 6.1 Execution Sequence

1. Audit execution (after this spec is approved) — walk Section 3 checklist, accumulate findings
2. Write `2026-07-13-v0x04-findings.csv` first (foundation)
3. Write `2026-07-13-v0x04-chunked-gcm-audit.md` (references CSV)
4. Write `2026-07-13-v0x04-smoke-test-plan.md` (independent)
5. Spec self-review (placeholder scan, internal consistency, scope check, ambiguity check)
6. User review gate — wait for explicit approval
7. Verdict branch (see 6.2)

### 6.2 Verdict Branches

**Branch A — PASS (zero Critical + zero High):**
- Audit complete
- Medium / Low / Info findings become follow-up issues (CSV `status=open`)
- `writing-plans` is NOT invoked
- Smoke test plan handed to user for self-execution on device
- Brainstorming terminates

**Branch B — FAIL (one or more Critical OR High):**
- Audit report remains complete (nothing removed)
- Critical / High findings become input for `writing-plans`
- The `writing-plans` skill is invoked (per brainstorming SKILL.md: "The terminal state is invoking writing-plans")
- `writing-plans` produces an implementation plan to fix all Critical / High findings
- Medium / Low / Info findings remain in CSV as backlog, do not enter this cycle's plan
- Smoke test plan handed to user for self-execution (may need re-run after fix lands)

### 6.3 Explicit Non-Goals

The following are NOT done post-audit:
- Implementing fixes inline (handled by `writing-plans` → `executing-plans` cycle)
- Running instrumented tests (environment has no Android SDK)
- Updating `ENCRYPTION.md` (separate TODO)
- Updating `TODO1.md` (separate TODO)
- Migrating v0x02 → v0x04 (separate TODO)
- Implementing Medium / Low recommendations (follow-up issues only)

---

## 7. Risks & Mitigations

| Risk | Mitigation |
|---|---|
| Audit finds too few findings → inaccurate | Cross-check against `HybridCryptoEngine` KDoc + `ENCRYPTION.md`, look for gaps between spec and implementation |
| Audit finds too many findings → overwhelming | Group by severity, Critical / High become priority, rest become backlog |
| False positive (not a bug, design decision) | Evidence required + recommendation; if unsure, classify as Info and surface in user review gate |
| Verdict FAIL but fix ambiguous | `writing-plans` handles disambiguation; if still ambiguous, return to user for clarifying question |
| Spec ambiguous when `writing-plans` reads it | Self-review + user review gate as safety net |

---

## 8. Open Questions for User Review

None at spec-writing time. All material design decisions were resolved during the brainstorming Q&A (Q1–Q7).

---

## 9. Approval

By approving this design spec, the user confirms:
- Audit scope (Section 2) is correct
- Methodology (Section 3) is correct
- Severity rubric (Section 4) is correct
- Deliverables structure (Section 5) is correct
- Post-audit flow (Section 6) is correct

On approval, the auditor (this agent) will execute the audit and produce the three deliverables. The user will then review the deliverables before any transition to `writing-plans` (if applicable).
