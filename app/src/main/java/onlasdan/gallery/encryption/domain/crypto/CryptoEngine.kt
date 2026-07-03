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

package onlasdan.gallery.encryption.domain.crypto

import onlasdan.gallery.encryption.domain.models.Session
import java.io.InputStream
import java.io.OutputStream
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream

/**
 * PhotoZ's pluggable encryption engine.
 *
 * The encrypt path accepts a [useGcm] flag (default `true`) so callers can opt
 * out of GCM for files that need random-access streaming — currently only video
 * originals, which the [onlasdan.gallery.transcoding.data.AesCbcRandomAccessDataSource]
 * decrypts via CBC's block-chain IV property. All other files (photos, thumbnails,
 * video previews, PDF/ZIP/audio) default to GCM for the authentication tag
 * protection (Sprint 1 / P6).
 *
 * The decrypt path is fully automatic — it reads the version byte from the
 * stream header and dispatches to CBC (versions 1, 2) or GCM (version 3).
 * Callers don't need to know which algorithm was used at encrypt time.
 */
interface CryptoEngine {
    fun createEncryptStream(
        output: OutputStream,
        session: Session,
        /**
         * When `true` (default), the engine writes a version-3 GCM header.
         * When `false`, it writes a version-2 CBC header — required for files
         * that will be streamed via [AesCbcRandomAccessDataSource] (video originals).
         */
        useGcm: Boolean = true,
    ): CipherOutputStream?

    fun createDecryptStream(
        input: InputStream,
        session: Session,
    ): CipherInputStream?
}
