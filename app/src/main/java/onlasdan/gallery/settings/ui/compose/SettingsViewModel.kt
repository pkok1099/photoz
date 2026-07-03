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

package onlasdan.gallery.settings.ui.compose

import android.app.Application
import android.net.Uri
import android.text.format.Formatter
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import onlasdan.gallery.BaseApplication
import onlasdan.gallery.R
import onlasdan.gallery.encryption.domain.SessionRepository
import onlasdan.gallery.encryption.domain.VaultService
import onlasdan.gallery.encryption.domain.models.CreateRequest
import onlasdan.gallery.settings.ui.SettingsFragment
import onlasdan.gallery.encryption.domain.models.VaultProtectionType
import onlasdan.gallery.encryption.ui.UserCanceledBiometricsException
import onlasdan.gallery.gallery.albums.domain.AlbumRepository
import onlasdan.gallery.model.repositories.PhotoRepository
import onlasdan.gallery.other.extensions.areBiometricsAvailable
import onlasdan.gallery.settings.data.Config
import onlasdan.gallery.settings.domain.Preference
import onlasdan.gallery.settings.domain.PreferenceScreenConfig
import onlasdan.gallery.settings.domain.PreferenceScreenConfigContent
import onlasdan.gallery.settings.domain.models.SettingsEnum
import onlasdan.gallery.sync.domain.SyncState
import onlasdan.gallery.sync.rclone.RcloneConfigManager
import onlasdan.gallery.sync.work.HashRegistry
import onlasdan.gallery.uicomponnets.Dialogs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject

data class SettingsUiState(
    val screenConfig: PreferenceScreenConfig = PreferenceScreenConfig(PreferenceScreenConfigContent),
    val preferencesValues: Map<String, *> = emptyMap<String, String>(),
    /**
     * Runtime-computed subtitles for `Preference.Info` rows, keyed by the
     * preference's `key`. Currently populated for the three Storage rows
     * (originals / thumbnails / photos) by [SettingsViewModel.refreshStorageStats].
     *
     * @since Item 4 — storage analytics
     */
    val infoSummaries: Map<String, String> = emptyMap(),
    /**
     * Live count of photos currently in the trash (deleted_at > 0). `null`
     * while the first DB read is in flight. Drives the subtitle of the
     * "Trash" row in Settings.
     *
     * @since v10 recycle bin
     */
    val trashCount: Int? = null,
)

sealed interface SettingsUiEvent {
    data class OnPreferenceClick(val preference: Preference, val value: Any?) : SettingsUiEvent
}

/**
 * State of the rclone cloud-sync config, surfaced to the Settings UI.
 * @since PR1 sync addendum (Settings UI)
 */
