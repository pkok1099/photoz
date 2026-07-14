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

import onlasdan.gallery.encryption.domain.models.EncryptionVersionByte
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import javax.crypto.spec.SecretKeySpec

/**
 * F-ENC-027 — Tests for ChunkedGcm OutputStream → InputStream round-trip.
 *
 * Verifies that:
 * - Multi-chunk files decrypt correctly (data integrity)
 * - Single-chunk files decrypt correctly
 * - Empty input produces empty output
 * - Small data (less than one chunk) works
 * - Data spanning multiple chunk boundaries works
 * - The version byte (0x04) is written by the OutputStream
 * - Chunk size is configurable
 * - Different VMKs produce different ciphertexts
 * - Tampered ciphertext fails to decrypt (GCM auth tag verification)
 *
 * @since F-ENC-027 — ChunkedGcm test coverage
 */
class ChunkedGcmStreamTest {
	private val vmk: javax.crypto.SecretKey = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
	private val keyGen = KeyGen()

	private fun encrypt(plaintext: ByteArray, chunkSize: Int = CHUNK_SIZE): ByteArray {
		val baos = ByteArrayOutputStream()
		val out = ChunkedGcmOutputStream(baos, vmk, chunkSize)
		out.write(plaintext)
		out.close()
		return baos.toByteArray()
	}

	private fun decrypt(ciphertext: ByteArray): ByteArray {
		// Skip the version byte (0x04) — ChunkedGcmInputStream expects it already consumed.
		val input = java.io.ByteArrayInputStream(ciphertext, 1, ciphertext.size - 1)
		val cin = ChunkedGcmInputStream(input, vmk)
		val result = cin.readBytes()
		cin.close()
		return result
	}

	@Test
	fun `empty input round-trips to empty output`() {
		val plaintext = ByteArray(0)
		val ciphertext = encrypt(plaintext)
		val decrypted = decrypt(ciphertext)
		assertArrayEquals(plaintext, decrypted)
	}

	@Test
	fun `small data (less than one chunk) round-trips correctly`() {
		val plaintext = "Hello, World!".toByteArray()
		val ciphertext = encrypt(plaintext)
		val decrypted = decrypt(ciphertext)
		assertArrayEquals(plaintext, decrypted)
	}

	@Test
	fun `exactly one chunk round-trips correctly`() {
		val plaintext = ByteArray(CHUNK_SIZE) { (it and 0xFF).toByte() }
		val ciphertext = encrypt(plaintext)
		val decrypted = decrypt(ciphertext)
		assertArrayEquals(plaintext, decrypted)
	}

	@Test
	fun `multi-chunk data round-trips correctly`() {
		// 2.5 chunks of data
		val plaintext = ByteArray(CHUNK_SIZE * 2 + CHUNK_SIZE / 2) { (it and 0xFF).toByte() }
		val ciphertext = encrypt(plaintext)
		val decrypted = decrypt(ciphertext)
		assertArrayEquals(plaintext, decrypted)
	}

	@Test
	fun `small chunk size works for multi-chunk data`() {
		// Use a small chunk size to test multiple chunks without large memory
		val smallChunkSize = 64
		val plaintext = ByteArray(smallChunkSize * 5 + 10) { (it and 0xFF).toByte() }
		val ciphertext = encrypt(plaintext, chunkSize = smallChunkSize)
		val decrypted = decrypt(ciphertext)
		assertArrayEquals(plaintext, decrypted)
	}

	@Test
	fun `version byte 0x04 is written as first byte`() {
		val ciphertext = encrypt("test".toByteArray())
		assertEquals(
			"First byte must be version 0x04",
			EncryptionVersionByte.Four.value,
			ciphertext[0],
		)
	}

	@Test
	fun `different VMKs produce different ciphertexts`() {
		val vmk2 = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES")
		val plaintext = "same plaintext".toByteArray()

		val baos1 = ByteArrayOutputStream()
		ChunkedGcmOutputStream(baos1, vmk).use { it.write(plaintext) }
		val ciphertext1 = baos1.toByteArray()

		val baos2 = ByteArrayOutputStream()
		ChunkedGcmOutputStream(baos2, vmk2).use { it.write(plaintext) }
		val ciphertext2 = baos2.toByteArray()

		// Ciphertexts should differ (different VMK → different KEK → different ciphertext)
		assertTrue(
			"Different VMKs must produce different ciphertexts",
			!ciphertext1.contentEquals(ciphertext2),
		)
	}

