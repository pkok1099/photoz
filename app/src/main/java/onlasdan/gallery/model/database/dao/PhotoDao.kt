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

import androidx.room.*
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import onlasdan.gallery.sort.domain.Sort
import onlasdan.gallery.model.database.entity.Photo
import onlasdan.gallery.sync.domain.SyncState
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for [Photo] Entity.
 * Never use directory. Use with Repository only.
 *
 * @since 1.0.0
 * @author PhotoZ
 */
@Dao
interface PhotoDao {

    /**
     * Insert one [Photo]
     *
     * @return the id of the new inserted item.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(photo: Photo): Long

    /**
     * Delete one [Photo]
     *
     * @return the id of the deleted item.
     */
    @Delete
    suspend fun delete(photo: Photo): Int

    /**
     * Delete all [Photo] rows.
     */
    @Query("DELETE FROM photo")
    suspend fun deleteAll()

    /**
     * Get one [Photo] by [id].
     *
     * @return the photo with [id]
     */
    @Query("SELECT * FROM photo WHERE photo_uuid = :uuid")
    suspend fun get(uuid: String): Photo

    @Query("SELECT * FROM photo")
    suspend fun getAll(): List<Photo>

    /**
     * Get all photos, ordered by imported At (desc).
     * Used for re-encrypting, backup dump, and resetApp — INCLUDES trash
     * entries so a backup/restore round-trip preserves trashed photos and
     * a re-encryption pass migrates everything (not just live photos).
     *
     * @return all photos as [List]
     */
    @Query("SELECT * FROM photo ORDER BY importedAt DESC")
    suspend fun findAllPhotosByImportDateDesc(): List<Photo>

    @Query("SELECT * FROM photo WHERE deleted_at = 0 ORDER BY importedAt DESC")
    fun observeAll(): Flow<List<Photo>>

    /**
     * Count all photos.
     */
    @Query("SELECT COUNT(*) FROM photo WHERE deleted_at = 0")
    suspend fun countAll(): Int

    // Sorted

    fun observeAllSorted(sort: Sort): Flow<List<Photo>> {
        val query = SimpleSQLiteQuery(
            "SELECT * FROM photo WHERE deleted_at = 0 ORDER BY ${sort.field.columnName} ${sort.order.sql}"
        )

        return observeAll(query)
    }

    @RawQuery(observedEntities = [Photo::class])
    fun observeAll(query: SupportSQLiteQuery): Flow<List<Photo>>

    // region SYNC STATE — @since PR1 sync feature

    @Query("UPDATE photo SET syncState = :state WHERE photo_uuid = :uuid")
    suspend fun updateSyncState(uuid: String, state: SyncState)

    @Query("UPDATE photo SET syncState = :state WHERE photo_uuid = :uuid")
    suspend fun updateSyncStateReturningRows(uuid: String, state: SyncState): Int

    @Query(
        "SELECT * FROM photo WHERE syncState IN " +
            "(:pending, :failed) ORDER BY importedAt ASC"
    )
    suspend fun findPhotosInSyncState(
        pending: SyncState = SyncState.UPLOAD_PENDING,
        failed: SyncState = SyncState.UPLOAD_FAILED,
    ): List<Photo>

    // endregion

    // region DEDUP — @since v9 dedup + encrypted GCM registry

    /**
     * Update the SHA-256 content hash for a photo. Called by
     * [onlasdan.gallery.model.repositories.PhotoRepository.safeImportPhoto]
     * after the plaintext bytes have been streamed through the digest.
     */
    @Query("UPDATE photo SET content_hash = :hash WHERE photo_uuid = :uuid")
    suspend fun updateContentHash(uuid: String, hash: String)

    /**
     * Find a Photo with the given content_hash, excluding a specific UUID.
     * Used for dedup at import time — if another photo with the same content
     * already exists, the just-imported duplicate is deleted.
     *
     * @since Bug 1 fix — dedup at import time
     */
    @Query("SELECT * FROM photo WHERE content_hash = :hash AND photo_uuid != :excludeUuid LIMIT 1")
    suspend fun findByContentHash(hash: String, excludeUuid: String): Photo?

