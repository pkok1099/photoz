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

package onlasdan.gallery.main.ui

import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import androidx.core.view.isVisible
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavOptions
import androidx.navigation.findNavController
import com.google.android.material.color.DynamicColors
import dagger.hilt.android.AndroidEntryPoint
import onlasdan.gallery.R
import onlasdan.gallery.databinding.ActivityMainBinding
import onlasdan.gallery.main.ui.navigation.MainMenu
import onlasdan.gallery.settings.data.Config
import onlasdan.gallery.ui.theme.AppTheme
import onlasdan.gallery.uicomponnets.bindings.BindableActivity
import javax.inject.Inject

val FragmentsWithMenu = listOf(R.id.galleryFragment, R.id.albumsFragment, R.id.settingsFragment, R.id.albumDetailFragment)

/**
 * The main Activity.
 * Holds all fragments and initializes toolbar, menu, etc.
 *
 * @since 1.0.0
 * @author PhotoZ
 */
@AndroidEntryPoint
class MainActivity : BindableActivity<ActivityMainBinding>(R.layout.activity_main) {

    private val viewModel: MainViewModel by viewModels()

    @Inject
    override lateinit var config: Config

    var onOrientationChanged: (Int) -> Unit = {} // Init empty

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // ─── Sprint 1 / P4: Dynamic Color (Material You) ─────────────────────
        // Overlays wallpaper-derived colors onto the AppTheme (which now extends
        // Theme.Material3.DayNight.NoActionBar.Bridge). On Android 12+ (always,
        // since minSdk=35) this pulls primary/secondary/tertiary colors from the
        // user's wallpaper, giving PhotoZ a personalized look matching the system.
        // Devices without dynamic color support (e.g. emulator with default
        // wallpaper) fall back to the colorPrimary/colorAccent defined in colors.xml.
        // Safe to call unconditionally — the API is a no-op below API 31.
        DynamicColors.applyToActivityIfAvailable(this)
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        dispatchIntent()

        findNavController(R.id.mainNavHostFragment).let { navController ->
            navController.addOnDestinationChangedListener { controller, destination, arguments ->
                val showMenu = FragmentsWithMenu.contains(destination.id)
                binding.mainMenuComposeContainer.isVisible = showMenu

                WindowCompat.getInsetsController(
                    window, window.decorView
                ).isAppearanceLightStatusBars = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) != Configuration.UI_MODE_NIGHT_YES

                viewModel.onDestinationChanged(destination.id)
            }

        }
    }

    private fun dispatchIntent() {
        when (intent.action) {
            Intent.ACTION_SEND -> intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { uri ->
                viewModel.addUriToSharedUriStore(uri)
            }

            Intent.ACTION_SEND_MULTIPLE ->
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.forEach { uri ->
                    viewModel.addUriToSharedUriStore(uri)
                }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        onOrientationChanged(newConfig.orientation)
    }

    override fun bind(binding: ActivityMainBinding) {
        super.bind(binding)
        binding.context = this

        binding.mainMenuComposeContainer.setContent {
            val uiState by viewModel.mainMenuUiState.collectAsStateWithLifecycle()

            AppTheme {
                MainMenu(uiState) {
                    val navController = findNavController(R.id.mainNavHostFragment)
                    if (navController.currentDestination?.id != it) {
                        navController.navigate(
                            resId = it,
                            args = null,
                            navOptions = NavOptions.Builder()
                                .setEnterAnim(android.R.anim.fade_in)
                                .setExitAnim(android.R.anim.fade_out)
                                .build()
                        )
                    }
                }
            }
        }
    }
}