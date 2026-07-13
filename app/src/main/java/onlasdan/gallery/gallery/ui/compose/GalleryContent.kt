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

package onlasdan.gallery.gallery.ui.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import onlasdan.gallery.R
import onlasdan.gallery.gallery.components.MultiSelectionState
import onlasdan.gallery.gallery.components.PhotoGallery
import onlasdan.gallery.gallery.components.PhotoTile
import onlasdan.gallery.gallery.components.rememberMultiSelectionState
import onlasdan.gallery.gallery.ui.GalleryFilter
import onlasdan.gallery.gallery.ui.GalleryUiEvent
import onlasdan.gallery.gallery.ui.GalleryUiState
import onlasdan.gallery.model.database.entity.PhotoType
import onlasdan.gallery.sort.domain.SortConfig
import onlasdan.gallery.ui.theme.AppTheme
import java.util.UUID

@Composable
fun GalleryContent(
	uiState: GalleryUiState.Content,
	handleUiEvent: (GalleryUiEvent) -> Unit,
	multiSelectionState: MultiSelectionState,
	modifier: Modifier = Modifier,
) {
	// ─── Bug 1 fix: search bar + filter chips moved to GalleryScreen ──────────
	// The search bar ([GallerySearchBar]) and the All/Photos/Videos/Files
	// FilterChip row ([GalleryFilterRow]) used to live here, inside the
	// Content-only branch of GalleryScreen's `when (uiState)` block. That
	// meant the chips disappeared whenever the gallery was empty — e.g.
	// selecting the "Videos" filter when there are no videos made the gallery
	// a dead-end: the user couldn't switch back to "All" or "Photos" because
	// the chips were gone. They now render in GalleryScreen above the
	// `when (uiState)` block so they're ALWAYS visible, regardless of whether
	// the gallery has content or shows the empty placeholder.
	//
	// This composable now only renders the photo grid itself (plus the FAB
	// and the multi-selection menu that ride on top of it).
	Column(modifier = modifier.fillMaxSize()) {
		PhotoGallery(
			modifier = Modifier.fillMaxSize(),
			photos = uiState.photos,
			albumName = null,
			multiSelectionState = multiSelectionState,
			onOpenPhoto = { handleUiEvent(GalleryUiEvent.OpenPhoto(it)) },
			onExport = { targetUri ->
				handleUiEvent(
					GalleryUiEvent.OnExport(
						multiSelectionState.selectedItems.value.toList(),
						targetUri,
					),
				)
			},
			onDelete = {
				handleUiEvent(
					GalleryUiEvent.OnDelete(
						multiSelectionState.selectedItems.value.toList(),
					),
				)
			},
			onImportChoice = {
				handleUiEvent(GalleryUiEvent.OnImportChoice(it))
			},
			// @since batch-operations feature — one-tap "Add to album" icon
			//   on the multi-selection bar. Was previously only reachable via
			//   the More dropdown.
			onAddToAlbum = {
				handleUiEvent(GalleryUiEvent.OnAddToAlbum)
			},
			// Sprint 5 / M2 finish — one-tap "Mark as favorite" icon on the
			// multi-selection bar. Toggles is_favorite=true for every selected
			// photo (the inverse "Remove from favorites" lives in the More
			// dropdown above).
			onMarkFavorite = {
				handleUiEvent(
					GalleryUiEvent.OnToggleFavorite(
						items = multiSelectionState.selectedItems.value.toList(),
						isFavorite = true,
					),
				)
			},
			additionalMultiSelectionActions = {
				HorizontalDivider()
				DropdownMenuItem(
					leadingIcon = {
						Icon(
							painter = painterResource(R.drawable.ic_folder),
							contentDescription = null,
							modifier = Modifier.size(24.dp),
						)
					},
					text = { Text(stringResource(R.string.menu_ms_add_to_album)) },
					onClick = {
						handleUiEvent(GalleryUiEvent.OnAddToAlbum)
						multiSelectionState.dismissMore()
					},
				)
				DropdownMenuItem(
					leadingIcon = {
						Icon(
							painter = painterResource(R.drawable.ic_download),
							contentDescription = null,
							modifier = Modifier.size(24.dp),
						)
					},
					text = { Text(stringResource(R.string.menu_ms_restore_original)) },
					onClick = {
						handleUiEvent(
							GalleryUiEvent.OnRestoreOriginals(
								multiSelectionState.selectedItems.value.toList(),
							),
						)
						multiSelectionState.dismissMore()
					},
				)
				// Sprint 5 / M2 finish — "Remove from favorites" in the More
				// dropdown. The "Mark as favorite" one-tap icon is on the bar
				// itself (see PhotoGallery.barActions); this is the inverse
				// action for users who selected already-favorited photos.
				DropdownMenuItem(
					leadingIcon = {
						Icon(
							painter = painterResource(R.drawable.ic_favorite),
							contentDescription = null,
							modifier = Modifier.size(24.dp),
						)
					},
					text = { Text(stringResource(R.string.gallery_action_remove_favorite)) },
					onClick = {
						handleUiEvent(
							GalleryUiEvent.OnToggleFavorite(
								items = multiSelectionState.selectedItems.value.toList(),
								isFavorite = false,
							),
						)
						multiSelectionState.dismissMore()
						multiSelectionState.cancelSelection()
					},
				)
			},
		)
	}
}

