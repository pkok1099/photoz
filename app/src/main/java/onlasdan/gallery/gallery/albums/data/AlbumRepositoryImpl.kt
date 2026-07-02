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

package onlasdan.gallery.gallery.albums.data

import onlasdan.gallery.gallery.albums.domain.AlbumRepository
import onlasdan.gallery.gallery.albums.domain.model.Album
import onlasdan.gallery.gallery.albums.domain.model.AlbumPhotoRef
import onlasdan.gallery.gallery.albums.toData
import onlasdan.gallery.gallery.albums.toDomain
import onlasdan.gallery.model.database.dao.AlbumDao
import onlasdan.gallery.model.database.entity.Photo
import onlasdan.gallery.sort.domain.Sort
import onlasdan.gallery.sort.domain.SortConfig
import onlasdan.gallery.sort.domain.SortRepository
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject

class AlbumRepositoryImpl @Inject constructor(
    private val albumDao: AlbumDao,
    private val sortRepository: SortRepository,
) : AlbumRepository {

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeAllAlbumsWithPhotos(): Flow<List<Album>> {
        return sortRepository.observeSortsForAlbums().flatMapLatest { sorts ->
            albumDao.observeAllAlbums().map { albums ->
                albums.map { album ->
                    val photos = albumDao.getPhotosForAlbum(album.uuid, sorts[album.uuid] ?: SortConfig.Album.default)
                    album.toDomain().copy(files = photos)
                }
            }
        }
    }

    override suspend fun getAlbums(): List<Album> = withContext(IO) {
        albumDao.getAllAlbums().map { album -> album.toDomain() }
    }

    override fun observeAlbumWithPhotos(uuid: String, sort: Sort): Flow<Album> =
        albumDao.observeAlbumWithPhotos(uuid, sort)
            .map { it.toDomain() }

    override suspend fun getPhotosForAlbum(uuid: String): List<Photo> = withContext(IO) {
        val sort = sortRepository.getSortForAlbum(uuid) ?: SortConfig.Album.default

        albumDao.getPhotosForAlbum(uuid, sort)
    }

    override suspend fun createAlbum(album: Album): Result<Album> = withContext(IO) {
        when (albumDao.insert(album.toData())) {
            -1L -> Result.failure(IOException())
            else -> Result.success(album.copy())
        }
    }
    override suspend fun deleteAlbum(album: Album): Result<Unit> = withContext(IO) {
        when (albumDao.unlinkAndDeleteAlbum(album.toData())) {
            -1 -> Result.failure(IOException())
            else -> Result.success(Unit)
        }
    }


    override suspend fun deleteAll() = withContext(IO) {
        albumDao.deleteAll()
    }

    override suspend fun link(photoUUIDs: List<String>, albumUUID: String) = withContext(IO) {
        albumDao.link(photoUUIDs, albumUUID)
    }

    override suspend fun link(ref: AlbumPhotoRef) = withContext(IO) {
        albumDao.insert(ref.toData())
    }

    override suspend fun unlink(photoUUIDs: List<String>, uuid: String) = withContext(IO) {
        albumDao.unlink(photoUUIDs, uuid)
    }

    override suspend fun unlinkAll() = withContext(IO) {
        albumDao.unlinkAll()
    }

    override suspend fun rename(albumUUID: String, newName: String) = withContext(IO) {
        albumDao.renameAlbum(albumUUID = albumUUID, newName = newName)
    }

    override suspend fun getAllAlbumPhotoLinks(): List<AlbumPhotoRef> = withContext(IO) {
        albumDao.getAllAlbumPhotoRefs().map { ref ->
            ref.toDomain()
        }
    }


    override fun observePinnedPhotoUUIDs(albumUUID: String): Flow<Set<String>> =
        albumDao.observePinnedPhotoUUIDs(albumUUID).map { it.toSet() }

    override suspend fun setPinned(photoUUIDs: List<String>, albumUUID: String, pinned: Boolean) = withContext(IO) {
        albumDao.updatePinned(photoUUIDs, albumUUID, pinned)
    }
}