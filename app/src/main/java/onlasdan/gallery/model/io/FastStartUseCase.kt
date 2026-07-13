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
import android.widget.Toast
import net.ypresto.qtfaststart.QtFastStart
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * (roadmap #1) — QtFastStart: MP4 MOOV atom relocation for progressive streaming.
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
 * streaming-friendly by design — skipped.
 *
 * Non-fatal: if faststart fails (corrupt MP4, MOOV already at front,
 * unsupported variant, I/O error), the original file is used as-is.
 * A warning toast is shown to the user so they know progressive
 * streaming may not work for this video.
 *
 * @since (roadmap #1) — progressive video streaming
 */
@Singleton
class FastStartUseCase
	@Inject
	constructor(
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
		 * The QtFastStart library does NOT have an isFaststartNeeded() check —
		 * it tries to faststart and throws if the file doesn't need it or
		 * is malformed. We catch those exceptions and return null.
		 *
		 * A warning toast is shown on failure so the user knows progressive
		 * streaming may not work for this video.
		 *
		 * @param displayName the video filename for the toast message
		 * @return temp File with faststarted content, or null if not needed/failed.
		 */
		fun fastStart(
			inputUri: Uri,
			displayName: String? = null,
		): File? {
			val tempInput = File(app.cacheDir, "faststart-input-${System.currentTimeMillis()}.mp4")
			val tempOutput = File(app.cacheDir, "faststart-output-${System.currentTimeMillis()}.mp4")

			val name = displayName ?: "video"
			return try {
				// Copy URI content to temp file (QtFastStart needs a File, not a stream)
				app.contentResolver.openInputStream(inputUri)?.use { input ->
					FileOutputStream(tempInput).use { output ->
						input.copyTo(output)
					}
				} ?: return null

				Timber.i("FastStart: attempting MOOV relocation for '$name' (${tempInput.length()} bytes)")

				// Run faststart — throws if not needed or malformed
				QtFastStart.fastStart(tempInput, tempOutput)

				if (tempOutput.exists() && tempOutput.length() > 0) {
					Timber.i("FastStart: success — output ${tempOutput.length()} bytes")
					tempOutput
				} else {
					Timber.w("FastStart: output file empty — using original")
					showWarning("⚠ '$name': FastStart failed — video will need full download before playback")
					null
				}
			} catch (e: QtFastStart.MalformedFileException) {
				Timber.d("FastStart: malformed MP4 for '$name' — using original")
				showWarning("⚠ '$name': Not a valid MP4 — video may need full download before playback")
				null
			} catch (e: QtFastStart.UnsupportedFileException) {
				// This is thrown when MOOV is already at front OR format is unsupported.
				// Both are non-critical — check the message to differentiate.
				val msg = e.message ?: ""
				if (msg.contains("already", ignoreCase = true) || msg.contains("front", ignoreCase = true)) {
					Timber.d("FastStart: MOOV already at front for '$name' — no faststart needed")
					// No warning — video is already optimized
				} else {
					Timber.d("FastStart: unsupported format for '$name' — using original")
					showWarning("⚠ '$name': Unsupported video format — progressive streaming may not work")
				}
				null
			} catch (e: QtFastStart.QtFastStartException) {
				Timber.d(e, "FastStart: exception for '$name' — using original")
				showWarning("⚠ '$name': FastStart failed — video may need full download before playback")
				null
			} catch (e: Exception) {
				Timber.w(e, "FastStart: failed for '$name' — using original (non-fatal)")
				showWarning("⚠ '$name': FastStart failed — video may need full download before playback")
				null
			} finally {
				// Clean up temp input (output is returned to caller)
				tempInput.delete()
			}
		}

		/**
		 * Show a toast warning on the main thread. Non-blocking — if toast
		 * fails, the warning is only in logcat.
		 */
		private fun showWarning(message: String) {
			try {
				android.os.Handler(android.os.Looper.getMainLooper()).post {
					Toast.makeText(app, message, Toast.LENGTH_LONG).show()
				}
			} catch (e: Exception) {
				Timber.w(e, "FastStart: failed to show warning toast (non-fatal)")
			}
		}

		/**
		 * Clean up the temp output file after the caller is done with it.
		 */
		fun cleanup(tempFile: File) {
			try {
				if (!tempFile.delete()) Timber.w("tempFile.delete() failed (FastStart cleanup)")
			} catch (e: Exception) {
				Timber.w(e, "FastStart: cleanup failed (non-fatal)")
			}
		}
	}
