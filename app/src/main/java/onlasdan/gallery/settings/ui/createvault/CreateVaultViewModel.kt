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

package onlasdan.gallery.settings.ui.createvault

import android.content.res.Resources
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import onlasdan.gallery.R
import onlasdan.gallery.encryption.domain.StrongPasswordPolicy
import onlasdan.gallery.encryption.domain.VaultService
import onlasdan.gallery.encryption.domain.models.UnlockRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Sprint 2 / M7 — UI state for the "Create New Vault" sheet.
 *
 * The sheet has 3 fields (password, confirm, ready-to-create) and shows
 * a strength indicator + contextual rejection message using the same
 * [StrongPasswordPolicy] logic as the setup screen (P7).
 *
 * The "Create" button is gated by:
 *  1. Password passes [StrongPasswordPolicy.isAcceptable] (length, class
 *     diversity, no PIN, no common-password blacklist).
 *  2. Confirm password matches.
 *  3. Password does NOT unlock an existing vault — verified by calling
 *     [VaultService.unlock] and expecting it to FAIL. If unlock succeeds,
 *     the password already corresponds to a vault; we reject with a
 *     "this password already unlocks an existing vault" message so the
 *     user doesn't accidentally shadow an existing vault.
 */
data class CreateVaultUiState(
    val loading: Boolean = false,
    val password: String = "",
    val passwordConfirm: String = "",
    val passwordAlreadyUnlocks: Boolean = false,
    val done: Boolean = false,
    val errorMessage: String? = null,
)

sealed interface CreateVaultUiEvent {
    data class PasswordChanged(val value: String) : CreateVaultUiEvent
    data class PasswordConfirmChanged(val value: String) : CreateVaultUiEvent
    data object CreateVault : CreateVaultUiEvent
}

@HiltViewModel
class CreateVaultViewModel @Inject constructor(
    private val vaultService: VaultService,
    private val resources: Resources,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CreateVaultUiState())
    val uiState = _uiState.asStateFlow()

    fun handleUiEvent(event: CreateVaultUiEvent) {
        when (event) {
            is CreateVaultUiEvent.PasswordChanged -> _uiState.update {
                it.copy(password = event.value, passwordAlreadyUnlocks = false, errorMessage = null)
            }
            is CreateVaultUiEvent.PasswordConfirmChanged -> _uiState.update {
                it.copy(passwordConfirm = event.value)
            }
            is CreateVaultUiEvent.CreateVault -> createVault()
        }
    }

    /**
     * Strength bucket for the password field — drives the UI color + label.
     * Mirrors the logic in SetupFragment so the user sees consistent feedback
     * across both setup and create-vault screens.
     */
    fun strengthLabel(): Int = when (StrongPasswordPolicy.strength(_uiState.value.password)) {
        onlasdan.gallery.encryption.domain.PasswordStrength.EMPTY,
        onlasdan.gallery.encryption.domain.PasswordStrength.TOO_SHORT,
        onlasdan.gallery.encryption.domain.PasswordStrength.PIN_REJECTED,
        onlasdan.gallery.encryption.domain.PasswordStrength.COMMON,
        onlasdan.gallery.encryption.domain.PasswordStrength.WEAK,
        -> R.string.create_vault_strength_weak
        onlasdan.gallery.encryption.domain.PasswordStrength.MODERATE -> R.string.create_vault_strength_moderate
        onlasdan.gallery.encryption.domain.PasswordStrength.STRONG -> R.string.create_vault_strength_strong
    }

    /**
     * True when the password is acceptable AND matches the confirm field.
     * Used by the Sheet to enable/disable the Create button.
     */
    fun canSubmit(): Boolean {
        val state = _uiState.value
        return StrongPasswordPolicy.isAcceptable(state.password) &&
            state.password == state.passwordConfirm &&
            !state.passwordAlreadyUnlocks
    }

    private fun createVault() {
        val state = _uiState.value
        if (!canSubmit()) return

        _uiState.update { it.copy(loading = true, errorMessage = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // ─── Verify the password doesn't already unlock a vault ─────
                // The unlock path iterates all Password rows. If any row's auth
                // tag verifies, this password corresponds to an existing vault.
                // We reject so the user doesn't accidentally shadow it.
                val existingUnlock = vaultService.unlock(UnlockRequest.Password(state.password))
                if (existingUnlock.isSuccess) {
                    _uiState.update {
                        it.copy(
                            loading = false,
                            passwordAlreadyUnlocks = true,
                            errorMessage = resources.getString(R.string.create_vault_password_already_unlocks),
                        )
                    }
                    return@launch
                }

                // ─── Create the new vault ──────────────────────────────────
                // vaultService.createNewVault generates a fresh VMK, wraps it
                // with the password, persists a new VaultProtection(Password)
                // row, and returns the new VaultSession.
                //
                // We DON'T activate the new session here — we leave the user
                // in their current vault and show a success message telling
                // them to "lock + unlock with this password" to switch into
                // the new vault. This avoids a jarring context switch (current
                // vault's gallery disappearing mid-tap) and makes the
                // multi-vault mental model explicit: "you now have N vaults;
                // lock and pick one to enter."
                vaultService.createNewVault(state.password)
                _uiState.update { it.copy(loading = false, done = true) }
            } catch (e: Exception) {
                Timber.e(e, "createVault failed")
                _uiState.update {
                    it.copy(
                        loading = false,
                        errorMessage = resources.getString(
                            R.string.create_vault_failed, e.message ?: e.javaClass.simpleName
                        ),
                    )
                }
            }
        }
    }
}
