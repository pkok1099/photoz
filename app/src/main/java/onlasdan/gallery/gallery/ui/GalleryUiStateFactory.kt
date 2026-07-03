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
            // ─── Bug 1 fix: Empty state carries filter + searchQuery ──────────
            // The gallery header (search bar + filter chip row) is now rendered
            // outside the `when (uiState)` block in GalleryScreen and pulls the
            // current filter + searchQuery from EITHER the Empty or Content
            // state. The chips MUST stay visible when the gallery is empty so
            // the user can switch back from a filter with no matches (e.g.
            // "Videos" with no videos imported) — without this the gallery
            // would be a dead-end.
            GalleryUiState.Empty(filter = filter, searchQuery = searchQuery)
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
            // ─── Item 3 fix: count LOCAL_ONLY + UPLOAD_PENDING as "pending sync" ──
            // Previously this counted only UPLOAD_PENDING. That caused the
            // indicator to flicker/stick: a freshly-imported photo sits in
            // LOCAL_ONLY until the worker picks it up and flips it to
            // UPLOAD_PENDING (line ~307 in PhotoSyncWorker), then to
            // UPLOADED or UPLOAD_FAILED on completion. During WorkManager's
            // scheduling delay and during retry-backoff the photo is in
            // UPLOAD_PENDING but no upload is actively running, so the
            // indicator would show "Syncing…" indefinitely.
            //
            // The new semantics: anything that isn't UPLOADED or
            // UPLOAD_FAILED is "pending sync". The indicator now appears
            // at import time (LOCAL_ONLY) and clears only when every photo
            // has reached a terminal state. The gallery top-bar text was
            // updated accordingly (see GalleryScreen.kt) to read
            // "N photos to sync" instead of "Syncing N…" — accurate for
            // both the LOCAL_ONLY and UPLOAD_PENDING cases.
            val filteredPending = filteredPhotos.count {
                it.syncState == SyncState.UPLOAD_PENDING ||
                    it.syncState == SyncState.LOCAL_ONLY
            }

            // If the selected filter + search yield zero matches, show Empty
            // so the gallery placeholder appears (e.g. user has only photos,
            // picks the "Files" filter — show the empty state with the FAB).
            //
            // ─── Bug 1 fix: pass filter + searchQuery to Empty ──────────────
            // Same reason as above: the gallery header needs to keep showing
            // the chips so the user can switch back to "All" or clear the
            // search query when the filtered list is empty.
            if (filteredPhotos.isEmpty()) {
                GalleryUiState.Empty(filter = filter, searchQuery = searchQuery)
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