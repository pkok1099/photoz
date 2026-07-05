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

package onlasdan.gallery.transcoding.data

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import onlasdan.gallery.encryption.domain.SessionRepository
import onlasdan.gallery.encryption.domain.crypto.BLOCK_SIZE
import onlasdan.gallery.encryption.domain.crypto.IV_SIZE
import onlasdan.gallery.encryption.domain.crypto.SALT_SIZE
import onlasdan.gallery.encryption.domain.models.Algorithm
import onlasdan.gallery.encryption.domain.models.EncryptionVersionByte
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.spec.IvParameterSpec

/**
 * AES-CBC Random Access DataSource (Block-Aligned Seeking)
 *
 *  ┌────────────────────────────────────────────────────────┐
 *  │                 Encrypted File Layout                  │
 *  ├────────────────────────────────────────────────────────┤
 *  │  H  │  C0  │  C1  │  C2  │  C3  │  C4  │  ...          │
 *  └────────────────────────────────────────────────────────┘
 *   H  = [ENC_VERSION_BYTE][SALT][IV]
 *   Cn = Ciphertext block n (16 bytes each)
 *
 *  Example: Seek into plaintext that belongs to block P3.
 *  CBC requires the previous ciphertext block as IV.
 *
 *  ┌────────────────────────────────────────────────────────┐
 *  │  H  │  C0  │  C1  │  C2  │  C3  │  C4  │  ...          │
 *  └────────────────────────────────────────────────────────┘
 *                      ▲      ▲
 *                      │      │
 *                      │      └─ Read C3 (target ciphertext block)
 *                      └──────── Read C2 (used as IV for C3)
 *
 *  Steps (block-aware seek):
 *    1) Skip H and jump to C2.
 *    2) Read C2 → use as IV.
 *    3) Read C3 → decrypt with IV = C2 → produces plaintext block P3.
 *    4) Dismiss the first "discard" bytes inside P3
 *       (when the target byte is not aligned to the block boundary).
 *
 *  Discard illustration (inside P3):
 *  ┌──────────────────────────────┐
 *  │  P3: [xxxx|.............]    │
 *  └──────────────────────────────┘
 *             ↑
 *             └─ dismissed bytes (discard)
 *
 *  This avoids fake-skipping bytes by decrypting and allows correct
 *  block-aligned random access in AES-CBC.
 *
 *  Limitations:
 *  - Random access is only safe at 16-byte block boundaries.
 *  - PKCS7 padding is only validated at end-of-stream.
 *
 *  ## Progressive streaming
 *
 *  When the encrypted file is being downloaded in the background
 *  (progressive video streaming), [availableBytesProvider] and
 *  [downloadCompleteProvider] describe how many bytes are currently
 *  readable and whether the download has finished. The DataSource
 *  blocks (polls) on read when it reaches the end of available data
 *  but the download is still in progress — ExoPlayer never sees an
 *  EOF until the file is actually complete. This lets playback start
 *  while the file is still downloading.
 *
 *  When both providers return their defaults (`-1` and `true`), the
 *  DataSource behaves exactly as before — fully-available local file,
 *  no waiting. This preserves the existing local-playback path.
 */
