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

import android.content.res.Configuration.UI_MODE_NIGHT_YES
import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.fragment.findNavController
import kotlinx.coroutines.launch
import onlasdan.gallery.BuildConfig
import onlasdan.gallery.R
import onlasdan.gallery.backup.domain.BackupStrategy
import onlasdan.gallery.backup.ui.BackupBottomSheetDialogFragment
import onlasdan.gallery.backup.ui.ConfirmPasswordDialog
import onlasdan.gallery.databinding.BindingConverters
import onlasdan.gallery.encryption.ui.RecoveryPhraseSheet
import onlasdan.gallery.other.extensions.launchAndIgnoreTimer
import onlasdan.gallery.other.extensions.show
import onlasdan.gallery.other.openUrl
import onlasdan.gallery.other.sendEmail
import onlasdan.gallery.other.setAppDesign
import onlasdan.gallery.settings.data.Config
import onlasdan.gallery.settings.domain.Preference
import onlasdan.gallery.settings.domain.PreferenceScreenConfig
import onlasdan.gallery.settings.domain.PreferenceScreenConfigContent
import onlasdan.gallery.settings.domain.PreferenceSection
import onlasdan.gallery.settings.domain.models.SettingsEnum
import onlasdan.gallery.settings.domain.models.SystemDesignEnum
import onlasdan.gallery.settings.ui.SettingsFragment
import onlasdan.gallery.settings.ui.changepassword.ChangePasswordSheet
import onlasdan.gallery.settings.ui.hideapp.SecretLaunchCodeDialog
import onlasdan.gallery.settings.ui.hideapp.ToggleAppVisibilityDialog
import onlasdan.gallery.sync.rclone.RcloneConfigManager
import onlasdan.gallery.telemetry.ui.TelemetryExplanationSheet
import onlasdan.gallery.ui.LocalFragment
import onlasdan.gallery.ui.theme.AppTheme

val LocalPreferencesValues: ProvidableCompositionLocal<Map<String, *>> =
        compositionLocalOf { emptyMap<String, String>() }

fun createBackupFilename(): String = "photok_backup_${BindingConverters.millisToFormattedDateConverter(System.currentTimeMillis())}.zip"

