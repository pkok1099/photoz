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

import android.app.Application
import android.content.res.Resources
import androidx.databinding.Bindable
import androidx.fragment.app.Fragment
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import onlasdan.gallery.BR
import onlasdan.gallery.R
import onlasdan.gallery.encryption.domain.LegacyEncryption
import onlasdan.gallery.encryption.domain.SessionRepository
import onlasdan.gallery.encryption.domain.VaultService
import onlasdan.gallery.encryption.domain.crypto.Bip39WordCount
import onlasdan.gallery.encryption.domain.models.CreateRequest
import onlasdan.gallery.encryption.domain.models.UnlockRequest
import onlasdan.gallery.encryption.domain.models.VaultProtectionType
import onlasdan.gallery.encryption.migration.LegacyEncryptionMigrator
import onlasdan.gallery.encryption.ui.UserCanceledBiometricsException
import onlasdan.gallery.other.extensions.empty
import onlasdan.gallery.settings.data.Config
import onlasdan.gallery.uicomponnets.Dialogs
import onlasdan.gallery.uicomponnets.bindings.ObservableViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * ViewModel for unlocking the safe.
 * Handles state, password validation and initializing the encryption.
 * Just like the setup.
 *
 * @since 1.0.0
 * @author PhotoZ
 */
@HiltViewModel
class UnlockViewModel @Inject constructor(
    app: Application,
    private val config: Config,
    private val resources: Resources,
    private val vaultService: VaultService,
    private val sessionRepository: SessionRepository,
    private val legacyEncryptionMigrator: LegacyEncryptionMigrator,
    private val legacyEncryption: LegacyEncryption,
    private val breakInDetector: onlasdan.gallery.security.BreakInDetector,
) : ObservableViewModel(app) {

    @Bindable
    var password: String = String.empty
        set(value) {
            field = value
            notifyChange(BR.password, value)
        }

    val unlockState: MutableStateFlow<UnlockState> = MutableStateFlow(UnlockState.Initial)

    /**
     * Sprint 7+ / P2 — Break-in warning string (null = no warning).
     *
     * Populated by [unlockWithPassword] / [unlockWithBiometric] on success
     * when there were failed attempts since the last login. The UI
     * (UnlockFragment) observes this and shows an AlertDialog.
     *
     * @since v14 — Sprint 7+ / P2 break-in warning UI
     */
    val breakInWarning: MutableStateFlow<String?> = MutableStateFlow(null)

    /**
     * Tries to unlock the save.
     * Compares [password] to saved hash.
     * Updates UnlockState.
     * Called by ui.
     */
    fun unlockWithPassword() {
        unlockState.update { UnlockState.Loading }

        viewModelScope.launch {
            try {
                vaultService.unlock(UnlockRequest.Password(password))
                    .onSuccess { session ->
                        sessionRepository.set(session)

                        // Sprint 7+ / P2 — Consume break-in warning on success.
                        // If there were failed attempts since last login, surface
                        // the warning to the UI. The counter is reset by consume.
                        breakInWarning.value = breakInDetector.consumeWarningIfAny()

                        if (legacyEncryptionMigrator.migrationNeeded() || config.legacyCurrentlyMigrating) {
                            val legacySession = legacyEncryption.obtainSession(password)
                            legacyEncryptionMigrator.initialize(legacySession)

                            unlockState.update { UnlockState.StartLegacyMigration }
                        } else if (!vaultService.isSetup(VaultProtectionType.RecoveryPhrase)) {
                            vaultService.create(CreateRequest.RecoveryPhrase(session, Bip39WordCount.Twelve))
                            unlockState.update { UnlockState.ShowRecoveryPhrase }
                        } else {
                            unlockState.update { UnlockState.Unlocked }
                        }
                    }
                    .onFailure {
                        unlockState.update { UnlockState.PasswordError }
                    }
            } catch (e: Exception) {
                Timber.e(e)
                unlockState.update { UnlockState.Error }
            }
        }
    }

    fun unlockWithBiometric(fragment: Fragment) {
        viewModelScope.launch {
            vaultService.unlock(UnlockRequest.Biometric(fragment))
                .onSuccess { session ->
                    sessionRepository.set(session)
                    // Sprint 7+ / P2 — consume break-in warning on biometric success.
                    breakInWarning.value = breakInDetector.consumeWarningIfAny()
                    unlockState.update { UnlockState.Unlocked }
                }
                .onFailure {
                    if (it !is UserCanceledBiometricsException) {
                        Dialogs.showLongToast(
                            context = fragment.requireContext(),
                            message = resources.getString(R.string.biometric_unlock_error),
                        )
                    }
                }
        }
    }

}