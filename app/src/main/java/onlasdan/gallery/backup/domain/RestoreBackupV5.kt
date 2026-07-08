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

package onlasdan.gallery.backup.domain

import onlasdan.gallery.backup.data.BackupMetaData
import onlasdan.gallery.backup.data.getPhotosInOriginalOrder
import onlasdan.gallery.backup.data.toDomain
import onlasdan.gallery.encryption.domain.VaultProtectionRepository
import onlasdan.gallery.encryption.domain.crypto.CryptoEngine
import onlasdan.gallery.encryption.domain.models.Session
import onlasdan.gallery.encryption.domain.models.VaultProtection
import onlasdan.gallery.encryption.domain.models.VaultProtectionType
import onlasdan.gallery.gallery.albums.domain.AlbumRepository
import onlasdan.gallery.io.IO
import onlasdan.gallery.io.VaultFileStorage
import onlasdan.gallery.model.repositories.PhotoRepository
import timber.log.Timber
import java.util.UUID
import java.util.zip.ZipInputStream
import javax.inject.Inject

/**
 * Backup Format V5
 *
 *  A ZIP archive with the following structure:
 *
 *  ┌─────────────────────────────────────────┐
 *  │                backup.zip              │
 *  ├─────────────────────────────────────────┤
 *  │ meta.json                              │
 *  │   {                                    │
 *  │     "wrappedVmk": String,              │
 *  │     "params": [VaultProtectionParams], │
 *  │     "photos": [PhotoBackup],           │
 *  │     "albums": [AlbumBackup],           │
 *  │     "albumPhotoRefs":                  │
 *  │ [AlbumPhotoRefBackup],          │
 *  │     "createdAt": Long,                 │
 *  │     "backupVersion": Int               │
 *  │   }                                    │
 *  │                                        │
 *  │ <uuid>.crypt                           │  ← Encrypted photo/video
 *  │ <uuid>.crypt.tn                        │  ← Encrypted thumbnail
 *  │ <uuid>.crypt.vp                        │  ← Encrypted video preview
 *  │ ...                                    │
 *  └─────────────────────────────────────────┘
 *
 * Notes:
 *  - `wrappedVmk` is the wrapped vault master key.
 *  - `params` is the vault protection parameters needed to decrypt the vmk.
 *  - `photos`, `albums`, and `albumPhotoRefs` define the logical structure.
 *  - Each media file is identified by a UUID and encrypted.
 *  - `createdAt` is the timestamp of backup creation.
 *  - `backupVersion` must equal 5 for this format.
 *
 *  ## F-BACK-002 — Vault protection restore
 *
 *  Previously this class only restored photo/album files + DB rows but did NOT
 *  write the source vault's `VaultProtection(Password)` row to the local
 *  `BootstrapDatabase`. The result: restore was a "merge photos into current
 *  vault" operation, not a "restore vault from backup" operation. On a fresh
 *  install with no existing vault, the user could not unlock after restore
 *  because no Password row existed.
 *
 *  Now: the source `wrappedVMK` + `params` from the backup metadata are
 *  written as a new VaultProtection row (with a fresh UUID id) so the user
 *  can unlock with the backup's password after restore.
 */
class RestoreBackupV5
        @Inject
        constructor(
                private val photoRepository: PhotoRepository,
                private val albumRepository: AlbumRepository,
                private val io: IO,
                private val vaultFileStorage: VaultFileStorage,
                private val cryptoEngine: CryptoEngine,
                private val vaultProtectionRepository: VaultProtectionRepository,
        ) : RestoreBackupStrategy<BackupMetaData.V5> {
                override suspend fun restore(
                        metaData: BackupMetaData.V5,
                        stream: ZipInputStream,
                        session: Session,
                ): RestoreResult {
                        val start = System.currentTimeMillis()

                        var errors = 0

                        // F-BACK-002: persist the source vault's Password protection row so
                        // the user can unlock with the backup's password after restore.
                        // We generate a fresh UUID for the row id (the original id is not
                        // preserved in V5 metadata). If a row with the same wrappedVMK
                        // already exists (e.g. re-restoring the same backup), the insert
                        // is idempotent from the user's perspective — both rows unwrap to
                        // the same VMK, and multi-vault unlock picks the first match.
                        runCatching {
                                vaultProtectionRepository.createProtection(
                                        VaultProtection(
                                                id = UUID.randomUUID().toString(),
                                                type = VaultProtectionType.Password,
                                                wrappedVMK = metaData.wrappedVMK.let { kotlin.io.encoding.Base64.decode(it) },
                                                params = metaData.params,
                                        ),
                                )
                        }.onFailure { Timber.e(it, "F-BACK-002: failed to persist source vault protection row") }

                        var ze = stream.nextEntry

                        while (ze != null) {
                                if (ze.name == BackupMetaData.FILE_NAME) {
                                        ze = stream.nextEntry
                                        continue
                                }

                                // Skip files that are not mentioned in the metadata
                                // These might be dead files from old versions of photok
                                if (metaData.photos.none { ze.name.contains(it.uuid) }) {
                                        Timber.i("Skipping dead file in backup: ${ze.name}")
                                        ze = stream.nextEntry
                                        continue
                                }

                                val encryptedZipInput = cryptoEngine.createDecryptStream(stream, session)
                                val internalOutputStream = vaultFileStorage.openEncryptedOutput(ze.name)

                                if (encryptedZipInput == null || internalOutputStream == null) {
                                        ze = stream.nextEntry
                                        continue
                                }

                                io
                                        .copy(encryptedZipInput, internalOutputStream)
                                        .onFailure {
                                                Timber.e(it, "Error restoring zip entry: ${ze.name}")
                                                errors++
                                        }

                                ze = stream.nextEntry
                        }

                        metaData.getPhotosInOriginalOrder().forEach { photoBackup ->
                                val newPhoto =
                                        photoBackup
                                                .toDomain()
                                                .copy(importedAt = System.currentTimeMillis())

                                photoRepository.insert(newPhoto)
                        }

                        metaData.albums.forEach { albumBackup ->
                                val album = albumBackup.toDomain()
                                albumRepository.createAlbum(album)
                        }

                        metaData.albumPhotoRefs.forEach { albumPhotoRefBackup ->
                                val albumPhotoRef = albumPhotoRefBackup.toDomain()
                                albumRepository.link(albumPhotoRef)
                        }

                        Timber.d("PERFORMANCE: Restore backup took ${System.currentTimeMillis() - start}ms")

                        return RestoreResult(errors)
                }
        }
