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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
        // ─── Photos / Files filter chip row (file-upload feature) ────────────
        // Sits above the photo grid. The actual filtering happens in
        // [onlasdan.gallery.gallery.ui.GalleryUiStateFactory.create] —
        // this row only toggles the filterFlow selection on the ViewModel.
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
 * Photos/Files filter row at the top of the gallery. Two FilterChips in a
 * horizontally-spaced row — "Photos" (default) shows image+video tiles,
 * "Files" shows DOCUMENT/ARCHIVE/AUDIO tiles.
 *
 * @since file-upload feature
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
            selected = selected == GalleryFilter.PHOTOS,
            onClick = { onSelect(GalleryFilter.PHOTOS) },
            label = { Text(stringResource(R.string.gallery_filter_photos)) },
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

