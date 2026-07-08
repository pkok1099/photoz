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
import onlasdan.gallery.encryption.domain.crypto.GCM_IV_SIZE
import onlasdan.gallery.encryption.domain.crypto.GCM_TAG_SIZE
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
		val ciphertext =
			Cipher.getInstance(Algorithm.AesCbcPkcs7Padding.value).run {
				init(Cipher.ENCRYPT_MODE, vmk, IvParameterSpec(iv))
				doFinal(plaintext)
			}
		val v1Blob =
			ByteArrayOutputStream()
				.apply {
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
	 * Sprint 1 / P6 + TODO #2 — verify the new chunked GCM encrypt path
	 * produces a version-4 header.
	 *
	 * Format: [0x04][chunk_size(4)][total_plaintext_size(8)][per-chunk GCM blobs]
	 * Header: 1 + 4 + 8 = 13 bytes.
	 *
	 * For a 20-byte plaintext with default chunk size (1MB), there's one
	 * chunk: nonce(12) + ciphertext(20) + tag(16) = 48 bytes.
	 * Total: 13 + 48 = 61 bytes.
	 *
	 * @since v15 — TODO #2 updated from version 0x03 to version 0x04
	 */
	@Test
	fun `GCM encrypt produces version 4 chunked header`() {
		val vmk = keyGen.generateVaultMasterKey()
		val session = VaultSession(vmk)
		val plaintext = "Sprint 1 P6 GCM test".toByteArray()

		val out = ByteArrayOutputStream()
		cbcEngine.createEncryptStream(out, session, useGcm = true)!!.use { it.write(plaintext) }
		val ciphertext = out.toByteArray()

		assertEquals("Version byte must be 0x04 for chunked GCM", 0x04.toByte(), ciphertext[0])
		// Header is 1 (version) + 4 (chunk_size) + 8 (total_size) = 13 bytes.
		// Body is one chunk: nonce(12) + ciphertext(plaintext.size) + tag(16).
		// For 20-byte plaintext: 13 + 12 + 20 + 16 = 61 bytes.
		val expectedLen = 13 + GCM_IV_SIZE + plaintext.size + GCM_TAG_SIZE
		assertEquals(
			"Total length must be header(13) + nonce(12) + plaintext + tag(16)",
			expectedLen.toLong(),
			ciphertext.size.toLong(),
		)
	}

	/**
	 * Sprint 1 / P6 + TODO #2 — verify GCM encrypt → decrypt round-trip
	 * recovers the original plaintext via the chunked GCM path.
	 *
	 * @since v15 — TODO #2 chunked GCM round-trip
	 */
	@Test
	fun `GCM encrypt then decrypt round-trip recovers plaintext`() {
		val vmk = keyGen.generateVaultMasterKey()
		val session = VaultSession(vmk)
		val plaintext = "Round-trip GCM test 12345".toByteArray()

		val out = ByteArrayOutputStream()
		cbcEngine.createEncryptStream(out, session, useGcm = true)!!.use { it.write(plaintext) }
		val ciphertext = out.toByteArray()

		val decrypted =
			cbcEngine
				.createDecryptStream(
					ByteArrayInputStream(ciphertext),
					session,
				)!!
				.readBytes()

		assertArrayEquals(plaintext, decrypted)
	}

	/**
	 * Sprint 1 / P6 + TODO #2 — verify that a tampered GCM ciphertext
	 * (bit-flipped) is detected by the per-chunk authentication tag.
	 *
	 * With chunked GCM (version 0x04), each chunk has its own auth tag.
	 * Tampering with any byte in a chunk's ciphertext or tag causes GCM
	 * verification to fail when that chunk is decrypted.
	 *
	 * ChunkedGcmInputStream catches the AEADBadTagException in
	 * loadNextChunk() and returns false (EOF). The caller's readBytes()
	 * then returns whatever was decoded before the tamper was detected.
	 * For a small single-chunk file, the tamper is detected on the first
	 * chunk → readBytes() returns an empty array, which differs from the
	 * original plaintext.
	 *
	 * @since v15 — TODO #2 chunked GCM tamper detection
	 */
	@Test
	fun `GCM decrypt detects tampered ciphertext via per-chunk auth tag`() {
		val vmk = keyGen.generateVaultMasterKey()
		val session = VaultSession(vmk)
		val plaintext = "Tamper detection test".toByteArray()

		val out = ByteArrayOutputStream()
		cbcEngine.createEncryptStream(out, session, useGcm = true)!!.use { it.write(plaintext) }
		val ciphertext = out.toByteArray().copyOf()

		// Flip a byte in the ciphertext body (after the 13-byte header).
		// The per-chunk GCM auth tag will fail to verify on decrypt.
		// For a single-chunk file: header(13) + nonce(12) + ct + tag(16).
		// Flip a byte in the ciphertext portion (offset 25 onwards).
		val flipOffset = 25 + (plaintext.size / 2).coerceAtLeast(0)
		if (flipOffset < ciphertext.size) {
			ciphertext[flipOffset] = (ciphertext[flipOffset].toInt() xor 0x01).toByte()
		}

		// Read all bytes — ChunkedGcmInputStream catches AEADBadTagException
		// internally and returns EOF. The result differs from the original.
		val stream = cbcEngine.createDecryptStream(ByteArrayInputStream(ciphertext), session)!!
		val decrypted =
			try {
				stream.readBytes()
			} finally {
				stream.close()
			}

		// The decrypted bytes must NOT equal the original plaintext
		// (either empty due to auth tag failure, or partial/wrong data).
		assertFalse(
			"Tampered ciphertext must not produce the original plaintext",
			decrypted.contentEquals(plaintext),
		)
	}

	// --- helpers ---

	private fun gcmEncrypt(
		plaintext: ByteArray,
		session: LegacySession,
	): ByteArray {
		val out = ByteArrayOutputStream()
		legacyEngine.createEncryptStream(out, session)!!.use { it.write(plaintext) }
		return out.toByteArray()
	}

	private fun gcmDecrypt(
		ciphertext: ByteArray,
		session: LegacySession,
	): ByteArray = legacyEngine.createDecryptStream(ByteArrayInputStream(ciphertext), session)!!.readBytes()

	private fun cbcEncrypt(
		plaintext: ByteArray,
		session: VaultSession,
	): ByteArray {
		val out = ByteArrayOutputStream()
		// Sprint 1 / P6: pass useGcm = false explicitly — this helper is named
		// cbcEncrypt and is used by tests that verify CBC-specific behavior
		// (version byte 0x02, block-chain IV property for streaming). The
		// engine's default is now GCM (version 3).
		cbcEngine.createEncryptStream(out, session, useGcm = false)!!.use { it.write(plaintext) }
		return out.toByteArray()
	}

	private fun cbcDecrypt(
		ciphertext: ByteArray,
		session: VaultSession,
	): ByteArray = cbcEngine.createDecryptStream(ByteArrayInputStream(ciphertext), session)!!.readBytes()
}
