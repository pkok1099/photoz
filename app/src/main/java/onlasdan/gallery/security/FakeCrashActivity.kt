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

package onlasdan.gallery.security

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import onlasdan.gallery.ui.theme.AppTheme

/**
 * Sprint 7 / L2 — Fake Crash Screen.
 *
 * A decoy activity that mimics the Android system "app has stopped" dialog.
 * Shown when the user enters a wrong dial code while stealth mode is on —
 * instead of opening the dialer (which is the existing disguise), this
 * activity makes the app look BROKEN, not hidden. An investigator who
 * triggers it sees "PhotoZ keeps stopping" and moves on, rather than
 * suspecting the phone icon is a disguise.
 *
 * The dialog has two buttons:
 *  - "Close app" — finishes the activity (same as dismissing).
 *  - "Send feedback" — also finishes (no actual feedback is sent).
 *
 * Both buttons close the activity. The user must re-enter the correct dial
 * code to actually open PhotoZ.
 *
 * @since v13 — Sprint 7 / L2 fake crash
 */
class FakeCrashActivity : ComponentActivity() {
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContent {
			AppTheme {
				FakeCrashScreen(
					onClose = { finishAndRemoveTask() },
				)
			}
		}
	}
}

@Composable
private fun FakeCrashScreen(onClose: () -> Unit) {
	Surface(
		modifier = Modifier.fillMaxSize(),
		color = MaterialTheme.colorScheme.surface,
	) {
		Column(
			modifier =
				Modifier
					.fillMaxSize()
					.padding(24.dp),
			horizontalAlignment = Alignment.CenterHorizontally,
			verticalArrangement = Arrangement.Center,
		) {
			Text(
				text = "PhotoZ keeps stopping",
				style = MaterialTheme.typography.headlineSmall,
				textAlign = TextAlign.Center,
				color = MaterialTheme.colorScheme.onSurface,
			)
			Text(
				text = "The app has stopped unexpectedly. Try again.",
				style = MaterialTheme.typography.bodyMedium,
				textAlign = TextAlign.Center,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				modifier = Modifier.padding(top = 8.dp),
			)
			Column(
				modifier = Modifier.padding(top = 24.dp),
				verticalArrangement = Arrangement.spacedBy(8.dp),
			) {
				TextButton(onClick = onClose) {
					Text("Close app")
				}
				TextButton(onClick = onClose) {
					Text("Send feedback")
				}
			}
		}
	}
}
