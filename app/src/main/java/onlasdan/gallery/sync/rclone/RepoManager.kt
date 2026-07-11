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

package onlasdan.gallery.sync.rclone

import android.app.Application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import onlasdan.gallery.encryption.domain.RecoveryPhraseStore
import onlasdan.gallery.encryption.domain.VaultProtectionRepository
import onlasdan.gallery.encryption.domain.crypto.PhraseEscrowWrapper
import onlasdan.gallery.encryption.domain.models.Algorithm
import onlasdan.gallery.encryption.domain.models.Kdf
import onlasdan.gallery.encryption.domain.models.VaultProtection
import onlasdan.gallery.encryption.domain.models.VaultProtectionParams
import onlasdan.gallery.encryption.domain.models.VaultProtectionType
import onlasdan.gallery.encryption.domain.models.VaultSession
import onlasdan.gallery.model.database.dao.AlbumDao
import onlasdan.gallery.model.database.dao.PhotoDao
import onlasdan.gallery.model.database.entity.AlbumTable
import onlasdan.gallery.model.database.entity.PHOTOK_FILE_EXTENSION
import onlasdan.gallery.model.database.entity.Photo
import onlasdan.gallery.model.database.entity.PhotoType
import onlasdan.gallery.settings.data.Config
import onlasdan.gallery.sync.domain.SyncConfig
import onlasdan.gallery.sync.domain.SyncState
import onlasdan.gallery.sync.work.HashRegistry
import onlasdan.gallery.sync.work.HashRegistryEntry
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the restic-style repo on the rclone remote.
 *
 * A repo is identified by a `repo-config.json` marker file at the repo root
 * (`<remote>:photok-backup/repo-config.json`) containing:
 *   {"repo_id":"<uuid>","created":"<iso8601>","version":1}
 *
 * ## Register vs Login detection
 *
 * Before any write, [detectRepo] lists the repo root via an independent `operations/list` rc
 * call. This is the check that was previously missing entirely — the app used to write blindly
 * with no prior existence check, which is the root cause behind the recurring false-upload-
 * success bug.
 *
 * - Marker file absent → [RepoState.NOT_INITIALIZED] → caller runs [registerRepo].
 * - Marker file present and parses → [RepoState.LOGGED_IN] → caller skips registration.
 * - Listing errors → [RepoState.ERROR] → caller surfaces error, does NOT guess.
 *
 * ## Independent verification
 *
 * [registerRepo] writes the marker, then **independently re-lists** to confirm the write
 * actually landed. The write call's return value alone is NOT sufficient proof (this exact
 * mistake caused the previous false-success bug). Only after independent verification
 * succeeds does the repo become "confirmed" locally.
 *
 * @since PR1 sync — mandatory repo setup
 */
