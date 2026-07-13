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
import kotlinx.coroutines.Dispatchers
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
import onlasdan.gallery.R
import onlasdan.gallery.gallery.components.ImportChoice
import onlasdan.gallery.gallery.components.PhotoTile
import onlasdan.gallery.gallery.ui.navigation.GalleryNavigationEvent
import onlasdan.gallery.gallery.ui.navigation.PhotoAction
import onlasdan.gallery.model.repositories.ImportSource
import onlasdan.gallery.model.repositories.PhotoRepository
import onlasdan.gallery.sort.domain.SortConfig
import onlasdan.gallery.sort.domain.SortRepository
import onlasdan.gallery.sync.rclone.RepoManager
import onlasdan.gallery.sync.work.SyncRestorer
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class GalleryViewModel
	@Inject
	constructor(
		// @since file-upload feature — promoted to private val so navigateToPhoto
		// can look up the photo and route file-type taps to openFileExternally
		// instead of the in-app image viewer (which can't render PDF/ZIP/audio).
		private val photoRepository: PhotoRepository,
		private val galleryUiStateFactory: GalleryUiStateFactory,
		private val sortRepository: SortRepository,
		// @since v9 followup (Bug 2) — needed by OnRestoreFromBackup to re-download
		// thumbnails from the cloud backup. The gallery's overflow menu has a new
		// "Restore from backup" option that calls [RepoManager.restoreThumbnailsFromPacks]
		// to re-sync thumbnails and create Photo DB rows for any registry entries
		// that don't have one yet.
		//
		// @since Item 1 fix — [SyncRestorer] is no longer called from this VM:
		//   restore-from-backup is now thumbnails-only. Originals are fetched
		//   on-demand by the image viewer / video player via
		//   [SyncRestorer.ensureLocalOriginal*]. The field is retained because
		//   [SyncRestorer] is still wired up by Hilt and the function
		//   [SyncRestorer.restoreAllOriginals] is preserved as a utility for
		//   future use. Suppressed unused warning accordingly.
		@Suppress("unused")
		private val syncRestorer: onlasdan.gallery.sync.work.SyncRestorer,
		private val repoManager: RepoManager,
		private val resources: Resources,
	) : ViewModel() {
		private val sortFlow = sortRepository.observeSortFor(albumUuid = null, default = SortConfig.Gallery.default)

		@OptIn(ExperimentalCoroutinesApi::class)
		private val photosFlow =
			sortFlow
				.flatMapLatest { sort ->
					photoRepository.observeAll(sort)
				}.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), listOf())

		private val showAlbumSelectionDialog = MutableStateFlow(false)

		// @since file-upload feature — current All/Photos/Videos/Files filter
		//  selection. Drives the client-side type filter in
		//  [galleryUiStateFactory.create] and the FilterChip selection in the
		//  gallery header. Default is ALL so the gallery shows everything on
		//  first open (matches user expectation: "show me my library").
		//
		// @since search-filter feature — default changed from PHOTOS → ALL.
		private val filterFlow = MutableStateFlow(GalleryFilter.ALL)

		// @since search-filter feature — current search query entered in the
		//  gallery search bar. Empty string means "no text filter". Drives the
		//  client-side filename-contains filter (case-insensitive) in
		//  [galleryUiStateFactory.create].
		private val searchQueryFlow = MutableStateFlow("")

		val uiState: StateFlow<GalleryUiState> =
			combine(
				photosFlow,
				showAlbumSelectionDialog,
				sortFlow,
				filterFlow,
				searchQueryFlow,
			) { photos, showAlbumSelection, sort, filter, searchQuery ->
				galleryUiStateFactory.create(photos, showAlbumSelection, sort, filter, searchQuery)
			}.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), GalleryUiState.Empty())

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
				is GalleryUiEvent.SortChanged ->
					viewModelScope.launch {
						sortRepository.updateSortFor(albumUuid = null, sort = event.sort)
					}
				// @since file-upload feature — Photos/Files filter chip toggle.
				// @since search-filter feature — extended to ALL/PHOTOS/VIDEOS/FILES.
				is GalleryUiEvent.FilterChanged -> filterFlow.value = event.filter
				// @since search-filter feature — typing in the gallery search bar
				//   updates the filename-contains filter (case-insensitive).
				is GalleryUiEvent.SearchQueryChanged -> searchQueryFlow.value = event.query
				// @since v9 followup (Bug 2) — Restore from cloud backup. Re-runs
				// the pack-based thumbnail restore (downloads packs, extracts
				// thumbnails by offset+length, creates Photo rows for any registry
				// entries that don't have one yet). Non-fatal: failures are surfaced
				// as a toast; the user can retry.
				GalleryUiEvent.OnRestoreFromBackup -> onRestoreFromBackup()
				// @since Bug 4 — granular per-photo restore. The user picked
				//   "Restore original" from the multi-selection dropdown. Downloads
				//   the encrypted original for each selected UUID via
				//   [SyncRestorer.ensureLocalOriginal]. Failures are surfaced as a
				//   toast; the user can retry. Idempotent: photos whose local
				//   original already exists are skipped.
				is GalleryUiEvent.OnRestoreOriginals -> onRestoreOriginals(event.uuids)
				// Sprint 4 / M2 — toggle favorite flag on selected photos.
				is GalleryUiEvent.OnToggleFavorite -> onToggleFavorite(event.items, event.isFavorite)
			}
		}

		/**
		 * Sprint 4 / M2 — Toggle the `is_favorite` flag on a batch of photos.
		 *
		 * Called from the multi-selection action bar's "Mark as favorite" /
		 * "Remove from favorites" button. Iterates the selected UUIDs and calls
		 * [PhotoRepository.toggleFavorite] for each. The gallery's Flow observer
		 * picks up the DB change and re-emits with updated heart badges — no
		 * manual UI refresh needed.
		 *
		 * Non-fatal: individual failures are logged but don't abort the batch.
		 *
		 * @since v12 — Sprint 4 / M2 favorites
		 */
		private fun onToggleFavorite(
			items: List<String>,
			isFavorite: Boolean,
		) {
			viewModelScope.launch(Dispatchers.IO) {
				for (uuid in items) {
					try {
						photoRepository.toggleFavorite(uuid, isFavorite)
					} catch (e: Exception) {
						Timber.w(e, "onToggleFavorite: failed for %s (non-fatal)", uuid)
					}
				}
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
					// Step 1: restore thumbnails + create DB rows from registry.
					//
					// ─── Item 1 fix: thumbnails-only restore ──────────────────────
					// Restore-from-backup now downloads ONLY the thumbnail packs
					// and creates Photo DB rows for any registry entries that
					// don't have one yet. Original encrypted files are NOT
					// downloaded eagerly — they're fetched on-demand when the
					// user opens a photo, via [SyncRestorer.ensureLocalOriginal]
					// (or [SyncRestorer.ensureLocalOriginalWithProgress] for
					// videos). This avoids pulling potentially gigabytes of
					// originals during a restore, which was slow and burned
					// mobile data.
					//
					// The previous call to `syncRestorer.restoreAllOriginals()`
					// has been removed. The function itself is kept in
					// [SyncRestorer] as a utility for future use, but the
					// restore button no longer triggers it.
					val thumbsRestored = repoManager.restoreThumbnailsFromPacks()

					eventsChannel.trySend(
						GalleryNavigationEvent.ShowToast(
							resources.getString(
								R.string.menu_restore_from_backup_toast_success,
								thumbsRestored,
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

		/**
		 * Triggered by the multi-selection "Restore original" dropdown item.
		 *
		 * Per-photo granular restore (Bug 4): for each selected UUID, calls
		 * [onlasdan.gallery.sync.work.SyncRestorer.ensureLocalOriginal] to
		 * download the encrypted original from the cloud backup. This is the
		 * complement to the bulk [onRestoreFromBackup] action — the user can
		 * restore just the photos they care about instead of pulling every
		 * original (which could be gigabytes).
		 *
		 * Idempotent: photos whose local original already exists are skipped
		 * (the underlying SyncRestorer short-circuits on a non-empty local file).
		 *
		 * Sends a [GalleryNavigationEvent.ShowToast] with the result so the user
		 * gets feedback (started / success / nothing-to-do / failed).
		 *
		 * @since Bug 4 — granular per-photo restore
		 */
		private fun onRestoreOriginals(uuids: List<String>) {
			if (uuids.isEmpty()) return

			eventsChannel.trySend(
				GalleryNavigationEvent.ShowToast(
					resources.getString(
						R.string.menu_restore_original_toast_started,
						uuids.size,
					),
				),
			)
			viewModelScope.launch {
				try {
					var restored = 0
					for (uuid in uuids) {
						val result = syncRestorer.ensureLocalOriginal(uuid)
						if (result.isSuccess) restored++
					}
					val toastRes =
						if (restored == 0) {
							R.string.menu_restore_original_toast_nothing
						} else {
							R.string.menu_restore_original_toast_success
						}
					eventsChannel.trySend(
						GalleryNavigationEvent.ShowToast(
							resources.getString(toastRes, restored),
						),
					)
				} catch (e: Exception) {
					eventsChannel.trySend(
						GalleryNavigationEvent.ShowToast(
							resources.getString(
								R.string.menu_restore_original_toast_failed,
								e.message ?: e.javaClass.simpleName,
							),
						),
					)
				}
			}
		}

		private fun onImportChoice(choice: ImportChoice) {
			val navEvent =
				when (choice) {
					is ImportChoice.AddNewFiles ->
						GalleryNavigationEvent.StartImport(
							fileUris = choice.fileUris,
							importSource = ImportSource.InApp,
						)
					is ImportChoice.RestoreBackup -> GalleryNavigationEvent.StartRestoreBackup(choice.backupUri)
					// Sprint 3 / M10 — Photo Picker import. Same StartImport event as
					// AddNewFiles, but the URIs come from PickVisualMedia (no
					// RELATIVE_PATH). The PhotoRepository will use the filename as
					// albumPath fallback; the user has already picked a target album
					// via PathMakerDialog, and that album name is stashed as a
					// side-channel via the ImportSource's pending target album field.
					//
					// For v1, we route through StartImport with a special
					// ImportSource.PhotoPicker that carries the target album. The
					// import flow in MainViewModel picks it up and sets albumPath
					// before calling PhotoRepository.safeImportPhoto.
					is ImportChoice.AddFromPhotoPicker ->
						GalleryNavigationEvent.StartImport(
							fileUris = choice.fileUris,
							importSource = ImportSource.InApp,
							// Sprint 3 / M10 — stash the Path Maker's chosen album so the
							// import flow sets albumPath explicitly, bypassing the
							// auto-album-from-folder logic (which would skip because the
							// picker URI has no RELATIVE_PATH → albumPath falls back to
							// filename → ensureAlbumForPhoto skips).
							targetAlbumName = choice.targetAlbum,
						)
				}

			eventsChannel.trySend(navEvent)
		}

		private fun onExportSelectedItems(
			selectedItems: List<String>,
			target: Uri?,
		) {
			target ?: return
			photoActionsChannel.trySend(
				PhotoAction.ExportPhotos(
					photosFlow.value.filter { selectedItems.contains(it.uuid) },
					target,
				),
			)
		}

		private fun onDeleteSelectedItems(selectedItems: List<String>) {
			photoActionsChannel.trySend(
				PhotoAction.DeletePhotos(
					photosFlow.value.filter { selectedItems.contains(it.uuid) },
				),
			)
		}

		private fun navigateToPhoto(item: PhotoTile) {
			// @since file-upload feature — route file-type taps to the external
			//  viewer (PDF / ZIP / audio can't be rendered by the in-app image
			//  viewer). The in-app image viewer is unchanged for photo/video
			//  types — they keep going through PhotoAction.OpenPhoto and the
			//  navigation graph.
			if (item.type.isFile) {
				viewModelScope.launch {
					// F-WARN-008: PhotoRepository.get() returns non-null Photo per the type
					// system, but Room may return null at runtime if the row was deleted
					// between list-load and tap. Wrap in runCatching to handle this safely
					// without triggering "elvis always returns left operand" warning.
					val photo = runCatching { photoRepository.get(item.uuid) }.getOrNull()
					if (photo == null) {
						eventsChannel.trySend(
							GalleryNavigationEvent.ShowToast(
								resources.getString(R.string.gallery_open_file_not_found),
							),
						)
						return@launch
					}
					val launched = photoRepository.openFileExternally(photo)
					if (!launched) {
						eventsChannel.trySend(
							GalleryNavigationEvent.ShowToast(
								resources.getString(R.string.gallery_open_file_failed),
							),
						)
					}
				}
				return
			}
			photoActionsChannel.trySend(PhotoAction.OpenPhoto(item.uuid))
		}
	}
