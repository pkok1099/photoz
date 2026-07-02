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

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import onlasdan.gallery.R
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
    PhotoGallery(
        modifier = modifier.fillMaxSize(),
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

