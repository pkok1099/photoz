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

import android.net.Uri
import onlasdan.gallery.sort.domain.Sort
import onlasdan.gallery.gallery.components.PhotoTile

/**
 * Filter applied to the gallery's photo list. The user picks between
 * "Photos" (images + videos) and "Files" (DOCUMENT / ARCHIVE / AUDIO) via
 * the FilterChip row at the top of the gallery.
 *
 * @since file-upload feature
 */
enum class GalleryFilter {
    /** Show photo/video types only (JPEG, PNG, GIF, HEIC, MP4, WEBP, etc.). */
    PHOTOS,

    /** Show file types only (DOCUMENT, ARCHIVE, AUDIO). */
    FILES,
}

sealed interface GalleryUiState {

    data object Empty : GalleryUiState

    data class Content(
        val photos: List<PhotoTile> = emptyList(),
        val showAlbumSelectionDialog: Boolean = false,
        val sort: Sort,
        // @since PR2 sync — count of photos still pending upload, for the global
        // sync status indicator in the gallery top bar.
        val pendingSyncCount: Int = 0,
        /**
         * Current gallery filter (Photos vs Files). Drives the FilterChip row
         * selection and the client-side filter applied to the photo list.
         *
         * @since file-upload feature
         */
        val filter: GalleryFilter = GalleryFilter.PHOTOS,
    ) : GalleryUiState
}