/**
 * Search bar at the top of the gallery. Filters the photo list by filename
 * (case-insensitive contains). The trailing clear button (X) empties the
 * query — easier than long-press + delete on a soft keyboard.
 *
 * Rendered by [GalleryScreen] ABOVE the `when (uiState)` block so it stays
 * visible in both Empty and Content states — Bug 1 fix.
 *
 * @since search-filter feature — filename search
 */
@Composable
fun GallerySearchBar(
	query: String,
	onQueryChange: (String) -> Unit,
) {
	OutlinedTextField(
		value = query,
		onValueChange = onQueryChange,
		placeholder = { Text(stringResource(R.string.gallery_search_placeholder)) },
		leadingIcon = {
			Icon(
				painter = painterResource(R.drawable.ic_image),
				contentDescription = null,
			)
		},
		trailingIcon =
			if (query.isNotEmpty()) {
				{
					IconButton(onClick = { onQueryChange("") }) {
						Icon(
							painter = painterResource(R.drawable.ic_close),
							contentDescription = stringResource(R.string.gallery_search_clear),
						)
					}
				}
			} else {
				null
			},
		singleLine = true,
		colors =
			TextFieldDefaults.colors(
				focusedContainerColor = Color.Transparent,
				unfocusedContainerColor = Color.Transparent,
			),
		modifier =
			Modifier
				.fillMaxWidth()
				.padding(horizontal = 16.dp, vertical = 4.dp),
	)
}

/**
 * All/Photos/Videos/Files filter row at the top of the gallery. Four
 * FilterChips in a horizontally-spaced row. "All" (default) shows every tile,
 * "Photos" shows image types only, "Videos" shows video types only, and
 * "Files" shows DOCUMENT/ARCHIVE/AUDIO tiles.
 *
 * Rendered by [GalleryScreen] ABOVE the `when (uiState)` block so it stays
 * visible in both Empty and Content states — Bug 1 fix: without this, picking
 * a filter with no matches (e.g. "Videos" with no videos) made the gallery
 * a dead-end because the chips disappeared along with the empty state.
 *
 * @since file-upload feature — originally just Photos / Files
 * @since search-filter feature — added All + Videos
 */
@Composable
fun GalleryFilterRow(
	selected: GalleryFilter,
	onSelect: (GalleryFilter) -> Unit,
) {
	androidx.compose.foundation.layout.Row(
		horizontalArrangement = Arrangement.spacedBy(8.dp),
		modifier =
			Modifier
				.fillMaxWidth()
				.padding(horizontal = 16.dp, vertical = 8.dp),
	) {
		FilterChip(
			selected = selected == GalleryFilter.ALL,
			onClick = { onSelect(GalleryFilter.ALL) },
			label = { Text(stringResource(R.string.gallery_filter_all)) },
		)
		FilterChip(
			selected = selected == GalleryFilter.PHOTOS,
			onClick = { onSelect(GalleryFilter.PHOTOS) },
			label = { Text(stringResource(R.string.gallery_filter_photos)) },
		)
		FilterChip(
			selected = selected == GalleryFilter.VIDEOS,
			onClick = { onSelect(GalleryFilter.VIDEOS) },
			label = { Text(stringResource(R.string.gallery_filter_videos)) },
		)
		FilterChip(
			selected = selected == GalleryFilter.FILES,
			onClick = { onSelect(GalleryFilter.FILES) },
			label = { Text(stringResource(R.string.gallery_filter_files)) },
			colors = FilterChipDefaults.filterChipColors(),
		)
		// Sprint 4 / M2 — Favorites filter chip
		FilterChip(
			selected = selected == GalleryFilter.FAVORITES,
			onClick = { onSelect(GalleryFilter.FAVORITES) },
			label = { Text(stringResource(R.string.gallery_filter_favorites)) },
			colors = FilterChipDefaults.filterChipColors(),
		)
	}
}

@Preview(showBackground = true, backgroundColor = 0xFFFFFF, showSystemUi = true)
@Composable
fun GalleryContentPreview() {
	AppTheme {
		GalleryContent(
			uiState =
				GalleryUiState.Content(
					photos =
						listOf(
							PhotoTile("", PhotoType.JPEG, UUID.randomUUID().toString()),
							PhotoTile("", PhotoType.MP4, UUID.randomUUID().toString()),
							PhotoTile("", PhotoType.GIF, UUID.randomUUID().toString()),
							PhotoTile("", PhotoType.MPEG, "1"),
							PhotoTile("", PhotoType.PNG, UUID.randomUUID().toString()),
							PhotoTile("", PhotoType.PNG, UUID.randomUUID().toString()),
							PhotoTile("", PhotoType.PNG, UUID.randomUUID().toString()),
							PhotoTile("", PhotoType.PNG, UUID.randomUUID().toString()),
							PhotoTile("", PhotoType.PNG, "2"),
							PhotoTile("", PhotoType.PNG, UUID.randomUUID().toString()),
							PhotoTile("", PhotoType.PNG, UUID.randomUUID().toString()),
							PhotoTile("", PhotoType.PNG, UUID.randomUUID().toString()),
							PhotoTile("", PhotoType.PNG, UUID.randomUUID().toString()),
							PhotoTile("", PhotoType.PNG, UUID.randomUUID().toString()),
							PhotoTile("", PhotoType.PNG, UUID.randomUUID().toString()),
						),
					showAlbumSelectionDialog = false,
					sort = SortConfig.Gallery.default,
				),
			handleUiEvent = {},
			multiSelectionState = rememberMultiSelectionState(items = emptyList()),
		)
	}
}
