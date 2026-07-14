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

package onlasdan.gallery.recoverymenu

import android.content.Intent
import androidx.activity.ComponentActivity
import onlasdan.gallery.main.ui.MainActivity
import javax.inject.Inject

class RecoveryMenuNavigator
	@Inject
	constructor() {
		/**
		 * F-PERF-003 (v1.0.2): widened parameter from `AppCompatActivity` to [ComponentActivity]
		 * so callers don't need AppCompat — RecoveryMenuActivity now extends ComponentActivity.
		 * Both `startActivity` and `finish` are defined on ComponentActivity / Activity.
		 */
		fun navigate(
			navigationEvent: NavigationEvent,
			activity: ComponentActivity,
		) {
			when (navigationEvent) {
				NavigationEvent.OpenPhotoZ -> navigateOpenPhotoZ(activity)
				NavigationEvent.AfterResetHideApp -> navigateAfterResetHideApp(activity)
			}
		}

		private fun navigateAfterResetHideApp(activity: ComponentActivity) {
			activity.finish()
		}

		private fun navigateOpenPhotoZ(activity: ComponentActivity) {
			val intent =
				Intent(activity, MainActivity::class.java).apply {
					flags = Intent.FLAG_ACTIVITY_NEW_TASK
				}
			activity.startActivity(intent)
			activity.finish()
		}

		sealed class NavigationEvent {
			object OpenPhotoZ : NavigationEvent()

			object AfterResetHideApp : NavigationEvent()
		}
	}
