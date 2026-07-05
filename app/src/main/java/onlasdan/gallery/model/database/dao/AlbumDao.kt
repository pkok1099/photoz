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

package onlasdan.gallery.model.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.MapColumn
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import onlasdan.gallery.model.database.entity.AlbumTable
import onlasdan.gallery.model.database.entity.Photo
import onlasdan.gallery.model.database.ref.AlbumPhotoCrossRefTable
import onlasdan.gallery.model.database.ref.AlbumWithPhotos
import onlasdan.gallery.sort.domain.Sort
import org.intellij.lang.annotations.Language

@Language("roomsql")
const val SELECT_ALL_ALBUMS_QUERY = """
    SELECT * FROM album
    WHERE vault_id = :vaultId OR vault_id IS NULL
    ORDER BY modified_at DESC
"""

@Dao
abstract class AlbumDao {
	@Insert(onConflict = OnConflictStrategy.REPLACE)
	abstract suspend fun insert(album: AlbumTable): Long

	@Delete
	abstract suspend fun delete(album: AlbumTable): Int

	@Query("DELETE FROM album")
	abstract suspend fun deleteAll()

	@Query(SELECT_ALL_ALBUMS_QUERY)
	abstract suspend fun getAllAlbums(vaultId: String): List<AlbumTable>

	@Query(SELECT_ALL_ALBUMS_QUERY)
	abstract fun observeAllAlbums(vaultId: String): Flow<List<AlbumTable>>

	@Query("SELECT * FROM album WHERE album_uuid = :uuid")
	abstract fun observeAlbum(uuid: String): Flow<AlbumTable?>

	@Query("SELECT * FROM album WHERE album_uuid = :uuid")
	abstract suspend fun getAlbum(uuid: String): AlbumTable?

	/**
	 * Find an album by its (case-sensitive) name, scoped to the current vault.
	 * Used by the auto-album feature (Bug 5 fix) to look up an existing album
	 * whose name matches the imported photo's `albumPath` before deciding
	 * whether to create a new one — without this, repeated imports from the
	 * same folder would each spawn a new album instead of accumulating into
	 * one.
	 *
	 * Sprint 2 / M7: scoped to the current vault — different vaults may have
	 * albums with the same name without colliding.
	 *
	 * @since Bug 5 fix — auto-create albums from folder path
	 */
	@Query("SELECT * FROM album WHERE name = :name AND (vault_id = :vaultId OR vault_id IS NULL) LIMIT 1")
	abstract suspend fun getByName(
		name: String,
		vaultId: String,
	): AlbumTable?

	@Query("SELECT photo_uuid, linked_at FROM album_photos_cross_ref WHERE photo_uuid in (:photoUUIDs)")
	abstract suspend fun getLinkedAtFor(
		photoUUIDs: List<String>,
	): Map<
		@MapColumn(columnName = "photo_uuid")
		String,
		@MapColumn(columnName = "linked_at")
		Long,
	>

	@Query("INSERT OR IGNORE INTO album_photos_cross_ref (album_uuid, photo_uuid, linked_at) VALUES (:albumId, :photoId, :linkedAt)")
	protected abstract suspend fun internalLink(
		photoId: String,
		albumId: String,
		linkedAt: Long,
	)

	@Query("UPDATE album SET modified_at = :modifiedAt WHERE album_uuid = :uuid")
	abstract suspend fun updateModifiedAt(
		uuid: String,
		modifiedAt: Long,
	)

	@Transaction
	open suspend fun link(
		photoId: String,
		albumId: String,
		linkedAt: Long,
	) {
		internalLink(photoId, albumId, linkedAt)
		updateModifiedAt(albumId, linkedAt)
	}

	@Transaction
	open suspend fun link(
		photoUUIDs: List<String>,
		albumUUID: String,
	) {
		val linkedAt = System.currentTimeMillis()
		photoUUIDs.forEach {
			link(it, albumUUID, linkedAt)
		}
	}

	@Insert(onConflict = OnConflictStrategy.IGNORE)
	abstract suspend fun insert(ref: AlbumPhotoCrossRefTable)