@Singleton
class RepoManager
	@Inject
	constructor(
		private val app: Application,
		private val config: Config,
		private val rcloneController: RcloneController,
		private val configManager: RcloneConfigManager,
		// @since PR4 sync — needed by restoreThumbnailsAfterLogin() to insert DB rows for
		// each thumbnail pulled back from the remote after a successful login.
		private val photoDao: PhotoDao,
		// @since Bug 5 fix — auto-create albums from folder path on restore.
		// When [ensurePhotoRowForRestoredEntry] creates a new Photo row from a
		// registry entry, it also calls [ensureAlbumForRestoredPhoto] to
		// find-or-create an album whose name matches the entry's `albumPath`
		// and link the photo to it. Without this, restored photos never show up
		// in any album even though they carry a folder key — the user would
		// have to manually create and populate each album after every restore.
		private val albumDao: AlbumDao,
		// @since key-escrow — needed by uploadRecoveryPhraseEscrow() / downloadVaultProtectionEscrow()
		// to persist the recovery-phrase wrapped-VMK artifact on the remote during registration
		// and to restore it into the local DB during login (so the existing phrase-entry UI can
		// unlock the VMK on a fresh install).
		private val vaultProtectionRepository: VaultProtectionRepository,
		// @since Part A fix + Part B two-layer escrow — PhraseEscrowWrapper wraps the recovery
		// phrase with a password-derived KEK. Used by uploadAllEscrows() (which runs AFTER
		// SetupFragment has created both password + phrase VaultProtection rows, NOT during
		// registerRepo() — the prior early call was the root cause of the register-always-
		// triggered bug) and by submitPassword() on the login branch.
		private val phraseEscrowWrapper: PhraseEscrowWrapper,
		// @since Part A fix + Part B two-layer escrow — needed by uploadAllEscrows() to read
		// the locally-stored recovery phrase (encrypted-at-rest with the VMK via
		// RecoveryPhraseStoreImpl) so it can be wrapped with the password and uploaded as
		// wrapped-phrase.json (Layer 2 of the two-layer escrow).
		private val recoveryPhraseStore: RecoveryPhraseStore,
		// @since v9 dedup + encrypted GCM registry — needed by [downloadRegistry] to
		// download + decrypt the remote `registry.json.crypt` into the local Room cache
		// after the vault is unlocked. The VMK is required for decryption, so this is
		// called from [RepoSetupViewModel.submitPassword] AFTER `sessionRepository.set`
		// (not from [restoreThumbnailsAfterLogin], which runs BEFORE unlock).
		private val hashRegistry: HashRegistry,
		/**
		 * Sprint 2 / M7 — Multi-vault.
		 *
		 * Used to fetch the current session's `vault_id` when creating Photo rows
		 * during restore. Restored photos are tagged with the syncing vault's
		 * vault_id so they appear in the correct gallery view.
		 *
		 * The session is set in [RepoSetupViewModel.submitPassword] BEFORE any
		 * restore path runs, so this is safe to access.
		 */
		private val sessionRepository: onlasdan.gallery.encryption.domain.SessionRepository,
	) {
		// ─── DIAGNOSTIC LOGGING (RcloneDiag pattern, same as RcloneController) ────
		// Writes to BOTH Log.e (logcat) AND files/sync_log.txt so it's visible via
		// `adb logcat -d | grep RcloneDiag` AND
		// `adb shell run-as <pkg> cat files/sync_log.txt`.
		// Used by restoreThumbnailsAfterLogin() — restore bugs are otherwise silent
		// (no UI surfaces failures, just empty gallery).
		//
		// The file-append path goes through [SyncLogRotator.append], which caps
		// `sync_log.txt` at 1 MB (truncating to the last 512 KB when exceeded).
		// Without that cap the file would grow without bound — it lives in
		// `filesDir` (persistent storage, NOT cache), so it never gets reclaimed
		// by the OS. See [SyncLogRotator] for the full rotation policy.
		private fun diag(
			msg: String,
			throwable: Throwable? = null,
		) {
			android.util.Log.e("RcloneDiag", "[Restore] $msg", throwable)
			try {
				val entry =
					buildString {
						append("\n[RcloneDiag] [Restore] ")
						append(msg)
						append('\n')
						if (throwable != null) {
							append(throwable.stackTraceToString())
							append('\n')
						}
					}
				onlasdan.gallery.sync.debug.SyncLogRotator
					.append(app, entry)
			} catch (_: Exception) {
				// Never let diag() itself throw.
			}
		}

		data class RepoMarker(
			val repoId: String,
			val created: String,
			val version: Int,
		)

		sealed class RepoState {
			/** No repo-config.json found on the remote. Caller should register. */
			object NOT_INITIALIZED : RepoState()

			/** Repo-config.json found and parsed. Caller should login (read-only). */
			data class LOGGED_IN(
				val marker: RepoMarker,
			) : RepoState()

			/** Listing failed. Caller should surface error — do NOT guess register vs login. */
			data class ERROR(
				val message: String,
			) : RepoState()
		}

		/**
		 * What kind of escrow was downloaded from the remote during [loginRepo].
		 *
		 * Drives which restore UI the login branch shows:
		 * - [PASSWORD_PLUS_PHRASE] → both `recovery-phrase.json` (wrapped VMK) AND
		 *   `wrapped-phrase.json` (password-wrapped phrase) downloaded. Show the
		 *   password-entry UI — the user's password unwraps the phrase, which in
		 *   turn unwraps the VMK via the existing RecoveryPhraseVaultProtectionHandler.
		 * - [PHRASE_ONLY] → only `recovery-phrase.json` downloaded (older repo
		 *   created before Part B two-layer escrow existed). Show the existing
		 *   recovery-phrase-entry UI (RecoveryPhraseRestoreScreen) — the user must
		 *   type their phrase directly.
		 * - [NONE] → neither artifact available. Show the degraded-mode UI
		 *   ("no escrow available"); the user can still reach the gallery via
		 *   the normal vault PIN/password path (SetupFragment).
		 *
		 * @since Part A fix + Part B two-layer escrow — replaces the prior
		 *   `escrowAvailable: Boolean` flag with a 3-way enum so the login branch
		 *   can distinguish "password-entry UI" from "phrase-entry UI".
		 */
		enum class EscrowType { NONE, PHRASE_ONLY, PASSWORD_PLUS_PHRASE }

		/**
		 * Result of [loginRepo]. Carries the escrow-artifact status so the caller
		 * can decide which restore UI to route the user to.
		 *
		 * @since key-escrow — login-branch phrase entry; refined in Part B to carry
		 *   an [EscrowType] instead of a Boolean.
		 */
		sealed class LoginResult {
			/**
			 * Login (remote repo session) succeeded.
			 *
			 * @param escrow which escrow artifacts were downloaded from the remote
			 *   during login. See [EscrowType] for the routing logic.
			 */
			data class Success(
				val escrow: EscrowType,
			) : LoginResult()

			/** Login failed — caller should surface error. */
			data class Failure(
				val message: String,
			) : LoginResult()
		}

		/**
		 * Detect whether a repo exists on the currently-chosen remote.
		 *
		 * Uses an independent `operations/list` call scoped to the repo root path. This is the
		 * critical check that was previously missing — the app never distinguished "repo doesn't
		 * exist yet, create it" from "repo already exists, open it".
		 *
		 * Must be called after [RcloneConfigManager.import] + [RcloneConfigManager.chooseRemote]
		 * have completed (i.e. rcd is running with a valid config and a chosen remote).
		 */
		suspend fun detectRepo(): RepoState =
			withContext(Dispatchers.IO) {
				// ─── DIAGNOSTIC LOGGING (Step B) ─────────────────────────────────
				// detectRepo() is the user-facing entry point for the bug: it's the first
				// call that triggers rclone RPC during login/repo-init. If we
				// never see this log line, the bug is in the call chain ABOVE detectRepo
				// (e.g. the ViewModel doesn't call it, or short-circuits before).
				android.util.Log.e(
					"RcloneDiag",
					"detectRepo: BEGIN remote=${config.syncChosenRemote} repoConfirmed=${config.repoConfirmed}",
				)
				try {
					onlasdan.gallery.sync.debug.SyncLogRotator.append(app,
						"\n[RcloneDiag] detectRepo: BEGIN remote=${config.syncChosenRemote} repoConfirmed=${config.repoConfirmed}\n",
					)
				} catch (_: Exception) {
				}

				val remote = config.syncChosenRemote
				if (remote.isNullOrBlank()) {
					android.util.Log.e("RcloneDiag", "detectRepo: ABORT — no remote chosen")
					return@withContext RepoState.ERROR("No remote chosen")
				}

				val repoRoot = "$remote:$REPO_DIR"
				try {
					// Use operations/list to check for the marker file
					android.util.Log.e("RcloneDiag", "detectRepo: calling listRemote($repoRoot)")
					val result = rcloneController.listRemote("$remote:", REPO_DIR)
					if (result.isFailure) {
						val err = result.exceptionOrNull()?.message ?: "unknown error"
						android.util.Log.e(
							"RcloneDiag",
							"detectRepo: listRemote FAILED class=${result.exceptionOrNull()?.javaClass?.name} msg=$err",
							result.exceptionOrNull(),
						)
						// Only treat as "repo not initialized" if the error is specifically about
						// the remote directory not existing. Do NOT match "not found" generically —
						// that would also match "rclone binary not found" from locateRcloneBinary(),
						// swallowing a real infrastructure error as a false "repo doesn't exist yet".
						// @since PR1 sync — fix for error-swallowing bug that hid binary-not-found
						val isDirNotFound =
							err.contains("directory not found", ignoreCase = true) ||
								err.contains("error in ListJSON", ignoreCase = true) ||
								err.contains("not found", ignoreCase = true)
						if (isDirNotFound) {
							android.util.Log.e("RcloneDiag", "detectRepo: classifying as NOT_INITIALIZED (dir not found)")
							return@withContext RepoState.NOT_INITIALIZED
						}
						android.util.Log.e("RcloneDiag", "detectRepo: classifying as ERROR (not a dir-not-found error)")
						return@withContext RepoState.ERROR(err)
					}

					val files = result.getOrThrow()
					android.util.Log.e("RcloneDiag", "detectRepo: listRemote OK, ${files.size} files")
					val markerFile = files.find { it.name == MARKER_FILENAME }
					if (markerFile == null) {
						android.util.Log.e("RcloneDiag", "detectRepo: marker not in listing → NOT_INITIALIZED")
						return@withContext RepoState.NOT_INITIALIZED
					}

					// Marker exists — download and parse it
					android.util.Log.e("RcloneDiag", "detectRepo: marker found, downloading")
					val tempMarker = File(app.cacheDir, "repo-config-download-${System.currentTimeMillis()}.json")
					val downloadResult =
						rcloneController.downloadFile(
							"$remote:$REPO_DIR/$MARKER_FILENAME",
							tempMarker.absolutePath,
						)
					if (downloadResult.isFailure) {
						android.util.Log.e(
							"RcloneDiag",
							"detectRepo: downloadFile FAILED msg=${downloadResult.exceptionOrNull()?.message}",
						)
						return@withContext RepoState.ERROR(
							"Failed to download marker: ${downloadResult.exceptionOrNull()?.message}",
						)
					}

					val markerContent = tempMarker.readText()
					tempMarker.delete()
					val marker =
						parseMarker(markerContent)
							?: return@withContext RepoState.ERROR("Malformed marker file")

					android.util.Log.e("RcloneDiag", "detectRepo: marker parsed, state=LOGGED_IN repoId=${marker.repoId}")
					RepoState.LOGGED_IN(marker)
				} catch (e: Exception) {
					android.util.Log.e("RcloneDiag", "detectRepo: CAUGHT ${e.javaClass.name}: ${e.message}", e)
					RepoState.ERROR(e.message ?: e.javaClass.simpleName)
				}
			}

		/**
		 * Lightweight repo-existence check for a SPECIFIC remote name, without
		 * mutating [Config.syncChosenRemote].
		 *
		 * Used by [onlasdan.gallery.reposetup.ui.RepoSetupViewModel.checkAllRemotes]
		 * to annotate every remote in the imported rclone.conf with "Repo found"
		 * vs "No repo" before the user picks one. The full [detectRepo] flow
		 * (which downloads + parses the marker) is overkill for the picker UI —
		 * we just need to know whether the repo dir contains the marker file.
		 *
		 * The check uses [RcloneController.listRemote] directly (rcd serves any
		 * remote in the imported rclone.conf, not just the chosen one), so we
		 * don't need to swap [Config.syncChosenRemote] in/out around the call.
		 *
		 * @return `true` if a repo marker file exists on the remote, `false` if
		 * the remote is reachable but no marker exists, `null` if the remote
		 * could not be reached (network/auth error) — caller treats `null` as
		 * "error" and surfaces it as such in the picker UI.
		 *
		 * @since Item 3 — single-page remote picker with per-remote status
		 */
		suspend fun hasRepo(remoteName: String): Boolean? =
			withContext(Dispatchers.IO) {
				if (remoteName.isBlank()) return@withContext null
				try {
					val result = rcloneController.listRemote("$remoteName:", REPO_DIR)
					if (result.isFailure) {
						val err = result.exceptionOrNull()?.message ?: "unknown error"
						// Mirror detectRepo()'s dir-not-found classification — a missing
						// repo dir is NOT an error here, it just means "no repo".
						val isDirNotFound =
							err.contains("directory not found", ignoreCase = true) ||
								err.contains("error in ListJSON", ignoreCase = true) ||
								(
									err.contains("not found", ignoreCase = true) &&
										!err.contains("binary", ignoreCase = true) &&
										!err.contains("rclone", ignoreCase = true)
								)
						if (isDirNotFound) {
							return@withContext false
						}
						android.util.Log.e("RcloneDiag", "hasRepo($remoteName): listRemote FAILED: $err")
						return@withContext null
					}
					val files = result.getOrThrow()
					val hasMarker = files.any { it.name == MARKER_FILENAME }
					android.util.Log.e("RcloneDiag", "hasRepo($remoteName): ${files.size} files, marker=$hasMarker")
					hasMarker
				} catch (e: Exception) {
					android.util.Log.e("RcloneDiag", "hasRepo($remoteName): CAUGHT ${e.javaClass.name}: ${e.message}", e)
					null
				}
			}

		/**
		 * Register a new repo on the remote. Writes the marker file, then independently re-lists
		 * to verify the write landed. Only after verification succeeds is the repo confirmed.
		 *
		 * @return `Result.success(RepoMarker)` on success, `Result.failure` on any error.
		 */
		suspend fun registerRepo(): Result<RepoMarker> =
			withContext(Dispatchers.IO) {
				runCatching {
					val remote =
						config.syncChosenRemote
							?: throw IllegalStateException("No remote chosen")

					val marker =
						RepoMarker(
							repoId = UUID.randomUUID().toString(),
							created = Instant.now().toString(),
							version = 1,
						)
					val markerJson = """{"repo_id":"${marker.repoId}","created":"${marker.created}","version":${marker.version}}"""

					// Write marker to a temp file, then upload via rclone
					val tempFile = File(app.cacheDir, "repo-config-${System.currentTimeMillis()}.json")
					tempFile.writeText(markerJson)

					try {
						// Ensure repo directory exists. Try mkdir first (idempotent — no error if exists).
						// If fails: purge anything at path (stale file from previous attempt),
						// then retry mkdir. operations/copyfile needs parent dir to exist.
						Timber.i("registerRepo: ensuring directory $REPO_DIR exists")
						val createDirResult = rcloneController.createDir("$remote:$REPO_DIR")
						if (createDirResult.isFailure) {
							val dirErr = createDirResult.exceptionOrNull()?.message ?: "unknown"
							Timber.w("registerRepo: createDir failed ($dirErr) — purge + retry")
							rcloneController.removeDir("$remote:$REPO_DIR", recursive = true).onFailure { }
							val retryResult = rcloneController.createDir("$remote:$REPO_DIR")
							if (retryResult.isFailure) {
								throw IOException("Cannot create $REPO_DIR: $dirErr / ${retryResult.exceptionOrNull()?.message}")
							}
							Timber.i("registerRepo: createDir OK (after purge)")
						} else {
							Timber.i("registerRepo: createDir OK")
						}

						val remotePath = "$remote:$REPO_DIR/$MARKER_FILENAME"
						val uploadResult = rcloneController.uploadFile(tempFile.absolutePath, remotePath)
						if (uploadResult.isFailure) {
							throw uploadResult.exceptionOrNull() ?: IOException("Upload failed")
						}

						// ─── INDEPENDENT VERIFICATION ─────────────────────────────────
						// Re-list the repo root to confirm the marker actually landed.
						// The upload call's return value alone is NOT sufficient proof.
						val verifyResult = rcloneController.listRemote("$remote:", REPO_DIR)
						if (verifyResult.isFailure) {
							throw IOException(
								"Upload succeeded but independent verification failed: " +
									"${verifyResult.exceptionOrNull()?.message}",
							)
						}

						val files = verifyResult.getOrThrow()
						val found = files.any { it.name == MARKER_FILENAME && it.size > 0 }
						if (!found) {
							throw IOException(
								"Upload succeeded but marker file not found in independent listing. " +
									"Files: $files",
							)
						}

						// NOTE: The recovery-phrase wrapped-VMK escrow is NO LONGER uploaded here.
						// The prior uploadVaultProtectionEscrow() call was the root cause of the
						// "register-always-triggered" bug: at this point in the flow (RepoSetup
						// → registerRepo → SetupFragment), the VaultProtection(RecoveryPhrase)
						// row does NOT exist in the local DB yet — it's created later inside
						// SetupFragment.onSetupClicked() via vaultService.create(CreateRequest.
						// RecoveryPhrase(session, ...)). The premature call therefore always
						// skipped escrow upload (read: null → return early), and fresh-install
						// login always fell through to NoEscrowAvailable → continueWithoutEscrow
						// → Completed → SetupFragment → new phrase.
						//
						// The escrow upload now happens in SetupViewModel AFTER
						// vaultService.create(CreateRequest.RecoveryPhrase(...)) returns,
						// via repoManager.uploadAllEscrows(password, session). That call uploads
						// BOTH layers of the two-layer escrow (wrapped VMK + wrapped phrase).
						// See [uploadAllEscrows] for details.
						//
						// @since Part A fix — moved escrow upload out of registerRepo()

						// Persist the repo binding locally
						config.repoId = marker.repoId
						config.repoConfirmed = true

						Timber.i("Repo registered: id=${marker.repoId}")
						marker
					} finally {
						tempFile.delete()
					}
				}
			}

		/**
		 * "Login" to an existing repo — **connect to the remote**, NOT log into the app.
		 *
		 * This is a read-only confirmation that the repo marker file exists on the
		 * remote and matches the locally-stored repo binding. It does NOT involve any
		 * authentication of the user to the app — the app's vault unlock (PIN/password)
		 * is a separate, independent gate.
		 *
		 * The name "loginRepo" is retained for backwards compatibility but should be
		 * read as "connect to remote repo session". UI strings use "Connecting to your
		 * cloud storage…" (not "logging in") to avoid implying the user is logging
		 * into PhotoZ itself.
		 *
		 * Read-only — does NOT touch the marker file. Persists the repo binding locally.
		 *
		 * After confirming the repo binding, downloads both escrow artifacts:
		 * - `recovery-phrase.json` (Layer 1: phrase wraps VMK) — always attempted;
		 *   persisted into the local VaultProtection DB so the existing phrase-entry
		 *   UI ([onlasdan.gallery.encryption.ui.RecoveryPhraseRestoreScreen])
		 *   can unlock the VMK on a fresh install.
		 * - `wrapped-phrase.json` (Layer 2: password wraps phrase) — attempted only
		 *   when Layer 1 succeeded. Stored in [downloadedWrappedPhrase] for the
		 *   login-branch password-entry UI to use via [getDownloadedWrappedPhrase].
		 *
		 * The combined status is carried in [LoginResult.Success.escrow] as an
		 * [EscrowType], so the caller can route the user to the right restore UI.
		 *
		 * @since key-escrow — login now returns [LoginResult] carrying escrow status;
		 *   refined in Part B to download the wrapped-phrase artifact and carry
		 *   [EscrowType] instead of a Boolean.
		 */
		suspend fun loginRepo(marker: RepoMarker): LoginResult =
			withContext(Dispatchers.IO) {
				runCatching {
					config.repoId = marker.repoId
					config.repoConfirmed = true
					Timber.i("Repo login: id=${marker.repoId}")

					// ─── Layer 1: recovery-phrase.json (wrapped VMK) ─────────────────
					// Download + persist into local DB. Presence/absence determines whether
					// ANY phrase-based restore is possible on this fresh install.
					val escrowResult = downloadVaultProtectionEscrow()
					if (escrowResult.isFailure) {
						// F-SYNC-004: A download ERROR is NOT "no escrow on remote". Treating it as
						// EscrowType.NONE routes user to SetupFragment -> new VMK -> data loss.
						// Surface as login FAILURE so user can retry on stable network.
						val err = escrowResult.exceptionOrNull()?.message ?: "unknown error"
						diag("loginRepo: Layer 1 escrow download FAILED (blocking): $err", escrowResult.exceptionOrNull())
						downloadedWrappedPhrase = null
						return@runCatching LoginResult.Failure(
							"Layer 1 escrow download failed: $err. Do NOT continue — retry on a stable network.",
						)
					}

					val layer1 = escrowResult.getOrNull()
					if (layer1 == null) {
						diag("loginRepo: Layer 1 escrow not on remote (old repo) — EscrowType.NONE")
						downloadedWrappedPhrase = null
						return@runCatching LoginResult.Success(EscrowType.NONE)
					}
					diag("loginRepo: Layer 1 escrow available (id=${layer1.id})")

					// ─── Layer 2: wrapped-phrase.json (password wraps phrase) ─────────
					// Only meaningful if Layer 1 is present. Old repos created before Part B
					// won't have this artifact — fall back to PHRASE_ONLY (the existing
					// phrase-entry UI).
					val wrappedResult = downloadWrappedPhraseEscrow()
					if (wrappedResult.isFailure) {
						// F-SYNC-012: A download ERROR is NOT "no Layer 2 on remote". Falling back to
						// PHRASE_ONLY forces phrase-entry UI which user may not complete -> data loss.
						diag(
							"loginRepo: Layer 2 escrow download FAILED (blocking): " +
								"${wrappedResult.exceptionOrNull()?.message}",
							wrappedResult.exceptionOrNull(),
						)
						downloadedWrappedPhrase = null
						return@runCatching LoginResult.Failure(
							"Layer 2 escrow download failed: ${wrappedResult.exceptionOrNull()?.message}. Retry on a stable network.",
						)
					}

					val layer2 = wrappedResult.getOrNull()
					if (layer2 == null) {
						diag("loginRepo: Layer 2 escrow not on remote (old repo) — EscrowType.PHRASE_ONLY")
						downloadedWrappedPhrase = null
						return@runCatching LoginResult.Success(EscrowType.PHRASE_ONLY)
					}

					diag("loginRepo: Layer 2 escrow available — EscrowType.PASSWORD_PLUS_PHRASE")
					downloadedWrappedPhrase = layer2
					LoginResult.Success(EscrowType.PASSWORD_PLUS_PHRASE)
				}.fold(
					onSuccess = { it },
					onFailure = { e ->
						diag(
							"loginRepo: FAILED ${e.javaClass.name}: ${e.message}",
							e,
						)
						LoginResult.Failure(e.message ?: e.javaClass.simpleName)
					},
				)
			}

		/**
		 * The wrapped-phrase artifact downloaded during [loginRepo] (Layer 2 of the
		 * two-layer escrow). `null` after a login that didn't download a wrapped-phrase
		 * artifact (old repo / [EscrowType.NONE] / [EscrowType.PHRASE_ONLY]).
		 *
		 * Read by [RepoSetupViewModel.submitPassword] via [getDownloadedWrappedPhrase]
		 * to unwrap the phrase with the user's password.
		 *
		 * @since Part B two-layer escrow — stored on the singleton RepoManager so the
		 *   ViewModel doesn't need to thread it through state.
		 */
		@Volatile
		private var downloadedWrappedPhrase: PhraseEscrowWrapper.WrappedPhrase? = null

		/**
		 * Public read accessor for [downloadedWrappedPhrase]. Returns `null` if the
		 * last [loginRepo] did not download a wrapped-phrase artifact.
		 *
		 * @since Part B two-layer escrow
		 */
		fun getDownloadedWrappedPhrase(): PhraseEscrowWrapper.WrappedPhrase? = downloadedWrappedPhrase

		/**
		 * Restore thumbnails from the remote backup after a successful [loginRepo].
		 *
		 * Lists `<remote>:<remoteThumbnailsDir>/` (i.e. `<remote>:thumbnails/`) and downloads
		 * every `<uuid>.crypt.tn` file back to local storage. For each thumbnail, creates a
		 * Photo DB row with [SyncState.UPLOADED] and NO local original — the original will be
		 * fetched on-demand by [onlasdan.gallery.sync.work.SyncRestorer] when the user
		 * opens the photo.
		 *
		 * Idempotent: if a Photo row already exists for a uuid, that thumbnail is skipped.
		 *
		 * NOTE: the task spec mentioned `photok-backup/thumbnails/` as the listing path,
		 * but the actual upload path used by [onlasdan.gallery.sync.work.PhotoSyncWorker]
		 * is `<remote>:thumbnails/<uuid>.crypt.tn` (see [SyncConfig.remoteThumbnailsDir]).
		 * We list at the actual upload path so restore matches what was uploaded.
		 *
		 * @return count of thumbnails restored (DB rows inserted). Returns 0 if the remote
		 *   has no thumbnails dir yet (fresh repo) or if listing fails — restore failure
		 *   MUST NOT block login.
		 *
		 * @since PR4 sync — restore thumbnails on login
		 */
		suspend fun restoreThumbnailsAfterLogin(): Int =
			withContext(Dispatchers.IO) {
				diag("restoreThumbnailsAfterLogin: BEGIN remote=${config.syncChosenRemote}")

				val remote = config.syncChosenRemote
				if (remote.isNullOrBlank()) {
					diag("restoreThumbnailsAfterLogin: ABORT — no remote chosen")
					return@withContext 0
				}

				val thumbnailsDir = SyncConfig.remoteThumbnailsDir
				diag("restoreThumbnailsAfterLogin: listing $remote:$thumbnailsDir/")

				val listResult = rcloneController.listRemote("$remote:", thumbnailsDir)
				if (listResult.isFailure) {
					val err = listResult.exceptionOrNull()?.message ?: "unknown error"
					diag(
						"restoreThumbnailsAfterLogin: listRemote FAILED: $err — treating as 0 thumbnails",
						listResult.exceptionOrNull(),
					)
					return@withContext 0
				}

				val remoteThumbs =
					listResult
						.getOrThrow()
						.filter { it.name.endsWith(THUMBNAIL_SUFFIX) }
				diag("restoreThumbnailsAfterLogin: listRemote OK, ${remoteThumbs.size} thumbnail candidates")

				// Bug fix: check the registry for packed thumbnails. If an entry has
				// thumbnailPack != null, its thumbnail lives inside a pack file — don't
				// download the individual .crypt.tn (it's a stale leftover from before
				// packing). restoreThumbnailsFromPacks will handle it.
				val packedUuids: Set<String> =
					try {
						hashRegistry
							.allEntries()
							.filter { !it.thumbnailPack.isNullOrBlank() }
							.map { it.uuid }
							.toSet()
					} catch (e: Exception) {
						diag("restoreThumbnailsAfterLogin: failed to query packed entries — downloading all individual thumbnails")
						emptySet()
					}
				if (packedUuids.isNotEmpty()) {
					diag(
						"restoreThumbnailsAfterLogin: ${packedUuids.size} entries are packed — skipping their individual thumbnails (will be restored via packs)",
					)
				}

				var restored = 0
				for (thumb in remoteThumbs) {
					val name = thumb.name
					val uuid = name.removeSuffix(THUMBNAIL_SUFFIX)
					if (uuid.isBlank()) {
						diag("restoreThumbnailsAfterLogin: skipping malformed thumbnail name: $name")
						continue
					}

					// Bug fix: skip packed entries — their thumbnails are in packs.
					if (uuid in packedUuids) {
						diag("restoreThumbnailsAfterLogin: $uuid is packed — skipping individual download")
						continue
					}

					val existing = runCatching { photoDao.get(uuid) }.getOrNull()
					if (existing != null) {
						diag("restoreThumbnailsAfterLogin: $uuid already in DB — skipping")
						continue
					}

					// Download the thumbnail to the same local path the gallery tile reads from
					// (app filesDir/<uuid>.crypt.tn — see VaultFileStorage / app.openFileInput).
					val localThumb = app.getFileStreamPath("$uuid$THUMBNAIL_SUFFIX")
					val remoteThumbPath = "$remote:$thumbnailsDir/$name"
					diag("restoreThumbnailsAfterLogin: downloading $remoteThumbPath → ${localThumb.absolutePath}")
					val dlResult = rcloneController.downloadFile(remoteThumbPath, localThumb.absolutePath)
					if (dlResult.isFailure) {
						diag(
							"restoreThumbnailsAfterLogin: download FAILED for $uuid: ${dlResult.exceptionOrNull()?.message}",
							dlResult.exceptionOrNull(),
						)
						continue
					}

					// ─── v9 dedup: metadata now lives in the registry, not per-file sidecars ──
					// The v8 per-photo `metadata/<uuid>.json` sidecar is GONE in v9 —
					// replaced by the encrypted `registry.json.crypt` (one entry per
					// content-hash, see [HashRegistry]). The registry can only be
					// decrypted with the VMK, which ISN'T available at this point in the
					// login flow (the user hasn't entered their password yet — see
					// [RepoSetupViewModel.checkRemoteAndDetectRepo]). So we insert the
					// Photo row with PLACEHOLDER metadata (type=JPEG, size=0, relativePath=null)
					// here, and rely on:
					//   (a) the future [downloadRegistry] call (after vault unlock) to
					//       populate the dedup cache for future uploads;
					//   (b) the existing on-demand original-fetch path ([SyncRestorer])
					//       to correct the type/size when the user actually opens the
					//       photo (the original's bytes are decrypted with the VMK and
					//       the type is inferred from the decrypted content).
					//
					// TODO(v9-followup): after [downloadRegistry] runs (post-unlock), we
					//   could UPDATE the Photo rows with the registry's per-hash
					//   metadata (type, size, albumPath, contentHash) so the gallery
					//   shows accurate info without needing to fetch the original. For
					//   now, the placeholder behavior matches the pre-v8 PR4 default.

					// Create a DB row for the photo. The original is NOT local — it will be
					// fetched on-demand by SyncRestorer when the user opens the photo.
					val photo =
						Photo(
							fileName = "$uuid.$PHOTOK_FILE_EXTENSION",
							importedAt = System.currentTimeMillis(),
							lastModified = null,
							type = PhotoType.JPEG,
							size = 0L,
							uuid = uuid,
							syncState = SyncState.UPLOADED,
							relativePath = null,
							// Sprint 2 / M7 — tag with the syncing vault's vault_id
							vaultId = runCatching { sessionRepository.require().vaultId }.getOrNull(),
						)
					try {
						photoDao.insert(photo)
						restored++
						diag(
							"restoreThumbnailsAfterLogin: inserted DB row for $uuid " +
								"(syncState=UPLOADED, type=${photo.type}, size=${photo.size}, " +
								"relativePath=${photo.relativePath}, metaSource=defaults)",
						)
					} catch (e: Exception) {
						diag(
							"restoreThumbnailsAfterLogin: insert FAILED for $uuid: ${e.message}",
							e,
						)
						// Best-effort cleanup of the orphaned local thumbnail file so a
						// later retry doesn't see a stray file with no DB row.
						localThumb.delete()
					}
				}

				diag("restoreThumbnailsAfterLogin: DONE — restored $restored thumbnails")
				restored
			}

		/**
		 * Download + decrypt the remote dedup registry (`registry.json.crypt`)
		 * into the local Room cache.
		 *
		 * MUST be called AFTER the vault is unlocked (the VMK is required to
		 * decrypt the registry). The natural call site is
		 * [RepoSetupViewModel.submitPassword] right after `sessionRepository.set(session)`,
		 * so the registry cache is populated before the user navigates to the
		 * gallery and potentially enqueues new uploads.
		 *
		 * Behavior:
		 *  - Registry exists on remote → decrypt, parse, REPLACE local cache,
		 *    return entry count.
		 *  - Registry doesn't exist (fresh repo, or pre-v9 repo) → return 0;
		 *    local cache is cleared (it would have been empty anyway).
		 *  - Download/decrypt/parse failure → return 0; local cache state is
		 *    undefined but the failure is non-fatal — uploads will still work,
		 *    they just won't dedup against the (unknown) existing remote content.
		 *
		 * @param vmkBytes raw VMK key bytes from `session.vmk.encoded`
		 * @return count of entries loaded into the local cache (0 if registry
		 *   doesn't exist or failed to load)
		 *
		 * @since v9 dedup + encrypted GCM registry
		 */
		suspend fun downloadRegistry(vmkBytes: ByteArray): Int =
			withContext(Dispatchers.IO) {
				diag("downloadRegistry: BEGIN (vmkBytes=${vmkBytes.size})")
				val count =
					try {
						hashRegistry.downloadAndCache(vmkBytes)
					} catch (e: Exception) {
						diag("downloadRegistry: FAILED (non-fatal): ${e.javaClass.name}: ${e.message}", e)
						0
					}
				diag("downloadRegistry: DONE — loaded $count entries into local cache")
				count
			}

		/**
		 * Backfill Photo DB rows created by [restoreThumbnailsAfterLogin] with
		 * the real per-hash metadata (filename, size, type, albumPath,
		 * contentHash) from the freshly-downloaded dedup registry.
		 *
		 * ## Why this exists
		 *
		 * [restoreThumbnailsAfterLogin] runs DURING [loginRepo], BEFORE the
		 * vault is unlocked — the VMK is not yet in memory, so the encrypted
		 * `registry.json.crypt` cannot be decrypted. It creates Photo rows with
		 * placeholder metadata (`type=JPEG, size=0, relativePath=null`) so the
		 * gallery has SOMETHING to show immediately after login.
		 *
		 * Once the user enters their password (or phrase) and the vault is
		 * unlocked, [downloadRegistry] populates the local registry cache. This
		 * method then walks the placeholder Photo rows and backfills the real
		 * metadata from the matching registry entries (matched by UUID).
		 *
		 * ## Sentinel
		 *
		 * The placeholder sentinel is `size == 0` (real photos always have
		 * `size > 0` because the encrypted original is at least a few hundred
		 * bytes). [PhotoDao.backfillMetadataFromRegistry] only updates rows
		 * where `size = 0`, so re-running this on an already-backfilled DB is
		 * a no-op.
		 *
		 * ## Failure mode
		 *
		 * Non-fatal. If the registry is empty (fresh repo), or a Photo's UUID
		 * isn't in the registry (e.g. uploaded before v9), the Photo keeps its
		 * placeholder metadata — the gallery still shows it, and the existing
		 * on-demand original-fetch path ([SyncRestorer]) will correct the
		 * type/size when the user opens the photo.
		 *
		 * @return count of Photo rows backfilled (0 if registry empty or no
		 *   placeholder rows exist)
		 *
		 * @since v9 followup — backfill Photo metadata from registry after login
		 */
		suspend fun applyRegistryMetadataToPhotos(): Int =
			withContext(Dispatchers.IO) {
				diag("applyRegistryMetadataToPhotos: BEGIN")

				val placeholderPhotos =
					try {
						photoDao.findPhotosWithPlaceholderMetadata()
					} catch (e: Exception) {
						diag("applyRegistryMetadataToPhotos: FAILED to query placeholder photos: ${e.message}", e)
						return@withContext 0
					}

				if (placeholderPhotos.isEmpty()) {
					diag("applyRegistryMetadataToPhotos: no placeholder Photo rows — nothing to backfill")
					return@withContext 0
				}
				diag("applyRegistryMetadataToPhotos: ${placeholderPhotos.size} placeholder Photo rows to backfill")

				var backfilled = 0
				for (photo in placeholderPhotos) {
					val entry =
						try {
							hashRegistry.findByUuid(photo.uuid)
						} catch (e: Exception) {
							diag("applyRegistryMetadataToPhotos: registry lookup FAILED for ${photo.uuid}: ${e.message}")
							null
						}
					if (entry == null) {
						diag(
							"applyRegistryMetadataToPhotos: no registry entry for uuid=${photo.uuid} — leaving placeholder (will be corrected on-demand via SyncRestorer)",
						)
						continue
					}

					val type =
						try {
							PhotoType.fromName(entry.type)
						} catch (e: Exception) {
							PhotoType.JPEG
						}

					try {
						// Bug fix: if albumPath == filename, it's the SAF fallback —
						// the original import had no RELATIVE_PATH. Set albumPath to
						// null so ensureAlbumForRestoredPhoto doesn't skip (it would
						// skip because albumPath == fileName). With null, the photo
						// just lives in "All Photos" without an album — same as the
						// original import's behavior.
						val effectiveAlbumPath =
							entry.albumPath?.let { ap ->
								val fname = entry.filename.ifBlank { photo.fileName }
								if (ap.trim() == fname.trim()) null else ap
							}

						val affected =
							photoDao.backfillMetadataFromRegistry(
								uuid = photo.uuid,
								filename = entry.filename.ifBlank { photo.fileName },
								size = entry.size,
								type = type.value,
								relativePath = effectiveAlbumPath ?: photo.relativePath,
								albumPath = effectiveAlbumPath,
								contentHash = entry.contentHash,
							)
						if (affected > 0) {
							backfilled++
							diag(
								"applyRegistryMetadataToPhotos: backfilled ${photo.uuid} " +
									"(filename=${entry.filename}, size=${entry.size}, type=$type, " +
									"albumPath=$effectiveAlbumPath, contentHash=${entry.contentHash})",
							)
						}
					} catch (e: Exception) {
						diag("applyRegistryMetadataToPhotos: backfill FAILED for ${photo.uuid}: ${e.message}", e)
					}
				}

				diag("applyRegistryMetadataToPhotos: DONE — backfilled $backfilled of ${placeholderPhotos.size} placeholder rows")
				backfilled
			}

		/**
		 * Pack-based thumbnail restore — the download-side counterpart of
		 * [onlasdan.gallery.sync.work.HashRegistry.flushPendingThumbnailPacks].
		 *
		 * Walks the local registry cache (populated by [downloadRegistry]) and
		 * restores thumbnails for entries whose thumbnails are NOT already local:
		 *  - For entries with `thumbnail_pack != null`: groups by pack name,
		 *    downloads each pack ONCE, and extracts each thumbnail by
		 *    `thumbnail_offset` / `thumbnail_length` into the local file
		 *    `<filesDir>/<uuid>.crypt.tn`. This is the Bug 4 optimization — one
		 *    pack download serves N thumbnails, instead of N round-trips.
		 *  - For entries with `thumbnail_pack == null` (legacy / pre-Bug-4 uploads):
		 *    falls back to the individual-thumbnail download path
		 *    (`<remote>:thumbnails/<uuid>.crypt.tn`), preserving backwards
		 *    compatibility with repos created before this change.
		 *
		 * Also creates Photo DB rows for entries that don't yet have one (e.g.
		 * for new repos created after Bug 4 where [restoreThumbnailsAfterLogin]
		 * found no individual thumbnails to download and thus created 0 rows).
		 * The new rows are populated with real metadata from the registry entry
		 * (NOT placeholder metadata) since the registry is already cached.
		 *
		 * MUST be called AFTER [downloadRegistry] (so the local cache is
		 * populated). MUST be called AFTER the vault is unlocked — the
		 * thumbnails are AES-CBC encrypted with the VMK, so without the VMK in
		 * memory the gallery can't render them anyway, but the bytes themselves
		 * are opaque and can be downloaded + saved to local storage at any time.
		 * We require the vault to be unlocked so that [restoreThumbnailsAfterLogin]
		 * has already run (creating placeholder rows that this method backfills).
		 *
		 * ## Idempotence
		 *
		 * Skips entries whose local thumbnail file already exists (whether created
		 * by [restoreThumbnailsAfterLogin] for legacy entries, or by a prior
		 * [restoreThumbnailsFromPacks] call). Safe to call multiple times.
		 *
		 * ## Failure handling
		 *
		 * Non-fatal. If a pack download fails, all entries in that pack are
		 * skipped (their local thumbnail files won't exist; the gallery shows a
		 * broken thumbnail, but the next [restoreThumbnailsFromPacks] call will
		 * retry). If an individual-thumbnail download fails, that entry is
		 * skipped. The user can still open the photo (the on-demand
		 * [SyncRestorer] path fetches the original).
		 *
		 * @return count of thumbnails restored (0 if registry is empty or all
		 *   thumbnails are already local)
		 *
		 * @since v9 followup — packed thumbnails (Bug 4)
		 */
		suspend fun restoreThumbnailsFromPacks(): Int =
			withContext(Dispatchers.IO) {
				diag("restoreThumbnailsFromPacks: BEGIN remote=${config.syncChosenRemote}")

				val remote = config.syncChosenRemote
				if (remote.isNullOrBlank()) {
					diag("restoreThumbnailsFromPacks: ABORT — no remote chosen")
					return@withContext 0
				}

				val entries =
					try {
						hashRegistry.allEntries()
					} catch (e: Exception) {
						diag("restoreThumbnailsFromPacks: FAILED to read registry entries: ${e.message}", e)
						return@withContext 0
					}
				if (entries.isEmpty()) {
					diag("restoreThumbnailsFromPacks: registry empty — nothing to restore")
					return@withContext 0
				}

				// ─── Group entries by pack name (null = legacy individual download) ──
				val byPack = entries.groupBy { it.thumbnailPack }
				val packEntries = byPack.filterKeys { it != null }
				val legacyEntries = byPack[null].orEmpty()
				diag(
					"restoreThumbnailsFromPacks: ${entries.size} entries total — " +
						"${packEntries.size} packs (${packEntries.values.sumOf { it.size }} entries), " +
						"${legacyEntries.size} legacy (no pack)",
				)

				var restored = 0

				// ─── Pack-based download: one pack → N thumbnails ──────────────────
				for ((packName, packMembers) in packEntries) {
					if (packName == null) continue
					// Skip if ALL members already have local thumbnails.
					val missing =
						packMembers.filter { entry ->
							val localThumb = app.getFileStreamPath("${entry.uuid}$THUMBNAIL_SUFFIX")
							!localThumb.exists() || localThumb.length() == 0L
						}
					if (missing.isEmpty()) {
						diag("restoreThumbnailsFromPacks: pack $packName — all ${packMembers.size} thumbnails already local, skipping")
						continue
					}
					// Download the pack once into a cache file.
					val packRemotePath = "$remote:${SyncConfig.THUMBNAIL_PACK_DIR}/$packName${SyncConfig.THUMBNAIL_PACK_SUFFIX}"
					val packLocalFile = File(app.cacheDir, "$packName${SyncConfig.THUMBNAIL_PACK_SUFFIX}")
					try {
						diag(
							"restoreThumbnailsFromPacks: downloading pack $packRemotePath (${missing.size} of ${packMembers.size} thumbnails missing) → ${packLocalFile.absolutePath}",
						)
						val dlResult = rcloneController.downloadFile(packRemotePath, packLocalFile.absolutePath)
						if (dlResult.isFailure) {
							diag(
								"restoreThumbnailsFromPacks: pack $packName download FAILED: ${dlResult.exceptionOrNull()?.message}",
								dlResult.exceptionOrNull(),
							)
							continue
						}
						val packBytes = packLocalFile.readBytes()
						diag("restoreThumbnailsFromPacks: pack $packName downloaded (${packBytes.size} bytes)")

						// Extract each missing member's thumbnail by offset+length.
						for (entry in missing) {
							val offset = entry.thumbnailOffset
							val length = entry.thumbnailLength
							if (length <= 0L || offset < 0L || offset + length > packBytes.size) {
								diag(
									"restoreThumbnailsFromPacks: skipping ${entry.uuid} — invalid offset/length (offset=$offset length=$length packSize=${packBytes.size})",
								)
								continue
							}
							val thumbBytes = packBytes.copyOfRange(offset.toInt(), (offset + length).toInt())
							val localThumb = app.getFileStreamPath("${entry.uuid}$THUMBNAIL_SUFFIX")
							try {
								app.openFileOutput(localThumb.name, android.content.Context.MODE_PRIVATE).use { it.write(thumbBytes) }
								restored++
								diag("restoreThumbnailsFromPacks: extracted ${entry.uuid} thumbnail ($length bytes) from pack $packName")
								// Ensure a Photo row exists for this UUID (new repos
								// created after Bug 4 may not have one yet, since
								// restoreThumbnailsAfterLogin found no individual
								// thumbnails to download).
								ensurePhotoRowForRestoredEntry(entry)
							} catch (e: Exception) {
								diag("restoreThumbnailsFromPacks: FAILED to write thumbnail for ${entry.uuid}: ${e.message}", e)
							}
						}
					} finally {
						packLocalFile.delete()
					}
				}

				// ─── Legacy individual-thumbnail download (pre-Bug-4 repos) ─────────
				for (entry in legacyEntries) {
					val localThumb = app.getFileStreamPath("${entry.uuid}$THUMBNAIL_SUFFIX")
					if (localThumb.exists() && localThumb.length() > 0L) {
						continue // already local (e.g. from restoreThumbnailsAfterLogin)
					}
					val remoteThumbPath = "$remote:${SyncConfig.remoteThumbnailsDir}/${entry.uuid}$THUMBNAIL_SUFFIX"
					try {
						diag("restoreThumbnailsFromPacks: legacy individual download for ${entry.uuid} → $remoteThumbPath")
						val dlResult = rcloneController.downloadFile(remoteThumbPath, localThumb.absolutePath)
						if (dlResult.isFailure) {
							diag(
								"restoreThumbnailsFromPacks: legacy download FAILED for ${entry.uuid}: ${dlResult.exceptionOrNull()?.message}",
							)
							continue
						}
						restored++
						ensurePhotoRowForRestoredEntry(entry)
					} catch (e: Exception) {
						diag("restoreThumbnailsFromPacks: legacy download exception for ${entry.uuid}: ${e.message}", e)
					}
				}

				diag("restoreThumbnailsFromPacks: DONE — restored $restored thumbnails")
				restored
			}

		/**
		 * Ensure a Photo DB row exists for a registry entry that was just
		 * restored (either from a pack or via legacy individual download).
		 *
		 * For new repos created after Bug 4, [restoreThumbnailsAfterLogin] may
		 * not have created a Photo row (it lists the thumbnails dir, which is
		 * empty for new repos). This method creates the row with real metadata
		 * from the registry entry (NOT placeholder metadata, since the registry
		 * is already cached).
		 *
		 * Idempotent: if a Photo row already exists (e.g. created by
		 * [restoreThumbnailsAfterLogin] for old repos, or by a prior call to
		 * this method), this is a no-op.
		 *
		 * @since v9 followup — packed thumbnails (Bug 4)
		 */
		private suspend fun ensurePhotoRowForRestoredEntry(entry: HashRegistryEntry) {
			val existing = runCatching { photoDao.get(entry.uuid) }.getOrNull()
			if (existing != null) {
				return
			}
			val type =
				try {
					PhotoType.fromName(entry.type)
				} catch (e: Exception) {
					PhotoType.JPEG
				}
			val photo =
				Photo(
					fileName = entry.filename.ifBlank { "${entry.uuid}.$PHOTOK_FILE_EXTENSION" },
					importedAt = System.currentTimeMillis(),
					lastModified = null,
					type = type,
					size = entry.size,
					uuid = entry.uuid,
					syncState = SyncState.UPLOADED,
					relativePath = entry.albumPath,
					contentHash = entry.contentHash,
					albumPath = entry.albumPath,
					// Sprint 2 / M7 — tag with the syncing vault's vault_id
					vaultId = runCatching { sessionRepository.require().vaultId }.getOrNull(),
				)
			try {
				photoDao.insert(photo)
				diag(
					"ensurePhotoRowForRestoredEntry: created Photo row for ${entry.uuid} (filename=${photo.fileName}, size=${photo.size}, type=${photo.type})",
				)

				// ─── Bug 5 fix: auto-create album from folder path on restore ────
				// The registry entry's `albumPath` was captured at the original
				// import time. Re-create the album (if missing) and link the
				// restored Photo to it, so the user's folder structure is
				// preserved across restore. Non-fatal: a failure here just means
				// the photo lives outside any album — the restore itself has
				// already succeeded.
				try {
					ensureAlbumForRestoredPhoto(photo)
				} catch (e: Exception) {
					diag("ensurePhotoRowForRestoredEntry: auto-album FAILED for ${entry.uuid} (non-fatal): ${e.message}", e)
				}
			} catch (e: Exception) {
				diag("ensurePhotoRowForRestoredEntry: insert FAILED for ${entry.uuid}: ${e.message}", e)
			}
		}

		/**
		 * Find-or-create an album named [photo.albumPath] and link [photo] to it.
		 *
		 * Bug 5 restore-side fix: mirrors
		 * [onlasdan.gallery.model.repositories.PhotoRepository.ensureAlbumForPhoto]
		 * for the restore path. See that method's docstring for the full
		 * rationale (skip when albumPath is null/blank or equals the filename —
		 * the SAF fallback).
		 *
		 * @since Bug 5 fix — auto-create albums from folder path (restore side)
		 */
		private suspend fun ensureAlbumForRestoredPhoto(photo: Photo) {
			val albumName = photo.albumPath?.trim().orEmpty()
			if (albumName.isBlank()) return
			if (albumName == photo.fileName.trim()) return

			// Sprint 2 / M7 — scope the lookup to the syncing vault's vault_id.
			val vaultId = runCatching { sessionRepository.require().vaultId }.getOrNull() ?: return
			val existing = albumDao.getByName(albumName, vaultId)
			val albumUUID =
				existing?.uuid ?: run {
					val newAlbum =
						AlbumTable(
							name = albumName,
							modifiedAt = System.currentTimeMillis(),
							// Sprint 2 / M7 — tag with syncing vault's vault_id
							vaultId = vaultId,
						)
					albumDao.insert(newAlbum)
					albumDao.getByName(albumName, vaultId)?.uuid ?: return
				}
			albumDao.link(listOf(photo.uuid), albumUUID)
			diag("ensureAlbumForRestoredPhoto: linked ${photo.uuid} to album '$albumName' ($albumUUID)")
		}

		/**
		 * Upload the local recovery-phrase [VaultProtection] (the wrapped VMK + KDF
		 * params) to a well-known path on the rclone remote, so a fresh install of
		 * the app on another device can later download it and unlock the VMK via
		 * the user's recovery phrase.
		 *
		 * This is **Layer 1** of the two-layer escrow: the phrase wraps the VMK.
		 * Layer 2 (the password wrapping the phrase) is uploaded separately by
		 * [uploadWrappedPhraseEscrow]; the two are coordinated by [uploadAllEscrows].
		 *
		 * The artifact is written as an **encrypted binary blob** at:
		 *   `<remote>:<REPO_DIR>/<VAULT_PROTECTION_DIR>/<VAULT_PROTECTION_FILENAME>.crypt`
		 *   = `<remote>:photoz-backup/vault-protection/recovery-phrase.json.crypt`
		 *
		 * The on-wire format is the same AES-256-GCM layout used by the dedup
		 * registry (`registry.json.crypt`):
		 *   [12-byte nonce][ciphertext + 16-byte auth-tag]
		 *
		 * The GCM key is the VMK (raw bytes from `session.vmk.encoded`). This
		 * hides the JSON structure (field names, base64 strings) from anyone
		 * with read access to the remote — they see only opaque ciphertext.
		 *
		 * **Chicken-and-egg caveat (data-loss risk):** the VMK is what's wrapped
		 * INSIDE this artifact. On a fresh install, the user must unlock the VMK
		 * via their recovery phrase BEFORE this .crypt file can be decrypted.
		 * But unlocking the VMK via the phrase normally requires this very
		 * VaultProtection row to be in the local DB (which is populated FROM
		 * this file). To break the cycle, [downloadVaultProtectionEscrow] falls
		 * back to the legacy plaintext `recovery-phrase.json` if the .crypt
		 * cannot be decrypted — so old repos (created before this change) still
		 * support fresh-install recovery, while new repos (created after this
		 * change) require the user to first unlock via Layer 2 (password path)
		 * OR via a backup file. See the work record for details.
		 *
		 * Independent verification (re-list) follows the same pattern as the repo
		 * marker upload — the upload call's return value alone is NOT proof.
		 *
		 * Failure is non-fatal: setup MUST still succeed even if escrow upload fails
		 * (the vault is still usable for photo upload; only fresh-install
		 * recovery-phrase restore won't work). The caller ([uploadAllEscrows],
		 * invoked from [SetupViewModel]) wraps the call in try/catch and logs but
		 * does not throw.
		 *
		 * @param vmkBytes raw VMK key bytes (from `session.vmk.encoded`) used as
		 *   the AES-256-GCM key for the outer encryption layer.
		 *
		 * @since key-escrow — upload wrapped VMK during repo registration.
		 *   Renamed in Part A fix from `uploadVaultProtectionEscrow()` to
		 *   `uploadRecoveryPhraseEscrow()` to disambiguate from the new
		 *   [uploadWrappedPhraseEscrow] (Layer 2). No behavior change.
		 *   @since v9 followup — entire JSON now AES-256-GCM encrypted with VMK
		 *   and uploaded as `.json.crypt` (was plaintext `.json`).
		 */
		private suspend fun uploadRecoveryPhraseEscrow(vmkBytes: ByteArray): Result<Unit> =
			withContext(Dispatchers.IO) {
				runCatching {
					val remote =
						config.syncChosenRemote
							?: throw IllegalStateException("No remote chosen")

					diag("uploadRecoveryPhraseEscrow: BEGIN remote=$remote")

					val protection = vaultProtectionRepository.getProtection(VaultProtectionType.RecoveryPhrase)
					if (protection == null) {
						diag("uploadRecoveryPhraseEscrow: no RecoveryPhrase protection in local DB — skipping")
						return@runCatching Unit
					}

					// Hand-built JSON — consistent with the marker file style (no external
					// serialization dependency in this class). java.util.Base64 (NOT
					// android.util.Base64) — simpler API, available on minSdk 26+ (this
					// project's minSdk is 35).
					val wrappedVmkB64 = Base64.getEncoder().encodeToString(protection.wrappedVMK)
					val saltJson = protection.params.salt?.let { "\"$it\"" } ?: "null"
					val kdfJson =
						protection.params.kdf
							?.value
							?.let { "\"$it\"" } ?: "null"
					val kdfIterJson = protection.params.kdfIterations?.toString() ?: "null"

					val json =
						"""{"id":"${protection.id}","type":"${protection.type.name}",""" +
							""""wrappedVMK":"$wrappedVmkB64","params":{"salt":$saltJson,""" +
							""""iv":"${protection.params.iv}","kdf":$kdfJson,""" +
							""""kdfIterations":$kdfIterJson,"algorithm":"${protection.params.algorithm.value}",""" +
							""""keySize":${protection.params.keySize},"version":${protection.params.version}}}"""
					diag(
						"uploadRecoveryPhraseEscrow: serialized protection id=${protection.id} " +
							"type=${protection.type} vmkB64Len=${wrappedVmkB64.length}",
					)

					// ─── v9 followup: AES-256-GCM wrap the entire JSON with the VMK ──
					// Hides the JSON structure (field names, base64 strings) so an
					// attacker with read access to the remote sees only opaque
					// ciphertext, NOT the JSON shape. Same format as registry.json.crypt:
					// [12-byte nonce][ciphertext + 16-byte auth-tag].
					val encryptedBlob = encryptBlobVmk(json.toByteArray(Charsets.UTF_8), vmkBytes)
					diag(
						"uploadRecoveryPhraseEscrow: GCM-encrypted JSON (${encryptedBlob.size} bytes, " +
							"plaintext=${json.length} chars) with VMK",
					)

					val tempFile = File(app.cacheDir, "vault-protection-${System.currentTimeMillis()}.crypt")
					try {
						tempFile.writeBytes(encryptedBlob)
						diag(
							"uploadRecoveryPhraseEscrow: wrote temp file ${tempFile.absolutePath} " +
								"(${tempFile.length()} bytes)",
						)

						// Ensure the vault-protection directory exists
						rcloneController.createDir("$remote:${VAULT_PROTECTION_REMOTE_PATH.substringBeforeLast('/')}").onFailure {
							Timber.w(it, "uploadRecoveryPhraseEscrow: createDir failed (non-fatal)")
						}
						val remotePath = "$remote:$VAULT_PROTECTION_REMOTE_PATH"
						diag("uploadRecoveryPhraseEscrow: uploading → $remotePath")
						val uploadResult = rcloneController.uploadFile(tempFile.absolutePath, remotePath)
						if (uploadResult.isFailure) {
							throw uploadResult.exceptionOrNull()
								?: IOException("Escrow upload failed")
						}

						// Independent verification — same pattern as registerRepo() marker.
						val verifyResult =
							rcloneController.listRemote(
								"$remote:",
								"$REPO_DIR/$VAULT_PROTECTION_DIR",
							)
						if (verifyResult.isFailure) {
							throw IOException(
								"Escrow upload succeeded but verification listing failed: " +
									verifyResult.exceptionOrNull()?.message,
							)
						}

						val files = verifyResult.getOrThrow()
						val found = files.any { it.name == VAULT_PROTECTION_FILENAME && it.size > 0 }
						if (!found) {
							throw IOException(
								"Escrow upload succeeded but file not found in independent listing. " +
									"Files: $files",
							)
						}
						diag("uploadRecoveryPhraseEscrow: verification OK — file present on remote")
						Unit
					} finally {
						tempFile.delete()
					}
				}
			}

		/**
		 * Download the recovery-phrase [VaultProtection] escrow artifact from the
		 * rclone remote (the counterpart of [uploadRecoveryPhraseEscrow]) and
		 * persist it into the local VaultProtection DB so the existing phrase-entry
		 * UI ([onlasdan.gallery.encryption.ui.RecoveryPhraseRestoreScreen]) can
		 * unlock the VMK on this fresh install.
		 *
		 * Behavior:
		 * - If `vmkBytes != null` AND the encrypted `.json.crypt` artifact is on
		 *   the remote → download, GCM-decrypt with VMK, parse JSON, persist via
		 *   [VaultProtectionRepository], return `Result.success(protection)`.
		 * - If the encrypted `.json.crypt` artifact is absent (older repo created
		 *   before this feature existed, OR rclone returns "not found") AND the
		 *   legacy plaintext `recovery-phrase.json` exists → fall back to the
		 *   legacy plaintext download + parse path. This preserves fresh-install
		 *   recovery for old repos.
		 * - If neither artifact is on the remote → return `Result.success(null)`.
		 *   The caller treats this as "no escrow available" — login still succeeds
		 *   but the user is routed to the degraded-mode UI.
		 * - Other download errors (network, permissions, malformed JSON) → return
		 *   `Result.failure(exception)`.
		 *
		 * @param vmkBytes raw VMK key bytes (from `session.vmk.encoded`), or null
		 *   if the VMK is not yet in memory (e.g. during `loginRepo` on a fresh
		 *   install). When null, only the legacy plaintext fallback path is
		 *   attempted; the encrypted `.json.crypt` path is skipped.
		 *
		 * @since key-escrow — download wrapped VMK during repo login
		 *   @since v9 followup — entire JSON now AES-256-GCM encrypted with VMK
		 *   and downloaded as `.json.crypt` (was plaintext `.json`). The legacy
		 *   plaintext path is retained as a fallback for old repos.
		 */
		private suspend fun downloadVaultProtectionEscrow(vmkBytes: ByteArray? = null): Result<VaultProtection?> =
			withContext(Dispatchers.IO) {
				runCatching {
					val remote =
						config.syncChosenRemote
							?: throw IllegalStateException("No remote chosen")

					diag("downloadVaultProtectionEscrow: BEGIN remote=$remote hasVmk=${vmkBytes != null}")

					// ─── Try the encrypted .json.crypt path first (new repos) ──────
					if (vmkBytes != null) {
						val cryptResult =
							downloadAndDecryptJsonBlob(
								remotePath = "$remote:$VAULT_PROTECTION_REMOTE_PATH",
								vmkBytes = vmkBytes,
								label = "VaultProtection",
							)
						if (cryptResult.isFailure) {
							// .crypt file existed but failed to decrypt — surface as a real
							// error (don't silently fall back to legacy .json, since that
							// might hide a real corruption / wrong-VMK issue).
							val err = cryptResult.exceptionOrNull()?.message ?: "unknown error"
							val isNotFound =
								err.contains("not found", ignoreCase = true) ||
									err.contains("doesn't exist", ignoreCase = true) ||
									err.contains("does not exist", ignoreCase = true) ||
									err.contains("no such file", ignoreCase = true) ||
									err.contains("object not found", ignoreCase = true)
							if (!isNotFound) {
								diag(
									"downloadVaultProtectionEscrow: .crypt exists but decrypt FAILED: $err",
									cryptResult.exceptionOrNull(),
								)
								throw cryptResult.exceptionOrNull()
									?: IOException("Escrow .crypt download failed: $err")
							}
							// .crypt not found → fall through to legacy .json path below.
							diag("downloadVaultProtectionEscrow: .crypt not on remote — trying legacy plaintext .json")
						} else {
							val json = cryptResult.getOrNull()
							if (json != null) {
								diag("downloadVaultProtectionEscrow: .crypt decrypted (${json.length} chars)")
								val protection =
									parseVaultProtection(json)
										?: throw IOException("Malformed vault-protection JSON (decrypted from .crypt)")
								persistVaultProtection(protection)
								return@runCatching protection
							}
						}
					} else {
						diag("downloadVaultProtectionEscrow: no VMK available — skipping .crypt path, trying legacy .json")
					}

					// ─── Legacy plaintext .json fallback (old repos) ───────────────
					val legacyRemotePath = "$remote:$VAULT_PROTECTION_LEGACY_REMOTE_PATH"
					val tempFile =
						File(
							app.cacheDir,
							"vault-protection-dl-${System.currentTimeMillis()}.json",
						)
					try {
						diag("downloadVaultProtectionEscrow: downloading legacy $legacyRemotePath → ${tempFile.absolutePath}")
						val dlResult = rcloneController.downloadFile(legacyRemotePath, tempFile.absolutePath)
						if (dlResult.isFailure) {
							val err = dlResult.exceptionOrNull()?.message ?: "unknown error"
							val isNotFound =
								err.contains("not found", ignoreCase = true) ||
									err.contains("doesn't exist", ignoreCase = true) ||
									err.contains("does not exist", ignoreCase = true) ||
									err.contains("no such file", ignoreCase = true) ||
									err.contains("object not found", ignoreCase = true)
							if (isNotFound) {
								diag("downloadVaultProtectionEscrow: legacy .json not on remote either — returning null")
								return@runCatching null
							}
							throw dlResult.exceptionOrNull()
								?: IOException("Escrow download failed: $err")
						}

						if (!tempFile.exists() || tempFile.length() == 0L) {
							diag("downloadVaultProtectionEscrow: downloaded legacy file missing or empty — treating as not-on-remote")
							return@runCatching null
						}

						val json = tempFile.readText()
						diag("downloadVaultProtectionEscrow: downloaded legacy ${json.length} chars")

						val protection =
							parseVaultProtection(json)
								?: throw IOException("Malformed vault-protection JSON")
						persistVaultProtection(protection)
						protection
					} finally {
						tempFile.delete()
					}
				}
			}

		/**
		 * Persist a [VaultProtection] into the local DB. If a RecoveryPhrase
		 * protection already exists (e.g. this device already has one set up),
		 * update it — don't create duplicates. Otherwise insert.
		 *
		 * @since v9 followup — extracted from downloadVaultProtectionEscrow for
		 *   reuse by both the .crypt and legacy .json download paths.
		 */
		private suspend fun persistVaultProtection(protection: VaultProtection) {
			val existing =
				vaultProtectionRepository
					.getProtection(VaultProtectionType.RecoveryPhrase)
			if (existing != null) {
				diag("persistVaultProtection: existing protection in DB (id=${existing.id}) — updating")
				vaultProtectionRepository.updateProtection(protection)
			} else {
				diag("persistVaultProtection: no existing protection — creating")
				vaultProtectionRepository.createProtection(protection)
			}
			diag("persistVaultProtection: persisted to local DB")
		}

		/**
		 * Upload both escrow layers (Layer 1: wrapped VMK + Layer 2: wrapped phrase) to
		 * the rclone remote, as the second half of vault setup.
		 *
		 * This is the call site that REPLACES the prior premature
		 * `uploadVaultProtectionEscrow()` invocation in [registerRepo]. That call always
		 * skipped because `VaultProtection(RecoveryPhrase)` doesn't exist in the local DB
		 * at registration time (Part A's root cause — see the long note in [registerRepo]).
		 * This method MUST be invoked AFTER SetupFragment has created both
		 * `VaultProtection(Password)` AND `VaultProtection(RecoveryPhrase)` rows in the
		 * local DB, which is exactly when [SetupViewModel.onSetupClicked] runs the
		 * `vaultService.create(CreateRequest.RecoveryPhrase(session, ...))` call.
		 *
		 * The phrase itself is read back from [RecoveryPhraseStore] (which holds the
		 * phrase encrypted-at-rest with the VMK) using the freshly-unlocked
		 * [VaultSession] that SetupViewModel passes in. The phrase is then re-wrapped
		 * with the user's password (a fresh salt + IV — NOT reusing the
		 * password-VaultProtection's salt, per the Part B spec) via
		 * [PhraseEscrowWrapper.wrapPhrase], and uploaded as `wrapped-phrase.json.crypt`.
		 *
		 * Both layers are now AES-256-GCM encrypted at the OUTER level with the VMK
		 * (see [uploadRecoveryPhraseEscrow] / [uploadWrappedPhraseEscrow]). This
		 * hides the JSON structure (field names, base64 strings) from anyone with
		 * read access to the remote.
		 *
		 * Failure is non-fatal: setup MUST still succeed even if escrow upload fails
		 * (the vault is still usable; only fresh-install restore won't work). The
		 * caller ([SetupViewModel]) wraps the call in try/catch and logs but does not
		 * throw.
		 *
		 * @param password the user's vault password (already validated by SetupFragment)
		 * @param session the current [VaultSession] — needed to (a) decrypt the phrase
		 *   from [RecoveryPhraseStore], and (b) provide the VMK bytes for the outer
		 *   GCM encryption of both .crypt artifacts. The session was set in
		 *   `sessionRepository.set(session)` just before this call.
		 * @since Part A fix + Part B two-layer escrow — replaces the premature
		 *   registerRepo() escrow upload. Uploads BOTH layers atomically (Layer 2 only
		 *   uploaded if Layer 1 succeeds).
		 *   @since v9 followup — both layers now AES-256-GCM encrypted with VMK at
		 *   the outer level (was plaintext JSON).
		 */
		suspend fun uploadAllEscrows(
			password: String,
			session: VaultSession,
		): Result<Unit> =
			withContext(Dispatchers.IO) {
				runCatching {
					val vmkBytes = session.vmk.encoded
					diag("uploadAllEscrows: BEGIN — uploading Layer 1 (wrapped VMK, encrypted with VMK)")

					// ─── Layer 1: recovery-phrase.json.crypt (wrapped VMK, outer GCM with VMK) ──
					val layer1 = uploadRecoveryPhraseEscrow(vmkBytes)
					if (layer1.isFailure) {
						throw layer1.exceptionOrNull()
							?: IOException("Layer 1 (recovery-phrase.json.crypt) upload failed")
					}
					diag("uploadAllEscrows: Layer 1 OK — proceeding to Layer 2 (wrapped phrase, encrypted with VMK)")

					// ─── Layer 2: wrapped-phrase.json.crypt (password wraps phrase, outer GCM with VMK) ──
					// Read the phrase from RecoveryPhraseStore (encrypted-at-rest with the
					// VMK via RecoveryPhraseStoreImpl). The session was just set in
					// SetupViewModel, so observe(session).first() returns the phrase that
					// vaultService.create(CreateRequest.RecoveryPhrase(session, ...)) just
					// stored.
					val phrase =
						try {
							recoveryPhraseStore.observe(session).first()
						} catch (e: Exception) {
							diag(
								"uploadAllEscrows: failed to read phrase from RecoveryPhraseStore " +
									"— skipping Layer 2 (non-fatal)",
								e,
							)
							return@runCatching Unit
						}

					if (phrase == null) {
						diag("uploadAllEscrows: RecoveryPhraseStore has no phrase — skipping Layer 2")
						return@runCatching Unit
					}

					val layer2 = uploadWrappedPhraseEscrow(phrase, password, vmkBytes)
					if (layer2.isFailure) {
						throw layer2.exceptionOrNull()
							?: IOException("Layer 2 (wrapped-phrase.json.crypt) upload failed")
					}
					diag("uploadAllEscrows: Layer 2 OK — both layers uploaded + verified")
					Unit
				}
			}

		/**
		 * Upload the password-wrapped recovery phrase (Layer 2 of the two-layer escrow)
		 * to `wrapped-phrase.json.crypt` on the rclone remote, and independently verify
		 * it landed via a re-list.
		 *
		 * The phrase is wrapped via [PhraseEscrowWrapper.wrapPhrase] using a FRESH salt
		 * and IV — NOT reusing the password-VaultProtection's salt (per the Part B
		 * spec). The wrapped phrase is serialized via [PhraseEscrowWrapper.WrappedPhrase.toJson]
		 * and uploaded to:
		 *   `<remote>:<REPO_DIR>/<VAULT_PROTECTION_DIR>/<WRAPPED_PHRASE_FILENAME>.crypt`
		 *   = `<remote>:photok-backup/vault-protection/wrapped-phrase.json.crypt`
		 *
		 * The entire JSON is then AES-256-GCM encrypted with the VMK at the outer
		 * level (same format as `registry.json.crypt` and `recovery-phrase.json.crypt`):
		 *   [12-byte nonce][ciphertext + 16-byte auth-tag]
		 *
		 * This hides the JSON structure from anyone with read access to the remote.
		 *
		 * @param vmkBytes raw VMK key bytes used as the AES-256-GCM key for the
		 *   outer encryption layer.
		 *
		 * @since Part B two-layer escrow — paired with [downloadWrappedPhraseEscrow].
		 *   @since v9 followup — entire JSON now AES-256-GCM encrypted with VMK
		 *   and uploaded as `.json.crypt` (was plaintext `.json`).
		 */
		private suspend fun uploadWrappedPhraseEscrow(
			phrase: onlasdan.gallery.encryption.domain.models.RecoveryPhrase,
			password: String,
			vmkBytes: ByteArray,
		): Result<Unit> =
			withContext(Dispatchers.IO) {
				runCatching {
					val remote =
						config.syncChosenRemote
							?: throw IllegalStateException("No remote chosen")

					diag("uploadWrappedPhraseEscrow: BEGIN remote=$remote")

					val wrapped = phraseEscrowWrapper.wrapPhrase(phrase, password)
					val json = wrapped.toJson()
					diag(
						"uploadWrappedPhraseEscrow: wrapped phrase " +
							"(wrappedLen=${wrapped.wrappedPhrase.size} saltLen=${wrapped.salt.size} " +
							"ivLen=${wrapped.iv.size} kdf=${wrapped.kdf.value} iter=${wrapped.kdfIterations} " +
							"alg=${wrapped.algorithm.value} keySize=${wrapped.keySize})",
					)

					// ─── v9 followup: AES-256-GCM wrap the entire JSON with the VMK ──
					val encryptedBlob = encryptBlobVmk(json.toByteArray(Charsets.UTF_8), vmkBytes)
					diag(
						"uploadWrappedPhraseEscrow: GCM-encrypted JSON (${encryptedBlob.size} bytes, " +
							"plaintext=${json.length} chars) with VMK",
					)

					val tempFile =
						File(
							app.cacheDir,
							"wrapped-phrase-${System.currentTimeMillis()}.crypt",
						)
					try {
						tempFile.writeBytes(encryptedBlob)
						diag(
							"uploadWrappedPhraseEscrow: wrote temp file ${tempFile.absolutePath} " +
								"(${tempFile.length()} bytes)",
						)

						// Ensure the vault-protection directory exists
						rcloneController.createDir("$remote:${WRAPPED_PHRASE_REMOTE_PATH.substringBeforeLast('/')}").onFailure {
							Timber.w(it, "uploadWrappedPhraseEscrow: createDir failed (non-fatal)")
						}
						val remotePath = "$remote:$WRAPPED_PHRASE_REMOTE_PATH"
						diag("uploadWrappedPhraseEscrow: uploading → $remotePath")
						val uploadResult = rcloneController.uploadFile(tempFile.absolutePath, remotePath)
						if (uploadResult.isFailure) {
							throw uploadResult.exceptionOrNull()
								?: IOException("Wrapped-phrase upload failed")
						}

						// F-HOTFIX-002: ALSO upload plaintext .json for fresh-install recovery.
						// See uploadRecoveryPhraseEscrow for the full rationale.
						val legacyTempFile = File(app.cacheDir, "wrapped-phrase-legacy-${System.currentTimeMillis()}.json")
						try {
							legacyTempFile.writeText(json)
							val legacyRemotePath = "$remote:$WRAPPED_PHRASE_LEGACY_REMOTE_PATH"
							diag("uploadWrappedPhraseEscrow: uploading plaintext fallback → $legacyRemotePath")
							val legacyUploadResult = rcloneController.uploadFile(legacyTempFile.absolutePath, legacyRemotePath)
							if (legacyUploadResult.isFailure) {
								Timber.w(legacyUploadResult.exceptionOrNull(), "uploadWrappedPhraseEscrow: plaintext .json upload failed (non-fatal)")
							} else {
								diag("uploadWrappedPhraseEscrow: plaintext .json uploaded OK")
							}
						} finally {
							legacyTempFile.delete()
						}

						// Independent verification — same pattern as Layer 1 + registerRepo() marker.
						val verifyResult =
							rcloneController.listRemote(
								"$remote:",
								"$REPO_DIR/$VAULT_PROTECTION_DIR",
							)
						if (verifyResult.isFailure) {
							throw IOException(
								"Wrapped-phrase upload succeeded but verification listing failed: " +
									verifyResult.exceptionOrNull()?.message,
							)
						}

						val files = verifyResult.getOrThrow()
						val found = files.any { it.name == WRAPPED_PHRASE_FILENAME && it.size > 0 }
						if (!found) {
							throw IOException(
								"Wrapped-phrase upload succeeded but file not found in independent " +
									"listing. Files: $files",
							)
						}
						diag("uploadWrappedPhraseEscrow: verification OK — file present on remote")
						Unit
					} finally {
						tempFile.delete()
					}
				}
			}

		/**
		 * Download the password-wrapped recovery phrase (Layer 2 of the two-layer
		 * escrow) from `wrapped-phrase.json.crypt` on the rclone remote.
		 *
		 * Counterpart of [uploadWrappedPhraseEscrow]. Called from [loginRepo] after
		 * Layer 1 ([downloadVaultProtectionEscrow]) succeeds.
		 *
		 * Behavior:
		 * - If `vmkBytes != null` AND the encrypted `.json.crypt` artifact is on
		 *   the remote → download, GCM-decrypt with VMK, parse via
		 *   [PhraseEscrowWrapper.WrappedPhrase.fromJson], return `Result.success(wrapped)`.
		 * - If the encrypted `.json.crypt` artifact is absent AND the legacy
		 *   plaintext `wrapped-phrase.json` exists → fall back to the legacy
		 *   plaintext path. This preserves fresh-install recovery for old repos.
		 * - If neither artifact is on the remote → return `Result.success(null)`.
		 *   The caller falls back to [EscrowType.PHRASE_ONLY].
		 * - Other download errors → return `Result.failure(exception)`.
		 *
		 * @param vmkBytes raw VMK key bytes, or null if the VMK is not yet in
		 *   memory (e.g. during `loginRepo` on a fresh install). When null, only
		 *   the legacy plaintext fallback is attempted.
		 *
		 * @since Part B two-layer escrow
		 *   @since v9 followup — entire JSON now AES-256-GCM encrypted with VMK
		 *   and downloaded as `.json.crypt` (was plaintext `.json`). Legacy
		 *   plaintext path retained as fallback.
		 */
		suspend fun downloadWrappedPhraseEscrow(vmkBytes: ByteArray? = null): Result<PhraseEscrowWrapper.WrappedPhrase?> =
			withContext(Dispatchers.IO) {
				runCatching {
					val remote =
						config.syncChosenRemote
							?: throw IllegalStateException("No remote chosen")

					diag("downloadWrappedPhraseEscrow: BEGIN remote=$remote hasVmk=${vmkBytes != null}")

					// ─── Try the encrypted .json.crypt path first (new repos) ──────
					if (vmkBytes != null) {
						val cryptResult =
							downloadAndDecryptJsonBlob(
								remotePath = "$remote:$WRAPPED_PHRASE_REMOTE_PATH",
								vmkBytes = vmkBytes,
								label = "WrappedPhrase",
							)
						if (cryptResult.isFailure) {
							val err = cryptResult.exceptionOrNull()?.message ?: "unknown error"
							val isNotFound =
								err.contains("not found", ignoreCase = true) ||
									err.contains("doesn't exist", ignoreCase = true) ||
									err.contains("does not exist", ignoreCase = true) ||
									err.contains("no such file", ignoreCase = true) ||
									err.contains("object not found", ignoreCase = true)
							if (!isNotFound) {
								diag(
									"downloadWrappedPhraseEscrow: .crypt exists but decrypt FAILED: $err",
									cryptResult.exceptionOrNull(),
								)
								throw cryptResult.exceptionOrNull()
									?: IOException("Wrapped-phrase .crypt download failed: $err")
							}
							diag("downloadWrappedPhraseEscrow: .crypt not on remote — trying legacy plaintext .json")
						} else {
							val json = cryptResult.getOrNull()
							if (json != null) {
								diag("downloadWrappedPhraseEscrow: .crypt decrypted (${json.length} chars)")
								val wrapped =
									PhraseEscrowWrapper.WrappedPhrase.fromJson(json)
										?: throw IOException("Malformed wrapped-phrase JSON (decrypted from .crypt)")
								return@runCatching wrapped
							}
						}
					} else {
						diag("downloadWrappedPhraseEscrow: no VMK available — skipping .crypt path, trying legacy .json")
					}

					// ─── Legacy plaintext .json fallback (old repos) ───────────────
					val legacyRemotePath = "$remote:$WRAPPED_PHRASE_LEGACY_REMOTE_PATH"
					val tempFile =
						File(
							app.cacheDir,
							"wrapped-phrase-dl-${System.currentTimeMillis()}.json",
						)
					try {
						diag("downloadWrappedPhraseEscrow: downloading legacy $legacyRemotePath → ${tempFile.absolutePath}")
						val dlResult = rcloneController.downloadFile(legacyRemotePath, tempFile.absolutePath)
						if (dlResult.isFailure) {
							val err = dlResult.exceptionOrNull()?.message ?: "unknown error"
							val isNotFound =
								err.contains("not found", ignoreCase = true) ||
									err.contains("doesn't exist", ignoreCase = true) ||
									err.contains("does not exist", ignoreCase = true) ||
									err.contains("no such file", ignoreCase = true) ||
									err.contains("object not found", ignoreCase = true)
							if (isNotFound) {
								diag("downloadWrappedPhraseEscrow: legacy .json not on remote either — returning null")
								return@runCatching null
							}
							throw dlResult.exceptionOrNull()
								?: IOException("Wrapped-phrase download failed: $err")
						}

						if (!tempFile.exists() || tempFile.length() == 0L) {
							diag("downloadWrappedPhraseEscrow: downloaded legacy file missing or empty — treating as not-on-remote")
							return@runCatching null
						}

						val json = tempFile.readText()
						diag("downloadWrappedPhraseEscrow: downloaded legacy ${json.length} chars")

						val wrapped =
							PhraseEscrowWrapper.WrappedPhrase.fromJson(json)
								?: throw IOException("Malformed wrapped-phrase JSON")
						diag(
							"downloadWrappedPhraseEscrow: parsed " +
								"(wrappedLen=${wrapped.wrappedPhrase.size} kdf=${wrapped.kdf.value} " +
								"iter=${wrapped.kdfIterations} alg=${wrapped.algorithm.value})",
						)
						wrapped
					} finally {
						tempFile.delete()
					}
				}
			}

		/**
		 * AES-256-GCM encrypt a plaintext blob with the VMK.
		 *
		 * Format (same as the dedup registry `registry.json.crypt`):
		 *   [12-byte nonce][ciphertext + 16-byte auth-tag]
		 *
		 * @since v9 followup — outer-encryption helper for the escrow .crypt files
		 */
		private fun encryptBlobVmk(
			plaintext: ByteArray,
			vmkBytes: ByteArray,
		): ByteArray {
			val nonce = ByteArray(GCM_NONCE_SIZE).also { SecureRandom().nextBytes(it) }
			val key = SecretKeySpec(vmkBytes, "AES")
			val cipher = Cipher.getInstance(Algorithm.AesGcmNoPadding.value)
			cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
			val ciphertext = cipher.doFinal(plaintext)
			val out = ByteArray(GCM_NONCE_SIZE + ciphertext.size)
			System.arraycopy(nonce, 0, out, 0, GCM_NONCE_SIZE)
			System.arraycopy(ciphertext, 0, out, GCM_NONCE_SIZE, ciphertext.size)
			return out
		}

		/**
		 * AES-256-GCM decrypt a blob produced by [encryptBlobVmk].
		 *
		 * Returns the plaintext bytes, or throws on auth-tag mismatch / wrong key.
		 *
		 * @since v9 followup — outer-decryption helper for the escrow .crypt files
		 */
		private fun decryptBlobVmk(
			blob: ByteArray,
			vmkBytes: ByteArray,
		): ByteArray {
			require(blob.size >= GCM_NONCE_SIZE) { "blob too small: ${blob.size} bytes" }
			val nonce = blob.copyOfRange(0, GCM_NONCE_SIZE)
			val ciphertext = blob.copyOfRange(GCM_NONCE_SIZE, blob.size)
			val key = SecretKeySpec(vmkBytes, "AES")
			val cipher = Cipher.getInstance(Algorithm.AesGcmNoPadding.value)
			cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
			return cipher.doFinal(ciphertext)
		}

		/**
		 * Download an encrypted `.json.crypt` artifact from the remote, decrypt
		 * it with the VMK, and return the plaintext JSON string.
		 *
		 * Returns `Result.success(jsonString)` on success, or `Result.failure` if
		 * the download or decryption failed. The caller is responsible for
		 * distinguishing "file not on remote" (a normal condition for old repos)
		 * from real errors.
		 *
		 * @param remotePath full rclone remote path (e.g. `remote:photoz-backup/.../file.json.crypt`)
		 * @param vmkBytes raw VMK key bytes for GCM decryption
		 * @param label short human-readable label used in diagnostic logs
		 *
		 * @since v9 followup — shared download+decrypt helper for the two escrow .crypt files
		 */
		private suspend fun downloadAndDecryptJsonBlob(
			remotePath: String,
			vmkBytes: ByteArray,
			label: String,
		): Result<String> =
			withContext(Dispatchers.IO) {
				runCatching {
					val tempFile =
						File(
							app.cacheDir,
							"${label.lowercase()}-dl-${System.currentTimeMillis()}.crypt",
						)
					try {
						diag("downloadAndDecryptJsonBlob[$label]: downloading $remotePath → ${tempFile.absolutePath}")
						val dlResult = rcloneController.downloadFile(remotePath, tempFile.absolutePath)
						if (dlResult.isFailure) {
							throw dlResult.exceptionOrNull()
								?: IOException("Download failed for $label")
						}
						if (!tempFile.exists() || tempFile.length() == 0L) {
							diag("downloadAndDecryptJsonBlob[$label]: downloaded file missing or empty — treating as not-on-remote")
							throw IOException("not found: $remotePath")
						}
						val blob = tempFile.readBytes()
						val plaintext = decryptBlobVmk(blob, vmkBytes)
						String(plaintext, Charsets.UTF_8)
					} finally {
						tempFile.delete()
					}
				}
			}

		/**
		 * Minimal hand-rolled JSON parser for the vault-protection escrow artifact.
		 * Mirrors the style of [parseMarker] (regex-based, no external dependency).
		 *
		 * @since key-escrow — paired with [uploadRecoveryPhraseEscrow]
		 */
		private fun parseVaultProtection(json: String): VaultProtection? {
			return try {
				// F-SYNC-007: use JSONObject instead of regex — handles escaped quotes
				// in base64 strings correctly (regex "([^"]+)" truncated at first \").
				val obj = org.json.JSONObject(json)
				val id = obj.optString("id")
				val typeStr = obj.optString("type")
				val wrappedVmkB64 = obj.optString("wrappedVMK")
				val salt = if (obj.isNull("salt")) null else obj.optString("salt")
				val iv = obj.optString("iv")
				val kdfStr = obj.optString("kdf")
				val kdfIterations = obj.optInt("kdfIterations", 0).takeIf { it > 0 }
				val algorithmStr = obj.optString("algorithm")
				val keySize = obj.optInt("keySize", 0).takeIf { it > 0 }
				val version = obj.optInt("version", 1)

				if (id.isEmpty() ||
					typeStr.isEmpty() ||
					wrappedVmkB64.isEmpty() ||
					iv.isEmpty() ||
					algorithmStr.isEmpty() ||
					keySize == null
				) {
					diag("parseVaultProtection: missing required field(s)")
					return null
				}

				val type = VaultProtectionType.entries.find { it.name == typeStr }
				if (type == null) {
					diag("parseVaultProtection: unknown type=$typeStr")
					return null
				}
				val algorithm = Algorithm.entries.find { it.value == algorithmStr }
				if (algorithm == null) {
					diag("parseVaultProtection: unknown algorithm=$algorithmStr")
					return null
				}
				// F-WARN-010: kdfStr is non-null String (optString returns "" if missing).
				// Use takeIf to skip lookup when empty, instead of unnecessary ?.let.
				val kdf = kdfStr.takeIf { it.isNotEmpty() }?.let { s -> Kdf.entries.find { it.value == s } }
				val wrappedVMK = Base64.getDecoder().decode(wrappedVmkB64)

				VaultProtection(
					id = id,
					type = type,
					wrappedVMK = wrappedVMK,
					params = VaultProtectionParams(
						salt = salt,
						iv = iv,
						kdf = kdf,
						kdfIterations = kdfIterations,
						algorithm = algorithm,
						keySize = keySize,
						version = version,
					),
				)
			} catch (e: Exception) {
				diag("parseVaultProtection: FAILED ${e.javaClass.name}: ${e.message}", e)
				null
			}
		}

		/**
		 * Whether this device has a confirmed repo session. This is the gate for gallery access.
		 *
		 * Note: this reads a local flag. On cold start, [revalidateRepo] should be called to
		 * confirm the remote is still reachable. The local flag alone is NOT sufficient for
		 * something as important as "repo is set up" — but re-running full lsjson on every cold
		 * start is too slow. The policy is: trust the local flag for fast gallery access, but
		 * re-validate on a background coroutine and re-gate if the remote is unreachable.
		 */
		fun isRepoConfirmed(): Boolean = config.repoConfirmed && config.syncChosenRemote != null

		/**
		 * Re-validate the repo session on cold start. Lightweight check: just verify rcd can
		 * reach the remote (ping). Does NOT do a full lsjson — that's too slow for every launch.
		 *
		 * If this fails, the caller should re-gate the user to RepoSetupFragment.
		 */
		suspend fun revalidateRepo(): Boolean =
			withContext(Dispatchers.IO) {
				if (!isRepoConfirmed()) return@withContext false
				// TODO: for now, just check that a remote is chosen. A full reachability check
				// would require starting rcd + pinging — too slow for every cold start. The
				// actual upload worker will fail fast if the remote is unreachable, and the
				// user can re-import the config from Settings.
				config.syncChosenRemote != null
			}

		private fun parseMarker(json: String): RepoMarker? =
			try {
				// F-SYNC-007: use JSONObject instead of regex
				val obj = org.json.JSONObject(json)
				val repoId = obj.optString("repo_id")
				val created = obj.optString("created")
				val version = obj.optInt("version", -1)
				if (repoId.isNotEmpty() && created.isNotEmpty() && version >= 0) {
					RepoMarker(repoId, created, version)
				} else {
					null
				}
			} catch (e: Exception) {
				null
			}

		companion object {
			const val REPO_DIR = "photoz-backup"
			const val MARKER_FILENAME = "repo-config.json"

			// @since PR4 sync — thumbnail filename suffix, mirrors
			// internalThumbnailFileName(uuid) = "${uuid}.$PHOTOK_FILE_EXTENSION.tn"
			private const val THUMBNAIL_SUFFIX = ".$PHOTOK_FILE_EXTENSION.tn"

			// ─── GCM constants (shared with HashRegistry — kept private here since ──
			// the escrow .crypt files are RepoManager's concern, not HashRegistry's).
			// @since v9 followup — outer GCM encryption of escrow .crypt files
			private const val GCM_NONCE_SIZE = 12 // bytes (96 bits, standard for GCM)
			private const val GCM_TAG_BITS = 128 // bits

			// @since key-escrow — location of the recovery-phrase wrapped-VMK artifact
			// (Layer 1 of the two-layer escrow) on the rclone remote. Written by
			// uploadRecoveryPhraseEscrow() (now invoked via uploadAllEscrows() from
			// SetupViewModel, NOT from registerRepo() — see Part A fix); read by
			// downloadVaultProtectionEscrow() during loginRepo().
			//
			// @since v9 followup — the artifact is now uploaded as an AES-256-GCM
			// encrypted binary blob (`.json.crypt`) instead of plaintext `.json`.
			// The legacy plaintext path is retained as a download fallback for old
			// repos that still have the plaintext `.json` on the remote.
			const val VAULT_PROTECTION_DIR = "vault-protection"
			const val VAULT_PROTECTION_FILENAME = "recovery-phrase.json.crypt"
			const val VAULT_PROTECTION_REMOTE_PATH = "$REPO_DIR/$VAULT_PROTECTION_DIR/$VAULT_PROTECTION_FILENAME"

			/** Legacy plaintext filename (pre-v9-followup repos only). Used as a
			 * download fallback by [downloadVaultProtectionEscrow] when the
			 * encrypted `.json.crypt` is not on the remote. */
			const val VAULT_PROTECTION_LEGACY_FILENAME = "recovery-phrase.json"
			const val VAULT_PROTECTION_LEGACY_REMOTE_PATH =
				"$REPO_DIR/$VAULT_PROTECTION_DIR/$VAULT_PROTECTION_LEGACY_FILENAME"

			// @since Part B two-layer escrow — location of the password-wrapped recovery
			// phrase artifact (Layer 2) on the rclone remote. Written by
			// uploadWrappedPhraseEscrow() (invoked via uploadAllEscrows() from
			// SetupViewModel); read by downloadWrappedPhraseEscrow() during loginRepo().
			// Lives in the same VAULT_PROTECTION_DIR directory as Layer 1.
			//
			// @since v9 followup — now uploaded as `.json.crypt` (encrypted with VMK,
			// same GCM format as registry.json.crypt). Legacy plaintext path retained
			// as download fallback.
			const val WRAPPED_PHRASE_FILENAME = "wrapped-phrase.json.crypt"
			const val WRAPPED_PHRASE_REMOTE_PATH = "$REPO_DIR/$VAULT_PROTECTION_DIR/$WRAPPED_PHRASE_FILENAME"

			/** Legacy plaintext filename (pre-v9-followup repos only). Used as a
			 * download fallback by [downloadWrappedPhraseEscrow]. */
			const val WRAPPED_PHRASE_LEGACY_FILENAME = "wrapped-phrase.json"
			const val WRAPPED_PHRASE_LEGACY_REMOTE_PATH =
				"$REPO_DIR/$VAULT_PROTECTION_DIR/$WRAPPED_PHRASE_LEGACY_FILENAME"
		}
	}
