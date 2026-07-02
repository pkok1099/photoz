/*
 *   Copyright 2020–2026 Leon Latsch
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
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import onlasdan.gallery.R
import onlasdan.gallery.gallery.ui.navigation.NavigateToGallery
import onlasdan.gallery.ui.theme.AppTheme
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class RecoveryPhraseSetupFragment : Fragment() {

    @Inject
    lateinit var navigateToGallery: NavigateToGallery

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        return ComposeView(requireContext()).apply {
            setContent {
                AppTheme {
                    // ─── Notification permission launcher ───────────────────────────
                    // Since Android 13 (API 33), POST_NOTIFICATIONS is a runtime permission.
                    // Without it, NotificationManager.notify() fails silently — no crash, no
                    // visible notification. This was the root cause of "notifications never
                    // appear despite the code existing" — the permission was declared in the
                    // manifest but never requested at runtime in the normal onboarding flow
                    // (only in the legacy encryption-migration flow).
                    //
                    // We request it here (in RecoveryPhraseSetupFragment, the last onboarding
                    // step before the gallery) because:
                    // 1. The user has just set up their vault + recovery phrase — they're in
                    //    a "completing setup" mindset and more likely to grant.
                    // 2. This is the last point before the first photo import could trigger
                    //    an upload, so the notification will work from the very first sync.
                    // 3. Denial is non-blocking — uploads still work via WorkManager, just
                    //    without visible progress. We don't gate the gallery on this.
                    //
                    // ─── Item 2 fix: removed LaunchedEffect(Unit) ────────────────────
                    // The previous implementation fired the permission request from a
                    // `LaunchedEffect(Unit)` at fragment creation — BEFORE the user had a
                    // chance to read their recovery phrase. If permission was already granted,
                    // `navigateToGalleryOrPop()` fired immediately and the user never saw
                    // their phrase at all. If not granted, the system dialog appeared over
                    // an unread screen.
                    //
                    // Now: the permission request is fired ONLY when the user taps "Continue"
                    // (the `onContinue` callback below). The user sees their phrase first,
                    // taps Continue, then the permission dialog appears. After the result
                    // (granted or denied), we navigate to the gallery — uploads work either
                    // way (notification is UX-only, not a functional dependency).
                    val notificationPermissionLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestPermission()
                    ) { granted ->
                        // RcloneDiag logging (Item 2) — confirms the result callback fires
                        // and tells us whether the user granted or denied, so we can
                        // distinguish "permission dialog never shown" from "user denied".
                        android.util.Log.e("RcloneDiag",
                            "POST_NOTIFICATIONS: result callback fired — granted=$granted (register branch, RecoveryPhraseSetupFragment)")
                        Timber.i("POST_NOTIFICATIONS permission granted=$granted")
                        // Regardless of granted/denied, proceed to gallery — uploads work
                        // either way (notification is UX-only, not a functional dependency).
                        navigateToGalleryOrPop()
                    }

                    // While the permission dialog is showing, render the recovery phrase screen
                    // underneath (the dialog is system-level, overlaying whatever we draw).
                    // The onContinue callback handles the permission request + navigation
                    // after the user has had a chance to read & save their phrase.
                    RecoveryPhraseSetupScreen(
                        onContinue = {
                            // Item 2: fire the permission request HERE (on user tap), not
                            // in a LaunchedEffect at fragment creation. This guarantees the
                            // user has seen their recovery phrase before any system dialog
                            // appears, and that we don't navigate away before they've had a
                            // chance to read it.
                            val needsRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                ContextCompat.checkSelfPermission(
                                    requireContext(),
                                    Manifest.permission.POST_NOTIFICATIONS
                                ) != PackageManager.PERMISSION_GRANTED
                            } else {
                                false
                            }
                            if (needsRequest) {
                                android.util.Log.e("RcloneDiag",
                                    "POST_NOTIFICATIONS: launching permission request (register branch, onContinue) — user tapped Continue")
                                // RcloneDiag logging BEFORE launch — confirms we reached
                                // this point and the launcher is about to fire.
                                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                // RcloneDiag logging AFTER launch — confirms launch() returned
                                // without throwing. (The result callback fires asynchronously
                                // later, with its own log line above.)
                                android.util.Log.e("RcloneDiag",
                                    "POST_NOTIFICATIONS: launch() returned (register branch, onContinue) — awaiting result callback")
                            } else {
                                android.util.Log.e("RcloneDiag",
                                    "POST_NOTIFICATIONS: already granted or not needed (SDK < 33) — navigating to gallery (register branch, onContinue)")
                                navigateToGalleryOrPop()
                            }
                        }
                    )
                }
            }
        }
    }

    private fun navigateToGalleryOrPop() {
        val navController = findNavController()
        if (navController.previousBackStackEntry?.destination?.id == R.id.settingsFragment) {
            navController.popBackStack()
        } else {
            navigateToGallery(navController)
        }
    }
}
