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

package onlasdan.gallery.other.extensions

import android.view.Window
import android.view.WindowInsets
import android.view.WindowManager

/**
 * Compat method for adding a [visibilityListener] to the system ui.
 *
 * @since PR1: minSdk bumped to 35 — `Build.VERSION.SDK_INT >= R` guard removed (always true).
 */
fun Window.addSystemUIVisibilityListener(visibilityListener: (Boolean) -> Unit) {
	decorView.setOnApplyWindowInsetsListener { v, insets ->
		val suppliedInsets = v.onApplyWindowInsets(insets)
		visibilityListener(suppliedInsets.isVisible(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars()))
		suppliedInsets
	}
}

/**
 * Get the device screen size.
 *
 * @since PR1: minSdk bumped to 35 — `Build.VERSION.SDK_INT >= R` guard removed (always true).
 */
fun WindowManager.getCompatScreenSize(): Pair<Int, Int> {
	val bounds = this.currentWindowMetrics.bounds
	return Pair(bounds.width(), bounds.height())
}