    /**
     * Backfill a Photo row's placeholder metadata (filename, size, type,
     * relativePath, albumPath, contentHash) from the dedup registry, after a
     * fresh-install login has downloaded + decrypted the registry.
     *
     * [onlasdan.gallery.sync.rclone.RepoManager.restoreThumbnailsAfterLogin]
     * creates Photo rows with placeholder metadata (type=JPEG, size=0,
     * relativePath=null) because the registry cannot be decrypted until the
     * vault is unlocked. Once the vault is unlocked and the registry is
     * cached, [RepoManager.applyRegistryMetadataToPhotos] iterates the
     * registry entries and calls this method to backfill the real metadata
     * for each row.
     *
     * Only updates rows whose `size = 0` (the placeholder sentinel), so
     * re-running this on an already-populated DB is a no-op.
     *
     * @since v9 followup — backfill Photo metadata from registry after login
     */
    @Query(
        "UPDATE photo SET " +
            "fileName = :filename, " +
            "size = :size, " +
            "type = :type, " +
            "relativePath = :relativePath, " +
            "album_path = :albumPath, " +
            "content_hash = :contentHash " +
            "WHERE photo_uuid = :uuid AND size = 0"
    )
    suspend fun backfillMetadataFromRegistry(
        uuid: String,
        filename: String,
        size: Long,
        type: Int,
        relativePath: String?,
        albumPath: String?,
        contentHash: String,
    ): Int

    /**
     * Return all Photo rows whose metadata is still the placeholder
     * (size = 0) — i.e. created by restoreThumbnailsAfterLogin and not yet
     * backfilled from the registry. Used by
     * [onlasdan.gallery.sync.rclone.RepoManager.applyRegistryMetadataToPhotos]
     * to know which rows need updating.
     *
     * @since v9 followup — backfill Photo metadata from registry after login
     */
    @Query("SELECT * FROM photo WHERE size = 0")
    suspend fun findPhotosWithPlaceholderMetadata(): List<Photo>

    // endregion

    // region RECYCLE BIN — @since v10 soft delete

    /**
     * Observe all photos currently in the trash (deleted_at > 0), ordered by
     * most-recently-deleted first. Drives the Trash screen's list.
     *
     * @since v10 recycle bin
     */
    @Query("SELECT * FROM photo WHERE deleted_at > 0 ORDER BY deleted_at DESC")
    fun observeTrash(): Flow<List<Photo>>

    /**
     * Snapshot of all photos in the trash. Used by the auto-cleanup pass
     * [onlasdan.gallery.model.repositories.PhotoRepository.cleanupExpiredTrash]
     * to find rows whose 30-day retention has expired.
     *
     * @since v10 recycle bin
     */
    @Query("SELECT * FROM photo WHERE deleted_at > 0")
    suspend fun findAllTrash(): List<Photo>

    /**
     * Soft-delete a single photo: stamp `deleted_at` with the given epoch-ms
     * (the gallery + album queries filter `WHERE deleted_at = 0`, so the
     * photo disappears from the live views but its DB row + encrypted files
     * stay intact and are surfaced on the Trash screen).
     *
     * @since v10 recycle bin
     */
    @Query("UPDATE photo SET deleted_at = :timestamp WHERE photo_uuid = :uuid")
    suspend fun softDelete(uuid: String, timestamp: Long)

    /**
     * Restore a photo from the trash: reset `deleted_at` to 0 so the photo
     * reappears in the gallery and albums.
     *
     * @since v10 recycle bin
     */
    @Query("UPDATE photo SET deleted_at = 0 WHERE photo_uuid = :uuid")
    suspend fun restoreFromTrash(uuid: String)

    /**
     * Permanently delete all trash entries whose `deleted_at` is older than
     * [before]. Used by the auto-cleanup pass on app start (30-day retention)
     * and by the Trash screen's "Empty trash" action (pass [before] = Long.MAX_VALUE
     * to wipe the entire trash). Returns the number of rows deleted so the
     * caller can clean up the corresponding encrypted files via
     * [onlasdan.gallery.io.VaultFileStorage.deleteEncryptedFile].
     *
     * Note: this only deletes the DB rows; the caller is responsible for
     * deleting the on-disk `.crypt` / `.crypt.tn` files for each returned
     * Photo BEFORE calling this (or via [PhotoRepository.cleanupExpiredTrash]).
     *
     * @since v10 recycle bin
     */
    @Query("DELETE FROM photo WHERE deleted_at > 0 AND deleted_at < :before")
    suspend fun deleteExpiredTrashBefore(before: Long): Int

    /**
     * Count all photos currently in the trash. Used by the Trash screen
     * header and the auto-cleanup toast.
     *
     * @since v10 recycle bin
     */
    @Query("SELECT COUNT(*) FROM photo WHERE deleted_at > 0")
    suspend fun countTrash(): Int

    // endregion
}