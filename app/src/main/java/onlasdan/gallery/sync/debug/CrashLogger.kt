/*
 *   Copyright 2020–2026 Leon Latsch
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

package onlasdan.gallery.sync.debug

import android.app.Application
import timber.log.Timber
import java.io.File
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/**
 * Persistent crash logger writing full stack traces (including the entire `Caused by` chain)
 * to `<filesDir>/crash_log.txt` in app-private storage. Readable via:
 *   adb shell run-as onlasdan.gallery cat files/crash_log.txt
 *
 * Bounded at 500 KB — trims oldest `=== CRASH ... === END CRASH ===` blocks from the front
 * when exceeded. Every method is wrapped in runCatching — the logger must NEVER throw.
 */
object CrashLogger {
    private const val MAX_FILE_BYTES: Long = 500L * 1024L
    private val seq = AtomicLong(0L)
    private lateinit var filesDir: File

    fun init(app: Application) {
        filesDir = app.filesDir
    }

    fun logCrash(thread: Thread, throwable: Throwable, context: String = "") {
        runCatching {
            val file = File(filesDir, "crash_log.txt")
            val now = Instant.now()
            val seqNum = seq.incrementAndGet()

            val entry = buildString {
                append("\n=== CRASH #")
                append(seqNum)
                append(" at ")
                append(now)
                append(" ===\n")
                append("Context: ")
                append(context.ifEmpty { "(uncaught exception)" })
                append('\n')
                append("Thread: ")
                append(thread.name)
                append(" (id=")
                append(thread.id)
                append(")\n")
                append("Exception: ")
                append(throwable.javaClass.name)
                append(": ")
                append(throwable.message)
                append('\n')
                append("Stack trace:\n")
                append(throwable.stackTraceToString())

                var depth = 0
                var cause: Throwable? = throwable.cause
                while (cause != null && depth < 20) {
                    depth++
                    append("\n--- Caused by (#")
                    append(depth)
                    append(") ---\n")
                    append(cause.javaClass.name)
                    append(": ")
                    append(cause.message)
                    append('\n')
                    append(cause.stackTraceToString())
                    cause = cause.cause
                }
                append("\n=== END CRASH #")
                append(seqNum)
                append(" ===\n")
            }

            trimIfNeeded(file, entry.length.toLong())
            file.appendText(entry)
        }.onFailure { e ->
            try {
                android.util.Log.e("CrashLogger", "FAILED to write crash log", e)
            } catch (_: Throwable) {
            }
        }
    }

    private fun trimIfNeeded(file: File, newEntrySize: Long) {
        if (!file.exists()) return
        val currentSize = file.length()
        val projected = currentSize + newEntrySize
        if (projected <= MAX_FILE_BYTES) return

        val content = file.readText()
        val blocks = content.split("(?=\n=== CRASH )".toRegex()).filter { it.isNotBlank() }
        if (blocks.isEmpty()) {
            val keep = content.takeLast((MAX_FILE_BYTES / 2).toInt())
            file.writeText(keep)
            return
        }

        var kept = blocks.toMutableList()
        var keptSize = kept.sumOf { it.length }
        while (keptSize + newEntrySize > MAX_FILE_BYTES && kept.size > 1) {
            kept.removeAt(0)
            keptSize = kept.sumOf { it.length }
        }
        file.writeText(kept.joinToString(separator = ""))
    }

    fun read(): String = runCatching {
        if (!::filesDir.isInitialized) return "(CrashLogger not initialized)"
        val file = File(filesDir, "crash_log.txt")
        if (!file.exists()) return "(no crash log yet)"
        file.readText()
    }.getOrDefault("(failed to read crash log)")

    fun clear() = runCatching {
        if (!::filesDir.isInitialized) return@runCatching
        File(filesDir, "crash_log.txt").delete()
        Timber.i("CrashLogger: log cleared")
    }.onFailure { }
}
