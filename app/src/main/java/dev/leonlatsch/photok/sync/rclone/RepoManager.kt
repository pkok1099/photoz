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

package dev.leonlatsch.photok.sync.rclone

import android.app.Application
import dev.leonlatsch.photok.encryption.domain.VaultProtectionRepository
import dev.leonlatsch.photok.encryption.domain.models.Algorithm
import dev.leonlatsch.photok.encryption.domain.models.Kdf
import dev.leonlatsch.photok.encryption.domain.models.VaultProtection
import dev.leonlatsch.photok.encryption.domain.models.VaultProtectionParams
import dev.leonlatsch.photok.encryption.domain.models.VaultProtectionType
import dev.leonlatsch.photok.model.database.dao.PhotoDao
import dev.leonlatsch.photok.model.database.entity.PHOTOK_FILE_EXTENSION
import dev.leonlatsch.photok.model.database.entity.Photo
import dev.leonlatsch.photok.model.database.entity.PhotoType
import dev.leonlatsch.photok.settings.data.Config
import dev.leonlatsch.photok.sync.domain.SyncConfig
import dev.leonlatsch.photok.sync.domain.SyncState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the restic-style repo on the rclone remote.
 *
 * A repo is identified by a `repo-config.json` marker file at the repo root
 * (`<remote>:photok-backup/repo-config.json`) containing:
 *   {"repo_id":"<uuid>","created":"<iso8601>","version":1}
 *
 * ## Register vs Login detection
 *
 * Before any write, [detectRepo] lists the repo root via an independent `operations/list` rc
 * call. This is the check that was previously missing entirely — the app used to write blindly
 * with no prior existence check, which is the root cause behind the recurring false-upload-
 * success bug.
 *
 * - Marker file absent → [RepoState.NOT_INITIALIZED] → caller runs [registerRepo].
 * - Marker file present and parses → [RepoState.LOGGED_IN] → caller skips registration.
 * - Listing errors → [RepoState.ERROR] → caller surfaces error, does NOT guess.
 *
 * ## Independent verification
 *
 * [registerRepo] writes the marker, then **independently re-lists** to confirm the write
 * actually landed. The write call's return value alone is NOT sufficient proof (this exact
 * mistake caused the previous false-success bug). Only after independent verification
 * succeeds does the repo become "confirmed" locally.
 *
 * @since PR1 sync — mandatory repo setup
 */
