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

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

/**
 * DAO for the local cache of the dedup registry ([HashRegistryEntry]).
 *
 * The remote-of-truth is the encrypted `registry.json.crypt` artifact; this
 * table is just a queryable cache so the upload worker can do fast
 * "is this hash already on the remote?" lookups without downloading +
 * decrypting the registry on every photo. It is fully rebuilt from the
 * remote on every successful login (see [HashRegistry.downloadAndCache]).
 *
 * @since v9 — dedup + encrypted GCM registry
 */
@Dao
interface HashRegistryDao {
	/**
	 * F-BACK-008: Look up by content_hash, scoped to the syncing vault.
	 *
	 * Previously this query had no vault_id filter, which broke multi-vault data
	 * isolation: vault A's hash entry would be found by vault B's upload worker,
	 * causing vault B's photo to reference vault A's UUID on the remote. If vault
	 * A was later wiped, vault B's photo became undecryptable.
	 *
	 * Now: callers pass the current session's vaultId. The hash_registry table
	 * has vault_id column since v11 (Sprint 2 / M7 multi-vault).
	 *
	 * NOTE: content_hash is NOT globally unique — it's unique per-vault. Two
	 * vaults can have the same content_hash pointing to different UUIDs.
	 *
	 * @since F-BACK-008 — multi-vault isolation fix
	 */
	@Query("SELECT * FROM hash_registry WHERE content_hash = :hash AND vault_id = :vaultId AND deleted = 0 LIMIT 1")
	suspend fun findByHash(hash: String, vaultId: String): HashRegistryEntry?

	@Query("SELECT * FROM hash_registry WHERE content_hash = :hash AND deleted = 0 LIMIT 1")
	suspend fun findByHashAnyVault(hash: String): HashRegistryEntry?

	/**
	 * Look up an entry by content_hash, INCLUDING tombstoned entries.
	 *
	 * Used by [HashRegistry.softDelete] so a tombstone can be re-marked
	 * (or its UUID updated) even if the entry was already soft-deleted by
	 * a prior call.
	 *
	 * @since registry GC feature — soft delete + repack + remote cleanup
	 */
	@Query("SELECT * FROM hash_registry WHERE content_hash = :hash LIMIT 1")
	suspend fun findByHashIncludingDeleted(hash: String): HashRegistryEntry?

	@Query("SELECT * FROM hash_registry WHERE deleted = 0")
	suspend fun getAll(): List<HashRegistryEntry>

	/**
	 * Return ALL entries including tombstoned (deleted=true) ones.
	 *
	 * Used by the registry garbage collector
	 * ([HashRegistry.gcThumbnailPacks] / [HashRegistry.gcOriginals])
	 * to enumerate dead entries for cleanup. Regular callers should use
	 * [getAll] which filters out the tombstones.
	 *
	 * @since registry GC feature — soft delete + repack + remote cleanup
	 */
	@Query("SELECT * FROM hash_registry")
	suspend fun getAllIncludingDeleted(): List<HashRegistryEntry>

	/**
	 * Look up a registry entry by the canonical photo UUID.
	 *
	 * Used by [onlasdan.gallery.sync.rclone.RepoManager.applyRegistryMetadataToPhotos]
	 * after a fresh-install login: the registry is downloaded + decrypted with the
	 * VMK, and each entry's UUID is matched against existing Photo rows (created
	 * earlier in the login flow with placeholder metadata) to backfill the real
	 * filename / size / type / albumPath / contentHash.
	 *
	 * @since v9 followup — backfill Photo metadata from registry after login
	 */
	@Query("SELECT * FROM hash_registry WHERE uuid = :uuid AND deleted = 0 LIMIT 1")
	suspend fun findByUuid(uuid: String): HashRegistryEntry?

	@Upsert
	suspend fun upsert(entry: HashRegistryEntry)

	@Upsert
	suspend fun upsertAll(entries: List<HashRegistryEntry>)

	@Query("SELECT COUNT(*) FROM hash_registry WHERE deleted = 0")
	suspend fun count(): Int

	@Query("DELETE FROM hash_registry")
	suspend fun clear()

	/**
	 * Physically remove a registry row by content_hash.
	 *
	 * Used by [HashRegistry.gcOriginals] to delete tombstoned rows AFTER the
	 * remote original file has been confirmed deleted — the row is no longer
	 * needed as a tombstone because the original it pointed to is gone.
	 *
	 * @since registry GC feature — soft delete + repack + remote cleanup
	 */
	@Query("DELETE FROM hash_registry WHERE content_hash = :hash")
	suspend fun deleteByHash(hash: String)

	// ─── Sprint 2 / M7 — Multi-vault queries ──────────────────────────────

	/**
	 * Backfill `vault_id` for all registry entries that have it NULL.
	 *
	 * Called once on first unlock after the v10→v11 migration.
	 *
	 * @since v11 — Sprint 2 / M7 multi-vault
	 */
	@Query("UPDATE hash_registry SET vault_id = :vaultId WHERE vault_id IS NULL")
	suspend fun backfillVaultId(vaultId: String): Int

	/**
	 * Returns ALL non-deleted registry entries for a specific vault.
	 *
	 * Used by [HashRegistry.flushRegistryIfBatchComplete] to serialize only
	 * the syncing vault's entries to `registry.json.crypt` for upload.
	 * Additional (decoy) vaults' entries stay local-only and never reach
	 * the remote, so forensic inspection of cloud storage cannot reveal
	 * the existence of additional vaults.
	 *
	 * @since v11 — Sprint 2 / M7 multi-vault
	 */
	@Query("SELECT * FROM hash_registry WHERE deleted = 0 AND vault_id = :vaultId")
	suspend fun getAllForVault(vaultId: String): List<HashRegistryEntry>

	/**
	 * Returns ALL registry entries (including deleted tombstones) for a
	 * specific vault. Used by the registry GC to walk tombstones.
	 *
	 * @since v11 — Sprint 2 / M7 multi-vault
	 */
	@Query("SELECT * FROM hash_registry WHERE vault_id = :vaultId")
	suspend fun getAllForVaultIncludingDeleted(vaultId: String): List<HashRegistryEntry>
}
