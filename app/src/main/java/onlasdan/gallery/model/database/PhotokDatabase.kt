/*
 *   Copyright 2020–2026 PhotoZ
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package onlasdan.gallery.model.database

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.DeleteColumn
import androidx.room.RenameColumn
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.AutoMigrationSpec
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import onlasdan.gallery.model.database.dao.AlbumDao
import onlasdan.gallery.model.database.dao.PhotoDao
import onlasdan.gallery.model.database.entity.AlbumTable
import onlasdan.gallery.model.database.entity.Photo
import onlasdan.gallery.model.database.ref.AlbumPhotoCrossRefTable
import onlasdan.gallery.sort.data.db.SortDao
import onlasdan.gallery.sort.data.db.model.SortTable
import onlasdan.gallery.sync.work.HashRegistryDao
import onlasdan.gallery.sync.work.HashRegistryEntry
import timber.log.Timber

private const val DATABASE_VERSION = 17
const val DATABASE_NAME = "photok.db"

/**
 * Abstract Room Database.
 *
 * @since 1.0.0
 * @author PhotoZ
 */
@Database(
	entities = [
		Photo::class,
		AlbumTable::class,
		AlbumPhotoCrossRefTable::class,
		SortTable::class,
		// @since v16 — Sprint 3 / SQLCipher
		// VaultProtectionTable moved to the plaintext BootstrapDatabase
		// (onlasdan.gallery.encryption.data.BootstrapDatabase). The
		// bootstrap DB must be readable BEFORE the encrypted DB can be
		// opened (chicken-and-egg: SQLCipher key is wrapped per-vault
		// with the VMK; to find the right VMK we must read vault_protection
		// rows; to read those rows we need a DB open…). The bootstrap DB
		// is the always-readable escape hatch.
		//
		// The vault_protection table still EXISTS in the photok.db file
		// (left as an orphan by the manual v15→v16 Migration — see
		// [MIGRATION_15_16]). It's just no longer managed by Room. The
		// orphan table is harmless; a future cleanup task can DROP it.
		// @since v9 dedup + encrypted GCM registry — local cache of the remote
		// registry.json.crypt (one row per content-hash). Fully rebuilt from
		// the remote on every successful login by HashRegistry.downloadAndCache.
		HashRegistryEntry::class,
	],
	version = DATABASE_VERSION,
	autoMigrations = [
		AutoMigration(
			from = 1,
			to = 2,
			spec = MigrationSpec1To2::class,
		),
		AutoMigration(
			from = 2,
			to = 3,
		),
		AutoMigration(
			from = 3,
			to = 4,
		),
		AutoMigration(
			from = 4,
			to = 5,
		),
		AutoMigration(
			from = 5,
			to = 6,
		),
		// v6 → v7: add `syncState TEXT NOT NULL DEFAULT 'LOCAL_ONLY'` to `photo`.
		// @since PR1 sync feature
		AutoMigration(
			from = 6,
			to = 7,
		),
		// v7 → v8: add `relativePath TEXT DEFAULT NULL` to `photo`.
		// Nullable column with default NULL — Room auto-generates the ALTER TABLE
		// ADD COLUMN statement; no AutoMigrationSpec needed.
		// @since v8 path-consistency metadata sidecar
		AutoMigration(
			from = 7,
			to = 8,
		),
		// v8 → v9: dedup + encrypted GCM registry.
		//   1. Add `content_hash TEXT DEFAULT NULL` to `photo` (nullable — existing
		//      photos imported before v9 simply have no hash; the upload worker
		//      treats null hash as "no dedup, upload normally").
		//   2. Add `album_path TEXT DEFAULT NULL` to `photo` (nullable — same
		//      reasoning).
		//   3. Add the new `hash_registry` table (entity HashRegistryEntry) for
		//      the local cache of the encrypted remote registry.
		// All three changes are additive (new nullable columns + new table) —
		// Room auto-generates the DDL; no AutoMigrationSpec needed.
		// @since v9 dedup + encrypted GCM registry
		AutoMigration(
			from = 8,
			to = 9,
		),
		// v9 → v10: recycle bin / soft delete.
		//   Add `deleted_at INTEGER NOT NULL DEFAULT 0` to `photo`. 0 = live,
		//   non-zero = epoch-ms the user moved the photo to the Trash. All
		//   gallery / album queries now filter `WHERE deleted_at = 0`; the
		//   Trash screen queries `WHERE deleted_at > 0`. Existing photos get
		//   the default 0 on migration (they were all live).
		// Additive (new column with constant default) — Room auto-generates
		// the ALTER TABLE ADD COLUMN statement; no AutoMigrationSpec needed.
		// @since v10 recycle bin
		AutoMigration(
			from = 9,
			to = 10,
		),
		// v10 → v11: Sprint 2 / M7 multi-vault.
		//   1. Add `vault_id TEXT DEFAULT NULL` to `photo` (nullable for backfill).
		//   2. Add `vault_id TEXT DEFAULT NULL` to `album` (nullable for backfill).
		//   3. Add `vault_id TEXT DEFAULT NULL` to `hash_registry` (nullable for backfill).
		//   4. DROP the unique index on `vault_protection.type` — multiple Password
		//      rows are now allowed (one per vault).
		//
		// Steps 1-3 are additive (new nullable columns) — Room handles via AutoMigration.
		// Step 4 (drop index) CANNOT be done by AutoMigration alone — it requires
		// a manual Migration registered alongside the AutoMigration. The manual
		// migration runs FIRST (Room executes manual migrations matching the
		// (startVersion, endVersion) range before AutoMigrations), then the
		// AutoMigration runs to add the new columns.
		//
		// Backfill (UPDATE rows WHERE vault_id IS NULL) is done lazily at first
		// unlock — see [onlasdan.gallery.encryption.domain.VaultIdBackfillUseCase].
		//
		// @since v11 — Sprint 2 / M7 multi-vault
		AutoMigration(
			from = 10,
			to = 11,
		),
		// v11 → v12: Sprint 4 / M2 favorites.
		//   Add `is_favorite INTEGER NOT NULL DEFAULT 0` to `photo`. 0 = regular,
		//   1 = user-marked favorite. Existing photos get the default 0 on
		//   migration (they were all non-favorites).
		// Additive (new column with constant default) — Room auto-generates
		// the ALTER TABLE ADD COLUMN statement; no AutoMigrationSpec needed.
		// @since v12 — Sprint 4 / M2 favorites
		AutoMigration(
			from = 11,
			to = 12,
		),
		// v12 → v13: Sprint 6 / M4 EXIF search.
		//   Add 4 nullable columns to `photo`:
		//     - exif_date_taken (Long, epoch-ms)
		//     - exif_gps_lat (Double, decimal degrees)
		//     - exif_gps_lon (Double, decimal degrees)
		//     - exif_camera (String, "Make Model" concatenated)
		//   All nullable with default NULL — existing photos have no EXIF
		//   (we don't backfill from already-encrypted files because that
		//   would require decrypting every photo on migration; the user
		//   can re-import if they want EXIF populated for old photos, or
		//   we add a "Re-extract EXIF" button in Settings as a future
		//   enhancement).
		// Additive (new nullable columns) — Room auto-generates the DDL.
		// @since v13 — Sprint 6 / M4 EXIF search
		AutoMigration(
			from = 12,
			to = 13,
		),
		// v13 → v14: Sprint 9 / L6 on-device semantic search.
		//   Add `ai_tags TEXT DEFAULT NULL` to `photo`. Stores comma-separated
		//   AI-generated tags (e.g. "beach,sunset,ocean") from the on-device
		//   ML model. NULL when semantic search is disabled or model hasn't
		//   run yet. Existing photos have NULL (no backfill — the model runs
		//   on new imports only; a "Re-tag all photos" button is a future
		//   enhancement).
		// Additive (new nullable column) — Room auto-generates the DDL.
		// @since v14 — Sprint 9 / L6 semantic search
		AutoMigration(
			from = 13,
			to = 14,
		),
		// v14 → v15: Sprint 10+ / M10 Part 3 — Symlink album.
		//   Add `canonical_uuid TEXT DEFAULT NULL` to `photo`.
		//   When non-null, the Photo row is a symlink referencing the
		//   canonical row's encrypted file. When null, the row owns its file.
		//   Existing photos get NULL (they're all canonical — no symlinks
		//   existed before this schema change).
		//   Additive (new nullable column) — Room auto-generates the DDL.
		// @since v15 — Sprint 10+ / M10 Part 3 symlink album
		AutoMigration(
			from = 14,
			to = 15,
		),
		// v15 → v16: Sprint 3 / (roadmap #6) — SQLCipher at-rest encryption.
		//   No schema change to the entities remaining in [PhotoZDatabase]
		//   (Photo / AlbumTable / AlbumPhotoCrossRefTable / SortTable /
		//   HashRegistryEntry). The ONLY change is that VaultProtectionTable
		//   is REMOVED from this database's entity list — it now lives in
		//   the plaintext BootstrapDatabase (see onlasdan.gallery.encryption.data.BootstrapDatabase).
		//
		//   AutoMigration CANNOT handle entity removal (it requires a
		//   @DeleteTable spec, but we don't actually want to DROP the
		//   table — the migration flow reads from it to copy rows into
		//   the new bootstrap DB). Instead, a manual Migration
		//   [MIGRATION_15_16] is registered alongside the AutoMigrations.
		//   Room executes manual migrations BEFORE AutoMigrations for the
		//   same version range, so MIGRATION_15_16 runs first as a no-op
		//   (the orphan vault_protection table is left in place; the
		//   SchemaMigration flow copies rows out separately at first
		//   unlock — see SqlCipherMigrationUseCase).
		//
		//   The encrypted DB is opened via SupportOpenHelperFactory with
		//   the SQLCipher passphrase sourced from Android Keystore (see
		//   onlasdan.gallery.encryption.data.SqlCipherKeyProvider). The
		//   v15→v16 migration runs the FIRST time the user opens the app
		//   after upgrading; the migration helper (SqlCipherMigrationHelper)
		//   uses sqlcipher_export() to copy the plaintext DB into a new
		//   encrypted file BEFORE Room tries to open it with the key.
		//
		//   IMPORTANT: This migration is registered manually in
		//   [onlasdan.gallery.di.AppModule.providePhotoZDatabase] via
		//   `.addMigrations(MIGRATION_15_16)` — NOT via the autoMigrations
		//   array (because Room's AutoMigration code generator can't
		//   produce a migration for an entity removal without a
		//   @DeleteTable spec, which we explicitly don't want).
		// @since v16 — Sprint 3 / SQLCipher
	],
)
@TypeConverters(Converters::class)
abstract class PhotoZDatabase : RoomDatabase() {
	/**
	 * Get the data access object for [Photo]
	 */
	abstract fun getPhotoDao(): PhotoDao

