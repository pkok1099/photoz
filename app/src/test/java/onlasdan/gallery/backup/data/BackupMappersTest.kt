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

package onlasdan.gallery.backup.data

import onlasdan.gallery.model.database.entity.Photo
import onlasdan.gallery.model.database.entity.PhotoType
import onlasdan.gallery.sync.domain.SyncState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * F-BACK-006 — Round-trip tests for [Photo.toBackup] / [PhotoBackup.toDomain].
 *
 * Verifies that ALL 19 Photo columns (6 original + 13 post-V3) survive a
 * backup → restore round-trip without data loss. Before the F-BACK-001 fix,
 * only 6 fields were backed up; the other 13 were silently dropped.
 *
 * @since F-BACK-001 — full metadata round-trip
 */
class BackupMappersTest {
	@Test
	fun `round-trip preserves all original V3 fields`() {
		val photo = Photo(
			fileName = "test.jpg",
			importedAt = 1700000000000L,
			lastModified = 1699999999000L,
			type = PhotoType.JPEG,
			size = 123456L,
			uuid = "uuid-123",
		)

		val restored = photo.toBackup().toDomain()

		assertEquals(photo.fileName, restored.fileName)
		assertEquals(photo.importedAt, restored.importedAt)
		assertEquals(photo.lastModified, restored.lastModified)
		assertEquals(photo.type, restored.type)
		assertEquals(photo.size, restored.size)
		assertEquals(photo.uuid, restored.uuid)
	}

	@Test
	fun `round-trip preserves syncState`() {
		val photo = Photo(
			fileName = "test.jpg",
			importedAt = 1L,
			lastModified = null,
			type = PhotoType.JPEG,
			size = 1L,
			uuid = "uuid",
			syncState = SyncState.UPLOADED,
		)

		val restored = photo.toBackup().toDomain()

		assertEquals(SyncState.UPLOADED, restored.syncState)
	}

	@Test
	fun `round-trip preserves contentHash and albumPath`() {
		val photo = Photo(
			fileName = "test.jpg",
			importedAt = 1L,
			lastModified = null,
			type = PhotoType.JPEG,
			size = 1L,
			uuid = "uuid",
			contentHash = "sha256abcdef",
			albumPath = "DCIM/Camera",
		)

		val restored = photo.toBackup().toDomain()

		assertEquals("sha256abcdef", restored.contentHash)
		assertEquals("DCIM/Camera", restored.albumPath)
	}

	@Test
	fun `round-trip preserves vaultId and isFavorite`() {
		val photo = Photo(
			fileName = "test.jpg",
			importedAt = 1L,
			lastModified = null,
			type = PhotoType.JPEG,
			size = 1L,
			uuid = "uuid",
			vaultId = "vault-id-abc",
			isFavorite = true,
		)

		val restored = photo.toBackup().toDomain()

		assertEquals("vault-id-abc", restored.vaultId)
		assertEquals(true, restored.isFavorite)
	}

	@Test
	fun `round-trip preserves deletedAt`() {
		val photo = Photo(
			fileName = "test.jpg",
			importedAt = 1L,
			lastModified = null,
			type = PhotoType.JPEG,
			size = 1L,
			uuid = "uuid",
			deletedAt = 1700000000000L,
		)

		val restored = photo.toBackup().toDomain()

		assertEquals(1700000000000L, restored.deletedAt)
	}

	@Test
	fun `round-trip preserves EXIF metadata`() {
		val photo = Photo(
			fileName = "test.jpg",
			importedAt = 1L,
			lastModified = null,
			type = PhotoType.JPEG,
			size = 1L,
			uuid = "uuid",
			exifDateTaken = 1699999999000L,
			exifGpsLat = 37.7749,
			exifGpsLon = -122.4194,
			exifCamera = "Canon EOS R6",
		)

		val restored = photo.toBackup().toDomain()

		assertEquals(1699999999000L, restored.exifDateTaken)
		assertEquals(37.7749, restored.exifGpsLat!!, 0.0001)
		assertEquals(-122.4194, restored.exifGpsLon!!, 0.0001)
		assertEquals("Canon EOS R6", restored.exifCamera)
	}

	@Test
	fun `round-trip preserves aiTags`() {
		val photo = Photo(
			fileName = "test.jpg",
			importedAt = 1L,
			lastModified = null,
			type = PhotoType.JPEG,
			size = 1L,
			uuid = "uuid",
			aiTags = "beach,sunset,ocean",
		)

		val restored = photo.toBackup().toDomain()

		assertEquals("beach,sunset,ocean", restored.aiTags)
	}

