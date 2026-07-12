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

import onlasdan.gallery.encryption.domain.crypto.IV_SIZE
import onlasdan.gallery.encryption.domain.crypto.KeyGen
import onlasdan.gallery.encryption.domain.crypto.SALT_SIZE
import onlasdan.gallery.encryption.domain.models.Algorithm
import onlasdan.gallery.encryption.domain.models.CreateRequest
import onlasdan.gallery.encryption.domain.models.Kdf
import onlasdan.gallery.encryption.domain.models.UnlockRequest
import onlasdan.gallery.encryption.domain.models.VaultProtection
import onlasdan.gallery.encryption.domain.models.VaultProtectionParams
import onlasdan.gallery.encryption.domain.models.VaultProtectionType
import onlasdan.gallery.settings.data.Config
import org.mindrot.jbcrypt.BCrypt
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import kotlin.io.encoding.Base64

private const val KEK_SIZE = 256
private const val KEK_ITERATIONS = 100_000

class PasswordVaultProtectionHandler
	@Inject
	constructor(
		private val keyGen: KeyGen,
		private val config: Config,
	) : VaultProtectionHandler<UnlockRequest.Password, CreateRequest.Password> {
		override suspend fun unlock(
			request: UnlockRequest.Password,
			protection: VaultProtection,
		): SecretKey {
			val params = protection.params

			requireNotNull(params.salt)
			requireNotNull(params.iv)
			requireNotNull(params.kdf)
			requireNotNull(params.kdfIterations)
			requireNotNull(params.keySize)
			requireNotNull(params.algorithm)

			// (roadmap #3) — For Argon2id, the `iv` field contains a 4-byte memory cost
			// prefix followed by the 16-byte AES wrapping IV. For PBKDF2, the `iv`
			// field is just the 16-byte IV (no prefix).
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
					password = request.password,
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

		override suspend fun create(request: CreateRequest.Password): VaultProtection {
			val salt = ByteArray(SALT_SIZE).also { SecureRandom().nextBytes(it) }
			val iv = ByteArray(IV_SIZE).also { SecureRandom().nextBytes(it) }

			// (roadmap #3) — New vaults use Argon2id (memory-hard KDF, 2025 standard).
			// Old vaults stay PBKDF2 (backwards compatible — unlock dispatches by kdf type).
			val kdf = Kdf.Argon2id
			val kdfIterations = onlasdan.gallery.encryption.domain.crypto.KeyGen.DEFAULT_ARGON2_ITERATIONS
			val argon2MemoryKB = onlasdan.gallery.encryption.domain.crypto.KeyGen.DEFAULT_ARGON2_MEMORY_KB

			// For Argon2id, the `iv` field stores the AES wrapping IV (still needed).
			// The memory cost is encoded in the `iv` field as well — we prepend
			// a 4-byte big-endian int before the IV bytes, Base64-encoded together.
			// This reuses the existing column without schema changes.
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

			val vmk = keyGen.generateVaultMasterKey()

			val kek =
				keyGen.derivePasswordKeyEncryptionKey(
					password = request.password,
					salt = salt,
					kdf = kdf,
					kdfIterations = kdfIterations,
					keySize = params.keySize,
					argon2MemoryKB = argon2MemoryKB,
				)

			val cipher =
				Cipher.getInstance(params.algorithm.value).apply {
					init(Cipher.ENCRYPT_MODE, kek, IvParameterSpec(iv))
				}

			val wrappedVmk = cipher.doFinal(vmk.encoded)

			return VaultProtection(
				id = UUID.randomUUID().toString(),
				type = request.protectionType,
				wrappedVMK = wrappedVmk,
				params = params,
			)
		}

		/**
		 * Wrap an EXISTING VMK (from a recovered session) with the given password.
		 * Used after login-branch unlock to persist a local Password protection
		 * so [VaultService.canUnlock] returns true on subsequent app opens.
		 *
		 * Unlike [create] which generates a new VMK, this wraps the provided [vmk].
		 *
		 * @since data-loss fix — login branch must persist local Password protection
		 */
		fun wrapExistingVmk(
			password: String,
			vmk: javax.crypto.SecretKey,
		): VaultProtection {
			val salt = ByteArray(SALT_SIZE).also { SecureRandom().nextBytes(it) }
			val iv = ByteArray(IV_SIZE).also { SecureRandom().nextBytes(it) }
			// (roadmap #3) — use Argon2id for new wrappings
			val kdf = Kdf.Argon2id
			val kdfIterations = onlasdan.gallery.encryption.domain.crypto.KeyGen.DEFAULT_ARGON2_ITERATIONS
			val argon2MemoryKB = onlasdan.gallery.encryption.domain.crypto.KeyGen.DEFAULT_ARGON2_MEMORY_KB

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
					password = password,
					salt = salt,
					kdf = kdf,
					kdfIterations = kdfIterations,
					keySize = params.keySize,
					argon2MemoryKB = argon2MemoryKB,
				)

			val cipher =
				Cipher.getInstance(params.algorithm.value).apply {
					init(Cipher.ENCRYPT_MODE, kek, IvParameterSpec(iv))
				}

			val wrappedVmk = cipher.doFinal(vmk.encoded)

			return VaultProtection(
				id = UUID.randomUUID().toString(),
				type = VaultProtectionType.Password,
				wrappedVMK = wrappedVmk,
				params = params,
			)
		}

		@Suppress("DEPRECATION") // legacyPasswordHash/legacyUserSalt — only for migration from 1.x/2.x
		override suspend fun canMigrate(): Boolean {
			// 1.x.x users have no legacyUserSalt — migrate() handles that case by generating a fresh
			// VMK. Returning true when only legacyPasswordHash is present covers both 1.x.x and 2.x.x.
			return config.legacyPasswordHash.orEmpty().isNotEmpty()
		}

		@Suppress("DEPRECATION") // legacyPasswordHash/legacyUserSalt — only for migration from 1.x/2.x
		override suspend fun migrate(request: UnlockRequest.Password): VaultProtection {
			require(BCrypt.checkpw(request.password, config.legacyPasswordHash))

			val vmk =
				if (config.legacyUserSalt.isNullOrEmpty()) {
					// Migrating from 1.x.x
					keyGen.generateVaultMasterKey()
				} else {
					// Migrating from 2.x.x
					val vmkSalt = Base64.decode(config.legacyUserSalt!!)

					keyGen.derivePasswordKeyEncryptionKey(
						password = request.password,
						salt = vmkSalt,
						kdf = Kdf.PBKDF2WithHmacSHA256,
						kdfIterations = KEK_ITERATIONS,
						keySize = KEK_SIZE,
					)
				}

			val salt = ByteArray(SALT_SIZE).also { SecureRandom().nextBytes(it) }
			val iv = ByteArray(IV_SIZE).also { SecureRandom().nextBytes(it) }

			val params =
				VaultProtectionParams(
					salt = Base64.encode(salt),
					iv = Base64.encode(iv),
					kdf = Kdf.PBKDF2WithHmacSHA256,
					kdfIterations = KEK_ITERATIONS,
					algorithm = Algorithm.AesCbcPkcs7Padding,
					keySize = KEK_SIZE,
				)

			val kek =
				keyGen.derivePasswordKeyEncryptionKey(
					password = request.password,
					salt = salt,
					kdf = params.kdf!!,
					kdfIterations = params.kdfIterations!!,
					keySize = params.keySize,
				)

			val cipher =
				Cipher.getInstance(params.algorithm.value).apply {
					init(Cipher.ENCRYPT_MODE, kek, IvParameterSpec(iv))
				}

			val wrappedVmk = cipher.doFinal(vmk.encoded)

			return VaultProtection(
				id = UUID.randomUUID().toString(),
				type = request.protectionType,
				wrappedVMK = wrappedVmk,
				params = params,
			)
		}

		override suspend fun reset() {}
	}
