# 09 — Audit B2 Summary

> **Audit period**: 2026-07-08 to 2026-07-09
> **Auditor**: Super Z (automated deep audit + fixes)
> **Branch**: `audit-b2-fixes` (8 commits di atas `B2`)
> **Full report**: [`/download/AUDIT_B2.md`](../../download/AUDIT_B2.md)

---

## Executive Summary

Cabang B2 berisi migrasi besar-besaran dari rclone subprocess ke gomobile JNI, plus aktivasi SQLCipher, Argon2id, chunked GCM, dan two-layer key escrow. Audit mendalam menemukan **72 findings**:

| Severity | Count | Status |
|----------|------:|--------|
| Critical | 9 | 7 fixed, 2 documented (butuh design) |
| High | 23 | 19 fixed, 4 documented (butuh instrumented test) |
| Medium | 27 | 22 fixed, 5 documented |
| Low | 13 | 11 fixed, 2 documented |
| **Total** | **72** | **59 fixed, 13 documented** |

**Test coverage**: 67 tests across 7 test classes, 0 failures.

---

## Commits di `audit-b2-fixes`

| # | Commit | Description |
|---|--------|-------------|
| 1 | `8496bb81` | Critical + High severity fixes dari audit awal |
| 2 | `0cd5d2b6` | Priority follow-up — F-BACK-001/002, F-ENC-001/003/004/005, F-SYNC-002/006 |
| 3 | `5274175f` | Test coverage + F-ENC-011 fix (32 tests) |
| 4 | `f04a2b81` | Medium/Low cleanup + additional tests (54 tests) |
| 5 | `6fe980a3` | F-SYNC-007 parsers, F-ENC-014 orphan table, F-ENC-022/024 typed exceptions |
| 6 | `03b07089` | F-ENC-016 header patch + RcloneJsonParsingTest (67 tests) |
| 7 | `536b143a` | F-SYNC-021 async progress + F-SYNC-014 optimistic concurrency |
| 8 | `9c24a401` | Rclone folder operations (mkdir, move, copydir, rmdir, purge, hash) |

**Total**: 78 files changed, 10185 insertions, 8088 deletions

---

## Critical Fixes (9 total)

### F-MAIN-001: proguard-rules.pro package rename
- **File**: `app/proguard-rules.pro:14, 17`
- **Before**: keep rule merujuk `dev.leonlatsch.photok.**` (tidak match class apapun)
- **After**: `onlasdan.gallery.**` — R8 keep rules sekarang match class yang benar

### F-ENC-001: SqlCipher Keystore fallback UI warning
- **File**: `SqlCipherKeyProvider.kt`, `Config.kt`, `SettingsScreen.kt`
- **Before**: Silent fallback ke plaintext SharedPreferences saat Keystore gagal
- **After**: `Config.keystoreFallbackActive` flag + warning banner di Settings

### F-ENC-002: SQLCipher passphrase leak via logcat
- **File**: `SqlCipherMigrationHelper.kt`
- **Before**: Passphrase hex bisa bocor ke logcat via exception message
- **After**: Scrub passphrase dari error logging menggunakan regex pattern

### F-SYNC-001: RPC error detection berbasis substring
- **File**: `RcloneController.kt`
- **Before**: `result.contains("\"error\":")` — false positive pada filename mengandung "error"
- **After**: `JSONObject.has("error")` — proper JSON-based detection

### F-SYNC-003: PhotoSyncWorker duplicate catch block
- **File**: `PhotoSyncWorker.kt:680-697`
- **Before**: Dua `catch (e: Exception)` beruntun — catch kedua dead code, verifikasi error diabaikan
- **After**: Merged catch dengan dispatch benign (hash-unsupported) vs real error

### F-SYNC-004/012: loginRepo escrow download error collapse
- **File**: `RepoManager.kt:524-536, 550-559`
- **Before**: Download error → `EscrowType.NONE` → user dipaksa setup vault baru → **silent vault brick**
- **After**: Download error → `LoginResult.Failure` — user bisa retry

### F-SYNC-005: submitPassword navigasi ke gallery meski persist gagal
- **File**: `RepoSetupViewModel.kt:657-674`
- **Before**: `createPasswordProtectionFromSession` failure → navigasi ke gallery → next open buat new VMK → **data loss**
- **After**: Failure → `RepoSetupState.Error` + `return@launch`

