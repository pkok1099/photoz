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

package onlasdan.gallery.encryption.crypto

import onlasdan.gallery.encryption.domain.LegacyEncryption
import onlasdan.gallery.encryption.domain.crypto.CbcCryptoEngine
import onlasdan.gallery.encryption.domain.crypto.KeyGen
import onlasdan.gallery.encryption.domain.crypto.LegacyGcmCryptoEngine
import onlasdan.gallery.encryption.domain.models.Algorithm
import onlasdan.gallery.encryption.domain.models.LegacySession
import onlasdan.gallery.encryption.domain.models.VaultSession
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec

/**
 * Tests for the two crypto engines (GCM and CBC) at the engine level.
 *
 * Each test exercises a complete encrypt→decrypt flow, so failures pinpoint
 * broken engine behavior rather than individual helper methods.
 */
@RunWith(RobolectricTestRunner::class)
class CryptoEnginesTest {

    private val legacyEngine = LegacyGcmCryptoEngine()
    private val legacyEncryption = LegacyEncryption()
    private val cbcEngine = CbcCryptoEngine()
    private val keyGen = KeyGen()

    /**
     * The legacy GCM engine (1.x.x) must encrypt and decrypt correctly for both small and
     * large payloads, and the ciphertext must differ from the plaintext.
     */
    @Test
    fun `GCM engine encrypts and decrypts file content`() {
        val session = legacyEncryption.obtainSession("test-password-123")

        for (plaintext in listOf(
            "Hello, PhotoZ! 1.x.x GCM file.".toByteArray(),
            ByteArray(1_500_000) { (it % 256).toByte() },
        )) {
            val ciphertext = gcmEncrypt(plaintext, session)
            val decrypted = gcmDecrypt(ciphertext, session)

            assertArrayEquals(plaintext, decrypted)
            assertFalse("Ciphertext must differ from plaintext", ciphertext.contentEquals(plaintext))
        }
    }

    /**
     * The GCM authentication tag must prevent recovering plaintext with a wrong password.
     * Either the stream is null, reading throws, or the output differs from the original.
     */
    @Test
    fun `GCM engine wrong password cannot recover plaintext`() {
        val session = legacyEncryption.obtainSession("correct-password")
        val wrongSession = legacyEncryption.obtainSession("wrong-password")
        val plaintext = "sensitive photo content".toByteArray()

        val ciphertext = gcmEncrypt(plaintext, session)
        val stream = legacyEngine.createDecryptStream(ByteArrayInputStream(ciphertext), wrongSession)

        if (stream != null) {
            val result = runCatching { stream.readBytes() }
            if (result.isSuccess) {
                assertFalse(
                    "Wrong password must not produce the original plaintext",
                    result.getOrThrow().contentEquals(plaintext),
                )
            }
            // An exception from GCM auth tag failure is equally acceptable
        }
        // A null stream is also acceptable
    }

    /**
     * The CBC engine (3.x.x) must produce a fresh random IV on every encrypt call (so two
     * encryptions of the same plaintext differ), and both resulting blobs must decrypt correctly.
     * Also covers large payloads.
     */
    @Test
    fun `CBC engine encrypts files with random IV and decrypts V2-format content`() {
        val session = VaultSession(keyGen.generateVaultMasterKey())
        val plaintext = "Hello, PhotoZ! 3.x.x CBC file.".toByteArray()

        val ct1 = cbcEncrypt(plaintext, session)
        val ct2 = cbcEncrypt(plaintext, session)

        assertFalse("Two encryptions of the same plaintext must produce different ciphertext", ct1.contentEquals(ct2))
        assertArrayEquals(plaintext, cbcDecrypt(ct1, session))
        assertArrayEquals(plaintext, cbcDecrypt(ct2, session))

        // Large payload
        val largeFile = ByteArray(1_500_000) { (it % 256).toByte() }
        assertArrayEquals(largeFile, cbcDecrypt(cbcEncrypt(largeFile, session), session))
    }

    /**
     * Backward compatibility: files written by the 2.x.x app use a V1 header
     * (byte 0x01 + 16-byte salt + 16-byte IV + ciphertext). The current CBC engine must
     * still decrypt them without any re-encryption step.
     *
     * This is a hard compatibility requirement — breaking it corrupts existing user vaults.
     */
    @Test
    fun `CBC engine decrypts V1-format files written by the 2xx app`() {
        val vmk = keyGen.generateVaultMasterKey()
        val salt = ByteArray(16) { (it * 7).toByte() }
        val iv = ByteArray(16) { (it * 3 + 1).toByte() }
        val plaintext = "Legacy 2.x.x encrypted photo file.".toByteArray()

        // Simulate what the 2.x.x app wrote to disk
        val ciphertext = Cipher.getInstance(Algorithm.AesCbcPkcs7Padding.value).run {
            init(Cipher.ENCRYPT_MODE, vmk, IvParameterSpec(iv))
            doFinal(plaintext)
        }
        val v1Blob = ByteArrayOutputStream().apply {
            write(byteArrayOf(0x01))
            write(salt)
            write(iv)
            write(ciphertext)
        }.toByteArray()

        val decrypted = cbcEngine.createDecryptStream(ByteArrayInputStream(v1Blob), VaultSession(vmk))!!.readBytes()
        assertArrayEquals(plaintext, decrypted)
    }

