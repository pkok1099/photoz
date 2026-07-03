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
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.ContextCompat
import androidx.core.view.children
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import onlasdan.gallery.BR
import onlasdan.gallery.BuildConfig
import onlasdan.gallery.R
import onlasdan.gallery.databinding.FragmentSetupBinding
import onlasdan.gallery.encryption.domain.PasswordStrength
import onlasdan.gallery.encryption.domain.StrongPasswordPolicy
import onlasdan.gallery.gallery.ui.navigation.NavigateToGallery
import onlasdan.gallery.other.extensions.empty
import onlasdan.gallery.other.extensions.finishOnBackWhileStarted
import onlasdan.gallery.other.extensions.hide
import onlasdan.gallery.other.extensions.show
import onlasdan.gallery.other.systemBarsPadding
import onlasdan.gallery.uicomponnets.Dialogs
import onlasdan.gallery.uicomponnets.base.hideKeyboard
import onlasdan.gallery.uicomponnets.bindings.BindableFragment
import onlasdan.gallery.ui.theme.AppTheme
import timber.log.Timber
import javax.inject.Inject

/**
 * Fragment for the setup.
 *
 * @since 1.0.0
 * @author PhotoZ
 */
@AndroidEntryPoint
class SetupFragment : BindableFragment<FragmentSetupBinding>(R.layout.fragment_setup) {

    private val viewModel: SetupViewModel by viewModels()

    @Inject
    lateinit var navigateToGallery: NavigateToGallery

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.systemBarsPadding()
        finishOnBackWhileStarted()

        if (BuildConfig.DEBUG) {
            // ─── Sprint 1 / P7: DEBUG auto-fill must pass the new strong policy ──
            // The old "abc123" (6 chars, 2 classes) is rejected by the new
            // StrongPasswordPolicy (too short + only 2 classes). Use a 12-char
            // passphrase with all 4 classes so debug builds still auto-fill
            // and the developer can tap "Create" without retyping.
            // DO NOT ship this in release — it's gated by BuildConfig.DEBUG.
            viewModel.password = "Debug123!pass"
            viewModel.confirmPassword = "Debug123!pass"
        }

        viewModel.addOnPropertyChange<String>(BR.password) {
            // ─── Sprint 1 / P7: Strong Password Enforcement ────────────────────
            // The strength indicator now reflects [StrongPasswordPolicy.strength],
            // which considers length, character class diversity, PIN detection,
            // and common-password blacklist — not just length buckets as before.
            //
            // The "Create" button is gated by [StrongPasswordPolicy.isAcceptable]
            // via [viewModel.validatePassword] (called from [enableOrDisableSetup]).
            // The user sees the strength meter change AND a contextual reason
            // ("PINs not allowed" / "Minimum 8 characters" / "Common password")
            // when their input falls below the bar.
            if (it.isNotEmpty()) {
                val strength = StrongPasswordPolicy.strength(it)
                val (labelRes, colorRes) = when (strength) {
                    PasswordStrength.EMPTY -> R.string.setup_password_strength_weak to R.color.darkRed
                    PasswordStrength.TOO_SHORT -> R.string.setup_password_too_short to R.color.darkRed
                    PasswordStrength.PIN_REJECTED -> R.string.setup_password_pin_rejected to R.color.darkRed
                    PasswordStrength.COMMON -> R.string.setup_password_common to R.color.darkRed
                    PasswordStrength.WEAK -> R.string.setup_password_strength_weak to R.color.darkRed
                    PasswordStrength.MODERATE -> R.string.setup_password_strength_moderate to R.color.darkYellow
                    PasswordStrength.STRONG -> R.string.setup_password_strength_strong to R.color.darkGreen
                }
                binding.setupPasswordStrengthValue.setTextColor(requireContext().getColor(colorRes))
                binding.setupPasswordStrengthValue.text = getString(labelRes)
                binding.setupPasswordStrengthLayout.show()
            } else {
                binding.setupPasswordStrengthLayout.hide()
            }

            if (viewModel.validatePassword()) {
                binding.setupConfirmPasswordEditText.show()
            } else {
                binding.setupConfirmPasswordEditText.setTextValue(String.empty)
                binding.setupConfirmPasswordEditText.hide()
            }

            enableOrDisableSetup()
        }

        viewModel.addOnPropertyChange<String>(BR.confirmPassword) {
            enableOrDisableSetup()
        }

