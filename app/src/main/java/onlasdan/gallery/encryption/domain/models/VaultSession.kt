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

package onlasdan.gallery.encryption.domain.models

import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.IvParameterSpec

sealed interface Session

data class LegacySession(
    val key: SecretKey,
    val iv: IvParameterSpec,
) : Session

/**
 * Active vault session — holds the in-memory VMK + the cryptographically-derived
 * vault identifier.
 *
 * ## Sprint 2 / M7 — vault_id
 *
 * The [vaultId] is computed once at unlock time from the VMK via
 * HMAC-SHA256 with a fixed domain-separation tag:
 *
 * ```
 * vault_id = HMAC-SHA256(VMK, "photoz-vault-id-v1").take(16 bytes).toHex()
 * ```
 *
 * It is the ONLY discriminator between vaults — there is no `is_real` /
 * `is_decoy` flag anywhere in the data model. Every Photo / Album /
 * HashRegistryEntry row stores the vault_id of the vault that owns it; all
 * gallery queries filter `WHERE vault_id = ?` using the current session's
 * value.
 *
 * Properties:
 *  - **Deterministic**: same VMK always derives the same vault_id.
 *  - **Unforgeable**: without the VMK, an attacker cannot compute the
 *    vault_id, so they cannot tell which DB rows belong to which vault.
 *  - **Stable across devices**: a vault restored on a new device (via
 *    recovery phrase) derives the same vault_id, so the user's folder
 *    structure and album grouping are preserved.
 *  - **Migration-safe**: rows with `vault_id IS NULL` (created before v11)
 *    are backfilled on first unlock with the unlocking VMK's vault_id.
 *
 * The vault_id is NEVER persisted alongside the VMK — it is recomputed
 * from the VMK on every unlock. An attacker with disk access sees the
 * vault_id on Photo rows but cannot reverse it back to the VMK (HMAC is
 * one-way), and cannot tell which vault_protection row corresponds to
 * which vault_id without trying each password.
 *
 * @since v11 — Sprint 2 / M7 multi-vault
 */
data class VaultSession(
    val vmk: SecretKey,
) : Session {

    /**
     * Lazily-computed vault_id. Computed from [vmk] on first access and
     * cached in-memory for the lifetime of this session (via `by lazy`).
     * Never persisted.
     *
     * Throws if the JCE provider rejects the VMK as an HMAC key — should
     * never happen because [KeyGen.generateVaultMasterKey] always produces
     * an AES-256 key whose `getEncoded()` returns the raw 32 bytes that
     * HmacSHA256 can consume.
     */
    val vaultId: String by lazy { computeVaultId(vmk) }

    companion object {
        /** Domain-separation tag for vault_id derivation. */
        private const val VAULT_ID_TAG = "photoz-vault-id-v1"

        /**
         * Compute the vault_id for a given VMK.
         *
         * HMAC-SHA256(VMK, "photoz-vault-id-v1"), take first 16 bytes, hex-encode.
         * 16 bytes = 32 hex chars — short enough for DB indexing, long enough
         * to make collisions astronomically unlikely (2^128 keyspace).
         */
        fun computeVaultId(vmk: SecretKey): String {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(vmk)
            val full = mac.doFinal(VAULT_ID_TAG.toByteArray(Charsets.US_ASCII))
            return full.take(16).joinToString("") { "%02x".format(it) }
        }
    }
}