	@Query("DELETE FROM album_photos_cross_ref WHERE album_uuid = :albumUUID AND photo_uuid IN (:photoUUIDs)")
	abstract suspend fun unlink(
		photoUUIDs: List<String>,
		albumUUID: String,
	)

	@Query("DELETE FROM album_photos_cross_ref WHERE photo_uuid = :photoUUID")
	abstract suspend fun unlink(photoUUID: String)

	@Query("DELETE FROM album_photos_cross_ref")
	abstract suspend fun unlinkAll()

	@Query("UPDATE album SET name = :newName WHERE album_uuid = :albumUUID")
	abstract suspend fun renameAlbum(
		albumUUID: String,
		newName: String,
	)

	@Query("DELETE FROM album_photos_cross_ref WHERE album_uuid = :albumId")
	abstract suspend fun removeAllPhotosFromAlbum(albumId: String)

	@Transaction
	open suspend fun unlinkAndDeleteAlbum(album: AlbumTable): Int {
		removeAllPhotosFromAlbum(album.uuid)
		return delete(album)
	}

	@Query("SELECT * FROM album_photos_cross_ref")
	abstract suspend fun getAllAlbumPhotoRefs(): List<AlbumPhotoCrossRefTable>

	@Query("SELECT photo_uuid FROM album_photos_cross_ref WHERE album_uuid = :albumUUID AND pinned = 1")
	abstract fun observePinnedPhotoUUIDs(albumUUID: String): Flow<List<String>>

	@Query("UPDATE album_photos_cross_ref SET pinned = :pinned WHERE album_uuid = :albumUUID AND photo_uuid IN (:photoUUIDs)")
	abstract suspend fun updatePinned(
		photoUUIDs: List<String>,
		albumUUID: String,
		pinned: Boolean,
	)

	// Sorting

	open fun observeAlbumWithPhotos(
		uuid: String,
		sort: Sort,
	): Flow<AlbumWithPhotos?> {
		val query = createSortedPhotosQuery(uuid, sort)

		return combine(
			observeAlbum(uuid),
			observePhotosForAlbum(query),
		) { album, photos ->
			album ?: return@combine null
			AlbumWithPhotos(album, photos)
		}
	}

	@RawQuery(observedEntities = [Photo::class, AlbumPhotoCrossRefTable::class])
	abstract fun observePhotosForAlbum(query: SupportSQLiteQuery): Flow<List<Photo>>

	open suspend fun getPhotosForAlbum(
		uuid: String,
		sort: Sort,
	): List<Photo> {
		val query = createSortedPhotosQuery(uuid, sort)
		return getPhotosForAlbum(query)
	}

	@RawQuery
	abstract suspend fun getPhotosForAlbum(query: SupportSQLiteQuery): List<Photo>

	private fun createSortedPhotosQuery(
		album: String,
		sort: Sort,
	): SupportSQLiteQuery {
		@Language("roomsql")
		val sql =
			"""
			SELECT p.*
			FROM ${Photo.TABLE_NAME} p
			INNER JOIN ${AlbumPhotoCrossRefTable.TABLE_NAME} ref ON p.photo_uuid = ref.photo_uuid
			WHERE ref.album_uuid = ? AND p.${Photo.COL_DELETED_AT} = 0
			ORDER BY ref.${AlbumPhotoCrossRefTable.COL_PINNED} DESC, ${sort.field.columnName} ${sort.order.sql}
			""".trimIndent()

		return SimpleSQLiteQuery(sql, arrayOf(album))
	}

	// ─── Sprint 2 / M7 — Multi-vault queries ──────────────────────────────

	/**
	 * Backfill `vault_id` for all album rows that have it NULL.
	 *
	 * Called once on first unlock after the v10→v11 migration.
	 *
	 * @since v11 — Sprint 2 / M7 multi-vault
	 */
	@Query("UPDATE album SET vault_id = :vaultId WHERE vault_id IS NULL")
	abstract suspend fun backfillVaultId(vaultId: String): Int
}
