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

package onlasdan.gallery.gallery.components

import android.content.res.Configuration
import android.net.Uri
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import onlasdan.gallery.R
import onlasdan.gallery.model.database.entity.PhotoType
import onlasdan.gallery.other.extensions.launchAndIgnoreTimer
import onlasdan.gallery.settings.ui.compose.LocalConfig
import onlasdan.gallery.sync.domain.SyncState
import onlasdan.gallery.transcoding.compose.model.EncryptedImageRequestData
import onlasdan.gallery.transcoding.compose.rememberEncryptedImagePainter
import onlasdan.gallery.ui.components.ConfirmationDialog
import onlasdan.gallery.ui.components.MagicFab
import onlasdan.gallery.ui.components.MultiSelectionMenu
import onlasdan.gallery.ui.theme.AppTheme

private const val PORTRAIT_COLUMN_COUNT = 3
private const val LANDSCAPE_COLUMN_COUNT = 6

@Composable
fun PhotoGallery(
    photos: List<PhotoTile>,
    albumName: String?,
    multiSelectionState: MultiSelectionState,
    onOpenPhoto: (PhotoTile) -> Unit,
    onExport: (Uri?) -> Unit,
    onDelete: () -> Unit,
    onImportChoice: (ImportChoice) -> Unit,
    additionalMultiSelectionActions: @Composable (ColumnScope.() -> Unit),
    /**
     * Called when the user taps the "Add to album" icon on the multi-selection
     * bar. The caller (typically GalleryContent) opens the album picker dialog
     * with the currently-selected UUIDs.
     *
     * @since batch-operations feature — surfaced Add-to-album as a one-tap
     *   icon on the selection bar (was previously only in the More dropdown).
     */
    onAddToAlbum: () -> Unit = {},
    /**
     * Sprint 5 / M2 finish — Called when the user taps the heart icon on the
     * multi-selection bar to mark all selected photos as favorite.
     *
     * The caller (GalleryContent → GalleryViewModel.onToggleFavorite) iterates
     * the selected UUIDs and calls PhotoRepository.toggleFavorite for each.
     * After the toggle, the gallery's Flow observer re-emits with updated
     * heart badges.
     *
     * @since v12 — Sprint 5 / M2 favorites finish
     */
    onMarkFavorite: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val activity = LocalActivity.current
    var importMenuBottomSheetVisible by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Hide magic fab menu when multi selection active
    LaunchedEffect(multiSelectionState.isActive.value) {
        if (multiSelectionState.isActive.value) {
            importMenuBottomSheetVisible = false
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        PhotoGrid(
            photos = photos,
            multiSelectionState = multiSelectionState,
            openPhoto = onOpenPhoto,
        )

        AnimatedVisibility(
            visible = multiSelectionState.isActive.value.not(),
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
            modifier = Modifier
                .align(Alignment.BottomEnd)
        ) {
            MagicFab(
                label = stringResource(R.string.import_menu_fab_label),
                onClick = {
                    importMenuBottomSheetVisible = true
                }
            )
        }

        ImportMenuBottomSheet(
            open = importMenuBottomSheetVisible,
            onDismissRequest = {
                importMenuBottomSheetVisible = false
            },
            onImportChoice = onImportChoice,
            albumName = albumName,
        )

        var showDeleteConfirmationDialog by remember {
            mutableStateOf(false)
        }

        var showExportConfirmationDialog by remember {
            mutableStateOf(false)
        }

        var exportDirectoryUri by remember { mutableStateOf<Uri?>(null) }

        val pickExportTargetLauncher =
            rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { exportTarget ->
                exportTarget ?: return@rememberLauncherForActivityResult
                exportDirectoryUri = exportTarget
                showExportConfirmationDialog = true
            }

        ConfirmationDialog(
            show = showDeleteConfirmationDialog,
            onDismissRequest = { showDeleteConfirmationDialog = false },
            text = stringResource(
                R.string.delete_are_you_sure,
                multiSelectionState.selectedItems.value.size
            ),
            onConfirm = {
                onDelete()
                multiSelectionState.cancelSelection()
            }
        )

        ConfirmationDialog(
            show = showExportConfirmationDialog,
            onDismissRequest = { showExportConfirmationDialog = false },
            text = stringResource(
                if (LocalConfig.current?.deleteExportedFiles == true) {
                    R.string.export_and_delete_are_you_sure
                } else {
                    R.string.export_are_you_sure
                },
                multiSelectionState.selectedItems.value.size
            ),
            onConfirm = {
                onExport(exportDirectoryUri)
                multiSelectionState.cancelSelection()
            }
        )

        MultiSelectionMenu(
            modifier = Modifier.align(Alignment.BottomCenter),
            multiSelectionState = multiSelectionState,
            // @since batch-operations feature — surface the three most common
            //   batch actions (Delete, Export, Add-to-album) as one-tap icon
            //   buttons directly on the selection bar. The More dropdown
            //   (below) retains Select All + text-label versions of the same
            //   actions for users who prefer the menu.
            barActions = {
                IconButton(onClick = {
                    showDeleteConfirmationDialog = true
                }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_delete),
                        contentDescription = stringResource(R.string.common_delete),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
                IconButton(onClick = {
                    pickExportTargetLauncher.launchAndIgnoreTimer(
                        input = null,
                        activity = activity,
                    )
                }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_export),
                        contentDescription = stringResource(R.string.common_export),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
                IconButton(onClick = {
                    onAddToAlbum()
                }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_folder),
                        contentDescription = stringResource(R.string.menu_ms_add_to_album),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
                // Sprint 5 / M2 finish — one-tap "Mark as favorite" icon on
                // the multi-selection bar. Toggles is_favorite=true for every
                // selected photo. (Removing favorite is via the More dropdown
                // below — keeping the bar uncluttered with just the most common
                // "add to favorites" action.)
                IconButton(onClick = {
                    onMarkFavorite()
                    multiSelectionState.cancelSelection()
                }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_favorite),
                        contentDescription = stringResource(R.string.gallery_action_toggle_favorite),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            },
        ) {
            DropdownMenuItem(
                leadingIcon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_select_all),
                        contentDescription = null
                    )
                },
                text = { Text(stringResource(R.string.menu_ms_select_all)) },
                onClick = {
                    multiSelectionState.selectAll()
                    multiSelectionState.dismissMore()
                },
            )
            DropdownMenuItem(
                leadingIcon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_delete),
                        contentDescription = null
                    )
                },
                text = { Text(stringResource(R.string.common_delete)) },
                onClick = {
                    showDeleteConfirmationDialog = true
                    multiSelectionState.dismissMore()
                },
            )
            DropdownMenuItem(
                leadingIcon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_export),
                        contentDescription = null
                    )
                },
                text = { Text(stringResource(R.string.common_export)) },
                onClick = {
                    pickExportTargetLauncher.launchAndIgnoreTimer(
                        input = null,
                        activity = activity,
                    )
                    multiSelectionState.dismissMore()
                },
            )

            additionalMultiSelectionActions()
        }
    }
}

