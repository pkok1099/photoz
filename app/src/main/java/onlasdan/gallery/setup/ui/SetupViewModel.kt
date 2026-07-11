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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
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
class SetupViewModel
	@Inject
	constructor(
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
		var setupState: MutableStateFlow<SetupState> = MutableStateFlow(SetupState.SETUP)
			private set

		// endregion

		fun onSetupClicked() =
			viewModelScope.launch {
				if (validateBothPasswords()) {
					setupState.value = SetupState.LOADING

					// ANTI-DATA-LOSS: If pendingPasswordSetup is true, the VMK was
					// recovered from a recovery-phrase restore (PHRASE_ONLY login flow).
					// We must NOT generate a new VMK — wrap the EXISTING VMK with the
					// user's password so canUnlock() returns true on next app open.
					if (config.pendingPasswordSetup) {
						val session = sessionRepository.get()
						if (session == null) {
							// Should not happen — phrase restore sets session before
							// navigating here. But if it does, fall back to full setup.
							// ANTI-DATA-LOSS: session is null means process death happened.
							// Do NOT create a new VMK — redirect to RepoSetup for re-login.
							Timber.e("pendingPasswordSetup=true but session is null — process death. Redirecting to re-login.")
							config.pendingPasswordSetup = false
							config.systemFirstStart = true
							setupState.value = SetupState.SETUP
							return@launch
						}
						// Wrap existing VMK with the user's new password
						vaultService.createPasswordProtectionFromSession(password, session)
						config.pendingPasswordSetup = false
						// Skip recovery phrase creation — it already exists from the
						// original setup. Just upload escrows if repo is confirmed.
						if (config.repoConfirmed) {
							// F-HOTFIX-003: wrap in NonCancellable so the upload completes
							// even if the user navigates away and the ViewModel is cleared.
							viewModelScope.launch {
								withContext(kotlinx.coroutines.NonCancellable) {
									try {
										val result = repoManager.uploadAllEscrows(password, session)
										if (result.isFailure) {
											Timber.e(result.exceptionOrNull(), "ANTI-DATA-LOSS: Escrow re-upload FAILED — fresh-install recovery will NOT work!")
										} else {
											Timber.i("ANTI-DATA-LOSS: Escrow re-upload OK — fresh-install recovery available")
										}
									} catch (e: Exception) {
										Timber.e(e, "ANTI-DATA-LOSS: Escrow re-upload threw — fresh-install recovery will NOT work!")
									}
								}
							}
						}
						config.justFinishedSetup = true
						setupState.value = SetupState.SHOW_RECOVERY_PHRASE
						return@launch
					}

					// Normal flow: create new VMK + new vault
					vaultService.create(CreateRequest.Password(password))
					vaultService
						.unlock(UnlockRequest.Password(password))
						.onSuccess { session ->
							sessionRepository.set(session)
							completeSetupWithSession(session, password)
						}.onFailure {
							setupState.value = SetupState.SETUP
						}
				}
			}

		private suspend fun completeSetupWithSession(
			session: onlasdan.gallery.encryption.domain.models.VaultSession,
			password: String,
		) {
			vaultService.create(CreateRequest.RecoveryPhrase(session, Bip39WordCount.Twelve))

			// Upload both escrow layers to the remote (wrappedVMK + wrappedPhrase).
			// Non-fatal: if escrow upload fails, setup still completes.
			if (config.repoConfirmed) {
				// F-HOTFIX-003: wrap in NonCancellable so the upload completes
				// even if the user navigates away and the ViewModel is cleared.
				// Without this, the fire-and-forget viewModelScope.launch is
				// cancelled mid-upload → no escrow on remote → data loss on
				// fresh-install login.
				viewModelScope.launch {
					withContext(kotlinx.coroutines.NonCancellable) {
						try {
							val result = repoManager.uploadAllEscrows(password, session)
							if (result.isFailure) {
								Timber.e(result.exceptionOrNull(), "ANTI-DATA-LOSS: Escrow upload FAILED — fresh-install recovery will NOT work!")
							} else {
								Timber.i("ANTI-DATA-LOSS: Escrow upload OK — fresh-install recovery available")
							}
						} catch (e: Exception) {
							Timber.e(e, "ANTI-DATA-LOSS: Escrow upload threw — fresh-install recovery will NOT work!")
						}
					}
				}
			}

			config.justFinishedSetup = true
			telemetryService.signal(Signal.SetupCompleted)
			setupState.value = SetupState.SHOW_RECOVERY_PHRASE
		}

		/**
		 * Validate hte [password] property.
		 */
		fun validatePassword() = PasswordUtils.validatePassword(password)

		/**
		 * @see PasswordUtils.passwordsNotEmptyAndEqual
		 */
		fun passwordsEqual() = PasswordUtils.passwordsNotEmptyAndEqual(password, confirmPassword)

		/**
		 * @see PasswordUtils.validatePasswords
		 */
		fun validateBothPasswords() = PasswordUtils.validatePasswords(password, confirmPassword)
	}
