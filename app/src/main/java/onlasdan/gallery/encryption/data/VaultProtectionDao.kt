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

package onlasdan.gallery.encryption.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import onlasdan.gallery.encryption.domain.models.VaultProtectionType

@Dao
interface VaultProtectionDao {
	@Insert(onConflict = OnConflictStrategy.REPLACE)
	suspend fun insert(protection: VaultProtectionTable)

	@Query("DELETE FROM vault_protection WHERE type = :type")
	suspend fun delete(type: VaultProtectionType)

	@Query("DELETE FROM vault_protection WHERE id = :id")
	suspend fun deleteById(id: String)

	@Update(onConflict = OnConflictStrategy.REPLACE)
	suspend fun update(protection: VaultProtectionTable)

	/**
	 * Returns the FIRST row matching [type], or null.
	 *
	 * For `Biometric` and `RecoveryPhrase` types this is the canonical single row
	 * (only the first vault has them, by design).
	 *
	 * For `Password` type, this returns an arbitrary row — callers that need
	 * multi-vault semantics must use [getAllByType] instead and iterate.
	 *
	 * Kept for backwards compatibility with existing callers that don't care
	 * about multi-vault (e.g. `isSetup(type)` checks).
	 */
	@Query("SELECT * FROM vault_protection WHERE type = :type LIMIT 1")
	suspend fun getVaultProtection(type: VaultProtectionType): VaultProtectionTable?

	/**
	 * Returns ALL rows matching [type].
	 *
	 * Sprint 2 / M7: used by the multi-vault unlock flow — for `Password` type,
	 * the unlock code iterates every row and tries to unwrap each `wrappedVMK`
	 * with the user-supplied password. The first row whose GCM auth tag verifies
	 * identifies the unlocked vault.
	 *
	 * For `Biometric` and `RecoveryPhrase` types, this returns 0 or 1 row in
	 * practice (only the first vault has them).
	 *
	 * @since v11 — Sprint 2 / M7 multi-vault
	 */
	@Query("SELECT * FROM vault_protection WHERE type = :type")
	suspend fun getAllByType(type: VaultProtectionType): List<VaultProtectionTable>

	/**
	 * Returns the count of rows matching [type].
	 *
	 * Used by `VaultService.canUnlock()` and the migration path to detect
	 * whether any Password protection exists at all.
	 *
	 * @since v11 — Sprint 2 / M7 multi-vault
	 */
	@Query("SELECT COUNT(*) FROM vault_protection WHERE type = :type")
	suspend fun countByType(type: VaultProtectionType): Int
}
