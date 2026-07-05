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

import android.content.Context
import java.io.File

/**
 * Shared append-with-rotation helper for the `sync_log.txt` diagnostic file
 * used by the various `diag()` helpers across the sync subsystem
 * ([onlasdan.gallery.sync.rclone.RcloneController],
 * [onlasdan.gallery.sync.rclone.RepoManager],
 * [onlasdan.gallery.sync.work.PhotoSyncWorker],
 * [onlasdan.gallery.sync.work.HashRegistry]).
 *
 * ## Why this exists
 *
 * Before this helper, each `diag()` function wrote directly via
 * `File(app.filesDir, "sync_log.txt").appendText(...)`. None of them
 * truncated the file, and `sync_log.txt` lives in `filesDir` (persistent
 * storage, NOT cache), so it grew without bound across app launches. On a
 * long-running install with sync issues (which is exactly when `diag()`
 * fires most), the file could easily reach tens of MB — slowing down every
 * subsequent append and bloating the app's storage footprint forever.
 *
 * This helper centralizes the append + rotate logic so every `diag()` site
 * gets the same cap for free. The `Log.e("RcloneDiag", ...)` calls in those
 * `diag()` functions stay as-is — logcat has its own kernel-level rotation,
 * so we only need to bound the on-disk file.
 *
 * ## Rotation policy
 *
 * - **Cap**: 1 MB ([MAX_LOG_SIZE]). If the file exceeds this when [append]
 *   is called, it is truncated to the last 512 KB ([TRUNCATE_TO]) before
 *   the new entry is appended. This keeps the most recent diagnostic
 *   context (which is what the user is debugging right now) and drops the
 *   oldest half.
 * - **Synchronization**: none. The various `diag()` callers are already
 *   serialized within their own components (the rclone callMutex, the
 *   worker's single-threadedness, etc.), and a torn write at worst loses
 *   one log line — acceptable for a best-effort diagnostic file. Adding a
 *   global mutex here would serialize unrelated sync subsystems on log
 *   I/O, which is worse than the rare lost-line risk.
 * - **Errors**: swallowed. [append] MUST NEVER throw — it is called from
 *   `catch` blocks in `diag()` helpers, and a thrown exception here would
 *   mask the original error being logged.
 *
 * ## Relationship to [SyncLogger]
 *
 * [SyncLogger] (the rc-call / state-transition logger) writes to the same
 * `sync_log.txt` file with its own 500 KB trim logic. The two writers
 * coexist:
 *   - [SyncLogger] trims when its own writes push the file past 500 KB
 *     (block-aware: drops whole `--- RC CALL #N ---` entries from the
 *     front).
 *   - [SyncLogRotator] trims when its own writes push the file past 1 MB
 *     (byte-aware: keeps the last 512 KB regardless of entry boundaries).
 *
 * Both effectively bound the file size; the dual-writer design is a
 * historical artifact (they were added in different PRs) and is preserved
 * here to avoid a more invasive refactor of [SyncLogger].
 *
 * @since QC fix — sync_log.txt unbounded growth
 */
object SyncLogRotator {
	/** Hard cap on `sync_log.txt` size. Triggers rotation when exceeded. */
	private const val MAX_LOG_SIZE = 1_048_576L // 1 MB

	/**
	 * When the cap is exceeded, keep this many bytes from the END of the
	 * file (most recent entries) and drop everything before. 512 KB is
	 * enough to retain the last several diag() calls + their stack traces
	 * — plenty for debugging whatever issue the user is currently hitting.
	 */
	private const val TRUNCATE_TO = 524288 // 512 KB (keep last half)

	/**
	 * Append [text] to `sync_log.txt` in [context]'s `filesDir`, rotating
	 * first if the file exceeds [MAX_LOG_SIZE].
	 *
	 * Never throws — all I/O errors are swallowed. The caller's `diag()`
	 * helper is typically inside a `catch` block already, and a thrown
	 * exception from logging would mask the original error.
	 */
	fun append(
		context: Context,
		text: String,
	) {
		try {
			val logFile = File(context.filesDir, "sync_log.txt")
			// Rotate if too large. We check the size on every append —
			// diag() calls are not on a hot path (single-digit per upload /
			// download / GC operation), so the stat() cost is negligible.
			if (logFile.exists() && logFile.length() > MAX_LOG_SIZE) {
				val bytes = logFile.readBytes()
				val truncated =
					bytes.copyOfRange(
						(bytes.size - TRUNCATE_TO).coerceAtLeast(0),
						bytes.size,
					)
				logFile.writeBytes(truncated)
			}
			logFile.appendText(text)
		} catch (_: Exception) {
			// Swallow — see class doc. Logging the failure to log would be
			// recursive; logcat is the only viable channel and the caller
			// has already emitted a Log.e there.
		}
	}
}
