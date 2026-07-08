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

package onlasdan.gallery.sync.domain

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import onlasdan.gallery.sync.work.HashRegistryDao
import onlasdan.gallery.sync.work.HashRegistryEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [Dedup] — the pure-logic decision helper that decides
 * whether a photo upload should be skipped (content hash already on the
 * remote) or uploaded normally (new content).
 *
 * The [HashRegistryDao] is mocked — no Android framework, no Room, no real
 * database. Tests only the dedup DECISION logic.
 *
 * @since Batch 3 — unit tests for key components
 */
class DedupTest {
	private val dao: HashRegistryDao = mockk()
	private val dedup = Dedup(dao)

	private fun entryFor(
		hash: String,
		uuid: String = "canonical-$hash",
	): HashRegistryEntry =
		HashRegistryEntry(
			contentHash = hash,
			uuid = uuid,
			filename = "file-$hash.jpg",
			albumPath = null,
			size = 1024L,
			type = "JPEG",
			thumbnailPack = null,
			thumbnailOffset = 0L,
			thumbnailLength = 0L,
			deleted = false,
		)

	@Test
	fun `same content hash in registry returns skip=true`() =
		runTest {
			val hash = "abc123def456"
			coEvery { dao.findByHashAnyVault(hash) } returns entryFor(hash)

			assertTrue("Entry exists for hash → must skip upload", dedup.shouldSkip(hash))
		}

	@Test
	fun `different content hash not in registry returns skip=false`() =
		runTest {
			val knownHash = "known-hash"
			val newHash = "different-hash"
			coEvery { dao.findByHashAnyVault(knownHash) } returns entryFor(knownHash)
			coEvery { dao.findByHashAnyVault(newHash) } returns null

			assertFalse("No entry for new hash → must upload", dedup.shouldSkip(newHash))
		}

	@Test
	fun `null content hash returns skip=false (no dedup possible)`() =
		runTest {
			assertFalse(dedup.shouldSkip(null))
		}

	@Test
	fun `blank content hash returns skip=false (no dedup possible)`() =
		runTest {
			assertFalse(dedup.shouldSkip(""))
			assertFalse(dedup.shouldSkip("   "))
		}

	@Test
	fun `findCanonical returns the entry when hash is in registry`() =
		runTest {
			val hash = "canonical-lookup-hash"
			val entry = entryFor(hash, uuid = "canonical-uuid-1234")
			coEvery { dao.findByHashAnyVault(hash) } returns entry

			val result = dedup.findCanonical(hash)
			assertEquals(entry, result)
			assertEquals("canonical-uuid-1234", result?.uuid)
		}

	@Test
	fun `findCanonical returns null when hash not in registry`() =
		runTest {
			val hash = "missing-hash"
			coEvery { dao.findByHashAnyVault(hash) } returns null

			assertNull(dedup.findCanonical(hash))
		}

	@Test
	fun `findCanonical returns null for blank hash`() =
		runTest {
			assertNull(dedup.findCanonical(""))
			assertNull(dedup.findCanonical("   "))
		}

	@Test
	fun `shouldSkip and findCanonical agree for an existing hash`() =
		runTest {
			val hash = "agreement-hash"
			coEvery { dao.findByHashAnyVault(hash) } returns entryFor(hash)

			assertTrue(dedup.shouldSkip(hash))
			assertEquals(entryFor(hash), dedup.findCanonical(hash))
		}

	@Test
	fun `shouldSkip and findCanonical agree for a missing hash`() =
		runTest {
			val hash = "missing-agreement-hash"
			coEvery { dao.findByHashAnyVault(hash) } returns null

			assertFalse(dedup.shouldSkip(hash))
			assertNull(dedup.findCanonical(hash))
		}

	@Test
	fun `tombstoned entries are filtered by the DAO and treated as missing`() =
		runTest {
			// The DAO's findByHash SQL is `WHERE content_hash = :hash AND deleted = 0`,
			// so a tombstoned entry returns null from the DAO. Dedup must then say
			// "upload" (the content is no longer referenced by any live photo).
			val hash = "tombstoned-hash"
			coEvery { dao.findByHashAnyVault(hash) } returns null

			assertFalse("Tombstoned entry must not block re-upload", dedup.shouldSkip(hash))
		}
}
