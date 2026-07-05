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

package onlasdan.gallery.transcoding.data

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import onlasdan.gallery.encryption.domain.SessionRepository
import onlasdan.gallery.encryption.domain.crypto.CHUNK_SIZE
import onlasdan.gallery.encryption.domain.crypto.GCM_IV_SIZE
import onlasdan.gallery.encryption.domain.crypto.GCM_TAG_SIZE
import onlasdan.gallery.encryption.domain.models.Algorithm
import onlasdan.gallery.encryption.domain.models.EncryptionVersionByte
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec

/**
 * TODO #2 — Chunked GCM Random Access DataSource for ExoPlayer.
 *
 * Decrypts version 0x04 (chunked GCM) files with true random access:
 * seek to the chunk containing the requested plaintext offset, decrypt
 * ONLY that chunk, serve the requested bytes.
 *
 * This replaces the CBC block-chain IV trick in [AesCbcRandomAccessDataSource]
 * for chunked files. Random access is simpler and more efficient:
 * - No need to read the previous ciphertext block as IV
 * - Each chunk is independently decryptable
 * - Seeking is O(1) — just compute chunk index + offset
 *
 * Chunk layout in the encrypted file:
 * ```
 * [header: version(1) + chunk_size(4) + total_size(8) = 13 bytes]
 * [chunk_0: nonce(12) + ciphertext(chunk_size) + tag(16)]
 * [chunk_1: nonce(12) + ciphertext(chunk_size) + tag(16)]
 * ...
 * ```
 *
 * To read plaintext at offset P:
 * 1. chunk_index = P / chunk_size
 * 2. offset_in_chunk = P % chunk_size
 * 3. file_offset = header_size + chunk_index * (12 + chunk_size + 16)
 * 4. Seek to file_offset, read nonce(12) + ciphertext(chunk_size) + tag(16)
 * 5. Decrypt with GCM
 * 6. Skip offset_in_chunk bytes, serve the rest
 *
 * @since v15 — TODO #2 chunked streaming encryption
 */
@UnstableApi
class ChunkedGcmRandomAccessDataSource(
    private val sessionRepository: SessionRepository,
    private val availableBytesProvider: (Uri) -> Long = { -1L },
    private val downloadCompleteProvider: (Uri) -> Boolean = { true },
) : DataSource {

    private var file: File? = null
    private var uri: Uri = Uri.EMPTY
    private var chunkSize: Int = CHUNK_SIZE
    private var headerSize: Int = 13
    private var key: javax.crypto.SecretKey? = null
    private var currentChunkData: ByteArray? = null
    private var currentChunkIndex: Int = -1
    private var currentChunkPos: Int = 0

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        uri.path ?: return 0
        file = File(uri.path!!).canonicalFile

        // Wait for header
        waitForBytesAvailable(headerSize.toLong())

        // Read header
        val headerBuf = ByteArray(headerSize)
        val channel = java.io.FileInputStream(file).channel
        channel.position(0)
        channel.read(ByteBuffer.wrap(headerBuf))

        val version = headerBuf[0]
        if (version != EncryptionVersionByte.Four.value) {
            throw IOException("ChunkedGcmRandomAccessDataSource: expected version 0x04, got 0x${version.toString(16)}")
        }

        chunkSize = ByteBuffer.wrap(headerBuf, 1, 4).int
        val totalPlaintextSize = ByteBuffer.wrap(headerBuf, 5, 8).long

        key = sessionRepository.get()?.vmk
            ?: throw IOException("Vault is locked — cannot decrypt video stream")

        // Compute the chunk containing the requested position
        val plainOffset = dataSpec.position
        val chunkIndex = (plainOffset / chunkSize).toInt()
        val offsetInChunk = (plainOffset % chunkSize).toInt()

        Timber.d("ChunkedGcmRA: open at plainOffset=%d chunkIndex=%d offsetInChunk=%d chunkSize=%d totalSize=%d",
            plainOffset, chunkIndex, offsetInChunk, chunkSize, totalPlaintextSize)

        // Load the target chunk
        loadChunk(chunkIndex, channel)
        currentChunkPos = offsetInChunk

        channel.close()

        // Return content length (remaining bytes from this position)
        return if (totalPlaintextSize > 0) {
            totalPlaintextSize - plainOffset
        } else {
            java.lang.Long.MAX_VALUE // unknown — stream until EOF
        }
    }

    private fun loadChunk(chunkIndex: Int, channel: java.nio.channels.FileChannel) {
        val chunkEncSize = GCM_IV_SIZE + chunkSize + GCM_TAG_SIZE
        val chunkOffset = headerSize + chunkIndex.toLong() * chunkEncSize

        waitForBytesAvailable(chunkOffset + GCM_IV_SIZE + 1) // at least nonce + 1 byte

        channel.position(chunkOffset)

        // Read nonce
        val nonce = ByteArray(GCM_IV_SIZE)
        channel.read(ByteBuffer.wrap(nonce))

        // Read ciphertext + tag
        // For the last chunk, the ciphertext may be smaller than chunkSize.
        // We read what's available and let GCM doFinal handle it.
        val maxCipherLen = chunkSize + GCM_TAG_SIZE
        val cipherBuf = ByteArray(maxCipherLen)
        val read = channel.read(ByteBuffer.wrap(cipherBuf))

        if (read <= GCM_TAG_SIZE) {
            throw IOException("ChunkedGcmRA: chunk $chunkIndex too small (read=$read)")
        }

        val actualCipher = if (read < maxCipherLen) cipherBuf.copyOf(read) else cipherBuf

        val cipher = Cipher.getInstance(Algorithm.AesGcmNoPadding.value).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_SIZE * 8, nonce))
        }
        currentChunkData = cipher.doFinal(actualCipher)
        currentChunkIndex = chunkIndex
        Timber.d("ChunkedGcmRA: loaded chunk %d (%d plaintext bytes)", chunkIndex, currentChunkData!!.size)
    }

    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val chunk = currentChunkData ?: return -1

        val available = chunk.size - currentChunkPos
        if (available <= 0) {
            // Try to load next chunk
            val file = this.file ?: return -1
            val channel = java.io.FileInputStream(file).channel
            try {
                loadChunk(currentChunkIndex + 1, channel)
            } catch (e: Exception) {
                channel.close()
                return -1 // EOF
            }
            channel.close()
            currentChunkPos = 0
            return read(target, offset, length)
        }

        val toRead = minOf(length, available)
        System.arraycopy(chunk, currentChunkPos, target, offset, toRead)
        currentChunkPos += toRead
        return toRead
    }

    override fun addTransferListener(transferListener: TransferListener) {}

    override fun getUri(): Uri = uri

    override fun close() {
        currentChunkData = null
        currentChunkIndex = -1
    }

    private fun waitForBytesAvailable(minBytes: Long) {
        if (availableBytesProvider(uri) < 0) return
        val deadline = System.currentTimeMillis() + 600_000L
        while (true) {
            val avail = availableBytesProvider(uri)
            if (avail < 0) return
            if (avail >= minBytes) return
            if (downloadCompleteProvider(uri)) return
            if (System.currentTimeMillis() > deadline) {
                throw IOException("Timeout waiting for download: needed $minBytes bytes")
            }
            Thread.sleep(50)
        }
    }
}
