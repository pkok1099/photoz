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

        if (config.securityLockTimeout != -1
            && wentToBackgroundAt != 0L
            && System.currentTimeMillis() - wentToBackgroundAt >= config.securityLockTimeout
        ) {
            lockApp()
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        super.onStop(owner)

        wentToBackgroundAt = System.currentTimeMillis()
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

        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
    }
}