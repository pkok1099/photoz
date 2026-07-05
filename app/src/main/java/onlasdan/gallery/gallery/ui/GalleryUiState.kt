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

import onlasdan.gallery.gallery.components.PhotoTile
import onlasdan.gallery.sort.domain.Sort

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

	/**
	 * Sprint 4 / M2 — Show only photos marked as favorite in the current
	 * vault. Crosses type boundaries (a favorited video shows up here too).
	 *
	 * @since v12 — Sprint 4 / M2 favorites
	 */
	FAVORITES,
}

sealed interface GalleryUiState {
	/**
	 * Gallery is empty — either the user has imported nothing yet, or the
	 * current filter + search combination yields zero matches.
	 *
	 * Carries the current [filter] and [searchQuery] so the gallery header
	 * (search bar + filter chip row) can be rendered identically in both the
	 * Empty and Content states — Bug 1 fix: the chips MUST stay visible when
	 * the gallery is empty, otherwise the user can't switch back from a
	 * filter that has no matches (e.g. "Videos" with no videos imported).
	 *
	 * @since Bug 1 fix — Empty now carries filter + searchQuery
	 */
	data class Empty(
		val filter: GalleryFilter = GalleryFilter.ALL,
		val searchQuery: String = "",
	) : GalleryUiState

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
