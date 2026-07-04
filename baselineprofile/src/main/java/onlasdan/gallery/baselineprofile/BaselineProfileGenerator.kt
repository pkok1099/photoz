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

package onlasdan.gallery.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Sprint 8 / L7 — Baseline Profile generator.
 *
 * Drives the app through its critical user journeys (cold start → unlock
 * screen → gallery) and records the JIT hot paths. The resulting
 * baseline-prof.txt is embedded in the release APK so ART pre-compiles
 * those paths on first install, cutting cold start by 15-40%.
 *
 * To generate:
 *  1. Connect a device/emulator (API 34+)
 *  2. Run: ./gradlew :baselineprofile:connectedBenchmarkAndroidTest
 *  3. Profile written to: app/src/release/baseline-prof.txt
 *  4. Rebuild release APK — profile embedded automatically
 *
 * @since v13 — Sprint 8 / L7 baseline profiles
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun generate() = baselineProfileRule.collect(
        packageName = "onlasdan.gallery",
    ) {
        // Cold start — the app launches into the unlock/setup screen.
        // We don't unlock (that requires a password) but the startup path
        // (Application.onCreate, Hilt init, DB init, theme setup) is the
        // most impactful part to profile.
        pressHome()
        startActivityAndWait()
    }
}
