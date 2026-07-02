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
import onlasdan.gallery.gallery.ui.GalleryFilter
import onlasdan.gallery.gallery.ui.GalleryUiEvent
import onlasdan.gallery.gallery.ui.GalleryUiState
import onlasdan.gallery.gallery.components.MultiSelectionState
import onlasdan.gallery.gallery.components.PhotoGallery
import onlasdan.gallery.gallery.components.PhotoTile
import onlasdan.gallery.gallery.components.rememberMultiSelectionState
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
    Column(modifier = modifier.fillMaxSize()) {
        // ─── Search bar + All/Photos/Videos/Files filter chip row ──────────
        // Sits above the photo grid. The search bar drives the
        // [GalleryUiEvent.SearchQueryChanged] event (filename contains,
        // case-insensitive); the chips drive [GalleryUiEvent.FilterChanged].
        // Both filters compose in [GalleryUiStateFactory.create].
        //
        // @since file-upload feature — original Photos/Files chip row.
        // @since search-filter feature — added the search bar + the All and
        //   Videos chips, and split Photos to be images-only (videos moved to
        //   their own chip).
        GallerySearchBar(
            query = uiState.searchQuery,
            onQueryChange = { handleUiEvent(GalleryUiEvent.SearchQueryChanged(it)) },
        )
        GalleryFilterRow(
            selected = uiState.filter,
            onSelect = { handleUiEvent(GalleryUiEvent.FilterChanged(it)) },
        )
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
                        targetUri
                    )
                )
            },
            onDelete = {
                handleUiEvent(
                    GalleryUiEvent.OnDelete(
                        multiSelectionState.selectedItems.value.toList()
                    )
                )
            },
            onImportChoice = {
                handleUiEvent(GalleryUiEvent.OnImportChoice(it))
            },
            additionalMultiSelectionActions = {
                HorizontalDivider()
                DropdownMenuItem(
                    leadingIcon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_folder),
                            contentDescription = null
                        )
                    },
                    text = { Text(stringResource(R.string.menu_ms_add_to_album)) },
                    onClick = {
                        handleUiEvent(GalleryUiEvent.OnAddToAlbum)
                        multiSelectionState.dismissMore()
                    },
                )
            }
        )
    }
}

/**
 * Search bar at the top of the gallery. Filters the photo list by filename
 * (case-insensitive contains). The trailing clear button (X) empties the
 * query — easier than long-press + delete on a soft keyboard.
 *
 * @since search-filter feature — filename search
 */
@Composable
private fun GallerySearchBar(
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
        trailingIcon = if (query.isNotEmpty()) {
            {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_close),
                        contentDescription = stringResource(R.string.gallery_search_clear),
                    )
                }
            }
        } else null,
        singleLine = true,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
        ),
        modifier = Modifier
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
 * @since file-upload feature — originally just Photos / Files
 * @since search-filter feature — added All + Videos
 */
@Composable
private fun GalleryFilterRow(
    selected: GalleryFilter,
    onSelect: (GalleryFilter) -> Unit,
) {
    androidx.compose.foundation.layout.Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
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
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFFFFF, showSystemUi = true)
@Composable
fun GalleryContentPreview() {
    AppTheme {
        GalleryContent(
            uiState = GalleryUiState.Content(
                photos = listOf(
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
            multiSelectionState = rememberMultiSelectionState(items = emptyList())
        )
    }
}

