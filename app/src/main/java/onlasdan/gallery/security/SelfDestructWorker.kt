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

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import onlasdan.gallery.settings.data.Config
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Sprint 10 / L3 — Self-Destruct Worker.
 *
 * Periodic WorkManager job that checks whether the vault has been inactive
 * (not unlocked) for longer than the configured self-destruct period. If so,
 * it triggers [PanicWipeUseCase.wipe] to permanently delete all local vault
 * data.
 *
 * Schedule: runs every 24 hours. The check is cheap (compare two timestamps)
 * so even though the worker fires daily, the wipe only triggers when the
 * inactivity threshold is exceeded.
 *
 * Edge cases:
 *  - `selfDestructDays = 0` → disabled, worker is a no-op.
 *  - `lastUnlockAt = 0` → vault never unlocked (fresh install or post-wipe).
 *    Worker skips — we don't wipe a vault that was never set up.
 *  - Worker runs BEFORE first unlock → `lastUnlockAt = 0`, skip (same as above).
 *
 * The wipe is IRREVERSIBLE — same as PanicWipeUseCase. The remote backup
 * (if any) is NOT touched; the user can restore later by re-registering
 * with their recovery phrase.
 *
 * @since v14 — Sprint 10 / L3 self-destruct
 */
@HiltWorker
class SelfDestructWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val config: Config,
    private val panicWipeUseCase: PanicWipeUseCase,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val days = config.selfDestructDays
        if (days <= 0) {
            // Self-destruct disabled — no-op.
            return Result.success()
        }

        val lastUnlock = config.lastUnlockAt
        if (lastUnlock <= 0) {
            // Vault never unlocked (fresh install or post-wipe) — skip.
            // We don't wipe a vault that was never set up.
            Timber.d("SelfDestruct: lastUnlockAt=0, skipping (vault never unlocked)")
            return Result.success()
        }

        val now = System.currentTimeMillis()
        val thresholdMs = days * 24L * 60 * 60 * 1000
        val inactiveMs = now - lastUnlock

        if (inactiveMs >= thresholdMs) {
            android.util.Log.e("RcloneDiag",
                "SelfDestruct: TRIGGERED — inactive for ${inactiveMs / (24 * 60 * 60 * 1000)} days " +
                    "(threshold=$days days). Wiping vault.")
            try {
                val deleted = panicWipeUseCase.wipe()
                android.util.Log.e("RcloneDiag",
                    "SelfDestruct: WIPE COMPLETE — deleted $deleted photos")
            } catch (e: Exception) {
                Timber.e(e, "SelfDestruct: wipe FAILED (non-fatal — will retry next period)")
                return Result.retry()
            }
        } else {
            Timber.d("SelfDestruct: inactive for ${inactiveMs / (60 * 60 * 1000)}h, " +
                "threshold=${days * 24}h — no wipe")
        }

        return Result.success()
    }

    companion object {
        /**
         * Schedule the self-destruct check as a periodic WorkManager job.
         * Runs every 24 hours. The caller (BaseApplication.onCreate) invokes
         * this on app start — WorkManager deduplicates by the unique work
         * name, so calling it repeatedly is safe.
         *
         * @since v14 — Sprint 10 / L3 self-destruct
         */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SelfDestructWorker>(
                24, TimeUnit.HOURS
            ).build()

            androidx.work.WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                androidx.work.ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        private const val WORK_NAME = "self_destruct_check"
    }
}
