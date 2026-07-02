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

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import onlasdan.gallery.R
import onlasdan.gallery.gallery.components.AlbumPickerDialog
import onlasdan.gallery.gallery.components.ImportSharedDialog
import onlasdan.gallery.gallery.components.rememberMultiSelectionState
import onlasdan.gallery.gallery.ui.GalleryUiEvent
import onlasdan.gallery.gallery.ui.GalleryUiState
import onlasdan.gallery.gallery.ui.GalleryViewModel
import onlasdan.gallery.news.newfeatures.ui.NewFeaturesSheet
import onlasdan.gallery.sort.domain.SortConfig
import onlasdan.gallery.sort.ui.SortingMenu
import onlasdan.gallery.sort.ui.SortingMenuIconButton
import onlasdan.gallery.telemetry.ui.TelemetryOptInQuestionSheet
import onlasdan.gallery.ui.components.AppName
import onlasdan.gallery.ui.theme.AppTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    viewModel: GalleryViewModel,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    AppTheme {
        Scaffold(
            topBar = {
                LargeTopAppBar(
                    title = { AppName() },
                    windowInsets = WindowInsets.statusBars,
                    scrollBehavior = scrollBehavior,
                    actions = {
                        if (uiState is GalleryUiState.Content) {
                            val content = uiState as GalleryUiState.Content
                            // @since PR2 sync — global sync status indicator
                            GallerySyncStatusIndicator(
                                pendingCount = content.pendingSyncCount,
                            )

                            val sort = content.sort

                            var showSortMenu by remember { mutableStateOf(false) }

                            SortingMenuIconButton(
                                config = SortConfig.Gallery,
                                sort = sort,
                                onClick = { showSortMenu = true },
                            )

                            SortingMenu(
                                config = SortConfig.Gallery,
                                expanded = showSortMenu,
                                onDismissRequest = { showSortMenu = false },
                                sort = sort,
                                onSortChanged = { sort ->
                                    viewModel.handleUiEvent(GalleryUiEvent.SortChanged(sort))
                                }
                            )

                            // ─── v9 followup (Bug 2): overflow menu with Restore ──
                            // Houses the "Restore from backup" action that re-downloads
                            // thumbnails from the cloud. Placed AFTER the sort menu so
                            // the sort icon stays in its familiar spot; the 3-dot
                            // overflow is the rightmost action, matching Material
                            // guidance.
                            var showOverflowMenu by remember { mutableStateOf(false) }
                            IconButton(
                                onClick = { showOverflowMenu = true },
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_more_vert),
                                    contentDescription = stringResource(R.string.common_more),
                                )
                            }
                            DropdownMenu(
                                expanded = showOverflowMenu,
                                onDismissRequest = { showOverflowMenu = false },
                            ) {
                                DropdownMenuItem(
                                    leadingIcon = {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_restore),
                                            contentDescription = null,
                                        )
                                    },
                                    text = { Text(stringResource(R.string.menu_restore_from_backup)) },
                                    onClick = {
                                        showOverflowMenu = false
                                        viewModel.handleUiEvent(GalleryUiEvent.OnRestoreFromBackup)
                                    },
                                )
                            }
                        }
                    }
                )
            },
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection)
        ) { contentPadding ->
            val modifier = Modifier.padding(top = contentPadding.calculateTopPadding())

            when (uiState) {
                is GalleryUiState.Empty -> GalleryPlaceholder(
                    handleUiEvent = { viewModel.handleUiEvent(it) },
                    modifier = modifier,
                )

                is GalleryUiState.Content -> {
                    val contentUiState = uiState as GalleryUiState.Content
                    val multiSelectionState = rememberMultiSelectionState(
                        items = contentUiState.photos.map { it.uuid }
                    )

                    GalleryContent(
                        uiState = contentUiState,
                        handleUiEvent = { viewModel.handleUiEvent(it) },
                        multiSelectionState = multiSelectionState,
                        modifier = modifier,
                    )

                    AlbumPickerDialog(
                        visible = contentUiState.showAlbumSelectionDialog,
                        selectedItemIds = multiSelectionState.selectedItems.value.toList(),
                        onAlbumSelected = { multiSelectionState.cancelSelection() },
                        onDismissRequest = { viewModel.handleUiEvent(GalleryUiEvent.CancelAlbumSelection) }
                    )
                }
            }

            ImportSharedDialog()
        }

        NewFeaturesSheet()
        TelemetryOptInQuestionSheet()
    }
}

/**
 * Lightweight global sync status chip for the gallery top bar.
 *
 * Shows the count of photos still queued for upload. When the queue is empty, shows a subtle
 * "all synced" state. Kept minimal on purpose — this is a status hint, not a primary action.
 *
 * @since PR2 sync — global sync status indicator
 */
@Composable
private fun GallerySyncStatusIndicator(
    pendingCount: Int,
) {
    val isSyncing = pendingCount > 0
    val text = if (isSyncing) {
        stringResource(R.string.gallery_sync_status_syncing, pendingCount)
    } else {
        stringResource(R.string.gallery_sync_status_all_synced)
    }
    val tint = if (isSyncing) {
        Color(0xFFFFCA28) // amber — there's pending work
    } else {
        MaterialTheme.colorScheme.outline
    }
    val iconPainter = if (isSyncing) {
        painterResource(R.drawable.ic_cloud_upload)
    } else {
        painterResource(R.drawable.ic_cloud_done)
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(end = 8.dp),
    ) {
        Icon(
            painter = iconPainter,
            contentDescription = text,
            tint = tint,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            maxLines = 1,
        )
    }
}
