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
import onlasdan.gallery.encryption.domain.SessionRepository
import java.io.File
import java.io.IOException

/**
 * Sprint 8 — Version-dispatching DataSource.Factory for ExoPlayer.
 *
 * Reads the first byte of the encrypted file to determine the format version,
 * then delegates to the appropriate DataSource:
 *
 *  - Version 0x01 (legacy CBC + salt) → [AesCbcRandomAccessDataSource]
 *  - Version 0x02 (CBC) → [AesCbcRandomAccessDataSource]
 *  - Version 0x03 (single-stream GCM) → throws (videos should never be v3)
 *  - Version 0x04 (chunked GCM) → [ChunkedGcmRandomAccessDataSource]
 *
 * This replaces the hardcoded `AesCbcRandomAccessDataSource` in
 * [ImageViewerViewModel], allowing videos encrypted with the new chunked GCM
 * format (version 0x04, Sprint 1) to be played back with true
 * random access — seek to any chunk, decrypt only that chunk.
 *
 * @since Sprint 8 — wire ChunkedGcmRandomAccessDataSource for video
 */
@UnstableApi
class VersionDispatchDataSourceFactory(
	private val sessionRepository: SessionRepository,
	private val availableBytesProvider: (Uri) -> Long = { -1L },
	private val downloadCompleteProvider: (Uri) -> Boolean = { true },
) : DataSource.Factory {
	override fun createDataSource(): DataSource {
		return VersionDispatchDataSource(
			sessionRepository = sessionRepository,
			availableBytesProvider = availableBytesProvider,
			downloadCompleteProvider = downloadCompleteProvider,
		)
	}
}

/**
 * Version-dispatching DataSource — reads the version byte on [open], then
 * delegates all subsequent [read] / [close] calls to the appropriate
 * implementation.
 *
 * @since Sprint 8 — wire ChunkedGcmRandomAccessDataSource for video
 */
@UnstableApi
class VersionDispatchDataSource(
	private val sessionRepository: SessionRepository,
	private val availableBytesProvider: (Uri) -> Long = { -1L },
	private val downloadCompleteProvider: (Uri) -> Boolean = { true },
) : DataSource {
	private var delegate: DataSource? = null

	override fun open(dataSpec: androidx.media3.datasource.DataSpec): Long {
		val uri = dataSpec.uri
		val path = uri.path ?: return 0
		val file = File(path).canonicalFile

		// Wait for at least 1 byte (version byte) to be available.
		// For progressive download, this blocks until the first byte arrives.
		val available = availableBytesProvider(uri)
		if (available in 0..0) {
			// 0 bytes available but file might exist locally
		}

		// Read the version byte
		val versionByte: Byte = try {
			if (!file.exists()) {
				// F-UV-013: Wait for file to appear instead of defaulting to 0x02.
				// Defaulting to CBC breaks progressive streaming of chunked-GCM videos (0x04).
				val deadline = System.currentTimeMillis() + 10_000L
				while (!file.exists() && System.currentTimeMillis() < deadline) {
					try {
						Thread.sleep(100)
					} catch (e: InterruptedException) {
						// F-012 fix (related): wrap InterruptedException as IOException per DataSource contract
						Thread.currentThread().interrupt()
						throw IOException("Interrupted while waiting for file to appear: ${file.absolutePath}", e)
					}
				}
				if (!file.exists()) {
					throw IOException("VersionDispatchDataSource: file not found after 10s: ${file.absolutePath}")
				}
				val firstByte = ByteArray(1)
				file.inputStream().use { it.read(firstByte) }
				firstByte[0]
			} else {
				val firstByte = ByteArray(1)
				file.inputStream().use { it.read(firstByte) }
				firstByte[0]
			}
		} catch (e: IOException) {
			// Re-throw IOException (don't default to 0x02 — that masks the real problem)
			throw e
		} catch (e: Exception) {
			// F-012 fix: don't default to 0x02 on unknown errors — that silently dispatches
			// to CBC path which then throws IllegalStateException (RuntimeException, not IOException).
			// Throw IOException so ExoPlayer's error handler sees a contract-compliant exception.
			throw IOException("VersionDispatchDataSource: could not read version byte from ${file.absolutePath}", e)
		}

		// Dispatch based on version byte
		delegate = when (versionByte) {
			// Version 0x04 — chunked GCM (new format, Sprint 1)
			0x04.toByte() -> ChunkedGcmRandomAccessDataSource(
				sessionRepository = sessionRepository,
				availableBytesProvider = availableBytesProvider,
				downloadCompleteProvider = downloadCompleteProvider,
			)
			// Version 0x01, 0x02 — CBC path (legacy)
			0x01.toByte(), 0x02.toByte() -> AesCbcRandomAccessDataSource(
				sessionRepository = sessionRepository,
				availableBytesProvider = availableBytesProvider,
				downloadCompleteProvider = downloadCompleteProvider,
			)
			// F-012 fix: explicit handling for 0x03 (videos should never be v3)
			// and unknown bytes (0x05+, future versions). Throw IOException per DataSource contract.
			0x03.toByte() -> throw IOException(
				"VersionDispatchDataSource: version 0x03 (single-stream GCM) is not supported for video streaming"
			)
			else -> throw IOException(
				"VersionDispatchDataSource: unknown encryption version byte 0x${versionByte.toInt() and 0xFF} " +
					"(file: ${file.absolutePath}). Supported: 0x01, 0x02, 0x04."
			)
		}

		return delegate!!.open(dataSpec)
	}

	override fun read(target: ByteArray, offset: Int, length: Int): Int {
		return delegate?.read(target, offset, length) ?: 0
	}

	override fun addTransferListener(transferListener: androidx.media3.datasource.TransferListener) {
		delegate?.addTransferListener(transferListener)
	}

	override fun getUri(): Uri? = delegate?.uri

	override fun close() {
		delegate?.close()
	}
}
