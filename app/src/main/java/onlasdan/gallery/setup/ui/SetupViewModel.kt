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

package onlasdan.gallery.setup.ui

import android.app.Application
import androidx.databinding.Bindable
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import onlasdan.gallery.BR
import onlasdan.gallery.encryption.domain.PasswordUtils
import onlasdan.gallery.encryption.domain.SessionRepository
import onlasdan.gallery.encryption.domain.VaultService
import onlasdan.gallery.encryption.domain.crypto.Bip39WordCount
import onlasdan.gallery.encryption.domain.models.CreateRequest
import onlasdan.gallery.encryption.domain.models.UnlockRequest
import onlasdan.gallery.other.extensions.empty
import onlasdan.gallery.settings.data.Config
import onlasdan.gallery.sync.rclone.RepoManager
import onlasdan.gallery.telemetry.domain.Signal
import onlasdan.gallery.telemetry.domain.TelemetryService
import onlasdan.gallery.uicomponnets.bindings.ObservableViewModel
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * ViewModel for the setup.
 * Handles password validation, saving password, initializing the [EncryptionManager], etc.
 *
 * @since 1.0.0
 * @author PhotoZ
 */
@HiltViewModel
class SetupViewModel @Inject constructor(
    app: Application,
    private val config: Config,
    private val vaultService: VaultService,
    private val sessionRepository: SessionRepository,
    private val telemetryService: TelemetryService,
    // @since Part A fix + Part B two-layer escrow — needed to upload both escrow
    // layers (wrapped VMK + wrapped phrase) to the rclone remote AFTER the
    // vault is created. The prior call site (registerRepo) ran BEFORE
    // SetupFragment and always skipped escrow upload — see the long note in
    // RepoManager.registerRepo().
    private val repoManager: RepoManager,
) : ObservableViewModel(app) {

    //region binding properties

    @Bindable
    var password: String = String.empty
        set(value) {
            field = value
            notifyChange(BR.password, value)
        }

    @Bindable
    var confirmPassword: String = String.empty
        set(value) {
            field = value
            notifyChange(BR.confirmPassword, value)
        }

    @get:Bindable
    var setupState: SetupState = SetupState.SETUP
        set(value) {
            field = value
            notifyChange(BR.setupState, value)
        }

    // endregion

    fun onSetupClicked() = viewModelScope.launch {

        if (validateBothPasswords()) {
            setupState = SetupState.LOADING

            vaultService.create(CreateRequest.Password(password))
            vaultService.unlock(UnlockRequest.Password(password))
                .onSuccess { session ->
                    sessionRepository.set(session)
                    vaultService.create(CreateRequest.RecoveryPhrase(session, Bip39WordCount.Twelve))

                    // Upload both escrow layers to the remote (wrappedVMK + wrappedPhrase).
                    // This MUST happen after both password + phrase VaultProtection rows
                    // exist in the local DB, which is why it's here (after SetupFragment
                    // creates them) rather than during registerRepo() (which runs before
                    // SetupFragment — that prior ordering was the root cause of the
                    // register-always-triggered bug: the premature
                    // uploadVaultProtectionEscrow() call always saw a null
                    // VaultProtection(RecoveryPhrase) row and skipped).
                    //
                    // Non-fatal: if escrow upload fails, setup still completes — the
                    // vault is usable on this device; only fresh-install restore won't
                    // work. The user can re-upload from Settings → Re-register repo
                    // (follow-up, not in scope).
                    // @since Part A fix + Part B two-layer escrow
                    if (config.repoConfirmed) {
                        viewModelScope.launch {
                            try {
                                val result = repoManager.uploadAllEscrows(password, session)
                                if (result.isFailure) {
                                    Timber.w(result.exceptionOrNull(), "Escrow upload failed (non-fatal)")
                                }
                            } catch (e: Exception) {
                                Timber.w(e, "Escrow upload threw (non-fatal)")
                            }
                        }
                    }

                    config.justFinishedSetup = true
                    telemetryService.signal(Signal.SetupCompleted)
                    setupState = SetupState.SHOW_RECOVERY_PHRASE
                }
                .onFailure {
                    setupState = SetupState.SETUP
                }

        }
    }

    /**
     * Validate hte [password] property.
     */
    fun validatePassword() = PasswordUtils.validatePassword(password)

    /**
     * @see PasswordUtils.passwordsNotEmptyAndEqual
     */
    fun passwordsEqual() =
        PasswordUtils.passwordsNotEmptyAndEqual(password, confirmPassword)

    /**
     * @see PasswordUtils.validatePasswords
     */
    fun validateBothPasswords() = PasswordUtils.validatePasswords(password, confirmPassword)
}