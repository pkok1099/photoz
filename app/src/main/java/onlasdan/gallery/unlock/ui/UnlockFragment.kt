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
import android.view.View
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import onlasdan.gallery.BR
import onlasdan.gallery.BuildConfig
import onlasdan.gallery.R
import onlasdan.gallery.databinding.FragmentUnlockBinding
import onlasdan.gallery.encryption.domain.VaultService
import onlasdan.gallery.encryption.domain.models.VaultProtectionType
import onlasdan.gallery.gallery.ui.navigation.NavigateToGallery
import onlasdan.gallery.other.extensions.finishOnBackWhileStarted
import onlasdan.gallery.other.extensions.hide
import onlasdan.gallery.other.extensions.launchLifecycleAwareJob
import onlasdan.gallery.other.extensions.show
import onlasdan.gallery.other.extensions.vanish
import onlasdan.gallery.other.systemBarsPadding
import onlasdan.gallery.settings.data.Config
import onlasdan.gallery.uicomponnets.Dialogs
import onlasdan.gallery.uicomponnets.base.hideKeyboard
import onlasdan.gallery.uicomponnets.bindings.BindableFragment
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Unlock fragment.
 * Handles state and login.
 *
 * @since 1.0.0
 * @author PhotoZ
 */
@AndroidEntryPoint
class UnlockFragment : BindableFragment<FragmentUnlockBinding>(R.layout.fragment_unlock) {

    private val viewModel: UnlockViewModel by viewModels()

    @Inject
    lateinit var config: Config

    @Inject
    lateinit var vaultService: VaultService

    @Inject
    lateinit var navigateToGallery: NavigateToGallery

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.systemBarsPadding()
        finishOnBackWhileStarted()

        if (BuildConfig.DEBUG) {
            viewModel.password = "abc123"
        }

        launchLifecycleAwareJob {
            viewModel.unlockState.collect {
                when (it) {
                    UnlockState.Initial -> Unit
                    UnlockState.PasswordError -> {
                        binding.loadingOverlay.hide()
                        binding.unlockWrongPasswordWarningTextView.show()
                    }

                    UnlockState.Loading -> binding.loadingOverlay.show()
                    UnlockState.Unlocked -> {
                        binding.loadingOverlay.hide()
                        activity?.hideKeyboard()
                        navigateToGallery(findNavController())
                    }

                    UnlockState.StartLegacyMigration -> {
                        binding.loadingOverlay.hide()
                        activity?.hideKeyboard()

                        findNavController().navigate(R.id.action_unlockFragment_to_encryptionMigrationFragment)
                    }

                    UnlockState.Error -> showErrorToast()

                    UnlockState.ShowRecoveryPhrase -> {
                        binding.loadingOverlay.hide()
                        activity?.hideKeyboard()
                        findNavController().navigate(R.id.action_global_recoveryPhraseSetupFragment)
                    }
                }
            }
        }

        viewModel.addOnPropertyChange<String>(BR.password) {
            if (binding.unlockWrongPasswordWarningTextView.visibility != View.INVISIBLE) {
                binding.unlockWrongPasswordWarningTextView.vanish()
            }
        }

        super.onViewCreated(view, savedInstanceState)

        lifecycleScope.launch {
            if (vaultService.isSetup(VaultProtectionType.Biometric) || vaultService.canMigrate(VaultProtectionType.Biometric)) {
                binding.unlockUseBiometricUnlockButton.show()

                delay(500L)
                viewModel.unlockWithBiometric(fragment = this@UnlockFragment)
            } else {
                binding.unlockUseBiometricUnlockButton.hide()
            }
        }

        lifecycleScope.launch {
            if (vaultService.isSetup(VaultProtectionType.RecoveryPhrase)) {
                binding.unlockForgotPassword.show()
            } else {
                binding.unlockForgotPassword.hide()
            }
        }
    }

    private fun showErrorToast() {
        binding.loadingOverlay.hide()
        Dialogs.showLongToast(requireContext(), getString(R.string.common_error))
    }

    fun forgotPassword() {
        try {
            findNavController().navigate(R.id.action_unlockFragment_to_recoveryPhraseRestoreFragment)
        } catch (e: Exception) {
            Timber.e(e)
            showErrorToast()
        }
    }

    override fun bind(binding: FragmentUnlockBinding) {
        super.bind(binding)
        binding.context = this
        binding.viewModel = viewModel
    }
}