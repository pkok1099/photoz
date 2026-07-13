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
import onlasdan.gallery.gallery.components.ImportChoice
import onlasdan.gallery.gallery.components.PhotoTile
import onlasdan.gallery.sort.domain.Sort

sealed interface GalleryUiEvent {
	data class OpenPhoto(
		val item: PhotoTile,
	) : GalleryUiEvent

	data class OnDelete(
		val items: List<String>,
	) : GalleryUiEvent

	data class OnExport(
		val items: List<String>,
		val target: Uri?,
	) : GalleryUiEvent

	data object OnAddToAlbum : GalleryUiEvent

	data object CancelAlbumSelection : GalleryUiEvent

	data class OnImportChoice(
		val choice: ImportChoice,
	) : GalleryUiEvent

	data class SortChanged(
		val sort: Sort,
	) : GalleryUiEvent

	/**
	 * User changed the All/Photos/Videos/Files filter chip in the gallery header.
	 *
	 * @since file-upload feature
	 * @since search-filter feature — added ALL + VIDEOS chips
	 */
	data class FilterChanged(
		val filter: GalleryFilter,
	) : GalleryUiEvent

	/**
	 * User typed in the gallery search bar. The text is a case-insensitive
	 * "filename contains" filter applied on top of the current [FilterChanged]
	 * type filter. Empty string means "no text filter".
	 *
	 * @since search-filter feature — filename search
	 */
	data class SearchQueryChanged(
		val query: String,
	) : GalleryUiEvent

	/**
	 * User tapped "Restore from backup" in the gallery's overflow menu.
	 *
	 * Triggers a re-download of thumbnails from the cloud backup (via
	 * [onlasdan.gallery.sync.rclone.RepoManager.restoreThumbnailsFromPacks]).
	 * Useful when the user has deleted local thumbnails, or when a prior
	 * restore was interrupted and they want to re-sync without re-logging-in.
	 *
	 * @since v9 followup (Bug 2) — Restore button alongside Export
	 */
	data object OnRestoreFromBackup : GalleryUiEvent

	/**
	 * User picked "Restore original" from the multi-selection dropdown.
	 *
	 * Per-photo granular restore: for each selected UUID, calls
	 * [onlasdan.gallery.sync.work.SyncRestorer.ensureLocalOriginal] to
	 * download the encrypted original file from the cloud backup. This is
	 * the "Bug 4" complement to the bulk [OnRestoreFromBackup] action —
	 * the user can now restore just the photos they care about instead of
	 * pulling every original.
	 *
	 * Idempotent: photos whose local original already exists are skipped
	 * (the underlying SyncRestorer short-circuits).
	 *
	 * @since Bug 4 — granular per-photo restore
	 */
	data class OnRestoreOriginals(
		val uuids: List<String>,
	) : GalleryUiEvent

	/**
	 * Sprint 4 / M2 — User picked "Mark as favorite" / "Remove from favorites"
	 * from the multi-selection dropdown.
	 *
	 * Per-photo granular toggle: for each selected UUID, calls
	 * [onlasdan.gallery.model.repositories.PhotoRepository.toggleFavorite]
	 * to flip the `is_favorite` flag. After the toggle, the gallery's Flow
	 * observer re-emits with updated heart badges.
	 *
	 * @since v12 — Sprint 4 / M2 favorites
	 */
	data class OnToggleFavorite(
		val items: List<String>,
		val isFavorite: Boolean,
	) : GalleryUiEvent
}
