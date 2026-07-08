# 06 — Database

> **File referensi**: `app/src/main/java/onlasdan/gallery/model/database/`, `app/src/main/java/onlasdan/gallery/encryption/data/`
> **Schema JSON**: `app/schemas/onlasdan.gallery.model.database.PhotoZDatabase/`

---

## Overview

PhotoZ menggunakan 2 database:

```
┌─────────────────────────────────────────────────────────┐
│  BootstrapDatabase (plaintext)                          │
│  File: photok_meta.db                                   │
│  Tables: vault_protection                               │
│  Open: always (no key required)                         │
│  Reads: VaultService.unlock iterates rows to find VMK   │
└─────────────────────────────────────────────────────────┘
                              ↓ unwrap wrappedVMK with VMK
                              ↓
┌─────────────────────────────────────────────────────────┐
│  PhotoZDatabase (SQLCipher-encrypted)                   │
│  File: photok.db                                        │
│  Tables: photo, album, album_photos_cross_ref,          │
│          sort, hash_registry                            │
│  Open: only when DB key available (Keystore)            │
│  Key: 32-byte random, backed by Android Keystore        │
│  Stays open across vault switches (multi-vault via      │
│  vault_id column, not via DB re-keying)                 │
└─────────────────────────────────────────────────────────┘
```

---

## Current Schema Version

```kotlin
private const val DATABASE_VERSION = 17
const val DATABASE_NAME = "photok.db"
```

---

## Entities

### Photo (table: `photo`)

20 columns (v17):

| Column | Type | Default | Since |
|--------|------|---------|-------|
| `photo_uuid` | TEXT (PK) | UUID | v2 |
| `fileName` | TEXT | — | v1 |
| `importedAt` | INTEGER | — | v1 |
| `lastModified` | INTEGER | NULL | v1 |
| `type` | TEXT (PhotoType) | — | v1 |
| `size` | INTEGER | 0 | v1 |
| `syncState` | TEXT | 'LOCAL_ONLY' | v7 |
| `relativePath` | TEXT | NULL | v8 |
| `content_hash` | TEXT | NULL | v9 |
| `album_path` | TEXT | NULL | v9 |
| `deleted_at` | INTEGER | 0 | v10 |
| `vault_id` | TEXT | NULL | v11 |
| `is_favorite` | INTEGER | 0 | v12 |
| `exif_date_taken` | INTEGER | NULL | v13 |
| `exif_gps_lat` | REAL | NULL | v13 |
| `exif_gps_lon` | REAL | NULL | v13 |
| `exif_camera` | TEXT | NULL | v13 |
| `ai_tags` | TEXT | NULL | v14 |
| `canonical_uuid` | TEXT | NULL | v15 |

**Symlink albums** (v15): jika `canonical_uuid` non-null, Photo row ini adalah symlink — references encrypted file owned by another Photo row.

### AlbumTable (table: `album`)

| Column | Type | Since |
|--------|------|-------|
| `uuid` | TEXT (PK) | — |
| `name` | TEXT | — |
| `modifiedAt` | INTEGER | — |
| `vault_id` | TEXT | v11 |

### AlbumPhotoCrossRefTable (table: `album_photos_cross_ref`)

| Column | Type | Since |
|--------|------|-------|
| `album_uuid` | TEXT | — |
| `photo_uuid` | TEXT | — |
| `linked_at` | INTEGER | — |
| `pinned` | INTEGER | — |

### SortTable (table: `sort`)

User's sort preference per context.

### HashRegistryEntry (table: `hash_registry`)

11 columns — local cache of remote dedup registry. See [05-sync-workflow.md](05-sync-workflow.md).

### VaultProtectionTable (BootstrapDatabase, table: `vault_protection`)

| Column | Type | Notes |
|--------|------|-------|
| `id` | TEXT (PK) | UUID |
| `type` | TEXT (VaultProtectionType) | Password / Biometric / RecoveryPhrase |
| `wrappedVMK` | BLOB | VMK wrapped dengan KEK |
| `params` | VaultProtectionParams | Gson-serialized (salt, iv, kdf, dll) |

---

## Migrations

### Auto-Migrations (v1 → v16)

Room auto-generates DDL untuk additive changes (new nullable columns, new tables):

```kotlin
@Database(
    entities = [...],
    version = DATABASE_VERSION,
    autoMigrations = [
        AutoMigration(from = 1, to = 2, spec = MigrationSpec1To2::class),
        AutoMigration(from = 2, to = 3),
        // ... v3→v4, v4→v5, ..., v14→v15
    ],
)
```

### MigrationSpec1To2

```kotlin
@DeleteColumn.Entries(DeleteColumn(tableName = "photo", columnName = "id"))
@RenameColumn.Entries(RenameColumn(tableName = "photo", fromColumnName = "uuid", toColumnName = "photo_uuid"))
class MigrationSpec1To2 : AutoMigrationSpec
```

### MIGRATION_15_16 (manual, no-op)

```kotlin
val MIGRATION_15_16 = object : Migration(15, 16) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // No-op. Data copy handled by SqlCipherMigrationHelper BEFORE Room opens.
        Timber.d("MIGRATION_15_16: no-op migration (data already copied by helper)")
    }
}
```

### MIGRATION_16_17 (F-ENC-014 — orphan table cleanup)

```kotlin
val MIGRATION_16_17 = object : Migration(16, 17) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS vault_protection")
        Timber.d("MIGRATION_16_17: dropped orphan vault_protection table from photok.db")
    }
}
```

The orphan `vault_protection` table was left by `sqlcipher_export()` copying ALL tables. Canonical home is BootstrapDatabase.

### Registration

