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

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import onlasdan.gallery.BuildConfig
import onlasdan.gallery.R
import onlasdan.gallery.gallery.ui.navigation.NavigateToGallery
import onlasdan.gallery.other.extensions.finishOnBackWhileStarted
import onlasdan.gallery.ui.theme.AppTheme
import timber.log.Timber
import javax.inject.Inject

/**
 * Sprint 10+ / M1 — SetupFragment migrated to Compose.
 *
 * Previously extended [BindableFragment] with XML layout (fragment_setup.xml)
 * + ViewBinding. Now extends plain [Fragment] and hosts a [ComposeView] that
 * renders either [SetupScreen] (password entry) or [RecoveryPhraseSetupScreen]
 * (phrase display) depending on [SetupViewModel.setupState].
 *
 * The inline recovery-phrase display (Item 4) is preserved — when state
 * transitions to SHOW_RECOVERY_PHRASE, the ComposeView's content switches
 * from SetupScreen to RecoveryPhraseSetupScreen. No fragment navigation
 * needed — the user stays on one screen for the entire flow.
 *
 * @since v14 — Sprint 10+ / M1 Compose migration
 */
@AndroidEntryPoint
class SetupFragment : Fragment() {
	private val viewModel: SetupViewModel by viewModels()

	@Inject
	lateinit var navigateToGallery: NavigateToGallery

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View =
		ComposeView(requireContext()).apply {
			setContent {
				AppTheme {
					val setupState by viewModel.setupState.collectAsStateWithLifecycle()

					// Debug auto-fill (P7 strong policy compliant).
					if (BuildConfig.DEBUG) {
						LaunchedEffect(Unit) {
							viewModel.password = "Debug123!pass"
							viewModel.confirmPassword = "Debug123!pass"
						}
					}

					when (setupState) {
						SetupState.SETUP -> {
							SetupScreen(
								loading = false,
								onSetup = { password ->
									viewModel.password = password
									viewModel.confirmPassword = password
									viewModel.onSetupClicked()
								},
							)
						}
						SetupState.LOADING -> {
							SetupScreen(
								loading = true,
								onSetup = {},
							)
						}
						SetupState.SHOW_RECOVERY_PHRASE -> {
							// Inline recovery phrase display — same Composable
							// as the standalone RecoveryPhraseSetupFragment,
							// rendered in-place without fragment navigation.
							showRecoveryPhraseInline()
						}
						SetupState.FINISHED -> {
							// Will be navigated away by LaunchedEffect below.
							SetupScreen(loading = true, onSetup = {})
						}
					}

					// Handle FINISHED state → navigate to gallery.
					LaunchedEffect(setupState) {
						if (setupState == SetupState.FINISHED) {
							navigateToGalleryOrPop()
						}
					}
				}
			}
		}

	override fun onViewCreated(
		view: View,
		savedInstanceState: Bundle?,
	) {
		super.onViewCreated(view, savedInstanceState)
		finishOnBackWhileStarted()
	}

	/**
	 * Render RecoveryPhraseSetupScreen inline with the POST_NOTIFICATIONS
	 * permission launcher wired to the onContinue callback.
	 *
	 * Same logic as the old XML fragment's showRecoveryPhraseInline() —
	 * just without the FrameLayout/ComposeView manipulation.
	 */
	@androidx.compose.runtime.Composable
	private fun showRecoveryPhraseInline() {
		val notificationPermissionLauncher =
			rememberLauncherForActivityResult(
				ActivityResultContracts.RequestPermission(),
			) { granted ->
				Timber.i("POST_NOTIFICATIONS permission granted=$granted (register branch, inline)")
				navigateToGalleryOrPop()
			}

		RecoveryPhraseSetupScreen(
			onContinue = {
				val needsRequest =
					if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
						ContextCompat.checkSelfPermission(
							requireContext(),
							Manifest.permission.POST_NOTIFICATIONS,
						) != PackageManager.PERMISSION_GRANTED
					} else {
						false
					}
				if (needsRequest) {
					notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
				} else {
					navigateToGalleryOrPop()
				}
			},
		)
	}

	/**
	 * Navigate to the gallery (or pop back to Settings if that's where we came from).
	 */
	private fun navigateToGalleryOrPop() {
		val navController = findNavController()
		if (navController.previousBackStackEntry?.destination?.id == R.id.settingsFragment) {
			navController.popBackStack()
		} else {
			navigateToGallery(navController)
		}
	}
}
