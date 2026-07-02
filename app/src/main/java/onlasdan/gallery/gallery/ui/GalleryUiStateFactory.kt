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
        filter: GalleryFilter = GalleryFilter.ALL,
        searchQuery: String = "",
    ): GalleryUiState {
        return if (photos.isEmpty()) {
            GalleryUiState.Empty
        } else {
            // @since file-upload feature — split photos vs files for the
            // gallery filter chip. "Photos" = image types only; "Videos" =
            // video types; "Files" = DOCUMENT / ARCHIVE / AUDIO; "All" = no
            // type filter. The split uses PhotoType's isVideo / isFile flags
            // so adding a new photo/video/file type automatically shows up
            // under the right chip without touching this factory.
            //
            // @since search-filter feature — extended with ALL + VIDEOS chips
            //   and the searchQuery filename filter (case-insensitive
            //   contains on Photo.fileName).
            val typeFiltered = when (filter) {
                GalleryFilter.ALL -> photos
                GalleryFilter.PHOTOS -> photos.filter { !it.type.isVideo && !it.type.isFile }
                GalleryFilter.VIDEOS -> photos.filter { it.type.isVideo }
                GalleryFilter.FILES -> photos.filter { it.type.isFile }
            }

            // @since search-filter feature — filename contains, case-insensitive.
            // Empty query means "no text filter" — show everything that survived
            // the type filter.
            val textFiltered = if (searchQuery.isBlank()) {
                typeFiltered
            } else {
                val needle = searchQuery.trim().lowercase()
                typeFiltered.filter { it.fileName.lowercase().contains(needle) }
            }

            val filteredPhotos = textFiltered
            val filteredPending = filteredPhotos.count { it.syncState == SyncState.UPLOAD_PENDING }

            // If the selected filter + search yield zero matches, show Empty
            // so the gallery placeholder appears (e.g. user has only photos,
            // picks the "Files" filter — show the empty state with the FAB).
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
                    searchQuery = searchQuery,
                )
            }
        }
    }
}