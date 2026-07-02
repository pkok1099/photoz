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

package onlasdan.gallery.sync.debug

import android.app.Application
import kotlinx.serialization.json.JsonObject
import timber.log.Timber
import java.io.File
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/**
 * Diagnostic logger for rclone rc calls — writes every HTTP request/response pair to
 * `<filesDir>/sync_log.txt` so false-success bugs can be diagnosed without logcat.
 *
 * Readable via:
 *   adb shell run-as onlasdan.gallery cat files/sync_log.txt
 *
 * Bounded at 500 KB (trims oldest entries from the front). Never throws.
 *
 * **Never logs rclone.conf contents or credentials** — only:
 * - The rc operation name (e.g. "operations/copyfile")
 * - The request params (which are file paths and remote names, NOT credentials)
 * - The HTTP status code
 * - The raw response body (rclone's error messages, which don't contain secrets)
 *
 * @since PR1 sync debug — false-success diagnosis
 */
object SyncLogger {

    private const val MAX_FILE_BYTES: Long = 500L * 1024L
    private val seq = AtomicLong(0L)
    private lateinit var filesDir: File

    fun init(app: Application) {
        filesDir = app.filesDir
    }

    /**
     * Log an rc call. Called from [onlasdan.gallery.sync.rclone.RcloneController.invokeRc]
     * for every HTTP request to rcd.
     */
    fun logRcCall(op: String, params: JsonObject, httpStatus: Int, responseBody: String) {
        runCatching {
            if (!::filesDir.isInitialized) return@runCatching
            val file = File(filesDir, "sync_log.txt")
            val now = Instant.now()
            val seqNum = seq.incrementAndGet()

            val entry = buildString {
                append("\n--- RC CALL #")
                append(seqNum)
                append(" at ")
                append(now)
                append(" ---\n")
                append("op: ")
                append(op)
                append('\n')
                append("params: ")
                append(params)
                append('\n')
                append("HTTP status: ")
                append(httpStatus)
                append('\n')
                append("response body: ")
                append(responseBody.take(2000)) // cap response to 2KB per entry
                append('\n')
            }

            trimIfNeeded(file, entry.length.toLong())
            file.appendText(entry)
        }.onFailure { e ->
            try {
                android.util.Log.e("SyncLogger", "FAILED to write sync log", e)
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * Log a sync state transition (e.g. LOCAL_ONLY → UPLOAD_PENDING → UPLOADED).
     */
    fun logStateTransition(uuid: String, from: String, to: String, context: String = "") {
        runCatching {
            if (!::filesDir.isInitialized) return@runCatching
            val file = File(filesDir, "sync_log.txt")
            val now = Instant.now()
            val seqNum = seq.incrementAndGet()

            val entry = buildString {
                append("\n--- STATE #")
                append(seqNum)
                append(" at ")
                append(now)
                append(" ---\n")
                append("photo: ")
                append(uuid)
                append('\n')
                append("transition: ")
                append(from)
                append(" → ")
                append(to)
                append('\n')
                if (context.isNotEmpty()) {
                    append("context: ")
                    append(context)
                    append('\n')
                }
            }

            trimIfNeeded(file, entry.length.toLong())
            file.appendText(entry)
        }.onFailure { }
    }

    private fun trimIfNeeded(file: File, newEntrySize: Long) {
        if (!file.exists()) return
        val currentSize = file.length()
        val projected = currentSize + newEntrySize
        if (projected <= MAX_FILE_BYTES) return

        val content = file.readText()
        val blocks = content.split("(?=\n--- )".toRegex()).filter { it.isNotBlank() }
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
        if (!::filesDir.isInitialized) return "(SyncLogger not initialized)"
        val file = File(filesDir, "sync_log.txt")
        if (!file.exists()) return "(no sync log yet)"
        file.readText()
    }.getOrDefault("(failed to read sync log)")

    fun clear() = runCatching {
        if (!::filesDir.isInitialized) return@runCatching
        File(filesDir, "sync_log.txt").delete()
        Timber.i("SyncLogger: log cleared")
    }.onFailure { }
}
