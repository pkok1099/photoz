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

package onlasdan.gallery.gallery.ui.menu

import androidx.fragment.app.viewModels
import dagger.hilt.android.AndroidEntryPoint
import onlasdan.gallery.R
import onlasdan.gallery.model.database.entity.Photo
import onlasdan.gallery.uicomponnets.base.processdialogs.BaseProcessBottomSheetDialogFragment

/**
 * Process fragment to delete photos.
 * Uses [DeleteViewModel] for the process.
 *
 * @since 1.0.0
 * @author PhotoZ
 */
@AndroidEntryPoint
class DeleteBottomSheetDialogFragment(
    photos: List<Photo>
) : BaseProcessBottomSheetDialogFragment<Photo>(
    photos,
    R.string.delete_deleting,
    true
) {

    override val viewModel: DeleteViewModel by viewModels()
}