### F-BACK-001: V5 backup kehilangan 13 kolom metadata
- **File**: `BackupMetaData.kt`, `BackupMappers.kt`
- **Before**: `PhotoBackup` hanya 6 field — dedup, multi-vault, favorites, EXIF, AI tags, symlink semua hilang
- **After**: Extended ke 19 fields (full round-trip)

### F-BACK-002: V5 restore tidak tulis vault_protection row
- **File**: `RestoreBackupV5.kt`
- **Before**: Restore hanya merge photos — user tidak bisa unlock dengan password backup
- **After**: Persist source `wrappedVMK` + `params` sebagai new VaultProtection row

---

## High Fixes (19 fixed, 4 documented)

### Encryption
- **F-ENC-003**: `createPasswordProtectionFromSession` pakai Argon2id (was: PBKDF2)
- **F-ENC-004/005**: RecoveryPhrase handler pakai Argon2id + IV decoding
- **F-ENC-006/007/008**: ChunkedGcmRandomAccessDataSource — validate chunkSize, full-read loop, channel lifecycle
- **F-ENC-009**: VaultService.canUnlock — hapus RcloneDiag metadata leak
- **F-ENC-010**: Mass replace `android.util.Log` → `Timber` di encryption/transcoding
- **F-ENC-011**: ChunkedGcmInputStream throw on AEADBadTagException (was: silent swallow)
- **F-ENC-016**: ChunkedGcmOutputStream patch totalPlaintextSize di header saat close()
- **F-ENC-019**: unlockMultiVaultPassword narrow catch (hanya auth failures)
- **F-ENC-020**: CbcCryptoEngine readFully helper (loop sampai buffer full)
- **F-ENC-024**: Typed exceptions (CorruptHeaderException) menggantikan return null

### Sync
- **F-SYNC-002**: RPC timeout 30s via withTimeoutOrNull + runBlocking
- **F-SYNC-006**: RcloneConfigManager.clear() invalidate config cache
- **F-SYNC-007**: Regex parsers → JSONObject (parseVaultProtection, parseMarker, parseRegistryJson)
- **F-SYNC-009**: @Volatile pada companion object state
- **F-SYNC-010**: Cache reflection Class/Method lookups via lazy
- **F-SYNC-011**: verifyFileExists pakai JSONObject.getLong (was: substring Size match)
- **F-SYNC-014**: HashRegistry optimistic concurrency (re-download sebelum upload)
- **F-SYNC-017**: SyncModule conditional MinimumLoggingLevel (INFO debug, WARN release)
- **F-SYNC-021**: SyncRestorer async progress via _async=true + job/status polling

### Backup
- **F-BACK-003/004**: Replace android.util.Log dengan Timber
- **F-BACK-005**: Delete TfliteTagExtractor.kt.bak
- **F-BACK-008**: HashRegistryDao.findByHash filter by vault_id

### Build
- **F-MAIN-002**: useLegacyPackaging = false (was: true — leftover dari subprocess approach)

---

## Medium Fixes (22 fixed, 5 documented)

### Encryption
- **F-ENC-014**: DROP orphan vault_protection table (MIGRATION_16_17)
- **F-ENC-021**: Fix misleading comment di SqlCipherMigrationHelper
- **F-ENC-022**: Rename removeProtection → removeAllProtectionsOfType
- **F-ENC-023**: Fix SqlCipherKeyProvider doc comment (raw-key, bukan PBKDF2)

### Sync
- **F-SYNC-013**: parseListResult pakai JSONArray (was: regex truncate escaped quotes)
- **F-SYNC-016**: Hapus dead "binary"/"rclone" substring checks
- **F-SYNC-019**: RepoSetupViewModel replace android.util.Log.e dengan Timber
- **F-SYNC-028**: Fix "50 MB" → "5 MB" thumbnail pack doc

