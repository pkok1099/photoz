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
 * "All" (everything), "Photos" (images only), "Videos", and "Files"
 * (DOCUMENT / ARCHIVE / AUDIO) via the FilterChip row at the top of the
 * gallery.
 *
 * @since file-upload feature — originally just PHOTOS / FILES
 * @since search-filter feature — added ALL + VIDEOS for finer-grained chips
 */
enum class GalleryFilter {
    /** Show every photo type (image, video, file). */
    ALL,

    /** Show image types only (JPEG, PNG, GIF, HEIC, WEBP). */
    PHOTOS,

    /** Show video types only (MP4, MPEG, WEBM, MOV, MKV). */
    VIDEOS,

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
         * Current gallery filter (All / Photos / Videos / Files). Drives the
         * FilterChip row selection and the client-side filter applied to the
         * photo list.
         *
         * @since file-upload feature
         * @since search-filter feature — added ALL + VIDEOS
         */
        val filter: GalleryFilter = GalleryFilter.ALL,
        /**
         * Current search query entered in the gallery search bar. Empty string
         * means "no filter" — show every photo that matches [filter]. Non-empty
         * means "show only photos whose filename contains this string
         * (case-insensitive)".
         *
         * Drives the client-side filter applied in
         * [onlasdan.gallery.gallery.ui.GalleryUiStateFactory.create].
         *
         * @since search-filter feature — filename search
         */
        val searchQuery: String = "",
    ) : GalleryUiState
}