@Composable
fun SettingsCallbacks(viewModel: SettingsViewModel) {
        val fragment = LocalFragment.current
        val context = LocalContext.current
        val activity = LocalActivity.current

        val backupLauncher =
                rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
                        uri ?: return@rememberLauncherForActivityResult
                        fragment ?: return@rememberLauncherForActivityResult
                        BackupBottomSheetDialogFragment(
                                uri,
                                BackupStrategy.Name.Default,
                        ).show(fragment.parentFragmentManager)
                }

        // SAF picker for ZIP vault export. The user picks the output .zip path
        // (SAF CreateDocument — file is created at the chosen location).
        // @since Item 1 ZIP backup
        val exportZipLauncher =
                rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
                        uri ?: return@rememberLauncherForActivityResult
                        viewModel.exportVaultToZip(uri)
                }

        // SAF picker for ZIP vault import. The user picks an existing .zip to
        // import photos from.
        // @since Item 1 ZIP backup
        val importZipLauncher =
                rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                        uri ?: return@rememberLauncherForActivityResult
                        viewModel.importVaultFromZip(uri)
                }

        // SAF picker for rclone.conf import. @since PR1 sync addendum (Settings UI)
        val rcloneConfigLauncher =
                rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                        uri ?: return@rememberLauncherForActivityResult
                        viewModel.importRcloneConfig(uri)
                }

        // Observe sync config status to trigger the remote picker dialog when state becomes
        // AwaitingRemoteChoice. @since PR1 sync addendum (remote picker)
        val syncConfigStatus by viewModel.syncConfigStatus.collectAsStateWithLifecycle()
        var showRemotePicker by rememberSaveable { mutableStateOf(false) }
        var pickerRemotes by remember { mutableStateOf<List<RcloneConfigManager.RemoteInfo>>(emptyList()) }
        val settingsScope = rememberCoroutineScope()

        LaunchedEffect(syncConfigStatus) {
                when (val s = syncConfigStatus) {
                        is SyncConfigStatus.AwaitingRemoteChoice -> {
                                pickerRemotes = s.remotes
                                showRemotePicker = true
                        }
                        else -> {
                                showRemotePicker = false
                        }
                }
        }

        var showSecretLaunchCodeDialog by remember { mutableStateOf(false) }
        var showUsageDataSheet by rememberSaveable { mutableStateOf(false) }
        var showRecoveryPhraseSheet by rememberSaveable { mutableStateOf(false) }
        var showChangePasswordSheet by rememberSaveable { mutableStateOf(false) }
        // Sprint 2 / M7 — Multi-vault UI
        var showCreateVaultSheet by rememberSaveable { mutableStateOf(false) }
        var showConfirmPasswordDialogForBackup by rememberSaveable { mutableStateOf(false) }
        var showConfirmPasswordDialogForReset by rememberSaveable { mutableStateOf(false) }

        LaunchedEffect(Unit) {
                fragment ?: return@LaunchedEffect

                viewModel.registerPreferenceCallback(Config.SYSTEM_DESIGN) {
                        it as SystemDesignEnum
                        setAppDesign(it)
                        true
                }

                viewModel.registerPreferenceCallback(SettingsFragment.KEY_ACTION_CHANGE_PASSWORD) {
                        showChangePasswordSheet = true
                        false
                }

                viewModel.registerPreferenceCallback(Config.SECURITY_BIOMETRIC_AUTHENTICATION_ENABLED) {
                        viewModel.onBiometricUnlockChanged(it, fragment)
                }

                viewModel.registerPreferenceCallback(Config.SECURITY_DIAL_LAUNCH_CODE) {
                        showSecretLaunchCodeDialog = true
                        false
                }

                viewModel.registerPreferenceCallback(SettingsFragment.KEY_ACTION_RECOVERY_PHRASE) {
                        showRecoveryPhraseSheet = true
                        false
                }

                // ─── Sprint 2 / M7 — Multi-vault UI wiring ──────────────────────────
                viewModel.registerPreferenceCallback(SettingsFragment.KEY_ACTION_CREATE_VAULT) {
                        showCreateVaultSheet = true
                        false
                }
                viewModel.registerPreferenceCallback(SettingsFragment.KEY_ACTION_SWITCH_VAULT) {
                        // Switch vault = lock the app. The unlock screen will iterate all
                        // Password rows and the user picks which vault to enter by typing
                        // that vault's password.
                        (fragment?.activity as? onlasdan.gallery.BaseApplication)?.lockApp()
                        false
                }

                viewModel.registerPreferenceCallback(SettingsFragment.KEY_ACTION_HIDE_APP) {
                        ToggleAppVisibilityDialog().show(fragment.childFragmentManager)
                        false
                }

                viewModel.registerPreferenceCallback(SettingsFragment.KEY_ACTION_RESET) {
                        showConfirmPasswordDialogForReset = true
                        false
                }

                viewModel.registerPreferenceCallback(SettingsFragment.KEY_ACTION_BACKUP) {
                        showConfirmPasswordDialogForBackup = true
                        false
                }

                // Cloud Sync row → context-dependent action. @since PR1 sync addendum (Settings UI)
                viewModel.registerPreferenceCallback(SettingsFragment.KEY_ACTION_CLOUD_SYNC) {
                        when (syncConfigStatus) {
                                is SyncConfigStatus.Configured, is SyncConfigStatus.AwaitingRemoteChoice -> {
                                        settingsScope.launch {
                                                val remotes = viewModel.rcloneConfigManagerAvailableRemotes()
                                                if (remotes.isNotEmpty()) {
                                                        pickerRemotes = remotes
                                                        showRemotePicker = true
                                                } else {
                                                        rcloneConfigLauncher.launch(arrayOf("*/*"))
                                                }
                                        }
                                }
                                else -> {
                                        rcloneConfigLauncher.launch(arrayOf("*/*"))
                                }
                        }
                        false
                }

                // @since registry-gc feature — "Clean up backup" row. Triggers
                // HashRegistry.gcThumbnailPacks() + gcOriginals() in the ViewModel.
                // The ViewModel surfaces the result via toast; no UI state needs to
                // be observed here.
                viewModel.registerPreferenceCallback(SettingsFragment.KEY_ACTION_CLEANUP_BACKUP) {
                        viewModel.cleanupBackup()
                        false
                }

                // @since Item 2 — "Clean cached originals" row. Triggers the
                // ViewModel's cleanCachedOriginals() which deletes the local `.crypt`
                // originals for photos whose syncState == UPLOADED. The ViewModel
                // surfaces the result (count + bytes freed) via toast.
                viewModel.registerPreferenceCallback(SettingsFragment.KEY_ACTION_CLEAN_CACHED_ORIGINALS) {
                        settingsScope.launch { viewModel.cleanCachedOriginals() }
                        false
                }

                // @since Item 1 ZIP backup — "Export vault to ZIP" row. Launches
                // SAF CreateDocument so the user picks the output .zip path. The
                // ViewModel handles the actual encryption-to-plaintext-and-zip work
                // once the URI is returned.
                viewModel.registerPreferenceCallback(SettingsFragment.KEY_ACTION_EXPORT_ZIP) {
                        exportZipLauncher.launchAndIgnoreTimer(
                                "photok_vault_${System.currentTimeMillis()}.zip",
                                activity = activity,
                        )
                        false
                }

                // @since Item 1 ZIP backup — "Import vault from ZIP" row. Launches
                // SAF OpenDocument so the user picks an existing .zip file. The
                // ViewModel handles reading the manifest, re-encrypting each entry,
                // and creating fresh Photo DB rows.
                viewModel.registerPreferenceCallback(SettingsFragment.KEY_ACTION_IMPORT_ZIP) {
                        importZipLauncher.launchAndIgnoreTimer(
                                input = arrayOf("application/zip", "application/octet-stream"),
                                activity = activity,
                        )
                        false
                }

                // @since v10 recycle bin — "Trash" row. Navigates to the Trash
                // screen (TrashFragment) where the user can browse, restore, or
                // permanently delete soft-deleted photos.
                viewModel.registerPreferenceCallback(SettingsFragment.KEY_ACTION_TRASH) {
                        fragment?.findNavController()?.navigate(R.id.action_global_trashFragment)
                        false
                }

                // @since Item 4 storage analytics — "Refresh storage stats" row.
                // Re-runs getStorageStats() and updates the subtitles of the three
                // Info rows in the Storage section. The ViewModel surfaces no toast
                // (the rows themselves update visibly).
                viewModel.registerPreferenceCallback(SettingsFragment.KEY_ACTION_REFRESH_STORAGE) {
                        viewModel.refreshStorageStats()
                        false
                }

                viewModel.registerPreferenceCallback(SettingsFragment.KEY_ACTION_FEEDBACK) {
                        val email = context.getString(R.string.settings_other_feedback_mail_emailaddress)
                        val subject =
                                "${context.getString(
                                        R.string.settings_other_feedback_mail_subject,
                                )} (App ${BuildConfig.VERSION_NAME} / Android ${Build.VERSION.RELEASE})"
                        val text = context.getString(R.string.settings_other_feedback_mail_body)

                        context.sendEmail(
                                email = email,
                                subject = subject,
                                text = text,
                                chooserTitle = context.getString(R.string.settings_other_feedback_title),
                        )
                        false
                }

                viewModel.registerPreferenceCallback(SettingsFragment.KEY_ACTION_DONATE) {
                        fragment.openUrl(context.getString(R.string.settings_other_donate_url))
                        false
                }

                viewModel.registerPreferenceCallback(SettingsFragment.KEY_ACTION_SOURCECODE) {
                        fragment.openUrl(context.getString(R.string.settings_other_sourcecode_url))
                        false
                }

                viewModel.registerPreferenceCallback(SettingsFragment.KEY_ACTION_CREDITS) {
                        fragment.findNavController().navigate(R.id.action_settingsFragment_to_creditsFragment)
                        false
                }

                viewModel.registerPreferenceCallback(SettingsFragment.KEY_ACTION_TELEMETRY) {
                        showUsageDataSheet = true
                        false
                }

                viewModel.registerPreferenceCallback(SettingsFragment.KEY_ACTION_ABOUT) {
                        fragment.findNavController().navigate(R.id.action_settingsFragment_to_aboutFragment)
                        false
                }
        }

        SecretLaunchCodeDialog(
                show = showSecretLaunchCodeDialog,
                onDismissRequest = { showSecretLaunchCodeDialog = false },
        )

        TelemetryExplanationSheet(
                visible = showUsageDataSheet,
                onDismissRequest = { showUsageDataSheet = false },
        )

        ConfirmPasswordDialog(
                visible = showConfirmPasswordDialogForBackup,
                subtitle = stringResource(R.string.backup_confirm_password),
                onSuccess = {
                        backupLauncher.launchAndIgnoreTimer(
                                createBackupFilename(),
                                activity = activity,
                        )

                        showConfirmPasswordDialogForBackup = false
                },
                onDismissRequest = {
                        showConfirmPasswordDialogForBackup = false
                },
        )

        ConfirmPasswordDialog(
                visible = showConfirmPasswordDialogForReset,
                subtitle = stringResource(R.string.settings_advanced_reset_confirmation),
                onSuccess = {
                        viewModel.resetApp()

                        showConfirmPasswordDialogForReset = false
                },
                onDismissRequest = {
                        showConfirmPasswordDialogForReset = false
                },
        )

        if (showRecoveryPhraseSheet) {
                RecoveryPhraseSheet(
                        onDismissRequest = { showRecoveryPhraseSheet = false },
                        onNavigateToSetup = {
                                showRecoveryPhraseSheet = false
                                fragment?.findNavController()?.navigate(R.id.action_global_recoveryPhraseSetupFragment)
                        },
                )
        }

        if (showChangePasswordSheet) {
                ChangePasswordSheet(
                        onDismissRequest = { showChangePasswordSheet = false },
                )
        }

        // Sprint 2 / M7 — Multi-vault UI
        if (showCreateVaultSheet) {
                onlasdan.gallery.settings.ui.createvault.CreateVaultSheet(
                        onDismissRequest = { showCreateVaultSheet = false },
                )
        }

        // Remote picker dialog — shown when syncConfigStatus becomes AwaitingRemoteChoice.
        // @since PR1 sync addendum (remote picker)
        if (showRemotePicker && pickerRemotes.isNotEmpty()) {
                RemotePickerDialog(
                        remotes = pickerRemotes,
                        onPick = { name ->
                                showRemotePicker = false
                                viewModel.chooseRemote(name)
                        },
                        onDismiss = {
                                showRemotePicker = false
                        },
                )
        }
}

