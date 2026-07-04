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

package onlasdan.gallery.security

import android.app.Application
import onlasdan.gallery.encryption.domain.SessionRepository
import onlasdan.gallery.encryption.domain.VaultService
import onlasdan.gallery.encryption.domain.models.VaultProtectionType
import onlasdan.gallery.gallery.albums.domain.AlbumRepository
import onlasdan.gallery.model.repositories.PhotoRepository
import onlasdan.gallery.settings.data.Config
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sprint 7 / P3 — Panic Wipe.
 *
 * Permanently deletes ALL local vault data (photos, thumbnails, albums,
 * VaultProtection rows, registry cache) and resets the app to a fresh-
 * install state. Used when the user triggers an emergency wipe:
 *
 *  - **Dial code panic**: the user configures a special "panic dial code"
 *    (distinct from the stealth launch code). Dialing it wipes the vault
 *    instead of opening it.
 *  - **Panic password** (future): a special password that, when entered
 *    at the unlock screen, wipes the vault instead of unlocking it. This
 *    is the "under duress" scenario — the user appears to cooperate by
 *    entering a password, but the vault is wiped instead of opened.
 *
 * The wipe is IRREVERSIBLE — there's no trash, no undo. The remote backup
 * (if any) is NOT touched by this wipe (the user can restore from cloud
 * later by re-registering with their recovery phrase). Only local data
 * is destroyed.
 *
 * @since v13 — Sprint 7 / P3 panic mode
 */
@Singleton
class PanicWipeUseCase @Inject constructor(
    private val app: Application,
    private val photoRepository: PhotoRepository,
    private val albumRepository: AlbumRepository,
    private val vaultService: VaultService,
    private val sessionRepository: SessionRepository,
    private val config: Config,
) {
    /**
     * Wipe all local vault data. Returns the count of photos deleted.
     *
     * Steps (all best-effort — a failure at any step logs + continues so
     * partial wipes still destroy as much as possible):
     *  1. Delete all encrypted photo files + thumbnails from filesDir.
     *  2. Delete all Photo DB rows.
     *  3. Delete all Album DB rows + cross-refs.
     *  4. Reset all VaultProtection rows (Password, Biometric, RecoveryPhrase).
     *  5. Clear the session (VMK from memory).
     *  6. Clear legacy password hash (1.x migration residue).
     *
     * Does NOT touch:
     *  - The remote rclone backup (user can restore later).
     *  - The rclone config (user keeps their cloud setup).
     *  - App settings (lock timeout, stealth mode, etc.) — the user might
     *    want to re-setup the vault with the same preferences.
     */
    suspend fun wipe(): Int {
        android.util.Log.e("RcloneDiag", "PanicWipe: INITIATED — wiping all local vault data")
        var deletedCount = 0

        try {
            // 1. Delete all encrypted photo files + thumbnails.
            val allPhotos = photoRepository.findAllPhotosByImportDateDesc()
            for (photo in allPhotos) {
                try {
                    photoRepository.deleteInternalPhotoData(photo)
                    deletedCount++
                } catch (e: Exception) {
                    Timber.w(e, "PanicWipe: failed to delete photo data for %s (continuing)", photo.uuid)
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "PanicWipe: photo file deletion phase failed")
        }

        try {
            // 2. Delete all Photo DB rows.
            photoRepository.deleteAll()
        } catch (e: Exception) {
            Timber.e(e, "PanicWipe: Photo DB deletion failed")
        }

        try {
            // 3. Delete all Album DB rows + cross-refs.
            albumRepository.deleteAll()
            albumRepository.unlinkAll()
        } catch (e: Exception) {
            Timber.e(e, "PanicWipe: Album DB deletion failed")
        }

        try {
            // 4. Reset all VaultProtection rows.
            vaultService.reset(VaultProtectionType.Password)
            vaultService.reset(VaultProtectionType.Biometric)
            vaultService.reset(VaultProtectionType.RecoveryPhrase)
        } catch (e: Exception) {
            Timber.e(e, "PanicWipe: VaultProtection reset failed")
        }

        try {
            // 5. Clear session.
            sessionRepository.reset()
        } catch (e: Exception) {
            Timber.w(e, "PanicWipe: session reset failed")
        }

        try {
            // 6. Clear legacy password hash.
            config.legacyPasswordHash = null
            config.legacyUserSalt = null
        } catch (e: Exception) {
            Timber.w(e, "PanicWipe: legacy hash clear failed")
        }

        android.util.Log.e("RcloneDiag", "PanicWipe: COMPLETE — deleted $deletedCount photos")
        return deletedCount
    }
}
