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

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import onlasdan.gallery.encryption.domain.models.VaultProtectionParams
import onlasdan.gallery.encryption.domain.models.VaultProtectionType

/**
 * Vault protection storage row — wraps a VMK with a password / biometric /
 * recovery-phrase-derived KEK.
 *
 * ## Sprint 2 / M7 — Multi-Vault
 *
 * Previously this table had `indices = [Index("type", unique = true)]`,
 * enforcing a single row per protection type. That constraint is now
 * REMOVED for `Password` to allow unlimited password-protected vaults:
 *
 *   vault_protection row 1: type=Password, wrappedVMK=A  (vault A's VMK)
 *   vault_protection row 2: type=Password, wrappedVMK=B  (vault B's VMK)
 *   vault_protection row 3: type=RecoveryPhrase, wrappedVMK=A  (only first vault)
 *
 * Each distinct password derives a distinct KEK; the KEK successfully
 * unwraps exactly ONE row's wrappedVMK — that row's VMK identifies which
 * vault the user unlocked. The app does not store "real" vs "decoy" —
 * every vault is treated identically.
 *
 * `Biometric` and `RecoveryPhrase` types remain single-row in practice
 * (only the first vault has them), but the unique index is dropped for
 * uniformity — the DAO-level queries enforce single-row semantics where
 * needed.
 *
 * ## Sprint 3 / (roadmap #6) — SQLCipher DB encryption
 *
 * This table now lives in the plaintext [BootstrapDatabase] (separate
 * file `photok_meta.db`). It must be readable BEFORE the encrypted
 * [onlasdan.gallery.model.database.PhotoZDatabase] can be opened —
 * VaultService reads these rows to find the matching VMK, then unlocks
 * the encrypted DB. See the design notes in `ROADMAP2.md` for the
 * chicken-and-egg explanation.
 *
 * The SQLCipher DB key itself is stored in the Android Keystore (not
 * wrapped per-vault with VMK) — this avoids breaking app-start DB
 * queries that run before vault unlock (e.g. `CleanupDeadFilesUseCase`).
 * A future enhancement may re-key the DB with VMK-derived keys per
 * vault for stronger "vault lock = DB lock" semantics; the schema is
 * forward-compatible for that.
 *
 * @since v11 — Sprint 2 / M7 multi-vault
 * @since v16 — Sprint 3 / SQLCipher (table moved to BootstrapDatabase)
 */
@Entity(tableName = "vault_protection")
data class VaultProtectionTable(
	@PrimaryKey
	val id: String,
	val type: VaultProtectionType,
	// F-ENC-017: renamed from camelCase wrappedVMK
	@ColumnInfo(name = "wrapped_vmk")
	val wrappedVMK: ByteArray,
	val params: VaultProtectionParams,
) {
	override fun equals(other: Any?): Boolean {
		if (this === other) return true
		if (javaClass != other?.javaClass) return false

		other as VaultProtectionTable

		if (id != other.id) return false
		if (type != other.type) return false
		if (!wrappedVMK.contentEquals(other.wrappedVMK)) return false
		if (params != other.params) return false

		return true
	}

	override fun hashCode(): Int {
		var result = id.hashCode()
		result = 31 * result + type.hashCode()
		result = 31 * result + wrappedVMK.contentHashCode()
		result = 31 * result + params.hashCode()
		return result
	}
}
