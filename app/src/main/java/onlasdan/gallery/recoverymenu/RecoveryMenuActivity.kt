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

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dagger.hilt.android.AndroidEntryPoint
import onlasdan.gallery.R
import onlasdan.gallery.settings.data.Config
import onlasdan.gallery.ui.theme.AppTheme
import javax.inject.Inject

/**
 * F-PERF-003 (UI optimization, v1.0.2): migrated from `BindableActivity<ActivityRecoveryMenuBinding>`
 * (DataBinding) to [AppCompatActivity] + Jetpack Compose.
 *
 * F-HOTFIX-001: extends [AppCompatActivity] (not ComponentActivity) because the activity
 * uses `@style/AppTheme` which extends `Theme.MaterialComponents.DayNight.NoActionBar.Bridge`
 * — an AppCompat theme. AppCompatActivity ensures proper theme inflation.
 *
 * The activity_recovery_menu.xml layout file has been removed. The UI is now defined entirely
 * in Compose below — a column with the app title, subtitle, and two clickable rows for
 * "Open PhotoZ" and "Reset hide-app setting".
 *
 * @since 1.0.0
 * @author PhotoZ
 */
@AndroidEntryPoint
class RecoveryMenuActivity : AppCompatActivity() {
	@Inject
	lateinit var config: Config

	@Inject
	lateinit var navigator: RecoveryMenuNavigator

	private val viewModel: RecoveryMenuViewModel by viewModels()

	override fun onCreate(savedInstanceState: Bundle?) {
		enableEdgeToEdge()
		super.onCreate(savedInstanceState)

		viewModel.navigationEvent.observe(this) { event ->
			navigator.navigate(event, this)
		}

		setContent {
			AppTheme {
				RecoveryMenuScreen(
					onOpenPhotoZ = { viewModel.openPhotoZ() },
					onResetHideApp = { viewModel.resetHidePhotoSetting() },
				)
			}
		}
	}
}

@Composable
private fun RecoveryMenuScreen(
	onOpenPhotoZ: () -> Unit,
	onResetHideApp: () -> Unit,
) {
	Column(
		modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
		verticalArrangement = Arrangement.Top,
		horizontalAlignment = Alignment.CenterHorizontally,
	) {
		Spacer(Modifier.height(20.dp))

		Text(
			text = stringResource(R.string.app_name),
			style = MaterialTheme.typography.headlineLarge,
			textAlign = TextAlign.Center,
			modifier = Modifier.fillMaxWidth(),
		)

		Spacer(Modifier.height(8.dp))

		Text(
			text = stringResource(R.string.recovery_subtitle),
			style = MaterialTheme.typography.bodyLarge,
			textAlign = TextAlign.Center,
			modifier = Modifier.fillMaxWidth(),
		)

		Spacer(Modifier.height(22.dp))

		RecoveryMenuItem(
			text = stringResource(R.string.recovery_menu_item_open_photok),
			onClick = onOpenPhotoZ,
		)

		Spacer(Modifier.height(22.dp))

		RecoveryMenuItem(
			text = stringResource(R.string.recovery_subtitle_reset_hide_app),
			onClick = onResetHideApp,
		)
	}
}

@Composable
private fun RecoveryMenuItem(
	text: String,
	onClick: () -> Unit,
) {
	Row(
		verticalAlignment = Alignment.CenterVertically,
		modifier =
			Modifier
				.fillMaxWidth()
				.clickable(onClick = onClick)
				.padding(horizontal = 34.dp, vertical = 10.dp),
	) {
		Icon(
			painter = painterResource(R.drawable.ic_chevron_right),
			contentDescription = null,
			tint = MaterialTheme.colorScheme.primary,
		)
		Spacer(Modifier.width(8.dp))
		Text(
			text = text,
			style = MaterialTheme.typography.bodyLarge,
			fontWeight = FontWeight.Bold,
		)
	}
}
