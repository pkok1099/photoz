# Design Spec — v0x04 Chunked GCM Audit & Verification

> **Date:** 2026-07-13
> **Author:** Superpowers brainstorming session
> **Status:** Approved by user
> **Related:** `TODO1.md` item #2 (stale — feature shipped), `ENCRYPTION.md` (stale — missing v0x03/v0x04)

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

On-disk format:
```
[0x04][chunk_size(4, BE)][total_plaintext_size(8, BE)]   ← 13-byte header
[nonce(12)] [ciphertext(chunkSize)] [tag(16)]             ← per chunk, repeated
[nonce(12)] [ciphertext(N)]   [tag(16)]                   ← last chunk (may be < chunkSize)
```

Because the feature shipped without a formal verification pass — and because the existing documentation does not acknowledge it — there is a need to audit the implementation against its design intent and produce a smoke test plan for runtime verification on a physical device.

---

## 2. Audit Scope

### 2.1 In Scope — Video Pipeline End-to-End

**Write path (import):**
- `model/repositories/PhotoRepository.kt` — `createPhotoFile`, `useGcm=true`, 64KB buffer copy
- `model/io/FastStartUseCase.kt` — QtFastStart pre-encrypt MOOV relocation
- `io/VaultFileStorage.kt` — chokepoint `openEncryptedOutput` / `openEncryptedInput`
- `encryption/domain/crypto/HybridCryptoEngine.kt` — version dispatch
- `encryption/domain/crypto/ChunkedGcmOutputStream.kt` — 1MB chunk writer
- `encryption/domain/models/Algorithm.kt` — `EncryptionVersionByte` enum
- `encryption/domain/crypto/Constants.kt` — chunk/GCM constants

**Read path (ExoPlayer):**
- `encryption/domain/crypto/ChunkedGcmInputStream.kt` — sequential reader
- `transcoding/data/VersionDispatchDataSourceFactory.kt` (+ `VersionDispatchDataSource`) — byte-0 dispatch
- `transcoding/data/ChunkedGcmRandomAccessDataSource.kt` — O(1) chunk seek
- `transcoding/data/AesCbcRandomAccessDataSource.kt` — legacy CBC path (interop)
- `imageviewer/ui/ImageViewerViewModel.kt` — `videoStreamState` ConcurrentHashMap, monitor coroutine
- `imageviewer/ui/compose/ExoPlayerState.kt` + `ImageViewerPage.kt` — render side

**Thumbnails/previews (secondary):**
- `model/io/CreateThumbnailsUseCase.kt`
- `transcoding/data/EncryptedImageFetcher.kt`

### 2.2 Out of Scope

- Implementation of fixes (handled by `writing-plans` if audit fails gate)
- Running instrumented tests (no Android SDK in audit env)
- Updating `ENCRYPTION.md` or `TODO1.md` (separate TODOs)
- Migrating v0x02 → v0x04 (separate TODO)
- SQLCipher audit, Argon2id audit (separate scopes)

---

## 3. Methodology — Angle-First

### Angle B — Streaming & Progressive-Download (PRIMARY, ~60%)

- **B1.** `ChunkedGcmOutputStream.close()` — header patching requires seekable stream. Non-seekable backing stream behavior?
- **B2.** `ChunkedGcmInputStream` blocking — truncated input, swallowed `IOException`?
- **B3.** `ChunkedGcmRandomAccessDataSource.open()` — return values for full/mid-download/empty/seek-past-EOF
- **B4.** `waitForBytesAvailable` — `AesCbcRandomAccessDataSource` has 50ms-poll / 10-min-timeout. Does `ChunkedGcmRandomAccessDataSource` have equivalent?
- **B5.** `videoStreamState` ConcurrentHashMap race — entry leak on rapid open/close? `downloadComplete=true` before `open()`?
- **B6.** Monitor coroutine polling `File.length()` — `File.length()` shrink race? Cancellation? Backoff?
- **B7.** `availableBytesProvider` / `downloadCompleteProvider` callback semantics — `-1L` meaning to ExoPlayer? Who registers first?
- **B8.** ExoPlayer `ProgressiveMediaSource` contract — does `DataSource.open` block per contract?
- **B9.** QtFastStart + chunked GCM — MOOV always relocated?
- **B10.** Cancellation & resource cleanup — `BlockingInputStream` interrupt? Monitor coroutine cancel?

### Angle A — Cryptographic (SECONDARY, ~15%)

