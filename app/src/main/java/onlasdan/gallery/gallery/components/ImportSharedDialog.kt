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

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.fragment.app.FragmentManager
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import onlasdan.gallery.R
import onlasdan.gallery.gallery.ui.importing.ImportBottomSheetDialogFragment
import onlasdan.gallery.gallery.ui.importing.SharedUrisStore
import onlasdan.gallery.model.repositories.ImportSource
import onlasdan.gallery.other.extensions.show
import onlasdan.gallery.ui.LocalFragment
import onlasdan.gallery.ui.components.ConfirmationDialog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class ImportSharedUiState(
    val sharedUris: List<Uri> = emptyList(),
)

sealed interface ImportSharedUiEvent {
    data object ClearSharedUris : ImportSharedUiEvent
    data class StartImportShared(val fragmentManager: FragmentManager) : ImportSharedUiEvent
}

@HiltViewModel
class ImportSharedViewModel @Inject constructor(
    private val sharedUrisStore: SharedUrisStore
) : ViewModel() {

    val uiState = sharedUrisStore.observeSharedUris().map { uris ->
        ImportSharedUiState(
            sharedUris = uris,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), ImportSharedUiState())

    fun handleUiEvent(event: ImportSharedUiEvent) {
        when (event) {
            is ImportSharedUiEvent.ClearSharedUris -> sharedUrisStore.reset()
            is ImportSharedUiEvent.StartImportShared -> {
                ImportBottomSheetDialogFragment(
                    uris = uiState.value.sharedUris.toList(),
                    albumUUID = null,
                    importSource = ImportSource.Share,
                ).show(event.fragmentManager)

                sharedUrisStore.reset()
            }
        }
    }
}

@Composable
fun ImportSharedDialog() {
    val viewModel = hiltViewModel<ImportSharedViewModel>()

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val handleUiEvent = viewModel::handleUiEvent

    val fragment = LocalFragment.current

    ConfirmationDialog(
        show = uiState.sharedUris.isNotEmpty(),
        text = stringResource(R.string.import_sharted_question, uiState.sharedUris.size),
        onDismissRequest = { handleUiEvent(ImportSharedUiEvent.ClearSharedUris) },
        onConfirm = {
            fragment ?: return@ConfirmationDialog
            handleUiEvent(ImportSharedUiEvent.StartImportShared(fragment.childFragmentManager))
        }
    )
}