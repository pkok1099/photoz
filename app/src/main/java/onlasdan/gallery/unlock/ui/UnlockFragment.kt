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

package onlasdan.gallery.unlock.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import onlasdan.gallery.BuildConfig
import onlasdan.gallery.R
import onlasdan.gallery.encryption.domain.VaultService
import onlasdan.gallery.encryption.domain.models.VaultProtectionType
import onlasdan.gallery.gallery.ui.navigation.NavigateToGallery
import onlasdan.gallery.other.extensions.finishOnBackWhileStarted
import onlasdan.gallery.settings.data.Config
import onlasdan.gallery.ui.theme.AppTheme
import onlasdan.gallery.uicomponnets.Dialogs
import timber.log.Timber
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Sprint 10+ / M1 — UnlockFragment migrated to Compose.
 *
 * Previously extended [BindableFragment] with XML layout (fragment_unlock.xml)
 * + ViewBinding. Now extends plain [Fragment] and hosts a [ComposeView] that
 * renders [UnlockScreen].
 *
 * The ViewModel (UnlockViewModel) is unchanged — it still uses ObservableViewModel
 * for backwards compat with the legacy migration path. The Compose screen
 * manages password as local state and passes it to the ViewModel via
 * `viewModel.password = ...` before calling `unlockWithPassword()`.
 *
 * @since v14 — Sprint 10+ / M1 Compose migration
 */
@AndroidEntryPoint
class UnlockFragment : Fragment() {

    private val viewModel: UnlockViewModel by viewModels()

    @Inject
    lateinit var config: Config

    @Inject
    lateinit var vaultService: VaultService

    @Inject
    lateinit var navigateToGallery: NavigateToGallery

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        return ComposeView(requireContext()).apply {
            setContent {
                AppTheme {
                    val unlockState by viewModel.unlockState.collectAsStateWithLifecycle()
                    val breakInWarning by viewModel.breakInWarning.collectAsStateWithLifecycle()
                    var showBiometric by remember { mutableStateOf(false) }
                    var showForgotPassword by remember { mutableStateOf(false) }

                    // Sprint 7+ / P2 — Break-in warning dialog.
                    // Shown when there were failed unlock attempts since last login.
                    if (breakInWarning != null) {
                        androidx.compose.material3.AlertDialog(
                            onDismissRequest = { viewModel.breakInWarning.value = null },
                            title = { androidx.compose.material3.Text("⚠ Break-in attempt detected") },
                            text = { androidx.compose.material3.Text(breakInWarning!!) },
                            confirmButton = {
                                androidx.compose.material3.TextButton(
                                    onClick = { viewModel.breakInWarning.value = null }
                                ) {
                                    androidx.compose.material3.Text("Dismiss")
                                }
                            },
                        )
                    }

                    // Check biometric + recovery phrase availability on first composition.
                    LaunchedEffect(Unit) {
                        // Biometric check
                        val hasBiometric = vaultService.isSetup(VaultProtectionType.Biometric) ||
                            vaultService.canMigrate(VaultProtectionType.Biometric)
                        showBiometric = hasBiometric

                        // Auto-trigger biometric if available (same as old XML fragment).
                        if (hasBiometric) {
                            kotlinx.coroutines.delay(500L)
                            viewModel.unlockWithBiometric(this@UnlockFragment)
                        }

                        // Recovery phrase check
                        showForgotPassword = vaultService.isSetup(VaultProtectionType.RecoveryPhrase)
                    }

                    UnlockScreen(
                        unlockState = unlockState,
                        showBiometricButton = showBiometric,
                        showForgotPassword = showForgotPassword,
                        onUnlock = { password ->
                            // Sprint 7 / P2 note: the debug auto-fill password
                            // is handled below in onViewCreated (same as before).
                            viewModel.password = password
                            viewModel.unlockWithPassword()
                        },
                        onBiometricClick = {
                            viewModel.unlockWithBiometric(this@UnlockFragment)
                        },
                        onForgotPassword = {
                            forgotPassword()
                        },
                    )
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        finishOnBackWhileStarted()

        // Debug auto-fill (same as old XML fragment).
        if (BuildConfig.DEBUG) {
            viewModel.password = "Debug123!pass"
        }

        // Collect unlock state for navigation.
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.unlockState.collect { state ->
                when (state) {
                    UnlockState.Initial -> Unit
                    UnlockState.PasswordError -> Unit // Compose screen shows the error
                    UnlockState.Loading -> Unit // Compose screen shows the overlay
                    UnlockState.Unlocked -> {
                        navigateToGallery(findNavController())
                    }
                    UnlockState.StartLegacyMigration -> {
                        findNavController().navigate(R.id.action_unlockFragment_to_encryptionMigrationFragment)
                    }
                    UnlockState.Error -> {
                        Dialogs.showLongToast(requireContext(), getString(R.string.common_error))
                    }
                    UnlockState.ShowRecoveryPhrase -> {
                        findNavController().navigate(R.id.action_global_recoveryPhraseSetupFragment)
                    }
                }
            }
        }
    }

    fun forgotPassword() {
        try {
            findNavController().navigate(R.id.action_unlockFragment_to_recoveryPhraseRestoreFragment)
        } catch (e: Exception) {
            Timber.e(e)
            Dialogs.showLongToast(requireContext(), getString(R.string.common_error))
        }
    }
}
