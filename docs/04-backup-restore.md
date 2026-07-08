# 04 — Backup & Restore

> **File referensi**: `app/src/main/java/onlasdan/gallery/backup/`

---

## Overview

PhotoZ mendukung local backup (ZIP) dengan 5 format versions. Backup berisi:
- `meta.json` — metadata (photos, albums, refs, vault protection)
- `<uuid>.crypt` — encrypted photo/video files
- `<uuid>.crypt.tn` — encrypted thumbnails
- `<uuid>.crypt.vp` — encrypted video previews

```
┌─────────────────────────────────────────┐
│                backup.zip              │
├─────────────────────────────────────────┤
│ meta.json                              │
│   {                                    │
│     "wrappedVmk": String,              │  (V5 only)
│     "params": [VaultProtectionParams], │  (V5 only)
│     "photos": [PhotoBackup],           │
│     "albums": [AlbumBackup],           │
│     "albumPhotoRefs":                  │
│       [AlbumPhotoRefBackup],           │
│     "createdAt": Long,                 │
│     "backupVersion": Int               │
│   }                                    │
│ <uuid>.crypt                           │
│ <uuid>.crypt.tn                        │
│ <uuid>.crypt.vp                        │
└─────────────────────────────────────────┘
```

---

## Format Versions

| Version | Encryption | KDF | Notes |
|---------|------------|-----|-------|
| V1 | Legacy (BCrypt hash) | — | Plaintext password in meta.json |
| V2 | Legacy (BCrypt hash) | — | Same as V1, format cleanup |
| V3 | Legacy (BCrypt hash) | — | Same as V2, `LEGACY_BACKUP_VERSION = 3` |
| V4 | BCrypt + PBKDF2 | PBKDF2 | Salt read dari first file |
| V5 | wrappedVMK + params | Argon2id/PBKDF2 | **Current format** (`CURRENT_BACKUP_VERSION = 5`) |

### Dispatch

```kotlin
// RestoreBackupViewModel
when (metaData.backupVersion) {
    1, 2, 3 -> RestoreBackupV1/V2/V3
    4 -> RestoreBackupV4
    5 -> RestoreBackupV5
}

// UnlockBackupUseCase
when (metaData) {
    is V1, V2, V3 -> createSessionFromV1toV3(pwHash, password)
    is V4 -> createSessionFromV4(uri, pwHash, password)
    is V5 -> createSessionFromV5(password, metaData)
}
```

---

## PhotoBackup — Full Metadata Round-Trip (F-BACK-001)

**Sebelumnya** (bug): `PhotoBackup` hanya menyimpan 6 field:
```kotlin
data class PhotoBackup(
    val fileName: String,
    val importedAt: Long,
    val lastModified: Long?,
    val type: PhotoType,
    val size: Long,
    val uuid: String,
)
```

13 kolom post-V3 **hilang** saat backup → restore:
- `syncState`, `relativePath`, `contentHash`, `albumPath`
- `deletedAt`, `vaultId`, `isFavorite`
- `exifDateTaken`, `exifGpsLat`, `exifGpsLon`, `exifCamera`
- `aiTags`, `canonicalUuid`

**Sekarang** (F-BACK-001): semua 19 field di-round-trip:

```kotlin
data class PhotoBackup(
    val fileName: String,
    val importedAt: Long,
    val lastModified: Long?,
    val type: PhotoType,
    val size: Long,
    val uuid: String,
    // F-BACK-001: full metadata round-trip
    val syncState: String? = null,
    val relativePath: String? = null,
    val contentHash: String? = null,
    val albumPath: String? = null,
    val deletedAt: Long = 0L,
    val vaultId: String? = null,
    val isFavorite: Boolean = false,
    val exifDateTaken: Long? = null,
    val exifGpsLat: Double? = null,
    val exifGpsLon: Double? = null,
    val exifCamera: String? = null,
    val aiTags: String? = null,
    val canonicalUuid: String? = null,
)
```

### BackupMappers

```kotlin
fun Photo.toBackup(): PhotoBackup = PhotoBackup(
    // ... 6 original fields
    syncState = syncState.storageKey,
    relativePath = relativePath,
    contentHash = contentHash,
    // ... all 13 post-V3 fields
)

fun PhotoBackup.toDomain(): Photo = Photo(
    // ... 6 original fields
    syncState = syncState?.let { SyncState.fromStorageKey(it) } ?: SyncState.LOCAL_ONLY,
    relativePath = relativePath,
    // ... all 13 post-V3 fields
)
```

