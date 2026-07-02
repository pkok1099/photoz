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

package dev.leonlatsch.photok.setup.ui

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
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import dev.leonlatsch.photok.R
import dev.leonlatsch.photok.gallery.ui.navigation.NavigateToGallery
import dev.leonlatsch.photok.ui.theme.AppTheme
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
                    val notificationPermissionLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestPermission()
                    ) { granted ->
                        Timber.i("POST_NOTIFICATIONS permission granted=$granted")
                        // Regardless of granted/denied, proceed to gallery — uploads work
                        // either way (notification is UX-only, not a functional dependency).
                        navigateToGalleryOrPop()
                    }

                    // Check if permission is already granted (or not needed on old Android).
                    // If already granted, skip the system dialog and go straight to gallery.
                    // If not granted, launch the system permission request.
                    LaunchedEffect(Unit) {
                        val needsRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            ContextCompat.checkSelfPermission(
                                requireContext(),
                                Manifest.permission.POST_NOTIFICATIONS
                            ) != PackageManager.PERMISSION_GRANTED
                        } else {
                            false // API < 33: no runtime permission needed
                        }
                        if (needsRequest) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            navigateToGalleryOrPop()
                        }
                    }

                    // While the permission dialog is showing, render the recovery phrase screen
                    // underneath (the dialog is system-level, overlaying whatever we draw).
                    // The LaunchedEffect above handles navigation after the permission result.
                    // The onContinue callback is a fallback for manual continue.
                    RecoveryPhraseSetupScreen(
                        onContinue = {
                            val needsRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                ContextCompat.checkSelfPermission(
                                    requireContext(),
                                    Manifest.permission.POST_NOTIFICATIONS
                                ) != PackageManager.PERMISSION_GRANTED
                            } else {
                                false
                            }
                            if (needsRequest) {
                                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
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
