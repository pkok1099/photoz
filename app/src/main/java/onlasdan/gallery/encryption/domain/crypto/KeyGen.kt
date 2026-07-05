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
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import timber.log.Timber
import java.security.SecureRandom
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject

class KeyGen @Inject constructor() {

    fun generateVaultMasterKey(): SecretKey {
        val keyBytes = ByteArray(32) // 256-bit
        SecureRandom().nextBytes(keyBytes)
        return SecretKeySpec(keyBytes, "AES")
    }

    /**
     * Derive a Key Encryption Key (KEK) from a password.
     *
     * Dispatches to PBKDF2 or Argon2id based on the [kdf] parameter.
     * New vaults use Argon2id (memory-hard, 2025 standard); old vaults
     * stay PBKDF2 (backwards compatible).
     *
     * @param password the user's password
     * @param salt the per-vault salt (random, stored in VaultProtectionParams)
     * @param kdf the KDF algorithm to use
     * @param kdfIterations the time cost (iterations for PBKDF2, passes for Argon2)
     * @param keySize the output key size in bits (typically 256)
     * @param argon2MemoryKB the memory cost for Argon2id in KB (ignored for PBKDF2).
     *   Default 64MB (65536 KB) — OWASP-recommended minimum for interactive use.
     *   Stored in VaultProtectionParams.iv as Base64-encoded 4-byte int.
     * @return the derived KEK as a SecretKey
     *
     * @since v14 — TODO #3 Argon2id support
     */
    fun derivePasswordKeyEncryptionKey(
        password: String,
        salt: ByteArray,
        kdf: Kdf,
        kdfIterations: Int,
        keySize: Int,
        argon2MemoryKB: Int = DEFAULT_ARGON2_MEMORY_KB,
    ): SecretKey {
        return when (kdf) {
            Kdf.PBKDF2WithHmacSHA256 -> {
                val factory = SecretKeyFactory.getInstance(kdf.value)
                val spec = PBEKeySpec(password.toCharArray(), salt, kdfIterations, keySize)
                val keyBytes = factory.generateSecret(spec).encoded
                SecretKeySpec(keyBytes, "AES")
            }
            Kdf.Argon2id -> {
                deriveArgon2idKey(
                    password = password,
                    salt = salt,
                    iterations = kdfIterations,
                    memoryKB = argon2MemoryKB,
                    parallelism = DEFAULT_ARGON2_PARALLELISM,
                    keySizeBits = keySize,
                )
            }
        }
    }

    /**
     * Derive a KEK using Argon2id (memory-hard KDF).
     *
     * Uses Bouncy Castle's Argon2BytesGenerator. Parameters:
     * - type: Argon2id (hybrid — side-channel resistant + memory-hard)
     * - version: 1.3 (latest, fixed in BC)
     * - time cost: [iterations] (typically 3-5 for interactive use)
     * - memory cost: [memoryKB] (typically 65536 = 64MB)
     * - parallelism: [parallelism] (1 for mobile — no benefit from threading)
     * - output: [keySizeBits] / 8 bytes (typically 32 = 256 bits)
     *
     * @since v14 — TODO #3
     */
    private fun deriveArgon2idKey(
        password: String,
        salt: ByteArray,
        iterations: Int,
        memoryKB: Int,
        parallelism: Int,
        keySizeBits: Int,
    ): SecretKey {
        Timber.d("Argon2id: iterations=%d memory=%dKB parallelism=%d keySize=%d",
            iterations, memoryKB, parallelism, keySizeBits)

        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withSalt(salt)
            .withIterations(iterations)
            .withMemoryAsKB(memoryKB)
            .withParallelism(parallelism)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .build()

        val generator = Argon2BytesGenerator()
        generator.init(params)

        val keyBytes = ByteArray(keySizeBits / 8)
        generator.generateBytes(password.toByteArray(Charsets.UTF_8), keyBytes)

        return SecretKeySpec(keyBytes, "AES")
    }

    companion object {
        /** Default Argon2id memory cost: 64MB (OWASP recommended minimum). */
        const val DEFAULT_ARGON2_MEMORY_KB = 65536

        /** Default Argon2id time cost: 3 passes (OWASP recommended minimum). */
        const val DEFAULT_ARGON2_ITERATIONS = 3

        /** Argon2id parallelism: 1 (mobile — no benefit from threading). */
        const val DEFAULT_ARGON2_PARALLELISM = 1
    }
}
