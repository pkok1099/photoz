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

package onlasdan.gallery.model.io

import android.app.Application
import android.net.Uri
import net.ypresto.qtfaststart.QtFastStart
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TODO #1 — QtFastStart: MP4 MOOV atom relocation for progressive streaming.
 *
 * When a user imports an MP4/MOV video, the MOOV atom (metadata required
 * for playback) is often at the END of the file. ExoPlayer must seek to
 * the end to read it — which means downloading the entire file before
 * playback can start (no progressive streaming).
 *
 * This use case relocates the MOOV atom to the BEGINNING of the file
 * (faststart) BEFORE encryption. The encrypted file then has MOOV at the
 * start, so ExoPlayer can read it immediately and start playback while
 * the rest of the file downloads.
 *
 * Only applies to MP4/MOV (QuickTime container). WEBM/MKV are already
 * streaming-friendly by design (no MOOV atom) — skipped.
 *
 * Non-fatal: if faststart fails (corrupt MP4, unsupported variant, I/O
 * error), the original file is used as-is. The video will still play —
 * just not progressively (ExoPlayer downloads full file first).
 *
 * @since TODO #1 — progressive video streaming
 */
@Singleton
class FastStartUseCase @Inject constructor(
    private val app: Application,
) {
    /**
     * Check if a URI's content is an MP4/MOV that might need faststart.
     * Returns false for non-MP4 types (WEBM, MKV, etc.).
     */
    fun isFastStartCandidate(mimeType: String?): Boolean {
        if (mimeType.isNullOrBlank()) return false
        return mimeType.contains("mp4", ignoreCase = true) ||
            mimeType.contains("quicktime", ignoreCase = true) ||
            mimeType.contains("mov", ignoreCase = true)
    }

    /**
     * Relocate MOOV atom to the beginning of the file.
     *
     * Reads from [inputUri], writes faststarted output to a temp file,
     * returns the temp file. If faststart is not needed (MOOV already
     * at front) or fails, returns null — caller should use original input.
     *
     * @return temp File with faststarted content, or null if not needed/failed.
     */
    fun fastStart(inputUri: Uri): File? {
        val tempInput = File(app.cacheDir, "faststart-input-${System.currentTimeMillis()}.mp4")
        val tempOutput = File(app.cacheDir, "faststart-output-${System.currentTimeMillis()}.mp4")

        return try {
            // Copy URI content to temp file (QtFastStart needs a File, not a stream)
            app.contentResolver.openInputStream(inputUri)?.use { input ->
                FileOutputStream(tempInput).use { output ->
                    input.copyTo(output)
                }
            } ?: return null

            // Check if faststart is needed
            if (!QtFastStart.isFaststartNeeded(tempInput)) {
                Timber.d("FastStart: MOOV already at front — no faststart needed")
                return null
            }

            Timber.i("FastStart: MOOV at end — relocating to front for progressive streaming")

            // Run faststart
            QtFastStart.faststart(tempInput, tempOutput)

            if (tempOutput.exists() && tempOutput.length() > 0) {
                Timber.i("FastStart: success — output ${tempOutput.length()} bytes (input was ${tempInput.length()} bytes)")
                tempOutput
            } else {
                Timber.w("FastStart: output file empty or missing — falling back to original")
                null
            }
        } catch (e: QtFastStart.FastStartException) {
            Timber.w(e, "FastStart: not a valid MP4 or faststart not possible — using original")
            null
        } catch (e: Exception) {
            Timber.w(e, "FastStart: failed — using original (non-fatal)")
            null
        } finally {
            // Clean up temp input (output is returned to caller)
            tempInput.delete()
        }
    }

    /**
     * Clean up the temp output file after the caller is done with it.
     */
    fun cleanup(tempFile: File) {
        try {
            if (tempFile.exists()) tempFile.delete()
        } catch (e: Exception) {
            Timber.w(e, "FastStart: cleanup failed (non-fatal)")
        }
    }
}
