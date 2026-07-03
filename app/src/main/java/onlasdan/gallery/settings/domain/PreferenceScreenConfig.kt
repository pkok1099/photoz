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

package onlasdan.gallery.settings.domain

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import onlasdan.gallery.R
import onlasdan.gallery.settings.data.Config
import onlasdan.gallery.settings.data.Config.Companion.GALLERY_START_PAGE
import onlasdan.gallery.settings.data.Config.Companion.GALLERY_START_PAGE_DEFAULT
import onlasdan.gallery.settings.data.Config.Companion.SECURITY_ALLOW_SCREENSHOTS
import onlasdan.gallery.settings.data.Config.Companion.SECURITY_ALLOW_SCREENSHOTS_DEFAULT
import onlasdan.gallery.settings.data.Config.Companion.SECURITY_BIOMETRIC_AUTHENTICATION_ENABLED
import onlasdan.gallery.settings.data.Config.Companion.SECURITY_BIOMETRIC_AUTHENTICATION_ENABLED_DEFAULT
import onlasdan.gallery.settings.data.Config.Companion.SYSTEM_DESIGN
import onlasdan.gallery.settings.data.Config.Companion.SYSTEM_DESIGN_DEFAULT
import onlasdan.gallery.settings.data.Config.Companion.SYNC_AUTO_UPLOAD
import onlasdan.gallery.settings.data.Config.Companion.SYNC_AUTO_UPLOAD_DEFAULT
import onlasdan.gallery.settings.data.Config.Companion.SYNC_DELETE_AFTER_UPLOAD
import onlasdan.gallery.settings.data.Config.Companion.SYNC_DELETE_AFTER_UPLOAD_DEFAULT
import onlasdan.gallery.settings.data.Config.Companion.SYNC_WIFI_ONLY
import onlasdan.gallery.settings.data.Config.Companion.SYNC_WIFI_ONLY_DEFAULT
import onlasdan.gallery.settings.domain.models.LockTimeout
import onlasdan.gallery.settings.domain.models.SettingsEnum
import onlasdan.gallery.settings.domain.models.StartPage
import onlasdan.gallery.settings.domain.models.SystemDesignEnum
import onlasdan.gallery.settings.ui.SettingsFragment

data class PreferenceScreenConfig(
    val sections: List<PreferenceSection>,
)

data class PreferenceSection(
    @get:StringRes val title: Int,
    @get:StringRes val summary: Int?,
    val preferences: List<Preference>,
)

sealed interface Preference {
    val key: String
    @get:DrawableRes val icon: Int
    @get:StringRes val title: Int

    data class Simple(
        override val key: String,
        override val icon: Int,
        override val title: Int,
        val summary: Int,
    ) : Preference

    data class Switch(
        override val key: String,
        override val icon: Int,
        override val title: Int,
        val summary: Int,
        val default: Boolean
    ) : Preference

    data class Enum<T: SettingsEnum>(
        override val key: String,
        override val icon: Int,
        override val title: Int,
        val default: T,
        val possibleValues: List<T>,
    ) : Preference

    /**
     * A preference row whose subtitle is dynamic (computed at runtime from a StateFlow, not
     * from a static string resource). Used by the Cloud Sync row.
     *
     * @since PR1 sync addendum (Settings UI)
     */
    data class DynamicSummary(
        override val key: String,
        override val icon: Int,
        override val title: Int,
        val summaryPlaceholder: Int,
    ) : Preference
}