        viewModel.addOnPropertyChange<SetupState>(BR.setupState) {
            when (it) {
                SetupState.LOADING -> binding.loadingOverlay.show()
                SetupState.SETUP -> binding.loadingOverlay.hide()
                SetupState.FINISHED -> finishSetup()
                SetupState.SHOW_RECOVERY_PHRASE -> {
                    binding.loadingOverlay.hide()
                    activity?.hideKeyboard()
                    // @since Item 4 — show the recovery phrase INLINE on the
                    // same screen, instead of navigating to a separate
                    // RecoveryPhraseSetupFragment. The
                    // [RecoveryPhraseSetupScreen] composable is reused as-is
                    // (it has its own Scaffold + bottomBar with
                    // share/download/copy/QR + Continue). No fragment
                    // navigation means the user stays on one screen for the
                    // entire password + recovery-phrase setup flow.
                    showRecoveryPhraseInline()
                }
            }
        }
    }

    /**
     * Replace the password-setup content with an inline [RecoveryPhraseSetupScreen]
     * hosted in a [ComposeView] added as a sibling of the existing LinearLayout.
     *
     * The existing LinearLayout (password fields + setup button + loading overlay)
     * is hidden via [View.GONE]; it's not removed because the data-binding machinery
     * still references its children. The ComposeView is added to the root FrameLayout
     * with [FrameLayout.LayoutParams.MATCH_PARENT] so it fills the screen.
     *
     * The [RecoveryPhraseSetupScreen]'s `onContinue` callback is wired to the same
     * POST_NOTIFICATIONS permission + gallery-navigation flow that the standalone
     * [RecoveryPhraseSetupFragment] used — so the user experience is identical, just
     * without the extra fragment navigation.
     *
     * @since Item 4 — unify register screen (phrase + password on one screen)
     */
    private fun showRecoveryPhraseInline() {
        val container = binding.root as? FrameLayout ?: return
        // Hide the existing password-setup LinearLayout (the first child of
        // the FrameLayout root). The loadingOverlay include is a separate
        // sibling and remains in the tree but is hidden by [hide()] above.
        val existingContent = container.getChildAt(0) as? LinearLayout
        existingContent?.visibility = View.GONE

        // Avoid adding a duplicate ComposeView if the user re-triggers this
        // state (defensive — in practice the transition is one-shot).
        val existingCompose = container.children.firstOrNull { it is ComposeView }
        if (existingCompose != null) {
            existingCompose.visibility = View.VISIBLE
            return
        }

        val composeView = ComposeView(requireContext()).apply {
            setContent {
                AppTheme {
                    // ─── POST_NOTIFICATIONS permission launcher (register branch) ──
                    // Same pattern as RecoveryPhraseSetupFragment: the launcher
                    // must be registered in a @Composable context. Fires when
                    // the user taps Continue on the recovery-phrase view.
                    val notificationPermissionLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestPermission()
                    ) { granted ->
                        android.util.Log.e(
                            "RcloneDiag",
                            "POST_NOTIFICATIONS: result callback fired — granted=$granted (register branch, inline in SetupFragment)"
                        )
                        Timber.i("POST_NOTIFICATIONS permission granted=$granted (register branch, inline)")
                        navigateToGalleryOrPop()
                    }

                    RecoveryPhraseSetupScreen(
                        onContinue = {
                            // Same logic as RecoveryPhraseSetupFragment.onContinue:
                            // request POST_NOTIFICATIONS if needed, then navigate.
                            val needsRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                ContextCompat.checkSelfPermission(
                                    requireContext(),
                                    Manifest.permission.POST_NOTIFICATIONS
                                ) != PackageManager.PERMISSION_GRANTED
                            } else {
                                false
                            }
                            if (needsRequest) {
                                android.util.Log.e(
                                    "RcloneDiag",
                                    "POST_NOTIFICATIONS: launching permission request (register branch, inline onContinue) — user tapped Continue"
                                )
                                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                android.util.Log.e(
                                    "RcloneDiag",
                                    "POST_NOTIFICATIONS: launch() returned (register branch, inline onContinue) — awaiting result callback"
                                )
                            } else {
                                android.util.Log.e(
                                    "RcloneDiag",
                                    "POST_NOTIFICATIONS: already granted or not needed (SDK < 33) — navigating to gallery (register branch, inline onContinue)"
                                )
                                navigateToGalleryOrPop()
                            }
                        }
                    )
                }
            }
        }
        container.addView(
            composeView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        )
    }

    /**
     * Navigate to the gallery (or pop back to Settings if that's where we came from).
     *
     * Mirrors the standalone [RecoveryPhraseSetupFragment.navigateToGalleryOrPop]
     * so the inline recovery-phrase view behaves identically to the standalone one.
     *
     * @since Item 4 — unify register screen (phrase + password on one screen)
     */
    private fun navigateToGalleryOrPop() {
        val navController = findNavController()
        if (navController.previousBackStackEntry?.destination?.id == R.id.settingsFragment) {
            navController.popBackStack()
        } else {
            navigateToGallery(navController)
        }
    }

    private fun finishSetup() {
        try {
            val activity = activity
            requireNotNull(activity)

            activity.hideKeyboard()
            binding.loadingOverlay.hide()

            navigateToGallery(findNavController())
        } catch (e: Exception) {
            Timber.e(e)
            Dialogs.showLongToast(
                requireContext(),
                getString(R.string.common_error)
            )
        }
    }

    private fun enableOrDisableSetup() {
        if (!viewModel.passwordsEqual()
            && binding.setupConfirmPasswordEditText.isVisible
        ) {
            binding.setupPasswordMatchWarningTextView.show()
            binding.setupButton.isEnabled = false
        } else {
            binding.setupPasswordMatchWarningTextView.hide()
            if (viewModel.validateBothPasswords()) {
                binding.setupButton.isEnabled = true
            }
        }
    }

    override fun bind(binding: FragmentSetupBinding) {
        super.bind(binding)
        binding.viewModel = viewModel
    }
}