    /**
     * The CBC engine must not crash on an unrecognized version byte — it returns null,
     * which the caller handles by skipping the file or showing an error.
     */
    @Test
    fun `CBC engine returns null for unrecognized version byte`() {
        val session = VaultSession(keyGen.generateVaultMasterKey())
        val result = cbcEngine.createDecryptStream(ByteArrayInputStream(byteArrayOf(0x09)), session)
        assertNull("Unrecognized version byte must return null, not throw", result)
    }

    /**
     * Sprint 1 / P6 — verify the new GCM encrypt path produces a version-3 header.
     *
     * Format: [0x03][IV(12)][GCM ciphertext + 16-byte tag]
     */
    @Test
    fun `GCM encrypt produces version 3 header with 12-byte IV`() {
        val vmk = keyGen.generateVaultMasterKey()
        val session = VaultSession(vmk)
        val plaintext = "Sprint 1 P6 GCM test".toByteArray()

        val out = ByteArrayOutputStream()
        cbcEngine.createEncryptStream(out, session, useGcm = true)!!.use { it.write(plaintext) }
        val ciphertext = out.toByteArray()

        assertEquals("Version byte must be 0x03 for GCM", 0x03.toByte(), ciphertext[0])
        // Header is 1 + 12 = 13 bytes. Body is ciphertext + 16-byte tag.
        // For a 20-byte plaintext, GCM ciphertext is 20 bytes + 16 tag = 36 bytes.
        // Total: 13 + 36 = 49 bytes.
        assertEquals("Total length must be 1 (ver) + 12 (IV) + plaintext + 16 (tag)",
            (1 + 12 + plaintext.size + 16).toLong(), ciphertext.size.toLong())
    }

    /**
     * Sprint 1 / P6 — verify GCM encrypt → decrypt round-trip recovers the original plaintext.
     */
    @Test
    fun `GCM encrypt then decrypt round-trip recovers plaintext`() {
        val vmk = keyGen.generateVaultMasterKey()
        val session = VaultSession(vmk)
        val plaintext = "Round-trip GCM test 12345".toByteArray()

        val out = ByteArrayOutputStream()
        cbcEngine.createEncryptStream(out, session, useGcm = true)!!.use { it.write(plaintext) }
        val ciphertext = out.toByteArray()

        val decrypted = cbcEngine.createDecryptStream(
            ByteArrayInputStream(ciphertext), session
        )!!.readBytes()

        assertArrayEquals(plaintext, decrypted)
    }

    /**
     * Sprint 1 / P6 — verify that a tampered GCM ciphertext (bit-flipped) is detected
     * by the authentication tag and throws on read or close. This is the whole point
     * of GCM over CBC: bit-flipping attacks that CBC silently accepts are caught here.
     *
     * GCM's authentication tag is verified when the Cipher is finalized — for
     * CipherInputStream, that happens on the last `read()` OR on `close()`.
     * We call both (read + close) to maximize the chance of catching the
     * AEADBadTagException regardless of where the JCE provider chooses to
     * verify the tag.
     */
    @Test(expected = Exception::class)
    fun `GCM decrypt detects tampered ciphertext via authentication tag`() {
        val vmk = keyGen.generateVaultMasterKey()
        val session = VaultSession(vmk)
        val plaintext = "Tamper detection test".toByteArray()

        val out = ByteArrayOutputStream()
        cbcEngine.createEncryptStream(out, session, useGcm = true)!!.use { it.write(plaintext) }
        val ciphertext = out.toByteArray().copyOf()

        // Flip a byte in the ciphertext body (after the 13-byte header).
        // The authentication tag will fail to verify on decrypt.
        ciphertext[ciphertext.size - 5] = (ciphertext[ciphertext.size - 5].toInt() xor 0x01).toByte()

        // Read all bytes AND close — the AEADBadTagException is thrown on
        // either the final read() (when the cipher finalizes) or on close().
        val stream = cbcEngine.createDecryptStream(ByteArrayInputStream(ciphertext), session)!!
        try {
            stream.readBytes()
        } finally {
            stream.close()
        }
    }

    // --- helpers ---

    private fun gcmEncrypt(plaintext: ByteArray, session: LegacySession): ByteArray {
        val out = ByteArrayOutputStream()
        legacyEngine.createEncryptStream(out, session)!!.use { it.write(plaintext) }
        return out.toByteArray()
    }

    private fun gcmDecrypt(ciphertext: ByteArray, session: LegacySession): ByteArray =
        legacyEngine.createDecryptStream(ByteArrayInputStream(ciphertext), session)!!.readBytes()

    private fun cbcEncrypt(plaintext: ByteArray, session: VaultSession): ByteArray {
        val out = ByteArrayOutputStream()
        // Sprint 1 / P6: pass useGcm = false explicitly — this helper is named
        // cbcEncrypt and is used by tests that verify CBC-specific behavior
        // (version byte 0x02, block-chain IV property for streaming). The
        // engine's default is now GCM (version 3).
        cbcEngine.createEncryptStream(out, session, useGcm = false)!!.use { it.write(plaintext) }
        return out.toByteArray()
    }

    private fun cbcDecrypt(ciphertext: ByteArray, session: VaultSession): ByteArray =
        cbcEngine.createDecryptStream(ByteArrayInputStream(ciphertext), session)!!.readBytes()
}