---

## V5 Restore — Vault Protection Restore (F-BACK-002)

**Sebelumnya** (bug): `RestoreBackupV5` hanya restore photo/album files + DB rows, **tidak** menulis source vault's `VaultProtection(Password)` row. Hasilnya: restore hanya "merge photos ke current vault", bukan "restore vault dari backup".

**Sekarang** (F-BACK-002): source `wrappedVMK` + `params` dari backup metadata ditulis sebagai new VaultProtection row:

```kotlin
// RestoreBackupV5.restore()
runCatching {
    vaultProtectionRepository.createProtection(
        VaultProtection(
            id = UUID.randomUUID().toString(),
            type = VaultProtectionType.Password,
            wrappedVMK = Base64.decode(metaData.wrappedVMK),
            params = metaData.params,
        ),
    )
}.onFailure { Timber.e(it, "F-BACK-002: failed to persist source vault protection row") }
```

User bisa unlock dengan password backup setelah restore (sebelumnya: tidak bisa unlock — vault brick).

---

## Backup Strategy

### BackupStrategyImpl (Default → V5)

```kotlin
class BackupStrategyImpl @Inject constructor(
    private val photoRepository: PhotoRepository,
    private val albumRepository: AlbumRepository,
    private val vaultService: VaultService,
    private val cryptoEngine: CryptoEngine,
    private val vaultFileStorage: VaultFileStorage,
    private val io: IO,
) : BackupStrategy {
    override suspend fun create(): Result<OutputStream> = ...
}
```

Emits V5 format dengan:
- `wrappedVMK` dari current session
- `params` dari current session's VaultProtectionParams
- All photos + albums + albumPhotoRefs

### LegacyBackupStrategyImpl (Legacy → V3)

Emits V3 format untuk backwards compatibility testing.

---

## Unlock Backup

### UnlockBackupUseCase

```kotlin
class UnlockBackupUseCase @Inject constructor(
    private val legacyEncryption: LegacyEncryption,
    private val io: IO,
    private val keyGen: KeyGen,
    private val passwordVaultProtectionHandler: VaultProtectionHandler<UnlockRequest.Password, CreateRequest.Password>,
)
```

### V5 Unlock Flow

```kotlin
private suspend fun createSessionFromV5(password: String, metaData: BackupMetaData.V5): Session {
    val wrappedVmk = Base64.decode(metaData.wrappedVMK)
    val vmk = passwordVaultProtectionHandler.unlock(
        UnlockRequest.Password(password),
        VaultProtection(
            id = "",
            type = VaultProtectionType.Password,
            wrappedVMK = wrappedVmk,
            params = metaData.params,
        ),
    )
    return VaultSession(vmk = vmk)
}
```

---

## Dead File Handling

Backup mungkin berisi files yang tidak ada di metadata (dead files dari versi lama). Restore skip entries yang tidak match metadata:

```kotlin
// F-BACK-014: use startsWith for exact prefix match (was: contains)
if (metaData.photos.none { ze.name.startsWith(it.uuid + ".") }) {
    Timber.i("Skipping dead file in backup: ${ze.name}")
    ze = stream.nextEntry
    continue
}
```

---

## Testing

### BackupMappersTest (13 tests)

Round-trip verification untuk semua 19 Photo fields:

```kotlin
@Test
fun `round-trip of photo with all fields populated preserves every field`() {
    val photo = Photo(/* all 19 fields populated */)
    val restored = photo.toBackup().toDomain()
    // assert all 19 fields match
}

@Test
fun `syncState deserialization falls back to LOCAL_ONLY for unknown keys`() { ... }

@Test
fun `round-trip handles nullable fields with null values`() { ... }
```

### Coverage Gaps (butuh instrumented test)

- V1-V5 restore dispatch (butuh full Room + SQLCipher stack)
- V5 wrappedVMK unwrap + decrypt round-trip (butuh device)
- MigrationSpec1To2 (butuh MigrationTestHelper)
- MIGRATION_15_16 no-op safety
- SqlCipherMigrationHelper v15→v16 flow (butuh SQLCipher native lib)
