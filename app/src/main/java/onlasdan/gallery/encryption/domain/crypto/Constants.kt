/*
 *   Copyright 2020-2026 PhotoZ
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

package onlasdan.gallery.encryption.domain.crypto

const val IV_SIZE = 16
const val SALT_SIZE = 16
const val BLOCK_SIZE = 16

// ─── Sprint 1 / P6: AES-256-GCM for new files ──────────────────────────────
const val GCM_IV_SIZE = 12
const val GCM_TAG_SIZE = 16

// ─── TODO #2: Chunked streaming encryption ─────────────────────────────────
// Chunk size for version 0x04 (chunked GCM). 1MB is a good balance:
// - Per-chunk overhead: 12 (nonce) + 16 (tag) = 28 bytes
// - For 100MB video: ~100 chunks, ~2.8KB overhead (0.003%)
// - For 5MB photo: ~5 chunks, ~140 bytes overhead
// - Random access granularity: 1MB (seek to any 1MB boundary)
const val CHUNK_SIZE = 1_048_576 // 1MB

// Per-chunk overhead: nonce(12) + auth_tag(16) = 28 bytes
const val CHUNK_OVERHEAD = GCM_IV_SIZE + GCM_TAG_SIZE