/**
 * Dialog that lists all remotes parsed from the imported rclone.conf and lets the user pick
 * one. Uses LazyColumn with heightIn(max = 400.dp) so the list scrolls when there are many
 * remotes (Bug 1 fix).
 *
 * @since PR1 sync addendum (remote picker)
 */
@Composable
private fun RemotePickerDialog(
        remotes: List<RcloneConfigManager.RemoteInfo>,
        onPick: (String) -> Unit,
        onDismiss: () -> Unit,
) {
        var selected by remember { mutableStateOf<String?>(null) }

        AlertDialog(
                onDismissRequest = onDismiss,
                title = {
                        Text(text = stringResource(R.string.sync_remote_picker_title))
                },
                text = {
                        Column {
                                Text(
                                        text = stringResource(R.string.sync_remote_picker_subtitle, remotes.size),
                                        fontSize = 14.sp,
                                        color = MaterialTheme.colorScheme.outline,
                                        modifier = Modifier.padding(bottom = 12.dp),
                                )
                                LazyColumn(
                                        modifier = Modifier.heightIn(max = 400.dp),
                                ) {
                                        items(remotes) { remote ->
                                                val typeSuffix =
                                                        remote.type?.let { " ($it)" }
                                                                ?: stringResource(R.string.sync_remote_picker_no_type)
                                                Row(
                                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        modifier =
                                                                Modifier
                                                                        .clip(CircleShape)
                                                                        .clickable {
                                                                                selected = remote.name
                                                                        }.fillMaxWidth()
                                                                        .padding(vertical = 4.dp),
                                                ) {
                                                        RadioButton(
                                                                selected = selected == remote.name,
                                                                onClick = { selected = remote.name },
                                                        )
                                                        Text(
                                                                text = remote.name + typeSuffix,
                                                                style = MaterialTheme.typography.bodyLarge,
                                                                modifier = Modifier.weight(1f),
                                                        )
                                                }
                                        }
                                }
                        }
                },
                confirmButton = {
                        TextButton(
                                enabled = selected != null,
                                onClick = {
                                        selected?.let(onPick)
                                },
                        ) {
                                Text(stringResource(R.string.common_ok))
                        }
                },
                dismissButton = {
                        TextButton(
                                onClick = onDismiss,
                        ) {
                                Text(stringResource(R.string.common_cancel))
                        }
                },
        )
}

