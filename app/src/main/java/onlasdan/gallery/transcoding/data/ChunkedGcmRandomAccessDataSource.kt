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
 * (roadmap #2) — Chunked GCM Random Access DataSource for ExoPlayer.
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

		// Read header — F-ENC-008: use try/finally to ensure channel is closed even on exception
		val headerBuf = ByteArray(headerSize)
		val channel = java.io.FileInputStream(file).channel
		try {
			channel.position(0)
			channel.read(ByteBuffer.wrap(headerBuf))

			val version = headerBuf[0]
			if (version != EncryptionVersionByte.Four.value) {
				throw IOException("ChunkedGcmRandomAccessDataSource: expected version 0x04, got 0x${version.toString(16)}")
			}

			chunkSize = ByteBuffer.wrap(headerBuf, 1, 4).int
			// F-ENC-006: validate chunkSize — maliciously crafted file with Int.MAX_VALUE
			// would cause OOM; chunkSize=0 causes GCM failures. Same bounds as ChunkedGcmInputStream.
			if (chunkSize <= 0 || chunkSize > 100 * CHUNK_SIZE) {
				throw IOException("ChunkedGcmRA: invalid chunk_size=$chunkSize (must be 1..${100 * CHUNK_SIZE})")
			}
			val totalPlaintextSize = ByteBuffer.wrap(headerBuf, 5, 8).long

			key = sessionRepository.get()?.vmk
				?: throw IOException("Vault is locked — cannot decrypt video stream")

			// Compute the chunk containing the requested position
			val plainOffset = dataSpec.position
			// F-002 fix: validate plainOffset fits in Int range before .toInt() cast.
			// Long→Int truncation on huge seeks produces negative chunkIndex → IllegalArgumentException.
			if (plainOffset > Int.MAX_VALUE.toLong()) {
				throw IOException("ChunkedGcmRA: plainOffset $plainOffset exceeds Int.MAX_VALUE (chunk index overflow)")
			}
			val chunkIndex = (plainOffset / chunkSize).toInt()
			val offsetInChunk = (plainOffset % chunkSize).toInt()

			Timber.d(
				"ChunkedGcmRA: open at plainOffset=%d chunkIndex=%d offsetInChunk=%d chunkSize=%d totalSize=%d",
				plainOffset,
				chunkIndex,
				offsetInChunk,
				chunkSize,
				totalPlaintextSize,
			)

			// F-002 fix: guard against seek past EOF before loadChunk.
			// ExoPlayer's DataSource.open() contract: return non-negative byte count, or throw IOException.
			if (totalPlaintextSize > 0 && plainOffset >= totalPlaintextSize) {
				throw IOException("ChunkedGcmRA: seek past EOF (plainOffset=$plainOffset, totalPlaintextSize=$totalPlaintextSize)")
			}

			// Load the target chunk
			// F-009 fix: wrap AEADBadTagException as IOException to satisfy DataSource.open contract
			try {
				loadChunk(chunkIndex, channel)
			} catch (e: javax.crypto.AEADBadTagException) {
				throw IOException("ChunkedGcmRA: GCM auth tag verification failed on chunk $chunkIndex", e)
			}
			currentChunkPos = offsetInChunk
		} finally {
			channel.close()
		}

		// Return content length (remaining bytes from this position)
		// F-002 fix: coerce to 0 — never return negative (ExoPlayer contract violation)
		// F-015 fix: honor dataSpec.length when bounded (ExoPlayer may issue range requests)
		val totalPlaintextSize = ByteBuffer.wrap(headerBuf, 5, 8).long
		val plainOffset = dataSpec.position
		val remaining = if (totalPlaintextSize > 0) {
			(totalPlaintextSize - plainOffset).coerceAtLeast(0L)
		} else {
			java.lang.Long.MAX_VALUE // unknown — stream until EOF
		}
		return if (dataSpec.length != androidx.media3.common.C.LENGTH_UNBOUNDED.toLong()) {
			// Bounded request — return the requested length (clamped to remaining)
			minOf(dataSpec.length, remaining).coerceAtLeast(0L)
		} else {
			remaining
		}
	}

	private fun loadChunk(
		chunkIndex: Int,
		channel: java.nio.channels.FileChannel,
	) {
		val chunkEncSize = GCM_IV_SIZE + chunkSize + GCM_TAG_SIZE
		val chunkOffset = headerSize + chunkIndex.toLong() * chunkEncSize

		// F-ENC-007: wait for the FULL chunk to be available before reading,
		// not just nonce + 1 byte. Partial reads during progressive download
		// produce truncated ciphertext → AEADBadTagException.
		waitForBytesAvailable(chunkOffset + chunkEncSize)

		channel.position(chunkOffset)

		// Read nonce
		val nonce = ByteArray(GCM_IV_SIZE)
		// F-ENC-007: loop until full read (channel.read may return fewer bytes)
		var nonceRead = 0
		while (nonceRead < GCM_IV_SIZE) {
			val n = channel.read(ByteBuffer.wrap(nonce, nonceRead, GCM_IV_SIZE - nonceRead))
			if (n <= 0) break
			nonceRead += n
		}
		if (nonceRead < GCM_IV_SIZE) {
			// F-010 fix: distinguish genuine EOF (no bytes read = end of stream)
			// from truncated chunk (some bytes read, but < GCM_IV_SIZE).
			if (nonceRead == 0) {
				// Genuine EOF — no more chunks. Signal by nulling currentChunkData.
				currentChunkData = null
				currentChunkIndex = -1
				Timber.d("ChunkedGcmRA: reached EOF at chunk %d (no more nonce to read)", chunkIndex)
				return
			}
			throw IOException("ChunkedGcmRA: chunk $chunkIndex truncated nonce (read=$nonceRead)")
		}

		// Read ciphertext + tag — F-ENC-007: loop until full read
		val maxCipherLen = chunkSize + GCM_TAG_SIZE
		val cipherBuf = ByteArray(maxCipherLen)
		var totalRead = 0
		while (totalRead < maxCipherLen) {
			val n = channel.read(ByteBuffer.wrap(cipherBuf, totalRead, maxCipherLen - totalRead))
			if (n <= 0) break
			totalRead += n
		}

		if (totalRead <= GCM_TAG_SIZE) {
			throw IOException("ChunkedGcmRA: chunk $chunkIndex too small (read=$totalRead)")
		}

		val actualCipher = if (totalRead < maxCipherLen) cipherBuf.copyOf(totalRead) else cipherBuf

		val cipher =
			Cipher.getInstance(Algorithm.AesGcmNoPadding.value).apply {
				init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_SIZE * 8, nonce))
			}
		currentChunkData = cipher.doFinal(actualCipher)
		currentChunkIndex = chunkIndex
		Timber.d("ChunkedGcmRA: loaded chunk %d (%d plaintext bytes)", chunkIndex, currentChunkData!!.size)
	}

	override fun read(
		target: ByteArray,
		offset: Int,
		length: Int,
	): Int {
		if (length == 0) return 0
		val chunk = currentChunkData ?: return -1

		val available = chunk.size - currentChunkPos
		if (available <= 0) {
			// Try to load next chunk — F-ENC-008: use try/finally for channel lifecycle
			val file = this.file ?: return -1
			val channel = java.io.FileInputStream(file).channel
			try {
				loadChunk(currentChunkIndex + 1, channel)
			} catch (e: javax.crypto.AEADBadTagException) {
				throw java.io.IOException("ChunkedGcmRA: GCM auth failed at chunk ${currentChunkIndex + 1}", e)
			} catch (e: java.io.IOException) {
				// F-010 fix: do NOT swallow IOException("truncated nonce") / ("chunk too small")
				// as silent EOF. These indicate file corruption — the caller must know.
				// Genuine EOF (loadChunk exhausted all chunks) is signaled by currentChunkData
				// being null after loadChunk, which the next read() call handles via `?: return -1`.
				throw e
			} catch (e: Exception) {
				// F-010 fix: log and rethrow — do not convert to silent EOF.
				// If this is a genuine clean EOF (backing stream returns -1 at chunk boundary),
				// loadChunk would have set currentChunkData = null and returned normally.
				throw java.io.IOException("ChunkedGcmRA: read failed at chunk ${currentChunkIndex + 1}", e)
			} finally {
				channel.close()
			}
			// F-010 fix: if loadChunk set currentChunkData=null (genuine EOF), return -1
			if (currentChunkData == null) return -1
			currentChunkPos = 0
			return read(target, offset, length)
		}

		val toRead = minOf(length, available)
		System.arraycopy(chunk, currentChunkPos, target, offset, toRead)
		currentChunkPos += toRead
		return toRead
	}

	override fun addTransferListener(transferListener: TransferListener) {
		// No-op: this data source does not emit transfer progress events.
	}

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
			try {
				Thread.sleep(50)
			} catch (e: InterruptedException) {
				// F-004 fix: DataSource.open/read declare throws IOException; raw
				// InterruptedException violates the contract. Re-set the interrupt
				// flag so upstream cancellation checks see it, then throw IOException.
				Thread.currentThread().interrupt()
				throw IOException("Interrupted while waiting for download (needed $minBytes bytes)", e)
			}
		}
	}
}
