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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import onlasdan.gallery.R
import onlasdan.gallery.ui.theme.AppTheme

/**
 * Sprint 10+ / M1 — Compose migration of the unlock screen.
 *
 * Replaces the XML layout (fragment_unlock.xml) + ViewBinding with a pure
 * Compose composable. The fragment (UnlockFragment) becomes a thin wrapper
 * that hosts this composable in a ComposeView and handles navigation.
 *
 * State management:
 *  - [password] is local Compose state (not ObservableField) — simpler than
 *    the old Bindable approach. The fragment reads it via the [onUnlock]
 *    callback when the user taps "Unlock".
 *  - [unlockState] is collected by the fragment, not here — the fragment
 *    handles navigation (navigateToGallery, showRecoveryPhrase, etc.).
 *    This screen only reflects Loading / PasswordError visually.
 *
 * @since v14 — Sprint 10+ / M1 Compose migration
 */
@Composable
fun UnlockScreen(
    unlockState: UnlockState,
    showBiometricButton: Boolean,
    showForgotPassword: Boolean,
    onUnlock: (String) -> Unit,
    onBiometricClick: () -> Unit,
    onForgotPassword: () -> Unit,
) {
    var password by remember { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current

    // Clear password on error so the user can retype.
    LaunchedEffect(unlockState) {
        if (unlockState == UnlockState.PasswordError) {
            password = ""
        }
    }

    val isLoading = unlockState == UnlockState.Loading
    val showWrongPassword = unlockState == UnlockState.PasswordError

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // App title (lobster font, 62sp — matches AppNameTitleStyle)
            Text(
                text = stringResource(R.string.app_name),
                fontSize = 62.sp,
                fontFamily = FontFamily.Cursive,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 20.dp, bottom = 40.dp),
            )

            // "Unlock your safe" heading
            Text(
                text = stringResource(R.string.unlock_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                modifier = Modifier
                    .padding(start = 20.dp)
                    .width(280.dp),
            )

            // Password field + wrong password warning
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 30.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.unlock_enter_password)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                        onDone = {
                            keyboard?.hide()
                            if (password.isNotEmpty() && !isLoading) {
                                onUnlock(password)
                            }
                        },
                    ),
                    enabled = !isLoading,
                    isError = showWrongPassword,
                    supportingText = {
                        if (showWrongPassword) {
                            Text(
                                text = stringResource(R.string.unlock_wrong_password),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // Unlock button
            Button(
                onClick = {
                    keyboard?.hide()
                    onUnlock(password)
                },
                enabled = password.isNotEmpty() && !isLoading,
                modifier = Modifier.padding(top = 10.dp),
            ) {
                Text(stringResource(R.string.unlock_button))
            }
        }

        // Bottom-aligned buttons: forgot password + biometric
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (showForgotPassword) {
                TextButton(onClick = onForgotPassword) {
                    Text(
                        text = stringResource(R.string.recovery_phrase_forgot_password),
                        fontSize = 14.sp,
                    )
                }
            }
            if (showBiometricButton) {
                TextButton(onClick = onBiometricClick) {
                    Text(stringResource(R.string.biometric_unlock_hint_button))
                }
            }
        }

        // Loading overlay
        if (isLoading) {
            Surface(
                color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxSize(),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun UnlockScreenPreview() {
    AppTheme {
        UnlockScreen(
            unlockState = UnlockState.Initial,
            showBiometricButton = true,
            showForgotPassword = true,
            onUnlock = {},
            onBiometricClick = {},
            onForgotPassword = {},
        )
    }
}