	abstract fun getAlbumDao(): AlbumDao

	abstract fun getSortDao(): SortDao

	/**
	 * @since v16 — Sprint 3 / SQLCipher
	 * VaultProtectionDao moved to [onlasdan.gallery.encryption.data.BootstrapDatabase].
	 * Get it via Hilt injection (`@Inject vaultProtectionDao: VaultProtectionDao`)
	 * — see [onlasdan.gallery.di.AppModule.provideVaultProtectionDao].
	 */

	// DAO for the local cache of the dedup registry (HashRegistryEntry).
	// @since v9 dedup + encrypted GCM registry
	abstract fun getHashRegistryDao(): HashRegistryDao
}

@DeleteColumn.Entries(
	DeleteColumn(
		tableName = "photo",
		columnName = "id",
	),
)
@RenameColumn.Entries(
	RenameColumn(
		tableName = "photo",
		fromColumnName = "uuid",
		toColumnName = "photo_uuid",
	),
)
class MigrationSpec1To2 : AutoMigrationSpec

/**
 * Manual migration v15 → v16: SQLCipher activation.
 *
 * This migration is a NO-OP at the SQL level — the actual data copy
 * from plaintext to encrypted is handled by
 * [onlasdan.gallery.encryption.data.SqlCipherMigrationHelper] using
 * `sqlcipher_export()` BEFORE Room opens the DB.
 *
 * By the time this migration callback runs, the DB is already
 * SQLCipher-encrypted AND contains all the user's data (copied from
 * the v15 plaintext file). The only remaining task is to advance the
 * `user_version` pragma from 15 to 16 — Room does this automatically
 * after `migrate()` returns.
 *
 * ## Why this exists
 *
 * Room's AutoMigration CANNOT handle entity removal (we removed
 * `VaultProtectionTable` from this database's entity list because it
 * moved to [onlasdan.gallery.encryption.data.BootstrapDatabase]). A
 * manual Migration stub is the only way to advance the version without
 * Room's auto-generator trying to DROP the table (which would lose the
 * orphan data we want to copy to the bootstrap DB separately).
 *
 * The orphan `vault_protection` table is harmless — Room ignores tables
 * that aren't in its entity list. A future v17 cleanup task can DROP it.
 *
 * @since v16 — Sprint 3 / SQLCipher
 */
