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

import onlasdan.gallery.gallery.albums.domain.model.Album
import onlasdan.gallery.gallery.albums.domain.model.AlbumPhotoRef
import onlasdan.gallery.model.database.entity.Photo
import onlasdan.gallery.sync.domain.SyncState

fun Photo.toBackup(): PhotoBackup =
	PhotoBackup(
		fileName = fileName,
		importedAt = importedAt,
		lastModified = lastModified,
		type = type,
		size = size,
		uuid = uuid,
		// F-BACK-001: round-trip all post-V3 metadata.
		syncState = syncState.storageKey,
		relativePath = relativePath,
		contentHash = contentHash,
		albumPath = albumPath,
		deletedAt = deletedAt,
		vaultId = vaultId,
		isFavorite = isFavorite,
		exifDateTaken = exifDateTaken,
		exifGpsLat = exifGpsLat,
		exifGpsLon = exifGpsLon,
		exifCamera = exifCamera,
		aiTags = aiTags,
		canonicalUuid = canonicalUuid,
	)

fun PhotoBackup.toDomain(): Photo =
	Photo(
		fileName = fileName,
		importedAt = importedAt,
		lastModified = lastModified,
		type = type,
		size = size,
		uuid = uuid,
		// F-BACK-001: restore all post-V3 metadata. Defaults are safe for
		// backups that predate this fix (fields will be null/0/false).
		syncState = syncState?.let { runCatching { SyncState.fromStorageKey(it) }.getOrDefault(SyncState.LOCAL_ONLY) } ?: SyncState.LOCAL_ONLY,
		relativePath = relativePath,
		contentHash = contentHash,
		albumPath = albumPath,
		deletedAt = deletedAt,
		vaultId = vaultId,
		isFavorite = isFavorite,
		exifDateTaken = exifDateTaken,
		exifGpsLat = exifGpsLat,
		exifGpsLon = exifGpsLon,
		exifCamera = exifCamera,
		aiTags = aiTags,
		canonicalUuid = canonicalUuid,
	)

fun Album.toBackup(): AlbumBackup =
	AlbumBackup(
		uuid = uuid,
		modifiedAt = modifiedAt,
		name = name,
	)

fun AlbumBackup.toDomain(): Album =
	Album(
		uuid = uuid,
		name = name,
		modifiedAt = modifiedAt ?: System.currentTimeMillis(),
		files = emptyList(),
	)

fun AlbumPhotoRef.toBackup(): AlbumPhotoRefBackup =
	AlbumPhotoRefBackup(
		albumUUID = albumUUID,
		photoUUID = photoUUID,
		linkedAt = linkedAt,
		pinned = pinned,
	)

fun AlbumPhotoRefBackup.toDomain(): AlbumPhotoRef =
	AlbumPhotoRef(
		albumUUID = albumUUID,
		photoUUID = photoUUID,
		linkedAt = linkedAt,
		pinned = pinned,
	)
