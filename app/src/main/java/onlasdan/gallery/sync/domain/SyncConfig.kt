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

package onlasdan.gallery.sync.domain

/**
 * Hardcoded configuration for the rclone sync feature (PR1).
 *
 * The active rclone remote is read at runtime from
 * [onlasdan.gallery.settings.data.Config.syncChosenRemote], set by the user via the
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
    //   <remote>:photoz-backup/registry.json.crypt        (v9 — dedup registry)
    //   <remote>:photoz-backup/thumbnails/pack-*.pack     (future — 50MB packs)
    //   <remote>:photoz-backup/repo-config.json
    //   <remote>:photoz-backup/vault-protection/recovery-phrase.json
    //   <remote>:photoz-backup/vault-protection/wrapped-phrase.json
    //
    // @since rebrand + consolidation — moved from remote root to under photoz-backup/
    // @since v9 — per-photo metadata sidecar (`metadata/<uuid>.json`) is REPLACED
    //   by the encrypted dedup registry (`registry.json.crypt`). The metadata
    //   dir is gone; per-photo metadata now lives inside the registry entries.
    const val REPO_DIR: String = "photoz-backup"
    const val remoteOriginalsDir: String = "$REPO_DIR/originals"
    const val remoteThumbnailsDir: String = "$REPO_DIR/thumbnails"
    const val remoteVideosDir: String = "$REPO_DIR/videos"

    // ─── Dedup + packed thumbnails (v9) ───────────────────────────────────
    // The dedup registry is a single encrypted JSON artifact at
    // `<remote>:photoz-backup/registry.json.crypt`. It contains one entry per
    // content-hash that has ever been uploaded, recording the canonical UUID,
    // filename, album_path, size, type and (future) packed-thumbnail location.
    // See [onlasdan.gallery.sync.work.HashRegistry] for the on-wire format.
    //
    // Encryption: AES-256-GCM with the VMK as the key, format
    //   [12-byte nonce][ciphertext + 16-byte auth-tag].
    //
    // The packed-thumbnails constants below are RESERVED for a follow-up
    // enhancement — the current implementation uploads thumbnails individually
    // (`<remote>:photoz-backup/thumbnails/<uuid>.crypt.tn`) and leaves the
    // `thumbnail_pack` field in each registry entry as null. The registry
    // format already supports packs (thumbnail_pack / offset / length), so
    // the optimization can be added later without a format change.
    const val REGISTRY_FILENAME = "registry.json.crypt"
    const val REGISTRY_REMOTE_PATH = "$REPO_DIR/registry.json.crypt"
    const val THUMBNAIL_PACK_SIZE_BYTES: Long = 50L * 1024 * 1024 // 50 MB per pack
    const val THUMBNAIL_PACK_DIR = "$REPO_DIR/thumbnails"
    const val THUMBNAIL_PACK_PREFIX = "pack-"
    const val THUMBNAIL_PACK_SUFFIX = ".pack"
}
