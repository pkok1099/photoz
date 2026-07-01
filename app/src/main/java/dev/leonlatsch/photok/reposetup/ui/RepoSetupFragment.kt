/*
 *   Copyright 2020–2026 Leon Latsch
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

package dev.leonlatsch.photok.reposetup.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.fragment.findNavController
import dev.leonlatsch.photok.R
import dev.leonlatsch.photok.gallery.ui.navigation.NavigateToGallery
import dev.leonlatsch.photok.sync.rclone.RcloneConfigManager
import dev.leonlatsch.photok.ui.theme.AppTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Mandatory repo setup screen. Blocks gallery access until a remote is picked and a repo
 * is confirmed (register or login). Cannot be dismissed or skipped.
 *
 * @since PR1 sync — mandatory repo setup
 */
@AndroidEntryPoint
class RepoSetupFragment : Fragment() {

    private val viewModel: RepoSetupViewModel by viewModels()

    @Inject
    lateinit var navigateToGallery: NavigateToGallery

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setContent {
                AppTheme {
                    RepoSetupScreen(
                        viewModel = viewModel,
                        onCompleted = {
                            // @since PR3 — repo setup now chains forward to SetupFragment
                            // (vault password) instead of jumping directly to the gallery.
                            // The OpenGalleryUseCase gate inside SetupFragment's
                            // navigateToGallery will pass because repoConfirmed was set
                            // to true during repo setup.
                            findNavController().navigate(
                                R.id.action_repoSetupFragment_to_setupFragment
                            )
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun RepoSetupScreen(
    viewModel: RepoSetupViewModel,
    onCompleted: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state) {
        if (state is RepoSetupState.Completed) {
            onCompleted()
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            when (val s = state) {
                RepoSetupState.NeedsConfig -> NeedsConfigContent(viewModel)
                is RepoSetupState.NeedsRemoteChoice -> NeedsRemoteChoiceContent(s.remotes, viewModel)
                RepoSetupState.Checking -> CheckingContent()
                RepoSetupState.ReadyToRegister -> ReadyToRegisterContent(viewModel)
                RepoSetupState.Connecting -> ConnectingContent()
                RepoSetupState.RestoringBackup -> RestoringBackupContent()
                RepoSetupState.Completed -> {
                    // Will be navigated away by LaunchedEffect
                }
                is RepoSetupState.Error -> ErrorContent(s.message, viewModel)
            }
        }
    }
}

@Composable
private fun NeedsConfigContent(viewModel: RepoSetupViewModel) {
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.importConfig(it) }
    }

    Text(
        text = stringResource(R.string.repo_setup_title),
        style = MaterialTheme.typography.headlineMedium,
        textAlign = TextAlign.Center,
    )
    Text(
        text = stringResource(R.string.repo_setup_needs_config),
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 16.dp),
    )
    Button(
        onClick = { launcher.launch(arrayOf("*/*")) },
        modifier = Modifier.padding(top = 24.dp),
    ) {
        Text(stringResource(R.string.repo_setup_pick_config))
    }
}

@Composable
private fun NeedsRemoteChoiceContent(
    remotes: List<RcloneConfigManager.RemoteInfo>,
    viewModel: RepoSetupViewModel,
) {
    var selected by remember { mutableStateOf<String?>(null) }

    Text(
        text = stringResource(R.string.repo_setup_pick_remote),
        style = MaterialTheme.typography.titleLarge,
    )
    LazyColumn(
        modifier = Modifier.padding(top = 16.dp),
    ) {
        items(remotes) { remote ->
            val typeSuffix = remote.type?.let { " ($it)" } ?: ""
            androidx.compose.foundation.layout.Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clickable { selected = remote.name }
                    .padding(vertical = 4.dp)
            ) {
                RadioButton(
                    selected = selected == remote.name,
                    onClick = { selected = remote.name },
                )
                Text(
                    text = remote.name + typeSuffix,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
    Button(
        onClick = { selected?.let { viewModel.chooseRemote(it) } },
        enabled = selected != null,
        modifier = Modifier.padding(top = 24.dp),
    ) {
        Text(stringResource(R.string.common_ok))
    }
}

@Composable
private fun CheckingContent() {
    CircularProgressIndicator()
    Text(
        text = stringResource(R.string.repo_setup_checking),
        modifier = Modifier.padding(top = 16.dp),
    )
}

@Composable
private fun ReadyToRegisterContent(viewModel: RepoSetupViewModel) {
    Text(
        text = stringResource(R.string.repo_setup_ready_to_register),
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center,
    )
    Button(
        onClick = { viewModel.confirmRegister() },
        modifier = Modifier.padding(top = 24.dp),
    ) {
        Text(stringResource(R.string.repo_setup_register))
    }
}

@Composable
private fun ConnectingContent() {
    CircularProgressIndicator()
    Text(
        text = stringResource(R.string.repo_setup_connecting),
        modifier = Modifier.padding(top = 16.dp),
    )
}

/**
 * Shown while [RepoSetupViewModel] calls [RepoManager.restoreThumbnailsAfterLogin]
 * after a successful login. Keeps the user informed that the brief delay is
 * expected — without this screen, login would appear to hang.
 *
 * @since PR4 sync — restore thumbnails on login
 */
@Composable
private fun RestoringBackupContent() {
    CircularProgressIndicator()
    Text(
        text = stringResource(R.string.repo_setup_restoring_backup),
        modifier = Modifier.padding(top = 16.dp),
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun ErrorContent(message: String, viewModel: RepoSetupViewModel) {
    Text(
        text = stringResource(R.string.repo_setup_error),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.error,
    )
    Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 8.dp),
    )
    OutlinedButton(
        onClick = { viewModel.dismissError() },
        modifier = Modifier.padding(top = 24.dp),
    ) {
        Text(stringResource(R.string.repo_setup_retry))
    }
}
