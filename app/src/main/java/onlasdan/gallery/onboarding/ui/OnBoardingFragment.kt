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
import android.view.View
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.viewpager.widget.ViewPager
import dagger.hilt.android.AndroidEntryPoint
import onlasdan.gallery.R
import onlasdan.gallery.databinding.FragmentOnboardingBinding
import onlasdan.gallery.other.extensions.finishOnBackWhileStarted
import onlasdan.gallery.other.systemBarsPadding
import onlasdan.gallery.settings.data.Config
import onlasdan.gallery.telemetry.domain.Signal
import onlasdan.gallery.telemetry.domain.TelemetryService
import onlasdan.gallery.uicomponnets.ViewPagerAdapter
import onlasdan.gallery.uicomponnets.bindings.BindableFragment
import javax.inject.Inject

/**
 * On boarding fragment.
 * Used as a "tutorial".
 *
 * @since 1.0.0
 * @author PhotoZ
 */
@AndroidEntryPoint
class OnBoardingFragment :
    BindableFragment<FragmentOnboardingBinding>(R.layout.fragment_onboarding) {

    @Inject
    lateinit var config: Config

    private var isLastPage = false

    @Inject
    lateinit var telemetryService: TelemetryService

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.systemBarsPadding()
        finishOnBackWhileStarted()

        binding.onBoardingDotSelector1.isSelected = true
        binding.onBoardingDotSelector2.isSelected = false
        binding.onBoardingDotSelector3.isSelected = false

        val viewPagerAdapter = ViewPagerAdapter(childFragmentManager)
        viewPagerAdapter.addFragment(Fragment(R.layout.fragment_onboarding_slide_1))
        viewPagerAdapter.addFragment(Fragment(R.layout.fragment_onboarding_slide_2))
        viewPagerAdapter.addFragment(Fragment(R.layout.fragment_onboarding_slide_3))
        binding.onBoardingViewPager.adapter = viewPagerAdapter
        binding.onBoardingViewPager.addOnPageChangeListener(onPageChangeListener)
    }

    private val onPageChangeListener = object : ViewPager.OnPageChangeListener {
        override fun onPageScrolled(
            position: Int,
            positionOffset: Float,
            positionOffsetPixels: Int
        ) {
        }

        override fun onPageSelected(position: Int) {
            binding.onBoardingButton.text = when (position) {
                0 -> {
                    binding.onBoardingDotSelector1.isSelected = true
                    binding.onBoardingDotSelector2.isSelected = false
                    binding.onBoardingDotSelector3.isSelected = false
                    getString(R.string.onboarding_next)
                }
                1 -> {
                    binding.onBoardingDotSelector1.isSelected = false
                    binding.onBoardingDotSelector2.isSelected = true
                    binding.onBoardingDotSelector3.isSelected = false
                    getString(R.string.onboarding_next)
                }
                2 -> {
                    binding.onBoardingDotSelector1.isSelected = false
                    binding.onBoardingDotSelector2.isSelected = false
                    binding.onBoardingDotSelector3.isSelected = true
                    getString(R.string.onboarding_finish)
                }
                else -> getString(R.string.onboarding_next)
            }
            isLastPage = position == 2
        }

        override fun onPageScrollStateChanged(state: Int) {}
    }

    /**
     * Swipe to next slide or finish
     * Called by ui.
     */
    fun buttonClicked() {
        if (isLastPage) {
            telemetryService.signal(Signal.OnboardingFinished)

            finish()
        } else {
            binding.onBoardingViewPager.setCurrentItem(
                binding.onBoardingViewPager.currentItem + 1,
                true
            )
        }
    }

    /**
     * Navigate to repo setup (remote config + repo register/login) and set first start to false.
     *
     * @since PR3 — onboarding now routes to RepoSetupFragment first (remote config before
     * vault password). Repo setup then chains forward to SetupFragment via
     * action_repoSetupFragment_to_setupFragment.
     */
    fun finish() {
        android.util.Log.e("RcloneDiag",
            "OnBoardingFragment.finish: setting systemFirstStart=false (was=${config.systemFirstStart})")
        config.systemFirstStart = false
        android.util.Log.e("RcloneDiag",
            "OnBoardingFragment.finish: systemFirstStart is now ${config.systemFirstStart} " +
                "(verify it persisted — if this reads true, the write failed)")
        findNavController().navigate(R.id.action_onBoardingFragment_to_repoSetupFragment)
    }

    override fun bind(binding: FragmentOnboardingBinding) {
        super.bind(binding)
        binding.context = this
    }
}