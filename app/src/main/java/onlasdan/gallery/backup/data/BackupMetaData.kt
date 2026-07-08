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

package onlasdan.gallery.backup.data

import com.google.gson.annotations.Expose
import onlasdan.gallery.encryption.domain.models.VaultProtectionParams
import onlasdan.gallery.model.database.entity.PhotoType

/**
 * Model for meta.json in backups.
 *
 * @since 1.0.0
 * @author PhotoZ
 */
sealed interface BackupMetaData {
        val photos: List<PhotoBackup>
        val backupVersion: Int
        val createdAt: Long

        data class V1(
                @Expose override val photos: List<PhotoBackup>,
                @Expose val password: String,
                @Expose val albums: List<AlbumBackup>,
                @Expose val albumPhotoRefs: List<AlbumPhotoRefBackup>,
                @Expose override val createdAt: Long = System.currentTimeMillis(),
                @Expose override val backupVersion: Int,
        ) : BackupMetaData

        data class V2(
                @Expose override val photos: List<PhotoBackup>,
                @Expose val password: String,
                @Expose val albums: List<AlbumBackup>,
                @Expose val albumPhotoRefs: List<AlbumPhotoRefBackup>,
                @Expose override val createdAt: Long = System.currentTimeMillis(),
                @Expose override val backupVersion: Int,
        ) : BackupMetaData

        data class V3(
                @Expose override val photos: List<PhotoBackup>,
                @Expose val password: String,
                @Expose val albums: List<AlbumBackup>,
                @Expose val albumPhotoRefs: List<AlbumPhotoRefBackup>,
                @Expose override val createdAt: Long = System.currentTimeMillis(),
                @Expose override val backupVersion: Int,
        ) : BackupMetaData

        data class V4(
                @Expose override val photos: List<PhotoBackup>,
                @Expose val password: String,
                @Expose val albums: List<AlbumBackup>,
                @Expose val albumPhotoRefs: List<AlbumPhotoRefBackup>,
                @Expose override val createdAt: Long = System.currentTimeMillis(),
                @Expose override val backupVersion: Int,
        ) : BackupMetaData

        data class V5(
                @Expose override val photos: List<PhotoBackup>,
                @Expose val albums: List<AlbumBackup>,
                @Expose val albumPhotoRefs: List<AlbumPhotoRefBackup>,
                @Expose override val createdAt: Long = System.currentTimeMillis(),
                @Expose override val backupVersion: Int,
                @Expose val wrappedVMK: String,
                @Expose val params: VaultProtectionParams,
        ) : BackupMetaData

        companion object {
                const val FILE_NAME = "meta.json"

                /**
                 * Backup version used before switching the encryption. Used for creating a backup before migrating.
                 */
                const val LEGACY_BACKUP_VERSION = 3
                const val CURRENT_BACKUP_VERSION = 5
        }
}

fun BackupMetaData.getPhotosInOriginalOrder(): List<PhotoBackup> {
        return photos.sortedBy {
                it.importedAt
        } // ASC to keep original order. Dump is created with DESC
}

data class PhotoBackup(
        val fileName: String,
        val importedAt: Long,
        val lastModified: Long?,
        val type: PhotoType,
        val size: Long,
        val uuid: String,
        // ─── F-BACK-001: Full metadata round-trip (no backward compat — directly extended V5).
        // Previously only 6 fields were backed up; 13 post-V3 columns were silently dropped,
        // breaking dedup, multi-vault scoping, favorites, EXIF search, semantic search,
        // symlink albums, and trash state on restore.
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

data class AlbumBackup(
        val uuid: String,
        val name: String,
        val modifiedAt: Long?,
)

data class AlbumPhotoRefBackup(
        val albumUUID: String,
        val photoUUID: String,
        val linkedAt: Long,
        val pinned: Boolean = false,
)
