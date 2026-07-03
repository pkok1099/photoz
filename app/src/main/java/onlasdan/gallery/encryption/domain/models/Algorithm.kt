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

package onlasdan.gallery.encryption.domain.models

import android.security.keystore.KeyProperties
import onlasdan.gallery.encryption.domain.crypto.GCM_IV_SIZE
import onlasdan.gallery.encryption.domain.crypto.IV_SIZE
import onlasdan.gallery.encryption.domain.crypto.SALT_SIZE

enum class Algorithm(val value: String, val padding: String, val blockMode: String) {
    AesCbcPkcs7Padding(
        value = "AES/CBC/PKCS7Padding",
        padding = KeyProperties.ENCRYPTION_PADDING_PKCS7,
        blockMode = KeyProperties.BLOCK_MODE_CBC
    ),
    AesGcmNoPadding(
        value = "AES/GCM/NoPadding",
        padding = KeyProperties.ENCRYPTION_PADDING_NONE,
        blockMode = KeyProperties.BLOCK_MODE_GCM
    )
}

/**
 * File format versions for encrypted photo/file artifacts in PhotoZ's private storage.
 *
 *  - **One** (legacy, pre-v8): `[0x01][SALT(16)][IV(16)][CBC ciphertext]`
 *    Derived the KEK on-the-fly from password + salt via PBKDF2 — slow and
 *    removed in v8 in favor of pre-derived VMK.
 *  - **Two** (v8 onward): `[0x02][IV(16)][CBC ciphertext]`
 *    Uses the in-memory VMK directly. Still used for **video originals** because
 *    the random-access streaming DataSource ([AesCbcRandomAccessDataSource])
 *    relies on CBC's block-chain property (each ciphertext block is the IV for
 *    the next). GCM streaming is a future enhancement (see ROADMAP.md).
 *  - **Three** (Sprint 1 / P6 onward): `[0x03][IV(12)][GCM ciphertext][tag(16)]`
 *    AES-256-GCM with authentication tag — prevents bit-flipping attacks that
 *    CBC is vulnerable to. Used for **all non-video files** (photos, thumbnails,
 *    video previews, PDF/ZIP/audio). Backwards-compatible: the decrypt path
 *    reads the version byte and dispatches to CBC or GCM transparently.
 */
enum class EncryptionVersionByte(val value: Byte) {
    One(0x01),
    Two(0x02),
    Three(0x03);

    val headerSize: Int
        get() = when (this) {
            EncryptionVersionByte.One -> 1 + SALT_SIZE + IV_SIZE
            EncryptionVersionByte.Two -> 1 + IV_SIZE
            // GCM IV is 12 bytes (GCM_IV_SIZE), no salt.
            EncryptionVersionByte.Three -> 1 + GCM_IV_SIZE
        }

    companion object {
        fun fromValue(value: Byte): EncryptionVersionByte {
            return entries.find { it.value == value } ?: error("Unknown version byte: $value")
        }
    }
}