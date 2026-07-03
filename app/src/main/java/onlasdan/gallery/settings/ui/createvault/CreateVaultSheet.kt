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

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import onlasdan.gallery.R
import onlasdan.gallery.encryption.domain.PasswordStrength
import onlasdan.gallery.encryption.domain.StrongPasswordPolicy
import onlasdan.gallery.ui.components.DialogViewModelStoreOwner
import onlasdan.gallery.ui.theme.AppTheme
import kotlinx.coroutines.launch

/**
 * Sprint 2 / M7 — "Create New Vault" bottom sheet.
 *
 * Shown from Settings → Security → "Create New Vault". Collects a new
 * password (with strength indicator + strong-policy enforcement from P7),
 * verifies it doesn't already unlock an existing vault, then calls
 * [onlasdan.gallery.encryption.domain.VaultService.createNewVault] which
 * generates a fresh VMK + persists a new VaultProtection(Password) row.
 *
 * After success: shows a toast telling the user to "lock and unlock with
 * this password to switch into the new vault". We do NOT auto-activate
 * the new vault — the mental model is "you now have N vaults; lock and
 * pick one to enter", which avoids a jarring context switch.
 *
 * @since v11 — Sprint 2 / M7 multi-vault
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateVaultSheet(
    onDismissRequest: () -> Unit,
) {
    DialogViewModelStoreOwner {
        val viewModel: CreateVaultViewModel = hiltViewModel()

        val uiState by viewModel.uiState.collectAsStateWithLifecycle()

        SheetContent(
            uiState = uiState,
            strengthLabelRes = viewModel.strengthLabel(),
            canSubmit = viewModel.canSubmit(),
            handleUiEvent = viewModel::handleUiEvent,
            onDismissRequest = onDismissRequest,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SheetContent(
    uiState: CreateVaultUiState,
    strengthLabelRes: Int,
    canSubmit: Boolean,
    handleUiEvent: (CreateVaultUiEvent) -> Unit,
    onDismissRequest: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(uiState.done) {
        if (uiState.done) {
            Toast.makeText(context, R.string.create_vault_success, Toast.LENGTH_LONG).show()
            scope.launch {
                sheetState.hide()
            }.invokeOnCompletion {
                onDismissRequest()
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.create_vault_title),
                style = MaterialTheme.typography.headlineSmall,
            )

            Text(
                text = stringResource(R.string.create_vault_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // ─── Password field with strength indicator ─────────────────────
            OutlinedTextField(
                value = uiState.password,
                onValueChange = { handleUiEvent(CreateVaultUiEvent.PasswordChanged(it)) },
                label = { Text(stringResource(R.string.create_vault_password_label)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                enabled = !uiState.loading,
                modifier = Modifier.fillMaxWidth(),
            )

            // Strength indicator + contextual rejection message
            if (uiState.password.isNotEmpty()) {
                val strength = StrongPasswordPolicy.strength(uiState.password)
                val strengthColor = when (strength) {
                    PasswordStrength.EMPTY,
                    PasswordStrength.TOO_SHORT,
                    PasswordStrength.PIN_REJECTED,
                    PasswordStrength.COMMON,
                    PasswordStrength.WEAK -> MaterialTheme.colorScheme.error
                    PasswordStrength.MODERATE -> MaterialTheme.colorScheme.tertiary
                    PasswordStrength.STRONG -> MaterialTheme.colorScheme.primary
                }
                Text(
                    text = stringResource(strengthLabelRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = strengthColor,
                )
            }

            // ─── Confirm password field ─────────────────────────────────────
            OutlinedTextField(
                value = uiState.passwordConfirm,
                onValueChange = { handleUiEvent(CreateVaultUiEvent.PasswordConfirmChanged(it)) },
                label = { Text(stringResource(R.string.create_vault_password_confirm_label)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                enabled = !uiState.loading,
                isError = uiState.passwordConfirm.isNotEmpty() &&
                    uiState.password != uiState.passwordConfirm,
                supportingText = {
                    if (uiState.passwordConfirm.isNotEmpty() &&
                        uiState.password != uiState.passwordConfirm
                    ) {
                        Text(stringResource(R.string.create_vault_passwords_dont_match))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            // ─── Local-only warning banner ──────────────────────────────────
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(R.string.create_vault_warning_local_only),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(12.dp),
                )
            }

            // ─── Error message (if any) ─────────────────────────────────────
            if (uiState.errorMessage != null) {
                Text(
                    text = uiState.errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            // ─── Loading indicator ──────────────────────────────────────────
            if (uiState.loading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            // ─── Create button ──────────────────────────────────────────────
            Button(
                onClick = { handleUiEvent(CreateVaultUiEvent.CreateVault) },
                enabled = canSubmit && !uiState.loading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.create_vault_button_create))
            }
        }
    }
}

@Suppress("unused")
@Composable
private fun CreateVaultSheetPreview() {
    AppTheme {
        SheetContent(
            uiState = CreateVaultUiState(),
            strengthLabelRes = R.string.create_vault_strength_weak,
            canSubmit = false,
            handleUiEvent = {},
            onDismissRequest = {},
        )
    }
}