```kotlin
// AppModule.providePhotoZDatabase()
Room.databaseBuilder(app, PhotoZDatabase::class.java, DATABASE_NAME)
    .openHelperFactory(factory)
    .addMigrations(MIGRATION_15_16, MIGRATION_16_17)
    .build()
```

---

## SQLCipher Integration

### SupportOpenHelperFactory

```kotlin
val passphrase = sqlCipherKeyProvider.getOrCreatePassphrase()
val factory = SupportOpenHelperFactory(passphrase, null, false)
Room.databaseBuilder(...)
    .openHelperFactory(factory)
    .build()
```

### Key Format

- 32-byte random key
- Backed by Android Keystore (alias `photoz-sqlcipher-v1`)
- StrongBox jika available, TEE fallback
- Passed ke SQLCipher via `x"<hex>"` raw-key format (no PBKDF2 — key already 256-bit random)

### Fallback (F-ENC-001)

Jika Keystore gagal:
1. Set `config.keystoreFallbackActive = true`
2. Delegate ke `FallbackSqlCipherKeyProvider` (plaintext SharedPreferences — NO security benefit)
3. Settings UI shows warning banner

---

## Migration: Plaintext → SQLCipher (v15 → v16)

### SqlCipherMigrationHelper

```kotlin
class SqlCipherMigrationHelper @Inject constructor(
    private val app: Context,
    private val config: Config,
    private val sqlCipherKeyProvider: SqlCipherKeyProvider,
    private val bootstrapDatabase: BootstrapDatabase,
)
```

### Flow

```
1. Check config.sqlCipherMigrationDone — skip if true
2. Check if photok.db exists — fresh install if not
3. Check if already SQLCipher-encrypted (magic header)
4. If plaintext:
   a. Open plaintext DB dengan empty passphrase
   b. Generate new encrypted file path
   c. ATTACH DATABASE 'new.db' KEY 'x"<passphrase_hex>"'
   d. SELECT sqlcipher_export('new') — copy schema + data
   e. Copy vault_protection rows ke BootstrapDatabase
   f. Delete old plaintext file
   g. Rename new → photok.db
   h. Set config.sqlCipherMigrationDone = true
```

### Error Handling (F-ENC-002)

Passphrase hex di-scrub dari error logging:

```kotlin
// Companion object
private val SQLCIPHER_PASSPHRASE_PATTERN = Regex(Char(39) + "x" + Char(34) + "[0-9a-f]+" + Char(34) + Char(39))

// Catch block
val safeMsg = e.message?.take(60)?.replace(SQLCIPHER_PASSPHRASE_PATTERN, "<REDACTED>")
Timber.e("SqlCipherMigration: migration failed (${e.javaClass.simpleName}: $safeMsg)")
```

---

## DAOs

### PhotoDao

```kotlin
@Dao
interface PhotoDao {
    @Query("SELECT * FROM photo WHERE deleted_at = 0 ORDER BY importedAt DESC")
    fun observeAllSorted(): Flow<List<Photo>>

    @Query("SELECT * FROM photo WHERE photo_uuid = :uuid")
    suspend fun get(uuid: String): Photo?

    @Insert
    suspend fun insert(photo: Photo)

    @Query("UPDATE photo SET content_hash = :hash WHERE photo_uuid = :uuid")
    suspend fun updateContentHash(uuid: String, hash: String)

    // ... trash, favorites, vault_id filter, dll
}
```

### HashRegistryDao (F-BACK-008)

```kotlin
@Dao
interface HashRegistryDao {
    // Vault-scoped lookup (prevents cross-vault UUID references)
    @Query("SELECT * FROM hash_registry WHERE content_hash = :hash AND vault_id = :vaultId AND deleted = 0 LIMIT 1")
    suspend fun findByHash(hash: String, vaultId: String): HashRegistryEntry?

    // Legacy cross-vault lookup (use sparingly)
    @Query("SELECT * FROM hash_registry WHERE content_hash = :hash AND deleted = 0 LIMIT 1")
    suspend fun findByHashAnyVault(hash: String): HashRegistryEntry?

    @Query("SELECT * FROM hash_registry WHERE deleted = 0 AND vault_id = :vaultId")
    suspend fun getAllForVault(vaultId: String): List<HashRegistryEntry>
}
```

### AlbumDao

```kotlin
@Dao
interface AlbumDao {
    @Insert
    suspend fun createAlbum(album: AlbumTable)

    @Query("INSERT OR IGNORE INTO album_photos_cross_ref (album_uuid, photo_uuid, linked_at) VALUES (:albumId, :photoId, :linkedAt)")
    protected abstract suspend fun internalLink(albumId: String, photoId: String, linkedAt: Long)
    // F-BACK-013: params reordered to match SQL column order
}
```

---

## Schema Export

Room exports schema JSON ke `app/schemas/` setiap build:

```
app/schemas/
├── onlasdan.gallery.model.database.PhotoZDatabase/
│   ├── 1.json
│   ├── 2.json
│   ├── ...
│   ├── 16.json
│   └── 17.json
└── onlasdan.gallery.encryption.data.BootstrapDatabase/
    └── 1.json
```

**Penting**: commit schema JSON files ke repo — dibutuhkan untuk AutoMigration generation + migration tests.

---

## Common Issues

### "Room cannot verify data integrity"

**Penyebab**: DB schema mismatch (misal dari downgrade).
**Fix**: Hapus app data atau run migration test.

### "database disk image is malformed"

**Penyebab**: SQLCipher key mismatch (wrong passphrase) atau corrupt DB.
**Fix**: Restore dari backup, atau factory reset.

### MIGRATION_15_16 failed

**Penyebab**: `SqlCipherMigrationHelper` gagal sebelum Room open.
**Fix**: Check `crash_log.txt` + `sync_log.txt` via `adb shell run-as onlasdan.gallery cat files/crash_log.txt`.
