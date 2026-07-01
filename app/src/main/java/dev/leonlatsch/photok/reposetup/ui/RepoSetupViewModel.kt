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

package dev.leonlatsch.photok.reposetup.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.leonlatsch.photok.R
import dev.leonlatsch.photok.settings.data.Config
import dev.leonlatsch.photok.sync.rclone.RcloneConfigManager
import dev.leonlatsch.photok.sync.rclone.RepoManager
import dev.leonlatsch.photok.uicomponnets.Dialogs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * State of the mandatory repo setup flow.
 *
 * @since PR1 sync — mandatory repo setup
 */
sealed interface RepoSetupState {
    /** No config imported yet — user needs to pick an rclone.conf file. */
    object NeedsConfig : RepoSetupState

    /** Config imported but no remote chosen — user needs to pick a remote. */
    data class NeedsRemoteChoice(val remotes: List<RcloneConfigManager.RemoteInfo>) : RepoSetupState

    /** Remote chosen — checking remote reachability + detecting register vs login. */
    object Checking : RepoSetupState

    /** Remote reachable, no repo exists — user needs to confirm register. */
    object ReadyToRegister : RepoSetupState

    /** Remote reachable, repo exists — connecting (read-only confirmation). */
    object Connecting : RepoSetupState

    /**
     * Repo session confirmed — restoring thumbnails from the remote backup before
     * completing the login flow. Shows a brief progress indicator.
     *
     * @since PR4 sync — restore thumbnails on login
     */
    object RestoringBackup : RepoSetupState

    /** Repo session confirmed — gallery can be unlocked. */
    object Completed : RepoSetupState

    /** Error at any stage. [message] is user-facing. */
    data class Error(val message: String) : RepoSetupState
}

@HiltViewModel
class RepoSetupViewModel @Inject constructor(
    private val app: Application,
    private val config: Config,
    private val rcloneConfigManager: RcloneConfigManager,
    private val repoManager: RepoManager,
) : ViewModel() {

    private val _state = MutableStateFlow<RepoSetupState>(RepoSetupState.NeedsConfig)
    val state: StateFlow<RepoSetupState> = _state.asStateFlow()

    init {
        // Check if config + remote are already set (e.g. user configured in Settings
        // but repo wasn't confirmed yet). If so, skip ahead to the checking stage.
        viewModelScope.launch {
            val status = rcloneConfigManager.currentStatus()
            when (status) {
                is RcloneConfigManager.Status.Configured -> {
                    _state.value = RepoSetupState.Checking
                    checkRemoteAndDetectRepo()
                }
                is RcloneConfigManager.Status.AwaitingRemoteChoice -> {
                    _state.value = RepoSetupState.NeedsRemoteChoice(status.availableRemotes)
                }
                else -> {
                    _state.value = RepoSetupState.NeedsConfig
                }
            }
        }
    }

    /**
     * Import rclone.conf from SAF picker. After import, show remote picker.
     */
    fun importConfig(uri: Uri) {
        viewModelScope.launch {
            _state.value = RepoSetupState.Checking
            val result = rcloneConfigManager.import(uri)
            result.fold(
                onSuccess = {
                    val remotes = rcloneConfigManager.availableRemotes()
                    if (remotes.size == 1) {
                        // Auto-select the only remote
                        chooseRemote(remotes.first().name)
                    } else {
                        _state.value = RepoSetupState.NeedsRemoteChoice(remotes)
                    }
                },
                onFailure = { e ->
                    val reason = rcloneConfigManager.toInvalidReason(e)
                    val msg = when (reason) {
                        RcloneConfigManager.Status.InvalidReason.NO_SECTIONS ->
                            app.getString(R.string.settings_cloud_sync_invalid_no_sections)
                        RcloneConfigManager.Status.InvalidReason.UNREADABLE ->
                            app.getString(R.string.sync_settings_import_io_error)
                    }
                    _state.value = RepoSetupState.Error(msg)
                }
            )
        }
    }

    /**
     * User picked a remote. Persist it, then check reachability + detect register vs login.
     */
    fun chooseRemote(name: String) {
        viewModelScope.launch {
            rcloneConfigManager.chooseRemote(name)
            _state.value = RepoSetupState.Checking
            checkRemoteAndDetectRepo()
        }
    }

    /**
     * Check remote reachability + detect whether a repo already exists (register vs login).
     */
    private suspend fun checkRemoteAndDetectRepo() {
        val remote = config.syncChosenRemote
        if (remote.isNullOrBlank()) {
            _state.value = RepoSetupState.Error("No remote chosen")
            return
        }

        // Detect repo state via independent lsjson
        when (val repoState = repoManager.detectRepo()) {
            is RepoManager.RepoState.NOT_INITIALIZED -> {
                _state.value = RepoSetupState.ReadyToRegister
            }
            is RepoManager.RepoState.LOGGED_IN -> {
                // Repo exists — connect (read-only, no re-write).
                // NOTE: this is "connect to remote", NOT "log into the app".
                // The app's vault unlock (PIN/password) is a separate, independent
                // gate. This state confirms the remote repo session only.
                _state.value = RepoSetupState.Connecting
                val loginResult = repoManager.loginRepo(repoState.marker)
                if (loginResult.isSuccess) {
                    // @since PR4 sync — restore thumbnails from backup before completing.
                    // Show a brief "Restoring backup…" state so the user knows why
                    // login is taking longer than a no-op connect. Restore failure
                    // MUST NOT block login — the user can still get into the gallery;
                    // photos will be re-uploaded on the next sync cycle.
                    _state.value = RepoSetupState.RestoringBackup
                    val restored = try {
                        repoManager.restoreThumbnailsAfterLogin()
                    } catch (e: Exception) {
                        Timber.w(e, "restoreThumbnailsAfterLogin failed; continuing to Completed")
                        0
                    }
                    Timber.i("Repo login complete; restored $restored thumbnails from backup")
                    _state.value = RepoSetupState.Completed
                } else {
                    _state.value = RepoSetupState.Error(
                        "Connection failed: ${loginResult.exceptionOrNull()?.message}"
                    )
                }
            }
            is RepoManager.RepoState.ERROR -> {
                _state.value = RepoSetupState.Error(repoState.message)
            }
        }
    }

    /**
     * User confirmed register. Write marker, verify, complete.
     */
    fun confirmRegister() {
        viewModelScope.launch {
            _state.value = RepoSetupState.Checking
            val result = repoManager.registerRepo()
            result.fold(
                onSuccess = {
                    _state.value = RepoSetupState.Completed
                },
                onFailure = { e ->
                    _state.value = RepoSetupState.Error(
                        "Failed to register repo: ${e.message}"
                    )
                }
            )
        }
    }

    /**
     * Dismiss error, return to initial state.
     */
    fun dismissError() {
        viewModelScope.launch {
            val status = rcloneConfigManager.currentStatus()
            _state.value = when (status) {
                is RcloneConfigManager.Status.Configured -> RepoSetupState.Checking
                is RcloneConfigManager.Status.AwaitingRemoteChoice ->
                    RepoSetupState.NeedsRemoteChoice(status.availableRemotes)
                else -> RepoSetupState.NeedsConfig
            }
            if (_state.value == RepoSetupState.Checking) {
                checkRemoteAndDetectRepo()
            }
        }
    }
}
