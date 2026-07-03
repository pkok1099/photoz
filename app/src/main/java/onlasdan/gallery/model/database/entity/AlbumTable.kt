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
import java.util.UUID

@Entity(tableName = AlbumTable.TABLE_NAME)
data class AlbumTable(
    val name: String,
    @PrimaryKey
    @ColumnInfo(name = ALBUM_UUID)
    val uuid: String = UUID.randomUUID().toString(),
    @ColumnInfo(name = "modified_at", defaultValue = "0")
    val modifiedAt: Long,

    /**
     * Cryptographic vault identifier — Sprint 2 / M7 (Multi-Vault).
     *
     * Same semantics as [Photo.vaultId] — derived from VMK via HMAC-SHA256.
     * Albums are scoped per-vault: a vault can have an album named "Camera"
     * and another vault can independently have an album named "Camera" — they
     * are distinct rows with distinct `vault_id` values.
     *
     * Nullable for v10→v11 migration backfill (see [Photo.vaultId] doc).
     *
     * @since v11 — Sprint 2 / M7 multi-vault
     */
    @ColumnInfo(name = "vault_id", defaultValue = "NULL")
    val vaultId: String? = null,
) {
    companion object {
        const val TABLE_NAME = "album"
        const val ALBUM_UUID = "album_uuid"
    }
}