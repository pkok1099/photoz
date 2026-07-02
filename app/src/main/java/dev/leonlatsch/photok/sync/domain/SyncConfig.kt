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
    const val remoteOriginalsDir: String = "originals"
    const val remoteThumbnailsDir: String = "thumbnails"
    const val remoteVideosDir: String = "videos"

    // ─── Per-photo metadata sidecar (v8 path-consistency) ───────────────────
    // A small JSON file uploaded alongside each photo's encrypted artifacts,
    // capturing the photo's original local-origin provenance (relativePath +
    // fileName), type, and size — so a fresh-install restore can populate the
    // Photo DB row accurately instead of guessing `type=JPEG, size=0`.
    //
    // Remote layout (note the `photok-backup/` prefix — the metadata dir lives
    // UNDER the repo root, alongside `vault-protection/`. The encrypted
    // originals/thumbnails/videos live at the REMOTE ROOT — no prefix — for
    // backwards compat with PR1/PR4 installs):
    //   <remote>:photok-backup/metadata/<uuid>.json
    // @since v8 — path-consistency metadata sidecar
    const val METADATA_DIR: String = "metadata"
    const val METADATA_FILENAME_SUFFIX: String = ".json"
}
