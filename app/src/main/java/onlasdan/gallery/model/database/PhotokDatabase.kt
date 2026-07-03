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
import onlasdan.gallery.encryption.data.VaultProtectionDao
import onlasdan.gallery.encryption.data.VaultProtectionTable
import onlasdan.gallery.model.database.dao.AlbumDao
import onlasdan.gallery.model.database.dao.PhotoDao
import onlasdan.gallery.model.database.entity.AlbumTable
import onlasdan.gallery.model.database.entity.Photo
import onlasdan.gallery.model.database.ref.AlbumPhotoCrossRefTable
import onlasdan.gallery.sort.data.db.SortDao
import onlasdan.gallery.sort.data.db.model.SortTable
import onlasdan.gallery.sync.work.HashRegistryDao
import onlasdan.gallery.sync.work.HashRegistryEntry

private const val DATABASE_VERSION = 10
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
        VaultProtectionTable::class,
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
    ]
)
@TypeConverters(Converters::class)
abstract class PhotoZDatabase : RoomDatabase() {

    /**
     * Get the data access object for [Photo]
     */
    abstract fun getPhotoDao(): PhotoDao

    abstract fun getAlbumDao(): AlbumDao
    abstract fun getSortDao(): SortDao
    abstract fun getVaultProtectionDao(): VaultProtectionDao

    /**
     * DAO for the local cache of the dedup registry ([HashRegistryEntry]).
     *
     * @since v9 dedup + encrypted GCM registry
     */
    abstract fun getHashRegistryDao(): HashRegistryDao
}

@DeleteColumn.Entries(
    DeleteColumn(
        tableName = "photo",
        columnName = "id"
    ),
)
@RenameColumn.Entries(
    RenameColumn(
        tableName = "photo",
        fromColumnName = "uuid",
        toColumnName = "photo_uuid",
    )
)
class MigrationSpec1To2 : AutoMigrationSpec