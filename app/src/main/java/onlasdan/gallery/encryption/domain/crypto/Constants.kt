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
// GCM uses a 12-byte IV (96-bit) — the NIST-recommended size for best performance.
// The authentication tag is 16 bytes (128-bit) — the maximum strength.
// GCM's tag is appended automatically by the JCE provider when the Cipher is
// finalized (close on CipherOutputStream). On decrypt, the JCE provider reads
// the trailing 16 bytes as the tag and verifies it on doFinal().
const val GCM_IV_SIZE = 12
const val GCM_TAG_SIZE = 16
