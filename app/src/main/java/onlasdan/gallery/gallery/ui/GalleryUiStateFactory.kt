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
        filter: GalleryFilter = GalleryFilter.PHOTOS,
    ): GalleryUiState {
        return if (photos.isEmpty()) {
            GalleryUiState.Empty
        } else {
            // @since file-upload feature — split photos vs files for the
            // gallery filter chip. "Photos" = image + video types; "Files" =
            // DOCUMENT / ARCHIVE / AUDIO. The split is on `PhotoType.isFile`
            // so adding a new photo/video type automatically shows up under
            // "Photos" without touching this factory.
            val (filteredPhotos, filteredPending) = when (filter) {
                GalleryFilter.PHOTOS -> {
                    val matched = photos.filter { !it.type.isFile }
                    matched to matched.count { it.syncState == SyncState.UPLOAD_PENDING }
                }
                GalleryFilter.FILES -> {
                    val matched = photos.filter { it.type.isFile }
                    matched to matched.count { it.syncState == SyncState.UPLOAD_PENDING }
                }
            }
            // If the selected filter has zero matches, show Empty so the
            // gallery placeholder appears (e.g. user has only photos, picks
            // the "Files" filter — show the empty state with the FAB).
            if (filteredPhotos.isEmpty()) {
                GalleryUiState.Empty
            } else {
                GalleryUiState.Content(
                    photos = filteredPhotos.map {
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
                    pendingSyncCount = filteredPending,
                    filter = filter,
                )
            }
        }
    }
}