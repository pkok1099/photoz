# 03 — Encryption System

> **File referensi**: `ENCRYPTION.md` (root), `app/src/main/java/onlasdan/gallery/encryption/`
> **Baca `ENCRYPTION.md` sebelum mengubah kode crypto.**

---

## Overview

PhotoZ menggunakan layered encryption:

```
┌─────────────────────────────────────────────────────────┐
│              User Password / Biometric / Phrase          │
│                          │                               │
│                    KEK derivation                        │
│              (Argon2id atau PBKDF2)                      │
│                          │                               │
│                    KEK (256-bit)                         │
│                          │                               │
│              wraps VMK (AES-CBC/GCM)                     │
│                          │                               │
│                    VMK (256-bit)                         │
│                          │                               │
│         encrypts media files (AES-CBC/GCM/chunked-GCM)   │
│         encrypts DB metadata (SQLCipher via Keystore)    │
└─────────────────────────────────────────────────────────┘
```

---

## Key Hierarchy

### VMK (Vault Master Key)

- Random 256-bit AES key
- Dibuat sekali saat vault creation
- Membungkus semua media files
- **Never** disimpan plaintext ke disk

### KEK (Key Encryption Key)

- Derived dari password (PBKDF2 atau Argon2id)
- Atau derived dari biometric (Android Keystore)
- Membungkus VMK
- Stored sebagai `wrappedVMK` di `VaultProtectionTable`

### DB Key (SQLCipher)

- Random 256-bit key
- Backed by Android Keystore (StrongBox jika available, TEE fallback)
- **Tidak** wrapped per-vault (Keystore-persisted, independent dari VMK)
- Stored di `BootstrapDatabase` sebagai `keystoreFallbackActive` flag (F-ENC-001)

---

## Encryption Formats

PhotoZ mendukung 4 format file (version byte dispatch):

### v1 (0x01) — Legacy

```
[0x01][SALT(16)][IV(16)][CBC ciphertext]
```
- AES-CBC/PKCS7Padding
- PBKDF2 KEK (100k iterations)
- Hanya untuk migration dari Photok 1.x

### v2 (0x02) — CBC

```
[0x02][IV(16)][CBC ciphertext]
```
- AES-CBC/PKCS7Padding
- Default untuk photos (non-video)

### v3 (0x03) — GCM (single-stream)

```
[0x03][IV(12)][GCM ciphertext + 16-byte tag]
```
- AES-GCM/NoPadding
- Single-stream (harus decrypt seluruh file)

### v4 (0x04) — Chunked GCM (TODO #2)

```
[0x04][chunk_size(4, BE)][total_plaintext_size(8, BE)]
[chunk_0: nonce(12) + ciphertext(chunk_size) + tag(16)]
[chunk_1: nonce(12) + ciphertext(chunk_size) + tag(16)]
...
[chunk_last: nonce(12) + ciphertext(remaining) + tag(16)]
```
- AES-GCM per chunk
- **Random access** — seek to chunk N, decrypt only that chunk
- **Streaming** — ExoPlayer bisa mulai playback setelah chunk pertama
- **Per-chunk integrity** — tampering chunk N terdeteksi independen
- Default untuk video (progressive streaming)

---

## KDF (Key Derivation Function)

### Argon2id (default, TODO #3)

```kotlin
// KeyGen.kt
val kdf = Kdf.Argon2id
val kdfIterations = 3                    // time cost
val argon2MemoryKB = 65536               // 64 MB memory cost
val parallelism = 1                      // mobile (no benefit from threading)
```

**IV Encoding** (F-ENC-004/005): untuk Argon2id, field `iv` di `VaultProtectionParams` menyimpan:
```
[4-byte big-endian memory cost][16-byte AES wrapping IV]
```
Base64-encoded bersama. Ini reuse column tanpa schema change.

### PBKDF2 (legacy, untuk old vaults)

```kotlin
val kdf = Kdf.PBKDF2WithHmacSHA256
val kdfIterations = 100_000
```