- **A1.** Nonce uniqueness — `SecureRandom.nextBytes(12)` per chunk. Birthday paradox for 100k chunks (~100GB) ≈ 10⁻⁷. Acceptable?
- **A2.** GCM tag verification on decrypt — `AEADBadTagException` propagated or swallowed? Swallow = silent corruption (Critical).
- **A3.** AAD usage — v0x04 does not use AAD. Should header be bound as AAD?
- **A4.** Key handling — `vmk` zeroed on `close()`? (TODO1 #7 DONE — verify.)

### Angle C — Random-Access Seek (SECONDARY, ~15%)

- **C1.** Chunk boundary seek — `position == 0`, `== chunkSize`, `== totalSize - 1`
- **C2.** Last chunk < 1MB — does reader use `totalPlaintextSize`?
- **C3.** Out-of-range seek — `position >= totalSize` behavior
- **C4.** Seek-during-download — block or fail fast?
- **C5.** `dataSpec.length != C.LENGTH_UNBOUNDED` — honored or ignored?

### Angle D — Backwards Compat (SECONDARY, ~10%)

- **D1.** `VersionDispatchDataSource.open()` dispatch — `0x03` falls into `else` → CBC path → throws. Intentional or bug?
- **D2.** v0x02 video playback — `AesCbcRandomAccessDataSource` still functional? 10-min timeout trigger risk?
- **D3.** Backup V5 ZIP round-trip — version byte + chunk header survive?
- **D4.** Mixed-vault scenario — dispatch correct on gallery scroll?

---

## 4. Findings Taxonomy & Severity Rubric

### 4.1 Finding Format

```
F-<seq>: <title>
  Angle: B | A | C | D
  Severity: Critical | High | Medium | Low | Info
  File: <path>:<line>
  Description: <what is wrong>
  Evidence: <code excerpt or spec citation>
  Impact: <concrete consequence>
  Recommendation: <proposed fix>
  Effort: <S/M/L>
```

### 4.2 Severity Rubric

| Level | Definition |
|---|---|
| **Critical** | Data loss, security bypass, crash on common path, plaintext leak |
| **High** | Correctness bug on non-trivial path, very bad UX, potential data loss on edge case |
| **Medium** | Bad UX but recoverable, rare edge case, minor spec deviation |
| **Low** | Code smell, maintainability, stale documentation |
| **Info** | Observation without need for fix |

### 4.3 Pass Gate

- **PASS** — Zero Critical AND zero High
- **FAIL** — One or more Critical OR High

### 4.4 False-Positive Guard

- Every finding must have **evidence** (code excerpt or spec citation)
- If unclear whether bug or design decision → classify `Info`, surface in user review
- No finding without recommendation (minimum: "investigate further")

---

## 5. Deliverables

Three artifacts under `docs/superpowers/specs/`:

### 5.1 `2026-07-13-v0x04-findings.csv`

CSV stub for issue tracker import. Header:
```csv
id,title,angle,severity,file,line,description,evidence,impact,recommendation,effort,status
```

- `id` — `F-NNN` zero-padded
- `effort` — S (<1h) / M (1-4h) / L (4h+)
- `status` — `open` (default) / `fixed` / `wontfix` / `investigating`

### 5.2 `2026-07-13-v0x04-chunked-gcm-audit.md`

Structure:
```
0. Frontmatter (date, auditor, scope, methodology, pass criteria)
1. Executive Summary (verdict, finding counts, narrative)
2. Audit Scope (file list, out-of-scope)
3. Methodology (angle-first, severity rubric, checklist)
4. Findings — Angle B (PRIMARY)
5. Findings — Angle A
6. Findings — Angle C
7. Findings — Angle D
8. Cross-Angle Findings
9. Recommendations Summary (table)
10. Verdict (PASS / FAIL)
11. Appendix (spec citations, file inventory, build commit hash)
```

Estimated: 3000–4500 words.

### 5.3 `2026-07-13-v0x04-smoke-test-plan.md`

For maintainer on 1 physical device (Android 12+, arm64, debug build). Structure:
```
0. Test Environment
1. Pre-Test Setup (build, fixture matrix, fresh vault)
2. Import Smoke Tests (TC-001..TC-010)
3. Playback Smoke Tests — no network (TC-101..TC-112)
4. Progressive Download Smoke Tests (TC-201..TC-210)
5. Backwards Compat Smoke Tests (TC-301..TC-305)
6. Regression Matrix
7. Sign-Off
```

Test case format:
```
TC-XXX: <title>
  Precondition: <state>
  Steps: ...
  Expected: <result>
  adb check: <command>
  Pass criteria: <explicit>
```

Fixture matrix: small MP4 5MB, medium MP4 100MB, large MP4 500MB, MOV 50MB, short video 2MB.

Estimated: 2500–3500 words + ~30 test cases.

### 5.4 Commit Strategy

Single commit for all three artifacts.

---

## 6. Post-Audit Flow

### 6.1 Execution Sequence

1. Audit execution — walk Section 3 checklist, accumulate findings
2. Write `findings.csv` first (foundation)
3. Write `audit.md` (references CSV)
4. Write `smoke-test-plan.md` (independent)
5. Self-review (placeholder scan, internal consistency, scope check, ambiguity check)
6. User review gate — wait for explicit approval
7. Verdict branch

### 6.2 Verdict Branches

**PASS (zero Critical + zero High):**
- Audit complete. Medium/Low/Info become follow-up issues (CSV `status=open`).
- `writing-plans` NOT invoked.
- Smoke test plan handed to user for self-execution.

**FAIL (one or more Critical OR High):**
- Critical/High findings become input for `writing-plans`.
- `writing-plans` skill invoked to produce fix implementation plan.
- Medium/Low/Info remain in CSV as backlog.

### 6.3 Explicit Non-Goals

- Implementing fixes inline (handled by `writing-plans` → `executing-plans`)
- Running instrumented tests
- Updating `ENCRYPTION.md` / `TODO1.md`
- Migrating v0x02 → v0x04
- Implementing Medium/Low recommendations

---

## 7. Risks & Mitigations

| Risk | Mitigation |
|---|---|
| Too few findings → inaccurate | Cross-check `HybridCryptoEngine` KDoc + `ENCRYPTION.md` for spec/impl gaps |
| Too many findings → overwhelming | Group by severity, Critical/High priority, rest backlog |
| False positive | Evidence required; unsure → Info + user review |
| Verdict FAIL but fix ambiguous | `writing-plans` handles disambiguation |
| Spec ambiguous | Self-review + user review gate as safety net |
