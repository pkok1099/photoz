# 07 — Testing

> **File referensi**: `app/src/test/`
> **Current coverage**: 67 tests across 7 test classes, 0 failures

---

## Test Stack

| Tool | Version | Purpose |
|------|---------|---------|
| JUnit 4 | 4.13.2 | Test framework |
| Robolectric | 4.16.1 | Android runtime emulation (no device needed) |
| MockK | 1.14.11 | Kotlin mocking |
| kotlinx-coroutines-test | 1.11.0 | Coroutine testing (`runTest`) |
| Hilt Android Testing | 2.60 | DI testing |

---

## Running Tests

```bash
# Run all unit tests
./gradlew :app:testFossDebugUnitTest

# Run specific test class
./gradlew :app:testFossDebugUnitTest --tests "onlasdan.gallery.backup.data.BackupMappersTest"

# Run multiple classes
./gradlew :app:testFossDebugUnitTest \
  --tests "onlasdan.gallery.backup.data.BackupMappersTest" \
  --tests "onlasdan.gallery.encryption.domain.crypto.KeyGenArgon2idTest"

# Run with verbose output
./gradlew :app:testFossDebugUnitTest --console=plain --info
```

### Test Results

```
app/build/test-results/testFossDebugUnitTest/
├── TEST-onlasdan.gallery.backup.data.BackupMappersTest.xml
├── TEST-onlasdan.gallery.encryption.data.SqlCipherMigrationHelperTest.xml
├── TEST-onlasdan.gallery.encryption.domain.crypto.Argon2idIvEncodingTest.xml
├── TEST-onlasdan.gallery.encryption.domain.crypto.ChunkedGcmStreamTest.xml
├── TEST-onlasdan.gallery.encryption.domain.crypto.KeyGenArgon2idTest.xml
├── TEST-onlasdan.gallery.sync.domain.DedupTest.xml
└── TEST-onlasdan.gallery.sync.rclone.RcloneJsonParsingTest.xml
```

HTML report: `app/build/reports/tests/testFossDebugUnitTest/index.html`

---

## Test Classes

### 1. BackupMappersTest (13 tests)

**File**: `app/src/test/java/onlasdan/gallery/backup/data/BackupMappersTest.kt`

Verifies `Photo.toBackup()` / `PhotoBackup.toDomain()` round-trip untuk semua 19 Photo columns.

```
✓ round-trip preserves all original V3 fields
✓ round-trip preserves syncState
✓ round-trip preserves contentHash and albumPath
✓ round-trip preserves vaultId and isFavorite
✓ round-trip preserves deletedAt
✓ round-trip preserves EXIF metadata
✓ round-trip preserves aiTags
✓ round-trip preserves canonicalUuid for symlink albums
✓ round-trip preserves relativePath
✓ round-trip handles nullable fields with null values
✓ round-trip of photo with all fields populated preserves every field
✓ syncState deserialization falls back to LOCAL_ONLY for unknown keys
✓ syncState deserialization handles null syncState
```

### 2. KeyGenArgon2idTest (9 tests)

**File**: `app/src/test/java/onlasdan/gallery/encryption/domain/crypto/KeyGenArgon2idTest.kt`

Verifies Argon2id KDF correctness.

```
✓ Argon2id produces 32-byte key for AES-256
✓ Argon2id is deterministic with same inputs
✓ Argon2id with different salts produces different keys
✓ Argon2id with different passwords produces different keys
✓ Argon2id with different memory costs produces different keys
✓ Argon2id and PBKDF2 produce different keys for same input
✓ Argon2id with low memory cost still works (for fast tests)
✓ generateVaultMasterKey produces 32-byte AES key
✓ generateVaultMasterKey produces different keys on each call
```

### 3. ChunkedGcmStreamTest (10 tests)

**File**: `app/src/test/java/onlasdan/gallery/encryption/domain/crypto/ChunkedGcmStreamTest.kt`

Verifies ChunkedGcm OutputStream → InputStream round-trip.

```
✓ empty input round-trips to empty output
✓ small data (less than one chunk) round-trips correctly
✓ exactly one chunk round-trips correctly
✓ multi-chunk data round-trips correctly
✓ small chunk size works for multi-chunk data
✓ version byte 0x04 is written as first byte
✓ different VMKs produce different ciphertexts
✓ tampered ciphertext throws on decryption (GCM auth tag verification)
✓ large data round-trips correctly (10 chunks)
✓ byte-at-a-time writes produce same result as bulk write
```

### 4. Argon2idIvEncodingTest (7 tests)

**File**: `app/src/test/java/onlasdan/gallery/encryption/domain/crypto/Argon2idIvEncodingTest.kt`

Verifies Argon2id IV encoding (4-byte memory-cost prefix + 16-byte IV).

```
✓ encode-decode round-trip preserves memory cost and IV
✓ encode-decode round-trip with small memory cost
✓ encode-decode round-trip with large memory cost
✓ different memory costs produce different encoded IVs
✓ Argon2id KEK can wrap and unwrap VMK with encoded IV
✓ decode rejects too-short iv field
✓ PBKDF2 iv field is just 16 bytes (no memory prefix)
```

### 5. SqlCipherMigrationHelperTest (5 tests)

**File**: `app/src/test/java/onlasdan/gallery/encryption/data/SqlCipherMigrationHelperTest.kt`

