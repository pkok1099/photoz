/*
 *   Copyright 2020-2026 PhotoZ
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

package onlasdan.gallery.telemetry.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import onlasdan.gallery.settings.data.Config
import onlasdan.gallery.telemetry.domain.TELEMETRY_ENABLED_BY_DEFAULT
import onlasdan.gallery.telemetry.domain.TelemetryService
import javax.inject.Inject

@HiltViewModel
class TelemetryViewModel
	@Inject
	constructor(
		private val config: Config,
		private val telemetryService: TelemetryService,
	) : ViewModel() {
		val enabled =
			config.valuesFlow
				.map {
					it[Config.TELEMETRY_ENABLED] as? Boolean ?: TELEMETRY_ENABLED_BY_DEFAULT
				}.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), TELEMETRY_ENABLED_BY_DEFAULT)

		fun updateTelemetryEnabled(enabled: Boolean) {
			config.telemetryEnabled = enabled
			telemetryService.setup()
		}
	}
