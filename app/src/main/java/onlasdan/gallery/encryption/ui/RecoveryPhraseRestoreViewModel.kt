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

package onlasdan.gallery.encryption.ui

import android.content.res.Resources
import android.net.Uri
import androidx.compose.ui.platform.Clipboard
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import onlasdan.gallery.R
import onlasdan.gallery.encryption.domain.SessionRepository
import onlasdan.gallery.settings.data.Config
import onlasdan.gallery.encryption.domain.VaultService
import onlasdan.gallery.encryption.domain.models.RecoveryPhrase
import onlasdan.gallery.encryption.domain.models.UnlockRequest
import onlasdan.gallery.io.IO
import onlasdan.gallery.sync.rclone.RepoManager
import timber.log.Timber
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

// 24 words × ~10 chars + separators — anything larger is not a phrase file.
private const val MAX_PHRASE_FILE_BYTES = 512

data class RecoveryPhraseRestoreUiState(
	val phrase: RecoveryPhrase = RecoveryPhrase(emptyList()),
	val loading: Boolean = false,
	val error: String? = null,
	val unlocked: Boolean = false,
	val selectedRestoreMethod: RestoreMethod? = null,
	val restoreSupportingText: String? = null,
	val phraseValid: Boolean = false,
) {
	enum class RestoreMethod {
		TypeByHand,
		PasteFromClipboard,
		ScanQrCode,
		LoadFromFile,
	}

	data class Inputs(
		val phrase: RecoveryPhrase = RecoveryPhrase(emptyList()),
		val loading: Boolean = false,
		val error: String? = null,
		val selectedRestoreMethod: RestoreMethod? = null,
		val restoreSupportingText: String? = null,
		val unlocked: Boolean = false,
	)
}

sealed interface RecoveryPhraseRestoreUiEvent {
	data class Unlock(
		val phrase: RecoveryPhrase,
	) : RecoveryPhraseRestoreUiEvent

	data class UpdatePhrase(
		val phrase: RecoveryPhrase,
	) : RecoveryPhraseRestoreUiEvent

	data object TypeByHand : RecoveryPhraseRestoreUiEvent

	data object ScanQrCode : RecoveryPhraseRestoreUiEvent

	data class QrScanned(
		val raw: String,
	) : RecoveryPhraseRestoreUiEvent

	data class PasteFromClipboard(
		val clipboard: Clipboard,
	) : RecoveryPhraseRestoreUiEvent

	data class LoadFromFile(
		val uri: Uri,
	) : RecoveryPhraseRestoreUiEvent

	data object ResetInputs : RecoveryPhraseRestoreUiEvent
}