	@Test
	fun `round-trip preserves canonicalUuid for symlink albums`() {
		val photo = Photo(
			fileName = "test.jpg",
			importedAt = 1L,
			lastModified = null,
			type = PhotoType.JPEG,
			size = 1L,
			uuid = "uuid-symlink",
			canonicalUuid = "uuid-canonical",
		)

		val restored = photo.toBackup().toDomain()

		assertEquals("uuid-canonical", restored.canonicalUuid)
	}

	@Test
	fun `round-trip preserves relativePath`() {
		val photo = Photo(
			fileName = "test.jpg",
			importedAt = 1L,
			lastModified = null,
			type = PhotoType.JPEG,
			size = 1L,
			uuid = "uuid",
			relativePath = "Pictures/Vacation",
		)

		val restored = photo.toBackup().toDomain()

		assertEquals("Pictures/Vacation", restored.relativePath)
	}

	@Test
	fun `round-trip handles nullable fields with null values`() {
		val photo = Photo(
			fileName = "test.jpg",
			importedAt = 1L,
			lastModified = null,
			type = PhotoType.JPEG,
			size = 1L,
			uuid = "uuid",
			relativePath = null,
			contentHash = null,
			albumPath = null,
			vaultId = null,
			exifDateTaken = null,
			exifGpsLat = null,
			exifGpsLon = null,
			exifCamera = null,
			aiTags = null,
			canonicalUuid = null,
		)

		val restored = photo.toBackup().toDomain()

		assertNull(restored.lastModified)
		assertNull(restored.relativePath)
		assertNull(restored.contentHash)
		assertNull(restored.albumPath)
		assertNull(restored.vaultId)
		assertNull(restored.exifDateTaken)
		assertNull(restored.exifGpsLat)
		assertNull(restored.exifGpsLon)
		assertNull(restored.exifCamera)
		assertNull(restored.aiTags)
		assertNull(restored.canonicalUuid)
	}

	@Test
	fun `round-trip of photo with all fields populated preserves every field`() {
		val photo = Photo(
			fileName = "IMG_001.jpg",
			importedAt = 1700000000000L,
			lastModified = 1699999999000L,
			type = PhotoType.JPEG,
			size = 5242880L,
			uuid = "uuid-full-001",
			syncState = SyncState.UPLOAD_PENDING,
			relativePath = "DCIM/Camera",
			contentHash = "abc123def456",
			albumPath = "DCIM/Camera",
			deletedAt = 0L,
			vaultId = "vault-001",
			isFavorite = true,
			exifDateTaken = 1699999999000L,
			exifGpsLat = 35.6762,
			exifGpsLon = 139.6503,
			exifCamera = "Sony A7IV",
			aiTags = "tokyo,street,night",
			canonicalUuid = null,
		)

		val restored = photo.toBackup().toDomain()

		assertEquals(photo.fileName, restored.fileName)
		assertEquals(photo.importedAt, restored.importedAt)
		assertEquals(photo.lastModified, restored.lastModified)
		assertEquals(photo.type, restored.type)
		assertEquals(photo.size, restored.size)
		assertEquals(photo.uuid, restored.uuid)
		assertEquals(photo.syncState, restored.syncState)
		assertEquals(photo.relativePath, restored.relativePath)
		assertEquals(photo.contentHash, restored.contentHash)
		assertEquals(photo.albumPath, restored.albumPath)
		assertEquals(photo.deletedAt, restored.deletedAt)
		assertEquals(photo.vaultId, restored.vaultId)
		assertEquals(photo.isFavorite, restored.isFavorite)
		assertEquals(photo.exifDateTaken, restored.exifDateTaken)
		assertEquals(photo.exifGpsLat!!, restored.exifGpsLat!!, 0.0001)
		assertEquals(photo.exifGpsLon!!, restored.exifGpsLon!!, 0.0001)
		assertEquals(photo.exifCamera, restored.exifCamera)
		assertEquals(photo.aiTags, restored.aiTags)
		assertEquals(photo.canonicalUuid, restored.canonicalUuid)
	}

	@Test
	fun `syncState deserialization falls back to LOCAL_ONLY for unknown keys`() {
		val backup = PhotoBackup(
			fileName = "test.jpg",
			importedAt = 1L,
			lastModified = null,
			type = PhotoType.JPEG,
			size = 1L,
			uuid = "uuid",
			syncState = "UNKNOWN_STATE",
		)

		val restored = backup.toDomain()

		assertEquals(SyncState.LOCAL_ONLY, restored.syncState)
	}

	@Test
	fun `syncState deserialization handles null syncState`() {
		val backup = PhotoBackup(
			fileName = "test.jpg",
			importedAt = 1L,
			lastModified = null,
			type = PhotoType.JPEG,
			size = 1L,
			uuid = "uuid",
			syncState = null,
		)

		val restored = backup.toDomain()

		assertEquals(SyncState.LOCAL_ONLY, restored.syncState)
	}
}