Verifies detection logic (full migration butuh instrumented test dengan SQLCipher native lib).

```
✓ migrateIfNecessary returns true for fresh install (no DB file)
✓ isSqlCipherEncrypted detects SQLite magic header
✓ isSqlCipherEncrypted returns true for non-SQLite files
✓ Config sqlCipherMigrationDone flag is writable and readable
✓ Config keystoreFallbackActive flag is writable and readable
```

### 6. DedupTest (10 tests)

**File**: `app/src/test/java/onlasdan/gallery/sync/domain/DedupTest.kt`

Verifies dedup decision logic.

```
✓ same content hash in registry returns skip=true
✓ different content hash not in registry returns skip=false
✓ null content hash returns skip=false (no dedup possible)
✓ blank content hash returns skip=false
✓ findCanonical returns entry for known hash
✓ findCanonical returns null for unknown hash
✓ findCanonical returns null for blank hash
✓ vaultId-scoped lookup isolates vaults
✓ vaultId null falls back to cross-vault lookup
✓ tombstoned entries are excluded
```

### 7. RcloneJsonParsingTest (27 tests)

**File**: `app/src/test/java/onlasdan/gallery/sync/rclone/RcloneJsonParsingTest.kt`

Verifies JSON-based RPC error detection + list parsing + operation input formats.

```
# hasRpcError (5 tests)
✓ returns false for success response
✓ returns true for error field
✓ returns false for error in a value (F-SYNC-001 fix)
✓ returns true for invalid JSON
✓ returns true for empty string

# parseListResult (8 tests)
✓ parses single file
✓ parses multiple files
✓ handles filename with escaped quote (F-SYNC-013 fix)
✓ handles filename containing word error
✓ skips entries with empty Name
✓ returns empty for missing list array
✓ returns empty for invalid JSON
✓ defaults Size to 0 when missing

# IsDir + MimeType parsing (7 tests)
✓ captures IsDir true for directories
✓ captures IsDir false for files
✓ defaults IsDir to false when missing
✓ captures MimeType for files
✓ returns null MimeType for directories
✓ defaults MimeType to null when missing
✓ handles mixed files and directories

# RC operation input format (7 tests)
✓ mkdir input format is correct
✓ movefile input format is correct
✓ movedir input format is correct
✓ purge input format is correct
✓ hash input format is correct
✓ hashFile response parsing extracts hash correctly
✓ hashFile response with null hash returns null
```

---

## Test Patterns

### Pure Unit Test (no Android deps)

```kotlin
class KeyGenArgon2idTest {
    private val keyGen = KeyGen()

    @Test
    fun `Argon2id produces 32-byte key`() {
        val kek = keyGen.derivePasswordKeyEncryptionKey(...)
        assertEquals(32, kek.encoded.size)
    }
}
```

### Robolectric Test (Android SDK classes)

```kotlin
@RunWith(RobolectricTestRunner::class)
class SqlCipherMigrationHelperTest {
    private val app = RuntimeEnvironment.getApplication()

    @Test
    fun `Config flag is writable`() {
        val config = Config(app)
        config.sqlCipherMigrationDone = true
        assertTrue(config.sqlCipherMigrationDone)
    }
}
```

### Coroutine Test

```kotlin
class DedupTest {
    @Test
    fun `shouldSkip returns true for existing hash`() = runTest {
        coEvery { dao.findByHashAnyVault(hash) } returns entry
        assertTrue(dedup.shouldSkip(hash))
    }
}
```

### MockK Pattern

```kotlin
val dao = mockk<HashRegistryDao>()
coEvery { dao.findByHash("known") } returns entry
coEvery { dao.findByHash("unknown") } returns null
```

---

## Test Coverage Gaps

### Butuh Instrumented Test (device/emulator)

| Area | Reason |
|------|--------|
| SqlCipherMigrationHelper full flow | SQLCipher native lib tidak available di Robolectric |
| Room migration tests (v1→v17) | Butuh `MigrationTestHelper` + device |
| RcloneController integration | Butuh gomobile AAR + rclone backend |
| ChunkedGcmRandomAccessDataSource | Butuh ExoPlayer + real file I/O |
| Hilt DI graph | Butuh `@HiltAndroidTest` + device |

### Butuh Design Work

| Area | Reason |
|------|--------|
| RecoveryPhraseVaultProtectionHandler create+unlock round-trip | Butuh Bip39MnemonicGenerator + RecoveryPhraseStore mocks |
| HashRegistry multi-vault merge concurrent write | Butuh rclone mock + coroutine concurrency test |
| PhotoSyncWorker full upload flow | Butuh WorkManager test harness |

---

## Adding New Tests

### Pattern

1. Create test file di `app/src/test/java/onlasdan/gallery/<module>/`
2. Package harus match source package
3. Use `@RunWith(RobolectricTestRunner::class)` jika butuh Android SDK classes
4. Name test methods dengan backtick: `` `function name - condition - expected` ``
5. One assert per test method (preferably)
6. Use `runTest` untuk coroutine tests

### Example

```kotlin
@RunWith(RobolectricTestRunner::class)
class MyNewTest {
    @Test
    fun `myFunction with valid input returns expected result`() = runTest {
        val result = myFunction("input")
        assertEquals("expected", result)
    }
}
```

### Run + Verify

```bash
./gradlew :app:testFossDebugUnitTest --tests "onlasdan.gallery.mymodule.MyNewTest"
```