sealed interface SyncConfigStatus {
    data object NotConfigured : SyncConfigStatus
    data object Validating : SyncConfigStatus
    data class Configured(val remoteName: String) : SyncConfigStatus
    data class Invalid(val reason: RcloneConfigManager.Status.InvalidReason) : SyncConfigStatus
    data class ImportFailed(val reason: RcloneConfigManager.Status.InvalidReason) : SyncConfigStatus
    data class AwaitingRemoteChoice(val remotes: List<RcloneConfigManager.RemoteInfo>) : SyncConfigStatus
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val config: Config,
    private val app: Application,
    private val photoRepository: PhotoRepository,
    private val albumRepository: AlbumRepository,
    private val vaultService: VaultService,
    private val sessionRepository: SessionRepository,
    private val rcloneConfigManager: RcloneConfigManager,
    // @since registry-gc feature — used by the "Clean up backup" action to
    // run HashRegistry.gcThumbnailPacks() + gcOriginals(). Already
    // Hilt-provided (HashRegistry has @Singleton @Inject constructor).
    private val hashRegistry: HashRegistry,
) : ViewModel() {

    private val infoSummariesFlow = MutableStateFlow<Map<String, String>>(emptyMap())
    val infoSummaries: StateFlow<Map<String, String>> = infoSummariesFlow.asStateFlow()

    private val _trashCount = MutableStateFlow<Int?>(null)
    val trashCount: StateFlow<Int?> = _trashCount.asStateFlow()

    val uiState = combine(
        config.valuesFlow,
        infoSummariesFlow,
        _trashCount,
    ) { values, infoSummaries, trashCount ->
        SettingsUiState(
            screenConfig = PreferenceScreenConfig(PreferenceScreenConfigContent),
            preferencesValues = values,
            infoSummaries = infoSummaries,
            trashCount = trashCount,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(),
        SettingsUiState(
            preferencesValues = config.values,
            infoSummaries = infoSummariesFlow.value,
            trashCount = _trashCount.value,
        ),
    )

    /**
     * Current rclone config status, drives the subtitle of the "Cloud Sync" settings row.
     * @since PR1 sync addendum (Settings UI)
     */
    private val _syncConfigStatus = MutableStateFlow<SyncConfigStatus>(SyncConfigStatus.NotConfigured)
    val syncConfigStatus: StateFlow<SyncConfigStatus> = _syncConfigStatus.asStateFlow()

    init {
        refreshSyncConfigStatus()
        refreshStorageStats()
        refreshTrashCount()
    }

    private fun refreshSyncConfigStatus() {
        viewModelScope.launch {
            _syncConfigStatus.value = when (val s = rcloneConfigManager.currentStatus()) {
                is RcloneConfigManager.Status.NotConfigured -> SyncConfigStatus.NotConfigured
                is RcloneConfigManager.Status.Configured -> SyncConfigStatus.Configured(s.remoteName)
                is RcloneConfigManager.Status.AwaitingRemoteChoice ->
                    SyncConfigStatus.AwaitingRemoteChoice(s.availableRemotes)
                is RcloneConfigManager.Status.Invalid -> SyncConfigStatus.Invalid(s.reason)
            }
        }
    }

    fun importRcloneConfig(uri: Uri) {
        viewModelScope.launch {
            _syncConfigStatus.value = SyncConfigStatus.Validating
            val result = rcloneConfigManager.import(uri)

            result.fold(
                onSuccess = {
                    refreshSyncConfigStatus()
                    if (_syncConfigStatus.value is SyncConfigStatus.Configured) {
                        Dialogs.showLongToast(app, app.getString(R.string.sync_settings_import_success))
                    }
                },
                onFailure = { e ->
                    val reason = rcloneConfigManager.toInvalidReason(e)
                    _syncConfigStatus.value = SyncConfigStatus.ImportFailed(reason)
                    val toastResId = when (reason) {
                        RcloneConfigManager.Status.InvalidReason.NO_SECTIONS ->
                            R.string.settings_cloud_sync_invalid_no_sections
                        RcloneConfigManager.Status.InvalidReason.UNREADABLE ->
                            R.string.sync_settings_import_io_error
                    }
                    Dialogs.showLongToast(app, app.getString(toastResId))
                }
            )
        }
    }

    fun chooseRemote(name: String) {
        viewModelScope.launch {
            val ok = rcloneConfigManager.chooseRemote(name)
            if (ok) {
                refreshSyncConfigStatus()
                Dialogs.showLongToast(app, app.getString(R.string.sync_settings_import_success))
            } else {
                refreshSyncConfigStatus()
                Dialogs.showLongToast(app, app.getString(R.string.sync_settings_choose_remote_failed))
            }
        }
    }

    suspend fun rcloneConfigManagerAvailableRemotes(): List<RcloneConfigManager.RemoteInfo> =
        rcloneConfigManager.availableRemotes()

    fun handleUiEvent(event: SettingsUiEvent) {
        when (event) {
            is SettingsUiEvent.OnPreferenceClick -> {
                val proceed = callbacks[event.preference.key]?.invoke(event.value) ?: true

                if (!proceed) {
                    return
                }

                when (event.preference) {

                    is Preference.Enum<*> -> config.putString(event.preference.key, (event.value as SettingsEnum).value)
                    is Preference.Switch -> config.putBoolean(event.preference.key, event.value as Boolean)
                    is Preference.Simple -> Unit
                    is Preference.DynamicSummary -> Unit
                    is Preference.Info -> Unit
                    else -> Unit
                }
            }
        }
    }


    fun registerPreferenceCallback(key: String, callback: (value: Any?) -> Boolean) {
        callbacks[key] = callback
    }

    private val callbacks: MutableMap<String, (value: Any?) -> Boolean> = mutableMapOf()

    fun onBiometricUnlockChanged(value: Any?, fragment: Fragment): Boolean {
        value as Boolean

        if (!value) {
            viewModelScope.launch {
                vaultService.reset(VaultProtectionType.Biometric)
            }
            config.biometricAuthenticationEnabled = false
            return false
        }

        val context = fragment.context ?: return false

        if (!context.areBiometricsAvailable()) {
            Dialogs.showLongToast(
                context,
                context.getString(R.string.settings_security_biometric_not_available),
            )
            return false
        }

        viewModelScope.launch {

            val result = runCatching {
                val session = sessionRepository.require()
                vaultService.create(CreateRequest.Biometric(session, fragment))
            }

            result.onFailure {
                if (it !is UserCanceledBiometricsException) {
                    Dialogs.showLongToast(
                        context,
                        it.localizedMessage ?: context.getString(R.string.common_error),
                    )
                }
            }

            config.biometricAuthenticationEnabled = result.isSuccess
        }

        return false
    }

    fun resetApp() = viewModelScope.launch {
        val allPhotos = photoRepository.findAllPhotosByImportDateDesc()
        for (photo in allPhotos) {
            photoRepository.deleteInternalPhotoData(photo)
        }
        photoRepository.deleteAll()
        albumRepository.deleteAll()
        albumRepository.unlinkAll()

        vaultService.reset(VaultProtectionType.Password)
        vaultService.reset(VaultProtectionType.Biometric)

        config.legacyPasswordHash = null
        config.legacyUserSalt = null

        (app as BaseApplication).lockApp()
    }

    /**
     * Run the registry garbage collector: repack thumbnail packs with >30%
     * tombstoned entries, then delete the remote originals + thumbnail files
     * for tombstoned entries.
     *
     * Triggered from Settings → "Clean up backup". Surfaces the result via
     * toast — started / success (with counts) / failed / nothing to do.
     *
     * @since registry-gc feature
     */
    fun cleanupBackup() {
        Dialogs.showLongToast(app, app.getString(R.string.settings_cleanup_backup_toast_started))
        viewModelScope.launch {
            try {
                val repackedPacks = hashRegistry.gcThumbnailPacks()
                val deletedOriginals = hashRegistry.gcOriginals()
                if (repackedPacks == 0 && deletedOriginals == 0) {
                    Dialogs.showLongToast(app, app.getString(R.string.settings_cleanup_backup_toast_nothing))
                } else {
                    Dialogs.showLongToast(
                        app,
                        app.getString(R.string.settings_cleanup_backup_toast_success, repackedPacks, deletedOriginals),
                    )
                }
            } catch (e: Exception) {
                Dialogs.showLongToast(
                    app,
                    app.getString(
                        R.string.settings_cleanup_backup_toast_failed,
                        e.message ?: e.javaClass.simpleName,
                    ),
                )
            }
        }
    }

    /**
     * One-shot cleanup of cached local encrypted originals.
     *
     * Iterates every photo whose `syncState == UPLOADED` and deletes the
     * corresponding local `.crypt` original file (located at
     * `filesDir/<internalFileName>`). Thumbnails (`.crypt.tn`) are NEVER
     * touched — the gallery continues to display them. The original is
     * re-downloaded on demand via [onlasdan.gallery.sync.rclone.SyncRestorer.ensureLocalOriginal]
     * when the user opens a photo.
     *
     * SAFETY: photos whose `syncState` is NOT `UPLOADED` (i.e. `LOCAL_ONLY`
     * or `UPLOAD_PENDING`) are skipped entirely — their local original is the
     * only copy and must never be deleted here. This guards against accidental
     * data loss if the user taps this button before a sync has completed.
     *
     * Triggered from Settings → "Clean cached originals". Surfaces the result
     * via toast — started / success (with count + human-readable bytes freed)
     * / failed / nothing to do.
     *
     * @return the (count, bytesFreed) pair, also surfaced via toast. The
     * return value is primarily for testability — the UI does not consume it.
     *
     * @since Item 2 — manual one-shot cleanup of cached originals
     */
    suspend fun cleanCachedOriginals(): Pair<Int, Long> = withContext(Dispatchers.IO) {
        Dialogs.showLongToast(app, app.getString(R.string.settings_clean_cached_originals_toast_started))
        try {
            val photos = photoRepository.findAllPhotosByImportDateDesc()
            var count = 0
            var bytesFreed = 0L
            for (photo in photos) {
                // SAFETY: never delete the local original for photos that
                // haven't been verified on the remote. `UPLOADED` is the only
                // safe state — the encrypted original has been verified at the
                // remote by content-hash check and can be re-downloaded on
                // demand.
                if (photo.syncState != SyncState.UPLOADED) continue
                val origFile = File(app.filesDir, photo.internalFileName)
                if (origFile.exists()) {
                    bytesFreed += origFile.length()
                    if (origFile.delete()) {
                        count++
                    }
                }
            }
            when {
                count == 0 -> Dialogs.showLongToast(
                    app,
                    app.getString(R.string.settings_clean_cached_originals_toast_nothing),
                )
                else -> Dialogs.showLongToast(
                    app,
                    app.getString(
                        R.string.settings_clean_cached_originals_toast_success,
                        count,
                        Formatter.formatFileSize(app, bytesFreed),
                    ),
                )
            }
            count to bytesFreed
        } catch (e: Exception) {
            Dialogs.showLongToast(
                app,
                app.getString(
                    R.string.settings_clean_cached_originals_toast_failed,
                    e.message ?: e.javaClass.simpleName,
                ),
            )
            0 to 0L
        }
    }

    /**
     * Export every live (non-trashed) photo to a plain ZIP archive at [uri].
     * The ZIP contains each photo's decrypted plaintext bytes + a manifest.json
     * entry with per-photo metadata. The ZIP itself is NOT encrypted — the
     * user picked where to save it and is responsible for securing the file.
     *
     * Surfaces the result via toast — started / success / failed.
     *
     * @since Item 1 — ZIP backup export
     */
    fun exportVaultToZip(uri: android.net.Uri) {
        Dialogs.showLongToast(app, app.getString(R.string.settings_backup_export_zip_toast_started))
        viewModelScope.launch {
            val ok = try {
                photoRepository.exportToZip(uri)
            } catch (e: Exception) {
                Timber.e(e, "exportVaultToZip: FAILED")
                false
            }
            val msgRes = if (ok) {
                R.string.settings_backup_export_zip_toast_success
            } else {
                R.string.settings_backup_export_zip_toast_failed
            }
            Dialogs.showLongToast(app, app.getString(msgRes))
        }
    }

    /**
     * Import photos from a ZIP archive at [uri] previously produced by
     * [exportVaultToZip]. Reads manifest.json, re-encrypts each entry with
     * the current VMK, and creates fresh Photo DB rows (new UUIDs).
     *
     * Surfaces the result via toast — started / success (with count) /
     * failed.
     *
     * @since Item 1 — ZIP backup import
     */
    fun importVaultFromZip(uri: android.net.Uri) {
        Dialogs.showLongToast(app, app.getString(R.string.settings_backup_import_zip_toast_started))
        viewModelScope.launch {
            val count = try {
                photoRepository.importFromZip(uri)
            } catch (e: Exception) {
                Timber.e(e, "importVaultFromZip: FAILED")
                -1
            }
            val msgRes = when {
                count < 0 -> R.string.settings_backup_import_zip_toast_failed
                count == 0 -> R.string.settings_backup_import_zip_toast_nothing
                else -> R.string.settings_backup_import_zip_toast_success
            }
            val msg = if (count > 0) {
                app.getString(msgRes, count)
            } else {
                app.getString(msgRes)
            }
            Dialogs.showLongToast(app, msg)
            // Importing may have changed storage totals — refresh.
            refreshStorageStats()
            refreshTrashCount()
        }
    }

    /**
     * Re-compute [PhotoRepository.getStorageStats] and publish the formatted
     * per-row subtitles to [infoSummariesFlow]. Also refreshes the trash
     * count via [refreshTrashCount] since both come from the same DB scan.
     *
     * Called on init and from the "Refresh storage stats" action.
     *
     * @since Item 4 — storage analytics
     */
    fun refreshStorageStats() {
        viewModelScope.launch {
            val stats = try {
                photoRepository.getStorageStats()
            } catch (e: Exception) {
                Timber.w(e, "refreshStorageStats: getStorageStats FAILED")
                null
            }
            if (stats == null) {
                infoSummariesFlow.value = emptyMap()
                return@launch
            }
            val map = buildMap {
                put(
                    SettingsFragment.KEY_INFO_STORAGE_ORIGINALS,
                    app.getString(
                        R.string.settings_storage_originals_summary,
                        Formatter.formatFileSize(app, stats.localOriginalsBytes),
                        stats.localOriginalsCount,
                    ),
                )
                put(
                    SettingsFragment.KEY_INFO_STORAGE_THUMBNAILS,
                    app.getString(
                        R.string.settings_storage_thumbnails_summary,
                        Formatter.formatFileSize(app, stats.localThumbnailsBytes),
                        stats.localThumbnailsCount,
                    ),
                )
                put(
                    SettingsFragment.KEY_INFO_STORAGE_PHOTOS,
                    app.getString(
                        R.string.settings_storage_photos_summary,
                        stats.photoCount,
                        stats.uploadedCount,
                        stats.pendingCount,
                    ),
                )
            }
            infoSummariesFlow.value = map
        }
    }

    /**
     * Refresh the live trash count from the DB. Called on init and after
     * any action that may change it (e.g. import, restore, empty trash).
     *
     * @since v10 recycle bin
     */
    fun refreshTrashCount() {
        viewModelScope.launch {
            _trashCount.value = try {
                // Reuse the storage stats snapshot — it already includes
                // a `trashCount` field from the same DB scan.
                photoRepository.getStorageStats().trashCount
            } catch (e: Exception) {
                Timber.w(e, "refreshTrashCount: FAILED")
                null
            }
        }
    }
}