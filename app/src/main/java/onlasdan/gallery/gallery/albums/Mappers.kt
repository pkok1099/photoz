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

package onlasdan.gallery.gallery.albums

import onlasdan.gallery.gallery.albums.domain.model.Album
import onlasdan.gallery.gallery.albums.domain.model.AlbumPhotoRef
import onlasdan.gallery.gallery.albums.ui.compose.AlbumCover
import onlasdan.gallery.gallery.albums.ui.compose.AlbumItem
import onlasdan.gallery.model.database.entity.AlbumTable
import onlasdan.gallery.model.database.ref.AlbumPhotoCrossRefTable
import onlasdan.gallery.model.database.ref.AlbumWithPhotos

// Nullable is a safety mechanism for race condition when deleting album from detail view
fun AlbumWithPhotos?.toDomain(): Album = this?.run {
    Album(
        uuid = album.uuid,
        name = album.name,
        modifiedAt = album.modifiedAt,
        files = photos,
    )
} ?: Album(name = "", modifiedAt = System.currentTimeMillis(), files = emptyList())

fun AlbumTable.toDomain(): Album = Album(
    uuid = uuid,
    name = name,
    modifiedAt = modifiedAt,
    files = emptyList(),
)

fun Album.toData(): AlbumTable = AlbumTable(
    name = name,
    modifiedAt = modifiedAt,
    uuid = uuid,
)

fun Album.toUi(): AlbumItem = AlbumItem(
    id = uuid,
    name = name,
    itemCount = files.size,
    albumCover = files.firstOrNull()?.let { firstPhoto ->
        AlbumCover(
            filename = firstPhoto.internalThumbnailFileName,
            mimeType = firstPhoto.type.mimeType
        )
    }
)

fun AlbumPhotoCrossRefTable.toDomain(): AlbumPhotoRef =
    AlbumPhotoRef(
        albumUUID = albumUUID,
        photoUUID = photoUUID,
        linkedAt = linkedAt,
        pinned = pinned,
    )

fun AlbumPhotoRef.toData(): AlbumPhotoCrossRefTable =
    AlbumPhotoCrossRefTable(
        albumUUID = albumUUID,
        photoUUID = photoUUID,
        linkedAt = linkedAt,
        pinned = pinned,
    )