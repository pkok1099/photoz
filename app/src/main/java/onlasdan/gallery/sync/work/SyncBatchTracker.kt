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

package onlasdan.gallery.sync.work

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists per-batch sync counters across multiple [PhotoSyncWorker] executions.
 *
 * Each [PhotoSyncWorker] handles exactly ONE photo, but a "batch" is a sequence of
 * workers enqueued sequentially (e.g. after a multi-photo import). The batch state
 * (total / completed / failed / bytesCompleted) must survive across worker instances
 * because each worker is constructed fresh by HiltWorkerFactory.
 *
 * Backed by SharedPreferences so it also survives process death — important because
 * WorkManager may restart a worker much later, after the importing Activity has been
 * destroyed.
 *
 * Lifecycle (per batch):
 *  - [onPhotoEnqueued] — called at the start of each worker's `doWorkInternal()`
 *    (would ideally be called from the static `enqueue()` method, but accessing a
 *    `@Singleton` from a companion-object static method requires a Hilt EntryPoint;
 *    instead we increment here as the worker starts — slightly approximate but
 *    sufficient for UX). Gated by `runAttemptCount == 0` in the worker so retries
 *    don't double-count.
 *  - [onWorkerStart] — called right after [onPhotoEnqueued] to read the current
 *    batch state for the notification content ("Uploading N of M").
 *  - [onWorkerSuccess] / [onWorkerFailure] — called from the worker's
 *    `doWorkInternal()` after the upload result is known.
 *  - [isBatchComplete] — polled after each success/failure; when true, the worker
 *    shows the final "Sync complete" summary notification and calls [reset].
 *
 * @since Drive-style notification content (Item 1)
 */
@Singleton
class SyncBatchTracker @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val prefs = context.getSharedPreferences("sync_batch_tracker", Context.MODE_PRIVATE)

    /** Called when a new photo is enqueued for sync (incrementally grows `total`). */
    fun onPhotoEnqueued() {
        val total = prefs.getInt("total", 0) + 1
        prefs.edit().putInt("total", total).apply()
    }

    /**
     * Called when a worker starts processing a photo. Returns the current
     * [BatchState] for notification content. Does NOT mutate counters —
     * `current` is computed as `completed + failed + 1` (the in-flight photo).
     */
    fun onWorkerStart(uuid: String): BatchState {
        val total = prefs.getInt("total", 1)
        val completed = prefs.getInt("completed", 0)
        val failed = prefs.getInt("failed", 0)
        val current = completed + failed + 1
        return BatchState(current = current, total = total, completed = completed, failed = failed)
    }

    /** Called when a worker succeeds. Increments `completed` and accumulates bytes. */
    fun onWorkerSuccess(size: Long) {
        prefs.edit().apply {
            putInt("completed", prefs.getInt("completed", 0) + 1)
            putLong("bytesCompleted", prefs.getLong("bytesCompleted", 0) + size)
        }.apply()
    }

    /** Called when a worker permanently fails. Increments `failed`. */
    fun onWorkerFailure() {
        prefs.edit().putInt("failed", prefs.getInt("failed", 0) + 1).apply()
    }

    /** True when `completed + failed >= total` (i.e. no more in-flight workers expected). */
    fun isBatchComplete(): Boolean {
        val total = prefs.getInt("total", 0)
        val completed = prefs.getInt("completed", 0)
        val failed = prefs.getInt("failed", 0)
        return completed + failed >= total
    }

    /**
     * Read-only snapshot of the current batch state for notification content.
     * Note: `current` here is `completed + failed + 1` — the would-be index of the
     * next in-flight photo. For terminal notifications (batch complete), use the
     * `completed`/`failed`/`total` fields directly.
     */
    fun getBatchState(): BatchState {
        val total = prefs.getInt("total", 0)
        val completed = prefs.getInt("completed", 0)
        val failed = prefs.getInt("failed", 0)
        val bytesCompleted = prefs.getLong("bytesCompleted", 0)
        return BatchState(
            current = completed + failed + 1,
            total = total,
            completed = completed,
            failed = failed,
            bytesCompleted = bytesCompleted,
        )
    }

    /** Reset all batch state (called when the batch is complete). */
    fun reset() {
        prefs.edit().clear().apply()
    }

    /**
     * Snapshot of batch progress.
     *
     * @param current index of the currently-in-flight photo (1-based; = `completed + failed + 1`)
     * @param total planned batch size (grows incrementally as workers start — see class doc)
     * @param completed photos successfully uploaded so far
     * @param failed photos that permanently failed so far
     * @param bytesCompleted accumulated bytes of successful uploads
     */
    data class BatchState(
        val current: Int,
        val total: Int,
        val completed: Int,
        val failed: Int,
        val bytesCompleted: Long = 0,
    )
}
