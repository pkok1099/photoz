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

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.res.painterResource
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
fun GalleryScreen(viewModel: GalleryViewModel) {
	val uiState by viewModel.uiState.collectAsStateWithLifecycle()

	// ─── Bug 2 fix: switch from LargeTopAppBar + exitUntilCollapsedScrollBehavior ──
	// to a regular small TopAppBar + pinnedScrollBehavior. The LargeTopAppBar
	// reserves a large expanded-height for its title even when collapsed,
	// which left a noticeable empty gap between the top bar and the gallery
	// thumbnails. The small TopAppBar has a single, compact 64dp height with
	// no expanded/collapsed state, so the gap disappears and the search bar
	// + filter chips sit immediately under the top bar.
	val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

	AppTheme {
		Scaffold(
			topBar = {
				TopAppBar(
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
								},
							)
						}

						// ─── v9 followup (Bug 2): overflow menu with Restore ──
						// ALWAYS visible (even when gallery is empty) so the user
						// can restore from backup on a fresh install without needing
						// to have any local photos first.
						//
						// ─── Bug 3 fix: explicit icon sizes ──────────────────────────
						// The IconButton Icon and the DropdownMenuItem leadingIcon
						// both now have explicit `.size(24.dp)` modifiers. Material
						// 3's IconButton is already a 48dp touch target, but the
						// Icon inside defaults to whatever the vector drawable's
						// intrinsic size is (some of PhotoZ's drawables are
						// 48x48dp which made the overflow icon look oversized).
						// Pinning to 24dp matches the Material icon spec.
						var showOverflowMenu by remember { mutableStateOf(false) }
						IconButton(
							onClick = { showOverflowMenu = true },
						) {
							Icon(
								painter = painterResource(R.drawable.ic_more_vert),
								contentDescription = stringResource(R.string.common_more),
								modifier = Modifier.size(24.dp),
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
										modifier = Modifier.size(24.dp),
									)
								},
								text = { Text(stringResource(R.string.menu_restore_from_backup)) },
								onClick = {
									showOverflowMenu = false
									viewModel.handleUiEvent(GalleryUiEvent.OnRestoreFromBackup)
								},
							)
						}
					},
				)
			},
			modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
		) { contentPadding ->
			// ─── Bug 1 fix: search bar + filter chips ALWAYS visible ──────────
			// The All/Photos/Videos/Files filter chips and the search bar used
			// to live inside `GalleryContent`, which is only rendered in the
			// `GalleryUiState.Content` branch of the `when (uiState)` block
			// below. When the gallery became empty (e.g. user picked the
			// "Videos" filter but there are no videos), the chips disappeared
			// along with the empty state — making the gallery a dead-end: the
			// user couldn't switch back to "All" or "Photos" because the chips
			// were gone.
			//
			// Both composables now render ABOVE the `when (uiState)` block so
			// they stay visible in BOTH the Empty and Content states. The
			// current filter + searchQuery are pulled from whichever state is
			// active — `GalleryUiState.Empty` now carries those fields too
			// (Bug 1 fix in GalleryUiState.kt).
			//
			// Local snapshot of the delegated `uiState` so Kotlin can smart-
			// cast through the `when` below (delegated properties can't be
			// smart-cast directly).
			val state = uiState
			val currentFilter =
				when (state) {
					is GalleryUiState.Empty -> state.filter
					is GalleryUiState.Content -> state.filter
				}
			val currentSearchQuery =
				when (state) {
					is GalleryUiState.Empty -> state.searchQuery
					is GalleryUiState.Content -> state.searchQuery
				}

			Column(
				modifier = Modifier.padding(top = contentPadding.calculateTopPadding()),
			) {
				GallerySearchBar(
					query = currentSearchQuery,
					onQueryChange = {
						viewModel.handleUiEvent(GalleryUiEvent.SearchQueryChanged(it))
					},
				)
				GalleryFilterRow(
					selected = currentFilter,
					onSelect = {
						viewModel.handleUiEvent(GalleryUiEvent.FilterChanged(it))
					},
				)

				when (state) {
					is GalleryUiState.Empty ->
						GalleryPlaceholder(
							handleUiEvent = { viewModel.handleUiEvent(it) },
							modifier = Modifier,
						)

					is GalleryUiState.Content -> {
						val contentUiState = state
						val multiSelectionState =
							rememberMultiSelectionState(
								items = contentUiState.photos.map { it.uuid },
							)

						GalleryContent(
							uiState = contentUiState,
							handleUiEvent = { viewModel.handleUiEvent(it) },
							multiSelectionState = multiSelectionState,
							modifier = Modifier,
						)

						AlbumPickerDialog(
							visible = contentUiState.showAlbumSelectionDialog,
							selectedItemIds = multiSelectionState.selectedItems.value.toList(),
							onAlbumSelected = { multiSelectionState.cancelSelection() },
							onDismissRequest = { viewModel.handleUiEvent(GalleryUiEvent.CancelAlbumSelection) },
						)
					}
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
 * Shows the count of photos still queued for sync (LOCAL_ONLY or UPLOAD_PENDING).
 * When the queue is empty (every photo is UPLOADED or UPLOAD_FAILED), shows a
 * subtle "all synced" state. Kept minimal on purpose — this is a status hint,
 * not a primary action.
 *
 * ─── Item 3 fix: stuck-progress-indicator ──────────────────────────────
 * The pending count now includes both `LOCAL_ONLY` and `UPLOAD_PENDING`
 * photos (computed in [onlasdan.gallery.gallery.ui.GalleryUiStateFactory]).
 * Previously it counted only `UPLOAD_PENDING`, which caused the chip to
 * appear stuck whenever a worker was in WorkManager scheduling delay or
 * retry-backoff (the photo was still `UPLOAD_PENDING` but no upload was
 * actively running). The accompanying string was changed from
 * "Syncing N…" → "N photos to sync" so the label stays honest across
 * both states.
 *
 * @since PR2 sync — global sync status indicator
 * @since Item 3 fix — counts LOCAL_ONLY + UPLOAD_PENDING; text reflects that
 */
@Composable
private fun GallerySyncStatusIndicator(pendingCount: Int) {
	val isSyncing = pendingCount > 0
	val text =
		if (isSyncing) {
			stringResource(R.string.gallery_sync_status_syncing, pendingCount)
		} else {
			stringResource(R.string.gallery_sync_status_all_synced)
		}
	val tint =
		if (isSyncing) {
			MaterialTheme.colorScheme.secondary // amber — there's pending work
		} else {
			MaterialTheme.colorScheme.outline
		}
	val iconPainter =
		if (isSyncing) {
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