val MIGRATION_15_16 =
	object : Migration(15, 16) {
		override fun migrate(db: SupportSQLiteDatabase) {
			// No-op. Data copy was handled by SqlCipherMigrationHelper before
			// Room opened the DB. We just let Room advance the user_version
			// to 16 by returning successfully.
			Timber.d("MIGRATION_15_16: no-op migration (data already copied by helper)")
		}
	}

/**
 * Manual migration v16 → v17: DROP orphan `vault_protection` table.
 *
 * F-ENC-014: The v15→v16 migration used `sqlcipher_export('new')` which copies
 * ALL tables from the plaintext DB to the encrypted DB — including `vault_protection`.
 * The rows were ALSO copied to the plaintext BootstrapDatabase (the canonical
 * location). The copy left in `photok.db` is an orphan: Room ignores it (not in
 * the entity list), but it wastes space and confuses DB browsers.
 *
 * This migration drops the orphan table. Safe because:
 *  - The canonical `vault_protection` data lives in BootstrapDatabase (photok_meta.db)
 *  - Room never queries the orphan in photok.db (no entity mapping)
 *  - No code reads from this table via raw SQL
 *
 * @since v17 — F-ENC-014 orphan table cleanup
 */
val MIGRATION_16_17 =
	object : Migration(16, 17) {
		override fun migrate(db: SupportSQLiteDatabase) {
			db.execSQL("DROP TABLE IF EXISTS vault_protection")
			Timber.d("MIGRATION_16_17: dropped orphan vault_protection table from photok.db")
		}
	}
