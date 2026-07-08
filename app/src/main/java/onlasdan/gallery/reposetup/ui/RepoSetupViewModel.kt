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

package onlasdan.gallery.reposetup.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import onlasdan.gallery.R
import onlasdan.gallery.encryption.domain.SessionRepository
import onlasdan.gallery.encryption.domain.VaultService
import onlasdan.gallery.encryption.domain.crypto.PhraseEscrowWrapper
import onlasdan.gallery.encryption.domain.models.UnlockRequest
import onlasdan.gallery.settings.data.Config
import onlasdan.gallery.sync.rclone.RcloneConfigManager
import onlasdan.gallery.sync.rclone.RepoManager
import timber.log.Timber
import javax.inject.Inject

/**
 * Per-remote check status used by the single-page remote picker
 * ([RepoSetupState.NeedsRemoteChoice]).
 *
 * - [UNCHECKED] — user hasn't probed this remote yet (initial state).
 * - [CHECKING] — `hasRepo` is in flight for this remote.
 * - [REPO_FOUND] — marker file exists on the remote; login flow applies.
 * - [NO_REPO] — remote is reachable but no marker; register flow applies.
 * - [ERROR] — remote unreachable / auth failure / network error. The user
 *   can still pick this remote and try Connect — the error is informational.
 *
 * @since Item 3 — single-page remote picker with per-remote status
 */
sealed interface RemoteCheckStatus {
	data object UNCHECKED : RemoteCheckStatus

	data object CHECKING : RemoteCheckStatus

	data object REPO_FOUND : RemoteCheckStatus

	data object NO_REPO : RemoteCheckStatus

	data object ERROR : RemoteCheckStatus
}

/**
 * One row in the single-page remote picker.
 *
 * @since Item 3 — single-page remote picker with per-remote status
 */
data class RemoteStatus(
	val remote: RcloneConfigManager.RemoteInfo,
	val status: RemoteCheckStatus,
)

/**
 * State of the mandatory repo setup flow.
 *
 * @since PR1 sync — mandatory repo setup
 */
sealed interface RepoSetupState {
	/** No config imported yet — user needs to pick an rclone.conf file. */
	object NeedsConfig : RepoSetupState

	/** Config imported but no remote chosen — user needs to pick a remote. */
	data class NeedsRemoteChoice(
		val remotes: List<RcloneConfigManager.RemoteInfo>,
	) : RepoSetupState

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

	/**
	 * Escrow downloaded successfully — user needs to enter their recovery phrase
	 * to unlock the VMK. The recovery-phrase [VaultProtection] has been persisted
	 * to the local DB by [RepoManager.downloadVaultProtectionEscrow], so the
	 * embedded `RecoveryPhraseRestoreScreen` (which calls
	 * `vaultService.unlock(UnlockRequest.RecoveryPhrase(phrase))`) will succeed.
	 *
	 * Thumbnails were already restored from the remote in the preceding
	 * [RestoringBackup] state — they're on disk (still encrypted with the VMK).
	 * After the user enters the correct phrase, the VMK is in memory via
	 * SessionRepository, and the existing decryption path will use it when the
	 * gallery renders those thumbnails.
	 *
	 * Shown when [RepoManager.LoginResult.Success.escrow] is [RepoManager.EscrowType.PHRASE_ONLY]
	 * (older repo created before Part B two-layer escrow existed — only Layer 1
	 * `recovery-phrase.json` is on the remote, no Layer 2 `wrapped-phrase.json`).
	 *
	 * @since key-escrow — login-branch phrase entry
	 */
	object NeedsPhraseEntry : RepoSetupState

