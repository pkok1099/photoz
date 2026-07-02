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

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import dev.leonlatsch.photok.R
import dev.leonlatsch.photok.encryption.ui.RecoveryPhraseRestoreScreen
import dev.leonlatsch.photok.gallery.ui.navigation.NavigateToGallery
import dev.leonlatsch.photok.sync.rclone.RcloneConfigManager
import dev.leonlatsch.photok.ui.theme.AppTheme
import timber.log.Timber
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
                    // ─── Item 2 fix: POST_NOTIFICATIONS permission launcher (login branch) ──
                    // The login branch (onUnlocked) goes directly to the gallery WITHOUT
                    // passing through RecoveryPhraseSetupFragment, so the permission request
                    // that lives in RecoveryPhraseSetupFragment never fires for login users.
                    // This was the root cause of "notifications never appear for users who
                    // log in with their recovery phrase".
                    //
                    // Fix: register the same kind of launcher here at the RepoSetupScreen
                    // composable level (rememberLauncherForActivityResult must be called
                    // from a @Composable context). The onUnlocked lambda below checks the
                    // permission; if not granted (and SDK >= 33), it launches the system
                    // dialog. The result callback navigates to the gallery regardless of
                    // granted/denied — uploads work either way (notification is UX-only).
                    val notificationPermissionLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestPermission()
                    ) { granted ->
                        android.util.Log.e("RcloneDiag",
                            "POST_NOTIFICATIONS: result callback fired — granted=$granted (login branch, RepoSetupFragment)")
                        Timber.i("POST_NOTIFICATIONS permission granted=$granted (login branch)")
                        // Navigate to gallery regardless of granted/denied — uploads work
                        // either way (notification is UX-only, not a functional dependency).
                        navigateToGallery(findNavController())
                    }

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
                        },
                        // @since key-escrow — login-branch phrase entry.
                        // When the user successfully unlocks via the embedded
                        // RecoveryPhraseRestoreScreen, the VMK is in memory via
                        // SessionRepository — skip SetupFragment (no PIN/password
                        // needed) and go straight to the gallery.
                        //
                        // @since Item 2 fix — request POST_NOTIFICATIONS before
                        // navigating to gallery. The login branch never reaches
                        // RecoveryPhraseSetupFragment (where the permission request
                        // lives for the register branch), so without this the
                        // notification permission would never be requested for
                        // login users.
                        onUnlocked = {
                            // Check + request POST_NOTIFICATIONS before navigating to gallery.
                            // Same pattern as RecoveryPhraseSetupFragment's onContinue —
                            // see that file for the full rationale.
                            val needsRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                ContextCompat.checkSelfPermission(
                                    requireContext(),
                                    Manifest.permission.POST_NOTIFICATIONS
                                ) != PackageManager.PERMISSION_GRANTED
                            } else {
                                false
                            }
                            if (needsRequest) {
                                android.util.Log.e("RcloneDiag",
                                    "POST_NOTIFICATIONS: launching permission request (login branch, onUnlocked)")
                                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                android.util.Log.e("RcloneDiag",
                                    "POST_NOTIFICATIONS: launch() returned (login branch, onUnlocked) — awaiting result callback")
                            } else {
                                android.util.Log.e("RcloneDiag",
                                    "POST_NOTIFICATIONS: already granted or not needed (SDK < 33) — navigating to gallery (login branch, onUnlocked)")
                                navigateToGallery(findNavController())
                            }
                        },
                        onBack = {
                            findNavController().navigateUp()
                        },
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
    onUnlocked: () -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state) {
        if (state is RepoSetupState.Completed) {
            onCompleted()
        }
        // @since Part B two-layer escrow — when the user successfully unlocks via
        // the password-entry (or phrase-entry) login-branch path, the VMK is in
        // memory via SessionRepository. Skip SetupFragment (no PIN/password
        // needed) and go straight to the gallery.
        if (state is RepoSetupState.Unlocked) {
            onUnlocked()
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        // @since key-escrow — login-branch phrase entry.
        // NeedsPhraseEntry embeds RecoveryPhraseRestoreScreen, which has its own
        // Scaffold (topBar + bottomBar). It needs the full screen — NOT wrapped
        // in the padded/centered Column used by the other states.
        if (state is RepoSetupState.NeedsPhraseEntry) {
            NeedsPhraseEntryContent(
                onUnlocked = onUnlocked,
                onBack = onBack,
            )
        } else {
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
                    RepoSetupState.NoEscrowAvailable -> NoEscrowAvailableContent(viewModel)
                    is RepoSetupState.NeedsPasswordEntry -> NeedsPasswordEntryContent(s, viewModel)
                    RepoSetupState.Completed -> {
                        // Will be navigated away by LaunchedEffect
                    }
                    RepoSetupState.Unlocked -> {
                        // Will be navigated away by LaunchedEffect (onUnlocked)
                    }
                    is RepoSetupState.Error -> ErrorContent(s.message, viewModel)
                    RepoSetupState.NeedsPhraseEntry -> {
                        // Handled by the outer if — unreachable here, but the
                        // when must remain exhaustive over the sealed hierarchy.
                    }
                }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NeedsRemoteChoiceContent(
    remotes: List<RcloneConfigManager.RemoteInfo>,
    viewModel: RepoSetupViewModel,
) {
    var selected by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf(false) }

    val selectedRemote = remotes.find { it.name == selected }
    val displayText = selectedRemote?.let {
        it.name + (it.type?.let { " ($it)" } ?: "")
    } ?: ""

    Text(
        text = stringResource(R.string.repo_setup_pick_remote),
        style = MaterialTheme.typography.titleLarge,
    )

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.padding(top = 16.dp),
    ) {
        OutlinedTextField(
            value = displayText,
            onValueChange = {},
            readOnly = true,
            label = { Text("Remote") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth(),
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            remotes.forEach { remote ->
                val typeSuffix = remote.type?.let { " ($it)" } ?: ""
                DropdownMenuItem(
                    text = { Text(remote.name + typeSuffix) },
                    onClick = {
                        selected = remote.name
                        expanded = false
                    },
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

/**
 * Embeds the existing [RecoveryPhraseRestoreScreen] for the login-branch phrase
 * entry flow. The `RecoveryPhraseRestoreViewModel` (Hilt-injected internally)
 * calls `vaultService.unlock(UnlockRequest.RecoveryPhrase(phrase))`, which will
 * succeed because [RepoManager.downloadVaultProtectionEscrow] persisted the
 * recovery-phrase [VaultProtection] to the local DB during `loginRepo()`.
 *
 * After successful unlock:
 * - The VMK is in memory via `SessionRepository`.
 * - The thumbnails downloaded by [RepoManager.restoreThumbnailsAfterLogin] are
 *   already on disk (encrypted with that VMK) — when the gallery renders them,
 *   the existing decryption path will use the now-unlocked VMK. No additional
 *   decrypt-verify step is needed: if the phrase was wrong,
 *   `vaultService.unlock()` would have thrown (the wrapped VMK decryption would
 *   fail with a padding error), so reaching `onUnlocked` is sufficient proof
 *   the phrase was correct.
 *
 * @since key-escrow — login-branch phrase entry
 */
@Composable
private fun NeedsPhraseEntryContent(
    onUnlocked: () -> Unit,
    onBack: () -> Unit,
) {
    // Reused AS-IS — do NOT rebuild the phrase input UI. The screen has its
    // own Scaffold (TopAppBar + bottom unlock button) and handles all input
    // methods (type-by-hand, file, QR, clipboard).
    RecoveryPhraseRestoreScreen(
        onUnlocked = onUnlocked,
        onBack = onBack,
    )
}

/**
 * Degraded-mode screen shown when no escrow artifact is available on the remote
 * (old repo created before this feature, OR escrow download failed non-fatally).
 *
 * The user can still reach the gallery via their normal vault PIN/password
 * (SetupFragment), but recovery-phrase restore is not available on this fresh
 * install. Tapping "Continue to gallery" transitions to [RepoSetupState.Completed]
 * which chains forward to SetupFragment.
 *
 * @since key-escrow — login-branch phrase entry
 */
@Composable
private fun NoEscrowAvailableContent(viewModel: RepoSetupViewModel) {
    Text(
        text = stringResource(R.string.repo_setup_no_escrow_title),
        style = MaterialTheme.typography.titleMedium,
        textAlign = TextAlign.Center,
    )
    Text(
        text = stringResource(R.string.repo_setup_no_escrow_body),
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 16.dp),
    )
    Button(
        onClick = { viewModel.continueWithoutEscrow() },
        modifier = Modifier.padding(top = 24.dp),
    ) {
        Text(stringResource(R.string.repo_setup_no_escrow_continue))
    }
}

/**
 * Password-entry screen for the login branch when both escrow layers are
 * available on the remote ([RepoManager.EscrowType.PASSWORD_PLUS_PHRASE]).
 *
 * The user enters their vault password; [RepoSetupViewModel.submitPassword]
 * unwraps the recovery phrase (Layer 2: `wrapped-phrase.json`) using
 * [PhraseEscrowWrapper.unwrapPhrase], then feeds the recovered phrase into
 * the existing `vaultService.unlock(UnlockRequest.RecoveryPhrase(phrase))`
 * path to unlock the VMK (Layer 1: `recovery-phrase.json`).
 *
 * Wrong password → `unwrapPhrase` returns null (caught padding error) →
 * [RepoSetupState.NeedsPasswordEntry.error] is set to
 * `repo_setup_password_entry_error` ("Incorrect password"). The user can retry
 * — the password field is preserved across attempts because the composable
 * owns its own `remember` state.
 *
 * @since Part B two-layer escrow — password-entry UI for the login branch
 */
@Composable
private fun NeedsPasswordEntryContent(
    state: RepoSetupState.NeedsPasswordEntry,
    viewModel: RepoSetupViewModel,
) {
    var password by remember { mutableStateOf("") }

    Text(
        text = stringResource(R.string.repo_setup_password_entry_title),
        style = MaterialTheme.typography.titleMedium,
        textAlign = TextAlign.Center,
    )
    Text(
        text = stringResource(R.string.repo_setup_password_entry_subtitle),
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 16.dp),
    )

    OutlinedTextField(
        value = password,
        onValueChange = { password = it },
        label = { Text(stringResource(R.string.repo_setup_password_entry_title)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(
            onDone = {
                if (password.isNotEmpty() && !state.loading) {
                    viewModel.submitPassword(password)
                }
            },
        ),
        isError = state.error != null,
        supportingText = {
            if (state.error != null) {
                Text(
                    text = state.error,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        enabled = !state.loading,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 24.dp),
    )

    Spacer(Modifier.height(16.dp))

    if (state.loading) {
        CircularProgressIndicator()
    } else {
        Button(
            onClick = { viewModel.submitPassword(password) },
            enabled = password.isNotEmpty(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        ) {
            Text(stringResource(R.string.repo_setup_password_entry_submit))
        }
    }
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