@Composable
private fun PhotoGrid(
    photos: List<PhotoTile>,
    multiSelectionState: MultiSelectionState,
    openPhoto: (PhotoTile) -> Unit,
    modifier: Modifier = Modifier,
) {
    val gridState: LazyGridState = rememberLazyGridState()

    val columnCount = when (LocalConfiguration.current.orientation) {
        Configuration.ORIENTATION_PORTRAIT -> PORTRAIT_COLUMN_COUNT
        Configuration.ORIENTATION_LANDSCAPE -> LANDSCAPE_COLUMN_COUNT
        else -> PORTRAIT_COLUMN_COUNT
    }

    val haptic = LocalHapticFeedback.current

    LazyVerticalGrid(
        columns = GridCells.Fixed(columnCount),
        modifier = modifier.fillMaxWidth(),
        state = gridState
    ) {
        items(photos, key = { it.uuid }) {
            GalleryPhotoTile(
                photoTile = it,
                multiSelectionActive = multiSelectionState.isActive.value,
                onClicked = {
                    if (multiSelectionState.isActive.value.not()) {
                        openPhoto(it)
                        return@GalleryPhotoTile
                    }

                    if (multiSelectionState.selectedItems.value.contains(it.uuid)) {
                        multiSelectionState.deselectItem(it.uuid)
                    } else {
                        multiSelectionState.selectItem(it.uuid)
                    }
                    
                    haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                },
                selected = multiSelectionState.selectedItems.value.contains(it.uuid),
                onLongPress = {
                    if (multiSelectionState.isActive.value.not()) {
                        multiSelectionState.selectItem(it.uuid)
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                },
                modifier = Modifier.animateItem(),
            )
        }
    }
}

private val VideoIconSize = 20.dp
private val SelectedPadding = 15.dp
private val CheckmarkPadding = SelectedPadding - 9.dp
private val SyncBadgeSize = 18.dp
private val SyncBadgeIconSize = 12.dp

@Composable
fun Modifier.multiSelectionItem(selected: Boolean): Modifier {
    val animatedPadding by animateDpAsState(
        targetValue = if (selected) { SelectedPadding } else { 0.dp }
    )
    val animatedShape by animateDpAsState(
        targetValue = if (selected) { 12.dp } else { 0.dp }
    )

    return this
        .padding(animatedPadding)
        .clip(RoundedCornerShape(animatedShape))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GalleryPhotoTile(
    modifier: Modifier = Modifier,
    photoTile: PhotoTile,
    multiSelectionActive: Boolean,
    selected: Boolean,
    onClicked: () -> Unit,
    onLongPress: () -> Unit,
) {
    Box(
        modifier = modifier
            .padding(.5.dp)
            .combinedClickable(
                role = Role.Image,
                onClick = onClicked,
                onLongClick = onLongPress,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            )
    ) {
        val contentModifier = Modifier
            .multiSelectionItem(selected)
            .fillMaxSize()
            .aspectRatio(1f)

        if (LocalInspectionMode.current) {
            Box(
                modifier = contentModifier.background(Color.DarkGray)
            )
        } else {
            val requestData = remember(photoTile) {
                EncryptedImageRequestData(
                    internalFileName = photoTile.internalThumbnailFileName,
                    mimeType = photoTile.type.mimeType
                )
            }

            Image(
                painter = rememberEncryptedImagePainter(requestData),
                contentDescription = photoTile.fileName,
                modifier = contentModifier
            )
        }

        AnimatedVisibility(
            visible = photoTile.type.isVideo && !selected,
            enter = scaleIn(),
            exit = scaleOut(),
            modifier = Modifier
                .padding(2.dp)
                .size(VideoIconSize)
                .align(Alignment.BottomStart)
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_videocam),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier
                    .dropShadow(
                        shape = RoundedCornerShape(12.dp),
                        shadow = Shadow(
                            radius = 6.dp,
                            alpha = 0.3f
                        )
                    )
            )
        }

        AnimatedVisibility(
            visible = photoTile.pinned && !selected,
            enter = scaleIn(),
            exit = scaleOut(),
            modifier = Modifier
                .padding(2.dp)
                .size(VideoIconSize)
                .align(Alignment.TopEnd)
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_pin),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier
                    .dropShadow(
                        shape = RoundedCornerShape(12.dp),
                        shadow = Shadow(
                            radius = 6.dp,
                            alpha = 0.3f
                        )
                    )
            )
        }

        // ─── Sprint 4 / M2 — Favorite heart badge ───────────────────────────
        // Small heart icon at the top-START corner (opposite of the pin badge
        // at top-END) so they don't overlap. Only shown when the photo is
        // marked as favorite AND multi-selection is not active (selection
        // replaces the badges with a checkmark overlay via multiSelectionItem).
        AnimatedVisibility(
            visible = photoTile.isFavorite && !selected,
            enter = scaleIn(),
            exit = scaleOut(),
            modifier = Modifier
                .padding(2.dp)
                .size(VideoIconSize)
                .align(Alignment.TopStart)
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_favorite),
                contentDescription = null,
                tint = Color.Red,
                modifier = Modifier
                    .dropShadow(
                        shape = RoundedCornerShape(12.dp),
                        shadow = Shadow(
                            radius = 6.dp,
                            alpha = 0.3f
                        )
                    )
            )
        }

        // ─── Sync state badge (PR2) ──────────────────────────────────────────
        // Small icon in the bottom-right corner indicating per-photo cloud sync
        // status. LOCAL_ONLY photos show no badge (keeps the gallery visually
        // quiet right after import — most photos briefly sit in LOCAL_ONLY
        // before the WorkManager upload job fires).
        AnimatedVisibility(
            visible = photoTile.syncState != SyncState.LOCAL_ONLY && !selected,
            enter = scaleIn(),
            exit = scaleOut(),
            modifier = Modifier
                .padding(4.dp)
                .align(Alignment.BottomEnd)
        ) {
            val iconTint = when (photoTile.syncState) {
                SyncState.UPLOADED -> Color(0xFF66BB6A)      // green
                SyncState.UPLOAD_PENDING -> Color(0xFFFFCA28) // amber
                SyncState.UPLOAD_FAILED -> Color(0xFFEF5350)  // red
                SyncState.LOCAL_ONLY -> Color.White
            }
            val iconPainter = when (photoTile.syncState) {
                SyncState.UPLOADED -> painterResource(R.drawable.ic_cloud_done)
                SyncState.UPLOAD_PENDING -> painterResource(R.drawable.ic_cloud_upload)
                SyncState.UPLOAD_FAILED -> painterResource(R.drawable.ic_warning) // Warning is in material-icons-core, but use our own drawable for consistency
                SyncState.LOCAL_ONLY -> painterResource(R.drawable.ic_cloud) // unreachable — kept for exhaustiveness
            }
            Box(
                modifier = Modifier
                    .size(SyncBadgeSize)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = iconPainter,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(SyncBadgeIconSize),
                )
            }
        }

        AnimatedVisibility(
            visible = multiSelectionActive && selected,
            enter = scaleIn(),
            exit = scaleOut(),
            ) {
            Icon(
                painter = painterResource(R.drawable.ic_check_circle),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier
                    .padding(CheckmarkPadding)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.background)
                    .align(Alignment.TopStart)
            )
        }
    }
}

