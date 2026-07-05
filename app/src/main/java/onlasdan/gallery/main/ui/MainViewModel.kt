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

package onlasdan.gallery.main.ui

import android.app.Application
import android.net.Uri
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import onlasdan.gallery.R
import onlasdan.gallery.gallery.ui.importing.SharedUrisStore
import onlasdan.gallery.main.ui.navigation.MainMenuUiState
import onlasdan.gallery.uicomponnets.bindings.ObservableViewModel
import javax.inject.Inject

/**
 * ViewModel for the main activity.
 *
 * @since 1.2.4
 * @author PhotoZ
 */
@HiltViewModel
class MainViewModel
	@Inject
	constructor(
		app: Application,
		private val sharedUrisStore: SharedUrisStore,
	) : ObservableViewModel(app) {
		private val _mainMenuUiState = MutableStateFlow(MainMenuUiState(R.id.galleryFragment))
		val mainMenuUiState = _mainMenuUiState.asStateFlow()

		fun addUriToSharedUriStore(uri: Uri) = sharedUrisStore.safeAddUri(uri)

		fun onDestinationChanged(id: Int) {
			_mainMenuUiState.update { it.copy(currentFragmentId = id) }
		}
	}
