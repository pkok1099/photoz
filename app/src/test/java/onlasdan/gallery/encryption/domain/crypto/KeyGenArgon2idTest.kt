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

import onlasdan.gallery.encryption.domain.models.Kdf
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * F-ENC-026 — Tests for Argon2id KDF in [KeyGen].
 *
 * Verifies that:
 * - Argon2id produces the correct key length (32 bytes = 256 bits)
 * - Argon2id is deterministic (same input → same output)
 * - Different salts produce different keys
 * - Different passwords produce different keys
 * - Different memory costs produce different keys
 * - PBKDF2 and Argon2id produce different keys for the same input
 *
 * These tests guard against regressions in the Argon2id parameter encoding
 * (4-byte memory-cost prefix in IV field) that would cause unlock failures.
 *
 * @since F-ENC-026 — Argon2id test coverage
 */
class KeyGenArgon2idTest {
	private val keyGen = KeyGen()

	@Test
	fun `Argon2id produces 32-byte key for AES-256`() {
		val salt = ByteArray(16)
		val kek = keyGen.derivePasswordKeyEncryptionKey(
			password = "testPassword123",
			salt = salt,
			kdf = Kdf.Argon2id,
			kdfIterations = KeyGen.DEFAULT_ARGON2_ITERATIONS,
			keySize = 256,
			argon2MemoryKB = KeyGen.DEFAULT_ARGON2_MEMORY_KB,
		)

		assertEquals(32, kek.encoded.size)
	}

	@Test
	fun `Argon2id is deterministic with same inputs`() {
		val salt = ByteArray(16) { it.toByte() }
		val password = "testPassword123"

		val kek1 = keyGen.derivePasswordKeyEncryptionKey(
			password = password,
			salt = salt,
			kdf = Kdf.Argon2id,
			kdfIterations = KeyGen.DEFAULT_ARGON2_ITERATIONS,
			keySize = 256,
			argon2MemoryKB = KeyGen.DEFAULT_ARGON2_MEMORY_KB,
		)
		val kek2 = keyGen.derivePasswordKeyEncryptionKey(
			password = password,
			salt = salt,
			kdf = Kdf.Argon2id,
			kdfIterations = KeyGen.DEFAULT_ARGON2_ITERATIONS,
			keySize = 256,
			argon2MemoryKB = KeyGen.DEFAULT_ARGON2_MEMORY_KB,
		)

		assertArrayEquals(kek1.encoded, kek2.encoded)
	}

	@Test
	fun `Argon2id with different salts produces different keys`() {
		val salt1 = ByteArray(16) { 0x01 }
		val salt2 = ByteArray(16) { 0x02 }
		val password = "testPassword123"

		val kek1 = keyGen.derivePasswordKeyEncryptionKey(
			password = password,
			salt = salt1,
			kdf = Kdf.Argon2id,
			kdfIterations = KeyGen.DEFAULT_ARGON2_ITERATIONS,
			keySize = 256,
			argon2MemoryKB = KeyGen.DEFAULT_ARGON2_MEMORY_KB,
		)
		val kek2 = keyGen.derivePasswordKeyEncryptionKey(
			password = password,
			salt = salt2,
			kdf = Kdf.Argon2id,
			kdfIterations = KeyGen.DEFAULT_ARGON2_ITERATIONS,
			keySize = 256,
			argon2MemoryKB = KeyGen.DEFAULT_ARGON2_MEMORY_KB,
		)

		assertNotEquals("Different salts must produce different keys", kek1.encoded.toList(), kek2.encoded.toList())
	}

	@Test
	fun `Argon2id with different passwords produces different keys`() {
		val salt = ByteArray(16)

		val kek1 = keyGen.derivePasswordKeyEncryptionKey(
			password = "password1",
			salt = salt,
			kdf = Kdf.Argon2id,
			kdfIterations = KeyGen.DEFAULT_ARGON2_ITERATIONS,
			keySize = 256,
			argon2MemoryKB = KeyGen.DEFAULT_ARGON2_MEMORY_KB,
		)
		val kek2 = keyGen.derivePasswordKeyEncryptionKey(
			password = "password2",
			salt = salt,
			kdf = Kdf.Argon2id,
			kdfIterations = KeyGen.DEFAULT_ARGON2_ITERATIONS,
			keySize = 256,
			argon2MemoryKB = KeyGen.DEFAULT_ARGON2_MEMORY_KB,
		)

		assertNotEquals("Different passwords must produce different keys", kek1.encoded.toList(), kek2.encoded.toList())
	}

	@Test
	fun `Argon2id with different memory costs produces different keys`() {
		val salt = ByteArray(16)
		val password = "testPassword123"

		val kek1 = keyGen.derivePasswordKeyEncryptionKey(
			password = password,
			salt = salt,
			kdf = Kdf.Argon2id,
			kdfIterations = KeyGen.DEFAULT_ARGON2_ITERATIONS,
			keySize = 256,
			argon2MemoryKB = 32768, // 32 MB
		)
		val kek2 = keyGen.derivePasswordKeyEncryptionKey(
			password = password,
			salt = salt,
			kdf = Kdf.Argon2id,
			kdfIterations = KeyGen.DEFAULT_ARGON2_ITERATIONS,
			keySize = 256,
			argon2MemoryKB = 65536, // 64 MB
		)

		assertNotEquals("Different memory costs must produce different keys", kek1.encoded.toList(), kek2.encoded.toList())
	}

	@Test
	fun `Argon2id and PBKDF2 produce different keys for same input`() {
		val salt = ByteArray(16)
		val password = "testPassword123"

		val argon2idKek = keyGen.derivePasswordKeyEncryptionKey(
			password = password,
			salt = salt,
			kdf = Kdf.Argon2id,
			kdfIterations = KeyGen.DEFAULT_ARGON2_ITERATIONS,
			keySize = 256,
			argon2MemoryKB = KeyGen.DEFAULT_ARGON2_MEMORY_KB,
		)
		val pbkdf2Kek = keyGen.derivePasswordKeyEncryptionKey(
			password = password,
			salt = salt,
			kdf = Kdf.PBKDF2WithHmacSHA256,
			kdfIterations = 100_000,
			keySize = 256,
		)

		assertNotEquals(
			"Argon2id and PBKDF2 must produce different keys for the same input",
			argon2idKek.encoded.toList(),
			pbkdf2Kek.encoded.toList(),
		)
	}

	@Test
	fun `Argon2id with low memory cost still works (for fast tests)`() {
		val salt = ByteArray(16)
		val kek = keyGen.derivePasswordKeyEncryptionKey(
			password = "test",
			salt = salt,
			kdf = Kdf.Argon2id,
			kdfIterations = 1,
			keySize = 256,
			argon2MemoryKB = 1024, // 1 MB — fast for testing
		)

		assertEquals(32, kek.encoded.size)
	}

	@Test
	fun `generateVaultMasterKey produces 32-byte AES key`() {
		val vmk = keyGen.generateVaultMasterKey()

		assertEquals("AES", vmk.algorithm)
		assertEquals(32, vmk.encoded.size)
	}

	@Test
	fun `generateVaultMasterKey produces different keys on each call`() {
		val vmk1 = keyGen.generateVaultMasterKey()
		val vmk2 = keyGen.generateVaultMasterKey()

		assertNotEquals(
			"VMK must be random — two calls must produce different keys",
			vmk1.encoded.toList(),
			vmk2.encoded.toList(),
		)
	}
}
