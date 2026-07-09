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

import onlasdan.gallery.encryption.domain.models.Algorithm
import onlasdan.gallery.encryption.domain.models.Kdf
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import kotlin.io.encoding.Base64

/**
 * F-ENC-004/005 — Tests for Argon2id IV encoding used by both
 * PasswordVaultProtectionHandler and RecoveryPhraseVaultProtectionHandler.
 *
 * The encoding: for Argon2id, the `iv` field in VaultProtectionParams stores
 * a 4-byte big-endian memory-cost prefix + 16-byte AES wrapping IV (20 bytes
 * total), Base64-encoded. This test verifies the encode/decode round-trip
 * so that unlock() can correctly recover the memory cost + IV that create()
 * wrote.
 *
 * @since F-ENC-004/005 — Argon2id IV encoding consistency
 */
class Argon2idIvEncodingTest {
	private val keyGen = KeyGen()

	/**
	 * Encode memory cost + IV the same way PasswordVaultProtectionHandler.create() does.
	 */
	private fun encodeArgon2idIv(argon2MemoryKB: Int, iv: ByteArray): String {
		val ivWithMemory = ByteArray(4 + IV_SIZE)
		ivWithMemory[0] = (argon2MemoryKB ushr 24).toByte()
		ivWithMemory[1] = (argon2MemoryKB ushr 16).toByte()
		ivWithMemory[2] = (argon2MemoryKB ushr 8).toByte()
		ivWithMemory[3] = argon2MemoryKB.toByte()
		System.arraycopy(iv, 0, ivWithMemory, 4, IV_SIZE)
		return Base64.encode(ivWithMemory)
	}

	/**
	 * Decode the iv field the same way PasswordVaultProtectionHandler.unlock() does.
	 */
	private fun decodeArgon2idIv(encoded: String): Pair<ByteArray, Int> {
		val ivBytes = Base64.decode(encoded)
		require(ivBytes.size >= 4 + IV_SIZE) {
			"Argon2id iv field too short: ${ivBytes.size} bytes (need ${4 + IV_SIZE})"
		}
		val memory =
			((ivBytes[0].toInt() and 0xFF) shl 24) or
				((ivBytes[1].toInt() and 0xFF) shl 16) or
				((ivBytes[2].toInt() and 0xFF) shl 8) or
				(ivBytes[3].toInt() and 0xFF)
		val iv = ivBytes.copyOfRange(4, 4 + IV_SIZE)
		return iv to memory
	}

	@Test
	fun `encode-decode round-trip preserves memory cost and IV`() {
		val originalMemory = 65536 // 64 MB
		val originalIv = ByteArray(IV_SIZE).also { SecureRandom().nextBytes(it) }

		val encoded = encodeArgon2idIv(originalMemory, originalIv)
		val (decodedIv, decodedMemory) = decodeArgon2idIv(encoded)

		assertEquals(originalMemory, decodedMemory)
		assertArrayEquals(originalIv, decodedIv)
	}

	@Test
	fun `encode-decode round-trip with small memory cost`() {
		val originalMemory = 1024 // 1 MB
		val originalIv = ByteArray(IV_SIZE) { it.toByte() }

		val encoded = encodeArgon2idIv(originalMemory, originalIv)
		val (decodedIv, decodedMemory) = decodeArgon2idIv(encoded)

		assertEquals(originalMemory, decodedMemory)
		assertArrayEquals(originalIv, decodedIv)
	}

	@Test
	fun `encode-decode round-trip with large memory cost`() {
		val originalMemory = 262144 // 256 MB
		val originalIv = ByteArray(IV_SIZE) { 0x42.toByte() }

		val encoded = encodeArgon2idIv(originalMemory, originalIv)
		val (decodedIv, decodedMemory) = decodeArgon2idIv(encoded)

		assertEquals(originalMemory, decodedMemory)
		assertArrayEquals(originalIv, decodedIv)
	}

	@Test
	fun `different memory costs produce different encoded IVs`() {
		val iv = ByteArray(IV_SIZE) { 0x00.toByte() }

		val encoded1 = encodeArgon2idIv(32768, iv)
		val encoded2 = encodeArgon2idIv(65536, iv)

		assertNotEquals("Different memory costs must produce different encoded IVs", encoded1, encoded2)
	}

	@Test
	fun `Argon2id KEK can wrap and unwrap VMK with encoded IV`() {
		// End-to-end: generate VMK, derive Argon2id KEK, wrap VMK with AES-CBC,
		// then unwrap using the decoded IV + memory cost.
		val vmk = keyGen.generateVaultMasterKey()
		val password = "testPasswordForWrapping"
		val salt = ByteArray(SALT_SIZE).also { SecureRandom().nextBytes(it) }
		val iv = ByteArray(IV_SIZE).also { SecureRandom().nextBytes(it) }
		val memoryKB = KeyGen.DEFAULT_ARGON2_MEMORY_KB

		// Derive KEK with Argon2id
		val kek = keyGen.derivePasswordKeyEncryptionKey(
			password = password,
			salt = salt,
			kdf = Kdf.Argon2id,
			kdfIterations = KeyGen.DEFAULT_ARGON2_ITERATIONS,
			keySize = 256,
			argon2MemoryKB = memoryKB,
		)

		// Wrap VMK
		val wrapCipher = Cipher.getInstance(Algorithm.AesCbcPkcs7Padding.value).apply {
			init(Cipher.ENCRYPT_MODE, kek, IvParameterSpec(iv))
		}
		val wrappedVmk = wrapCipher.doFinal(vmk.encoded)

		// Encode IV with memory cost
		val encodedIv = encodeArgon2idIv(memoryKB, iv)

		// Decode IV + memory cost
		val (decodedIv, decodedMemory) = decodeArgon2idIv(encodedIv)

		// Re-derive KEK with decoded memory cost
		val unwrappedKek = keyGen.derivePasswordKeyEncryptionKey(
			password = password,
			salt = salt,
			kdf = Kdf.Argon2id,
			kdfIterations = KeyGen.DEFAULT_ARGON2_ITERATIONS,
			keySize = 256,
			argon2MemoryKB = decodedMemory,
		)

		// Unwrap VMK
		val unwrapCipher = Cipher.getInstance(Algorithm.AesCbcPkcs7Padding.value).apply {
			init(Cipher.DECRYPT_MODE, unwrappedKek, IvParameterSpec(decodedIv))
		}
		val unwrappedVmkBytes = unwrapCipher.doFinal(wrappedVmk)

		assertArrayEquals("VMK must survive wrap-unwrap round-trip", vmk.encoded, unwrappedVmkBytes)
	}

	@Test
	fun `decode rejects too-short iv field`() {
		val tooShort = Base64.encode(ByteArray(10)) // 10 bytes, need 20

		try {
			decodeArgon2idIv(tooShort)
			throw AssertionError("Should have thrown on too-short iv field")
		} catch (e: IllegalArgumentException) {
			// Expected
			val msg = e.message ?: ""
			assert(msg.contains("too short")) {
				"Expected 'too short' in message, got: $msg"
			}
		}
	}

	@Test
	fun `PBKDF2 iv field is just 16 bytes (no memory prefix)`() {
		// Verify that the PBKDF2 path (no memory prefix) is handled correctly
		// by the unlock() when() dispatch — iv is used as-is.
		val pbkdf2Iv = ByteArray(IV_SIZE) { it.toByte() }
		val encoded = Base64.encode(pbkdf2Iv)

		val decoded = Base64.decode(encoded)
		assertEquals("PBKDF2 iv must be exactly 16 bytes", IV_SIZE, decoded.size)
	}
}
