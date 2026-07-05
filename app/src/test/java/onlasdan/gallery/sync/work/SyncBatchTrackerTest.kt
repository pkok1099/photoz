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

package onlasdan.gallery.sync.work

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Unit tests for [SyncBatchTracker] — exercises the per-batch counter lifecycle:
 * enqueue (grows total) → start (reads snapshot) → success/failure (advance
 * completed/failed) → batch complete (completed + failed >= total) → reset.
 *
 * Backed by SharedPreferences so it also exercises cross-instance persistence
 * (each test constructs a fresh [SyncBatchTracker] but they share the same
 * prefs file under the application context).
 *
 * @since Batch 3 — unit tests for key components
 */
@RunWith(RobolectricTestRunner::class)
class SyncBatchTrackerTest {
	private val app: Application by lazy { RuntimeEnvironment.getApplication() }

	@Before
	fun setUp() {
		// Each test starts with a clean tracker state.
		SyncBatchTracker(app).reset()
	}

	@Test
	fun `fresh tracker reports batch complete when total is zero`() {
		val tracker = SyncBatchTracker(app)
		// total=0, completed=0, failed=0 → completed+failed (0) >= total (0) → complete.
		assertTrue("Empty batch (total=0) must be considered complete", tracker.isBatchComplete())
	}

	@Test
	fun `onPhotoEnqueued grows total by one each call`() {
		val tracker = SyncBatchTracker(app)
		tracker.onPhotoEnqueued()
		assertEquals(1, tracker.getBatchState().total)
		tracker.onPhotoEnqueued()
		assertEquals(2, tracker.getBatchState().total)
		tracker.onPhotoEnqueued()
		assertEquals(3, tracker.getBatchState().total)
	}

	@Test
	fun `onWorkerStart returns 1-based current index for first photo`() {
		val tracker = SyncBatchTracker(app)
		tracker.onPhotoEnqueued()
		tracker.onPhotoEnqueued()
		// After 2 enqueues, total=2. First worker starts → current = 0 + 0 + 1 = 1.
		val state = tracker.onWorkerStart("uuid-1")
		assertEquals(1, state.current)
		assertEquals(2, state.total)
		assertEquals(0, state.completed)
		assertEquals(0, state.failed)
	}

	@Test
	fun `onWorkerSuccess increments completed and accumulates bytes`() {
		val tracker = SyncBatchTracker(app)
		tracker.onPhotoEnqueued()
		tracker.onPhotoEnqueued()
		tracker.onWorkerStart("uuid-1")

		tracker.onWorkerSuccess(size = 1_024L)
		var state = tracker.getBatchState()
		assertEquals(1, state.completed)
		assertEquals(0, state.failed)
		assertEquals(1_024L, state.bytesCompleted)

		tracker.onWorkerSuccess(size = 2_048L)
		state = tracker.getBatchState()
		assertEquals(2, state.completed)
		assertEquals(0, state.failed)
		assertEquals(3_072L, state.bytesCompleted)
	}

	@Test
	fun `onWorkerFailure increments failed without changing completed`() {
		val tracker = SyncBatchTracker(app)
		tracker.onPhotoEnqueued()
		tracker.onPhotoEnqueued()

		tracker.onWorkerFailure()
		val state = tracker.getBatchState()
		assertEquals(0, state.completed)
		assertEquals(1, state.failed)
	}

	@Test
	fun `batch is not complete while workers are still in flight`() {
		val tracker = SyncBatchTracker(app)
		tracker.onPhotoEnqueued()
		tracker.onPhotoEnqueued()
		tracker.onPhotoEnqueued()

		tracker.onWorkerStart("uuid-1")
		tracker.onWorkerSuccess(size = 100L)
		// 1 of 3 done → not complete.
		assertFalse(tracker.isBatchComplete())

		tracker.onWorkerStart("uuid-2")
		tracker.onWorkerFailure()
		// 2 of 3 done (1 success + 1 fail) → not complete.
		assertFalse(tracker.isBatchComplete())
	}

	@Test
	fun `batch is complete when completed plus failed reaches total`() {
		val tracker = SyncBatchTracker(app)
		tracker.onPhotoEnqueued()
		tracker.onPhotoEnqueued()

		tracker.onWorkerStart("uuid-1")
		tracker.onWorkerSuccess(size = 100L)
		assertFalse(tracker.isBatchComplete())

		tracker.onWorkerStart("uuid-2")
		tracker.onWorkerSuccess(size = 200L)
		assertTrue("All photos resolved → batch must be complete", tracker.isBatchComplete())
	}

	@Test
	fun `batch is complete with mixed success and failure outcomes`() {
		val tracker = SyncBatchTracker(app)
		tracker.onPhotoEnqueued()
		tracker.onPhotoEnqueued()
		tracker.onPhotoEnqueued()

		tracker.onWorkerStart("uuid-1")
		tracker.onWorkerSuccess(size = 100L)
		tracker.onWorkerStart("uuid-2")
		tracker.onWorkerFailure()
		tracker.onWorkerStart("uuid-3")
		tracker.onWorkerSuccess(size = 300L)

		assertTrue("3 of 3 resolved (2 success + 1 fail) → complete", tracker.isBatchComplete())
		val state = tracker.getBatchState()
		assertEquals(2, state.completed)
		assertEquals(1, state.failed)
		assertEquals(3, state.total)
		assertEquals(400L, state.bytesCompleted)
	}

	@Test
	fun `reset clears all counters and bytes`() {
		val tracker = SyncBatchTracker(app)
		tracker.onPhotoEnqueued()
		tracker.onPhotoEnqueued()
		tracker.onWorkerStart("uuid-1")
		tracker.onWorkerSuccess(size = 1_024L)
		tracker.onWorkerFailure()

		tracker.reset()

		val state = tracker.getBatchState()
		assertEquals(0, state.total)
		assertEquals(0, state.completed)
		assertEquals(0, state.failed)
		assertEquals(0L, state.bytesCompleted)
		// After reset, the empty batch is considered "complete" (0 >= 0).
		assertTrue(tracker.isBatchComplete())
	}

	@Test
	fun `state persists across tracker instances constructed from same context`() {
		// WorkManager constructs a fresh SyncBatchTracker per worker instance, so
		// state MUST persist via SharedPreferences across instances.
		val tracker1 = SyncBatchTracker(app)
		tracker1.onPhotoEnqueued()
		tracker1.onPhotoEnqueued()
		tracker1.onWorkerStart("uuid-1")
		tracker1.onWorkerSuccess(size = 100L)

		// Simulate a new worker instance being constructed by HiltWorkerFactory.
		val tracker2 = SyncBatchTracker(app)
		val state = tracker2.getBatchState()
		assertEquals("Total must persist across instances", 2, state.total)
		assertEquals("Completed must persist across instances", 1, state.completed)
		assertEquals("Bytes must persist across instances", 100L, state.bytesCompleted)
		assertFalse("Batch state must persist (1 of 2 done)", tracker2.isBatchComplete())
	}

	@Test
	fun `onWorkerStart after a success reports current as next 1-based index`() {
		val tracker = SyncBatchTracker(app)
		tracker.onPhotoEnqueued()
		tracker.onPhotoEnqueued()
		tracker.onPhotoEnqueued()

		tracker.onWorkerStart("uuid-1")
		tracker.onWorkerSuccess(size = 100L)

		// After 1 success, the next worker's current = completed(1) + failed(0) + 1 = 2.
		val state = tracker.onWorkerStart("uuid-2")
		assertEquals(2, state.current)
		assertEquals(3, state.total)
		assertEquals(1, state.completed)
	}
}
