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

package onlasdan.gallery.encryption.domain

import onlasdan.gallery.model.database.dao.AlbumDao
import onlasdan.gallery.model.database.dao.PhotoDao
import onlasdan.gallery.sync.work.HashRegistryDao
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of [VaultIdBackfillHook] — runs after every successful unlock
 * to ensure all rows have a non-null `vault_id`.
 *
 * Sprint 2 / M7 — Multi-vault.
 *
 * On first unlock after the v10→v11 migration, every Photo / Album /
 * HashRegistryEntry row has `vault_id = NULL`. This use-case UPDATEs all NULL
 * rows to the unlocking vault's vault_id (computed by [VaultSession.vaultId]).
 *
 * Subsequent unlocks find no NULL rows and the UPDATEs are no-ops (0 rows
 * affected). The cost is one cheap `UPDATE ... WHERE vault_id IS NULL` query
 * per table per unlock — negligible.
 *
 * Non-fatal: if any individual backfill fails (e.g. DB busy), the error is
 * logged but does not propagate. The next successful unlock will retry.
 *
 * @since v11 — Sprint 2 / M7 multi-vault
 */
@Singleton
class VaultIdBackfillUseCase @Inject constructor(
    private val photoDao: PhotoDao,
    private val albumDao: AlbumDao,
    private val hashRegistryDao: HashRegistryDao,
) : VaultIdBackfillHook {

    override suspend fun backfillVaultId(vaultId: String) {
        // Order matters: backfill registry first (smallest), then albums, then
        // photos (largest). If any step fails, the remaining tables will still
        // be backfilled on the next unlock — partial progress is preserved.
        val registryBackfilled = try {
            hashRegistryDao.backfillVaultId(vaultId)
        } catch (e: Exception) {
            Timber.w(e, "VaultIdBackfill: hash_registry failed (non-fatal)")
            0
        }
        val albumsBackfilled = try {
            albumDao.backfillVaultId(vaultId)
        } catch (e: Exception) {
            Timber.w(e, "VaultIdBackfill: album failed (non-fatal)")
            0
        }
        val photosBackfilled = try {
            photoDao.backfillVaultId(vaultId)
        } catch (e: Exception) {
            Timber.w(e, "VaultIdBackfill: photo failed (non-fatal)")
            0
        }

        if (registryBackfilled + albumsBackfilled + photosBackfilled > 0) {
            Timber.i("VaultIdBackfill: backfilled %d photos, %d albums, %d registry entries " +
                "with vault_id=%s (one-time migration cost)",
                photosBackfilled, albumsBackfilled, registryBackfilled, vaultId)
            android.util.Log.e("RcloneDiag",
                "VaultIdBackfill: backfilled $photosBackfilled photos, $albumsBackfilled albums, " +
                    "$registryBackfilled registry entries with vault_id=$vaultId")
        }
    }
}
