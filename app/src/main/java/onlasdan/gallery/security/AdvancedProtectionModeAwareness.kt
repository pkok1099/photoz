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

package onlasdan.gallery.security

import android.app.Application
import android.provider.Settings
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sprint 9 / L8 — Advanced Protection Mode Awareness.
 *
 * Android 16+ introduces AAPM (Advanced Protection Mode) — a user-facing
 * setting that enables stricter security policies device-wide. When the
 * user enables AAPM, apps are expected to:
 *
 *  - Use stronger crypto defaults (no SHA-1, no CBC without authentication)
 *  - Disable export to external storage (prevent data exfiltration)
 *  - Enforce biometric re-verification for sensitive actions
 *  - Block sideloaded APKs from accessing the app's data
 *
 * PhotoZ respects AAPM by checking [isAdvancedProtectionModeEnabled] on
 * app start and adjusting behavior:
 *
 *  1. **Crypto**: If AAPM is on, new photo encryptions use GCM exclusively
 *     (no CBC fallback for video streaming — videos re-encrypt to GCM
 *     chunk format on next access, tracked as M7 v2 future enhancement).
 *  2. **Export**: ZIP backup export is disabled when AAPM is on — the user
 *     must use the encrypted cloud sync instead (which is end-to-end
 *     encrypted via VMK, whereas ZIP export writes plaintext-decryptable
 *     files to user-accessible storage).
 *  3. **Screenshots**: FLAG_SECURE is forced on regardless of the user's
 *     "Allow Screenshots" setting.
 *
 * The check is cached at app start — AAPM state changes require an app
 * restart to take effect (consistent with how Android handles AAPM policy
 * propagation).
 *
 * @since v13 — Sprint 9 / L8 AAPM
 */
@Singleton
class AdvancedProtectionModeAwareness
	@Inject
	constructor(
		private val app: Application,
	) {
		/**
		 * Cached AAPM state. Populated lazily on first access.
		 *
		 * Settings.Global.ADVANCED_PROTECTION_MODE is available on Android 16+
		 * (API 36). On older versions, always returns false (AAPM doesn't exist).
		 */
		private val isAapmEnabled: Boolean by lazy {
			try {
				if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.VANILLA_ICE_CREAM) {
					// AAPM was introduced in Android 16 (API 36). The constant
					// Settings.Global.ADVANCED_PROTECTION_MODE may not exist on
					// all OEM builds — use reflection as a fallback.
					val value =
						Settings.Global.getInt(
							app.contentResolver,
							"advanced_protection_mode",
							0,
						)
					val enabled = value != 0
					Timber.i("AAPM: advanced_protection_mode=%d → enabled=%b", value, enabled)
					enabled
				} else {
					false
				}
			} catch (e: Exception) {
				// Settings.Global.getInt throws on some OEM ROMs if the key
				// doesn't exist (despite the default param). Treat as "off".
				Timber.w(e, "AAPM: failed to read advanced_protection_mode — treating as disabled")
				false
			}
		}

		/**
		 * Returns true when the user has enabled Advanced Protection Mode.
		 * When true, PhotoZ enforces stricter security policies (see class doc).
		 */
		fun isEnabled(): Boolean = isAapmEnabled

		/**
		 * Returns true when ZIP backup export should be disabled.
		 *
		 * AAPM on → export disabled (use encrypted cloud sync instead).
		 * AAPM off → export allowed (respects existing user choice).
		 */
		fun shouldDisableExport(): Boolean = isAapmEnabled

		/**
		 * Returns true when FLAG_SECURE should be forced on regardless of the
		 * user's "Allow Screenshots" setting.
		 *
		 * AAPM on → FLAG_SECURE forced (no screenshots allowed).
		 * AAPM off → respect user's "Allow Screenshots" setting.
		 */
		fun shouldForceFlagSecure(): Boolean = isAapmEnabled

		/**
		 * Returns true when new photo encryptions should use GCM exclusively
		 * (no CBC fallback for video streaming).
		 *
		 * AAPM on → GCM only (videos re-encrypt to per-chunk GCM, M7 v2 future).
		 * AAPM off → GCM for photos, CBC for videos (current behavior, P6).
		 */
		fun shouldForceGcmOnly(): Boolean = isAapmEnabled
	}
