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

package onlasdan.gallery.encryption.domain

import onlasdan.gallery.encryption.domain.crypto.IV_SIZE
import onlasdan.gallery.encryption.domain.crypto.KeyGen
import onlasdan.gallery.encryption.domain.crypto.SALT_SIZE
import onlasdan.gallery.encryption.domain.models.Kdf
import onlasdan.gallery.encryption.domain.models.VaultProtectionType
import timber.log.Timber
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.inject.Inject
import kotlin.io.encoding.Base64

class ChangePasswordUseCase
	@Inject
	constructor(
		private val vaultProtectionRepository: VaultProtectionRepository,
		private val sessionRepository: SessionRepository,
		private val keyGen: KeyGen,
	) {
		suspend operator fun invoke(newPassword: String) =
			runCatching {
				val currentProtection = vaultProtectionRepository.getProtection(VaultProtectionType.Password)
				requireNotNull(currentProtection)

				val session = sessionRepository.require()

				val kdf = requireNotNull(currentProtection.params.kdf) { "Password protection is missing KDF" }
				val kdfIterations =
					requireNotNull(currentProtection.params.kdfIterations) {
						"Password protection is missing KDF iterations"
					}

				val newSalt = ByteArray(SALT_SIZE).also { SecureRandom().nextBytes(it) }
				val newIv = ByteArray(IV_SIZE).also { SecureRandom().nextBytes(it) }

				// ─── TODO #3 — Argon2id IV encoding ──────────────────────────────
				// For Argon2id, the `iv` field stores a 4-byte big-endian memory cost
				// prefix followed by the 16-byte AES wrapping IV (total 20 bytes).
				// For PBKDF2, the `iv` field is just the 16-byte IV.
				// See PasswordVaultProtectionHandler.create()/wrapExistingVmk() for
				// the encoding logic — we must match it here so unlock() can decode.
				val argon2MemoryKB =
					when (kdf) {
						Kdf.Argon2id -> KeyGen.DEFAULT_ARGON2_MEMORY_KB
						Kdf.PBKDF2WithHmacSHA256 -> KeyGen.DEFAULT_ARGON2_MEMORY_KB // unused for PBKDF2
					}
				val ivFieldBytes =
					when (kdf) {
						Kdf.Argon2id -> {
							val withMemory = ByteArray(4 + IV_SIZE)
							withMemory[0] = (argon2MemoryKB ushr 24).toByte()
							withMemory[1] = (argon2MemoryKB ushr 16).toByte()
							withMemory[2] = (argon2MemoryKB ushr 8).toByte()
							withMemory[3] = argon2MemoryKB.toByte()
							System.arraycopy(newIv, 0, withMemory, 4, IV_SIZE)
							withMemory
						}
						Kdf.PBKDF2WithHmacSHA256 -> newIv
					}

				val newKek =
					keyGen.derivePasswordKeyEncryptionKey(
						password = newPassword,
						salt = newSalt,
						kdf = kdf,
						kdfIterations = kdfIterations,
						keySize = currentProtection.params.keySize,
						argon2MemoryKB = argon2MemoryKB,
					)

				val cipher =
					Cipher.getInstance(currentProtection.params.algorithm.value).apply {
						init(Cipher.ENCRYPT_MODE, newKek, IvParameterSpec(newIv))
					}

				val newWrappedVmk = cipher.doFinal(session.vmk.encoded)

				val newParams =
					currentProtection.params.copy(
						salt = Base64.encode(newSalt),
						iv = Base64.encode(ivFieldBytes),
					)

				val newProtection =
					currentProtection.copy(
						wrappedVMK = newWrappedVmk,
						params = newParams,
					)

				Timber.d("ChangePasswordUseCase: re-wrapped VMK with kdf=%s", kdf)
				vaultProtectionRepository.updateProtection(newProtection)
			}
	}
