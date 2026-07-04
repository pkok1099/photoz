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

package onlasdan.gallery

import android.app.Application
import android.content.Intent
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import onlasdan.gallery.encryption.domain.SessionRepository
import onlasdan.gallery.main.ui.MainActivity
import onlasdan.gallery.model.repositories.CleanupDeadFilesUseCase
import onlasdan.gallery.other.setAppDesign
import onlasdan.gallery.settings.data.Config
import onlasdan.gallery.sync.debug.CrashLogger
import onlasdan.gallery.sync.debug.SyncLogger
import onlasdan.gallery.telemetry.domain.TelemetryService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Base Application class.
 *
 * @since 1.0.0
 * @author PhotoZ
 */
@HiltAndroidApp
class BaseApplication : Application(), DefaultLifecycleObserver, Configuration.Provider {

    @Inject
    lateinit var appScope: CoroutineScope

    @Inject
    lateinit var config: Config

    @Inject
    lateinit var sessionRepository: SessionRepository

    @Inject
    lateinit var cleanupDeadFilesUseCase: CleanupDeadFilesUseCase

    @Inject
    lateinit var telemetryService: TelemetryService

    /**
     * @since v10 recycle bin — injected so we can run the trash auto-cleanup
     *   pass on app start. The cleanup permanently deletes trash entries
     *   older than 30 days (see [PhotoRepository.cleanupExpiredTrash]).
     */
    @Inject
    lateinit var photoRepository: onlasdan.gallery.model.repositories.PhotoRepository

    /**
     * Sprint 6 / M5 — injected so we can run the thumbnail cache rotation
     * pass on app start. Deletes thumbnails for UPLOADED photos that are
     * older than the configured max age or exceed the total size limit.
     *
     * @since v13 — Sprint 6 / M5 cache rotation
     */
    @Inject
    lateinit var cacheRotationUseCase: onlasdan.gallery.model.io.CacheRotationUseCase

    /**
     * Hilt-injected WorkManager configuration. WorkManager reads this via
     * [Configuration.Provider.getWorkManagerConfiguration] before its first initialization.
     *
     * @since PR1 sync feature
     */
    @Inject
    lateinit var injectedWorkManagerConfiguration: Configuration

    override val workManagerConfiguration: Configuration
        get() = injectedWorkManagerConfiguration


    private var wentToBackgroundAt = 0L
    private var ignoreNextTimeout = false

    override fun onCreate() {
        super<Application>.onCreate()

        // ─── DIAGNOSTIC: process lifecycle logging (data-loss bug investigation) ──
        android.util.Log.e("RcloneDiag",
            "BaseApplication.onCreate: process starting fresh " +
                "(pid=${android.os.Process.myPid()}, systemFirstStart=${config.systemFirstStart}, " +
                "repoConfirmed=${config.repoConfirmed})")

        // ─── CRASH LOGGER — install FIRST, before anything else can crash ───────
        // Captures full stack traces (including `Caused by` chain) to
        // <filesDir>/crash_log.txt so they survive process death. Readable via:
        //   adb shell run-as onlasdan.gallery cat files/crash_log.txt
        // The logger itself never throws (see CrashLogger.kt).
        // @since PR1 sync debug — crash-loop diagnosis
        CrashLogger.init(this)
        SyncLogger.init(this)
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            CrashLogger.logCrash(thread, throwable, context = "UncaughtExceptionHandler")
            // Delegate to the default handler afterward so normal crash behavior (process
            // death) still happens — we're only adding logging, NOT suppressing the crash.
            defaultHandler?.uncaughtException(thread, throwable)
        }

        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        telemetryService.setup()

        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        setAppDesign(config.systemDesign)
        cleanupDeadFilesUseCase()

        // ─── v10 recycle bin — auto-cleanup expired trash on app start ───────
        // Permanently delete trash entries whose `deleted_at` is older than
        // 30 days. Best-effort: failures are logged but never crash the app
        // (the next start will retry). Skipped if the vault isn't set up yet
        // (the call is a no-op when the DB has no trash entries).
        appScope.launch {
            try {
                val n = photoRepository.cleanupExpiredTrash()
                if (n > 0) {
                    android.util.Log.i("TrashAutoCleanup", "permanently deleted $n expired trash entries")
                }
            } catch (e: Exception) {
                android.util.Log.w("TrashAutoCleanup", "cleanupExpiredTrash failed: ${e.message}")
            }
        }

