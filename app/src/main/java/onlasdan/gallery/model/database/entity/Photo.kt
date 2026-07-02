/*
 *   Copyright 2020–2026 Leon Latsch
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
 * @author Leon Latsch
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
     * This is a metadata-only field — Photok's vault is its own managed encrypted storage
     * and does NOT mirror the device's public filesystem. The restored photo still lives
     * in Photok's vault (as it always has); this column merely records where the photo
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
    }
}