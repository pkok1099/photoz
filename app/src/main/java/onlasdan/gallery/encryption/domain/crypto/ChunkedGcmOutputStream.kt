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
import timber.log.Timber
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * (roadmap #2) — Chunked GCM OutputStream.
 *
 * Encrypts data in fixed-size chunks (default 1MB). Each chunk gets its
 * own fresh nonce + GCM auth tag. This enables:
 * - Per-chunk integrity verification (tamper detection per 1MB)
 * - Random access decryption (seek to chunk N, decrypt only that chunk)
 * - Progressive streaming (ExoPlayer can start after first chunk)
 *
 * File format (version 0x04):
 * ```
 * [version=0x04][chunk_size(4, BE)][total_plaintext_size(8, BE)]
 * [chunk_0: nonce(12) + ciphertext(chunk_size) + tag(16)]
 * [chunk_1: nonce(12) + ciphertext(chunk_size) + tag(16)]
 * ...
 * [chunk_last: nonce(12) + ciphertext(remaining) + tag(16)]
 * ```
 *
 * The total_plaintext_size is written as 0 (unknown) — we don't know the
 * total until close(), and OutputStream doesn't support seeking back.
 * The decryptor ([ChunkedGcmInputStream]) handles totalPlaintextSize=0
 * by reading chunks until EOF.
 *
 * @since v15 — TODO #2 chunked streaming encryption
 */
class ChunkedGcmOutputStream(
	private val output: OutputStream,
	private val vmk: SecretKey,
	private val chunkSize: Int = CHUNK_SIZE,
) : OutputStream() {
	private val buffer = ByteArray(chunkSize)
	private var bufferPos = 0
	private var totalBytesWritten = 0L
	private var headerWritten = false
	private val secureRandom = SecureRandom()
	private var closed = false

	private fun writeHeaderIfNeeded() {
		if (headerWritten) return
		// Version byte
		output.write(byteArrayOf(EncryptionVersionByte.Four.value))
		// Chunk size (4 bytes, big-endian)
		val csBytes = ByteBuffer.allocate(4).putInt(chunkSize).array()
		output.write(csBytes)
		// Total plaintext size (8 bytes, big-endian) — 0 = unknown.
		// The decryptor reads chunks until EOF when totalPlaintextSize=0.
		output.write(ByteArray(8))
		headerWritten = true
	}

	override fun write(b: Int) {
		write(byteArrayOf(b.toByte()))
	}

	override fun write(
		b: ByteArray,
		off: Int,
		len: Int,
	) {
		writeHeaderIfNeeded()
		var remaining = len
		var srcPos = off
		while (remaining > 0) {
			val space = chunkSize - bufferPos
			val toCopy = minOf(remaining, space)
			System.arraycopy(b, srcPos, buffer, bufferPos, toCopy)
			bufferPos += toCopy
			srcPos += toCopy
			remaining -= toCopy
			if (bufferPos == chunkSize) {
				flushChunk()
			}
		}
	}

	private fun flushChunk() {
		if (bufferPos == 0) return
		val nonce = ByteArray(GCM_IV_SIZE).also { secureRandom.nextBytes(it) }
		val cipher =
			Cipher.getInstance(Algorithm.AesGcmNoPadding.value).apply {
				init(Cipher.ENCRYPT_MODE, vmk, GCMParameterSpec(GCM_TAG_SIZE * 8, nonce))
			}
		val ciphertext = cipher.doFinal(buffer, 0, bufferPos)
		// Write: nonce(12) + ciphertext(plaintext_len) + tag(16, included in ciphertext)
		output.write(nonce)
		output.write(ciphertext)
		totalBytesWritten += bufferPos
		bufferPos = 0
	}

	override fun flush() {
		output.flush()
	}

	override fun close() {
		if (closed) return
		closed = true
		try {
			writeHeaderIfNeeded()
			flushChunk() // Flush remaining buffered data
			output.flush()
			// F-ENC-016: Patch the total_plaintext_size header field (bytes 5-12)
			// now that we know the final size. Only possible if the underlying
			// output is a FileOutputStream (we can seek back to the header).
			// For other OutputStream types (network, in-memory), the header stays 0
			// and the decryptor reads until EOF (existing behavior).
			patchTotalPlaintextSizeIfPossible()
		} catch (e: Exception) {
			Timber.e(e, "ChunkedGcmOutputStream: close failed")
		} finally {
			output.close()
		}
		Timber.d("ChunkedGcmOutputStream: closed, total plaintext=%d bytes", totalBytesWritten)
	}

	/**
	 * F-ENC-016: If the output stream is a FileOutputStream, seek back to the
	 * header (offset 5, after version byte + chunk_size) and write the actual
	 * totalBytesWritten. This lets ExoPlayer know the content length for seeking.
	 *
	 * For other OutputStream types (ByteArrayOutputStream, network streams), the
	 * header stays 0 (unknown) — the decryptor reads chunks until EOF.
	 */
	private fun patchTotalPlaintextSizeIfPossible() {
		try {
			val fos = output as? java.io.FileOutputStream ?: return
			val channel = fos.channel
			if (!channel.isOpen) return
			// Save current position, seek to header offset 5, write size, restore position
			val savedPos = channel.position()
			channel.position(5) // skip version(1) + chunk_size(4)
			val sizeBytes = ByteBuffer.allocate(8).putLong(totalBytesWritten).array()
			channel.write(java.nio.ByteBuffer.wrap(sizeBytes))
			channel.position(savedPos)
			Timber.d("ChunkedGcmOutputStream: patched header totalPlaintextSize=%d", totalBytesWritten)
		} catch (e: Exception) {
			// Non-fatal — the decryptor handles totalPlaintextSize=0 by reading until EOF.
			Timber.w(e, "ChunkedGcmOutputStream: could not patch header (non-fatal)")
		}
	}
}
