/*
 *   Copyright 2020–2026 Leon Latsch
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

package onlasdan.gallery.gallery.ui

import onlasdan.gallery.sort.domain.Sort
import android.net.Uri
import onlasdan.gallery.gallery.components.PhotoTile
import onlasdan.gallery.model.database.entity.Photo
import onlasdan.gallery.sync.domain.SyncState
import javax.inject.Inject

class GalleryUiStateFactory @Inject constructor() {
    fun create(
        photos: List<Photo>,
        showAlbumSelectionDialog: Boolean,
        sort: Sort,
    ): GalleryUiState {
        return if (photos.isEmpty()) {
            GalleryUiState.Empty
        } else {
            // @since PR2 sync — count photos still queued for upload, for the global
            // sync status indicator in the gallery top bar.
            val pendingCount = photos.count { it.syncState == SyncState.UPLOAD_PENDING }
            GalleryUiState.Content(
                photos = photos.map {
                    PhotoTile(
                        fileName = it.fileName,
                        type = it.type,
                        uuid = it.uuid,
                        // @since PR2 sync — surface per-photo sync state in the gallery tile
                        syncState = it.syncState,
                    )
                },
                showAlbumSelectionDialog = showAlbumSelectionDialog,
                sort = sort,
                pendingSyncCount = pendingCount,
            )
        }
    }
}