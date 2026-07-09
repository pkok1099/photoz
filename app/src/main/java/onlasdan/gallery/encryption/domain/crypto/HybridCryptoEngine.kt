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
import onlasdan.gallery.encryption.domain.models.EncryptionVersionByte
import onlasdan.gallery.encryption.domain.models.Session
import onlasdan.gallery.encryption.domain.models.VaultSession
import timber.log.Timber
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.inject.Inject
import java.io.IOException

/**
 * F-ENC-024: Typed exceptions for HybridCryptoEngine error paths.
 * Previously these returned null, which was ambiguous (could mean wrong key,
 * corrupt header, IO error, unsupported version, etc.).
 */

/** Thrown when the version byte is not recognized (1, 2, 3, or 4). */
class InvalidVersionByteException(
	version: Int
) : IOException("Invalid encryption version byte: 0x${version.toString(16)} (supported: 0x01-0x04)")

/** Thrown when the file header is truncated or malformed. */
class CorruptHeaderException(
	message: String,
	cause: Throwable? = null
) : IOException(message, cause)

/** Thrown when the cipher algorithm in the params is unsupported. */
class UnsupportedAlgorithmException(
	alg: String
) : IOException("Unsupported cipher algorithm: $alg")

/**
 * Hybrid CBC + GCM crypto engine — the single injected [CryptoEngine] for PhotoZ.
 *
 * Despite the historical name (kept for stability — Hilt binding references it
 * directly in [onlasdan.gallery.encryption.di.EncryptionBindingModule]), this
 * engine handles **both** algorithms:
 *
 *  - **Encrypt, `useGcm = true` (default, Sprint 1 / P6)**: writes a version-3
 *    GCM header `[0x03][IV(12)]` and a GCM ciphertext with a trailing 16-byte
 *    authentication tag. Used for all non-video files.
 *  - **Encrypt, `useGcm = false`**: writes a version-2 CBC header `[0x02][IV(16)]`
 *    and a CBC ciphertext. Used for video originals (random-access streaming
 *    requires CBC's block-chain IV property).
 *  - **Decrypt**: reads the version byte from the stream header and dispatches
 *    transparently to the matching cipher (CBC for versions 1, 2; GCM for 3).
 *
 * The class name was kept as `HybridCryptoEngine` to avoid touching the Hilt
 * binding graph (renaming would require updating the `@Binds` in
 * `EncryptionBindingModule` and the existing unit tests). The class itself
 * is now an "engine" in the abstract sense, not strictly CBC.
 */
class HybridCryptoEngine
	@Inject
	constructor() : CryptoEngine {
		override fun createEncryptStream(
			output: OutputStream,
			session: Session,
			useGcm: Boolean,
		): OutputStream {
			require(session is VaultSession)

			try {
				if (useGcm) {
					// ─── (roadmap #2): Chunked GCM (version 0x04) for ALL new files ──
					// Replaces single-stream GCM (0x03) with per-chunk encryption:
					// - Per-chunk auth tag (tamper detection per 1MB)
					// - Random access decryption (seek to chunk N)
					// - Progressive streaming (ExoPlayer starts after first chunk)
					//
					// The returned OutputStream is NOT a CipherOutputStream (chunked
					// encryption doesn't work with a single Cipher instance). Callers
					// that type-check for CipherOutputStream will get null — but
					// VaultFileStorage.openEncryptedOutput just needs OutputStream.
					return ChunkedGcmOutputStream(output, session.vmk)
				} else {
					// ─── Legacy CBC path (videos, backwards compat) ──────────────
					// Format: [0x02][IV(16)][CBC ciphertext]
					val iv = ByteArray(IV_SIZE).also { SecureRandom().nextBytes(it) }

					val cipher =
						Cipher.getInstance(Algorithm.AesCbcPkcs7Padding.value).apply {
							init(Cipher.ENCRYPT_MODE, session.vmk, IvParameterSpec(iv))
						}

					output.write(byteArrayOf(EncryptionVersionByte.Two.value))
					output.write(iv)

					return CipherOutputStream(output, cipher)
				}
			} catch (e: Exception) {
				Timber.e(e, "Error creating CipherOutputStream")
				throw CorruptHeaderException("Failed to create encrypt stream: ${e.message}", e)
			}
		}

		override fun createDecryptStream(
			input: InputStream,
			session: Session,
		): InputStream {
			require(session is VaultSession)

			try {
				val versionByte = input.read().toByte()
				val version = EncryptionVersionByte.fromValue(versionByte)

				return when (version) {
					EncryptionVersionByte.One -> {
						// Legacy v1: [0x01][SALT(16)][IV(16)][CBC ciphertext]
						val salt = ByteArray(SALT_SIZE)
						readFully(input, salt)
						val iv = ByteArray(IV_SIZE)
						readFully(input, iv)

						val cipher =
							Cipher.getInstance(Algorithm.AesCbcPkcs7Padding.value).apply {
								init(Cipher.DECRYPT_MODE, session.vmk, IvParameterSpec(iv))
							}
						CipherInputStream(input, cipher)
					}
					EncryptionVersionByte.Two -> {
						// v2: [0x02][IV(16)][CBC ciphertext]
						val iv = ByteArray(IV_SIZE)
						readFully(input, iv)

						val cipher =
							Cipher.getInstance(Algorithm.AesCbcPkcs7Padding.value).apply {
								init(Cipher.DECRYPT_MODE, session.vmk, IvParameterSpec(iv))
							}
						CipherInputStream(input, cipher)
					}
					EncryptionVersionByte.Three -> {
						// v3 (Sprint 1 / P6): [0x03][IV(12)][GCM ciphertext + 16-byte tag]
						// The JCE provider reads the trailing 16-byte tag from the
						// stream and verifies it on doFinal() (triggered by close()
						// on CipherInputStream). If the tag doesn't match, the
						// AEADBadTagException is thrown on read/close — callers
						// should catch and treat as corruption.
						val iv = ByteArray(GCM_IV_SIZE)
						readFully(input, iv)

						val cipher =
							Cipher.getInstance(Algorithm.AesGcmNoPadding.value).apply {
								init(Cipher.DECRYPT_MODE, session.vmk, GCMParameterSpec(GCM_TAG_SIZE * 8, iv))
							}
						CipherInputStream(input, cipher)
					}
					EncryptionVersionByte.Four -> {
						// v4 (TODO #2): [0x04][chunk_size(4)][total_size(8)][per-chunk GCM blobs]
						// Each chunk: [nonce(12)][ciphertext][tag(16)] — independently decryptable.
						// The version byte has already been read; ChunkedGcmInputStream
						// reads the rest of the header + chunks.
						ChunkedGcmInputStream(input, session.vmk)
					}
				}
			} catch (e: Exception) {
				Timber.e(e, "Error creating CipherInputStream")
				throw CorruptHeaderException("Failed to create decrypt stream: ${e.message}", e)
			}
		}
	}

/**
 * (roadmap #20): Read exactly [buf.size] bytes from [input], looping if necessary.
 * InputStream.read(buf, off, len) is NOT guaranteed to fill the buffer.
 */
private fun readFully(input: java.io.InputStream, buf: ByteArray) {
	var offset = 0
	while (offset < buf.size) {
		val n = input.read(buf, offset, buf.size - offset)
		if (n <= 0) throw java.io.IOException("Unexpected EOF: needed ${buf.size} bytes, got $offset")
		offset += n
	}
}