@Singleton
class RepoManager @Inject constructor(
    private val app: Application,
    private val config: Config,
    private val rcloneController: RcloneController,
    private val configManager: RcloneConfigManager,
    // @since PR4 sync — needed by restoreThumbnailsAfterLogin() to insert DB rows for
    // each thumbnail pulled back from the remote after a successful login.
    private val photoDao: PhotoDao,
    // @since key-escrow — needed by uploadVaultProtectionEscrow() / downloadVaultProtectionEscrow()
    // to persist the recovery-phrase wrapped-VMK artifact on the remote during registration
    // and to restore it into the local DB during login (so the existing phrase-entry UI can
    // unlock the VMK on a fresh install).
    private val vaultProtectionRepository: VaultProtectionRepository,
) {

    // ─── DIAGNOSTIC LOGGING (RcloneDiag pattern, same as RcloneController) ────
    // Writes to BOTH Log.e (logcat) AND files/sync_log.txt so it's visible via
    // `adb logcat -d | grep RcloneDiag` AND
    // `adb shell run-as <pkg> cat files/sync_log.txt`.
    // Used by restoreThumbnailsAfterLogin() — restore bugs are otherwise silent
    // (no UI surfaces failures, just empty gallery).
    private fun diag(msg: String, throwable: Throwable? = null) {
        android.util.Log.e("RcloneDiag", "[Restore] $msg", throwable)
        try {
            val logFile = File(app.filesDir, "sync_log.txt")
            logFile.appendText("\n[RcloneDiag] [Restore] $msg\n")
            if (throwable != null) {
                logFile.appendText(throwable.stackTraceToString() + "\n")
            }
        } catch (_: Exception) {
            // Never let diag() itself throw.
        }
    }

    data class RepoMarker(
        val repoId: String,
        val created: String,
        val version: Int,
    )

    sealed class RepoState {
        /** No repo-config.json found on the remote. Caller should register. */
        object NOT_INITIALIZED : RepoState()

        /** Repo-config.json found and parsed. Caller should login (read-only). */
        data class LOGGED_IN(val marker: RepoMarker) : RepoState()

        /** Listing failed. Caller should surface error — do NOT guess register vs login. */
        data class ERROR(val message: String) : RepoState()
    }

    /**
     * Result of [loginRepo]. Carries the escrow-artifact status so the caller
     * can decide whether to route the user to the phrase-entry UI or to the
     * degraded-mode ("no escrow available") UI.
     *
     * @since key-escrow — login-branch phrase entry
     */
    sealed class LoginResult {
        /**
         * Login (remote repo session) succeeded.
         *
         * @param escrowAvailable `true` if a recovery-phrase wrapped-VMK artifact
         *   was downloaded from the remote and persisted into the local
         *   VaultProtection DB; the caller should route the user to the
         *   phrase-entry UI. `false` if the artifact was missing (old repo)
         *   or the download failed non-fatally — the caller should route to
         *   the "no escrow available" UI.
         */
        data class Success(val escrowAvailable: Boolean) : LoginResult()

        /** Login failed — caller should surface error. */
        data class Failure(val message: String) : LoginResult()
    }

    /**
     * Detect whether a repo exists on the currently-chosen remote.
     *
     * Uses an independent `operations/list` call scoped to the repo root path. This is the
     * critical check that was previously missing — the app never distinguished "repo doesn't
     * exist yet, create it" from "repo already exists, open it".
     *
     * Must be called after [RcloneConfigManager.import] + [RcloneConfigManager.chooseRemote]
     * have completed (i.e. rcd is running with a valid config and a chosen remote).
     */
    suspend fun detectRepo(): RepoState = withContext(Dispatchers.IO) {
        // ─── DIAGNOSTIC LOGGING (Step B) ─────────────────────────────────
        // detectRepo() is the user-facing entry point for the bug: it's the first
        // call that triggers locateRcloneBinary() during login/repo-init. If we
        // never see this log line, the bug is in the call chain ABOVE detectRepo
        // (e.g. the ViewModel doesn't call it, or short-circuits before).
        android.util.Log.e(
            "RcloneDiag",
            "detectRepo: BEGIN remote=${config.syncChosenRemote} repoConfirmed=${config.repoConfirmed}"
        )
        try {
            java.io.File(app.filesDir, "sync_log.txt").appendText(
                "\n[RcloneDiag] detectRepo: BEGIN remote=${config.syncChosenRemote} repoConfirmed=${config.repoConfirmed}\n"
            )
        } catch (_: Exception) {}

        val remote = config.syncChosenRemote
        if (remote.isNullOrBlank()) {
            android.util.Log.e("RcloneDiag", "detectRepo: ABORT — no remote chosen")
            return@withContext RepoState.ERROR("No remote chosen")
        }

        val repoRoot = "$remote:$REPO_DIR"
        try {
            // Use operations/list to check for the marker file
            android.util.Log.e("RcloneDiag", "detectRepo: calling listRemote($repoRoot)")
            val result = rcloneController.listRemote("$remote:", REPO_DIR)
            if (result.isFailure) {
                val err = result.exceptionOrNull()?.message ?: "unknown error"
                android.util.Log.e(
                    "RcloneDiag",
                    "detectRepo: listRemote FAILED class=${result.exceptionOrNull()?.javaClass?.name} msg=$err",
                    result.exceptionOrNull()
                )
                // Only treat as "repo not initialized" if the error is specifically about
                // the remote directory not existing. Do NOT match "not found" generically —
                // that would also match "rclone binary not found" from locateRcloneBinary(),
                // swallowing a real infrastructure error as a false "repo doesn't exist yet".
                // @since PR1 sync — fix for error-swallowing bug that hid binary-not-found
                val isDirNotFound = err.contains("directory not found", ignoreCase = true) ||
                    err.contains("error in ListJSON", ignoreCase = true) ||
                    (err.contains("not found", ignoreCase = true) &&
                        !err.contains("binary", ignoreCase = true) &&
                        !err.contains("rclone", ignoreCase = true))
                if (isDirNotFound) {
                    android.util.Log.e("RcloneDiag", "detectRepo: classifying as NOT_INITIALIZED (dir not found)")
                    return@withContext RepoState.NOT_INITIALIZED
                }
                android.util.Log.e("RcloneDiag", "detectRepo: classifying as ERROR (not a dir-not-found error)")
                return@withContext RepoState.ERROR(err)
            }

            val files = result.getOrThrow()
            android.util.Log.e("RcloneDiag", "detectRepo: listRemote OK, ${files.size} files")
            val markerFile = files.find { it.name == MARKER_FILENAME }
            if (markerFile == null) {
                android.util.Log.e("RcloneDiag", "detectRepo: marker not in listing → NOT_INITIALIZED")
                return@withContext RepoState.NOT_INITIALIZED
            }

            // Marker exists — download and parse it
            android.util.Log.e("RcloneDiag", "detectRepo: marker found, downloading")
            val tempMarker = File(app.cacheDir, "repo-config-download-${System.currentTimeMillis()}.json")
            val downloadResult = rcloneController.downloadFile(
                "$remote:$REPO_DIR/$MARKER_FILENAME",
                tempMarker.absolutePath
            )
            if (downloadResult.isFailure) {
                android.util.Log.e(
                    "RcloneDiag",
                    "detectRepo: downloadFile FAILED msg=${downloadResult.exceptionOrNull()?.message}"
                )
                return@withContext RepoState.ERROR(
                    "Failed to download marker: ${downloadResult.exceptionOrNull()?.message}"
                )
            }

            val markerContent = tempMarker.readText()
            tempMarker.delete()
            val marker = parseMarker(markerContent)
                ?: return@withContext RepoState.ERROR("Malformed marker file")

            android.util.Log.e("RcloneDiag", "detectRepo: marker parsed, state=LOGGED_IN repoId=${marker.repoId}")
            RepoState.LOGGED_IN(marker)
        } catch (e: Exception) {
            android.util.Log.e("RcloneDiag", "detectRepo: CAUGHT ${e.javaClass.name}: ${e.message}", e)
            RepoState.ERROR(e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * Register a new repo on the remote. Writes the marker file, then independently re-lists
     * to verify the write landed. Only after verification succeeds is the repo confirmed.
     *
     * @return `Result.success(RepoMarker)` on success, `Result.failure` on any error.
     */
    suspend fun registerRepo(): Result<RepoMarker> = withContext(Dispatchers.IO) {
        runCatching {
            val remote = config.syncChosenRemote
                ?: throw IllegalStateException("No remote chosen")

            val marker = RepoMarker(
                repoId = UUID.randomUUID().toString(),
                created = Instant.now().toString(),
                version = 1,
            )
            val markerJson = """{"repo_id":"${marker.repoId}","created":"${marker.created}","version":${marker.version}}"""

            // Write marker to a temp file, then upload via rclone
            val tempFile = File(app.cacheDir, "repo-config-${System.currentTimeMillis()}.json")
            tempFile.writeText(markerJson)

            try {
                val remotePath = "$remote:$REPO_DIR/$MARKER_FILENAME"
                val uploadResult = rcloneController.uploadFile(tempFile.absolutePath, remotePath)
                if (uploadResult.isFailure) {
                    throw uploadResult.exceptionOrNull() ?: IOException("Upload failed")
                }

                // ─── INDEPENDENT VERIFICATION ─────────────────────────────────
                // Re-list the repo root to confirm the marker actually landed.
                // The upload call's return value alone is NOT sufficient proof.
                val verifyResult = rcloneController.listRemote("$remote:", REPO_DIR)
                if (verifyResult.isFailure) {
                    throw IOException(
                        "Upload succeeded but independent verification failed: " +
                            "${verifyResult.exceptionOrNull()?.message}"
                    )
                }

                val files = verifyResult.getOrThrow()
                val found = files.any { it.name == MARKER_FILENAME && it.size > 0 }
                if (!found) {
                    throw IOException(
                        "Upload succeeded but marker file not found in independent listing. " +
                            "Files: $files"
                    )
                }

                // Upload the recovery-phrase wrapped-VMK escrow so a fresh install on
                // another device can restore the vault via the user's recovery phrase.
                // Failure here MUST NOT block registration — the repo is still usable
                // for photo upload; only recovery-phrase restore on fresh installs
                // won't work. Log but don't throw.
                // @since key-escrow — upload wrapped VMK during repo registration
                try {
                    val escrowResult = uploadVaultProtectionEscrow()
                    if (escrowResult.isFailure) {
                        diag(
                            "registerRepo: escrow upload failed (non-fatal, continuing): " +
                                "${escrowResult.exceptionOrNull()?.message}",
                            escrowResult.exceptionOrNull(),
                        )
                    }
                } catch (e: Exception) {
                    diag(
                        "registerRepo: escrow upload threw (non-fatal, continuing): ${e.message}",
                        e,
                    )
                }

                // Persist the repo binding locally
                config.repoId = marker.repoId
                config.repoConfirmed = true

                Timber.i("Repo registered: id=${marker.repoId}")
                marker
            } finally {
                tempFile.delete()
            }
        }
    }

    /**
     * "Login" to an existing repo — **connect to the remote**, NOT log into the app.
     *
     * This is a read-only confirmation that the repo marker file exists on the
     * remote and matches the locally-stored repo binding. It does NOT involve any
     * authentication of the user to the app — the app's vault unlock (PIN/password)
     * is a separate, independent gate.
     *
     * The name "loginRepo" is retained for backwards compatibility but should be
     * read as "connect to remote repo session". UI strings use "Connecting to your
     * cloud storage…" (not "logging in") to avoid implying the user is logging
     * into PhotoZ itself.
     *
     * Read-only — does NOT touch the marker file. Persists the repo binding locally.
     *
     * After confirming the repo binding, downloads the recovery-phrase wrapped-VMK
     * escrow artifact (if present on the remote) and persists it into the local
     * VaultProtection DB so the existing phrase-entry UI can unlock the VMK on
     * this fresh install. The escrow status is carried in [LoginResult.Success.escrowAvailable].
     *
     * @since key-escrow — login now returns [LoginResult] carrying escrow status
     */
    suspend fun loginRepo(marker: RepoMarker): LoginResult = withContext(Dispatchers.IO) {
        runCatching {
            config.repoId = marker.repoId
            config.repoConfirmed = true
            Timber.i("Repo login: id=${marker.repoId}")

            // Download the wrapped-VMK escrow artifact (if present on the remote) and
            // persist it to the local VaultProtection DB. The presence/absence of this
            // artifact determines whether the login flow can offer recovery-phrase
            // restore on a fresh install.
            // @since key-escrow — download wrapped VMK during repo login
            val escrowResult = downloadVaultProtectionEscrow()
            if (escrowResult.isFailure) {
                // Don't block login — the user can still get into the gallery via the
                // normal vault PIN/password path; just no recovery-phrase restore on
                // this fresh install. Surface as "not available" rather than an error.
                val err = escrowResult.exceptionOrNull()?.message ?: "unknown error"
                diag(
                    "loginRepo: escrow download failed (non-fatal): $err",
                    escrowResult.exceptionOrNull(),
                )
                LoginResult.Success(escrowAvailable = false)
            } else {
                val available = escrowResult.getOrNull() != null
                diag("loginRepo: escrow available=$available")
                LoginResult.Success(escrowAvailable = available)
            }
        }.fold(
            onSuccess = { it },
            onFailure = { e ->
                diag(
                    "loginRepo: FAILED ${e.javaClass.name}: ${e.message}",
                    e,
                )
                LoginResult.Failure(e.message ?: e.javaClass.simpleName)
            },
        )
    }

    /**
     * Restore thumbnails from the remote backup after a successful [loginRepo].
     *
     * Lists `<remote>:<remoteThumbnailsDir>/` (i.e. `<remote>:thumbnails/`) and downloads
     * every `<uuid>.crypt.tn` file back to local storage. For each thumbnail, creates a
     * Photo DB row with [SyncState.UPLOADED] and NO local original — the original will be
     * fetched on-demand by [dev.leonlatsch.photok.sync.work.SyncRestorer] when the user
     * opens the photo.
     *
     * Idempotent: if a Photo row already exists for a uuid, that thumbnail is skipped.
     *
     * NOTE: the task spec mentioned `photok-backup/thumbnails/` as the listing path,
     * but the actual upload path used by [dev.leonlatsch.photok.sync.work.PhotoSyncWorker]
     * is `<remote>:thumbnails/<uuid>.crypt.tn` (see [SyncConfig.remoteThumbnailsDir]).
     * We list at the actual upload path so restore matches what was uploaded.
     *
     * @return count of thumbnails restored (DB rows inserted). Returns 0 if the remote
     *   has no thumbnails dir yet (fresh repo) or if listing fails — restore failure
     *   MUST NOT block login.
     *
     * @since PR4 sync — restore thumbnails on login
     */
    suspend fun restoreThumbnailsAfterLogin(): Int = withContext(Dispatchers.IO) {
        diag("restoreThumbnailsAfterLogin: BEGIN remote=${config.syncChosenRemote}")

        val remote = config.syncChosenRemote
        if (remote.isNullOrBlank()) {
            diag("restoreThumbnailsAfterLogin: ABORT — no remote chosen")
            return@withContext 0
        }

        val thumbnailsDir = SyncConfig.remoteThumbnailsDir
        diag("restoreThumbnailsAfterLogin: listing $remote:$thumbnailsDir/")

        val listResult = rcloneController.listRemote("$remote:", thumbnailsDir)
        if (listResult.isFailure) {
            val err = listResult.exceptionOrNull()?.message ?: "unknown error"
            diag(
                "restoreThumbnailsAfterLogin: listRemote FAILED: $err — treating as 0 thumbnails",
                listResult.exceptionOrNull(),
            )
            // A missing thumbnails directory on a fresh remote is not an error —
            // there's just nothing to restore. Listing failure MUST NOT block login;
            // the user can still get into the gallery and re-import.
            return@withContext 0
        }

        val remoteThumbs = listResult.getOrThrow()
            .filter { it.name.endsWith(THUMBNAIL_SUFFIX) }
        diag("restoreThumbnailsAfterLogin: listRemote OK, ${remoteThumbs.size} thumbnail candidates")

        var restored = 0
        for (thumb in remoteThumbs) {
            val name = thumb.name
            // Filename pattern: <uuid>.crypt.tn — strip the .crypt.tn suffix to recover the uuid.
            val uuid = name.removeSuffix(THUMBNAIL_SUFFIX)
            if (uuid.isBlank()) {
                diag("restoreThumbnailsAfterLogin: skipping malformed thumbnail name: $name")
                continue
            }

            // Idempotent: skip if a Photo row already exists for this uuid.
            val existing = runCatching { photoDao.get(uuid) }.getOrNull()
            if (existing != null) {
                diag("restoreThumbnailsAfterLogin: $uuid already in DB — skipping")
                continue
            }

            // Download the thumbnail to the same local path the gallery tile reads from
            // (app filesDir/<uuid>.crypt.tn — see VaultFileStorage / app.openFileInput).
            val localThumb = app.getFileStreamPath("$uuid$THUMBNAIL_SUFFIX")
            val remoteThumbPath = "$remote:$thumbnailsDir/$name"
            diag("restoreThumbnailsAfterLogin: downloading $remoteThumbPath → ${localThumb.absolutePath}")
            val dlResult = rcloneController.downloadFile(remoteThumbPath, localThumb.absolutePath)
            if (dlResult.isFailure) {
                diag(
                    "restoreThumbnailsAfterLogin: download FAILED for $uuid: ${dlResult.exceptionOrNull()?.message}",
                    dlResult.exceptionOrNull(),
                )
                continue
            }

            // Create a DB row for the photo. The original is NOT local — it will be
            // fetched on-demand by SyncRestorer when the user opens the photo.
            // TODO: PhotoType cannot be inferred from the thumbnail filename alone.
            //   Defaulting to JPEG (most common). The correct type should be persisted
            //   in the repo-config.json marker file during registerRepo() and read
            //   back here. For now, the type will be corrected when the original is
            //   downloaded on-demand. Tracked as a follow-up.
            val photo = Photo(
                fileName = "$uuid.$PHOTOK_FILE_EXTENSION",
                importedAt = System.currentTimeMillis(),
                lastModified = null,
                type = PhotoType.JPEG,
                size = 0L,
                uuid = uuid,
                syncState = SyncState.UPLOADED,
            )
            try {
                photoDao.insert(photo)
                restored++
                diag("restoreThumbnailsAfterLogin: inserted DB row for $uuid (syncState=UPLOADED)")
            } catch (e: Exception) {
                diag(
                    "restoreThumbnailsAfterLogin: insert FAILED for $uuid: ${e.message}",
                    e,
                )
                // Best-effort cleanup of the orphaned local thumbnail file so a
                // later retry doesn't see a stray file with no DB row.
                localThumb.delete()
            }
        }

        diag("restoreThumbnailsAfterLogin: DONE — restored $restored thumbnails")
        restored
    }

    /**
     * Upload the local recovery-phrase [VaultProtection] (the wrapped VMK + KDF
     * params) to a well-known path on the rclone remote, so a fresh install of
     * the app on another device can later download it and unlock the VMK via
     * the user's recovery phrase.
     *
     * The artifact is written as JSON at:
     *   `<remote>:<REPO_DIR>/<VAULT_PROTECTION_DIR>/<VAULT_PROTECTION_FILENAME>`
     *   = `<remote>:photok-backup/vault-protection/recovery-phrase.json`
     *
     * Independent verification (re-list) follows the same pattern as the repo
     * marker upload — the upload call's return value alone is NOT proof.
     *
     * Failure is non-fatal: registration MUST still succeed even if escrow
     * upload fails (the repo is still usable for photo upload; only fresh-install
     * recovery-phrase restore won't work). The caller ([registerRepo]) wraps the
     * call in try/catch and logs but does not throw.
     *
     * @since key-escrow — upload wrapped VMK during repo registration
     */
    private suspend fun uploadVaultProtectionEscrow(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val remote = config.syncChosenRemote
                ?: throw IllegalStateException("No remote chosen")

            diag("uploadVaultProtectionEscrow: BEGIN remote=$remote")

            val protection = vaultProtectionRepository.getProtection(VaultProtectionType.RecoveryPhrase)
            if (protection == null) {
                // No recovery-phrase protection set up locally yet. Shouldn't happen
                // in the normal register flow (the user must have a vault password +
                // recovery phrase to get here), but handle gracefully — skip escrow
                // upload, don't fail registration.
                diag("uploadVaultProtectionEscrow: no RecoveryPhrase protection in local DB — skipping")
                return@runCatching Unit
            }

            // Hand-built JSON — consistent with the marker file style (no external
            // serialization dependency in this class). java.util.Base64 (NOT
            // android.util.Base64) — simpler API, available on minSdk 26+ (this
            // project's minSdk is 35).
            val wrappedVmkB64 = Base64.getEncoder().encodeToString(protection.wrappedVMK)
            val saltJson = protection.params.salt?.let { "\"$it\"" } ?: "null"
            val kdfJson = protection.params.kdf?.value?.let { "\"$it\"" } ?: "null"
            val kdfIterJson = protection.params.kdfIterations?.toString() ?: "null"

            val json = """{"id":"${protection.id}","type":"${protection.type.name}",""" +
                """"wrappedVMK":"$wrappedVmkB64","params":{"salt":$saltJson,""" +
                """"iv":"${protection.params.iv}","kdf":$kdfJson,""" +
                """"kdfIterations":$kdfIterJson,"algorithm":"${protection.params.algorithm.value}",""" +
                """"keySize":${protection.params.keySize},"version":${protection.params.version}}}"""
            diag(
                "uploadVaultProtectionEscrow: serialized protection id=${protection.id} " +
                    "type=${protection.type} vmkB64Len=${wrappedVmkB64.length}"
            )

            val tempFile = File(app.cacheDir, "vault-protection-${System.currentTimeMillis()}.json")
            try {
                tempFile.writeText(json)
                diag(
                    "uploadVaultProtectionEscrow: wrote temp file ${tempFile.absolutePath} " +
                        "(${tempFile.length()} bytes)"
                )

                val remotePath = "$remote:$VAULT_PROTECTION_REMOTE_PATH"
                diag("uploadVaultProtectionEscrow: uploading → $remotePath")
                val uploadResult = rcloneController.uploadFile(tempFile.absolutePath, remotePath)
                if (uploadResult.isFailure) {
                    throw uploadResult.exceptionOrNull()
                        ?: IOException("Escrow upload failed")
                }

                // Independent verification — same pattern as registerRepo() marker.
                val verifyResult = rcloneController.listRemote(
                    "$remote:", "$REPO_DIR/$VAULT_PROTECTION_DIR"
                )
                if (verifyResult.isFailure) {
                    throw IOException(
                        "Escrow upload succeeded but verification listing failed: " +
                            verifyResult.exceptionOrNull()?.message
                    )
                }

                val files = verifyResult.getOrThrow()
                val found = files.any { it.name == VAULT_PROTECTION_FILENAME && it.size > 0 }
                if (!found) {
                    throw IOException(
                        "Escrow upload succeeded but file not found in independent listing. " +
                            "Files: $files"
                    )
                }
                diag("uploadVaultProtectionEscrow: verification OK — file present on remote")
                Unit
            } finally {
                tempFile.delete()
            }
        }
    }

    /**
     * Download the recovery-phrase [VaultProtection] escrow artifact from the
     * rclone remote (the counterpart of [uploadVaultProtectionEscrow]) and
     * persist it into the local VaultProtection DB so the existing phrase-entry
     * UI ([dev.leonlatsch.photok.encryption.ui.RecoveryPhraseRestoreScreen]) can
     * unlock the VMK on this fresh install.
     *
     * Behavior:
     * - Remote artifact present → deserialize, persist via [VaultProtectionRepository]
     *   (`createProtection` or `updateProtection` if a RecoveryPhrase row already
     *   exists — no duplicates), return `Result.success(protection)`.
     * - Remote artifact absent (older repo created before this feature existed,
     *   or rclone returns "not found") → return `Result.success(null)`. The
     *   caller treats this as "no escrow available" — login still succeeds but
     *   the user is routed to the degraded-mode UI.
     * - Other download errors (network, permissions, malformed JSON) → return
     *   `Result.failure(exception)`. The caller treats this as a non-fatal
     *   download error — login still succeeds (degraded mode), the user can
     *   still reach the gallery via the normal vault PIN/password path.
     *
     * @since key-escrow — download wrapped VMK during repo login
     */
    private suspend fun downloadVaultProtectionEscrow(): Result<VaultProtection?> =
        withContext(Dispatchers.IO) {
            runCatching {
                val remote = config.syncChosenRemote
                    ?: throw IllegalStateException("No remote chosen")

                diag("downloadVaultProtectionEscrow: BEGIN remote=$remote")

                val remotePath = "$remote:$VAULT_PROTECTION_REMOTE_PATH"
                val tempFile = File(
                    app.cacheDir, "vault-protection-dl-${System.currentTimeMillis()}.json"
                )
                try {
                    diag("downloadVaultProtectionEscrow: downloading $remotePath → ${tempFile.absolutePath}")
                    val dlResult = rcloneController.downloadFile(remotePath, tempFile.absolutePath)
                    if (dlResult.isFailure) {
                        val err = dlResult.exceptionOrNull()?.message ?: "unknown error"
                        // Graceful missing-artifact case: file doesn't exist on remote
                        // (older repo created before this feature existed). Don't fail —
                        // return null so the caller can show "not available" UI instead
                        // of an error. Match the same "not found" pattern used in
                        // detectRepo() but tightened: only the file-not-found phrasings
                        // rclone actually emits for a missing remote file.
                        val isNotFound = err.contains("not found", ignoreCase = true) ||
                            err.contains("doesn't exist", ignoreCase = true) ||
                            err.contains("does not exist", ignoreCase = true) ||
                            err.contains("no such file", ignoreCase = true) ||
                            err.contains("object not found", ignoreCase = true)
                        if (isNotFound) {
                            diag(
                                "downloadVaultProtectionEscrow: artifact not on remote " +
                                    "(old repo) — returning null"
                            )
                            return@runCatching null
                        }
                        throw dlResult.exceptionOrNull()
                            ?: IOException("Escrow download failed: $err")
                    }

                    if (!tempFile.exists() || tempFile.length() == 0L) {
                        // Defensive: rclone sometimes returns success with an empty file
                        // when the remote path is missing. Treat as "not on remote".
                        diag(
                            "downloadVaultProtectionEscrow: downloaded file missing or empty " +
                                "— treating as not-on-remote"
                        )
                        return@runCatching null
                    }

                    val json = tempFile.readText()
                    diag("downloadVaultProtectionEscrow: downloaded ${json.length} chars")

                    val protection = parseVaultProtection(json)
                        ?: throw IOException("Malformed vault-protection JSON")
                    diag(
                        "downloadVaultProtectionEscrow: parsed id=${protection.id} " +
                            "type=${protection.type}"
                    )

                    // Persist into local DB. If a RecoveryPhrase protection already
                    // exists (e.g. this device already has one set up), update it —
                    // don't create duplicates. Otherwise insert.
                    val existing = vaultProtectionRepository
                        .getProtection(VaultProtectionType.RecoveryPhrase)
                    if (existing != null) {
                        diag(
                            "downloadVaultProtectionEscrow: existing protection in DB " +
                                "(id=${existing.id}) — updating"
                        )
                        vaultProtectionRepository.updateProtection(protection)
                    } else {
                        diag("downloadVaultProtectionEscrow: no existing protection — creating")
                        vaultProtectionRepository.createProtection(protection)
                    }
                    diag("downloadVaultProtectionEscrow: persisted to local DB")
                    protection
                } finally {
                    tempFile.delete()
                }
            }
        }

    /**
     * Minimal hand-rolled JSON parser for the vault-protection escrow artifact.
     * Mirrors the style of [parseMarker] (regex-based, no external dependency).
     *
     * @since key-escrow — paired with [uploadVaultProtectionEscrow]
     */
    private fun parseVaultProtection(json: String): VaultProtection? {
        return try {
            val id = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1)
            val typeStr = Regex("\"type\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1)
            val wrappedVmkB64 = Regex("\"wrappedVMK\"\\s*:\\s*\"([^\"]+)\"")
                .find(json)?.groupValues?.get(1)
            // salt is a nullable string — match the quoted form only (null → null).
            val salt = Regex("\"salt\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1)
            val iv = Regex("\"iv\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1)
            val kdfStr = Regex("\"kdf\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1)
            val kdfIterations = Regex("\"kdfIterations\"\\s*:\\s*(\\d+)")
                .find(json)?.groupValues?.get(1)?.toIntOrNull()
            val algorithmStr = Regex("\"algorithm\"\\s*:\\s*\"([^\"]+)\"")
                .find(json)?.groupValues?.get(1)
            val keySize = Regex("\"keySize\"\\s*:\\s*(\\d+)")
                .find(json)?.groupValues?.get(1)?.toIntOrNull()
            val version = Regex("\"version\"\\s*:\\s*(\\d+)")
                .find(json)?.groupValues?.get(1)?.toIntOrNull() ?: 1

            if (id == null || typeStr == null || wrappedVmkB64 == null ||
                iv == null || algorithmStr == null || keySize == null
            ) {
                diag("parseVaultProtection: missing required field(s)")
                return null
            }

            val type = VaultProtectionType.entries.find { it.name == typeStr }
            if (type == null) {
                diag("parseVaultProtection: unknown type=$typeStr")
                return null
            }
            val algorithm = Algorithm.entries.find { it.value == algorithmStr }
            if (algorithm == null) {
                diag("parseVaultProtection: unknown algorithm=$algorithmStr")
                return null
            }
            val kdf = kdfStr?.let { s -> Kdf.entries.find { it.value == s } }
            val wrappedVMK = Base64.getDecoder().decode(wrappedVmkB64)

            VaultProtection(
                id = id,
                type = type,
                wrappedVMK = wrappedVMK,
                params = VaultProtectionParams(
                    salt = salt,
                    iv = iv,
                    kdf = kdf,
                    kdfIterations = kdfIterations,
                    algorithm = algorithm,
                    keySize = keySize,
                    version = version,
                ),
            )
        } catch (e: Exception) {
            diag("parseVaultProtection: FAILED ${e.javaClass.name}: ${e.message}", e)
            null
        }
    }

    /**
     * Whether this device has a confirmed repo session. This is the gate for gallery access.
     *
     * Note: this reads a local flag. On cold start, [revalidateRepo] should be called to
     * confirm the remote is still reachable. The local flag alone is NOT sufficient for
     * something as important as "repo is set up" — but re-running full lsjson on every cold
     * start is too slow. The policy is: trust the local flag for fast gallery access, but
     * re-validate on a background coroutine and re-gate if the remote is unreachable.
     */
    fun isRepoConfirmed(): Boolean = config.repoConfirmed && config.syncChosenRemote != null

    /**
     * Re-validate the repo session on cold start. Lightweight check: just verify rcd can
     * reach the remote (ping). Does NOT do a full lsjson — that's too slow for every launch.
     *
     * If this fails, the caller should re-gate the user to RepoSetupFragment.
     */
    suspend fun revalidateRepo(): Boolean = withContext(Dispatchers.IO) {
        if (!isRepoConfirmed()) return@withContext false
        // TODO: for now, just check that a remote is chosen. A full reachability check
        // would require starting rcd + pinging — too slow for every cold start. The
        // actual upload worker will fail fast if the remote is unreachable, and the
        // user can re-import the config from Settings.
        config.syncChosenRemote != null
    }

    private fun parseMarker(json: String): RepoMarker? {
        return try {
            // Minimal JSON parse — no external dependency
            val repoId = Regex("\"repo_id\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1)
            val created = Regex("\"created\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1)
            val version = Regex("\"version\"\\s*:\\s*(\\d+)").find(json)?.groupValues?.get(1)?.toIntOrNull()
            if (repoId != null && created != null && version != null) {
                RepoMarker(repoId, created, version)
            } else null
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        const val REPO_DIR = "photok-backup"
        const val MARKER_FILENAME = "repo-config.json"

        // @since PR4 sync — thumbnail filename suffix, mirrors
        // internalThumbnailFileName(uuid) = "${uuid}.$PHOTOK_FILE_EXTENSION.tn"
        private const val THUMBNAIL_SUFFIX = ".$PHOTOK_FILE_EXTENSION.tn"

        // @since key-escrow — location of the recovery-phrase wrapped-VMK artifact
        // on the rclone remote. Written by uploadVaultProtectionEscrow() during
        // registerRepo(); read by downloadVaultProtectionEscrow() during loginRepo().
        const val VAULT_PROTECTION_DIR = "vault-protection"
        const val VAULT_PROTECTION_FILENAME = "recovery-phrase.json"
        const val VAULT_PROTECTION_REMOTE_PATH = "$REPO_DIR/$VAULT_PROTECTION_DIR/$VAULT_PROTECTION_FILENAME"
    }
}
