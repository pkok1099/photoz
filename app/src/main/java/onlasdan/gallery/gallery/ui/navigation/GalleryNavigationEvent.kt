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

package onlasdan.gallery.gallery.ui.navigation

import android.net.Uri
import onlasdan.gallery.model.repositories.ImportSource

sealed interface GalleryNavigationEvent {
    data class ShowToast(val text: String) : GalleryNavigationEvent
    /**
     * Sprint 3 / M10 — [targetAlbumName] is non-null when the import came
     * from the Photo Picker (which doesn't expose RELATIVE_PATH). The
     * consumer (MainViewModel.handleImport) sets this as the photo's
     * `albumPath` before calling PhotoRepository.safeImportPhoto, bypassing
     * the auto-album-from-folder logic.
     */
    data class StartImport(
        val fileUris: List<Uri>,
        val importSource: ImportSource,
        val targetAlbumName: String? = null,
    ) : GalleryNavigationEvent
    data class StartRestoreBackup(val backupUri: Uri) : GalleryNavigationEvent
}