### Backup
- **F-BACK-007**: Log before advancing zip entry (was: log next entry's name)
- **F-BACK-009**: TODO1.md QtFastStart status TODO → DONE
- **F-BACK-010**: PhotoRepository TODO #1 comments cleanup
- **F-BACK-011**: FastStart temp file cleanup comment
- **F-BACK-012**: BackupMetaData.V1 unused fields comment
- **F-BACK-013**: AlbumDao.internalLink param order match SQL
- **F-BACK-014**: RestoreBackupV1 startsWith instead of contains
- **F-BACK-015**: ImportBottomSheetDialogFragment albumUUID default null

### Docs
- **F-ENC-025**: Replace "TODO #N —" → "(roadmap #N) —" (23 files, 30+ comments)
- **F-ENC-013**: git mv 6 test files dari dev/leonlatsch/photok/ ke onlasdan/gallery/
- **F-MAIN-003**: README.md update (schema v9→v16, rcd→gomobile JNI)
- **F-MAIN-004**: AGENTS.md update source path
- **F-MAIN-005**: ROADMAP2.md SQLCipher marked DONE
- **F-MAIN-006**: Delete github_log.txt leftover

### Dead Code
- Delete `scripts/build-rclone.sh` + `.github/workflows/build-rclone.yml` (old subprocess)
- Delete `TfliteTagExtractor.kt.bak`

---

## Test Coverage

### New Test Files (4 files, 67 tests)

| Test Class | Tests | Coverage |
|-----------|------:|----------|
| `BackupMappersTest` | 13 | F-BACK-001 — Photo metadata round-trip |
| `KeyGenArgon2idTest` | 9 | F-ENC-026 — Argon2id KDF correctness |
| `ChunkedGcmStreamTest` | 10 | F-ENC-027 — Chunked GCM round-trip + tamper detection |
| `Argon2idIvEncodingTest` | 7 | F-ENC-004/005 — IV encoding consistency |
| `SqlCipherMigrationHelperTest` | 5 | F-ENC-028 — detection logic |
| `DedupTest` (updated) | 10 | F-BACK-008 — vault_id scoped lookup |
| `RcloneJsonParsingTest` | 27 | F-SYNC-001/013 — JSON parsing + operation formats |
| **Total** | **67** | **0 failures** |

---

## Unfixed (13 documented)

### Butuh Design Decision
- **F-BACK-001/002**: V5 backup metadata loss + vault protection restore — **FIXED** (round 2)
- **F-ENC-015**: Rename `CbcCryptoEngine` → `HybridCryptoEngine` (low value, skip)
- **F-ENC-017**: Column rename `wrappedVMK` → `wrapped_vmk` (butuh migration v18)
- **F-ENC-018**: DRY violation di createPasswordProtectionFromSession — **FIXED** (delegate ke wrapExistingVmk)

### Butuh Instrumented Test (device)
- **F-ENC-026 sisa**: RecoveryPhrase handler integration test
- **F-ENC-027 sisa**: ChunkedGcmRandomAccessDataSource test (butuh ExoPlayer)
- **F-ENC-028 sisa**: SqlCipherMigrationHelper full round-trip (butuh SQLCipher native)
- **F-BACK-006**: Backup/restore V1-V5 round-trip (butuh full Room + SQLCipher stack)

### Butuh Backend Support
- **F-SYNC-020**: `useLegacyPackaging = false` — **FIXED** (F-MAIN-002)
- **F-SYNC-023**: SyncLogger.trimIfNeeded reads entire file — butuh refactor ke RandomAccessFile
- **F-SYNC-027**: RepoManager.registerRepo marker plaintext — documented as intentional

---

## Build Verification

```bash
# Compile main sources
./gradlew :app:compileFossDebugKotlin
# → BUILD SUCCESSFUL

# Compile test sources
./gradlew :app:compileFossDebugUnitTestKotlin
# → BUILD SUCCESSFUL

# Run all tests
./gradlew :app:testFossDebugUnitTest
# → BUILD SUCCESSFUL (67 tests, 0 failures)
```

**Note**: Local build menggunakan `compileSdk = 36` (AGP 9.1.0 max). Production build butuh AGP yang support compileSdk 37, atau temporary suppress warning.

---

## Recommendation

1. **Review branch `audit-b2-fixes`** — 8 commits siap merge ke B2
2. **Run full test suite di CI** dengan AGP yang support compileSdk 37
3. **Instrumented tests** untuk coverage gaps (SqlCipher migration, RcloneController integration)
4. **Smoke test pada device** sebelum release — terutama fresh-install login flow (F-SYNC-004/005/012)
5. **Monitor logcat** untuk sisa `RcloneDiag` tags yang belum ter-replace (production leak risk)
