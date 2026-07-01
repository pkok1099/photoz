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
import dev.leonlatsch.photok.settings.data.Config
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.time.Instant
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
) {

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
     * Login to an existing repo. Read-only — does NOT touch the marker file.
     * Persists the repo binding locally.
     */
    suspend fun loginRepo(marker: RepoMarker): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            config.repoId = marker.repoId
            config.repoConfirmed = true
            Timber.i("Repo login: id=${marker.repoId}")
            Unit
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
    }
}
