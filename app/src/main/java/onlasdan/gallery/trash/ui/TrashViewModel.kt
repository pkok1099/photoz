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

package onlasdan.gallery.trash.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import onlasdan.gallery.model.database.entity.Photo
import onlasdan.gallery.model.repositories.PhotoRepository
import onlasdan.gallery.uicomponnets.Dialogs
import android.app.Application
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * ViewModel for the Trash screen. Exposes the live list of trashed photos
 * and handles restore / permanently-delete / empty-trash actions.
 *
 * @since v10 recycle bin
 */
@HiltViewModel
class TrashViewModel @Inject constructor(
    private val photoRepository: PhotoRepository,
    private val app: Application,
) : ViewModel() {

    /**
     * Live list of photos currently in the trash (deleted_at > 0), ordered
     * by most-recently-deleted first. Updates automatically as the user
     * restores or permanently deletes items.
     */
    val trash: StateFlow<List<Photo>> = photoRepository.observeTrash()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), emptyList())

    /**
     * Restore a single photo from the trash (set deleted_at = 0). The photo
     * reappears in the gallery and any albums it was linked to before the
     * soft-delete.
     *
     * @since v10 recycle bin
     */
    fun restore(uuid: String) {
        viewModelScope.launch {
            try {
                photoRepository.restorePhotoFromTrash(uuid)
                Dialogs.showShortToast(app, app.getString(onlasdan.gallery.R.string.trash_restore_success))
            } catch (e: Exception) {
                Timber.w(e, "restore: FAILED for %s", uuid)
                Dialogs.showShortToast(app, app.getString(onlasdan.gallery.R.string.trash_restore_failed))
            }
        }
    }

    /**
     * Permanently delete a single photo from the trash: remove its DB row +
     * on-disk encrypted files. NOT undoable.
     *
     * @since v10 recycle bin
     */
    fun permanentlyDelete(photo: Photo) {
        viewModelScope.launch {
            try {
                photoRepository.permanentlyDeletePhoto(photo)
                Dialogs.showShortToast(app, app.getString(onlasdan.gallery.R.string.trash_delete_permanent_success))
            } catch (e: Exception) {
                Timber.w(e, "permanentlyDelete: FAILED for %s", photo.uuid)
                Dialogs.showShortToast(app, app.getString(onlasdan.gallery.R.string.trash_delete_permanent_failed))
            }
        }
    }

    /**
     * Empty the entire trash: permanently delete every trashed photo.
     * NOT undoable. Surfaces a toast with the count.
     *
     * @since v10 recycle bin
     */
    fun emptyTrash() {
        viewModelScope.launch {
            val count = try {
                photoRepository.emptyTrash()
            } catch (e: Exception) {
                Timber.w(e, "emptyTrash: FAILED")
                Dialogs.showShortToast(app, app.getString(onlasdan.gallery.R.string.trash_empty_failed))
                return@launch
            }
            val msgRes = if (count == 0) {
                onlasdan.gallery.R.string.trash_empty_nothing
            } else {
                onlasdan.gallery.R.string.trash_empty_success
            }
            Dialogs.showShortToast(
                app,
                if (count == 0) app.getString(msgRes) else app.getString(msgRes, count),
            )
        }
    }
}
