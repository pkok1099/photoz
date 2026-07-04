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

package onlasdan.gallery.model.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.google.gson.annotations.Expose
import onlasdan.gallery.sync.domain.SyncState
import java.util.UUID

/**
 * Entity describing a Photo.
 *
 * @since 1.0.0
 * @author PhotoZ
 */
// TODO: Add a domain model for photos
@Entity(tableName = Photo.TABLE_NAME)
data class Photo(
    @Expose
    @ColumnInfo(name = COL_FILENAME)
    val fileName: String,

    @ColumnInfo(name = COL_IMPORTED_AT)
    var importedAt: Long,

    @Expose val type: PhotoType,
    @Expose
    @ColumnInfo(name = COL_SIZE)
    var size: Long = 0L,

    @ColumnInfo(name = COL_LAST_MODIFIED, defaultValue = "NULL")
    @Expose
    var lastModified: Long?,

    @Expose
    @PrimaryKey
    @ColumnInfo(name = "photo_uuid")
    val uuid: String = UUID.randomUUID().toString(),

    /**
     * Cloud sync state. Stored as the enum's [SyncState.storageKey] (TEXT).
     * Default `'LOCAL_ONLY'` set via column default + v6→v7 AutoMigration spec.
     *
     * @since PR1 sync feature
     */
    @ColumnInfo(name = COL_SYNC_STATE, defaultValue = "'LOCAL_ONLY'")
    var syncState: SyncState = SyncState.LOCAL_ONLY,

    /**
     * Original local-origin provenance path for this photo, captured at import time.
     *
     * This is a metadata-only field — PhotoZ's vault is its own managed encrypted storage
     * and does NOT mirror the device's public filesystem. The restored photo still lives
     * in PhotoZ's vault (as it always has); this column merely records where the photo
     * came from for the user's reference (filename, or in a future enhancement, the full
     * MediaStore `RELATIVE_PATH` such as `DCIM/Camera`).
     *
     * Uploaded as part of the per-photo metadata sidecar JSON to
     * `photok-backup/metadata/<uuid>.json` and used on restore to populate the Photo
     * row more accurately (instead of guessing `type=JPEG`, `size=0`).
     *
     * @since v8 — path-consistency metadata sidecar
     */
    @ColumnInfo(name = COL_RELATIVE_PATH, defaultValue = "NULL")
    @Expose
    var relativePath: String? = null,

    /**
     * SHA-256 hash of the photo's PLAINTEXT bytes (i.e. the unencrypted source
     * bytes BEFORE they were written through the AES-CBC encryption stream).
     *
     * Computed at import time in
     * [onlasdan.gallery.model.repositories.PhotoRepository.safeImportPhoto]
     * by feeding the source `InputStream` through a `MessageDigest("SHA-256")`
     * while it's being copied to the encrypted destination — no extra disk
     * read, no extra memory beyond the digest's internal state.
     *
     * Used by the v9 dedup registry ([onlasdan.gallery.sync.work.HashRegistry])
     * to decide whether an upload can be SKIPPED because the same content is
     * already on the remote under a different UUID. `null` for photos imported
     * before v9 (no hash was computed at the time) — these photos upload
     * normally and the registry entry is created with whatever hash we can
     * compute on-demand from the encrypted-at-rest original (or simply left
     * out of the registry, falling back to the pre-v9 behavior).
     *
     * @since v9 — dedup + encrypted GCM registry
     */
    @ColumnInfo(name = COL_CONTENT_HASH, defaultValue = "NULL")
    @Expose
    var contentHash: String? = null,

    /**
     * Logical album / folder path this photo belongs to (e.g. `DCIM/Camera`,
     * `Pictures/Screenshots`). Currently captured at import time as the same
     * value as [relativePath] (i.e. the original filename) until the
     * `FileMetaData` model is extended to expose the MediaStore
     * `RELATIVE_PATH` column.
     *
     * Distinct from [relativePath] because the registry's per-hash entry
     * needs a stable album grouping key for the future packed-thumbnails
     * optimization (50 MB packs per album) — `relativePath` may evolve to
     * hold the full original filesystem path for display purposes, while
     * `albumPath` stays a clean folder key.
     *
     * @since v9 — dedup + encrypted GCM registry
     */
    @ColumnInfo(name = COL_ALBUM_PATH, defaultValue = "NULL")
    @Expose
    var albumPath: String? = null,

    /**
     * Soft-delete timestamp. `0` means the photo is live (visible in the
     * gallery). A non-zero value is the epoch-ms at which the user moved the
     * photo to the Trash. Trash items are excluded from the gallery and
     * albums (see [PhotoDao.observeAllSorted] and
     * [AlbumDao.createSortedPhotosQuery]), and surfaced in the Trash screen
     * instead. Photos whose `deletedAt` is older than 30 days are
     * permanently deleted on app start by
     * [onlasdan.gallery.BaseApplication.onCreate] (via
     * [PhotoRepository.cleanupExpiredTrash]).
     *
     * @since v10 — recycle bin / soft delete
     */
    @ColumnInfo(name = COL_DELETED_AT, defaultValue = "0")
    var deletedAt: Long = 0L,

    /**
     * Cryptographic vault identifier — Sprint 2 / M7 (Multi-Vault).
     *
     * Computed as `HMAC-SHA256(VMK, "photoz-vault-id-v1").take(16 bytes).toHex()`
     * at unlock time (see [onlasdan.gallery.encryption.domain.models.VaultSession.vaultId]).
     * Stored on every Photo / Album / HashRegistryEntry row so queries can
     * filter `WHERE vault_id = ?` using the current session's vault_id.
     *
     * Nullable for backwards compatibility: rows created before the v11
     * migration have `vault_id = NULL`. On the first unlock after upgrade,
     * [onlasdan.gallery.encryption.domain.VaultService.unlock] backfills
     * all NULL rows with the vault_id computed from the unlocking VMK
     * (transparent one-time migration cost).
     *
     * NO `is_real` / `is_decoy` flag exists — every vault is treated
     * identically. The vault_id is the ONLY discriminator, and it is
     * derived cryptographically from the VMK, not stored as metadata.
     *
     * @since v11 — Sprint 2 / M7 multi-vault
     */
    @ColumnInfo(name = COL_VAULT_ID, defaultValue = "NULL")
    @Expose
    var vaultId: String? = null,

    /**
     * Sprint 4 / M2 — Favorites flag.
     *
     * `false` (default) means the photo is a regular gallery item. `true`
     * means the user has marked it as a favorite — the gallery shows a heart
     * badge on the thumbnail and the "Favorites" filter chip selects only
     * these photos.
     *
     * Vault-scoped: favorites are per-vault (a photo favorited in vault A
     * is NOT favorited in vault B, even if the same content_hash exists in
     * both vaults — they're distinct Photo rows with distinct vault_id).
     *
     * @since v12 — Sprint 4 / M2 favorites
     */
    @ColumnInfo(name = COL_IS_FAVORITE, defaultValue = "0")
    @Expose
    var isFavorite: Boolean = false,

    // ─── Sprint 6 / M4 — EXIF metadata for search ─────────────────────────
    // Extracted at import time from the source photo's EXIF (when available).
    // NULL when the source has no EXIF (e.g. screenshots, PDFs, audio) or when
    // EXIF parsing fails. All EXIF columns are nullable for that reason.
    //
    // The gallery search parser recognizes prefixes like `date:2024-01`,
    // `camera:Canon`, `location:Jakarta` and filters against these columns.
    // Without EXIF extraction, users with 1000+ photos have no way to find
    // photos by when/where they were taken — only by filename, which is
    // rarely meaningful (IMG_20240115_123456.jpg etc.).

    /** EXIF DateTimeOriginal as epoch-ms. NULL when no EXIF date. @since v13 M4 */
    @ColumnInfo(name = COL_EXIF_DATE_TAKEN, defaultValue = "NULL")
    @Expose
    var exifDateTaken: Long? = null,

    /** EXIF GPS latitude (decimal degrees). NULL when no GPS. @since v13 M4 */
    @ColumnInfo(name = COL_EXIF_GPS_LAT, defaultValue = "NULL")
    @Expose
    var exifGpsLat: Double? = null,

    /** EXIF GPS longitude (decimal degrees). NULL when no GPS. @since v13 M4 */
    @ColumnInfo(name = COL_EXIF_GPS_LON, defaultValue = "NULL")
    @Expose
    var exifGpsLon: Double? = null,

    /** EXIF Make + Model concatenated (e.g. "Canon EOS R6"). NULL when no EXIF. @since v13 M4 */
    @ColumnInfo(name = COL_EXIF_CAMERA, defaultValue = "NULL")
    @Expose
    var exifCamera: String? = null,

    /**
     * Sprint 9 / L6 — AI-generated semantic tags for on-device search.
     *
     * Comma-separated tags generated by the on-device ML model (MobileNet /
     * EfficientNet via TFLite) at import time. E.g. "beach,sunset,ocean,person".
     * NULL when semantic search is disabled or the model hasn't run yet.
     *
     * The gallery search parser recognizes the `tag:` prefix and filters
     * against this column (case-insensitive contains on each comma-separated
     * tag). Tags are stored in plaintext (not encrypted) because they're
     * derived from the photo's visual content, not the photo itself — the
     * encrypted photo file stays encrypted; only the tag metadata is
     * readable. This is a deliberate trade-off: encrypting tags would
     * require VMK decryption for every search query, making search
     * unusably slow.
     *
     * @since v14 — Sprint 9 / L6 on-device semantic search
     */
    @ColumnInfo(name = COL_AI_TAGS, defaultValue = "NULL")
    @Expose
    var aiTags: String? = null,
) {

    val internalFileName: String
        get() = internalFileName(uuid)

    val internalThumbnailFileName: String
        get() = internalThumbnailFileName(uuid)

    val internalVideoPreviewFileName: String
        get() = internalVideoPreviewFileName(uuid)

    companion object {
        const val COL_FILENAME = "fileName"
        const val COL_IMPORTED_AT = "importedAt"
        const val COL_LAST_MODIFIED = "lastModified"
        const val DATE_TAKEN = "dateTaken"
        const val COL_SIZE = "size"
        const val TABLE_NAME = "photo"

        /** Cloud sync state column. @since PR1 sync feature */
        const val COL_SYNC_STATE = "syncState"

        /** Original local-origin provenance path column. @since v8 path-consistency sidecar */
        const val COL_RELATIVE_PATH = "relativePath"

        /** SHA-256 of plaintext bytes — dedup key for the v9 registry. @since v9 dedup */
        const val COL_CONTENT_HASH = "content_hash"

        /** Logical album/folder key — for packed-thumbnails grouping. @since v9 dedup */
        const val COL_ALBUM_PATH = "album_path"

        /** Soft-delete timestamp column. 0 = live, non-zero = in trash. @since v10 recycle bin */
        const val COL_DELETED_AT = "deleted_at"

        /** Vault identifier column. @since v11 Sprint 2 / M7 multi-vault */
        const val COL_VAULT_ID = "vault_id"

        /** Favorite flag column. @since v12 Sprint 4 / M2 favorites */
        const val COL_IS_FAVORITE = "is_favorite"

        /** EXIF date taken column. @since v13 Sprint 6 / M4 EXIF search */
        const val COL_EXIF_DATE_TAKEN = "exif_date_taken"

        /** EXIF GPS latitude column. @since v13 Sprint 6 / M4 EXIF search */
        const val COL_EXIF_GPS_LAT = "exif_gps_lat"

        /** EXIF GPS longitude column. @since v13 Sprint 6 / M4 EXIF search */
        const val COL_EXIF_GPS_LON = "exif_gps_lon"

        /** EXIF camera (Make+Model) column. @since v13 Sprint 6 / M4 EXIF search */
        const val COL_EXIF_CAMERA = "exif_camera"

        /** AI-generated semantic tags column. @since v14 Sprint 9 / L6 semantic search */
        const val COL_AI_TAGS = "ai_tags"
    }
}