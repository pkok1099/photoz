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
import onlasdan.gallery.model.database.dao.PhotoDao
import onlasdan.gallery.model.database.entity.internalThumbnailFileName
import onlasdan.gallery.settings.data.Config
import onlasdan.gallery.sync.domain.SyncState
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sprint 6 / M5 — Encrypted thumbnail cache rotation.
 *
 * Thumbnails (`.crypt.tn` files in app-private storage) accumulate over time.
 * For photos with syncState=UPLOADED, the thumbnail can be re-downloaded
 * from the cloud on demand (via SyncRestorer.ensureLocalOriginal), so it's
 * safe to delete the local copy to free space.
 *
 * Two rotation policies, both configurable in Settings:
 *
 *  1. **Age-based**: thumbnails for UPLOADED photos whose file hasn't been
 *     accessed in `cacheMaxAgeDays` days are deleted. Default 30 days.
 *     Set to 0 to disable.
 *
 *  2. **Size-based**: when the total size of cached thumbnails exceeds
 *     `cacheMaxSizeMb` MB, the oldest-accessed thumbnails (for UPLOADED
 *     photos only — LOCAL_ONLY photos are NEVER evicted because they have
 *     no remote backup) are deleted until under the limit. Default 500 MB.
 *     Set to 0 to disable.
 *
 * LOCAL_ONLY photos are NEVER evicted — their thumbnail is the only copy
 * (no remote backup yet). Deleting it would leave the gallery tile blank
 * until the user triggers an upload.
 *
 * File access times: Android's `File.lastAccessed()` is unreliable across
 * filesystems, so we use `File.lastModified()` as a proxy (the gallery
 * updates a thumbnail's mtime whenever the photo is viewed — see the
 * ImageViewerFragment's "touch to update mtime" logic, which we'd add in
 * a follow-up. For now, mtime ≈ import time, which still gives a useful
 * LRU signal: recently imported photos stay cached, old ones get evicted).
 *
 * @since v13 — Sprint 6 / M5 cache rotation
 */
@Singleton
class CacheRotationUseCase
	@Inject
	constructor(
		private val app: Application,
		private val photoDao: PhotoDao,
		private val config: Config,
	) {
		/**
		 * Run both age-based and size-based rotation. Returns the count of
		 * thumbnails deleted.
		 *
		 * Safe to call on every app start — both policies are no-ops when their
		 * respective limits are 0, and the actual file deletions are best-effort
		 * (failures are logged but don't propagate).
		 */
		suspend fun run(): Int {
			val maxAgeDays = config.cacheMaxAgeDays
			val maxSizeMb = config.cacheMaxSizeMb
			if (maxAgeDays <= 0 && maxSizeMb <= 0) return 0

			// Fetch UPLOADED photos only — LOCAL_ONLY photos are never evicted.
			// We use the vault-agnostic findPhotosInSyncState (the cache is
			// shared across all vaults in the device's filesDir, so vault
			// scoping doesn't apply here — we can delete any UPLOADED photo's
			// thumbnail regardless of which vault it belongs to).
			val uploadedPhotos =
				try {
					// Pass the current vault_id (or null fallback) — the DAO query
					// filters by vault_id, but since cache rotation is a global
					// concern, we pass a sentinel that matches all vaults via the
					// NULL-or-equal pattern. The simpler approach: just call the
					// vault-agnostic getAll() and filter in-memory.
					photoDao.getAll().filter { it.syncState == SyncState.UPLOADED }
				} catch (e: Exception) {
					Timber.w(e, "CacheRotation: failed to query UPLOADED photos")
					return 0
				}

			var deleted = 0

			// ─── Age-based rotation ────────────────────────────────────────────
			if (maxAgeDays > 0) {
				deleted += rotateByAge(uploadedPhotos, maxAgeDays)
			}

			// ─── Size-based rotation ───────────────────────────────────────────
			if (maxSizeMb > 0) {
				deleted += rotateBySize(uploadedPhotos, maxSizeMb)
			}

			if (deleted > 0) {
				Timber.i(
					"CacheRotation: deleted %d thumbnails (ageDays=%d, sizeMb=%d)",
					deleted,
					maxAgeDays,
					maxSizeMb,
				)
			}
			return deleted
		}

		/**
		 * Delete thumbnails of UPLOADED photos not accessed within [maxAgeDays]
		 * days. Returns the number deleted.
		 *
		 * @since v13 — Sprint 6 / M5 cache rotation
		 */
		private fun rotateByAge(
			uploadedPhotos: List<Photo>,
			maxAgeDays: Int,
		): Int {
			val cutoff = System.currentTimeMillis() - maxAgeDays * 24L * 60 * 60 * 1000
			var deleted = 0
			for (photo in uploadedPhotos) {
				val tnFile = File(app.filesDir, internalThumbnailFileName(photo.uuid))
				if (!tnFile.exists()) continue
				if (tnFile.lastModified() >= cutoff) continue
				if (tnFile.delete()) {
					deleted++
					Timber.d(
						"CacheRotation (age): deleted %s (lastModified=%d, cutoff=%d)",
						tnFile.name,
						tnFile.lastModified(),
						cutoff,
					)
				}
			}
			return deleted
		}

		/**
		 * When total cached thumbnail size exceeds [maxSizeMb] MB, delete the
		 * oldest-accessed UPLOADED thumbnails (LRU) until under the limit.
		 * Returns the number deleted.
		 *
		 * @since v13 — Sprint 6 / M5 cache rotation
		 */
		private fun rotateBySize(
			uploadedPhotos: List<Photo>,
			maxSizeMb: Int,
		): Int {
			val maxBytes = maxSizeMb.toLong() * 1024 * 1024
			// Collect all existing thumbnail files for UPLOADED photos,
			// sorted oldest-first (LRU eviction).
			val tnFiles =
				uploadedPhotos
					.mapNotNull { photo ->
						val f = File(app.filesDir, internalThumbnailFileName(photo.uuid))
						if (f.exists()) f else null
					}.sortedBy { it.lastModified() }

			var totalSize = tnFiles.sumOf { it.length() }
			var deleted = 0
			if (totalSize > maxBytes) {
				for (f in tnFiles) {
					if (totalSize <= maxBytes) break
					val size = f.length()
					if (f.delete()) {
						totalSize -= size
						deleted++
						Timber.d(
							"CacheRotation (size): deleted %s (size=%d, totalNow=%d, max=%d)",
							f.name,
							size,
							totalSize,
							maxBytes,
						)
					}
				}
			}
			return deleted
		}
	}