@Composable
fun SettingsScreen() {
        val viewModel = hiltViewModel<SettingsViewModel>()
        val uiState by viewModel.uiState.collectAsStateWithLifecycle()
        val syncConfigStatus by viewModel.syncConfigStatus.collectAsStateWithLifecycle()

        CompositionLocalProvider(
                LocalPreferencesValues provides uiState.preferencesValues,
        ) {
                SettingsContent(
                        screenConfig = uiState.screenConfig,
                        handleUiEvent = viewModel::handleUiEvent,
                        syncConfigStatus = syncConfigStatus,
                        infoSummaries = uiState.infoSummaries,
                        trashCount = uiState.trashCount,
                        keystoreFallbackActive = uiState.keystoreFallbackActive,
                )
        }

        SettingsCallbacks(viewModel)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsContent(
        screenConfig: PreferenceScreenConfig,
        handleUiEvent: (SettingsUiEvent) -> Unit,
        syncConfigStatus: SyncConfigStatus = SyncConfigStatus.NotConfigured,
        infoSummaries: Map<String, String> = emptyMap(),
        trashCount: Int? = null,
        keystoreFallbackActive: Boolean = false,
) {
        val fragment = LocalFragment.current

        val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
        Scaffold(
                topBar = {
                        LargeTopAppBar(
                                title = {
                                        Text(
                                                text = stringResource(R.string.settings_title),
                                        )
                                },
                                scrollBehavior = scrollBehavior,
                        )
                },
        ) { contentPadding ->
                Column(
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                        modifier =
                                Modifier
                                        .nestedScroll(scrollBehavior.nestedScrollConnection)
                                        .verticalScroll(rememberScrollState())
                                        .padding(contentPadding),
                ) {
                        // F-ENC-001: persistent warning banner when SQLCipher fell back to
                        // plaintext SharedPreferences because Android Keystore was unavailable.
                        if (keystoreFallbackActive) {
                                KeystoreFallbackBanner()
                        }
                        for (section in screenConfig.sections) {
                                PreferenceSectionView(
                                        section = section,
                                ) {
                                        for (preference in section.preferences) {
                                                val isFirst = preference == section.preferences.first()
                                                val isLast = preference == section.preferences.last()

                                                val shape =
                                                        when {
                                                                section.preferences.size == 1 -> RoundedCornerShape(18.dp)
                                                                isFirst -> RoundedCornerShape(18.dp, 18.dp, 6.dp, 6.dp)
                                                                isLast -> RoundedCornerShape(6.dp, 6.dp, 18.dp, 18.dp)
                                                                else -> RoundedCornerShape(6.dp)
                                                        }

                                                Surface(
                                                        shape = shape,
                                                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                                                        modifier = Modifier.padding(bottom = 2.dp),
                                                ) {
                                                        when (preference) {
                                                                is Preference.Simple -> {
                                                                        PreferenceView(
                                                                                icon = painterResource(preference.icon),
                                                                                title = stringResource(preference.title),
                                                                                summary = stringResource(preference.summary),
                                                                                onClick = {
                                                                                        fragment ?: return@PreferenceView
                                                                                        handleUiEvent(
                                                                                                SettingsUiEvent.OnPreferenceClick(
                                                                                                        preference,
                                                                                                        null,
                                                                                                ),
                                                                                        )
                                                                                },
                                                                        )
                                                                }

                                                                is Preference.Switch -> {
                                                                        PreferenceSwitchView(
                                                                                preference = preference,
                                                                                onSwitchChange = { value ->
                                                                                        fragment ?: return@PreferenceSwitchView
                                                                                        handleUiEvent(
                                                                                                SettingsUiEvent.OnPreferenceClick(
                                                                                                        preference,
                                                                                                        value,
                                                                                                ),
                                                                                        )
                                                                                },
                                                                        )
                                                                }

                                                                is Preference.Enum<*> -> {
                                                                        PreferenceEnumView(
                                                                                preference = preference,
                                                                                onItemSelected = { value ->
                                                                                        fragment ?: return@PreferenceEnumView
                                                                                        handleUiEvent(
                                                                                                SettingsUiEvent.OnPreferenceClick(
                                                                                                        preference,
                                                                                                        value,
                                                                                                ),
                                                                                        )
                                                                                },
                                                                        )
                                                                }

                                                                is Preference.DynamicSummary -> {
                                                                        PreferenceDynamicSummaryView(
                                                                                preference = preference,
                                                                                status = syncConfigStatus,
                                                                                trashCount = trashCount,
                                                                                onClick = {
                                                                                        fragment ?: return@PreferenceDynamicSummaryView
                                                                                        handleUiEvent(
                                                                                                SettingsUiEvent.OnPreferenceClick(
                                                                                                        preference,
                                                                                                        null,
                                                                                                ),
                                                                                        )
                                                                                },
                                                                        )
                                                                }

                                                                is Preference.Info -> {
                                                                        PreferenceInfoView(
                                                                                preference = preference,
                                                                                summary = infoSummaries[preference.key],
                                                                        )
                                                                }
                                                        }
                                                }
                                        }
                                }
                        }

                        // Sprint 8 / TODO #15 — Semantic Search settings section
                        val context = androidx.compose.ui.platform.LocalContext.current
                        val config = remember { onlasdan.gallery.settings.data.Config(context) }
                        SemanticSearchSettings(
                                config = config,
                                onToggleChanged = { enabled ->
                                        config.semanticSearchEnabled = enabled
                                },
                        )
                }
        }
}

@Composable
fun PreferenceSectionView(
        section: PreferenceSection,
        modifier: Modifier = Modifier,
        content: @Composable ColumnScope.() -> Unit,
) {
        Column(
                modifier = modifier,
        ) {
                Text(
                        text = stringResource(section.title),
                        style = MaterialTheme.typography.titleLarge,
                        modifier =
                                Modifier
                                        .padding(
                                                horizontal = 30.dp,
                                        ),
                )

                if (section.summary != null) {
                        Text(
                                text = stringResource(section.summary),
                                fontSize = 16.sp,
                                color = MaterialTheme.colorScheme.secondary,
                                modifier =
                                        Modifier
                                                .padding(
                                                        horizontal = 30.dp,
                                                ),
                        )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Column(
                        modifier = Modifier.padding(horizontal = 15.dp),
                ) {
                        content()
                }
        }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T : SettingsEnum> PreferenceEnumView(
        preference: Preference.Enum<T>,
        onItemSelected: (T) -> Unit,
        modifier: Modifier = Modifier,
) {
        val preferencesValues = LocalPreferencesValues.current

        var showDialog by remember { mutableStateOf(false) }

        val rawValue = preferencesValues[preference.key] as? String ?: preference.default.value
        val value = preference.possibleValues.find { it.value == rawValue } ?: preference.default

        PreferenceView(
                icon = painterResource(preference.icon),
                title = stringResource(preference.title),
                summary = stringResource(value.label),
                onClick = { showDialog = true },
                modifier = modifier,
        )

        if (showDialog) {
                AlertDialog(
                        onDismissRequest = { showDialog = false },
                        confirmButton = {},
                        dismissButton = {
                                TextButton(
                                        onClick = { showDialog = false },
                                ) {
                                        Text(stringResource(R.string.common_cancel))
                                }
                        },
                        title = {
                                Text(
                                        text = stringResource(preference.title),
                                )
                        },
                        text = {
                                Column {
                                        for (v in preference.possibleValues) {
                                                Row(
                                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        modifier =
                                                                Modifier
                                                                        .clip(CircleShape)
                                                                        .clickable {
                                                                                showDialog = false
                                                                                onItemSelected(v)
                                                                        },
                                                ) {
                                                        RadioButton(
                                                                selected = value == v,
                                                                onClick = {
                                                                        showDialog = false
                                                                        onItemSelected(v)
                                                                },
                                                        )

                                                        Text(
                                                                text = stringResource(v.label),
                                                                style = MaterialTheme.typography.bodyLarge,
                                                                modifier = Modifier.weight(1f),
                                                        )
                                                }
                                        }
                                }
                        },
                )
        }
}

@Composable
fun PreferenceSwitchView(
        preference: Preference.Switch,
        onSwitchChange: (Boolean) -> Unit,
        modifier: Modifier = Modifier,
) {
        val preferencesValues = LocalPreferencesValues.current

        val summary = stringResource(preference.summary)
        val value = preferencesValues[preference.key] as? Boolean ?: preference.default

        PreferenceView(
                icon = painterResource(preference.icon),
                title = stringResource(preference.title),
                summary = summary,
                trailing = {
                        Switch(
                                checked = value,
                                onCheckedChange = {
                                        onSwitchChange(it)
                                },
                        )
                },
                onClick = {
                        onSwitchChange(!value)
                },
                modifier = modifier,
        )
}

@Composable
fun PreferenceView(
        icon: Painter,
        title: String,
        summary: String,
        modifier: Modifier = Modifier,
        onClick: (() -> Unit)? = null,
        trailing: (@Composable () -> Unit)? = null,
) {
        Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(15.dp),
                modifier =
                        modifier
                                .clickable(enabled = onClick != null) {
                                        onClick?.invoke()
                                }.fillMaxWidth()
                                .padding(
                                        horizontal = 15.dp,
                                        vertical = 12.dp,
                                ),
        ) {
                Surface(
                        modifier = Modifier.size(36.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                ) {
                        Box(
                                contentAlignment = Alignment.Center,
                        ) {
                                Icon(
                                        painter = icon,
                                        contentDescription = null,
                                        modifier = Modifier.padding(8.dp),
                                )
                        }
                }

                Column(
                        modifier = Modifier.weight(1f),
                ) {
                        Text(
                                text = title,
                                style = MaterialTheme.typography.bodyLarge,
                        )

                        Text(
                                text = summary,
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.outline,
                        )
                }

                if (trailing != null) {
                        trailing()
                }
        }
}

/**
 * Variant of [PreferenceView] whose subtitle is computed from [SyncConfigStatus].
 * @since PR1 sync addendum (Settings UI)
 */
@Composable
fun PreferenceDynamicSummaryView(
        preference: Preference.DynamicSummary,
        status: SyncConfigStatus,
        onClick: () -> Unit,
        modifier: Modifier = Modifier,
        trashCount: Int? = null,
) {
        val context = LocalContext.current
        val summary: String =
                when (preference.key) {
                        // Trash row — subtitle is the live trash count from the DB.
                        // @since v10 recycle bin
                        SettingsFragment.KEY_ACTION_TRASH -> {
                                val count = trashCount
                                if (count == null) {
                                        context.getString(R.string.settings_trash_summary)
                                } else if (count == 0) {
                                        context.getString(R.string.settings_trash_empty_summary)
                                } else {
                                        context.getString(R.string.settings_trash_count_summary, count)
                                }
                        }
                        // Cloud Sync row — subtitle is the rclone config status.
                        else ->
                                when (status) {
                                        is SyncConfigStatus.NotConfigured ->
                                                context.getString(R.string.settings_cloud_sync_not_configured)
                                        SyncConfigStatus.Validating ->
                                                context.getString(R.string.settings_cloud_sync_validating)
                                        is SyncConfigStatus.Configured ->
                                                context.getString(R.string.settings_cloud_sync_configured, status.remoteName)
                                        is SyncConfigStatus.AwaitingRemoteChoice ->
                                                context.getString(R.string.settings_cloud_sync_awaiting_choice)
                                        is SyncConfigStatus.Invalid -> status.reason.toSummary(context)
                                        is SyncConfigStatus.ImportFailed -> status.reason.toSummary(context)
                                }
                }

        PreferenceView(
                icon = painterResource(preference.icon),
                title = stringResource(preference.title),
                summary = summary,
                onClick = onClick,
                modifier = modifier,
        )
}

/**
 * Read-only info row whose subtitle comes from a runtime `Map<String, String>`
 * (see [SettingsUiState.infoSummaries]) rather than a static string resource.
 * Falls back to [Preference.Info.summaryPlaceholder] when no entry exists
 * for the row's key (e.g. before the first stats refresh completes).
 *
 * @since Item 4 — storage analytics
 */
@Composable
fun PreferenceInfoView(
        preference: Preference.Info,
        summary: String?,
        modifier: Modifier = Modifier,
) {
        val resolved = summary ?: stringResource(preference.summaryPlaceholder)
        PreferenceView(
                icon = painterResource(preference.icon),
                title = stringResource(preference.title),
                summary = resolved,
                onClick = null,
                modifier = modifier,
        )
}

/**
 * Render an [RcloneConfigManager.Status.InvalidReason] as a user-facing summary string.
 * @since PR1 sync addendum (Settings UI)
 */
private fun RcloneConfigManager.Status.InvalidReason.toSummary(context: android.content.Context): String =
        when (this) {
                RcloneConfigManager.Status.InvalidReason.NO_SECTIONS ->
                        context.getString(R.string.settings_cloud_sync_invalid_no_sections)
                RcloneConfigManager.Status.InvalidReason.UNREADABLE ->
                        context.getString(R.string.settings_cloud_sync_invalid_unreadable)
        }

/**
 * F-ENC-001 — Persistent warning banner shown when [SqlCipherKeyProvider] fell
 * back to plaintext SharedPreferences because Android Keystore was unavailable.
 *
 * The banner explains that the vault DB is effectively plaintext at-rest and
 * recommends the user re-install the app or contact support. It does NOT auto-
 * dismiss — the user must resolve the underlying Keystore issue (factory reset,
 * re-install) for the flag to clear.
 */
@Composable
private fun KeystoreFallbackBanner() {
        androidx.compose.material3.Surface(
                color = androidx.compose.material3.MaterialTheme.colorScheme.errorContainer,
                shape = androidx.compose.material3.MaterialTheme.shapes.medium,
                modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
        ) {
                androidx.compose.foundation.layout.Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                        androidx.compose.material3.Text(
                                text = "Security warning: Database encryption disabled",
                                style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                                color = androidx.compose.material3.MaterialTheme.colorScheme.onErrorContainer,
                        )
                        androidx.compose.material3.Text(
                                text = "Android Keystore is unavailable on this device. Your photo metadata " +
                                        "DB is stored in plaintext. Re-install the app or contact support to " +
                                        "restore hardware-backed encryption.",
                                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                                color = androidx.compose.material3.MaterialTheme.colorScheme.onErrorContainer,
                        )
                }
        }
}

@Preview(heightDp = 1000)
@Composable
private fun Preview() {
        val context = LocalContext.current
        CompositionLocalProvider(LocalConfig provides Config(context)) {
                AppTheme {
                        SettingsContent(
                                screenConfig = PreferenceScreenConfig(PreferenceScreenConfigContent),
                                handleUiEvent = {},
                        )
                }
        }
}

@Preview(heightDp = 1000, uiMode = UI_MODE_NIGHT_YES)
@Composable
private fun PreviewDark() {
        val context = LocalContext.current
        CompositionLocalProvider(LocalConfig provides Config(context)) {
                AppTheme {
                        SettingsContent(
                                screenConfig = PreferenceScreenConfig(PreferenceScreenConfigContent),
                                handleUiEvent = {},
                        )
                }
        }
}
