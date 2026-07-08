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
import timber.log.Timber
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * TODO #2 — Chunked GCM InputStream.
 *
 * Decrypts data from version 0x04 (chunked GCM) format. Reads chunks
 * sequentially, decrypting each independently with its own nonce + auth
 * tag verification.
 *
 * For random access (video streaming), use [ChunkedGcmRandomAccessDataSource]
 * instead — it can seek to a specific chunk without decrypting prior chunks.
 *
 * @since v15 — TODO #2 chunked streaming encryption
 */
class ChunkedGcmInputStream(
        private val input: InputStream,
        private val vmk: SecretKey,
) : InputStream() {
        private var chunkSize: Int = CHUNK_SIZE
        private var totalPlaintextSize: Long = -1L // -1 = unknown (read until EOF)
        private var bytesRead: Long = 0L
        private var currentChunk: ByteArray? = null
        private var currentChunkPos: Int = 0
        private var eof: Boolean = false

        init {
                readHeader()
        }

        private fun readHeader() {
                // NOTE: The version byte (0x04) has ALREADY been consumed by
                // CbcCryptoEngine.createDecryptStream() before constructing this
                // stream. We must NOT read it again — the input stream is now
                // positioned at the chunk_size field.
                //
                // Chunk size (4 bytes, big-endian)
                val csBytes = ByteArray(4)
                if (input.read(csBytes) != 4) throw IOException("ChunkedGcmInputStream: truncated header (chunk_size)")
                chunkSize = ByteBuffer.wrap(csBytes).int
                if (chunkSize <= 0 || chunkSize > 100 * CHUNK_SIZE) {
                        throw IOException("ChunkedGcmInputStream: invalid chunk_size=$chunkSize")
                }
                // Total plaintext size (8 bytes, big-endian)
                val tsBytes = ByteArray(8)
                if (input.read(tsBytes) != 8) throw IOException("ChunkedGcmInputStream: truncated header (total_size)")
                totalPlaintextSize = ByteBuffer.wrap(tsBytes).long
                Timber.d("ChunkedGcmInputStream: chunkSize=%d totalPlaintextSize=%d", chunkSize, totalPlaintextSize)
        }

        override fun read(): Int {
                val b = ByteArray(1)
                val n = read(b, 0, 1)
                return if (n <= 0) -1 else b[0].toInt() and 0xFF
        }

        override fun read(
                b: ByteArray,
                off: Int,
                len: Int,
        ): Int {
                if (len == 0) return 0
                if (eof) return -1

                // Check if we've read all expected plaintext
                if (totalPlaintextSize > 0 && bytesRead >= totalPlaintextSize) {
                        eof = true
                        return -1
                }

                // Ensure we have decrypted data available
                if (currentChunk == null || currentChunkPos >= currentChunk!!.size) {
                        if (!loadNextChunk()) {
                                eof = true
                                return -1
                        }
                }

                val chunk = currentChunk!!
                val available = chunk.size - currentChunkPos
                val toRead = minOf(len, available)
                System.arraycopy(chunk, currentChunkPos, b, off, toRead)
                currentChunkPos += toRead
                bytesRead += toRead
                return toRead
        }

        private fun loadNextChunk(): Boolean {
                return try {
                        // Read nonce (12 bytes)
                        val nonce = ByteArray(GCM_IV_SIZE)
                        val nonceRead = input.read(nonce)
                        if (nonceRead < GCM_IV_SIZE) return false // EOF

                        // Read ciphertext + tag. We don't know the exact size of this chunk
                        // (last chunk may be smaller). Read available bytes.
                        // For non-last chunks: ciphertext = chunkSize bytes, tag = 16 bytes.
                        // For last chunk: ciphertext = (totalPlaintextSize % chunkSize) bytes,
                        //   but if totalPlaintextSize = 0 (unknown), we read until EOF.

                        val expectedCipherLen =
                                if (totalPlaintextSize > 0) {
                                        val remaining = totalPlaintextSize - bytesRead
                                        minOf(remaining, chunkSize.toLong()).toInt()
                                } else {
                                        chunkSize // Assume full chunk (may be wrong for last chunk)
                                }

                        val cipherBuf = ByteArray(expectedCipherLen + GCM_TAG_SIZE)
                        var totalRead = 0
                        while (totalRead < cipherBuf.size) {
                                val n = input.read(cipherBuf, totalRead, cipherBuf.size - totalRead)
                                if (n < 0) break
                                totalRead += n
                        }
                        if (totalRead == 0) return false

                        // If we read less than expected, trim the buffer
                        val actualCipher =
                                if (totalRead < cipherBuf.size) {
                                        cipherBuf.copyOf(totalRead)
                                } else {
                                        cipherBuf
                                }

                        // Decrypt
                        val cipher =
                                Cipher.getInstance(Algorithm.AesGcmNoPadding.value).apply {
                                        init(Cipher.DECRYPT_MODE, vmk, GCMParameterSpec(GCM_TAG_SIZE * 8, nonce))
                                }
                        val plaintext = cipher.doFinal(actualCipher)

                        currentChunk = plaintext
                        currentChunkPos = 0
                        true
                } catch (e: javax.crypto.AEADBadTagException) {
                        // F-ENC-011: Do NOT silently swallow GCM auth tag failures.
                        // Throw IOException so the caller knows the file is corrupted/tampered.
                        throw java.io.IOException(
                                "ChunkedGcmInputStream: GCM auth tag verification failed at chunk (bytesRead=$bytesRead) — file corrupted or tampered",
                                e,
                        )
                } catch (e: Exception) {
                        Timber.e(e, "ChunkedGcmInputStream: loadNextChunk failed")
                        false
                }
        }

        override fun close() {
                input.close()
        }
}
