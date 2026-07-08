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

import onlasdan.gallery.encryption.domain.models.VaultProtection
import onlasdan.gallery.encryption.domain.models.VaultProtectionType

interface VaultProtectionRepository {
        suspend fun getProtection(type: VaultProtectionType): VaultProtection?

        /**
         * Returns ALL protections of [type].
         *
         * For `Password` type this returns 0..N rows (one per vault). The multi-vault
         * unlock flow iterates all of them and tries to unwrap each with the user's
         * password — the first success identifies the unlocked vault.
         *
         * For `Biometric` and `RecoveryPhrase` types this returns 0 or 1 row.
         *
         * @since v11 — Sprint 2 / M7 multi-vault
         */
        suspend fun getAllProtections(type: VaultProtectionType): List<VaultProtection>

        /**
         * Returns the count of protections of [type].
         *
         * @since v11 — Sprint 2 / M7 multi-vault
         */
        suspend fun countProtections(type: VaultProtectionType): Int

        suspend fun createProtection(protection: VaultProtection)

        /**
         * Delete ALL protections of [type]. Used by `VaultService.reset(type)` during
         * full-app factory reset (Settings → Reset app).
         *
         * F-ENC-022: Renamed from `removeProtection` for clarity — the name now
         * makes it obvious that ALL rows of the given type are deleted, not just one.
         * For per-vault deletion, use [removeProtectionById].
         */
        suspend fun removeAllProtectionsOfType(type: VaultProtectionType)

        /**
         * Delete a single protection by its `id`. Used by per-vault deletion flows
         * (future enhancement — not yet wired to UI in M7 v1).
         *
         * @since v11 — Sprint 2 / M7 multi-vault
         */
        suspend fun removeProtectionById(id: String)

        suspend fun updateProtection(protection: VaultProtection)
}
