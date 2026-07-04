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

import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import onlasdan.gallery.di.DaggerBroadcastReceiver
import onlasdan.gallery.main.ui.MainActivity
import onlasdan.gallery.security.FakeCrashActivity
import onlasdan.gallery.security.PanicWipeUseCase
import onlasdan.gallery.settings.data.Config
import timber.log.Timber
import javax.inject.Inject

/**
 * Broadcast receiver for receiving android secret codes.
 *
 * Sprint 7+ / P3 + L2 — Enhanced with panic wipe + fake crash triggers:
 *
 *  1. **Normal launch code** (e.g. "1337"): opens MainActivity as before.
 *  2. **Panic dial code** (e.g. "9111"): triggers [PanicWipeUseCase.wipe()]
 *     to permanently delete all local vault data. The user appears to have
 *     dialed a wrong number — no UI feedback, silent wipe. The remote backup
 *     is NOT touched (user can restore later from another device).
 *  3. **Any other code**: shows [FakeCrashActivity] — makes the app look
 *     broken, not hidden. An investigator who dials random codes sees
 *     "PhotoZ keeps stopping" and moves on.
 *
 * @since 1.2.0 — original dial launcher
 * @since v14 — Sprint 7+ / P3 panic wipe + L2 fake crash trigger
 */
@AndroidEntryPoint
class DialLauncher : DaggerBroadcastReceiver() {

    @Inject
    lateinit var config: Config

    @Inject
    lateinit var panicWipeUseCase: PanicWipeUseCase

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context?, intent: Intent?) {
        super.onReceive(context, intent)
        context ?: return
        val dialedCode = intent?.data?.host ?: return

        val launchCode = config.securityDialLaunchCode
        val panicCode = config.panicDialCode

        when (dialedCode) {
            // Normal launch — open the app.
            launchCode -> {
                val launchIntent = Intent(context, MainActivity::class.java)
                launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(launchIntent)
            }

            // Sprint 7+ / P3 — Panic wipe. Silent, irreversible.
            // The remote backup is NOT touched — user can restore later.
            panicCode -> {
                android.util.Log.e("RcloneDiag", "DialLauncher: PANIC CODE detected — initiating wipe")
                scope.launch {
                    try {
                        panicWipeUseCase.wipe()
                        android.util.Log.e("RcloneDiag", "DialLauncher: PANIC WIPE COMPLETE")
                    } catch (e: Exception) {
                        Timber.e(e, "DialLauncher: panic wipe failed")
                    }
                }
                // Show fake crash so the investigator thinks the app crashed,
                // not that it wiped. The wipe runs in the background.
                showFakeCrash(context)
            }

            // Sprint 7+ / L2 — Wrong code → fake crash.
            // Makes the app look broken, not hidden.
            else -> {
                android.util.Log.d("RcloneDiag", "DialLauncher: unrecognized code '$dialedCode' — showing fake crash")
                showFakeCrash(context)
            }
        }
    }

    /**
     * Launch [FakeCrashActivity] — mimics the Android system crash dialog.
     */
    private fun showFakeCrash(context: Context) {
        val intent = Intent(context, FakeCrashActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        context.startActivity(intent)
    }
}
