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

package onlasdan.gallery.gallery.components

import onlasdan.gallery.model.database.entity.PhotoType
import onlasdan.gallery.model.database.entity.internalThumbnailFileName
import onlasdan.gallery.sync.domain.SyncState

data class PhotoTile(
    val fileName: String,
    val type: PhotoType,
    val uuid: String,
    val pinned: Boolean = false,
    // @since PR2 sync — per-thumbnail sync state badge
    val syncState: SyncState = SyncState.LOCAL_ONLY,
    /**
     * Sprint 4 / M2 — Favorite flag for the heart badge.
     *
     * When true, the gallery renders a heart icon at the top-right corner of
     * the thumbnail. Tapped from the long-press menu's "Mark as favorite" /
     * "Remove from favorites" action.
     *
     * @since v12 — Sprint 4 / M2 favorites
     */
    val isFavorite: Boolean = false,
) {
    val internalThumbnailFileName = internalThumbnailFileName(uuid)
}