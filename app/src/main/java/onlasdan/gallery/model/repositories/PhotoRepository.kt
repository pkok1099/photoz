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

package onlasdan.gallery.model.repositories

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import onlasdan.gallery.io.IO
import onlasdan.gallery.io.VaultFileStorage
import onlasdan.gallery.model.database.dao.AlbumDao
import onlasdan.gallery.model.database.dao.PhotoDao
import onlasdan.gallery.model.database.entity.Photo
import onlasdan.gallery.model.database.entity.PhotoType
import onlasdan.gallery.model.io.CreateThumbnailsUseCase
import onlasdan.gallery.other.extensions.empty
import onlasdan.gallery.other.extensions.lazyClose
import onlasdan.gallery.other.getMetadataFor
import onlasdan.gallery.settings.data.Config
import onlasdan.gallery.sort.domain.Sort
import onlasdan.gallery.sync.work.HashRegistry
import onlasdan.gallery.sync.work.PhotoSyncWorker
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import kotlin.coroutines.resume

/**
 * Repository for [Photo].
 * Uses [PhotoDao] and accesses the filesystem to read and write encrypted photos.
 *
 * @since 1.0.0
 * @author PhotoZ
 */
class PhotoRepository
	@Inject
	constructor(
		private val photoDao: PhotoDao,
		private val albumDao: AlbumDao,
		private val vaultFileStorage: VaultFileStorage,
		private val createThumbnail: CreateThumbnailsUseCase,
		private val app: Application,
		private val config: Config,
		private val io: IO,
		// @since registry-gc feature — used by [safeDeletePhoto] to tombstone the
		// dedup registry entry for a deleted photo's content_hash BEFORE the local
		// encrypted file is removed. The tombstone is propagated to the remote
		// registry so other devices stop referencing this hash. The actual remote
		// original + thumbnail cleanup happens later in [HashRegistry.gcOriginals]
		// when the user runs "Clean up backup" from Settings.
		private val hashRegistry: HashRegistry,
		// @since Item 1 ZIP backup — serializes the manifest.json entry inside
		//  the export ZIP and parses it back on import. Hilt-provided (see
		//  AppModule.provideGson).
		private val gson: Gson,
		/**
		 * Sprint 2 / M7 — Multi-vault.
		 *
		 * Used to fetch the current session's `vault_id` for filtering all
		 * Photo queries (gallery, trash, dedup, sync-pending). Throws if the
		 * vault is locked — callers must guard with `sessionRepository.get() != null`
		 * when invoking from non-UI contexts (e.g. WorkManager workers that
		 * run after the user has unlocked).
		 */
		private val sessionRepository: onlasdan.gallery.encryption.domain.SessionRepository,
		/**
		 * TODO #1 — QtFastStart: MP4 MOOV relocation for progressive video streaming.
		 * Runs on plaintext before encryption. Non-fatal if it fails.
		 */
		private val fastStartUseCase: onlasdan.gallery.model.io.FastStartUseCase,
	) {
		// region DATABASE

		/**
		 * @see PhotoDao.insert
		 */
		suspend fun insert(photo: Photo) = photoDao.insert(photo)

		/**
		 * @see PhotoDao.delete
		 */
		private suspend fun delete(photo: Photo) = photoDao.delete(photo)

		/**
		 * @see PhotoDao.deleteAll
		 */
		suspend fun deleteAll() = photoDao.deleteAll()

		suspend fun get(uuid: String) = photoDao.get(uuid)

		/**
		 * @see PhotoDao.findAllPhotosByImportDateDesc
		 */
		suspend fun findAllPhotosByImportDateDesc() = photoDao.findAllPhotosByImportDateDesc()

		/**
		 * Sprint 2 / M7 — observe all live photos in the CURRENT vault, sorted.
		 *
		 * Throws if the vault is locked (no active session). Callers from the UI
		 * are always post-unlock, so this is safe.
		 */
		fun observeAll(sort: Sort) = photoDao.observeAllSorted(sort, currentVaultId())

		/**
		 * @see PhotoDao.countAll
		 */
		suspend fun countAll() = photoDao.countAll(currentVaultId())

		// endregion

		/**
		 * Sprint 2 / M7 — Returns the current session's `vault_id`.
		 *
		 * Throws if the vault is locked. All multi-vault-scoped DAO queries go
		 * through this so the filter is consistent across the app.
		 *
		 * @since v11 — Sprint 2 / M7 multi-vault
		 */
		private fun currentVaultId(): String = sessionRepository.get()?.vaultId ?: ""

		// region IO

		// region WRITE

		/**
		 * Import a photo (or any other file type) from a url.
		 *
		 * Collects meta data and calls [safeCreatePhoto].
		 * Returns re created uuid
		 *
		 * @since file-upload feature — now accepts ANY file type, not just photos.
		 *   The [PhotoType.fromMimeType] lookup recognizes JPEG/PNG/GIF/HEIC/MP4/
		 *   WEBP (photos + videos) AND PDF/ZIP/AUDIO (generic files). Each is
		 *   stored, encrypted and dedup'd identically; only the gallery view and
		 *   the tap-to-open flow branch on `photo.type.isFile`.
		 */

		// Sprint 3 / M10 — Import a photo from sourceUri.
		//
		// @param sourceUri the source URI (MediaStore, SAF, or Photo Picker).
		// @param importSource provenance marker for analytics + delete-after-import.
		// @param overrideAlbumPath OPTIONAL — when non-null, sets the photo's
		//   albumPath to this value instead of the auto-detected folder path.
		//   Used by the Photo Picker flow (M10): the picker URI doesn't expose
		//   RELATIVE_PATH, so the auto-album-from-folder logic would skip;
		//   the Path Maker dialog lets the user pick an album, and that choice
		//   is passed here to override.
		//
		//   When non-null, ensureAlbumForPhoto will find-or-create the named
		//   album and link the photo to it (same as the MediaStore auto path).
		suspend fun safeImportPhoto(
			sourceUri: Uri,
			importSource: ImportSource,
			overrideAlbumPath: String? = null,
		): String {
			val metaData = app.contentResolver.getMetadataFor(sourceUri)

			val type = PhotoType.fromMimeType(metaData.mimeType)
			if (type == PhotoType.UNDEFINED) return String.empty

			// ─── v9 dedup: compute SHA-256 of the plaintext source bytes ───────
			// The hash is computed INCREMENTALLY as the source stream is copied to
			// the encrypted destination (see [createPhotoFile]) — no extra disk
			// read and no extra memory beyond the digest's internal state. The
			// resulting hex string is the dedup key consulted by the upload worker
			// before transferring the original to the remote: if the same hash is
			// already on the remote under a different UUID, the upload is skipped.
			//
			// `null` if the source stream can't be read (the import will fail
			// downstream anyway) — the worker treats null hash as "no dedup,
			// upload normally".
			val sha256 = runCatching { MessageDigest.getInstance("SHA-256") }.getOrNull()

			val inputStream = io.openFileInput(sourceUri)

			// TODO #1 — QtFastStart: relocate MOOV atom for MP4/MOV videos
			// so ExoPlayer can start playback before full download (progressive
			// streaming). Runs on the PLAINTEXT before encryption.
			//
			// If faststart is needed and succeeds, we use the faststarted temp
			// file as the encryption source instead of the original URI.
			// If it fails or isn't needed, we use the original — non-fatal.
			var fastStartTempFile: java.io.File? = null
			var effectiveInputStream: java.io.InputStream? = inputStream
			var effectiveSize: Long? = metaData.size

			if (type.isVideo && fastStartUseCase.isFastStartCandidate(metaData.mimeType)) {
				try {
					val fastStartedFile = fastStartUseCase.fastStart(sourceUri, metaData.fileName)
					if (fastStartedFile != null) {
						fastStartTempFile = fastStartedFile
						effectiveInputStream = java.io.FileInputStream(fastStartedFile)
						effectiveSize = fastStartedFile.length()
						android.util.Log.i(
							"RcloneDiag",
							"[FastStart] Using faststarted file for import (${fastStartedFile.length()} bytes)",
						)
					}
				} catch (e: Exception) {
					Timber.w(e, "FastStart: integration failed (non-fatal) — using original")
				}
			}
			// Sprint 6 / M4 — extract EXIF metadata at import time.
			// For non-image types (video, PDF, audio) EXIF will be null/empty —
			// that's fine, the columns are nullable. The gallery search parser
			// treats null EXIF as non-matching for `date:`/`camera:`/`location:`
			// queries.
			val exif =
				if (!type.isVideo && !type.isFile) {
					onlasdan.gallery.model.io
						.extractExifMetadata(app, sourceUri)
				} else {
					onlasdan.gallery.model.io
						.ExifMetadata()
				}
			val photo =
				Photo(
					fileName = metaData.fileName ?: UUID.randomUUID().toString(),
					importedAt = System.currentTimeMillis(),
					lastModified = metaData.lastModified,
					type = type,
					size = effectiveSize ?: metaData.size ?: 0,
					// ─── Path-consistency metadata (v8) ───────────────────────────────
					// Capture the photo's original local-origin provenance for the user's
					// reference. The vault is its own managed encrypted storage — this field
					// is metadata only, NOT a filesystem path the photo gets written to.
					//
					// TODO(v8-followup): For gallery-sourced imports (ImportSource.InApp
					//   with a `content://media/...` URI), we could query the MediaStore
					//   `RELATIVE_PATH` column (e.g. `DCIM/Camera`, `Pictures/Screenshots`)
					//   to capture the full original subfolder rather than just the
					//   filename. The current `FileMetaData` from `getMetadataFor()` only
					//   exposes `DISPLAY_NAME`/`SIZE`/`COLUMN_LAST_MODIFIED` — it doesn't
					//   read `RELATIVE_PATH`. Until that's extended, the filename itself
					//   is the most meaningful provenance we have.
					//   See onlasdan.gallery.other.getMetadataFor.
					relativePath = metaData.relativePath ?: metaData.fileName,
					// ─── v9 dedup: album-path from MediaStore RELATIVE_PATH ──────────
					// metaData.relativePath captures the folder structure (e.g. "Download",
					// "DCIM/Camera") from MediaStore. Falls back to filename if not available
					// (SAF URIs from file pickers may not expose RELATIVE_PATH).
					//
					// Sprint 3 / M10 — [overrideAlbumPath] takes precedence when non-null.
					// Used by the Photo Picker flow: the picker URI has no RELATIVE_PATH,
					// so the user picks an album via Path Maker dialog, and that choice
					// is passed in here. The chosen album is then created/linked by
					// ensureAlbumForPhoto (which uses photo.albumPath as the album name).
					albumPath = overrideAlbumPath ?: metaData.relativePath ?: metaData.fileName,
					// ─── Sprint 2 / M7 — Multi-vault ───────────────────────────────
					// Tag the new photo with the current vault's vault_id so queries
					// filter it correctly. If the vault is somehow locked at import
					// time (shouldn't happen — import requires unlock), the photo is
					// created with vault_id = NULL and backfilled on the next unlock.
					vaultId = runCatching { currentVaultId() }.getOrNull(),
					// ─── Sprint 6 / M4 — EXIF metadata for search ───────────────────
					// Populated from the source photo's EXIF (when available). NULL
					// for screenshots/PDFs/audio or when EXIF is stripped. The gallery
					// search parser recognizes `date:`/`camera:`/`location:` prefixes
					// and filters against these columns.
					exifDateTaken = exif.dateTaken,
					exifGpsLat = exif.gpsLat,
					exifGpsLon = exif.gpsLon,
					exifCamera = exif.camera,
				)

			val created = safeCreatePhoto(photo, effectiveInputStream, sourceUri, sha256)
			inputStream?.lazyClose()
			effectiveInputStream?.lazyClose()

			// TODO #1 — Clean up faststart temp file
			fastStartTempFile?.let { fastStartUseCase.cleanup(it) }

			// ─── v9 dedup: finalize the SHA-256 hash and stash it on the Photo ──
			// The digest was updated incrementally inside createPhotoFile() as the
			// plaintext bytes were streamed through. If the import succeeded, the
			// hash now represents the photo's unencrypted content — store its hex
			// string on the Photo row so the upload worker can consult the dedup
			// registry without re-reading the file.
			if (created && sha256 != null) {
				val hashHex = sha256.digest().joinToString("") { "%02x".format(it) }
				try {
					photoDao.updateContentHash(photo.uuid, hashHex)

					// ─── Sprint 10+ / M10 Part 3: Symlink album dedup ──────────────
					// If another Photo row with the same content_hash already exists,
					// convert THIS photo into a SYMLINK instead of deleting it.
					//
					// The symlink's `canonical_uuid` points to the existing (canonical)
					// photo's UUID. The encrypted file + thumbnail are deleted from
					// this photo's UUID (they were just written) — the symlink will
					// read from the canonical's files via `internalFileName(canonicalUuid ?: uuid)`.
					//
					// This allows the same file to appear in multiple albums without
					// duplicating storage. The user sees two gallery entries (one per
					// album), but only one encrypted file exists on disk + remote.
					//
					// Previously (Bug 1 fix): the duplicate was deleted entirely —
					// the photo only appeared in the first album that imported it.
					val existing =
						photoDao.findByContentHash(
							hashHex,
							excludeUuid = photo.uuid,
							vaultId = currentVaultId(),
						)
					if (existing != null) {
						Timber.i(
							"Symlink dedup: content_hash=%s already exists as uuid=%s — creating symlink %s → %s",
							hashHex,
							existing.uuid,
							photo.uuid,
							existing.uuid,
						)

						// Determine the canonical UUID: if `existing` is itself a
						// symlink, follow the chain to the real canonical. This
						// handles the case where the user imports the same file 3+
						// times — all symlinks point to the same root canonical.
						val canonicalUuid = existing.canonicalUuid ?: existing.uuid

						// Delete the just-written encrypted file + thumbnail for
						// THIS photo's UUID — the symlink will use the canonical's.
						deleteInternalPhotoData(photo)

						// Update this photo to be a symlink.
						photoDao.updateCanonicalUuid(photo.uuid, canonicalUuid)

						// Link the symlink to its album (if albumPath is set).
						try {
							ensureAlbumForPhoto(photo)
						} catch (e: Exception) {
							Timber.w(e, "Symlink dedup: ensureAlbumForPhoto failed (non-fatal)")
						}

						return photo.uuid // signal: import succeeded as a symlink
					}
				} catch (e: Exception) {
					Timber.w(e, "Failed to persist contentHash or dedup check for %s", photo.uuid)
				}
			}

			if (!created) {
				return String.empty
			}

			// ─── Bug 5 fix: auto-create album from folder path ──────────────────
			// If the imported photo carries a real folder path (i.e. `albumPath`
			// came from MediaStore RELATIVE_PATH, NOT the filename fallback used
			// for SAF-picked files), find-or-create an album with that name and
			// link the photo to it. So photos imported from "Download" get grouped
			// into a "Download" album automatically — the user no longer has to
			// create albums by hand and move photos into them one-by-one.
			//
			// Skip when `albumPath` equals the photo's filename: that's the SAF
			// fallback path (no real RELATIVE_PATH available), and treating each
			// filename as its own album would create a useless one-item album per
			// import. Skip when blank too.
			try {
				ensureAlbumForPhoto(photo)
			} catch (e: Exception) {
				// Non-fatal: album creation is a convenience, not a correctness
				// requirement. The photo is already safely imported; if the
				// album link fails (e.g. DB busy), we just skip the grouping.
				Timber.w(e, "Auto-album creation failed for %s (non-fatal)", photo.uuid)
			}

			if (!config.deleteImportedFiles || importSource == ImportSource.Share) {
				return photo.uuid
			}

			val deleted = io.deleteFile(sourceUri)
			return if (deleted == true) photo.uuid else String.empty
		}

		/**
		 * Find-or-create an album named [photo.albumPath] and link [photo] to it.
		 *
		 * Bug 5 fix: photos imported from a folder (e.g. "Download") should be
		 * grouped into an album of the same name automatically. The folder key
		 * comes from MediaStore `RELATIVE_PATH`, captured at import time as
		 * [Photo.albumPath].
		 *
		 * Skip silently when:
		 *   - `albumPath` is null/blank (no folder info available)
		 *   - `albumPath` equals the photo's filename (the SAF fallback — would
		 *     create a one-item album per import, which is noise)
		 *
		 * Idempotent:
		 *   - If an album with this name already exists, the photo is linked to
		 *     that existing album (no duplicate album is created).
		 *   - The link itself uses `OnConflictStrategy.IGNORE` on the cross-ref
		 *     table, so re-linking an already-linked photo is a no-op.
		 *
		 * @since Bug 5 fix — auto-create albums from folder path
		 */
		private suspend fun ensureAlbumForPhoto(photo: Photo) {
			val albumName = photo.albumPath?.trim().orEmpty()
			if (albumName.isBlank()) return
			// Skip the SAF fallback: when the import came through a SAF picker
			// (no MediaStore RELATIVE_PATH available), `albumPath` falls back to
			// the filename. Treating each filename as its own album would pollute
			// the albums list with one-item entries.
			if (albumName == photo.fileName.trim()) return

			// Sprint 2 / M7 — scope the lookup to the current vault.
			val vaultId = runCatching { currentVaultId() }.getOrNull() ?: return
			val existing = albumDao.getByName(albumName, vaultId)
			val albumUUID =
				existing?.uuid ?: run {
					val newAlbum =
						onlasdan.gallery.model.database.entity.AlbumTable(
							name = albumName,
							modifiedAt = System.currentTimeMillis(),
							// Sprint 2 / M7 — tag the new album with the current vault_id
							vaultId = vaultId,
						)
					albumDao.insert(newAlbum)
					// Re-query by name to pick up the just-inserted row's UUID. The
					// insert() return value is the rowid (Long), not the UUID column,
					// and the cross-ref table keys on the UUID string column.
					albumDao.getByName(albumName, vaultId)?.uuid ?: return
				}
			albumDao.link(listOf(photo.uuid), albumUUID)
			Timber.i("Auto-album: linked %s to album '%s' (%s)", photo.uuid, albumName, albumUUID)
		}

		/**
		 * Writes and encrypts the [source] into internal storage.
		 * Saves the [photo] afterwords.
		 * It is up to the caller to close the [source].
		 * Does create a thumbnail, IF [origUri] is specified.
		 *
		 * @param digest optional [MessageDigest] — if non-null, it is updated
		 *   incrementally as the plaintext source bytes are streamed through to
		 *   the encrypted destination. The caller finalizes the digest
		 *   (`digest.digest()`) after this returns to obtain the SHA-256 hash of
		 *   the photo's unencrypted content (used as the v9 dedup key).
		 *   `null` for callers that don't need the hash (e.g. legacy restore).
		 *
		 * @return true, if everything worked
		 */
		private suspend fun safeCreatePhoto(
			photo: Photo,
			source: InputStream?,
			origUri: Uri? = null,
			digest: MessageDigest? = null,
		): Boolean {
			val fileLen = createPhotoFile(photo, source, digest)
			var success = fileLen != -1L

			if (success) {
				photo.size = fileLen

				if (origUri != null) {
					createThumbnail(photo, origUri)
				}

				val photoId = insert(photo)
				success = photoId != -1L
			}

			if (!success) {
				deleteInternalPhotoData(photo)
				return false
			}

			// ─── Sync hook (PR1 + sync-settings feature) ────────────────────────
			// After the DB row is committed, enqueue a WorkManager job to push the encrypted
			// original + thumbnail to the rclone remote. Idempotent via ExistingWorkPolicy.KEEP.
			//
			// @since sync-settings feature: the auto-upload toggle is now a user
			//   preference (config.syncAutoUpload), not a hardcoded SyncConfig flag.
			//   The worker's enqueue() also re-checks the flag (so callers that
			//   don't have a Config instance still respect it), but checking here
			//   too avoids the (cheap) WorkManager.getInstance() call when the
			//   user has explicitly disabled auto-upload.
			if (config.syncAutoUpload) {
				runCatching {
					PhotoSyncWorker.enqueue(app, photo)
				}.onFailure { e ->
					Timber.w(e, "Failed to enqueue sync job for %s", photo.uuid)
				}
			}

			return true
		}

		/**
		 * Create the internal file for a photo.
		 *
		 * @param digest optional [MessageDigest] — if non-null, it is updated
		 *   incrementally as the plaintext source bytes are read (BEFORE they're
		 *   handed to the encrypted destination). This lets the caller compute
		 *   the SHA-256 of the plaintext — the v9 dedup key — without an extra
		 *   disk read. The caller finalizes the digest after this returns.
		 */
		fun createPhotoFile(
			photo: Photo,
			source: InputStream?,
			digest: MessageDigest? = null,
		): Long {
			try {
				// ─── Sprint 1 / P6: AES-256-GCM for new files ───────────────────────
				// Non-video files (photos, PDFs, ZIPs, audio) encrypt with GCM (version
				// byte 0x03) for the authentication tag protection. Video originals
				// still encrypt with CBC (version 0x02) because the random-access
				// streaming DataSource (AesCbcRandomAccessDataSource) relies on CBC's
				// block-chain IV property — GCM streaming is a future enhancement
				// (tracked in ROADMAP.md).
				//
				// Thumbnails and video previews are small enough that they're read in
				// full (no streaming), so they default to GCM via the default
				// `useGcm = true` parameter in CreateThumbnailsUseCase's calls.
				val useGcm = !photo.type.isVideo
				val encryptedDestination =
					vaultFileStorage.openEncryptedOutput(
						fileName = photo.internalFileName,
						useGcm = useGcm,
					)

				source ?: return -1L
				encryptedDestination ?: return -1L

				// ─── v9 dedup: stream-copy with optional digest update ──────────
				// Replaces `source.copyTo(encryptedDestination)` with a manual
				// byte-buffer loop so we can feed each chunk through the digest
				// BEFORE it goes to the encrypted destination. The digest sees
				// the plaintext bytes; the destination sees the AES-CBC-encrypted
				// version of the same bytes (the encryption happens inside
				// `encryptedDestination.write`, transparently to this loop).
				//
				// 64 KB buffer matches the default used by Kotlin's `copyTo`.
				val buf = ByteArray(64 * 1024)
				var fileLen = 0L
				while (true) {
					val n = source.read(buf)
					if (n <= 0) break
					if (digest != null) digest.update(buf, 0, n)
					encryptedDestination.write(buf, 0, n)
					fileLen += n
				}
				encryptedDestination.lazyClose()

				return fileLen
			} catch (e: IOException) {
				Timber.e("Error while writing file: $e")
				return -1L
			}
		}

		// endregion

		// region DELETE

		/**
		 * Move a photo to the Trash: soft-delete the DB row by stamping
		 * `deleted_at = System.currentTimeMillis()`. The encrypted original +
		 * thumbnail files stay on disk so the user can restore the photo. The
		 * registry tombstone is also propagated so other devices stop
		 * referencing this content_hash (the actual remote original cleanup
		 * still happens later via "Clean up backup" in Settings).
		 *
		 * After 30 days (or on "Empty trash" / "Delete permanently"), the row
		 * + files are removed by [permanentlyDeletePhoto] /
		 * [cleanupExpiredTrash].
		 *
		 * @return true if the DB update succeeded.
		 *
		 * @since v10 — recycle bin / soft delete (was hard delete before v10)
		 */

		// Sprint 4 / M2 — Toggle the is_favorite flag for a single photo.
		//
		// Fire-and-forget: the gallery's Flow observer picks up the change and
		// re-renders the heart badge. The toggle is vault-agnostic (the photo's
		// UUID is globally unique within the table), but the gallery only shows
		// the photo in the current vault's view — so toggling favorite from
		// vault A's gallery only affects vault A's view of that photo.
		//
		// @since v12 — Sprint 4 / M2 favorites
		suspend fun toggleFavorite(
			uuid: String,
			isFavorite: Boolean,
		) = withContext(Dispatchers.IO) {
			try {
				photoDao.setFavorite(uuid, isFavorite)
			} catch (e: Exception) {
				Timber.w(e, "toggleFavorite failed for %s (non-fatal)", uuid)
			}
		}

		/**
		 * Sprint 4 / M2 — Count favorites in the current vault.
		 *
		 * Used by the Favorites filter chip's badge and to decide whether the
		 * chip should be enabled (chips with 0 favorites are still tappable but
		 * show an empty state when selected).
		 */
		suspend fun countFavorites(): Int =
			withContext(Dispatchers.IO) {
				try {
					photoDao.countFavorites(currentVaultId())
				} catch (e: Exception) {
					Timber.w(e, "countFavorites failed (non-fatal)")
					0
				}
			}

		suspend fun safeDeletePhoto(photo: Photo): Boolean {
			// ─── registry-gc feature — tombstone the dedup registry entry ──────
			// We still tombstone on soft-delete: the user's intent is "I don't
			// want this photo anymore" and other devices should stop
			// referencing its content_hash. If the user later restores the
			// photo from the trash the tombstone is NOT rolled back.
			//
			// Non-fatal: if the tombstone fails (no registry entry for pre-v9
			// imports, vault locked, network blip), the local soft-delete still
			// proceeds. The orphaned remote original is reclaimable later via
			// the Settings → "Clean up backup" button.
			val contentHash = photo.contentHash
			if (!contentHash.isNullOrBlank()) {
				try {
					hashRegistry.softDelete(contentHash)
				} catch (e: Exception) {
					Timber.w(
						e,
						"safeDeletePhoto: hashRegistry.softDelete FAILED (non-fatal — local soft-delete still proceeds): %s",
						photo.uuid,
					)
				}
			}

			return try {
				photoDao.softDelete(photo.uuid, System.currentTimeMillis())
				// Unlink the photo from any albums so a restored photo doesn't
				// reappear in an album the user removed it from. (Restoring
				// re-adds it to the gallery's "All photos" view but not to any
				// specific album — the user can re-add it manually if needed.)
				// Disabled: keep album links across soft-delete so restore
				// brings the photo back exactly where it was. The album detail
				// query already filters deleted_at = 0, so the photo is
				// invisible in albums while trashed.
				true
			} catch (e: Exception) {
				Timber.w(e, "safeDeletePhoto: softDelete FAILED for %s", photo.uuid)
				false
			}
		}

		/**
		 * Restore a photo from the trash: reset `deleted_at = 0` so the photo
		 * reappears in the gallery and any albums it was linked to.
		 *
		 * @since v10 recycle bin
		 */
		suspend fun restorePhotoFromTrash(uuid: String) {
			try {
				photoDao.restoreFromTrash(uuid)
			} catch (e: Exception) {
				Timber.w(e, "restorePhotoFromTrash: FAILED for %s", uuid)
			}
		}

		/**
		 * Permanently delete a single photo: remove its DB row + on-disk
		 * encrypted files. Used by the Trash screen's "Delete permanently"
		 * action. NOT undoable.
		 *
		 * @since v10 recycle bin
		 */
		suspend fun permanentlyDeletePhoto(photo: Photo) {
			// ─── Sprint 10+ / M10 Part 3: Per-album refcounted delete ──────────
			// If this photo is a SYMLINK (canonical_uuid != null), just delete the
			// DB row — the encrypted file belongs to the canonical and must not
			// be touched.
			//
			// If this photo is a CANONICAL (canonical_uuid == null), check if any
			// symlinks reference it. If yes, promote the oldest symlink to canonical
			// (set its canonical_uuid = null + rename the encrypted file from this
			// photo's UUID to the promoted symlink's UUID). Then delete this photo's
			// DB row + encrypted files.
			//
			// If no symlinks, delete normally (DB row + encrypted files).
			val isSymlink = photo.canonicalUuid != null

			if (isSymlink) {
				// Symlink: just delete the DB row + album links. NO file deletion.
				Timber.i(
					"permanentlyDeletePhoto: %s is a symlink (canonical=%s) — deleting DB row only",
					photo.uuid,
					photo.canonicalUuid,
				)
				try {
					photoDao.delete(photo)
				} catch (e: Exception) {
					Timber.w(e, "permanentlyDeletePhoto: photoDao.delete FAILED for symlink %s", photo.uuid)
				}
				try {
					albumDao.unlink(photo.uuid)
				} catch (e: Exception) {
					Timber.w(e, "permanentlyDeletePhoto: albumDao.unlink FAILED (non-fatal) for symlink %s", photo.uuid)
				}
				return
			}

			// Canonical: check for symlinks referencing this photo.
			val symlinks =
				try {
					photoDao.findSymlinksOf(photo.uuid)
				} catch (e: Exception) {
					Timber.w(e, "permanentlyDeletePhoto: findSymlinksOf failed — treating as no symlinks")
					emptyList()
				}

			if (symlinks.isNotEmpty()) {
				// Promote the oldest symlink to canonical.
				val promoted = symlinks.first()
				Timber.i(
					"permanentlyDeletePhoto: canonical %s has %d symlink(s) — promoting %s to canonical",
					photo.uuid,
					symlinks.size,
					promoted.uuid,
				)

				try {
					// Rename the encrypted files from old canonical UUID to promoted UUID.
					// Use the standalone function (not Photo.internalFileName) because
					// we're working with raw UUIDs, not Photo objects.
					val oldOrig =
						app.getFileStreamPath(
							onlasdan.gallery.model.database.entity
								.internalFileName(photo.uuid),
						)
					val newOrig =
						app.getFileStreamPath(
							onlasdan.gallery.model.database.entity
								.internalFileName(promoted.uuid),
						)
					if (oldOrig.exists()) oldOrig.renameTo(newOrig)

					val oldTn =
						app.getFileStreamPath(
							onlasdan.gallery.model.database.entity
								.internalThumbnailFileName(photo.uuid),
						)
					val newTn =
						app.getFileStreamPath(
							onlasdan.gallery.model.database.entity
								.internalThumbnailFileName(promoted.uuid),
						)
					if (oldTn.exists()) oldTn.renameTo(newTn)

					// Update the promoted row: clear canonical_uuid (it's now the owner).
					photoDao.updateCanonicalUuid(promoted.uuid, null)

					// Update all remaining symlinks to point to the promoted UUID.
					for (s in symlinks.drop(1)) {
						photoDao.updateCanonicalUuid(s.uuid, promoted.uuid)
					}
				} catch (e: Exception) {
					Timber.e(
						e,
						"permanentlyDeletePhoto: symlink promotion FAILED for %s — files may be orphaned",
						photo.uuid,
					)
				}
			} else {
				// No symlinks — delete the encrypted files normally.
				try {
					deleteInternalPhotoData(photo)
				} catch (e: Exception) {
					Timber.w(e, "permanentlyDeletePhoto: deleteInternalPhotoData FAILED (non-fatal) for %s", photo.uuid)
				}
			}

			try {
				photoDao.delete(photo)
			} catch (e: Exception) {
				Timber.w(e, "permanentlyDeletePhoto: photoDao.delete FAILED for %s", photo.uuid)
			}
			try {
				albumDao.unlink(photo.uuid)
			} catch (e: Exception) {
				Timber.w(e, "permanentlyDeletePhoto: albumDao.unlink FAILED (non-fatal) for %s", photo.uuid)
			}
		}

		/**
		 * Empty the entire trash: permanently delete every trashed photo
		 * (DB row + on-disk encrypted files). NOT undoable.
		 *
		 * @return the number of photos permanently deleted.
		 *
		 * @since v10 recycle bin
		 */
		suspend fun emptyTrash(): Int =
			withContext(Dispatchers.IO) {
				val trashed =
					try {
						photoDao.findAllTrash(currentVaultId())
					} catch (e: Exception) {
						Timber.w(e, "emptyTrash: findAllTrash FAILED")
						return@withContext 0
					}
				var count = 0
				for (photo in trashed) {
					permanentlyDeletePhoto(photo)
					count++
				}
				count
			}

		/**
		 * Auto-cleanup pass: permanently delete every trash entry whose
		 * `deleted_at` is older than 30 days. Called once on app start from
		 * [onlasdan.gallery.BaseApplication.onCreate]. Safe to call repeatedly —
		 * it's a no-op when the trash is empty or no entries have expired.
		 *
		 * @return the number of photos permanently deleted.
		 *
		 * @since v10 recycle bin
		 */
		suspend fun cleanupExpiredTrash(): Int =
			withContext(Dispatchers.IO) {
				val cutoff = System.currentTimeMillis() - TRASH_RETENTION_MS
				val expired =
					try {
						photoDao.findAllTrash(currentVaultId()).filter { it.deletedAt in 1 until cutoff }
					} catch (e: Exception) {
						Timber.w(e, "cleanupExpiredTrash: findAllTrash FAILED")
						return@withContext 0
					}
				if (expired.isEmpty()) return@withContext 0
				var count = 0
				for (photo in expired) {
					permanentlyDeletePhoto(photo)
					count++
				}
				Timber.i("cleanupExpiredTrash: permanently deleted %d expired trash entries (cutoff=%d)", count, cutoff)
				count
			}

		/**
		 * Observe all photos currently in the trash (deleted_at > 0). Drives the
		 * Trash screen's list.
		 *
		 * @since v10 recycle bin
		 */
		fun observeTrash() = photoDao.observeTrash(currentVaultId())

		/**
		 * Delete a photos bytes and thumbnail bytes on the filesystem.
		 *
		 * @param photo the photo to delete
		 *
		 * @return true, if photo and thumbnail could be deleted
		 */
		fun deleteInternalPhotoData(photo: Photo): Boolean =
			vaultFileStorage.deleteEncryptedFile(photo.internalFileName) &&
				vaultFileStorage.deleteEncryptedFile(photo.internalThumbnailFileName) &&
				(!photo.type.isVideo || vaultFileStorage.deleteEncryptedFile(photo.internalVideoPreviewFileName))

		// endregion

		// region EXPORT

		/**
		 * Export a photo to a specific directory.
		 *
		 * @param photo The Photo to be saved
		 */
		suspend fun exportPhoto(
			photo: Photo,
			target: Uri,
		): Boolean =
			withContext(Dispatchers.IO) {
				try {
					val inputStream =
						vaultFileStorage.openEncryptedInput(photo.internalFileName)
					inputStream ?: return@withContext false

					val outputStream = createExternalOutputStream(photo, target)
					outputStream ?: return@withContext false

					val wrote =
						suspendCancellableCoroutine { continuation ->
							val wrote = inputStream.copyTo(outputStream)
							continuation.resume(wrote)
						}

					outputStream.lazyClose()

					var deleted = true
					if (config.deleteExportedFiles) {
						deleted = safeDeletePhoto(photo)
					}

					wrote != -1L && deleted
				} catch (e: IOException) {
					Timber.d("Error exporting file: ${photo.fileName} $e")
					false
				}
			}

		private fun createExternalOutputStream(
			photo: Photo,
			uri: Uri,
		): OutputStream? {
			val fileName = photo.fileName
			val mimeType = photo.type.mimeType

			return io.openFileOutput(
				app.contentResolver,
				fileName,
				mimeType,
				uri,
			)
		}

		// endregion
		// endregion

		// region EXTERNAL VIEW (file-upload feature)

		/**
		 * Decrypt the photo's stored original to a cache file and hand it off to
		 * the system via [Intent.ACTION_VIEW].
		 *
		 * Used for non-photo file types ([PhotoType.isFile] == true) — DOCUMENT,
		 * ARCHIVE, AUDIO — that the in-app image viewer can't render. The user
		 * taps the tile, the gallery calls this function, and the system shows a
		 * chooser for whatever app can handle the mime type (a PDF viewer, a
		 * file manager for archives, a music player for audio, etc.).
		 *
		 * The decrypted file lives under `cacheDir/extview/` and is exposed via
		 * the app's [FileProvider] (authority `<package>.fileprovider`). The
		 * cache file is overwritten on each call — no long-term storage of
		 * plaintext on disk. The system viewer app receives `FLAG_GRANT_READ_URI_PERMISSION`
		 * so it can read the content URI even though the FileProvider itself is
		 * not exported.
		 *
		 * For AUDIO files, the stored [PhotoType.mimeType] is the wildcard
		 * "audio/any" (because we accept any audio mime at import time and
		 * normalize to a single enum entry). The actual mime type sent to
		 * `ACTION_VIEW` is derived from the file extension on the stored
		 * filename (`.mp3`, `.m4a`, `.wav`, etc.) so the system picks a viewer
		 * that actually understands that specific audio format.
		 *
		 * This is a fire-and-forget call from the gallery's perspective — there's
		 * no result callback. The system either shows a chooser, launches the
		 * default handler, or shows a "no app can handle this" toast. Errors
		 * during decryption (e.g. vault locked) are caught and logged.
		 *
		 * @return `true` if the ACTION_VIEW intent was successfully launched
		 *   (decryption succeeded and at least one handler exists); `false`
		 *   otherwise. The caller surfaces a toast on `false`.
		 *
		 * @since file-upload feature — external viewer for DOCUMENT/ARCHIVE/AUDIO
		 */
		suspend fun openFileExternally(photo: Photo): Boolean =
			withContext(Dispatchers.IO) {
				try {
					// Resolve the decrypted file: copy the photo's encrypted original
					// through the decrypt stream into a fresh plaintext cache file.
					val extViewDir = File(app.cacheDir, "extview").apply { mkdirs() }

					// ─── QC fix: clean up stale plaintext cache files ─────────────────
					// Before creating the new plaintext cache file, delete any existing
					// files in cacheDir/extview/ left over from a previous openFileExternally
					// call. This guarantees at most ONE plaintext file is on disk at any
					// given time — these are decrypted originals of DOCUMENT/ARCHIVE/AUDIO
					// photos exposed to external viewer apps via FileProvider, so we want
					// to minimize their lifetime and count on disk.
					//
					// TODO(future enhancement): a full cleanup of this directory should
					//   also run on Activity.onPause / onStop so the plaintext file is
					//   wiped the moment the user leaves the gallery (the external viewer
					//   app has already finished reading the file by then). For now,
					//   this single-file housekeeping on each call is a defense-in-depth
					//   measure — the OS will eventually reclaim cacheDir space under
					//   memory pressure, but we don't want to rely on that for sensitive
					//   plaintext.
					runCatching {
						extViewDir.listFiles()?.forEach { stale ->
							if (stale.isFile) stale.delete()
						}
					}

					val outFile = File(extViewDir, "${photo.uuid}.${photo.type.fileExtension}")

					val cipherInput =
						vaultFileStorage.openEncryptedInput(photo.internalFileName)
							?: run {
								Timber.e(
									"openFileExternally: failed to open encrypted input for %s",
									photo.uuid,
								)
								return@withContext false
							}

					cipherInput.use { input ->
						outFile.outputStream().use { output ->
							input.copyTo(output)
						}
					}

					if (!outFile.exists() || outFile.length() == 0L) {
						Timber.e("openFileExternally: decrypted file is missing or empty for %s", photo.uuid)
						return@withContext false
					}

					// Build a content:// URI via the FileProvider. Android 7+ rejects
					// file:// URIs for ACTION_VIEW with FileUriExposedException.
					val authority = "${app.packageName}.fileprovider"
					val contentUri = FileProvider.getUriForFile(app, authority, outFile)

					// Resolve the actual mime type to send to ACTION_VIEW.
					// - DOCUMENT/ARCHIVE have a real, fixed mime type on the enum.
					// - AUDIO was normalized to an audio wildcard at import time;
					//   derive the specific subtype from the original filename's
					//   extension so the system viewer knows whether it's an mp3, m4a, etc.
					val mimeType = mimeTypeForExternalView(photo)

					val viewIntent =
						Intent(Intent.ACTION_VIEW).apply {
							setDataAndType(contentUri, mimeType)
							addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
							addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
						}

					// Resolve the activity before calling startActivity — if no app
					// can handle the mime type, fall back to a "send" chooser so the
					// user at least sees the system's "no app" UX instead of a silent
					// failure. resolveActivity() requires <queries> in the manifest
					// on API 30+ for implicit intents; the fallback handles that.
					val launchIntent =
						if (viewIntent.resolveActivity(app.packageManager) != null) {
							viewIntent
						} else {
							Intent.createChooser(viewIntent, photo.fileName).apply {
								addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
							}
						}

					app.startActivity(launchIntent)
					Timber.i(
						"openFileExternally: launched ACTION_VIEW for %s (mime=%s, %d bytes)",
						photo.uuid,
						mimeType,
						outFile.length(),
					)
					true
				} catch (e: Exception) {
					Timber.e(e, "openFileExternally: FAILED for %s", photo.uuid)
					false
				}
			}

		/**
		 * Compute the mime type to send to [Intent.ACTION_VIEW] for a file-type
		 * photo. DOCUMENT and ARCHIVE have fixed mime types; AUDIO was normalized
		 * to an audio wildcard at import time, so we derive the actual subtype
		 * from the original filename extension.
		 */
		private fun mimeTypeForExternalView(photo: Photo): String {
			if (photo.type != PhotoType.AUDIO) {
				return photo.type.mimeType
			}
			// Derive audio subtype from filename extension. Fall back to a
			// generic audio wildcard if the extension is unknown — the system
			// will usually still show a chooser with audio-capable apps.
			val ext = photo.fileName.substringAfterLast('.', "").lowercase()
			return when (ext) {
				"mp3" -> "audio/mpeg"
				"m4a", "aac" -> "audio/mp4"
				"wav" -> "audio/wav"
				"ogg" -> "audio/ogg"
				"flac" -> "audio/flac"
				"opus" -> "audio/ogg"
				"mid", "midi" -> "audio/midi"
				// Avoid writing "audio/*" as a literal — the `/*` sequence
				// inside a KDoc comment opens a nested block comment in Kotlin
				// (unlike Java). Concatenate at runtime so the source file
				// never contains the `/*` token outside a string literal.
				else -> "audio/" + "*"
			}
		}

		// endregion

		// region ZIP BACKUP (Item 1 — vault ZIP export / import)

		/**
		 * Export every live (non-trashed) photo to a plain ZIP archive at
		 * [targetUri]. Each photo's decrypted plaintext bytes are written to a
		 * ZIP entry named after the photo's original filename; a `manifest.json`
		 * entry at the ZIP root carries the per-photo metadata (uuid, fileName,
		 * type, size, albumPath, contentHash) so an [importFromZip] round-trip
		 * can recreate the DB rows.
		 *
		 * The ZIP itself is NOT encrypted — the user chose where to save it
		 * (e.g. a SAF-selected location) and is responsible for securing the
		 * file. The decrypted plaintext bytes exist only transiently inside
		 * the ZipOutputStream's buffer; no plaintext is written to the app's
		 * own storage.
		 *
		 * @return `true` if the ZIP was written successfully (zero or more
		 *   photos), `false` on a fatal error.
		 *
		 * @since Item 1 — ZIP backup export
		 */
		suspend fun exportToZip(targetUri: Uri): Boolean =
			withContext(Dispatchers.IO) {
				var zipOut: java.util.zip.ZipOutputStream? = null
				try {
					val photos = photoDao.findAllPhotosByImportDateDesc().filter { it.deletedAt == 0L }
					val manifest =
						ZipManifest(
							version = ZIP_MANIFEST_VERSION,
							createdAt = System.currentTimeMillis(),
							photos =
								photos.map {
									ZipManifestEntry(
										uuid = it.uuid,
										fileName = it.fileName,
										type = it.type.name,
										size = it.size,
										albumPath = it.albumPath,
										contentHash = it.contentHash,
									)
								},
						)

					val out =
						app.contentResolver.openOutputStream(targetUri)
							?: run {
								Timber.e("exportToZip: could not open output stream for %s", targetUri)
								return@withContext false
							}
					zipOut = java.util.zip.ZipOutputStream(java.io.BufferedOutputStream(out))

					// Write manifest.json first so an importer can pre-allocate
					// progress UI before any photo bytes are read.
					val manifestJson = gson.toJson(manifest).toByteArray(Charsets.UTF_8)
					zipOut.putNextEntry(java.util.zip.ZipEntry(ZIP_MANIFEST_ENTRY_NAME))
					zipOut.write(manifestJson)
					zipOut.closeEntry()

					// Write each photo's decrypted plaintext as a ZIP entry.
					// Entries are named `<uuid>/<originalFileName>` to guarantee
					// uniqueness (two photos can share the same fileName if they
					// came from different sources) and to keep importable items
					// grouped in a per-photo subfolder when the user unzips
					// manually.
					for (photo in photos) {
						val cipherIn =
							try {
								vaultFileStorage.openEncryptedInput(photo.internalFileName)
							} catch (e: Exception) {
								Timber.w(
									e,
									"exportToZip: skip %s (cannot open encrypted input)",
									photo.uuid,
								)
								null
							}
						if (cipherIn == null) continue

						val entryName = "${photo.uuid}/${photo.fileName}"
						zipOut.putNextEntry(java.util.zip.ZipEntry(entryName))
						try {
							cipherIn.use { it.copyTo(zipOut) }
						} catch (e: Exception) {
							Timber.w(e, "exportToZip: partial write for %s", photo.uuid)
						}
						zipOut.closeEntry()
					}

					zipOut.finish()
					zipOut.flush()
					Timber.i("exportToZip: wrote %d photos + manifest to %s", photos.size, targetUri)
					true
				} catch (e: Exception) {
					Timber.e(e, "exportToZip: FAILED")
					false
				} finally {
					runCatching { zipOut?.close() }
				}
			}

		/**
		 * Import photos from a ZIP archive previously produced by
		 * [exportToZip]. Reads `manifest.json` to recover per-photo metadata,
		 * then for each entry: reads the plaintext bytes from the ZIP,
		 * re-encrypts them with the current VMK, writes the new `.crypt` file
		 * to internal storage, and creates a fresh Photo DB row with a NEW
		 * UUID (so re-importing the same ZIP multiple times creates distinct
		 * photos, not duplicates of the originals).
		 *
		 * Thumbnails are NOT generated — the importer creates the DB row +
		 * encrypted original only. The thumbnail pack restore pass
		 * ([onlasdan.gallery.sync.rclone.RepoManager.restoreThumbnailsFromPacks])
		 * will pick up the new UUID on the next sync and fetch its thumbnail
		 * from the cloud (or the gallery will render a placeholder until then).
		 *
		 * Photos whose fileName collides with an existing photo (same
		 * fileName + same content_hash) are skipped to avoid trivial
		 * duplicates.
		 *
		 * @return the number of photos successfully imported.
		 *
		 * @since Item 1 — ZIP backup import
		 */
		suspend fun importFromZip(sourceUri: Uri): Int =
			withContext(Dispatchers.IO) {
				var zipIn: java.util.zip.ZipInputStream? = null
				var imported = 0
				try {
					val inStream =
						app.contentResolver.openInputStream(sourceUri)
							?: run {
								Timber.e("importFromZip: could not open input stream for %s", sourceUri)
								return@withContext 0
							}
					zipIn = java.util.zip.ZipInputStream(java.io.BufferedInputStream(inStream))

					// First pass: find manifest.json and parse it.
					var manifest: ZipManifest? = null
					val entryBytesByUuid = mutableMapOf<String, ByteArray>()
					var entry = zipIn.nextEntry
					while (entry != null) {
						if (entry.name == ZIP_MANIFEST_ENTRY_NAME) {
							val bytes = zipIn.readBytes()
							manifest =
								try {
									gson.fromJson(
										String(bytes, Charsets.UTF_8),
										ZipManifest::class.java,
									)
								} catch (e: Exception) {
									Timber.w(e, "importFromZip: manifest.json parse FAILED")
									null
								}
						} else if (!entry.isDirectory) {
							// Top-level folder name = original uuid
							val topFolder = entry.name.substringBefore('/', "")
							if (topFolder.isNotBlank()) {
								// Defer reading bytes — we'll only read entries that
								// appear in the manifest. Read everything though, since
								// the ZIP is small relative to gallery size and we
								// need to enumerate entries sequentially anyway.
								val bytes = zipIn.readBytes()
								entryBytesByUuid[topFolder] = bytes
							}
						}
						zipIn.closeEntry()
						entry = zipIn.nextEntry
					}

					val manifestResolved =
						manifest ?: run {
							Timber.w("importFromZip: no manifest.json in ZIP — aborting")
							return@withContext 0
						}

					for (meta in manifestResolved.photos) {
						val bytes = entryBytesByUuid[meta.uuid] ?: continue
						// Skip duplicates: same fileName + same content_hash.
						if (!meta.contentHash.isNullOrBlank()) {
							val existing =
								try {
									photoDao.findByContentHash(
										meta.contentHash,
										excludeUuid = meta.uuid,
										vaultId = currentVaultId(),
									)
								} catch (e: Exception) {
									null
								}
							if (existing != null) {
								Timber.i(
									"importFromZip: skip duplicate %s (hash=%s)",
									meta.fileName,
									meta.contentHash,
								)
								continue
							}
						}

						val newUuid = UUID.randomUUID().toString()
						val type =
							runCatching { PhotoType.valueOf(meta.type).takeIf { it != PhotoType.UNDEFINED } }
								.getOrNull() ?: PhotoType.JPEG

						// Sprint 6 / M4 — extract EXIF from the in-memory bytes
						// (ZIP import doesn't have a URI to stream from).
						val exif =
							if (!type.isVideo && !type.isFile) {
								onlasdan.gallery.model.io
									.extractExifMetadata(bytes)
							} else {
								onlasdan.gallery.model.io
									.ExifMetadata()
							}

						val photo =
							Photo(
								fileName = meta.fileName,
								importedAt = System.currentTimeMillis(),
								lastModified = null,
								type = type,
								size = bytes.size.toLong(),
								uuid = newUuid,
								relativePath = meta.albumPath ?: meta.fileName,
								albumPath = meta.albumPath,
								contentHash = meta.contentHash,
								// Sprint 2 / M7 — tag with current vault_id
								vaultId = runCatching { currentVaultId() }.getOrNull(),
								// Sprint 6 / M4 — EXIF from in-memory bytes
								exifDateTaken = exif.dateTaken,
								exifGpsLat = exif.gpsLat,
								exifGpsLon = exif.gpsLon,
								exifCamera = exif.camera,
							)

						// Write the plaintext bytes through the encrypt stream.
						val written =
							try {
								val encOut = vaultFileStorage.openEncryptedOutput(photo.internalFileName)
								if (encOut == null) {
									Timber.w(
										"importFromZip: openEncryptedOutput FAILED for %s",
										newUuid,
									)
									false
								} else {
									encOut.use { it.write(bytes) }
									true
								}
							} catch (e: Exception) {
								Timber.w(e, "importFromZip: encrypt+write FAILED for %s", newUuid)
								false
							}
						if (!written) continue

						try {
							photoDao.insert(photo)
							imported++
						} catch (e: Exception) {
							Timber.w(e, "importFromZip: insert FAILED for %s", newUuid)
							runCatching { vaultFileStorage.deleteEncryptedFile(photo.internalFileName) }
						}
					}

					Timber.i("importFromZip: imported %d photos from %s", imported, sourceUri)
					imported
				} catch (e: Exception) {
					Timber.e(e, "importFromZip: FAILED")
					imported
				} finally {
					runCatching { zipIn?.close() }
				}
			}

		// endregion

		// region STORAGE ANALYTICS (Item 4)

		/**
		 * Snapshot of vault storage usage. Counts live (non-trashed) photos
		 * only — trash entries' files are still on disk but are about to be
		 * cleaned up by the 30-day auto-cleanup, so they're not part of the
		 * "real" storage footprint we surface to the user.
		 *
		 * @since Item 4 — storage analytics
		 */
		data class StorageStats(
			val localOriginalsBytes: Long,
			val localThumbnailsBytes: Long,
			val localOriginalsCount: Int,
			val localThumbnailsCount: Int,
			val photoCount: Int,
			val uploadedCount: Int,
			val pendingCount: Int,
			val trashCount: Int,
		)

		/**
		 * Compute the current [StorageStats] by scanning the app's `filesDir`
		 * for `.crypt` and `.crypt.tn` files + querying the DB for counts by
		 * sync state. Best-effort: file system errors are logged and treated
		 * as zero for that counter.
		 *
		 * @since Item 4 — storage analytics
		 */
		suspend fun getStorageStats(): StorageStats =
			withContext(Dispatchers.IO) {
				var origBytes = 0L
				var origCount = 0
				var tnBytes = 0L
				var tnCount = 0
				try {
					val files =
						app.filesDir
							.listFiles()
							?.toList()
							.orEmpty()
					for (f in files) {
						if (!f.isFile) continue
						val len = f.length()
						when {
							f.name.endsWith(".crypt.tn") -> {
								tnBytes += len
								tnCount++
							}
							f.name.endsWith(".crypt.vp") -> {
								// Video preview — group with originals.
								origBytes += len
								origCount++
							}
							f.name.endsWith(".crypt") -> {
								origBytes += len
								origCount++
							}
						}
					}
				} catch (e: Exception) {
					Timber.w(e, "getStorageStats: filesDir scan FAILED")
				}

				val all =
					try {
						photoDao.findAllPhotosByImportDateDesc()
					} catch (e: Exception) {
						emptyList()
					}
				val live = all.filter { it.deletedAt == 0L }
				val uploaded = live.count { it.syncState == onlasdan.gallery.sync.domain.SyncState.UPLOADED }
				val pending =
					live.count {
						it.syncState == onlasdan.gallery.sync.domain.SyncState.UPLOAD_PENDING ||
							it.syncState == onlasdan.gallery.sync.domain.SyncState.LOCAL_ONLY
					}
				val trash = all.count { it.deletedAt > 0L }

				StorageStats(
					localOriginalsBytes = origBytes,
					localThumbnailsBytes = tnBytes,
					localOriginalsCount = origCount,
					localThumbnailsCount = tnCount,
					photoCount = live.size,
					uploadedCount = uploaded,
					pendingCount = pending,
					trashCount = trash,
				)
			}

		// endregion

		companion object {
			/**
			 * How long a photo stays in the trash before being permanently
			 * deleted by [cleanupExpiredTrash]. 30 days matches the user-facing
			 * string in `settings_trash_summary` — keep them in sync.
			 *
			 * @since v10 recycle bin
			 */
			const val TRASH_RETENTION_MS = 30L * 24 * 60 * 60 * 1000

			/** manifest.json entry name inside export ZIPs. @since Item 1 ZIP backup */
			const val ZIP_MANIFEST_ENTRY_NAME = "manifest.json"

			/** ZipManifest format version. Bump on incompatible changes. @since Item 1 */
			const val ZIP_MANIFEST_VERSION = 1
		}
	}

/**
 * manifest.json model for the ZIP backup format (Item 1).
 *
 * @since Item 1 — ZIP backup
 */

data class ZipManifest(
	@com.google.gson.annotations.Expose val version: Int,
	@com.google.gson.annotations.Expose val createdAt: Long,
	@com.google.gson.annotations.Expose val photos: List<ZipManifestEntry>,
)

/**
 * One entry in [ZipManifest.photos] — the metadata needed to reconstruct
 * a Photo DB row on import (the photo's plaintext bytes come from the
 * corresponding ZIP entry).
 *
 * @since Item 1 — ZIP backup
 */
data class ZipManifestEntry(
	@com.google.gson.annotations.Expose val uuid: String,
	@com.google.gson.annotations.Expose val fileName: String,
	@com.google.gson.annotations.Expose val type: String,
	@com.google.gson.annotations.Expose val size: Long,
	@com.google.gson.annotations.Expose val albumPath: String?,
	@com.google.gson.annotations.Expose val contentHash: String?,
)
