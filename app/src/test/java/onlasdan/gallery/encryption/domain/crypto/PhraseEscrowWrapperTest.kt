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

import onlasdan.gallery.encryption.domain.models.RecoveryPhrase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for [PhraseEscrowWrapper] — the second layer of the two-layer
 * recovery-phrase escrow (password wraps the phrase; the phrase wraps the VMK).
 *
 * Exercises:
 *  - `wrapPhrase` → `unwrapPhrase` round-trip with the correct password (the
 *    decrypted phrase must equal the original).
 *  - `unwrapPhrase` with a WRONG password returns `null` (CBC padding error
 *    caught and converted to null — the user sees "wrong password" not a crash).
 *  - JSON serialization round-trip of [PhraseEscrowWrapper.WrappedPhrase]
 *    (exercises the Base64 + regex-parse path used to persist the wrapped
 *    phrase on the rclone remote).
 *
 * Uses a real [KeyGen] — it's pure JCA (PBKDF2WithHmacSHA256 + AES-CBC), no
 * Android Keystore involvement. Robolectric is only needed because
 * [onlasdan.gallery.encryption.domain.models.Algorithm] references
 * `android.security.keystore.KeyProperties` for its enum constants.
 *
 * @since Batch 3 — unit tests for key components
 */
@RunWith(RobolectricTestRunner::class)
class PhraseEscrowWrapperTest {

    private val keyGen = KeyGen()
    private val wrapper = PhraseEscrowWrapper(keyGen)

    // A representative 12-word BIP-39 mnemonic (the recovery phrase payload).
    private val testPhrase = RecoveryPhrase(
        listOf(
            "abandon", "ability", "able", "about", "above", "absent",
            "absorb", "abstract", "absurd", "abuse", "access", "accident",
        ),
    )

    @Test
    fun `wrapPhrase then unwrapPhrase with correct password recovers original phrase`() {
        val password = "correct-horse-battery-staple"

        val wrapped = wrapper.wrapPhrase(testPhrase, password)
        val unwrapped = wrapper.unwrapPhrase(wrapped, password)

        assertNotNull("Unwrap with correct password must succeed", unwrapped)
        assertEquals(
            "Round-tripped phrase must equal the original",
            testPhrase.toMnemonicString(),
            unwrapped!!.toMnemonicString(),
        )
    }

    @Test
    fun `unwrapPhrase with wrong password returns null`() {
        val correctPassword = "correct-password"
        val wrongPassword = "wrong-password"

        val wrapped = wrapper.wrapPhrase(testPhrase, correctPassword)
        val unwrapped = wrapper.unwrapPhrase(wrapped, wrongPassword)

        assertNull(
            "Wrong password must yield null (CBC padding error caught, not a thrown exception)",
            unwrapped,
        )
    }

    @Test
    fun `wrapPhrase produces distinct salt and iv across calls`() {
        val password = "any-password"

        val wrapped1 = wrapper.wrapPhrase(testPhrase, password)
        val wrapped2 = wrapper.wrapPhrase(testPhrase, password)

        assertTrue(
            "Each wrap must produce a unique salt (random per call)",
            !wrapped1.salt.contentEquals(wrapped2.salt),
        )
        assertTrue(
            "Each wrap must produce a unique IV (random per call)",
            !wrapped1.iv.contentEquals(wrapped2.iv),
        )
        assertTrue(
            "Each wrap must produce distinct ciphertext (random salt + IV → different KEK + IV)",
            !wrapped1.wrappedPhrase.contentEquals(wrapped2.wrappedPhrase),
        )
    }

    @Test
    fun `wrapPhrase with empty password still round-trips`() {
        val password = ""

        val wrapped = wrapper.wrapPhrase(testPhrase, password)
        val unwrapped = wrapper.unwrapPhrase(wrapped, password)

        assertNotNull("Empty password must still round-trip", unwrapped)
        assertEquals(testPhrase.toMnemonicString(), unwrapped!!.toMnemonicString())
    }

    @Test
    fun `wrapPhrase with unicode password round-trips`() {
        val password = "p@ssw0rd-αβγδ-你好-🚀"

        val wrapped = wrapper.wrapPhrase(testPhrase, password)
        val unwrapped = wrapper.unwrapPhrase(wrapped, password)

        assertNotNull("Unicode password must round-trip", unwrapped)
        assertEquals(testPhrase.toMnemonicString(), unwrapped!!.toMnemonicString())
    }

    @Test
    fun `WrappedPhrase JSON round-trips through toJson and fromJson`() {
        val password = "json-roundtrip-password"
        val wrapped = wrapper.wrapPhrase(testPhrase, password)

        val json = wrapped.toJson()
        val parsed = PhraseEscrowWrapper.WrappedPhrase.fromJson(json)

        assertNotNull("fromJson must succeed for valid JSON", parsed)
        val p = parsed!!
        assertTrue("wrappedPhrase bytes must round-trip", wrapped.wrappedPhrase.contentEquals(p.wrappedPhrase))
        assertTrue("salt bytes must round-trip", wrapped.salt.contentEquals(p.salt))
        assertTrue("iv bytes must round-trip", wrapped.iv.contentEquals(p.iv))
        assertEquals(wrapped.kdf, p.kdf)
        assertEquals(wrapped.kdfIterations, p.kdfIterations)
        assertEquals(wrapped.algorithm, p.algorithm)
        assertEquals(wrapped.keySize, p.keySize)

        // The parsed wrapped phrase must also unwrap correctly.
        val unwrapped = wrapper.unwrapPhrase(p, password)
        assertNotNull("Parsed WrappedPhrase must unwrap to the original phrase", unwrapped)
        assertEquals(testPhrase.toMnemonicString(), unwrapped!!.toMnemonicString())
    }

    @Test
    fun `fromJson returns null for malformed JSON`() {
        val malformed = "not a json string"
        val parsed = PhraseEscrowWrapper.WrappedPhrase.fromJson(malformed)
        assertNull("Malformed JSON must yield null (not an exception)", parsed)
    }

    @Test
    fun `fromJson returns null for JSON missing required fields`() {
        // Missing the "iv" field — must yield null.
        val missingIv = """{"wrappedPhrase":"abc","salt":"def","kdf":"PBKDF2WithHmacSHA256",""" +
            """"kdfIterations":100000,"algorithm":"AES/CBC/PKCS7Padding","keySize":256}"""
        val parsed = PhraseEscrowWrapper.WrappedPhrase.fromJson(missingIv)
        assertNull("JSON missing required fields must yield null", parsed)
    }
}
