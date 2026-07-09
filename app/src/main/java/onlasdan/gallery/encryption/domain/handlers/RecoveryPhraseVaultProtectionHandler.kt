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

package onlasdan.gallery.encryption.domain.handlers

import onlasdan.gallery.encryption.domain.RecoveryPhraseStore
import onlasdan.gallery.encryption.domain.crypto.Bip39MnemonicGenerator
import onlasdan.gallery.encryption.domain.crypto.IV_SIZE
import onlasdan.gallery.encryption.domain.crypto.KeyGen
import onlasdan.gallery.encryption.domain.crypto.SALT_SIZE
import onlasdan.gallery.encryption.domain.models.Algorithm
import onlasdan.gallery.encryption.domain.models.CreateRequest
import onlasdan.gallery.encryption.domain.models.Kdf
import onlasdan.gallery.encryption.domain.models.RecoveryPhrase
import onlasdan.gallery.encryption.domain.models.UnlockRequest
import onlasdan.gallery.encryption.domain.models.VaultProtection
import onlasdan.gallery.encryption.domain.models.VaultProtectionParams
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import kotlin.io.encoding.Base64

private const val KEK_SIZE = 256
private const val KEK_ITERATIONS = 100_000

class RecoveryPhraseVaultProtectionHandler
	@Inject
	constructor(
		private val keyGen: KeyGen,
		private val mnemonicGenerator: Bip39MnemonicGenerator,
		private val recoveryPhraseStore: RecoveryPhraseStore,
	) : VaultProtectionHandler<UnlockRequest.RecoveryPhrase, CreateRequest.RecoveryPhrase> {
		override suspend fun unlock(
			request: UnlockRequest.RecoveryPhrase,
			protection: VaultProtection,
		): javax.crypto.SecretKey {
			val params = protection.params

			requireNotNull(params.salt)
			requireNotNull(params.iv)
			requireNotNull(params.kdf)
			requireNotNull(params.kdfIterations)

			// F-ENC-005: For Argon2id, the `iv` field contains a 4-byte memory-cost
			// prefix followed by the 16-byte AES wrapping IV. For PBKDF2, the `iv`
			// field is just the 16-byte IV (no prefix). Ported from PasswordVaultProtectionHandler.
			val ivBytes = Base64.decode(params.iv)
			val (aesIv, argon2MemoryKB) =
				when (params.kdf) {
					Kdf.Argon2id -> {
						require(ivBytes.size >= 4 + IV_SIZE) {
							"Argon2id iv field too short: ${ivBytes.size} bytes (need ${4 + IV_SIZE})"
						}
						val memory =
							((ivBytes[0].toInt() and 0xFF) shl 24) or
								((ivBytes[1].toInt() and 0xFF) shl 16) or
								((ivBytes[2].toInt() and 0xFF) shl 8) or
								(ivBytes[3].toInt() and 0xFF)
						val iv = ivBytes.copyOfRange(4, 4 + IV_SIZE)
						iv to memory
					}
					Kdf.PBKDF2WithHmacSHA256 -> {
						ivBytes to onlasdan.gallery.encryption.domain.crypto.KeyGen.DEFAULT_ARGON2_MEMORY_KB
					}
				}

			val kek =
				keyGen.derivePasswordKeyEncryptionKey(
					password = request.phrase.toMnemonicString(),
					salt = Base64.decode(params.salt),
					kdf = params.kdf,
					kdfIterations = params.kdfIterations,
					keySize = params.keySize,
					argon2MemoryKB = argon2MemoryKB,
				)

			val cipher =
				Cipher.getInstance(params.algorithm.value).apply {
					init(Cipher.DECRYPT_MODE, kek, IvParameterSpec(aesIv))
				}

			val vmkBytes = cipher.doFinal(protection.wrappedVMK)
			return SecretKeySpec(vmkBytes, "AES")
		}

		override suspend fun create(request: CreateRequest.RecoveryPhrase): VaultProtection {
			val vmk = request.session.vmk
			val phrase = RecoveryPhrase(mnemonicGenerator.generate(request.wordCount))

			val salt = ByteArray(SALT_SIZE).also { SecureRandom().nextBytes(it) }
			val iv = ByteArray(IV_SIZE).also { SecureRandom().nextBytes(it) }
			// F-ENC-004: Use Argon2id (was PBKDF2) — matches PasswordVaultProtectionHandler.
			// Recovery phrase is the "strong" unlock method; it should use the strongest KDF.
			val kdf = Kdf.Argon2id
			val kdfIterations = onlasdan.gallery.encryption.domain.crypto.KeyGen.DEFAULT_ARGON2_ITERATIONS
			val argon2MemoryKB = onlasdan.gallery.encryption.domain.crypto.KeyGen.DEFAULT_ARGON2_MEMORY_KB

			// F-ENC-004: 4-byte memory-cost prefix + 16-byte IV (same encoding as Password).
			val ivWithMemory = ByteArray(4 + IV_SIZE)
			ivWithMemory[0] = (argon2MemoryKB ushr 24).toByte()
			ivWithMemory[1] = (argon2MemoryKB ushr 16).toByte()
			ivWithMemory[2] = (argon2MemoryKB ushr 8).toByte()
			ivWithMemory[3] = argon2MemoryKB.toByte()
			System.arraycopy(iv, 0, ivWithMemory, 4, IV_SIZE)

			val params =
				VaultProtectionParams(
					salt = Base64.encode(salt),
					iv = Base64.encode(ivWithMemory),
					kdf = kdf,
					kdfIterations = kdfIterations,
					algorithm = Algorithm.AesCbcPkcs7Padding,
					keySize = KEK_SIZE,
				)

			val kek =
				keyGen.derivePasswordKeyEncryptionKey(
					password = phrase.toMnemonicString(),
					salt = salt,
					kdf = kdf,
					kdfIterations = kdfIterations,
					keySize = KEK_SIZE,
					argon2MemoryKB = argon2MemoryKB,
				)

			val cipher =
				Cipher.getInstance(params.algorithm.value).apply {
					init(Cipher.ENCRYPT_MODE, kek, IvParameterSpec(iv))
				}

			val wrappedVmk = cipher.doFinal(vmk.encoded)

			recoveryPhraseStore.store(phrase, request.session)

			return VaultProtection(
				id = UUID.randomUUID().toString(),
				type = request.protectionType,
				wrappedVMK = wrappedVmk,
				params = params,
			)
		}

		override suspend fun canMigrate(): Boolean = false

		override suspend fun migrate(request: UnlockRequest.RecoveryPhrase): VaultProtection =
			throw UnsupportedOperationException("RecoveryPhrase protection has no legacy migration path")

		override suspend fun reset() {
			recoveryPhraseStore.clear()
		}
	}
