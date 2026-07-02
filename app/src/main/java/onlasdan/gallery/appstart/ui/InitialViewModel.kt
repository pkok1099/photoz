/*
 *   Copyright 2020–2026 Leon Latsch
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

package onlasdan.gallery.appstart.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import onlasdan.gallery.encryption.domain.VaultService
import onlasdan.gallery.encryption.migration.LegacyEncryptionMigrator
import onlasdan.gallery.settings.data.Config
import kotlinx.coroutines.launch
import javax.inject.Inject


/**
 * ViewModel to check the application state.
 * Used by SplashScreen.
 *
 * @since 1.0.0
 * @author Leon Latsch
 */
@HiltViewModel
class InitialViewModel @Inject constructor(
    private val config: Config,
    private val vaultService: VaultService,
    private val legacyEncryptionMigrator: LegacyEncryptionMigrator,
) : ViewModel() {

    /**
     * Check the application state.
     */
    fun checkApplicationState(continueStart: (AppStartState) -> Unit) = viewModelScope.launch {
        // ─── DIAGNOSTIC LOGGING (data-loss bug investigation) ──────────────
        // The app incorrectly re-triggers the first-time setup flow after a
        // force-close + reopen. This logging traces the exact decision point.
        android.util.Log.e("RcloneDiag",
            "checkApplicationState: BEGIN systemFirstStart=${config.systemFirstStart} " +
                "repoConfirmed=${config.repoConfirmed} " +
                "securityLockTimeout=${config.securityLockTimeout}")

        // First start
        if (config.systemFirstStart) {
            android.util.Log.e("RcloneDiag",
                "checkApplicationState: systemFirstStart=true → FIRST_START " +
                    "(this is expected on a genuine first install; if this fires on a " +
                    "SECOND open after force-close, systemFirstStart was not persisted " +
                    "or was reset)")
            continueStart(AppStartState.FIRST_START)
            return@launch
        }

        // Unlock or Setup
        val canUnlock = vaultService.canUnlock()
        val migrationNeeded = legacyEncryptionMigrator.migrationNeeded()
        android.util.Log.e("RcloneDiag",
            "checkApplicationState: systemFirstStart=false → checking canUnlock=$canUnlock " +
                "migrationNeeded=$migrationNeeded")

        val appStartState: AppStartState

        if (canUnlock || migrationNeeded) {
            android.util.Log.e("RcloneDiag",
                "checkApplicationState: → LOCKED (canUnlock=$canUnlock, migrationNeeded=$migrationNeeded)")
            appStartState = AppStartState.LOCKED
        } else {
            android.util.Log.e("RcloneDiag",
                "checkApplicationState: → SETUP (canUnlock=false, migrationNeeded=false) " +
                    "⚠️ This means no VaultProtection(Password) row exists in the Room DB. " +
                    "If this fires on a second open, the DB was cleared or the row was never created.")
            appStartState = AppStartState.SETUP
        }

        continueStart(appStartState)
    }
}