	/**
	 * Both escrow layers downloaded successfully — user needs to enter their
	 * vault PASSWORD (not the phrase) to restore the backup. This is the
	 * preferred login path on a fresh install of an app created with Part B
	 * two-layer escrow: the user enters their password, which unwraps the
	 * recovery phrase (Layer 2: `wrapped-phrase.json`), which in turn unwraps
	 * the VMK (Layer 1: `recovery-phrase.json`) via the existing
	 * RecoveryPhraseVaultProtectionHandler.
	 *
	 * The state carries the loading flag (set while [RepoSetupViewModel.submitPassword]
	 * is unwrapping + unlocking) and an optional error message (set when the
	 * password is wrong — the wrapper produces a padding error → caught →
	 * clear "Incorrect password" message, NOT a crash).
	 *
	 * Shown when [RepoManager.LoginResult.Success.escrow] is
	 * [RepoManager.EscrowType.PASSWORD_PLUS_PHRASE].
	 *
	 * @since Part B two-layer escrow — password-entry UI for the login branch
	 */
	data class NeedsPasswordEntry(
		val loading: Boolean = false,
		val error: String? = null,
	) : RepoSetupState

	/**
	 * No escrow artifact available on the remote (old repo created before this
	 * feature, OR escrow download failed non-fatally). User can still reach the
	 * gallery via the normal vault PIN/password path (SetupFragment), but
	 * recovery-phrase restore is not available on this fresh install.
	 *
	 * @since key-escrow — login-branch phrase entry
	 */
	object NoEscrowAvailable : RepoSetupState

	/** Repo session confirmed — gallery can be unlocked. */
	object Completed : RepoSetupState

	/**
	 * Vault unlocked via the login-branch password or phrase entry path — the
	 * VMK is in memory via [SessionRepository], so the user can be navigated
	 * straight to the gallery, skipping SetupFragment (which would otherwise
	 * prompt for a new password).
	 *
	 * The Fragment's `LaunchedEffect(state)` observes this and invokes
	 * `onUnlocked` (= `navigateToGallery`).
	 *
	 * @since Part B two-layer escrow — password-entry UI for the login branch
	 */
	object Unlocked : RepoSetupState

	/** Error at any stage. [message] is user-facing. */
	data class Error(
		val message: String,
	) : RepoSetupState
}

