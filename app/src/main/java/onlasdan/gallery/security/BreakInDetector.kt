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
import android.content.Context
import onlasdan.gallery.settings.data.Config
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sprint 7 / P2 — Break-in Detection.
 *
 * Tracks failed unlock attempts and surfaces them to the user on the next
 * successful unlock. The user sees a warning like "3 failed unlock attempts
 * since last login" so they know someone tried to access their vault.
 *
 * Two counters, both persisted in SharedPreferences (survive app restart):
 *  - [failedAttemptCount]: total wrong-password attempts since last
 *    successful unlock. Reset to 0 on successful unlock.
 *  - [lastFailedAttemptAt]: epoch-ms of the most recent failed attempt.
 *    Used to show "last failed attempt: 2 hours ago".
 *
 * Optional future enhancement (deferred — needs CAMERA permission + UI
 * consent): capture a front-camera photo on each failed attempt. Stored
 * encrypted in the vault, visible only to the real user. For v1, we just
 * count + timestamp — no photo capture (privacy concerns + permission
 * friction).
 *
 * @since v13 — Sprint 7 / P2 break-in detection
 */
@Singleton
class BreakInDetector @Inject constructor(
    private val app: Application,
    private val config: Config,
) {
    /**
     * Record a failed unlock attempt. Increments the counter + stamps the
     * timestamp. Called from [onlasdan.gallery.encryption.domain.VaultService.unlock]
     * on failure (wrong password / wrong biometric / wrong recovery phrase).
     *
     * @since v13 — Sprint 7 / P2 break-in detection
     */
    fun recordFailedAttempt() {
        val current = config.breakInFailedAttemptCount
        config.breakInFailedAttemptCount = current + 1
        config.breakInLastFailedAttemptAt = System.currentTimeMillis()
        android.util.Log.i("RcloneDiag",
            "BreakInDetector: recorded failed attempt (total=${current + 1})")
    }

    /**
     * Check + consume the break-in warning. Called on successful unlock —
     * returns a non-null warning string if there were failed attempts since
     * the last successful unlock, then resets the counter to 0.
     *
     * Returns null when there were no failed attempts (the common case —
     * the user unlocked on the first try).
     *
     * @return human-readable warning, or null if no break-in attempts.
     *
     * @since v13 — Sprint 7 / P2 break-in detection
     */
    fun consumeWarningIfAny(): String? {
        val count = config.breakInFailedAttemptCount
        if (count <= 0) return null

        val lastAt = config.breakInLastFailedAttemptAt
        val timeAgo = if (lastAt > 0) formatTimeAgo(lastAt) else "unknown"

        // Reset after consuming.
        config.breakInFailedAttemptCount = 0
        config.breakInLastFailedAttemptAt = 0L

        return "$count failed unlock attempt(s) since last login. " +
            "Last attempt: $timeAgo."
    }

    /**
     * Format an epoch-ms timestamp as a human-readable "time ago" string.
     * E.g. "2 hours ago", "5 minutes ago", "3 days ago".
     */
    private fun formatTimeAgo(epochMs: Long): String {
        val delta = System.currentTimeMillis() - epochMs
        val seconds = delta / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        val days = hours / 24
        return when {
            days > 0 -> "$days day(s) ago"
            hours > 0 -> "$hours hour(s) ago"
            minutes > 0 -> "$minutes minute(s) ago"
            else -> "$seconds second(s) ago"
        }
    }
}