@Preview
@Composable
private fun PhotoGridPreview() {
    AppTheme {
        Scaffold {
            PhotoGallery(
                modifier = Modifier.padding(it),
                photos = listOf(
                    PhotoTile("", PhotoType.JPEG, "1"),
                    PhotoTile("", PhotoType.MP4, "2"),
                    PhotoTile("", PhotoType.MP4, "3"),
                    PhotoTile("", PhotoType.JPEG, "4"),
                    PhotoTile("", PhotoType.JPEG, "5"),
                    PhotoTile("", PhotoType.MP4, "6"),
                ),
                albumName = null,
                multiSelectionState = MultiSelectionState(
                    allItems = listOf("1", "2", "3"),
                ),
                onOpenPhoto = {},
                onDelete = {},
                onExport = {},
                onImportChoice = {},
                additionalMultiSelectionActions = {},
            )
        }
    }
}

@Preview
@Composable
private fun PhotoGridPreviewWithSelection() {
    AppTheme {
        Scaffold {
            PhotoGallery(
                modifier = Modifier.padding(it),
                photos = listOf(
                    PhotoTile("", PhotoType.JPEG, "1"),
                    PhotoTile("", PhotoType.MP4, "2"),
                    PhotoTile("", PhotoType.MP4, "3"),
                    PhotoTile("", PhotoType.JPEG, "4"),
                    PhotoTile("", PhotoType.JPEG, "5"),
                    PhotoTile("", PhotoType.MP4, "6"),
                ),
                albumName = null,
                multiSelectionState = MultiSelectionState(
                    allItems = listOf("1", "2", "3"),
                ).apply {
                    selectItem("2")
                    selectItem("3")
                },
                onOpenPhoto = {},
                onDelete = {},
                onExport = {},
                onImportChoice = {},
                additionalMultiSelectionActions = {},
            )
        }
    }
}