        // ─── Sprint 6 / M5 — thumbnail cache rotation on app start ────────
        // Deletes thumbnails for UPLOADED photos that are older than the
        // configured max age (default 30 days) or exceed the total size
        // limit (default 500 MB). LOCAL_ONLY photos are never evicted.
        // Best-effort: failures are logged but don't crash. Skipped when
        // both limits are 0 (user disabled cache rotation).
        appScope.launch {
            try {
                val n = cacheRotationUseCase.run()
                if (n > 0) {
                    android.util.Log.i("CacheRotation", "evicted $n cached thumbnails")
                }
            } catch (e: Exception) {
                android.util.Log.w("CacheRotation", "cache rotation failed: ${e.message}")
            }
        }

        // ─── Item 1: restore persisted background timestamp ───────────────
        // `wentToBackgroundAt` was previously an in-memory `Long` that reset to `0L`
        // on process death, defeating the auto-lock check in [onStart] after a
        // force-close + reopen. Restore it from SharedPreferences so the check
        // still fires.
        wentToBackgroundAt = config.lastBackgroundedAt
        android.util.Log.e("RcloneDiag",
            "BaseApplication.onCreate: restored wentToBackgroundAt=$wentToBackgroundAt " +
                "(lockTimeout=${config.securityLockTimeout})")
    }

    override fun onDestroy(owner: LifecycleOwner) {
        super.onDestroy(owner)
        appScope.cancel()
        ProcessLifecycleOwner.get().lifecycle.removeObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        super.onStart(owner)

        if (ignoreNextTimeout) {
            ignoreNextTimeout = false
            return
        }

        // Item 1: `wentToBackgroundAt` is now restored from prefs in onCreate, so the
        // auto-lock check still fires after a force-close + reopen.
        if (config.securityLockTimeout != -1
            && wentToBackgroundAt != 0L
            && System.currentTimeMillis() - wentToBackgroundAt >= config.securityLockTimeout
        ) {
            lockApp()
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        super.onStop(owner)

        // Item 1: persist to prefs so the value survives process death.
        wentToBackgroundAt = System.currentTimeMillis()
        config.lastBackgroundedAt = wentToBackgroundAt

        // ─── Sprint 3 / M8 — BFU-Safe key handling ────────────────────────
        // Clear the VMK from memory when the app goes to the background,
        // UNLESS the lock timeout is -1 (never auto-lock). This is the
        // BFU-safe trade-off:
        //
        //  2026 forensics research shows the BFU (Before First Unlock) state
        //  is dramatically harder for Cellebrite-class tools to extract from
        //  — once the VMK leaves memory, the only way to recover plaintext
        //  is to re-derive it from the user's password (which requires user
        //  interaction on the next foreground).
        //
        //  The previous behavior left the VMK in the SessionRepository
        //  Singleton across backgrounding, meaning a snapshot/dump taken
        //  while the app was backgrounded could still decrypt the vault.
        //
        // We respect the user's lock-timeout setting:
        //  - If timeout = -1 (never), the user explicitly chose convenience
        //    over BFU-safety — we leave the VMK in memory.
        //  - If timeout = 0 (immediately), lockApp() fires on the next
        //    onStart anyway, so we don't need to do anything special here.
        //  - If timeout > 0 (e.g. 5 min), we clear the VMK NOW so that a
        //    snapshot taken during the background window cannot decrypt
        //    the vault. The user re-unlocks when they return.
        //
        // Non-fatal: any in-flight WorkManager worker that needs the VMK
        // (e.g. PhotoSyncWorker mid-upload) will fail gracefully and retry
        // on the next foreground unlock.
        val timeout = config.securityLockTimeout
        if (timeout != -1) {
            try {
                sessionRepository.reset()
                android.util.Log.i("RcloneDiag",
                    "BaseApplication.onStop: cleared VMK from memory (BFU-safe, timeout=$timeout)")
            } catch (e: Exception) {
                android.util.Log.w("RcloneDiag",
                    "BaseApplication.onStop: sessionRepository.reset() failed (non-fatal): ${e.message}")
            }
        }
    }

    /**
     * Ignore next check for lock timeout.
     */
    fun ignoreNextTimeout() {
        ignoreNextTimeout = true
    }

    /**
     * Reset the [EncryptionManager], set [applicationState] to [ApplicationState.LOCKED] and start [MainActivity] with NEW_TESK.
     */
    fun lockApp() {
        sessionRepository.reset()

        // Item 1: clear persisted background timestamp so the next cold start doesn't
        // immediately re-lock (we're already locking now).
        config.lastBackgroundedAt = 0L
        wentToBackgroundAt = 0L

        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
    }
}