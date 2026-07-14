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

package onlasdan.gallery.settings.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import onlasdan.gallery.BuildConfig
import onlasdan.gallery.encryption.domain.models.VaultProtectionType
import onlasdan.gallery.settings.domain.models.StartPage
import onlasdan.gallery.settings.domain.models.SystemDesignEnum
import onlasdan.gallery.telemetry.domain.TELEMETRY_ENABLED_BY_DEFAULT

/**
 * Manages reading and writing with the config file.
 *
 * @since 1.0.0
 * @author PhotoZ
 */
class Config(
	context: Context,
) {
	private val coroutineScope = CoroutineScope(Dispatchers.IO)

	private val preferences: SharedPreferences = context.getSharedPreferences(FILE_NAME, MODE)

	val values: Map<String, *>
		get() = preferences.all

	val valuesFlow: Flow<Map<String, *>> =
		callbackFlow {
			send(preferences.all)

			val listener =
				SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, _ ->
					coroutineScope.launch { send(sharedPreferences.all) }
				}
			preferences.registerOnSharedPreferenceChangeListener(listener)

			awaitClose {
				preferences.unregisterOnSharedPreferenceChangeListener(listener)
			}
		}

	/**
	 * Determines if the app has started before.
	 */
	var systemFirstStart: Boolean
		get() = getBoolean(SYSTEM_FIRST_START, SYSTEM_FIRST_START_DEFAULT)
		set(value) = putBoolean(SYSTEM_FIRST_START, value)

	/**
	 * Set to true after a fresh-install login via recovery phrase (PHRASE_ONLY flow).
	 * The VMK is in memory (via sessionRepository) but NO VaultProtection(Password)
	 * row exists locally. SetupFragment detects this flag and calls
	 * [VaultService.createPasswordProtectionFromSession] (wrap existing VMK)
	 * instead of [VaultService.create] (generate new VMK).
	 *
	 * Without this, the next app open would see canUnlock()=false → SETUP →
	 * new VMK → all previously-encrypted photos undecryptable (data loss).
	 *
	 * @since anti-data-loss — phrase restore must persist Password protection
	 */
	var pendingPasswordSetup: Boolean
		get() = getBoolean(PENDING_PASSWORD_SETUP, PENDING_PASSWORD_SETUP_DEFAULT)
		set(value) = putBoolean(PENDING_PASSWORD_SETUP, value)

	/**
	 * The version code of the last app version.
	 * Updates after showing new features.
	 */
	var systemLastFeatureVersionCode: Int
		get() = getInt(SYSTEM_LAST_FEATURE_VERSION_CODE, SYSTEM_LAST_FEATURE_VERSION_CODE_DEFAULT)
		set(value) = putInt(SYSTEM_LAST_FEATURE_VERSION_CODE, value)

    /*
     * Sets the app design to "light", "dark" or "system"
     */
	var systemDesign: SystemDesignEnum
		get() = SystemDesignEnum.fromValue(getString(SYSTEM_DESIGN, SYSTEM_DESIGN_DEFAULT.value))
		set(value) = putString(SYSTEM_DESIGN, value.value)

	/**
	 * Determines the start page of the gallery.
	 */
	var galleryStartPage: StartPage
		get() = StartPage.fromValue(getString(GALLERY_START_PAGE, GALLERY_START_PAGE_DEFAULT.value))
		set(value) = putString(GALLERY_START_PAGE, value.value)

	/**
	 * Determines if screenshots should be allowed.
	 */
	var securityAllowScreenshots: Boolean
		get() = getBoolean(SECURITY_ALLOW_SCREENSHOTS, SECURITY_ALLOW_SCREENSHOTS_DEFAULT)
		set(value) = putBoolean(SECURITY_ALLOW_SCREENSHOTS, value)

	/**
	 * Timeout to auto lock when in background.
	 */
	var securityLockTimeout: Int
		get() = getIntFromString(SECURITY_LOCK_TIMEOUT, SECURITY_LOCK_TIMEOUT_DEFAULT)
		set(value) = putString(SECURITY_LOCK_TIMEOUT, value.toString())

	/**
	 * Launch code to launch from phone dialer.
	 */
	var securityDialLaunchCode: String?
		get() = getString(SECURITY_DIAL_LAUNCH_CODE, SECURITY_DIAL_LAUNCH_CODE_DEFAULT)
		set(value) = putString(SECURITY_DIAL_LAUNCH_CODE, value!!)

	/**
	 * Determines if files should be deleted after importing them.
	 */
	var deleteImportedFiles: Boolean
		get() = getBoolean(ADVANCED_DELETE_IMPORTED_FILES, ADVANCED_DELETE_IMPORTED_FILES_DEFAULT)
		set(value) = putBoolean(ADVANCED_DELETE_IMPORTED_FILES, value)

	/**
	 * Determines if files should be deleted after exporting them.
	 */
	var deleteExportedFiles: Boolean
		get() = getBoolean(ADVANCED_DELETE_EXPORTED_FILES, ADVANCED_DELETE_EXPORTED_FILES_DEFAULT)
		set(value) = putBoolean(ADVANCED_DELETE_EXPORTED_FILES, value)

	var timestampLastRecoveryStart: Long
		get() = getLong(TIMESTAMP_LAST_RECOVERY_START, TIMESTAMP_LAST_RECOVERY_START_DEFAULT)
		set(value) = putLong(TIMESTAMP_LAST_RECOVERY_START, value)

	/**
	 * Sprint 3 / (roadmap #6) — SQLCipher migration completed flag.
	 *
	 * `true` once the one-time plaintext → SQLCipher-encrypted migration
	 * has finished successfully (or fresh install where no migration was
	 * needed). Used by [SqlCipherMigrationHelper] to skip the migration
	 * check on subsequent app starts.
	 *
	 * @since v16 — Sprint 3 / SQLCipher
	 */
	var sqlCipherMigrationDone: Boolean
		get() = getBoolean(SQLCIPHER_MIGRATION_DONE, SQLCIPHER_MIGRATION_DONE_DEFAULT)
		set(value) = putBoolean(SQLCIPHER_MIGRATION_DONE, value)

	/**
	 * F-ENC-001 — True when SQLCipher fell back to plaintext SharedPreferences
	 * because the Android Keystore was unavailable. The Settings screen surfaces
	 * a persistent warning banner when this is true.
	 *
	 * @since F-ENC-001 — Keystore fallback UI warning
	 */
	var keystoreFallbackActive: Boolean
		get() = getBoolean(KEYSTORE_FALLBACK_ACTIVE, KEYSTORE_FALLBACK_ACTIVE_DEFAULT)
		set(value) = putBoolean(KEYSTORE_FALLBACK_ACTIVE, value)

	/**
	 * Timestamp (epoch ms) the app last went to background. Persisted across process death
	 * so that the auto-lock timer in [onlasdan.gallery.BaseApplication.onStart] still fires
	 * after a force-close + reopen. Reset to `0L` once the app has been locked, so the
	 * next cold start doesn't immediately re-lock.
	 *
	 * @since Batch 1 / Item 1 — auto-lock timer fix
	 */
	var lastBackgroundedAt: Long
		get() = getLong(LAST_BACKGROUNDED_AT, LAST_BACKGROUNDED_AT_DEFAULT)
		set(value) = putLong(LAST_BACKGROUNDED_AT, value)

	var biometricAuthenticationEnabled: Boolean
		get() = getBoolean(SECURITY_BIOMETRIC_AUTHENTICATION_ENABLED, SECURITY_BIOMETRIC_AUTHENTICATION_ENABLED_DEFAULT)
		set(value) = putBoolean(SECURITY_BIOMETRIC_AUTHENTICATION_ENABLED, value)

	var imageViewerLoopVideos: Boolean
		get() = getBoolean(IMAGE_VIEWER_LOOP_VIDEO, IMAGE_VIEWER_LOOP_VIDEO_DEFAULT)
		set(value) = putBoolean(IMAGE_VIEWER_LOOP_VIDEO, value)

	var imageViewerMuteVideoPlayer: Boolean
		get() = getBoolean(IMAGE_VIEWER_MUTE_VIDEO_PLAYER, IMAGE_VIEWER_MUTE_VIDEO_PLAYER_DEFAULT)
		set(value) = putBoolean(IMAGE_VIEWER_MUTE_VIDEO_PLAYER, value)

	var imageViewerPlaybackSpeed: Float
		get() = getFloat(IMAGE_VIEWER_PLAYBACK_SPEED, IMAGE_VIEWER_PLAYBACK_SPEED_DEFAULT)
		set(value) = putFloat(IMAGE_VIEWER_PLAYBACK_SPEED, value)

	var telemetryEnabled: Boolean
		get() = getBoolean(TELEMETRY_ENABLED, TELEMETRY_ENABLED_BY_DEFAULT)
		set(value) = putBoolean(TELEMETRY_ENABLED, value)

	var telemetryAskedForOptIn: Boolean
		get() = getBoolean(TELEMETRY_ASSSKED_FOR_OPT_IN, TELEMETRY_ASKED_FOR_OPT_IN_DEFAULT)
		set(value) = putBoolean(TELEMETRY_ASSSKED_FOR_OPT_IN, value)

	var inAppReviewRequested: Boolean
		get() = getBoolean(IN_APP_REVIEW_REQUESTED, false)
		set(value) = putBoolean(IN_APP_REVIEW_REQUESTED, value)

	/**
	 * The user-chosen rclone remote name. `null` means no remote has been chosen yet — sync
	 * is dormant until the user picks one via Settings → Cloud Sync.
	 *
	 * @since PR1 sync addendum (remote picker)
	 */
	var syncChosenRemote: String?
		get() = getString(SYNC_CHOSEN_REMOTE, null)
		set(value) = putString(SYNC_CHOSEN_REMOTE, value)

	/**
	 * Whether photos are auto-uploaded to the cloud as soon as they're
	 * imported. When `false`, the user must trigger uploads manually
	 * (the worker's enqueue() is a no-op, so newly-imported photos sit
	 * in LOCAL_ONLY until the user re-enables auto-upload or runs a
	 * manual "Restore from backup" pass that re-queues everything).
	 *
	 * Replaces the hardcoded [onlasdan.gallery.sync.domain.SyncConfig.autoUploadEnabled]
	 * flag with a user-configurable preference. The hardcoded default is
	 * `true` to preserve existing behaviour.
	 *
	 * @since sync settings feature — auto-upload toggle
	 */
	var syncAutoUpload: Boolean
		get() = getBoolean(SYNC_AUTO_UPLOAD, SYNC_AUTO_UPLOAD_DEFAULT)
		set(value) = putBoolean(SYNC_AUTO_UPLOAD, value)

	/**
	 * When `true`, sync only runs over unmetered networks (Wi-Fi or
	 * Ethernet). Mobile data uploads would burn through the user's
	 * data plan, so this is the safer default for users on metered
	 * connections. Implemented as a `NetworkType.UNMETERED` constraint
	 * on the WorkManager request — WorkManager won't even start the
	 * worker until the constraint is satisfied.
	 *
	 * @since sync settings feature — WiFi-only toggle
	 */
	var syncWifiOnly: Boolean
		get() = getBoolean(SYNC_WIFI_ONLY, SYNC_WIFI_ONLY_DEFAULT)
		set(value) = putBoolean(SYNC_WIFI_ONLY, value)

	/**
	 * Whether to delete the local encrypted original file after a
	 * successful upload. Frees up device storage at the cost of needing
	 * a re-download to view the photo offline (the gallery tile shows
	 * the local thumbnail, which is kept regardless of this setting).
	 *
	 * Replaces the hardcoded [onlasdan.gallery.sync.domain.SyncConfig.deleteLocalAfterUpload]
	 * flag with a user-configurable preference. Default `false` (keep
	 * local copies) to preserve existing behaviour and avoid surprising
	 * data loss.
	 *
	 * ─── Item 2 (toggle restored) ───────────────────────────────────
	 * The toggle is now surfaced again in Settings (Cloud Sync section).
	 * When ON, [onlasdan.gallery.sync.work.PhotoSyncWorker] deletes the
	 * local `.crypt` original after a verified upload. When OFF, the
	 * local copy is kept for offline access and the user can reclaim
	 * space later via the "Clean cached originals" button.
	 *
	 * @since sync settings feature — delete-after-upload toggle
	 * @since Item 2 — toggle restored; deletion is gated on this setting
	 */
	var syncDeleteAfterUpload: Boolean
		get() = getBoolean(SYNC_DELETE_AFTER_UPLOAD, SYNC_DELETE_AFTER_UPLOAD_DEFAULT)
		set(value) = putBoolean(SYNC_DELETE_AFTER_UPLOAD, value)

	/**
	 * When `true`, [onlasdan.gallery.sync.work.PhotoSyncWorker] performs an
	 * EXPENSIVE post-upload verification: it downloads the freshly-uploaded
	 * remote file to a cache temp file, decrypts it with the VMK, computes
	 * the SHA-256 of the decrypted plaintext, and compares it to the photo's
	 * stored `contentHash`. If they don't match, the upload is marked
	 * `UPLOAD_FAILED` and an exception is thrown so WorkManager retries.
	 *
	 * This is the only way to verify uploads against backends that don't
	 * support server-side hashsum (e.g. Koofr). It's OFF by default because
	 * it doubles the bandwidth cost per upload (download = upload size again).
	 * Power users / paranoiac users can turn it on.
	 *
	 * @since Batch 1 / Item 3 — hash verification for upload
	 */
	var syncVerifyHash: Boolean
		get() = getBoolean(SYNC_VERIFY_HASH, SYNC_VERIFY_HASH_DEFAULT)
		set(value) = putBoolean(SYNC_VERIFY_HASH, value)

	// ─── Sprint 6 / M5 — Cache rotation settings ──────────────────────────

	/**
	 * Max age in days for cached thumbnails. Thumbnails for UPLOADED photos
	 * not accessed in this many days are deleted on app start. 0 disables.
	 *
	 * @since v13 — Sprint 6 / M5 cache rotation
	 */
	var cacheMaxAgeDays: Int
		get() = getInt(CACHE_MAX_AGE_DAYS, CACHE_MAX_AGE_DAYS_DEFAULT)
		set(value) = putInt(CACHE_MAX_AGE_DAYS, value)

	/**
	 * Max total size in MB for cached thumbnails. When exceeded, oldest-
	 * accessed thumbnails (for UPLOADED photos) are deleted. 0 disables.
	 *
	 * @since v13 — Sprint 6 / M5 cache rotation
	 */
	var cacheMaxSizeMb: Int
		get() = getInt(CACHE_MAX_SIZE_MB, CACHE_MAX_SIZE_MB_DEFAULT)
		set(value) = putInt(CACHE_MAX_SIZE_MB, value)

	// ─── Sprint 7 / P2 — Break-in detection ───────────────────────────────

	/** Failed unlock attempt count since last successful unlock. @since v13 P2 */
	var breakInFailedAttemptCount: Int
		get() = getInt(BREAK_IN_FAILED_COUNT, BREAK_IN_FAILED_COUNT_DEFAULT)
		set(value) = putInt(BREAK_IN_FAILED_COUNT, value)

	/** Epoch-ms of the last failed unlock attempt. 0 = none. @since v13 P2 */
	var breakInLastFailedAttemptAt: Long
		get() = getLong(BREAK_IN_LAST_FAILED_AT, BREAK_IN_LAST_FAILED_AT_DEFAULT)
		set(value) = putLong(BREAK_IN_LAST_FAILED_AT, value)

	// ─── Sprint 9 / L6 — Semantic search toggle ───────────────────────────

	/** Enable/disable on-device TFLite tag inference at import time. @since v14 L6 */
	var semanticSearchEnabled: Boolean
		get() = getBoolean(SEMANTIC_SEARCH_ENABLED, SEMANTIC_SEARCH_ENABLED_DEFAULT)
		set(value) = putBoolean(SEMANTIC_SEARCH_ENABLED, value)

	/**
	 * Sprint 8 / (roadmap #15) — TFLite model download URL.
	 * Configurable so users can point to a mirror if the default is slow.
	 * @since Sprint 8 — TFLite activation
	 */
	var tfliteModelUrl: String
		get() = getString(TFLITE_MODEL_URL, TFLITE_MODEL_URL_DEFAULT) ?: TFLITE_MODEL_URL_DEFAULT
		set(value) = putString(TFLITE_MODEL_URL, value)

	/**
	 * Sprint 8 / (roadmap #15) — TFLite model download status.
	 * - 0 = not downloaded
	 * - 1 = downloading
	 * - 2 = downloaded (ready to use)
	 * @since Sprint 8 — TFLite activation
	 */
	var tfliteModelStatus: Int
		get() = getInt(TFLITE_MODEL_STATUS, TFLITE_MODEL_STATUS_DEFAULT)
		set(value) = putInt(TFLITE_MODEL_STATUS, value)

	// ─── Sprint 10 / L3 — Self-destruct timer ─────────────────────────────

	/** Self-destruct timer in days (0 = disabled). @since v14 L3 */
	var selfDestructDays: Int
		get() = getInt(SELF_DESTRUCT_DAYS, SELF_DESTRUCT_DAYS_DEFAULT)
		set(value) = putInt(SELF_DESTRUCT_DAYS, value)

	/** Epoch-ms of last successful unlock. @since v14 L3 */
	var lastUnlockAt: Long
		get() = getLong(LAST_UNLOCK_AT, LAST_UNLOCK_AT_DEFAULT)
		set(value) = putLong(LAST_UNLOCK_AT, value)

	/** Panic dial code for emergency wipe. Empty = disabled. @since v14 P3 */
	var panicDialCode: String
		get() = getString(PANIC_DIAL_CODE, PANIC_DIAL_CODE_DEFAULT) ?: PANIC_DIAL_CODE_DEFAULT
		set(value) = putString(PANIC_DIAL_CODE, value)

	/**
	 * The repo ID this device is bound to. Set by [onlasdan.gallery.sync.rclone.RepoManager]
	 * after a successful register or login. `null` means no repo has been set up yet.
	 *
	 * @since PR1 sync — mandatory repo setup
	 */
	var repoId: String?
		get() = getString(REPO_ID, null)
		set(value) = putString(REPO_ID, value)

	/**
	 * Whether the repo session has been confirmed (marker file verified on remote).
	 * This is the gate for gallery access — the gallery is unreachable until this is true.
	 *
	 * Note: this is a local flag. On cold start, [onlasdan.gallery.sync.rclone.RepoManager.revalidateRepo]
	 * should be called to confirm the remote is still reachable.
	 *
	 * @since PR1 sync — mandatory repo setup
	 */
	var repoConfirmed: Boolean
		get() = getBoolean(REPO_CONFIRMED, false)
		set(value) = putBoolean(REPO_CONFIRMED, value)

	// In memory flags
	var justFinishedSetup: Boolean = false
	var lastUsedUnlockMethod: VaultProtectionType? = null

	// --- Legacy

	var legacyCurrentlyMigrating: Boolean
		get() = getBoolean("legacy^currentlyMigrating", false)
		set(value) = putBoolean("legacy^currentlyMigrating", value)

	/**
	 * Password hash to check when unlocking.
	 */
	@Deprecated("Only needed for migration")
	var legacyPasswordHash: String?
		get() = getString(SECURITY_PASSWORD, SECURITY_PASSWORD_DEFAULT)
		set(value) = putString(SECURITY_PASSWORD, value)

	@Deprecated("Only needed for migration")
	var legacyUserSalt: String?
		get() = getString("user^salt", null)
		set(value) = putString("user^salt", value)

	// --- helpers

	fun getString(
		key: String,
		default: String?,
	) = preferences.getString(key, default)

	fun getInt(
		key: String,
		default: Int,
	) = preferences.getInt(key, default)

	fun getIntFromString(
		key: String,
		default: Int,
	): Int {
		val stringValue = preferences.getString(key, default.toString())
		return stringValue?.toInt() ?: default
	}

	fun getLong(
		key: String,
		default: Long,
	): Long = preferences.getLong(key, default)

	fun getBoolean(
		key: String,
		default: Boolean,
	) = preferences.getBoolean(key, default)

	fun getFloat(
		key: String,
		default: Float,
	) = preferences.getFloat(key, default)

	fun putString(
		key: String,
		value: String?,
	) {
		preferences.edit {
			putString(key, value)
		}
	}

	fun putInt(
		key: String,
		value: Int,
	) {
		preferences.edit {
			putInt(key, value)
		}
	}

	fun putBoolean(
		key: String,
		value: Boolean,
	) {
		preferences.edit {
			putBoolean(key, value)
		}
	}

	fun putLong(
		key: String,
		value: Long,
	) {
		preferences.edit {
			putLong(key, value)
		}
	}

	fun putFloat(
		key: String,
		value: Float,
	) {
		preferences.edit {
			putFloat(key, value)
		}
	}

	// Single source of truth for config keys and defaults. Always use these constants
	companion object {
		/**
		 * The filename used to store the preferences.
		 */
		const val FILE_NAME = "${BuildConfig.APPLICATION_ID}_preferences"

		/**
		 * Always use private mode to open preferences.
		 */
		const val MODE = Context.MODE_PRIVATE

		const val SYSTEM_FIRST_START = "system^firstStart"
		const val SYSTEM_FIRST_START_DEFAULT = true

		/** @since anti-data-loss — phrase restore must persist Password protection */
		const val PENDING_PASSWORD_SETUP = "system^pendingPasswordSetup"
		const val PENDING_PASSWORD_SETUP_DEFAULT = false

		const val SYSTEM_LAST_FEATURE_VERSION_CODE = "system^lastFeatureVersionCode"
		const val SYSTEM_LAST_FEATURE_VERSION_CODE_DEFAULT = 0

		const val SYSTEM_DESIGN = "system^design"
		val SYSTEM_DESIGN_DEFAULT = SystemDesignEnum.System

		const val GALLERY_START_PAGE = "gallery^startPage"
		val GALLERY_START_PAGE_DEFAULT = StartPage.AllFiles

		const val SECURITY_ALLOW_SCREENSHOTS = "security^allowScreenshots"
		val SECURITY_ALLOW_SCREENSHOTS_DEFAULT = BuildConfig.DEBUG

		const val SECURITY_PASSWORD = "security^password"
		const val SECURITY_PASSWORD_DEFAULT = ""

		const val SECURITY_LOCK_TIMEOUT = "security^lockTimeout"
		const val SECURITY_LOCK_TIMEOUT_DEFAULT = 300000

		const val SECURITY_DIAL_LAUNCH_CODE = "security^dialLaunchCode"
		const val SECURITY_DIAL_LAUNCH_CODE_DEFAULT = "1337"

		const val ADVANCED_DELETE_IMPORTED_FILES = "advanced^deleteImportedFiles"
		const val ADVANCED_DELETE_IMPORTED_FILES_DEFAULT = false

		const val ADVANCED_DELETE_EXPORTED_FILES = "advanced^deleteExportedFiles"
		const val ADVANCED_DELETE_EXPORTED_FILES_DEFAULT = false

		const val TIMESTAMP_LAST_RECOVERY_START = "internal^timestampLastRecoveryStart"
		const val TIMESTAMP_LAST_RECOVERY_START_DEFAULT = 0L

		/**
		 * Persisted background timestamp for auto-lock — survives process death.
		 * @since Batch 1 / Item 1 — auto-lock timer fix
		 */
		const val LAST_BACKGROUNDED_AT = "internal^lastBackgroundedAt"
		const val LAST_BACKGROUNDED_AT_DEFAULT = 0L

		const val SECURITY_BIOMETRIC_AUTHENTICATION_ENABLED = "security^biometricAuthenticationEnabled"
		const val SECURITY_BIOMETRIC_AUTHENTICATION_ENABLED_DEFAULT = false

		const val IMAGE_VIEWER_LOOP_VIDEO = "imageViewer^loopVideo"
		const val IMAGE_VIEWER_LOOP_VIDEO_DEFAULT = false

		const val IMAGE_VIEWER_MUTE_VIDEO_PLAYER = "imageViewer^muteVideoPlayer"
		const val IMAGE_VIEWER_MUTE_VIDEO_PLAYER_DEFAULT = false

		const val IMAGE_VIEWER_PLAYBACK_SPEED = "imageViewer^playbackSpeed"
		const val IMAGE_VIEWER_PLAYBACK_SPEED_DEFAULT = 1f

		const val TELEMETRY_ENABLED = "telemetry^enabled"

		const val TELEMETRY_ASSSKED_FOR_OPT_IN = "telemetry^askedForOptIn"
		const val TELEMETRY_ASKED_FOR_OPT_IN_DEFAULT = false

		const val IN_APP_REVIEW_REQUESTED = "internal^inAppReviewRequested"

		/**
		 * SharedPreferences key for the user-chosen rclone remote name.
		 * @since PR1 sync addendum (remote picker)
		 */
		const val SYNC_CHOSEN_REMOTE = "sync^chosenRemote"

		/** @since PR1 sync — mandatory repo setup */
		const val REPO_ID = "sync^repoId"

		/** @since PR1 sync — mandatory repo setup */
		const val REPO_CONFIRMED = "sync^repoConfirmed"

		// ─── Sync user preferences (settings UI) ────────────────────────────
		// Three toggles surfaced under Settings → Cloud Sync. Replacements
		// for the hardcoded flags previously in [SyncConfig].
		// @since sync settings feature

		const val SYNC_AUTO_UPLOAD = "sync^autoUpload"
		const val SYNC_AUTO_UPLOAD_DEFAULT = true

		const val SYNC_WIFI_ONLY = "sync^wifiOnly"
		const val SYNC_WIFI_ONLY_DEFAULT = false

		const val SYNC_DELETE_AFTER_UPLOAD = "sync^deleteAfterUpload"
		const val SYNC_DELETE_AFTER_UPLOAD_DEFAULT = false

		/**
		 * SharedPreferences key for the optional hash-verification-after-upload toggle.
		 * @since Batch 1 / Item 3 — hash verification for upload
		 */
		const val SYNC_VERIFY_HASH = "sync^verifyHash"
		const val SYNC_VERIFY_HASH_DEFAULT = false

		/**
		 * Sprint 6 / M5 — Cache rotation: max age in days for cached thumbnails.
		 *
		 * Thumbnails for photos with syncState=UPLOADED that haven't been
		 * accessed in this many days are deleted from local storage to free
		 * up space (the encrypted original stays on the remote; the thumbnail
		 * can be re-downloaded on demand via SyncRestorer).
		 *
		 * Default 30 days. Set to 0 to disable cache rotation entirely.
		 *
		 * @since v13 — Sprint 6 / M5 cache rotation
		 */
		const val CACHE_MAX_AGE_DAYS = "cache^maxAgeDays"
		const val CACHE_MAX_AGE_DAYS_DEFAULT = 30

		/**
		 * Sprint 6 / M5 — Cache rotation: max total size in MB for cached thumbnails.
		 *
		 * When the total size of cached thumbnails exceeds this limit, the
		 * oldest-accessed thumbnails (for UPLOADED photos only) are deleted
		 * until the cache is under the limit. Set to 0 to disable size-based
		 * rotation (only age-based rotation applies).
		 *
		 * Default 500 MB.
		 *
		 * @since v13 — Sprint 6 / M5 cache rotation
		 */
		const val CACHE_MAX_SIZE_MB = "cache^maxSizeMb"
		const val CACHE_MAX_SIZE_MB_DEFAULT = 500

		/**
		 * Sprint 7 / P2 — Break-in detection: failed unlock attempt count.
		 * Reset to 0 on successful unlock. Persisted so it survives app restart.
		 * @since v13 — Sprint 7 / P2 break-in detection
		 */
		const val BREAK_IN_FAILED_COUNT = "breakIn^failedCount"
		const val BREAK_IN_FAILED_COUNT_DEFAULT = 0

		/**
		 * Sprint 7 / P2 — Break-in detection: epoch-ms of last failed attempt.
		 * 0 means no failed attempts recorded.
		 * @since v13 — Sprint 7 / P2 break-in detection
		 */
		const val BREAK_IN_LAST_FAILED_AT = "breakIn^lastFailedAt"
		const val BREAK_IN_LAST_FAILED_AT_DEFAULT = 0L

		/**
		 * Sprint 9 / L6 — Enable/disable on-device semantic search.
		 * When true, new photo imports run TFLite inference to generate
		 * AI tags. When false (default), no inference runs (saves battery
		 * + storage). The `tag:` search prefix is a no-op when disabled.
		 * @since v14 — Sprint 9 / L6 semantic search
		 */
		const val SEMANTIC_SEARCH_ENABLED = "semantic^searchEnabled"
		const val SEMANTIC_SEARCH_ENABLED_DEFAULT = false

		/**
		 * Sprint 8 / (roadmap #15) — TFLite model config.
		 * @since Sprint 8 — TFLite activation
		 */
		const val TFLITE_MODEL_URL = "tflite^modelUrl"
		const val TFLITE_MODEL_URL_DEFAULT =
			"https://storage.googleapis.com/tfhub-lite-models/tensorflow/lite-model/mobilenet_v2_1.0_224/1/default/1.tflite"
		const val TFLITE_MODEL_STATUS = "tflite^modelStatus"
		const val TFLITE_MODEL_STATUS_DEFAULT = 0

		/**
		 * Sprint 10 / L3 — Self-destruct timer (days inactive).
		 * 0 = disabled (default). 7/14/30/60 = wipe after N days of no unlock.
		 * @since v14 — Sprint 10 / L3 self-destruct
		 */
		const val SELF_DESTRUCT_DAYS = "security^selfDestructDays"
		const val SELF_DESTRUCT_DAYS_DEFAULT = 0

		/**
		 * Sprint 10 / L3 — Epoch-ms of last successful unlock.
		 * Used by the self-destruct worker to compute inactivity.
		 * @since v14 — Sprint 10 / L3 self-destruct
		 */
		const val LAST_UNLOCK_AT = "security^lastUnlockAt"
		const val LAST_UNLOCK_AT_DEFAULT = 0L

		/**
		 * Sprint 7+ / P3 — Panic dial code. When the user dials this code
		 * (instead of the normal launch code), the vault is wiped.
		 * Default "9111" — distinct from the normal launch code "1337".
		 * Empty string = panic disabled.
		 * @since v14 — Sprint 7+ / P3 panic wipe
		 */
		const val PANIC_DIAL_CODE = "security^panicDialCode"
		const val PANIC_DIAL_CODE_DEFAULT = "9111"

		/**
		 * Sprint 3 / (roadmap #6) — SQLCipher migration completed flag.
		 *
		 * @since v16 — Sprint 3 / SQLCipher
		 */
		const val SQLCIPHER_MIGRATION_DONE = "sqlcipher^migrationDone"
		const val SQLCIPHER_MIGRATION_DONE_DEFAULT = false

		/**
		 * F-ENC-001 — Keystore fallback active flag.
		 *
		 * `true` when [SqlCipherKeyProvider] caught a Keystore failure and fell
		 * back to [FallbackSqlCipherKeyProvider] (which stores the SQLCipher
		 * passphrase in plaintext SharedPreferences). The Settings screen shows
		 * a persistent warning banner when this is true so the user knows their
		 * DB is effectively plaintext at-rest.
		 *
		 * Reset to `false` only by app factory reset (the Keystore key is
		 * destroyed, the fallback prefs are cleared, and the next launch
		 * generates a fresh Keystore-backed key).
		 *
		 * @since F-ENC-001 — Keystore fallback UI warning
		 */
		const val KEYSTORE_FALLBACK_ACTIVE = "sqlcipher^keystoreFallbackActive"
		const val KEYSTORE_FALLBACK_ACTIVE_DEFAULT = false
	}
}
