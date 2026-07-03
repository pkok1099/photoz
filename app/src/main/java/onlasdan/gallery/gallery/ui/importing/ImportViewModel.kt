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

package onlasdan.gallery.gallery.ui.importing

import android.app.Application
import android.net.Uri
import dagger.hilt.android.lifecycle.HiltViewModel
import onlasdan.gallery.gallery.albums.domain.AlbumRepository
import onlasdan.gallery.model.repositories.ImportSource
import onlasdan.gallery.model.repositories.PhotoRepository
import onlasdan.gallery.uicomponnets.base.processdialogs.BaseProcessViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import javax.inject.Inject

/**
 * View model to handle importing photos.
 *
 * @since 1.0.0
 * @author PhotoZ
 */
@HiltViewModel
class ImportViewModel @Inject constructor(
    app: Application,
    private val photoRepository: PhotoRepository,
    private val albumRepository: AlbumRepository,
    private val sharedUrisStore: SharedUrisStore,
) : BaseProcessViewModel<Uri>(app) {

    var albumUUID: String? = null
    var importSource = ImportSource.InApp

    /**
     * Sprint 3 / M10 — Optional album-name override for the Photo Picker flow.
     *
     * When non-null, [processItem] passes this to
     * [PhotoRepository.safeImportPhoto] as `overrideAlbumPath`. The photo's
     * `albumPath` is set to this value (e.g. "Picker") instead of falling
     * back to the filename, and `ensureAlbumForPhoto` creates/links the
     * named album.
     *
     * Null for the regular MediaStore import (auto-album-from-folder path).
     */
    var targetAlbumName: String? = null

    private val _reviewTrigger = Channel<Unit>(Channel.CONFLATED)
    val reviewTrigger = _reviewTrigger.receiveAsFlow()

    override suspend fun processItem(item: Uri) {
        val photoUUID = photoRepository.safeImportPhoto(
            sourceUri = item,
            importSource = importSource,
            overrideAlbumPath = targetAlbumName,
        )
        if (photoUUID.isEmpty()) {
            failuresOccurred = true
            return
        }

        albumUUID?.let {
            albumRepository.link(listOf(photoUUID), it)
        }
    }

    override suspend fun postProcess() {
        super.postProcess()
        sharedUrisStore.reset()
        if (!failuresOccurred && photoRepository.countAll() >= REVIEW_PHOTO_THRESHOLD) {
            _reviewTrigger.trySend(Unit)
        }
    }

    companion object {
        private const val REVIEW_PHOTO_THRESHOLD = 100
    }
}