**Dispatch**: `KeyGen.derivePasswordKeyEncryptionKey()` dispatch via `when(kdf)` — Argon2id atau PBKDF2. Old vaults tetap PBKDF2 (backwards compatible unlock).

### Konsistensi (F-ENC-003/004/005)

Semua protection handler sekarang pakai Argon2id untuk new vaults:
- `PasswordVaultProtectionHandler.create()` — Argon2id ✓
- `PasswordVaultProtectionHandler.wrapExistingVmk()` — Argon2id ✓
- `RecoveryPhraseVaultProtectionHandler.create()` — Argon2id ✓ (F-ENC-004)
- `RecoveryPhraseVaultProtectionHandler.unlock()` — decode Argon2id IV prefix ✓ (F-ENC-005)
- `VaultService.createPasswordProtectionFromSession()` — Argon2id ✓ (F-ENC-003)

---

## Vault Protection Handlers

### Strategy Pattern

```kotlin
interface VaultProtectionHandler<UnlockReq, CreateReq> {
    suspend fun unlock(request: UnlockReq, protection: VaultProtection): SecretKey
    suspend fun create(request: CreateReq): VaultProtection
    suspend fun canMigrate(): Boolean
    suspend fun migrate(request: UnlockReq): VaultProtection
    suspend fun reset()
}
```

### Implementations

| Handler | Type | KDF | Use Case |
|---------|------|-----|----------|
| `PasswordVaultProtectionHandler` | Password | Argon2id | Setup vault dengan password |
| `BiometricVaultProtectionHandler` | Biometric | Keystore | Unlock dengan fingerprint/face |
| `RecoveryPhraseVaultProtectionHandler` | RecoveryPhrase | Argon2id | 12/24-word BIP39 phrase |

### Multi-Vault (M7)

`vault_protection` table memungkinkan multiple Password rows (satu per vault). Unlock flow:
1. User enter password
2. Iterate all Password rows
3. Try unwrap each dengan derived KEK
4. First success → identifies which vault unlocked

```kotlin
// VaultService.unlockMultiVaultPassword()
for (protection in passwordProtections) {
    try {
        return passwordProtectionHandler.unlock(request, protection)
    } catch (e: AEADBadTagException) {
        // wrong password for THIS row — try next (F-ENC-019)
    } catch (e: BadPaddingException) {
        // CBC padding failure — try next
    }
    // Other exceptions propagate as real errors (not "wrong password")
}
```

---

## Two-Layer Key Escrow

Fresh-install recovery via 2 encrypted artifacts di remote:

### Layer 1: `recovery-phrase.json.crypt`

- VMK wrapped dengan phrase-derived KEK
- Outer AES-256-GCM dengan VMK (sembunyikan struktur JSON)

### Layer 2: `wrapped-phrase.json.crypt`

- Recovery phrase wrapped dengan password-derived KEK
- Outer AES-256-GCM dengan VMK

### Recovery Flow

```
Fresh install → enter password →
  Layer 2 unwrap (password → phrase) →
    Layer 1 unwrap (phrase → VMK) →
      unlock vault
```

Atau:

```
Fresh install → enter recovery phrase →
  Layer 1 unwrap (phrase → VMK) →
    unlock vault
```

---

## SQLCipher (TODO #6)

### At-Rest DB Encryption

Main vault DB (`photok.db`) diencrypt dengan SQLCipher 4.9.0:

```kotlin
// AppModule.providePhotoZDatabase()
val passphrase = sqlCipherKeyProvider.getOrCreatePassphrase()
val factory = SupportOpenHelperFactory(passphrase, null, false)
Room.databaseBuilder(app, PhotoZDatabase::class.java, DATABASE_NAME)
    .openHelperFactory(factory)
    .addMigrations(MIGRATION_15_16, MIGRATION_16_17)
    .build()
```

### BootstrapDatabase (plaintext)

`photok_meta.db` — plaintext, hanya berisi `vault_protection` table. Harus readable sebelum encrypted DB unlock (chicken-and-egg).

### SqlCipherKeyProvider