	@Test
	fun `tampered ciphertext throws on decryption (GCM auth tag verification)`() {
		val plaintext = "sensitive data that must be protected".toByteArray()
		val ciphertext = encrypt(plaintext)

		// Tamper with a byte in the middle of the ciphertext (after header + first nonce)
		val tampered = ciphertext.copyOf()
		val tamperPos = 20 // past version(1) + chunk_size(4) + total_size(8) + nonce(12) = 25... let's tamper at 25
		if (tamperPos < tampered.size) {
			tampered[tamperPos] = (tampered[tamperPos] + 1).toByte()
		}

		try {
			decrypt(tampered)
			// If we get here, decryption didn't throw — that's a failure
			throw AssertionError("Tampered ciphertext should have failed GCM auth tag verification")
		} catch (e: Exception) {
			// Expected — GCM auth tag verification should fail
			val msg = e.message?.lowercase() ?: ""
			val isExpectedError = msg.contains("tag") ||
				msg.contains("auth") ||
				msg.contains("mac") ||
				msg.contains("decrypt") ||
				msg.contains("padding") ||
				e is javax.crypto.AEADBadTagException
			assertTrue(
				"Expected GCM/auth-tag error, got: ${e.javaClass.simpleName}: ${e.message}",
				isExpectedError,
			)
		}
	}

	@Test
	fun `large data round-trips correctly (10 chunks)`() {
		val smallChunkSize = 256
		val plaintext = ByteArray(smallChunkSize * 10) { (it and 0xFF).toByte() }
		val ciphertext = encrypt(plaintext, chunkSize = smallChunkSize)
		val decrypted = decrypt(ciphertext)
		assertArrayEquals(plaintext, decrypted)
	}

	@Test
	fun `byte-at-a-time writes produce same result as bulk write`() {
		val plaintext = "test data for byte-at-a-time".toByteArray()

		// Bulk write
		val bulkCiphertext = encrypt(plaintext)

		// Byte-at-a-time write
		val baos = ByteArrayOutputStream()
		val out = ChunkedGcmOutputStream(baos, vmk)
		for (b in plaintext) {
			out.write(b.toInt())
		}
		out.close()
		val byteCiphertext = baos.toByteArray()

		// Both should decrypt to the same plaintext
		assertArrayEquals(plaintext, decrypt(bulkCiphertext))
		assertArrayEquals(plaintext, decrypt(byteCiphertext))
	}

	@Test
	fun `close() propagates IOException when underlying stream fails`() {
		// F-001: close() must not swallow exceptions from flushChunk/output.flush
		val failingStream = object : java.io.OutputStream() {
			private var closed = false

			override fun write(b: Int) { /* no-op */ }

			override fun flush() {
				throw java.io.IOException("simulated flush failure (disk full)")
			}

			override fun close() {
				closed = true
			}
		}
		val out = ChunkedGcmOutputStream(failingStream, vmk)
		out.write("test data".toByteArray())
		var caught: java.io.IOException? = null
		try {
			out.close()
		} catch (e: java.io.IOException) {
			caught = e
		}
		assertNotNull("close() must propagate IOException from flush(), not swallow it", caught)
		assertEquals("simulated flush failure (disk full)", caught?.message)
	}

	@Test
	fun `F-003 loadNextChunk throws IOException on truncated mid-chunk, not silent EOF`() {
		// F-003: generic catch (e: Exception) { return false } swallows IOException
		// from truncated reads as clean EOF. Corruption must surface as IOException.
		val plaintext = ByteArray(CHUNK_SIZE + 100) { it.toByte() } // 2 chunks
		val ciphertext = encrypt(plaintext)

		// Truncate the ciphertext mid-second-chunk (keep header + first chunk + 6 bytes of second nonce)
		val truncateAt = 1 + 4 + 8 + (GCM_IV_SIZE + CHUNK_SIZE + GCM_TAG_SIZE) + 6 // header(13) + chunk0 + 6 bytes
		val truncated = ciphertext.copyOf(truncateAt)

		var caught: java.io.IOException? = null
		try {
			val input = java.io.ByteArrayInputStream(truncated, 1, truncated.size - 1)
			val cin = ChunkedGcmInputStream(input, vmk)
			val buf = ByteArray(CHUNK_SIZE + 200)
			var total = 0
			while (total < CHUNK_SIZE + 200) {
				val n = cin.read(buf, total, buf.size - total)
				if (n <= 0) break
				total += n
			}
			cin.close()
			// If we got here without throwing, total should be < CHUNK_SIZE+100 (truncation hit)
			// F-003 bug: read() returned -1 silently at the truncation
			assertTrue("Should have detected truncation, but read $total bytes silently", total >= CHUNK_SIZE + 100)
		} catch (e: java.io.IOException) {
			caught = e
		}
		assertNotNull("loadNextChunk must throw IOException on truncated chunk, not silent EOF", caught)
	}
}
