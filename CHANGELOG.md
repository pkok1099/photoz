# Changelog

All notable changes to PhotoZ are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

---

## [Unreleased]

### Added
- Vendored [obra/superpowers](https://github.com/obra/superpowers) at `vendor/superpowers/` as a git submodule (v6.1.1) — provides TDD, systematic-debugging, brainstorming, planning, code-review, git-worktree, and subagent-driven-development skills for Claude Code, Cursor, Codex, Kimi, OpenCode, and Pi. Install via `.claude-plugin/marketplace.json`. See `SUPERPOWERS.md` for setup.
- `SUPERPOWERS.md` — user-facing doc for installing and updating the vendored Superpowers plugin.
- `.claude-plugin/marketplace.json` — local Claude plugin marketplace manifest exposing `superpowers@photoz-local`.
- Pointer block at the top of `AGENTS.md` directing agents to the vendored Superpowers.

### Changed
- `AGENTS.md` — added a banner pointing at `vendor/superpowers/`, `SUPERPOWERS.md`, and `.claude-plugin/marketplace.json`.

### Fixed
- None.

### Security
- None.

### Documentation
- **Verified that `TODO1.md` item #2 ("Chunked streaming encryption for large video files") is already shipped.** The v0x04 chunked GCM format is fully implemented across write, sequential-read, and random-access-read paths. `TODO1.md` and `ENCRYPTION.md` are stale and do not yet document v0x03 (GCM non-chunked) or v0x04 (chunked GCM). Follow-up: update both docs.

---

## [v0x04-chunked-gcm] — Status: Shipped (audit pending)

The chunked streaming encryption feature requested in `TODO1.md` #2 is implemented and active. All new files (photos and videos) are written with `useGcm = true`, which routes through the v0x04 chunked GCM path. The format is documented below for reference; `ENCRYPTION.md` should be updated to include this.

### On-disk format

```
[0x04][chunk_size(4, BE)][total_plaintext_size(8, BE)]   ← 13-byte header
[nonce(12)] [ciphertext(chunkSize)] [tag(16)]             ← per chunk, repeated
[nonce(12)] [ciphertext(N)]   [tag(16)]                   ← last chunk (may be < chunkSize)
```

Constants (`encryption/domain/crypto/Constants.kt`):
- `CHUNK_SIZE = 1_048_576` (1 MiB)
- `GCM_IV_SIZE = 12`
- `GCM_TAG_SIZE = 16`
- `CHUNK_OVERHEAD = 28` (nonce + tag)

### Implementation map

**Write path:**
| File | Role |
|---|---|
| `model/repositories/PhotoRepository.kt` | `createPhotoFile` — entry point, `useGcm = true` hardcoded, 64 KB buffer copy, optional SHA-256 dedup digest |
| `model/io/FastStartUseCase.kt` | QtFastStart pre-encrypt MOOV relocation (`TODO1.md` #1, DONE) |
| `io/VaultFileStorage.kt` | Chokepoint `openEncryptedOutput` / `openEncryptedInput` |
| `encryption/domain/crypto/HybridCryptoEngine.kt` | Version dispatch on read; v0x04 path on write |
| `encryption/domain/crypto/ChunkedGcmOutputStream.kt` | 1 MiB chunk writer, header patching on `close()` |
| `encryption/domain/models/Algorithm.kt` | `EncryptionVersionByte.Four(0x04)`, header sizes |

**Read path (ExoPlayer):**
| File | Role |
|---|---|
| `encryption/domain/crypto/ChunkedGcmInputStream.kt` | Sequential reader |
| `transcoding/data/VersionDispatchDataSourceFactory.kt` | Byte-0 dispatch |
| `transcoding/data/ChunkedGcmRandomAccessDataSource.kt` | O(1) chunk seek for ExoPlayer |
| `transcoding/data/AesCbcRandomAccessDataSource.kt` | Legacy v0x01/v0x02 CBC path (still active for old videos) |
| `imageviewer/ui/ImageViewerViewModel.kt` | `mediaSourceFactory`, `videoStreamState` ConcurrentHashMap, monitor coroutine polling `File.length()` every 100 ms |

### Version dispatch table

| Version byte | Format | Write | Sequential read | Random-access read |
|---|---|---|---|---|
| `0x01` | Legacy GCM (no header) | — (migration only) | `LegacyGcmCryptoEngine` | `AesCbcRandomAccessDataSource` (throws — videos never used v1) |
| `0x02` | CBC + IV (current v3.x) | — (only via `useGcm = false`, no production callers) | `HybridCryptoEngine` CBC branch | `AesCbcRandomAccessDataSource` |
| `0x03` | GCM whole-file | — | `HybridCryptoEngine` GCM branch | n/a (throws inside CBC DataSource) |
| `0x04` | Chunked GCM (1 MiB) | `ChunkedGcmOutputStream` (default for all new files) | `ChunkedGcmInputStream` | `ChunkedGcmRandomAccessDataSource` |

### Known follow-ups (not blocking)

- `ENCRYPTION.md` is stale — does not document v0x03 GCM or v0x04 chunked GCM.
- `TODO1.md` item #2 is marked TODO but is actually DONE.
- No formal audit has been performed against the v0x04 implementation. Suggested angles for a future audit: streaming & progressive-download correctness (primary), GCM tag verification, random-access seek edge cases, v0x02 ↔ v0x04 interop.
- Legacy v0x02 videos in existing user vaults are not auto-migrated to v0x04. A background migrator (following the `LegacyEncryptionMigrator` pattern) would close this gap.

---

## Pre-v0x04 history

See `TODO1.md`, `ROADMAP.md`, `ROADMAP2.md`, and `ENCRYPTION.md` for the historical encryption format evolution (v1.x raw GCM → v2.x CBC + salt header → v3.x CBC + VMK/KEK → v0x04 chunked GCM) and completed work items.
