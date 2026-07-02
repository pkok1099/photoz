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

import android.content.res.Resources
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import onlasdan.gallery.R
import onlasdan.gallery.gallery.albums.domain.AlbumRepository
import onlasdan.gallery.gallery.components.ImportChoice
import onlasdan.gallery.gallery.components.PhotoTile
import onlasdan.gallery.gallery.ui.importing.SharedUrisStore
import onlasdan.gallery.gallery.ui.navigation.GalleryNavigationEvent
import onlasdan.gallery.gallery.ui.navigation.PhotoAction
import onlasdan.gallery.model.repositories.ImportSource
import onlasdan.gallery.model.repositories.PhotoRepository
import onlasdan.gallery.sort.domain.SortConfig
import onlasdan.gallery.sort.domain.SortRepository
import onlasdan.gallery.sync.rclone.RepoManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class GalleryViewModel @Inject constructor(
    photoRepository: PhotoRepository,
    private val galleryUiStateFactory: GalleryUiStateFactory,
    private val sortRepository: SortRepository,
    // @since v9 followup (Bug 2) — needed by OnRestoreFromBackup to re-download
    // thumbnails from the cloud backup. The gallery's overflow menu has a new
    // "Restore from backup" option that calls [RepoManager.restoreThumbnailsFromPacks]
    // to re-sync thumbnails + [SyncRestorer.restoreAllOriginals] to download
    // the full original files back to local storage (path 1:1 inside PhotoZ).
    private val repoManager: RepoManager,
    private val syncRestorer: onlasdan.gallery.sync.work.SyncRestorer,
    private val resources: Resources,
) : ViewModel() {

    private val sortFlow = sortRepository.observeSortFor(albumUuid = null, default = SortConfig.Gallery.default)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val photosFlow = sortFlow.flatMapLatest { sort ->
        photoRepository.observeAll(sort)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), listOf())

    private val showAlbumSelectionDialog = MutableStateFlow(false)

    val uiState: StateFlow<GalleryUiState> = combine(
        photosFlow,
        showAlbumSelectionDialog,
        sortFlow,
    ) { photos, showAlbumSelection, sort ->
        galleryUiStateFactory.create(photos, showAlbumSelection, sort)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), GalleryUiState.Empty)

    private val eventsChannel = Channel<GalleryNavigationEvent>()
    val eventsFlow = eventsChannel.receiveAsFlow()

    private val photoActionsChannel = Channel<PhotoAction>()
    val photoActions = photoActionsChannel.receiveAsFlow()

    fun handleUiEvent(event: GalleryUiEvent) {
        when (event) {
            is GalleryUiEvent.OpenPhoto -> navigateToPhoto(event.item)
            is GalleryUiEvent.OnDelete -> onDeleteSelectedItems(event.items)
            is GalleryUiEvent.OnExport -> onExportSelectedItems(event.items, event.target)
            is GalleryUiEvent.OnAddToAlbum -> showAlbumSelectionDialog.value = true
            GalleryUiEvent.CancelAlbumSelection -> showAlbumSelectionDialog.value = false
            is GalleryUiEvent.OnImportChoice -> onImportChoice(event.choice)
            is GalleryUiEvent.SortChanged -> viewModelScope.launch {
                sortRepository.updateSortFor(albumUuid = null, sort = event.sort)
            }
            // @since v9 followup (Bug 2) — Restore from cloud backup. Re-runs
            // the pack-based thumbnail restore (downloads packs, extracts
            // thumbnails by offset+length, creates Photo rows for any registry
            // entries that don't have one yet). Non-fatal: failures are surfaced
            // as a toast; the user can retry.
            GalleryUiEvent.OnRestoreFromBackup -> onRestoreFromBackup()
        }
    }

    /**
     * Triggered by the gallery's overflow-menu "Restore from backup" item.
     *
     * Re-runs [RepoManager.restoreThumbnailsFromPacks] — downloads any missing
     * thumbnails from the cloud (via packs, with legacy individual-thumbnail
     * fallback for old repos) and creates Photo DB rows for registry entries
     * that don't have one yet. Idempotent: thumbnails that are already local
     * are skipped.
     *
     * Sends a [GalleryNavigationEvent.ShowToast] with the result so the user
     * gets feedback (started / success / failed).
     *
     * @since v9 followup (Bug 2) — Restore button alongside Export
     */
    private fun onRestoreFromBackup() {
        eventsChannel.trySend(
            GalleryNavigationEvent.ShowToast(
                resources.getString(R.string.menu_restore_from_backup_toast_started),
            ),
        )
        viewModelScope.launch {
            try {
                // Step 1: restore thumbnails + create DB rows from registry
                val thumbsRestored = repoManager.restoreThumbnailsFromPacks()

                // Step 2: restore full original files from remote to local storage
                // (path 1:1 inside PhotoZ's private storage)
                val originalsRestored = syncRestorer.restoreAllOriginals()

                eventsChannel.trySend(
                    GalleryNavigationEvent.ShowToast(
                        resources.getString(
                            R.string.menu_restore_from_backup_toast_success,
                            thumbsRestored + originalsRestored,
                        ),
                    ),
                )
            } catch (e: Exception) {
                eventsChannel.trySend(
                    GalleryNavigationEvent.ShowToast(
                        resources.getString(
                            R.string.menu_restore_from_backup_toast_failed,
                            e.message ?: e.javaClass.simpleName,
                        ),
                    ),
                )
            }
        }
    }

    private fun onImportChoice(choice: ImportChoice) {
        val navEvent = when (choice) {
            is ImportChoice.AddNewFiles -> GalleryNavigationEvent.StartImport(
                fileUris = choice.fileUris,
                importSource = ImportSource.InApp,
            )
            is ImportChoice.RestoreBackup -> GalleryNavigationEvent.StartRestoreBackup(choice.backupUri)
        }

        eventsChannel.trySend(navEvent)
    }

    private fun onExportSelectedItems(selectedItems: List<String>, target: Uri?) {
        target ?: return
        photoActionsChannel.trySend(
            PhotoAction.ExportPhotos(
                photosFlow.value.filter { selectedItems.contains(it.uuid) },
                target,
            )
        )
    }

    private fun onDeleteSelectedItems(selectedItems: List<String>) {
        photoActionsChannel.trySend(
            PhotoAction.DeletePhotos(
                photosFlow.value.filter { selectedItems.contains(it.uuid) }
            )
        )
    }

    private fun navigateToPhoto(item: PhotoTile) {
        photoActionsChannel.trySend(PhotoAction.OpenPhoto(item.uuid))
    }
}

