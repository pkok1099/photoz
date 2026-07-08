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

package onlasdan.gallery.sync.domain

import onlasdan.gallery.sync.work.HashRegistryDao
import onlasdan.gallery.sync.work.HashRegistryEntry

/**
 * Pure dedup decision helper — extracted from
 * [onlasdan.gallery.sync.work.PhotoSyncWorker.performUpload] so the dedup
 * logic (same content hash → skip, different hash → upload) is unit-testable
 * without spinning up a full `CoroutineWorker`.
 *
 * The decision is based on a single lookup against the local cache of the
 * encrypted dedup registry (see [HashRegistryDao]). The cache is rebuilt
 * from the remote on every successful login, so a hit here means the content
 * is already on the remote under a different (canonical) UUID — the upload
 * can be skipped entirely.
 *
 * @since Batch 3 — extracted for testability
 */
class Dedup(
        private val dao: HashRegistryDao,
) {
        /**
         * Returns `true` if [contentHash] already has a live (non-tombstoned)
         * entry in the local registry cache — i.e. the upload should be SKIPPED.
         *
         * Returns `false` if:
         *  - [contentHash] is `null` or blank (pre-v9 import, or hash computation
         *    failed at import time — no dedup possible, upload normally).
         *  - No entry exists for this hash (new content, upload normally).
         *
         * F-BACK-008: [vaultId] scopes the lookup to the current vault. Pass null
         * to search across all vaults (legacy behavior — breaks multi-vault isolation).
         */
        suspend fun shouldSkip(contentHash: String?, vaultId: String? = null): Boolean {
                if (contentHash.isNullOrBlank()) return false
                return if (vaultId != null) {
                        dao.findByHash(contentHash, vaultId) != null
                } else {
                        dao.findByHashAnyVault(contentHash) != null
                }
        }

        /**
         * Returns the canonical registry entry for [contentHash], or `null` if no
         * entry exists (new content) or [contentHash] is blank.
         *
         * The canonical entry's [HashRegistryEntry.uuid] is the UUID under which
         * the original + thumbnail + video preview are stored on the remote —
         * used by the restore flow to fetch artifacts without a separate upload.
         *
         * F-BACK-008: [vaultId] scopes the lookup to the current vault.
         */
        suspend fun findCanonical(contentHash: String, vaultId: String? = null): HashRegistryEntry? {
                if (contentHash.isBlank()) return null
                return if (vaultId != null) {
                        dao.findByHash(contentHash, vaultId)
                } else {
                        dao.findByHashAnyVault(contentHash)
                }
        }
}
