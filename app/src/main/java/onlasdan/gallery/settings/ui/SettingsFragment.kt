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

package onlasdan.gallery.settings.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.Fragment
import dagger.hilt.android.AndroidEntryPoint
import onlasdan.gallery.settings.data.Config
import onlasdan.gallery.settings.ui.compose.LocalConfig
import onlasdan.gallery.settings.ui.compose.SettingsScreen
import onlasdan.gallery.ui.LocalFragment
import onlasdan.gallery.ui.theme.AppTheme
import javax.inject.Inject

@AndroidEntryPoint
class SettingsFragment : Fragment() {

    @Inject
    lateinit var config: Config

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setContent {
                AppTheme {
                    CompositionLocalProvider(
                        LocalFragment provides this@SettingsFragment,
                        LocalConfig provides config,
                    ) {
                        SettingsScreen()
                    }
                }
            }
        }
    }

    companion object {
        const val KEY_ACTION_RESET = "action_reset_safe"
        const val KEY_ACTION_CHANGE_PASSWORD = "action_change_password"
        const val KEY_ACTION_CHECK_PASSWORD = "action_check_password"
        const val KEY_ACTION_HIDE_APP = "action_hide_app"
        const val KEY_ACTION_BACKUP = "action_backup_safe"
        const val KEY_ACTION_RECOVERY_PHRASE = "action_recovery_phrase"
        const val KEY_ACTION_FEEDBACK = "action_feedback"
        const val KEY_ACTION_DONATE = "action_donate"
        const val KEY_ACTION_SOURCECODE = "action_sourcecode"
        const val KEY_ACTION_CREDITS = "action_credits"
        const val KEY_ACTION_TELEMETRY = "action_telemetry"
        const val KEY_ACTION_ABOUT = "action_about"

        /** Action key for the "Cloud Sync" row. Opens SAF file picker to import rclone.conf.
         *  @since PR1 sync addendum (Settings UI) */
        const val KEY_ACTION_CLOUD_SYNC = "action_cloud_sync"

        /** Action key for the "Clean up backup" row. Runs registry GC: repacks
         *  thumbnail packs with >30% tombstoned entries, then deletes the remote
         *  originals for tombstoned entries. @since registry-gc feature */
        const val KEY_ACTION_CLEANUP_BACKUP = "action_cleanup_backup"

        /** Action key for the "Clean cached originals" row. Deletes the local
         *  `.crypt` files for photos that are already safely uploaded (state ==
         *  UPLOADED). Never touches LOCAL_ONLY or UPLOAD_PENDING files.
         *  @since Item 2 — manual one-shot cleanup of cached originals */
        const val KEY_ACTION_CLEAN_CACHED_ORIGINALS = "action_clean_cached_originals"

        /** Action key for "Export vault to ZIP" (Item 1 ZIP backup).
         *  Opens SAF CreateDocument so the user picks the output .zip path,
         *  then SettingsViewModel.exportVaultToZip(uri) decrypts each photo
         *  and writes the plaintext + manifest.json into the ZIP. */
        const val KEY_ACTION_EXPORT_ZIP = "action_export_zip"

        /** Action key for "Import vault from ZIP" (Item 1 ZIP backup).
         *  Opens SAF OpenDocument so the user picks an existing .zip, then
         *  SettingsViewModel.importVaultFromZip(uri) reads the manifest,
         *  re-encrypts each entry, and creates fresh Photo DB rows. */
        const val KEY_ACTION_IMPORT_ZIP = "action_import_zip"

        /** Action key for the "Trash" row. Navigates to the Trash screen
         *  (TrashFragment) where the user can browse / restore / permanently
         *  delete soft-deleted photos. @since v10 recycle bin */
        const val KEY_ACTION_TRASH = "action_trash"

        /** Action key for the "Refresh storage stats" row. Re-runs
         *  PhotoRepository.getStorageStats() and updates the subtitles of
         *  the three Info rows below. @since Item 4 storage analytics */
        const val KEY_ACTION_REFRESH_STORAGE = "action_refresh_storage"

        /** Info key for the "Local originals" row in the Storage section.
         *  The row's subtitle is computed at runtime and surfaced via
         *  SettingsUiState.infoSummaries under this key. @since Item 4 */
        const val KEY_INFO_STORAGE_ORIGINALS = "info_storage_originals"

        /** Info key for the "Local thumbnails" row. @since Item 4 */
        const val KEY_INFO_STORAGE_THUMBNAILS = "info_storage_thumbnails"

        /** Info key for the "Photos" row. @since Item 4 */
        const val KEY_INFO_STORAGE_PHOTOS = "info_storage_photos"
    }
}