@HiltViewModel
class RepoSetupViewModel
	@Inject
	constructor(
		private val app: Application,
		private val config: Config,
		private val rcloneConfigManager: RcloneConfigManager,
		private val repoManager: RepoManager,
		// @since Part B two-layer escrow — needed by submitPassword() to unwrap the
		// downloaded recovery phrase (Layer 2 artifact) with the user's password.
		private val phraseEscrowWrapper: PhraseEscrowWrapper,
		// @since Part B two-layer escrow — needed by submitPassword() to unlock the
		// VMK via the existing RecoveryPhrase unlock path once the phrase is
		// recovered from the password-wrapped artifact.
		private val vaultService: VaultService,
		// @since Part B two-layer escrow — needed by submitPassword() to set the
		// unlocked VaultSession into the in-memory session repo so the gallery's
		// decryption path can use the VMK.
		private val sessionRepository: SessionRepository,
	) : ViewModel() {
		private val _state = MutableStateFlow<RepoSetupState>(RepoSetupState.NeedsConfig)
		val state: StateFlow<RepoSetupState> = _state.asStateFlow()

		/**
		 * Per-remote check status, surfaced to the single-page remote picker.
		 *
		 * Populated when [checkAllRemotes] runs — each remote in the imported
		 * rclone.conf is probed via [RepoManager.hasRepo] (which calls
		 * `rcloneController.listRemote("$remoteName:", REPO_DIR)` directly,
		 * WITHOUT mutating [Config.syncChosenRemote]).
		 *
		 * Reset to `emptyList()` whenever the user navigates back to
		 * [RepoSetupState.NeedsRemoteChoice] (e.g. after an error + dismiss).
		 *
		 * @since Item 3 — single-page remote picker with per-remote status
		 */
		private val _remoteStatuses = MutableStateFlow<List<RemoteStatus>>(emptyList())
		val remoteStatuses: StateFlow<List<RemoteStatus>> = _remoteStatuses.asStateFlow()

		/**
		 * Password typed in the single-page picker's inline password field.
		 *
		 * When the user taps "Connect" with a non-empty password and the chosen
		 * remote turns out to have an existing repo + PASSWORD_PLUS_PHRASE escrow,
		 * the password is auto-submitted via [submitPassword] (saving the user
		 * from re-typing it on the dedicated [RepoSetupState.NeedsPasswordEntry]
		 * screen). Cleared after submission (success or failure) so a retry on
		 * the dedicated screen starts fresh.
		 *
		 * @since Item 3 — single-page remote picker with inline password
		 */
		private val _pendingPassword = MutableStateFlow("")
		val pendingPassword: StateFlow<String> = _pendingPassword.asStateFlow()

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
						val msg =
							when (reason) {
								RcloneConfigManager.Status.InvalidReason.NO_SECTIONS ->
									app.getString(R.string.settings_cloud_sync_invalid_no_sections)
								RcloneConfigManager.Status.InvalidReason.UNREADABLE ->
									app.getString(R.string.sync_settings_import_io_error)
							}
						_state.value = RepoSetupState.Error(msg)
					},
				)
			}
		}

		/**
		 * User picked a remote. Persist it, then check reachability + detect register vs login.
		 *
		 * @param pendingPassword optional password typed in the single-page
		 * picker's inline password field. If non-blank, the password is stashed
		 * via [setPendingPassword] so that when the login flow lands in
		 * [RepoSetupState.NeedsPasswordEntry], the password is auto-submitted
		 * via [submitPassword] (saving the user from re-typing it). Blank means
		 * the user didn't type a password — the dedicated NeedsPasswordEntry
		 * screen will be shown as before.
		 *
		 * @since Item 3 — single-page remote picker with inline password
		 */
		fun chooseRemote(
			name: String,
			pendingPassword: String = "",
		) {
			if (pendingPassword.isNotEmpty()) {
				_pendingPassword.value = pendingPassword
			}
			viewModelScope.launch {
				rcloneConfigManager.chooseRemote(name)
				_state.value = RepoSetupState.Checking
				checkRemoteAndDetectRepo()
			}
		}

		/**
		 * Stash the password typed in the single-page picker's inline field.
		 * Consumed (and cleared) by [submitPassword] when the login flow lands
		 * in [RepoSetupState.NeedsPasswordEntry].
		 *
		 * @since Item 3 — single-page remote picker with inline password
		 */
		fun setPendingPassword(password: String) {
			_pendingPassword.value = password
		}

		/**
		 * Probe every remote in the imported rclone.conf for repo existence,
		 * updating [remoteStatuses] as each check completes.
		 *
		 * Uses [RepoManager.hasRepo] (which calls `rcloneController.listRemote`
		 * directly — NO mutation of [Config.syncChosenRemote]) so the user can
		 * inspect every remote before picking one. Each remote's check runs in
		 * its own coroutine so the UI updates per-remote as results arrive
		 * (rather than blocking on the slowest remote).
		 *
		 * The list is initialized to all remotes with status [RemoteCheckStatus.CHECKING]
		 * so the user sees "Checking…" on every row immediately, then each row
		 * transitions to [REPO_FOUND] / [NO_REPO] / [ERROR] as its check
		 * completes. Remotes added to the config after this call won't appear
		 * until checkAllRemotes is invoked again.
		 *
		 * Idempotent: calling it again re-probes every remote from scratch.
		 *
		 * @since Item 3 — single-page remote picker with per-remote status
		 */
		fun checkAllRemotes() {
			viewModelScope.launch {
				val remotes = rcloneConfigManager.availableRemotes()
				if (remotes.isEmpty()) {
					_remoteStatuses.value = emptyList()
					return@launch
				}
				// Initialize every row to CHECKING so the user sees immediate
				// feedback. Each remote's check runs concurrently and updates
				// its own row on completion.
				_remoteStatuses.value = remotes.map { RemoteStatus(it, RemoteCheckStatus.CHECKING) }
				for (remote in remotes) {
					launch {
						val hasRepo = repoManager.hasRepo(remote.name)
						val newStatus =
							when (hasRepo) {
								true -> RemoteCheckStatus.REPO_FOUND
								false -> RemoteCheckStatus.NO_REPO
								null -> RemoteCheckStatus.ERROR
							}
						_remoteStatuses.value =
							_remoteStatuses.value.map {
								if (it.remote.name == remote.name) it.copy(status = newStatus) else it
							}
					}
				}
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
					when (loginResult) {
						is RepoManager.LoginResult.Success -> {
							// @since PR4 sync — restore thumbnails from backup before
							// completing. Show a brief "Restoring backup…" state so the
							// user knows why login is taking longer than a no-op connect.
							// Restore failure MUST NOT block login — the user can still
							// get into the gallery; photos will be re-uploaded on the next
							// sync cycle.
							_state.value = RepoSetupState.RestoringBackup
							val restored =
								try {
									repoManager.restoreThumbnailsAfterLogin()
								} catch (e: Exception) {
									Timber.w(e, "restoreThumbnailsAfterLogin failed; continuing")
									0
								}
							Timber.i(
								"Repo login complete; restored $restored thumbnails from backup; " +
									"escrow=${loginResult.escrow}",
							)

							// @since Part B two-layer escrow — route the user to the
							// appropriate restore UI based on which escrow artifacts
							// were downloaded. PASSWORD_PLUS_PHRASE is the preferred
							// path (the user's password is more memorable than the
							// 12-word phrase); PHRASE_ONLY is the fallback for old
							// repos created before Part B; NONE is the degraded mode
							// (no escrow — user goes through SetupFragment instead).
							when (loginResult.escrow) {
								RepoManager.EscrowType.PASSWORD_PLUS_PHRASE -> {
									// @since Item 3 — if the user typed a password in
									// the single-page picker's inline field, auto-submit
									// it now. This saves the user from re-typing the
									// password on the dedicated NeedsPasswordEntry
									// screen. Wrong password → submitPassword sets
									// NeedsPasswordEntry(error) → user retries there.
									val pending = _pendingPassword.value
									if (pending.isNotEmpty()) {
										_state.value = RepoSetupState.NeedsPasswordEntry(loading = true)
										submitPassword(pending)
									} else {
										_state.value = RepoSetupState.NeedsPasswordEntry()
									}
								}
								RepoManager.EscrowType.PHRASE_ONLY -> {
									_state.value = RepoSetupState.NeedsPhraseEntry
								}
								RepoManager.EscrowType.NONE -> {
									_state.value = RepoSetupState.NoEscrowAvailable
								}
							}
						}
						is RepoManager.LoginResult.Failure -> {
							_state.value =
								RepoSetupState.Error(
									"Connection failed: ${loginResult.message}",
								)
						}
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
						_state.value =
							RepoSetupState.Error(
								"Failed to register repo: ${e.message}",
							)
					},
				)
			}
		}

		/**
		 * Dismiss error, return to initial state.
		 */
		fun dismissError() {
			viewModelScope.launch {
				val status = rcloneConfigManager.currentStatus()
				_state.value =
					when (status) {
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

		/**
		 * User chose to continue without recovery-phrase restore (from the
		 * [RepoSetupState.NoEscrowAvailable] state). Transitions to [RepoSetupState.Completed],
		 * which chains forward to SetupFragment (normal vault PIN/password unlock path).
		 *
		 * @since key-escrow — login-branch phrase entry
		 */
		fun continueWithoutEscrow() {
			_state.value = RepoSetupState.Completed
		}

		/**
		 * User submitted their vault password on the [RepoSetupState.NeedsPasswordEntry]
		 * screen. Attempts to unwrap the downloaded recovery phrase (Layer 2 artifact,
		 * stored on the singleton [RepoManager] from [RepoManager.loginRepo]) with the
		 * password, then feeds the recovered phrase into the existing
		 * `vaultService.unlock(UnlockRequest.RecoveryPhrase(phrase))` path.
		 *
		 * Outcomes (all routed back through `_state`):
		 * - Wrong password → [PhraseEscrowWrapper.unwrapPhrase] returns null (caught
		 *   padding error) → state goes back to [RepoSetupState.NeedsPasswordEntry]
		 *   with the `repo_setup_password_entry_error` string ("Incorrect password").
		 *   This is a clear, user-facing message — NOT a crash.
		 * - Correct password + unlock succeeds → state goes to [RepoSetupState.Unlocked],
		 *   which the Fragment's `LaunchedEffect` observes to call `onUnlocked`
		 *   (= `navigateToGallery`). The VMK is now in memory via [SessionRepository],
		 *   so the gallery's decryption path will use it.
		 * - Correct password + unlock fails (e.g. corrupted Layer 1 artifact) →
		 *   state goes back to [RepoSetupState.NeedsPasswordEntry] with the exception
		 *   message (rare; surfaces the underlying error rather than masking it).
		 *
		 * @since Part B two-layer escrow — password-entry UI for the login branch
		 */
		fun submitPassword(password: String) {
			viewModelScope.launch {
				_state.value = RepoSetupState.NeedsPasswordEntry(loading = true, error = null)

				// @since Item 3 — clear the pending password (whether it was set
				// from the single-page picker or from this call). It's been
				// consumed; any retry on the dedicated NeedsPasswordEntry screen
				// starts fresh.
				_pendingPassword.value = ""

				val wrapped = repoManager.getDownloadedWrappedPhrase()
				if (wrapped == null) {
					// Defensive — we only enter NeedsPasswordEntry when
					// PASSWORD_PLUS_PHRASE was returned, which means wrapped != null.
					// If we get here, state was corrupted somehow; surface as error.
					_state.value =
						RepoSetupState.NeedsPasswordEntry(
							error = app.getString(R.string.repo_setup_password_entry_error),
						)
					return@launch
				}

				val phrase =
					try {
						phraseEscrowWrapper.unwrapPhrase(wrapped, password)
					} catch (e: Exception) {
						// Shouldn't happen — unwrapPhrase catches internally — but be defensive.
						Timber.w(e, "unwrapPhrase threw (non-fatal) — treating as wrong password")
						null
					}
				if (phrase == null) {
					// Wrong password → padding error → null. Clear message, NOT a crash.
					_state.value =
						RepoSetupState.NeedsPasswordEntry(
							error = app.getString(R.string.repo_setup_password_entry_error),
						)
					return@launch
				}

				// Phrase recovered — feed into the existing RecoveryPhrase unlock path.
				// vaultService.unlock() will look up the local VaultProtection(RecoveryPhrase)
				// row (downloaded by RepoManager.downloadVaultProtectionEscrow during
				// loginRepo) and unwrap the VMK with the phrase.
				vaultService
					.unlock(UnlockRequest.RecoveryPhrase(phrase))
					.onSuccess { session ->
						sessionRepository.set(session)

						// ─── v9 dedup: download + cache the remote registry ──────────
						// Now that the vault is unlocked, the VMK is available to
						// decrypt the remote `registry.json.crypt` into the local
						// Room cache. The cache is consulted by [PhotoSyncWorker]
						// before every upload to skip transfers whose content-hash
						// is already on the remote. Failure here is non-fatal — the
						// registry simply won't have entries yet (fresh repo or
						// pre-v9 repo) and uploads will proceed normally without
						// dedup; the cache will be populated by future registry
						// flushes from this device.
						try {
							val loaded = repoManager.downloadRegistry(session.vmk.encoded)
							Timber.e(
								"submitPassword: downloadRegistry loaded $loaded entries into local cache",
							)

							// ─── v9 followup: backfill Photo metadata from registry ──
							// [restoreThumbnailsAfterLogin] (called earlier in the
							// login flow, BEFORE the vault was unlocked) created
							// Photo rows with placeholder metadata (type=JPEG,
							// size=0, relativePath=null) because the registry could
							// not be decrypted yet. Now that the registry is in the
							// local cache, walk those placeholder rows and backfill
							// the real metadata (filename, size, type, albumPath,
							// contentHash) from the matching registry entries.
							//
							// Without this step, the gallery shows restored photos
							// with bogus metadata (always JPEG, size 0, no album
							// path) until the user opens each one and the on-demand
							// original-fetch path corrects it — see the data-loss
							// bug report ("metadata hilang saat reinstall").
							val backfilled = repoManager.applyRegistryMetadataToPhotos()
							Timber.e(
								"submitPassword: applyRegistryMetadataToPhotos backfilled $backfilled placeholder rows",
							)

							// ─── v9 followup (Bug 4): pack-based thumbnail restore ──
							// For repos created after Bug 4, individual thumbnails
							// are NOT uploaded (only packs). [restoreThumbnailsAfterLogin]
							// (called before unlock) found nothing in the thumbnails
							// dir for these repos. Now that the registry is cached,
							// we can download packs and extract thumbnails — one
							// pack download serves N thumbnails, instead of N
							// round-trips. Also handles legacy individual thumbnails
							// (for old repos) as a fallback.
							val packRestored = repoManager.restoreThumbnailsFromPacks()
							Timber.e(
								"submitPassword: restoreThumbnailsFromPacks restored $packRestored thumbnails from packs (and/or legacy individual)",
							)
						} catch (e: Exception) {
							Timber.e(
								"submitPassword: downloadRegistry FAILED (non-fatal — uploads will still work, just no dedup): ${e.message}",
								e,
							)
							Timber.w(e, "submitPassword: downloadRegistry failed (non-fatal)")
						}

						// ─── DATA-LOSS FIX: persist local VaultProtection(Password) ────
						// After a login-branch unlock, the local Room DB has
						// VaultProtection(RecoveryPhrase) (downloaded from escrow) but
						// NO VaultProtection(Password). On the next app open, canUnlock()
						// would return false (no Password row) → app thinks it's a fresh
						// install → SetupFragment → new password → NEW VMK → all previously
						// encrypted photos become undecryptable (red thumbnails).
						//
						// Fix: wrap the recovered VMK with the user's password and persist
						// it locally. On subsequent opens, canUnlock() finds the Password
						// row → returns true → LOCKED → UnlockFragment → user enters
						// password → same VMK → photos decrypt correctly.
						try {
							vaultService.createPasswordProtectionFromSession(password, session)
							Timber.e(
								"submitPassword: created local VaultProtection(Password) — " +
									"canUnlock will return true on next open",
							)
						} catch (e: Exception) {
							// F-SYNC-005: Do NOT navigate to gallery. Without a local
							// VaultProtection(Password) row, next canUnlock() returns false
							// -> SetupFragment -> new VMK -> all photos undecryptable.
							Timber.e(e, "submitPassword: createPasswordProtectionFromSession failed")
							_state.value = RepoSetupState.Error(
								"Critical: unable to persist vault password. Do not close the app. Contact support. Error: ${e.message}",
							)
							return@launch
						}

						_state.value = RepoSetupState.Unlocked
					}.onFailure { e ->
						Timber.w(e, "submitPassword: vaultService.unlock failed")
						_state.value =
							RepoSetupState.NeedsPasswordEntry(
								error = e.message ?: app.getString(R.string.repo_setup_password_entry_error),
							)
					}
			}
		}
	}
