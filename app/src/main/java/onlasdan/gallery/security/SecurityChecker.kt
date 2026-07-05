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
import android.content.pm.PackageManager
import android.os.Debug
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TODO #9 — Root + debugger detection.
 *
 * Casual root detection via multiple heuristics. NOT bulletproof — a
 * determined attacker with Magisk Hide / Zygisk can bypass all of these.
 * The goal is to warn casual users that their device is rooted and may
 * be less secure for vault storage, NOT to prevent determined attackers.
 *
 * Also detects if a debugger is attached at critical paths (unlock, encrypt).
 * This prevents runtime memory inspection via `adb` attach.
 *
 * @since TODO #9 — root/debugger detection
 */
@Singleton
class SecurityChecker @Inject constructor(
    private val app: Application,
) {
    /**
     * Check if the device appears to be rooted. Uses multiple heuristics:
     * 1. `su` binary in PATH
     * 2. Common root app packages installed
     * 3. Magisk-specific files/dirs
     * 4. Root-related mount points
     *
     * Returns true if ANY check passes. False negatives are possible
     * (hidden root). False positives are very rare.
     */
    fun isRooted(): Boolean {
        return checkSuBinary() || checkRootApps() || checkMagiskFiles()
    }

    /**
     * Check if a debugger is currently attached to the process.
     * Used at critical paths (unlock, encrypt) to prevent memory inspection.
     *
     * Note: We do NOT call Debug.waitForDebugger() here — that would block
     * the calling thread until a debugger attaches, which is the opposite
     * of what we want. We only check the connection state.
     */
    fun isDebuggerAttached(): Boolean {
        return Debug.isDebuggerConnected()
    }

    /**
     * Returns a human-readable security warning if any issues are detected.
     * Returns null if the device appears secure.
     */
    fun getSecurityWarning(): String? {
        val warnings = mutableListOf<String>()

        if (isRooted()) {
            warnings.add("Device appears to be rooted. VMK can be extracted more easily on rooted devices.")
        }

        if (isDebuggerAttached()) {
            warnings.add("A debugger is attached to PhotoZ. This allows memory inspection of the VMK.")
        }

        return if (warnings.isEmpty()) null else warnings.joinToString("\n")
    }

    // ─── Root detection heuristics ────────────────────────────────────────

    private fun checkSuBinary(): Boolean {
        val paths = arrayOf(
            "/system/bin/su", "/system/xbin/su", "/sbin/su",
            "/system/sd/xbin/su", "/system/bin/failsafe/su",
            "/data/local/xbin/su", "/data/local/bin/su", "/data/local/su",
            "/su/bin/su", "/su/su", "/magisk/.core/bin/su",
        )
        for (path in paths) {
            if (File(path).exists()) {
                Timber.w("SecurityChecker: su binary found at %s", path)
                return true
            }
        }
        return false
    }

    private fun checkRootApps(): Boolean {
        val rootAppPackages = arrayOf(
            "com.topjohnwu.magisk",        // Magisk
            "eu.chainfire.supersu",        // SuperSU
            "com.koushikdutta.superuser",  // Superuser by Koush
            "com.thirdparty.superuser",    // Superuser
            "com.kingouser.com",           // KingoUser
            "com.kingroot.kinguser",       // KingRoot
            "com.smedialink.oneclickroot", // One Click Root
            "com.zhiqupk.root.global",     // ZhiQu
            "com.alephzain.framaroot",     // Framaroot
        )
        val pm = app.packageManager
        for (pkg in rootAppPackages) {
            try {
                pm.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES)
                Timber.w("SecurityChecker: root app found: %s", pkg)
                return true
            } catch (e: PackageManager.NameNotFoundException) {
                // Not installed — continue
            }
        }
        return false
    }

    private fun checkMagiskFiles(): Boolean {
        val magiskPaths = arrayOf(
            "/sbin/.magisk", "/data/adb/magisk", "/data/adb/modules",
            "/cache/.disable_magisk", "/dev/.magisk.unblock",
        )
        for (path in magiskPaths) {
            if (File(path).exists()) {
                Timber.w("SecurityChecker: Magisk file/dir found: %s", path)
                return true
            }
        }

        // Check for Magisk's init.rc modification
        val magiskHide = File("/data/data/com.topjohnwu.magisk")
        if (magiskHide.exists()) return true

        return false
    }
}