@UnstableApi
class AesCbcRandomAccessDataSource(
    private val sessionRepository: SessionRepository,
    /**
     * Returns how many bytes of the file at [uri] are currently
     * available to read. `-1L` means "the whole file is available"
     * (default — local, fully-downloaded file). A positive value
     * means the file is still being written and only that many bytes
     * are safe to read; reads past this point block until more bytes
     * arrive (see [BlockingInputStream]).
     */
    private val availableBytesProvider: (Uri) -> Long = { -1L },
    /**
     * Returns `true` if the download of the file at [uri] has
     * completed (success OR failure). When `true`, reads past
     * [availableBytesProvider] return `-1` (real EOF) instead of
     * blocking. Default `true` — no download in flight.
     */
    private val downloadCompleteProvider: (Uri) -> Boolean = { true },
) : DataSource {

    private var inputStream: CipherInputStream? = null
    private var fileInputStream: FileInputStream? = null
    private lateinit var uri: Uri

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        uri.path ?: return 0

        val file = File(uri.path!!).canonicalFile

        // --- Wait for the file to exist and have at least the version byte ---
        // For progressive streaming: the file may not exist yet when ExoPlayer
        // calls open(). Block (poll) until at least 1 byte is available or the
        // download completes (which may have failed with 0 bytes).
        waitForBytesAvailable(minBytes = 1L)

        val fis = FileInputStream(file)
        fileInputStream = fis
        val channel = fis.channel

        // --- Read header ---
        // Wait for the full header to be available before reading.
        // We don't know the header size until we read the version byte, so we
        // wait for 1 byte first, read the version, then wait for the full header.
        waitForBytesAvailable(minBytes = 1L)

        val versionBuf = ByteBuffer.allocate(1)
        channel.position(0)
        channel.read(versionBuf)
        versionBuf.flip()

        val version = EncryptionVersionByte.fromValue(versionBuf.get())

        // Wait for the full header to be available.
        waitForBytesAvailable(minBytes = version.headerSize.toLong())

        channel.position(0)
        val headerBuf = ByteBuffer.allocate(version.headerSize)
        channel.read(headerBuf)
        headerBuf.flip()

        // Read/skip version byte since we have the whole header
        headerBuf.get()

        val fileIv = ByteArray(IV_SIZE)

        when (version) {
            EncryptionVersionByte.One -> {
                val salt = ByteArray(SALT_SIZE)
                headerBuf.get(salt)

                headerBuf.get(fileIv)
            }
            EncryptionVersionByte.Two -> {
                headerBuf.get(fileIv)
            }
            EncryptionVersionByte.Three -> {
                // ─── Sprint 1 / P6: GCM videos are NOT supported here ─────────
                // Video originals are still encrypted with CBC (version 2) by
                // PhotoRepository.createPhotoFile (which passes useGcm=false
                // for PhotoType.isVideo). If we ever see a version-3 file in
                // this DataSource, either:
                //   (a) a future change started encrypting videos with GCM
                //       without updating this DataSource to handle GCM's
                //       CTR-mode random access — a bug; OR
                //   (b) the file is not actually a video but somehow ended up
                //       being opened by ExoPlayer — also a bug.
                // Either way, fail loudly rather than silently corrupting
                // playback by treating GCM ciphertext as CBC.
                throw IOException(
                    "AesCbcRandomAccessDataSource: refusing to stream version-3 (GCM) " +
                        "file — GCM video streaming is not yet supported. " +
                        "Video originals should be encrypted with CBC (version 2). " +
                        "Check PhotoRepository.createPhotoFile's useGcm flag."
                )
            }
        }

        // --- Resolve key  ---
        // Sprint 10+ fix: use get() instead of require() to avoid crash if
        // the session was cleared (e.g. by BFU-Safe or lock timeout during
        // video playback). If null, throw a clear error instead of crashing.
        val key = sessionRepository.get()?.vmk
            ?: throw java.io.IOException("Vault is locked — cannot decrypt video stream")

        // --- Compute target block ---
        val plainOffset = dataSpec.position
        val blockIndex = (plainOffset / BLOCK_SIZE).toInt()
        val discard = (plainOffset % BLOCK_SIZE).toInt()

        // --- Resolve IV for target block ---
        val ivForTarget = if (blockIndex == 0) {
            fileIv
        } else {
            val prevCipherOffset = version.headerSize + (blockIndex - 1L) * BLOCK_SIZE
            // Wait for the previous ciphertext block (needed as IV) to be available.
            waitForBytesAvailable(minBytes = prevCipherOffset + BLOCK_SIZE)
            channel.position(prevCipherOffset)
            val prevCipher = ByteArray(BLOCK_SIZE)
            channel.read(ByteBuffer.wrap(prevCipher))
            prevCipher
        }

        // --- Position channel at the target ciphertext block ---
        val targetCipherOffset = version.headerSize + blockIndex.toLong() * BLOCK_SIZE
        // Wait for the target ciphertext block to be available so the first
        // read doesn't immediately EOF. We only need the start of the block;
        // subsequent reads block in [BlockingInputStream] as needed.
        waitForBytesAvailable(minBytes = targetCipherOffset + 1L)
        channel.position(targetCipherOffset)

        // --- Create cipher stream from this point ---
        val cipher = Cipher.getInstance(Algorithm.AesCbcPkcs7Padding.value)
        cipher.init(Cipher.DECRYPT_MODE, key, IvParameterSpec(ivForTarget))

        // Wrap the channel's input stream in a BlockingInputStream that waits
        // when the read position catches up to the available-bytes watermark.
        val sourceStream = Channels.newInputStream(channel)
        val blockingStream = BlockingInputStream(
            source = sourceStream,
            channel = channel,
            availableBytes = { availableBytesProvider(uri) },
            downloadComplete = { downloadCompleteProvider(uri) },
        )

        inputStream = CipherInputStream(blockingStream, cipher)

        // --- Discard bytes inside the first decrypted block ---
        if (discard > 0) {
            val skip = ByteArray(discard)
            inputStream?.read(skip, 0, discard)
        }

        return dataSpec.length
    }

    @Throws(IOException::class)
    override fun read(target: ByteArray, offset: Int, length: Int): Int =
        if (length == 0) 0 else inputStream?.read(target, offset, length) ?: 0

    override fun addTransferListener(transferListener: TransferListener) {}

    override fun getUri(): Uri = uri

    override fun close() {
        inputStream?.close()
        fileInputStream?.close()
    }

    /**
     * Block (poll) until [availableBytesProvider] reports that at least
     * [minBytes] bytes are available, or the download completes (which may
     * have produced fewer than [minBytes] bytes — in that case we return
     * anyway and let the subsequent channel read fail naturally), or the
     * timeout expires.
     *
     * No-op when the file is fully available (provider returns `-1`).
     *
     * This runs on ExoPlayer's loading thread — blocking here is safe and
     * does NOT stall the UI.
     */
    private fun waitForBytesAvailable(minBytes: Long) {
        if (availableBytesProvider(uri) < 0) return // no limit — fully available

        val deadline = System.currentTimeMillis() + WAIT_TIMEOUT_MS
        while (true) {
            val avail = availableBytesProvider(uri)
            if (avail < 0) return // file became fully available (e.g. entry removed)
            if (avail >= minBytes) return // enough data
            if (downloadCompleteProvider(uri)) {
                // Download finished with fewer than minBytes — bail out and let
                // the channel read fail naturally (FileChannel.read returns -1
                // → ByteBuffer.get throws BufferUnderflowException → ExoPlayer
                // surfaces a playback error).
                return
            }
            if (System.currentTimeMillis() > deadline) {
                throw IOException(
                    "Timeout waiting for download: needed $minBytes bytes, " +
                        "only $avail available after ${WAIT_TIMEOUT_MS}ms",
                )
            }
            try {
                Thread.sleep(WAIT_POLL_INTERVAL_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("Interrupted while waiting for download", e)
            }
        }
    }

    /**
     * InputStream wrapper that blocks when the underlying channel reaches the
     * available-bytes watermark. Used to keep [CipherInputStream] from seeing
     * an EOF (which would finalize the cipher and end playback prematurely)
     * while the file is still being downloaded.
     *
     * - Reads are capped to the available-bytes window so the underlying
     *   channel never returns -1 prematurely.
     * - When the window is exhausted and the download is still in flight,
     *   polls until more bytes arrive.
     * - When the window is exhausted and the download is complete, returns
     *   -1 (real EOF) so the cipher finalizes and ExoPlayer ends playback
     *   cleanly.
     * - When [availableBytes] returns `-1`, no limiting/blocking is applied
     *   (fully-available local file — original behavior).
     */
    private class BlockingInputStream(
        private val source: InputStream,
        private val channel: FileChannel,
        private val availableBytes: () -> Long,
        private val downloadComplete: () -> Boolean,
    ) : InputStream() {

        override fun read(): Int {
            val b = ByteArray(1)
            val n = read(b, 0, 1)
            return if (n <= 0) -1 else b[0].toInt() and 0xFF
        }

        @Throws(IOException::class)
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            val deadline = System.currentTimeMillis() + WAIT_TIMEOUT_MS
            while (true) {
                val avail = availableBytes()
                // No limit — fully-available file, just delegate.
                if (avail < 0) return source.read(b, off, len)

                val pos = channel.position()
                if (pos < avail) {
                    // We have data available. Cap the read to the available
                    // window so the underlying channel never returns -1
                    // prematurely (which would make CipherInputStream finalize
                    // the cipher).
                    val maxToRead = minOf(len.toLong(), avail - pos).toInt().coerceAtLeast(1)
                    val n = source.read(b, off, maxToRead)
                    if (n > 0) return n
                    // n <= 0 — unexpected (we knew data was available). Fall
                    // through to the wait/recheck logic below.
                }

                // pos >= avail — at end of available data.
                if (downloadComplete()) {
                    // Download is done. Re-check once more in case the
                    // available-bytes watermark advanced between the check
                    // above and now (race with the writer).
                    val finalAvail = availableBytes()
                    val finalPos = channel.position()
                    if (finalPos < finalAvail) {
                        val maxToRead = minOf(len.toLong(), finalAvail - finalPos).toInt().coerceAtLeast(1)
                        val n = source.read(b, off, maxToRead)
                        if (n > 0) return n
                    }
                    return -1 // real EOF
                }

                // Download still in flight — wait for more data.
                if (System.currentTimeMillis() > deadline) {
                    throw IOException(
                        "Timeout waiting for download: stuck at pos $pos, " +
                            "available $avail, after ${WAIT_TIMEOUT_MS}ms",
                    )
                }
                try {
                    Thread.sleep(WAIT_POLL_INTERVAL_MS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("Interrupted while waiting for download", e)
                }
            }
        }
    }

    companion object {
        /**
         * How long to block waiting for more download data before giving up
         * and surfacing an IOException to ExoPlayer. 30s is generous enough
         * for a network hiccup but short enough that a genuinely-stalled
         * download doesn't hang playback forever.
         */
        private const val WAIT_TIMEOUT_MS = 30_000L

        /**
         * Poll interval for the wait loops. 50ms is responsive enough that
         * playback starts within ~1 frame of the data arriving, without
         * burning CPU.
         */
        private const val WAIT_POLL_INTERVAL_MS = 50L
    }
}
