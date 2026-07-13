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
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.IntentCompat
import androidx.core.view.WindowCompat
import androidx.core.view.isVisible
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavOptions
import androidx.navigation.findNavController
import com.google.android.material.color.DynamicColors
import dagger.hilt.android.AndroidEntryPoint
import onlasdan.gallery.R
import onlasdan.gallery.main.ui.navigation.MainMenu
import onlasdan.gallery.settings.data.Config
import onlasdan.gallery.ui.theme.AppTheme
import javax.inject.Inject

val FragmentsWithMenu = listOf(R.id.galleryFragment, R.id.albumsFragment, R.id.settingsFragment, R.id.albumDetailFragment)

/**
 * The main Activity.
 * Holds all fragments and initializes toolbar, menu, etc.
 *
 * F-PERF-002 (UI optimization, v1.0.2): migrated from `BindableActivity<ActivityMainBinding>`
 * (DataBinding) to [AppCompatActivity] + `findViewById`. The ComposeView for the bottom
 * menu is now accessed via `findViewById` instead of generated binding class.
 *
 * F-HOTFIX-001 (P0 crash fix): must extend [AppCompatActivity] (NOT [androidx.activity.ComponentActivity])
 * because `activity_main.xml` uses `FragmentContainerView` with
 * `android:name="androidx.navigation.fragment.NavHostFragment"`. NavHostFragment requires
 * the host Activity to be a [androidx.fragment.app.FragmentActivity] — `AppCompatActivity`
 * extends `FragmentActivity`, but `ComponentActivity` does NOT. Using `ComponentActivity`
 * caused `UnsupportedOperationException` at `setContentView()` on every launch.
 *
 * @since 1.0.0
 * @author PhotoZ
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
	private val viewModel: MainViewModel by viewModels()

	@Inject
	lateinit var config: Config

	var onOrientationChanged: (Int) -> Unit = {} // Init empty

	private lateinit var menuComposeView: ComposeView

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

		setContentView(R.layout.activity_main)
		menuComposeView = findViewById(R.id.mainMenuComposeContainer)

		menuComposeView.setContent {
			val uiState by viewModel.mainMenuUiState.collectAsStateWithLifecycle()

			AppTheme {
				MainMenu(uiState) {
					val navController = findNavController(R.id.mainNavHostFragment)
					if (navController.currentDestination?.id != it) {
						navController.navigate(
							resId = it,
							args = null,
							navOptions =
								NavOptions
									.Builder()
									.setEnterAnim(android.R.anim.fade_in)
									.setExitAnim(android.R.anim.fade_out)
									.build(),
						)
					}
				}
			}
		}
	}

	override fun onPostCreate(savedInstanceState: Bundle?) {
		super.onPostCreate(savedInstanceState)
		dispatchIntent()

		findNavController(R.id.mainNavHostFragment).let { navController ->
			navController.addOnDestinationChangedListener { _, destination, _ ->
				val showMenu = FragmentsWithMenu.contains(destination.id)
				menuComposeView.isVisible = showMenu

				WindowCompat
					.getInsetsController(
						window,
						window.decorView,
					).isAppearanceLightStatusBars =
					(resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) != Configuration.UI_MODE_NIGHT_YES

				viewModel.onDestinationChanged(destination.id)
			}
		}
	}

	private fun dispatchIntent() {
		when (intent.action) {
			Intent.ACTION_SEND ->
				IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { uri ->
					viewModel.addUriToSharedUriStore(uri)
				}

			Intent.ACTION_SEND_MULTIPLE ->
				IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.forEach { uri ->
					viewModel.addUriToSharedUriStore(uri)
				}
		}
	}

	override fun onConfigurationChanged(newConfig: Configuration) {
		super.onConfigurationChanged(newConfig)

		onOrientationChanged(newConfig.orientation)
	}
}
