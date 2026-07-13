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

package onlasdan.gallery.onboarding.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import onlasdan.gallery.R
import onlasdan.gallery.other.extensions.finishOnBackWhileStarted
import onlasdan.gallery.settings.data.Config
import onlasdan.gallery.telemetry.domain.Signal
import onlasdan.gallery.telemetry.domain.TelemetryService
import onlasdan.gallery.ui.theme.AppTheme
import javax.inject.Inject

/**
 * Sprint 10+ / M1 — OnBoardingFragment migrated to Compose.
 *
 * Previously extended [BindableFragment] with XML layout (fragment_onboarding.xml)
 * + ViewPager + 3 slide Fragment layouts + ViewPagerAdapter. Now extends plain
 * [Fragment] and hosts a [ComposeView] that renders [OnBoardingScreen] with
 * a Compose [HorizontalPager].
 *
 * The 3 slide layouts (fragment_onboarding_slide_1/2/3.xml) are no longer
 * used — each slide is a composable inside OnBoardingScreen.
 *
 * @since v14 — Sprint 10+ / M1 Compose migration
 */
@AndroidEntryPoint
class OnBoardingFragment : Fragment() {
	@Inject
	lateinit var config: Config

	@Inject
	lateinit var telemetryService: TelemetryService

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View =
		ComposeView(requireContext()).apply {
			setContent {
				AppTheme {
					OnBoardingScreen(
						onFinish = { finish() },
					)
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
	 * Navigate to repo setup (remote config + repo register/login) and set
	 * first start to false.
	 */
	fun finish() {
		telemetryService.signal(Signal.OnboardingFinished)

		android.util.Log.e(
			"RcloneDiag",
			"OnBoardingFragment.finish: setting systemFirstStart=false (was=${config.systemFirstStart})",
		)
		config.systemFirstStart = false
		android.util.Log.e(
			"RcloneDiag",
			"OnBoardingFragment.finish: systemFirstStart is now ${config.systemFirstStart}",
		)

		findNavController().navigate(R.id.action_onBoardingFragment_to_repoSetupFragment)
	}
}
