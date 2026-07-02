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

package onlasdan.gallery.imageviewer.ui

import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect

/**
 * System bars controller for the image viewer.
 *
 * @since PR1: minSdk bumped to 35 — all `Build.VERSION.SDK_INT >= R` guards removed (always true).
 */
@Composable
fun ImageViewerSystemBarsController(
    visible: Boolean
) {
    val activity = LocalActivity.current ?: return
    val window = activity.window

    DisposableEffect(visible) {
        val previousStatusColor = window.statusBarColor
        val previousNavColor = window.navigationBarColor
        val previousAppearance = window.insetsController?.systemBarsAppearance ?: 0

        window.forceLightSystemBarIcons()

        // OEM / edge-to-edge safety net
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT

        if (visible) {
            window.showSystemBars()
        } else {
            window.hideSystemBars()
        }

        onDispose {
            window.showSystemBars()

            // restore colors
            window.statusBarColor = previousStatusColor
            window.navigationBarColor = previousNavColor

            // restore appearance
            window.insetsController?.setSystemBarsAppearance(
                previousAppearance,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                        WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            )
        }
    }
}


fun Window.forceLightSystemBarIcons() {
    insetsController?.setSystemBarsAppearance(
        0,
        WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
    )
}

private fun Window.showSystemBars() {
    insetsController?.show(WindowInsets.Type.systemBars())
}

private fun Window.hideSystemBars() {
    insetsController?.let {
        it.systemBarsBehavior =
            WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        it.hide(WindowInsets.Type.systemBars())
    }
}