@HiltViewModel
class RecoveryPhraseRestoreViewModel
	@Inject
	constructor(
		private val resources: Resources,
		private val sessionRepository: SessionRepository,
		private val vaultService: VaultService,
		private val io: IO,
		// @since v9 followup — needed to download + cache the dedup registry and
		// backfill Photo metadata after the user successfully enters their
		// recovery phrase and the vault is unlocked. The NeedsPhraseEntry login
		// branch (older repos with only Layer 1 escrow) goes through this VM, so
		// without this hook the registry would never be downloaded and the
		// gallery would show placeholder metadata ("metadata hilang saat
		// reinstall") for users on the phrase-only path.
		private val repoManager: RepoManager,
		private val config: Config,
	) : ViewModel() {
		private val inputs = MutableStateFlow(RecoveryPhraseRestoreUiState.Inputs())

		val uiState =
			inputs
				.map { inputs ->
					RecoveryPhraseRestoreUiState(
						phrase = inputs.phrase,
						loading = inputs.loading,
						error = inputs.error,
						unlocked = inputs.unlocked,
						selectedRestoreMethod = inputs.selectedRestoreMethod,
						restoreSupportingText = inputs.restoreSupportingText,
						phraseValid = inputs.phrase.validate(),
					)
				}.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), RecoveryPhraseRestoreUiState())

		fun handleUiEvent(event: RecoveryPhraseRestoreUiEvent) {
			if (inputs.value.unlocked) return

			when (event) {
				is RecoveryPhraseRestoreUiEvent.Unlock -> {
					inputs.update {
						it.copy(
							loading = true,
							error = null,
						)
					}

					viewModelScope.launch {
						delay(1.seconds)
						vaultService
							.unlock(UnlockRequest.RecoveryPhrase(event.phrase))
							.onSuccess { session ->
								sessionRepository.set(session)

								// ANTI-DATA-LOSS: set pendingPasswordSetup flag so SetupFragment
								// knows to wrap the existing VMK with the user's password
								// (createPasswordProtectionFromSession) instead of generating
								// a new VMK. Without this, the next app open would see
								// canUnlock()=false -> SETUP -> new VMK -> all photos undecryptable.
								config.pendingPasswordSetup = true

								// ─── v9 followup: download registry + backfill Photo metadata ──
								// Mirrors [RepoSetupViewModel.submitPassword]'s post-unlock
								// hook. The registry cannot be decrypted until the VMK is in
								// memory (just set above), so [RepoManager.loginRepo]'s
								// restoreThumbnailsAfterLogin left Photo rows with placeholder
								// metadata. Now that the VMK is available, download the
								// registry into the local cache and walk the placeholder
								// Photo rows to backfill real metadata (filename, size, type,
								// albumPath, contentHash) from the matching registry entries.
								//
								// Also runs the pack-based thumbnail restore (Bug 4): for
								// repos created after Bug 4, individual thumbnails are NOT
								// uploaded (only packs). restoreThumbnailsFromPacks downloads
								// packs and extracts thumbnails by offset+length.
								//
								// Non-fatal: failure here leaves the placeholder metadata in
								// place — the gallery still shows the photos, and the on-demand
								// original-fetch path ([SyncRestorer]) will correct the type/
								// size when the user opens each photo.
								try {
									val loaded = repoManager.downloadRegistry(session.vmk.encoded)
									android.util.Log.e(
										"RcloneDiag",
										"RecoveryPhraseRestore: downloadRegistry loaded $loaded entries",
									)
									val backfilled = repoManager.applyRegistryMetadataToPhotos()
									android.util.Log.e(
										"RcloneDiag",
										"RecoveryPhraseRestore: applyRegistryMetadataToPhotos backfilled $backfilled rows",
									)
									val packRestored = repoManager.restoreThumbnailsFromPacks()
									android.util.Log.e(
										"RcloneDiag",
										"RecoveryPhraseRestore: restoreThumbnailsFromPacks restored $packRestored thumbnails",
									)
								} catch (e: Exception) {
									android.util.Log.e(
										"RcloneDiag",
										"RecoveryPhraseRestore: registry download/backfill FAILED (non-fatal): ${e.message}",
										e,
									)
									Timber.w(e, "RecoveryPhraseRestore: registry download/backfill failed (non-fatal)")
								}

								inputs.update {
									it.copy(
										unlocked = true,
										restoreSupportingText = null,
									)
								}
							}.onFailure {
								inputs.update {
									it.copy(
										loading = false,
										error = resources.getString(R.string.recovery_phrase_restore_error_generic),
									)
								}
							}
					}
				}

				is RecoveryPhraseRestoreUiEvent.TypeByHand -> {
					inputs.update {
						it.copy(
							selectedRestoreMethod = RecoveryPhraseRestoreUiState.RestoreMethod.TypeByHand,
							phrase = RecoveryPhrase(),
							error = null,
							restoreSupportingText = null,
						)
					}
				}

				is RecoveryPhraseRestoreUiEvent.ScanQrCode -> {
					inputs.update {
						it.copy(
							selectedRestoreMethod = RecoveryPhraseRestoreUiState.RestoreMethod.ScanQrCode,
							phrase = RecoveryPhrase(),
							error = null,
							restoreSupportingText = null,
						)
					}
				}

				is RecoveryPhraseRestoreUiEvent.QrScanned -> {
					val phrase = RecoveryPhrase.from(event.raw)
					if (phrase.validate()) {
						inputs.update {
							it.copy(
								phrase = phrase,
								error = null,
								restoreSupportingText = resources.getString(R.string.recovery_phrase_restore_source_qr),
							)
						}
					} else {
						inputs.update {
							it.copy(
								error = resources.getString(R.string.recovery_phrase_restore_error_invalid_qr),
								restoreSupportingText = null,
							)
						}
					}
				}

				is RecoveryPhraseRestoreUiEvent.LoadFromFile ->
					viewModelScope.launch(IO) {
						io.openFileInput(event.uri)?.use { inputStream ->
							val filename = io.getFileName(event.uri)

							// A recovery phrase file is always tiny; cap to prevent OOM from a malicious file.
							val buffer = ByteArray(MAX_PHRASE_FILE_BYTES)
							val bytesRead = inputStream.read(buffer).coerceAtLeast(0)

							val phrase = RecoveryPhrase.from(String(buffer, 0, bytesRead))

							if (phrase.validate()) {
								inputs.update {
									it.copy(
										phrase = phrase,
										selectedRestoreMethod = RecoveryPhraseRestoreUiState.RestoreMethod.LoadFromFile,
										error = null,
										restoreSupportingText = resources.getString(R.string.recovery_phrase_restore_source_file, filename),
									)
								}
							} else {
								inputs.update {
									it.copy(
										error = resources.getString(R.string.recovery_phrase_restore_error_invalid),
										restoreSupportingText = null,
									)
								}
							}
						}
					}

				is RecoveryPhraseRestoreUiEvent.PasteFromClipboard ->
					viewModelScope.launch {
						val clipEntry = event.clipboard.getClipEntry()
						clipEntry ?: return@launch
						if (clipEntry.clipData.itemCount < 1) {
							return@launch
						}

						val item = clipEntry.clipData.getItemAt(0)
						val pastedText = item.text.toString()
						val phrase = RecoveryPhrase.from(pastedText.cleanupRawInput())

						if (phrase.validate()) {
							inputs.update {
								it.copy(
									phrase = phrase,
									selectedRestoreMethod = RecoveryPhraseRestoreUiState.RestoreMethod.PasteFromClipboard,
									error = null,
									restoreSupportingText = resources.getString(R.string.recovery_phrase_restore_source_clipboard),
								)
							}
						} else {
							inputs.update {
								it.copy(
									error = resources.getString(R.string.recovery_phrase_restore_error_invalid),
									restoreSupportingText = null,
								)
							}
						}
					}

				is RecoveryPhraseRestoreUiEvent.UpdatePhrase ->
					inputs.update {
						it.copy(phrase = event.phrase)
					}

				RecoveryPhraseRestoreUiEvent.ResetInputs ->
					inputs.update {
						RecoveryPhraseRestoreUiState.Inputs()
					}
			}
		}
	}
