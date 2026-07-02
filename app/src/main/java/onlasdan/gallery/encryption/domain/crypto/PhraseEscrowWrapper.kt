/*
 *   Copyright 2020–2026 Leon Latsch
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
import onlasdan.gallery.encryption.domain.models.RecoveryPhrase
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.io.encoding.Base64

private const val PHRASE_KEK_ITERATIONS = 100_000
private const val PHRASE_KEK_SIZE = 256

/**
 * Isolated module for wrapping/unwrapping the recovery phrase with a password-derived KEK.
 *
 * This is the "second layer" of the two-layer escrow:
 * - Layer 1: phrase wraps VMK (existing, in RecoveryPhraseVaultProtectionHandler — unchanged)
 * - Layer 2: password wraps phrase (this class — new)
 *
 * On a fresh install, the user enters their password → unwrap phrase → feed phrase into
 * the existing RecoveryPhraseVaultProtectionHandler.unlock() → get VMK.
 *
 * Deliberately isolated in its own class so it can be swapped out or made optional later
 * without touching the core VMK/VaultProtection code.
 *
 * @since Part A fix + Part B two-layer escrow
 */
@Singleton
class PhraseEscrowWrapper @Inject constructor(
    private val keyGen: KeyGen,
) {
    data class WrappedPhrase(
        val wrappedPhrase: ByteArray,
        val salt: ByteArray,
        val iv: ByteArray,
        val kdf: Kdf,
        val kdfIterations: Int,
        val algorithm: Algorithm,
        val keySize: Int,
    ) {
        fun toJson(): String {
            val wpB64 = Base64.encode(wrappedPhrase)
            val saltB64 = Base64.encode(salt)
            val ivB64 = Base64.encode(iv)
            return """{"wrappedPhrase":"$wpB64","salt":"$saltB64","iv":"$ivB64",""" +
                """"kdf":"${kdf.value}","kdfIterations":$kdfIterations,""" +
                """"algorithm":"${algorithm.value}","keySize":$keySize}"""
        }

        companion object {
            fun fromJson(json: String): WrappedPhrase? {
                return try {
                    val wp = Regex("\"wrappedPhrase\"\\s*:\\s*\"([^\"]+)\"")
                        .find(json)?.groupValues?.get(1) ?: return null
                    val salt = Regex("\"salt\"\\s*:\\s*\"([^\"]+)\"")
                        .find(json)?.groupValues?.get(1) ?: return null
                    val iv = Regex("\"iv\"\\s*:\\s*\"([^\"]+)\"")
                        .find(json)?.groupValues?.get(1) ?: return null
                    val kdfStr = Regex("\"kdf\"\\s*:\\s*\"([^\"]+)\"")
                        .find(json)?.groupValues?.get(1) ?: return null
                    val kdfIter = Regex("\"kdfIterations\"\\s*:\\s*(\\d+)")
                        .find(json)?.groupValues?.get(1)?.toIntOrNull() ?: return null
                    val algStr = Regex("\"algorithm\"\\s*:\\s*\"([^\"]+)\"")
                        .find(json)?.groupValues?.get(1) ?: return null
                    val keySize = Regex("\"keySize\"\\s*:\\s*(\\d+)")
                        .find(json)?.groupValues?.get(1)?.toIntOrNull() ?: return null
                    WrappedPhrase(
                        wrappedPhrase = Base64.decode(wp),
                        salt = Base64.decode(salt),
                        iv = Base64.decode(iv),
                        kdf = Kdf.entries.first { it.value == kdfStr },
                        kdfIterations = kdfIter,
                        algorithm = Algorithm.entries.first { it.value == algStr },
                        keySize = keySize,
                    )
                } catch (e: Exception) {
                    null
                }
            }
        }
    }

    fun wrapPhrase(phrase: RecoveryPhrase, password: String): WrappedPhrase {
        val salt = ByteArray(SALT_SIZE).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(IV_SIZE).also { SecureRandom().nextBytes(it) }
        val kek = keyGen.derivePasswordKeyEncryptionKey(
            password = password,
            salt = salt,
            kdf = Kdf.PBKDF2WithHmacSHA256,
            kdfIterations = PHRASE_KEK_ITERATIONS,
            keySize = PHRASE_KEK_SIZE,
        )
        val cipher = Cipher.getInstance(Algorithm.AesCbcPkcs7Padding.value).apply {
            init(Cipher.ENCRYPT_MODE, kek, IvParameterSpec(iv))
        }
        val wrapped = cipher.doFinal(phrase.toMnemonicString().toByteArray(Charsets.UTF_8))
        return WrappedPhrase(
            wrappedPhrase = wrapped,
            salt = salt,
            iv = iv,
            kdf = Kdf.PBKDF2WithHmacSHA256,
            kdfIterations = PHRASE_KEK_ITERATIONS,
            algorithm = Algorithm.AesCbcPkcs7Padding,
            keySize = PHRASE_KEK_SIZE,
        )
    }

    /**
     * Unwrap the phrase using the password. Returns null on wrong password
     * (decryption produces a padding error → caught → null).
     */
    fun unwrapPhrase(wrapped: WrappedPhrase, password: String): RecoveryPhrase? {
        return try {
            val kek = keyGen.derivePasswordKeyEncryptionKey(
                password = password,
                salt = wrapped.salt,
                kdf = wrapped.kdf,
                kdfIterations = wrapped.kdfIterations,
                keySize = wrapped.keySize,
            )
            val cipher = Cipher.getInstance(wrapped.algorithm.value).apply {
                init(Cipher.DECRYPT_MODE, kek, IvParameterSpec(wrapped.iv))
            }
            val plaintext = cipher.doFinal(wrapped.wrappedPhrase)
            RecoveryPhrase.from(String(plaintext, Charsets.UTF_8))
        } catch (e: Exception) {
            null // wrong password → padding error → null
        }
    }
}
