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

package onlasdan.gallery.sync.work

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One row per content-hash in the dedup registry.
 *
 * The dedup registry is a single encrypted `registry.json.crypt` artifact on the
 * rclone remote (see [HashRegistry]). At login it is downloaded, decrypted with
 * the VMK (AES-256-GCM) and cached locally in this Room table; at upload time
 * the worker consults the local cache to decide whether a photo's
 * [onlasdan.gallery.model.database.entity.Photo.contentHash] is already
 * present on the remote (skip upload) or new (upload + append to registry).
 *
 * Storage semantics:
 *  - [contentHash] is the primary key — one row per unique hash.
 *  - [uuid] is the canonical photo UUID that OWNS this content on the remote
 *    (i.e. the first photo that uploaded with this hash). Duplicate photos
 *    with the same hash do NOT get their own originals/thumbnails on the
 *    remote — they reference this canonical UUID.
 *  - [thumbnailPack] / [thumbnailOffset] / [thumbnailLength] are reserved
 *    for the future packed-thumbnails optimization (50 MB packs). The
 *    current implementation uploads thumbnails individually and leaves
 *    [thumbnailPack] = null; the registry format already supports packs so
 *    the optimization can be added later without a format change.
 *  - [deleted] is a soft-delete tombstone — when a photo is deleted locally
 *    and its hash has no other referencers, the entry is marked deleted
 *    (NOT removed) so other devices that still hold the registry cache know
 *    not to reference this hash. Garbage-collection of deleted entries is
 *    a future enhancement.
 *
 * @since v9 — dedup + encrypted GCM registry
 */
@Entity(tableName = "hash_registry")
data class HashRegistryEntry(
	@PrimaryKey
	@ColumnInfo(name = "content_hash")
	val contentHash: String,
	@ColumnInfo(name = "uuid")
	val uuid: String,
	@ColumnInfo(name = "filename")
	val filename: String,
	@ColumnInfo(name = "album_path")
	val albumPath: String?,
	@ColumnInfo(name = "size")
	val size: Long,
	@ColumnInfo(name = "type")
	val type: String,
	@ColumnInfo(name = "thumbnail_pack")
	val thumbnailPack: String?,
	@ColumnInfo(name = "thumbnail_offset")
	val thumbnailOffset: Long = 0,
	@ColumnInfo(name = "thumbnail_length")
	val thumbnailLength: Long = 0,
	@ColumnInfo(name = "deleted")
	val deleted: Boolean = false,
	/**
	 * Cryptographic vault identifier — Sprint 2 / M7 (Multi-Vault).
	 *
	 * Same semantics as [onlasdan.gallery.model.database.entity.Photo.vaultId].
	 * Registry entries are scoped per-vault: when [HashRegistry.flushRegistryIfBatchComplete]
	 * serializes the local cache to `registry.json.crypt` for upload, it filters
	 * `WHERE vault_id = <syncing_vault_id>` so only the syncing vault's entries
	 * go to the remote. Additional (decoy) vaults' entries stay in the local
	 * cache only — they never reach the remote, so forensic inspection of the
	 * cloud storage cannot reveal the existence of additional vaults.
	 *
	 * Nullable for v10→v11 migration backfill (see Photo.vaultId doc).
	 *
	 * @since v11 — Sprint 2 / M7 multi-vault
	 */
	@ColumnInfo(name = "vault_id", defaultValue = "NULL")
	val vaultId: String? = null,
)
