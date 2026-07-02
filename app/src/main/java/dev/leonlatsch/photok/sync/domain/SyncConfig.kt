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

package dev.leonlatsch.photok.sync.domain

/**
 * Hardcoded configuration for the rclone sync feature (PR1).
 *
 * The active rclone remote is read at runtime from
 * [dev.leonlatsch.photok.settings.data.Config.syncChosenRemote], set by the user via the
 * remote picker in Settings → Cloud Sync. Callers that need to build remote paths should
 * obtain the `Config` instance (e.g. via Hilt injection) and read `config.syncChosenRemote`.
 * If `null`, sync should be skipped (no remote chosen).
 *
 * The subfolder layout (`originals/`, `thumbnails/`, `videos/`) is app-managed — it lives
 * INSIDE whatever remote the user picked, not the remote's name.
 */
object SyncConfig {
    const val autoUploadEnabled: Boolean = true
    const val deleteLocalAfterUpload: Boolean = false
    const val maxSyncAttempts: Int = 5
    const val initialBackoffSeconds: Long = 10L

    // ─── Remote layout (consolidated under photoz-backup/) ────────────────
    // All sync artifacts live INSIDE the repo root folder (photoz-backup/),
    // not scattered at the remote root. This makes the remote layout clean:
    //   <remote>:photoz-backup/originals/<uuid>.crypt
    //   <remote>:photoz-backup/thumbnails/<uuid>.crypt.tn
    //   <remote>:photoz-backup/videos/<uuid>.crypt.vp
    //   <remote>:photoz-backup/metadata/<uuid>.json
    //   <remote>:photoz-backup/repo-config.json
    //   <remote>:photoz-backup/vault-protection/recovery-phrase.json
    //   <remote>:photoz-backup/vault-protection/wrapped-phrase.json
    //
    // @since rebrand + consolidation — moved from remote root to under photoz-backup/
    const val REPO_DIR: String = "photoz-backup"
    const val remoteOriginalsDir: String = "$REPO_DIR/originals"
    const val remoteThumbnailsDir: String = "$REPO_DIR/thumbnails"
    const val remoteVideosDir: String = "$REPO_DIR/videos"

    // ─── Per-photo metadata sidecar (v8 path-consistency) ───────────────────
    const val METADATA_DIR: String = "$REPO_DIR/metadata"
    const val METADATA_FILENAME_SUFFIX: String = ".json"
}
