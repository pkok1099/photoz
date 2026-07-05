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

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import onlasdan.gallery.R
import onlasdan.gallery.encryption.domain.PasswordStrength
import onlasdan.gallery.encryption.domain.StrongPasswordPolicy
import onlasdan.gallery.ui.theme.AppTheme

/**
 * Sprint 10+ / M1 — Compose migration of the setup screen.
 *
 * Replaces fragment_setup.xml + ViewBinding with a pure Compose composable.
 * The fragment (SetupFragment) becomes a thin wrapper that hosts this
 * composable in a ComposeView and handles state transitions.
 *
 * State management:
 *  - [password] and [confirmPassword] are local Compose state.
 *  - [onSetup] callback fires when the user taps "Create" — the fragment
 *    passes the password to SetupViewModel.
 *  - [loading] shows a CircularProgressIndicator overlay.
 *
 * The strength indicator uses [StrongPasswordPolicy] (Sprint 1 / P7) —
 * same logic as the old XML fragment, just rendered in Compose.
 *
 * @since v14 — Sprint 10+ / M1 Compose migration
 */
@Composable
fun SetupScreen(
	loading: Boolean,
	onSetup: (String) -> Unit,
) {
	var password by remember { mutableStateOf("") }
	var confirmPassword by remember { mutableStateOf("") }
	val keyboard = LocalSoftwareKeyboardController.current

	val strength = StrongPasswordPolicy.strength(password)
	val showConfirm = StrongPasswordPolicy.isAcceptable(password)
	val passwordsMatch = password == confirmPassword && password.isNotEmpty()
	val canSubmit = passwordsMatch && !loading

	val (strengthLabel, strengthColor) =
		when (strength) {
			PasswordStrength.EMPTY,
			PasswordStrength.TOO_SHORT,
			PasswordStrength.PIN_REJECTED,
			PasswordStrength.COMMON,
			PasswordStrength.WEAK,
			-> stringResource(R.string.setup_password_strength_weak) to MaterialTheme.colorScheme.error
			PasswordStrength.MODERATE -> stringResource(R.string.setup_password_strength_moderate) to MaterialTheme.colorScheme.tertiary
			PasswordStrength.STRONG -> stringResource(R.string.setup_password_strength_strong) to MaterialTheme.colorScheme.primary
		}

	Box(modifier = Modifier.fillMaxSize()) {
		Column(
			modifier =
				Modifier
					.fillMaxSize()
					.statusBarsPadding(),
			horizontalAlignment = Alignment.CenterHorizontally,
		) {
			// App title (lobster font, 62sp)
			Text(
				text = stringResource(R.string.app_name),
				fontSize = 62.sp,
				fontFamily = FontFamily.Cursive,
				color = MaterialTheme.colorScheme.onBackground,
				textAlign = TextAlign.Center,
				modifier = Modifier.padding(top = 20.dp, bottom = 30.dp),
			)

			// "Setup" subtitle
			Text(
				text = stringResource(R.string.setupSetup),
				fontSize = 20.sp,
				color = MaterialTheme.colorScheme.onBackground,
				modifier = Modifier.padding(bottom = 30.dp),
			)

			// "Create your password" heading
			Text(
				text = stringResource(R.string.setup_create_your_password),
				style = MaterialTheme.typography.headlineMedium,
				fontWeight = FontWeight.Bold,
				color = MaterialTheme.colorScheme.onBackground,
				modifier =
					Modifier
						.padding(start = 20.dp)
						.padding(bottom = 30.dp),
			)

			// Password + confirm fields
			Column(
				modifier =
					Modifier
						.fillMaxWidth()
						.padding(horizontal = 20.dp),
				verticalArrangement = Arrangement.spacedBy(16.dp),
			) {
				// Password field
				OutlinedTextField(
					value = password,
					onValueChange = { password = it },
					label = { Text(stringResource(R.string.setup_enter_password)) },
					singleLine = true,
					visualTransformation = PasswordVisualTransformation(),
					keyboardOptions =
						androidx.compose.foundation.text.KeyboardOptions(
							keyboardType = KeyboardType.Password,
							imeAction = ImeAction.Next,
						),
					enabled = !loading,
					modifier = Modifier.fillMaxWidth(),
				)

				// Strength indicator (visible when password not empty)
				if (password.isNotEmpty()) {
					Text(
						text = "${stringResource(R.string.setup_password_strength_label)} $strengthLabel",
						style = MaterialTheme.typography.bodySmall,
						color = strengthColor,
					)
				}

				// Confirm password field (only visible when password is acceptable)
				AnimatedVisibility(visible = showConfirm) {
					OutlinedTextField(
						value = confirmPassword,
						onValueChange = { confirmPassword = it },
						label = { Text(stringResource(R.string.setup_confirm_password)) },
						singleLine = true,
						visualTransformation = PasswordVisualTransformation(),
						keyboardOptions =
							androidx.compose.foundation.text.KeyboardOptions(
								keyboardType = KeyboardType.Password,
								imeAction = ImeAction.Done,
							),
						keyboardActions =
							androidx.compose.foundation.text.KeyboardActions(
								onDone = {
									keyboard?.hide()
									if (canSubmit) onSetup(password)
								},
							),
						enabled = !loading,
						isError = confirmPassword.isNotEmpty() && !passwordsMatch,
						supportingText = {
							if (confirmPassword.isNotEmpty() && !passwordsMatch) {
								Text(
									text = stringResource(R.string.setup_password_match_warning),
									color = MaterialTheme.colorScheme.error,
								)
							}
						},
						modifier = Modifier.fillMaxWidth(),
					)
				}
			}

			// Create button
			Button(
				onClick = {
					keyboard?.hide()
					onSetup(password)
				},
				enabled = canSubmit,
				modifier = Modifier.padding(top = 20.dp),
			) {
				Text(stringResource(R.string.setup_button))
			}
		}

		// Loading overlay
		if (loading) {
			Surface(
				color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f),
				modifier = Modifier.fillMaxSize(),
			) {
				Box(contentAlignment = Alignment.Center) {
					CircularProgressIndicator()
				}
			}
		}
	}
}

@Preview(showBackground = true)
@Composable
private fun SetupScreenPreview() {
	AppTheme {
		SetupScreen(
			loading = false,
			onSetup = {},
		)
	}
}