val PreferenceScreenConfigContent = buildList {
    add(
        PreferenceSection(
            title = R.string.settings_category_app,
            summary = null,
            preferences = listOf(
                Preference.Enum(
                    key = SYSTEM_DESIGN,
                    icon = R.drawable.ic_brush,
                    title = R.string.settings_app_design_title,
                    default = SYSTEM_DESIGN_DEFAULT,
                    possibleValues = SystemDesignEnum.entries,
                ),
                Preference.Enum(
                    key = GALLERY_START_PAGE,
                    icon = R.drawable.ic_gallery_thumbnail,
                    title = R.string.settings_gallery_start_page_title,
                    default = GALLERY_START_PAGE_DEFAULT,
                    possibleValues = StartPage.entries,
                )
            )
        )
    )
    // ─── Cloud Sync section (PR1 addendum + sync-settings feature) ───────────
    add(
        PreferenceSection(
            title = R.string.settings_category_cloud_sync,
            summary = R.string.settings_category_cloud_sync_summary,
            preferences = listOf(
                Preference.DynamicSummary(
                    key = SettingsFragment.KEY_ACTION_CLOUD_SYNC,
                    icon = R.drawable.ic_cloud_upload,
                    title = R.string.settings_cloud_sync_config_title,
                    summaryPlaceholder = R.string.settings_cloud_sync_not_configured,
                ),
                // ─── sync-settings feature — three user-configurable toggles ──
                // Replacements for the previously-hardcoded flags in
                // SyncConfig. Defaults match the prior hardcoded values so
                // existing users see no behaviour change on upgrade.
                Preference.Switch(
                    key = SYNC_AUTO_UPLOAD,
                    icon = R.drawable.ic_cloud_upload,
                    title = R.string.settings_sync_auto_upload_title,
                    summary = R.string.settings_sync_auto_upload_summary,
                    default = SYNC_AUTO_UPLOAD_DEFAULT,
                ),
                Preference.Switch(
                    key = SYNC_WIFI_ONLY,
                    icon = R.drawable.ic_cloud,
                    title = R.string.settings_sync_wifi_only_title,
                    summary = R.string.settings_sync_wifi_only_summary,
                    default = SYNC_WIFI_ONLY_DEFAULT,
                ),
                // ─── Item 2: restored `syncDeleteAfterUpload` toggle ─────────
                // When ON, [onlasdan.gallery.sync.work.PhotoSyncWorker] deletes
                // the local `.crypt` original after a verified upload. When OFF,
                // the local copy is kept for offline access and the user can
                // reclaim space later via the "Clean cached originals" row
                // below. Default OFF to preserve existing behaviour and avoid
                // surprising data loss.
                Preference.Switch(
                    key = SYNC_DELETE_AFTER_UPLOAD,
                    icon = R.drawable.ic_delete,
                    title = R.string.settings_sync_delete_after_upload_title,
                    summary = R.string.settings_sync_delete_after_upload_summary,
                    default = SYNC_DELETE_AFTER_UPLOAD_DEFAULT,
                ),
                // @since registry-gc feature — manual cleanup of soft-deleted
                // entries' remote originals + thumbnail pack compaction. The
                // row's onClick callback (registered in SettingsCallbacks)
                // runs HashRegistry.gcThumbnailPacks() + gcOriginals() in a
                // background coroutine and surfaces the result via a toast.
                Preference.Simple(
                    key = SettingsFragment.KEY_ACTION_CLEANUP_BACKUP,
                    icon = R.drawable.ic_refresh,
                    title = R.string.settings_cleanup_backup_title,
                    summary = R.string.settings_cleanup_backup_summary,
                ),
                // @since Item 2 — manual one-shot cleanup of cached local
                // originals. Deletes the `.crypt` file for every photo whose
                // syncState == UPLOADED. SAFETY: never touches LOCAL_ONLY or
                // UPLOAD_PENDING photos (those have not yet been verified on
                // the remote). Thumbnails are always kept so the gallery
                // continues to show the photo. The original is re-downloaded
                // on demand via [SyncRestorer.ensureLocalOriginal].
                Preference.Simple(
                    key = SettingsFragment.KEY_ACTION_CLEAN_CACHED_ORIGINALS,
                    icon = R.drawable.ic_delete,
                    title = R.string.settings_clean_cached_originals_title,
                    summary = R.string.settings_clean_cached_originals_summary,
                ),
            ),
        )
    )
    add(
        PreferenceSection(
            title = R.string.settings_category_security,
            summary = null,
            preferences = listOf(
                Preference.Switch(
                    key = SECURITY_ALLOW_SCREENSHOTS,
                    icon = R.drawable.ic_screen_lock,
                    title = R.string.settings_security_allow_screenshots_title,
                    summary = R.string.settings_security_allow_screenshots_summary,
                    default = SECURITY_ALLOW_SCREENSHOTS_DEFAULT,
                ),
                Preference.Simple(
                    key = SettingsFragment.KEY_ACTION_CHANGE_PASSWORD,
                    icon = R.drawable.ic_password,
                    title = R.string.change_password_title,
                    summary = R.string.settings_security_change_password_summary,
                ),
                Preference.Switch(
                    key = SECURITY_BIOMETRIC_AUTHENTICATION_ENABLED,
                    icon = R.drawable.ic_fingerprint,
                    title = R.string.settings_security_biometric_title,
                    summary = R.string.settings_security_biometric_summary,
                    default = SECURITY_BIOMETRIC_AUTHENTICATION_ENABLED_DEFAULT,
                ),
                Preference.Enum(
                    key = Config.SECURITY_LOCK_TIMEOUT,
                    icon = R.drawable.ic_schedule,
                    title = R.string.settings_security_timeout_title,
                    default = LockTimeout.FiveMinute,
                    possibleValues = LockTimeout.entries,
                ),
                Preference.Simple(
                    key = Config.SECURITY_DIAL_LAUNCH_CODE,
                    icon = R.drawable.ic_dialpad,
                    title = R.string.settings_security_launch_code_title,
                    summary = R.string.settings_security_launch_code_summary,
                ),

                Preference.Simple(
                    key = SettingsFragment.KEY_ACTION_HIDE_APP,
                    icon = R.drawable.ic_app_blocking,
                    title = R.string.settings_security_hide_app_title,
                    summary = R.string.settings_security_hide_app_summary,
                ),
                Preference.Simple(
                    key = SettingsFragment.KEY_ACTION_RECOVERY_PHRASE,
                    icon = R.drawable.ic_key,
                    title = R.string.settings_security_recovery_phrase_title,
                    summary = R.string.settings_security_recovery_phrase_summary,
                ),
            ),
        )
    )
    add(
        PreferenceSection(
            title = R.string.settings_category_advanced,
            summary = R.string.settings_category_advanced_summary,
            preferences = listOf(
                Preference.Simple(
                    SettingsFragment.KEY_ACTION_BACKUP,
                    icon = R.drawable.ic_save_as,
                    title = R.string.settings_advanced_backup_title,
                    summary = R.string.settings_advanced_backup_summary,
                ),
                Preference.Switch(
                    Config.ADVANCED_DELETE_IMPORTED_FILES,
                    icon = R.drawable.ic_delete,
                    title = R.string.settings_advanced_delete_imported_title,
                    summary = R.string.settings_advanced_delete_imported_summary,
                    default = Config.ADVANCED_DELETE_IMPORTED_FILES_DEFAULT,
                ),
                Preference.Switch(
                    Config.ADVANCED_DELETE_EXPORTED_FILES,
                    icon = R.drawable.ic_delete,
                    title = R.string.settings_advanced_delete_exported_title,
                    summary = R.string.settings_advanced_delete_exported_summary,
                    default = Config.ADVANCED_DELETE_EXPORTED_FILES_DEFAULT,
                ),
                Preference.Simple(
                    key = SettingsFragment.KEY_ACTION_RESET,
                    icon = R.drawable.ic_warning,
                    title = R.string.settings_advanced_reset_title,
                    summary = R.string.settings_advanced_reset_summary,
                ),
            ),
        )
    )
    add(
        PreferenceSection(
            title = R.string.settings_other_title,
            summary = null,
            preferences = listOf(
                Preference.Simple(
                    key = SettingsFragment.KEY_ACTION_FEEDBACK,
                    icon = R.drawable.ic_feedback,
                    title = R.string.settings_other_feedback_title,
                    summary = R.string.settings_other_feedback_summary,
                ),
                Preference.Simple(
                    key = SettingsFragment.KEY_ACTION_DONATE,
                    icon = R.drawable.ic_money,
                    title = R.string.settings_other_donate_title,
                    summary = R.string.settings_other_donate_summary,
                ),
                Preference.Simple(
                    key = SettingsFragment.KEY_ACTION_SOURCECODE,
                    icon = R.drawable.ic_code,
                    title = R.string.settings_other_sourcecode_title,
                    summary = R.string.settings_other_sourcecode_summary,
                ),
                Preference.Simple(
                    key = SettingsFragment.KEY_ACTION_CREDITS,
                    icon = R.drawable.ic_book,
                    title = R.string.settings_other_credits_title,
                    summary = R.string.settings_other_credits_summary,
                ),
                Preference.Simple(
                    key = SettingsFragment.KEY_ACTION_TELEMETRY,
                    icon = R.drawable.ic_data_object,
                    title = R.string.settings_other_telemetry_title,
                    summary = R.string.settings_other_telemetry_summary,
                ),
                Preference.Simple(
                    key = SettingsFragment.KEY_ACTION_ABOUT,
                    icon = R.drawable.ic_info,
                    title = R.string.settings_other_about_title,
                    summary = R.string.settings_other_about_summary,
                ),
            ),
        )
    )
}
