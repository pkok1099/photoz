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

package onlasdan.gallery.uicomponnets.base

import android.app.Activity
import android.content.Context.INPUT_METHOD_SERVICE
import android.os.Bundle
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatActivity
import onlasdan.gallery.R
import onlasdan.gallery.settings.data.Config

/**
 * Base for all activities.
 *
 * @since 1.0.0
 * @author PhotoZ
 */
abstract class BaseActivity : AppCompatActivity() {

    /**
     * Sprint 10+ / TODO #8 — AAPM (Advanced Protection Mode) awareness.
     * When AAPM is on, FLAG_SECURE is forced regardless of the user's
     * "Allow Screenshots" setting.
     */
    @javax.inject.Inject
    lateinit var aapmAwareness: onlasdan.gallery.security.AdvancedProtectionModeAwareness

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // FLAG_SECURE: prevents screenshots + recent apps thumbnail showing
        // plaintext vault content. Applied when:
        // 1. User has "Allow Screenshots" OFF (default), OR
        // 2. AAPM (Advanced Protection Mode) is enabled on the device
        if (!config.securityAllowScreenshots || aapmAwareness.shouldForceFlagSecure()) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-check AAPM on resume — user may have enabled it while app was backgrounded.
        if (aapmAwareness.shouldForceFlagSecure()) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    /**
     * Abstract [Config], must be injected in implementations.
     */
    abstract var config: Config
}

fun Activity.hideKeyboard() {
    val inputManager = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
    inputManager.hideSoftInputFromWindow(currentFocus?.windowToken, 0)
}