```kotlin
class SqlCipherKeyProvider @Inject constructor(
    private val app: Context,
    private val fallbackProvider: FallbackSqlCipherKeyProvider,
    private val config: Config,
)
```

**Keystore flow**:
1. Coba ambil key dari Android Keystore (alias `photoz-sqlcipher-v1`)
2. StrongBox jika available, TEE fallback
3. Jika Keystore gagal → **F-ENC-001**: set `config.keystoreFallbackActive = true` + delegate ke `FallbackSqlCipherKeyProvider` (plaintext SharedPreferences — NO security benefit)

### Migration v15 → v16

Plaintext → SQLCipher via `sqlcipher_export()`:

```kotlin
// SqlCipherMigrationHelper.migrateIfNecessary()
1. Open plaintext DB dengan empty passphrase
2. ATTACH DATABASE 'new.db' KEY 'x"<passphrase_hex>"'
3. SELECT sqlcipher_export('new')  — copy schema + data
4. Copy vault_protection rows ke BootstrapDatabase
5. Replace old file
6. Set config.sqlCipherMigrationDone = true
```

**F-ENC-002**: Passphrase hex di-scrub dari error logging (was: leak via logcat).

---

## Chunked GCM Streaming

### OutputStream (encrypt)

```kotlin
class ChunkedGcmOutputStream(
    output: OutputStream,
    vmk: SecretKey,
    chunkSize: Int = CHUNK_SIZE,  // 1 MB default
) : OutputStream()
```

- Write header: `[0x04][chunk_size(4)][total_plaintext_size(8)]`
- Buffer plaintext sampai `chunkSize`
- Flush: generate nonce(12) + encrypt(chunk + tag)
- On close: **F-ENC-016** — patch `total_plaintext_size` di header jika output adalah FileOutputStream

### InputStream (decrypt, sequential)

```kotlin
class ChunkedGcmInputStream(
    input: InputStream,
    vmk: SecretKey,
) : InputStream()
```

- Read header → get `chunkSize` + `totalPlaintextSize`
- Load chunk: read nonce + ciphertext + tag → decrypt
- **F-ENC-011**: `AEADBadTagException` di-throw sebagai IOException (was: silent swallow)

### RandomAccessDataSource (decrypt, seekable)

```kotlin
@UnstableApi
class ChunkedGcmRandomAccessDataSource(
    sessionRepository: SessionRepository,
    availableBytesProvider: (Uri) -> Long,
    downloadCompleteProvider: (Uri) -> Boolean,
) : DataSource
```

- Untuk ExoPlayer video streaming
- Seek to chunk N: compute file offset, read only that chunk
- **F-ENC-006**: validate `chunkSize` (reject 0 dan > 100×CHUNK_SIZE)
- **F-ENC-007**: full-read loop untuk partial reads during progressive download
- **F-ENC-008**: try/finally untuk channel lifecycle

---

## Typed Exceptions (F-ENC-024)

```kotlin
class InvalidVersionByteException(version: Int) : IOException(...)
class CorruptHeaderException(message: String, cause: Throwable? = null) : IOException(...)
class UnsupportedAlgorithmException(alg: String) : IOException(...)
```

`CbcCryptoEngine.createEncryptStream` / `createDecryptStream` throw typed exceptions (was: return null — ambiguous).

---

## VMK Zeroing (TODO #7)

```kotlin
// SessionRepositoryImpl
fun reset() {
    session?.let { Destroyable.destroy(it.vmk) }
    session = null
}
```

VMK bytes di-destroy explicitly (Kotlin/JVM GC tidak guarantee kapan clean).

---

## Security Checklist

- [x] No hardcoded IVs
- [x] No ECB mode
- [x] No key material in log output (F-ENC-002 scrub)
- [x] Argon2id untuk semua new vaults (F-ENC-003/004/005)
- [x] GCM auth tag verification (F-ENC-011 — throw, don't swallow)
- [x] SQLCipher Keystore fallback UI warning (F-ENC-001)
- [x] VMK zeroing on lock (TODO #7)
- [x] FLAG_SECURE enforcement (TODO #8)
- [x] StrongBox → TEE fallback (TODO #4)
