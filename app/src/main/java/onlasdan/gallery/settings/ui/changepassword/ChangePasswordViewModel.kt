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

package onlasdan.gallery.settings.ui.changepassword

import android.content.res.Resources
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import onlasdan.gallery.R
import onlasdan.gallery.encryption.domain.ChangePasswordUseCase
import onlasdan.gallery.encryption.domain.PasswordUtils
import onlasdan.gallery.encryption.domain.SessionRepository
import onlasdan.gallery.encryption.domain.VaultService
import onlasdan.gallery.encryption.domain.models.UnlockRequest
import onlasdan.gallery.encryption.domain.models.VaultProtectionType
import onlasdan.gallery.settings.data.Config
import onlasdan.gallery.sync.rclone.RepoManager
import timber.log.Timber
import javax.inject.Inject

data class ChangePasswordUiState(
	val loading: Boolean = false,
	val recoveryPhraseWasUsed: Boolean = false,
	val step: Step = Step.CheckOld,
	val oldPassword: String = "",
	val oldPasswordError: String? = null,
	val newPassword: String = "",
	val newPasswordConfirm: String = "",
	val done: Boolean = false,
) {
	enum class Step {
		CheckOld,
		SetNew,
	}
}

sealed interface ChangePasswordUiEvent {
	data class OldPasswordChanged(
		val value: String,
	) : ChangePasswordUiEvent

	data object CheckOldPassword : ChangePasswordUiEvent

	data class NewPasswordChanged(
		val value: String,
	) : ChangePasswordUiEvent

	data class NewPasswordConfirmChanged(
		val value: String,
	) : ChangePasswordUiEvent

	data object ChangePassword : ChangePasswordUiEvent
}

@HiltViewModel
class ChangePasswordViewModel
	@Inject
	constructor(
		private val changePasswordUseCase: ChangePasswordUseCase,
		private val vaultService: VaultService,
		private val sessionRepository: SessionRepository,
		private val repoManager: RepoManager,
		private val config: Config,
		private val resources: Resources,
	) : ViewModel() {
		private val recoveryPhraseUsed = config.lastUsedUnlockMethod == VaultProtectionType.RecoveryPhrase

		private val _uiState =
			MutableStateFlow(
				ChangePasswordUiState(
					recoveryPhraseWasUsed = recoveryPhraseUsed,
					step =
						if (recoveryPhraseUsed) {
							ChangePasswordUiState.Step.SetNew
						} else {
							ChangePasswordUiState.Step.CheckOld
						},
				),
			)
		val uiState = _uiState.asStateFlow()

		fun handleUiEvent(event: ChangePasswordUiEvent) {
			when (event) {
				is ChangePasswordUiEvent.OldPasswordChanged ->
					_uiState.update { it.copy(oldPassword = event.value, oldPasswordError = null) }
				is ChangePasswordUiEvent.CheckOldPassword -> checkOld()
				is ChangePasswordUiEvent.NewPasswordChanged ->
					_uiState.update { it.copy(newPassword = event.value) }
				is ChangePasswordUiEvent.NewPasswordConfirmChanged ->
					_uiState.update { it.copy(newPasswordConfirm = event.value) }
				is ChangePasswordUiEvent.ChangePassword -> changePassword()
			}
		}

		private fun checkOld() {
			val password = _uiState.value.oldPassword
			_uiState.update { it.copy(loading = true, oldPasswordError = null) }
			viewModelScope.launch {
				vaultService
					.unlock(UnlockRequest.Password(password))
					.onSuccess {
						_uiState.update { it.copy(loading = false, step = ChangePasswordUiState.Step.SetNew) }
					}.onFailure {
						_uiState.update {
							it.copy(
								loading = false,
								oldPasswordError = resources.getString(R.string.unlock_wrong_password),
							)
						}
					}
			}
		}

		private fun changePassword() {
			val state = _uiState.value
			if (!PasswordUtils.validatePasswords(state.newPassword, state.newPasswordConfirm)) return

			_uiState.update { it.copy(loading = true) }
			viewModelScope.launch(Dispatchers.IO) {
				changePasswordUseCase(state.newPassword)
					.onSuccess {
						// ─── Batch 1 / Item 4: re-upload the key escrow to the remote ──
						// [ChangePasswordUseCase] only updates the LOCAL password
						// VaultProtection row — it re-wraps the SAME VMK with the new
						// password-derived KEK. The VMK itself doesn't change, so the
						// already-uploaded `.crypt` photo files do NOT need re-encryption.
						//
						// But the two escrow artifacts on the remote DO need a refresh:
						//   - `recovery-phrase.json.crypt` (Layer 1): VMK wrapped by the
						//     recovery phrase. NOT affected by password change (the
						//     recovery phrase and VMK are unchanged). Re-uploading is
						//     wasteful but harmless — the outer GCM nonce is fresh, so
						//     the ciphertext differs but the payload is identical.
						//   - `wrapped-phrase.json.crypt` (Layer 2): recovery phrase
						//     wrapped by the user's PASSWORD. This DOES change after a
						//     password change — the old password no longer unwraps it,
						//     so we MUST re-upload Layer 2 with the new password.
						//
						// For simplicity and safety, [RepoManager.uploadAllEscrows]
						// re-uploads BOTH layers atomically. Non-fatal: if the upload
						// fails, the password change still succeeded locally — the user
						// can still unlock with the new password on THIS device. Only
						// fresh-install restore from this point would be broken until
						// they re-run setup.
						val session = sessionRepository.get()
						if (session != null && config.repoConfirmed) {
							try {
								val escrowResult = repoManager.uploadAllEscrows(state.newPassword, session)
								if (escrowResult.isFailure) {
									Timber.w(
										escrowResult.exceptionOrNull(),
										"Escrow re-upload after password change failed (non-fatal)",
									)
								} else {
									Timber.i("Escrow re-upload after password change OK")
								}
							} catch (e: Exception) {
								Timber.w(e, "Escrow re-upload after password change threw (non-fatal)")
							}
						} else {
							Timber.w(
								"Skipping escrow re-upload after password change: session=${session != null} repoConfirmed=${config.repoConfirmed}",
							)
						}

						_uiState.update { it.copy(done = true, loading = false) }
					}.onFailure { Timber.e(it) }
			}
		}
	}
