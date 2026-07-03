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

package onlasdan.gallery.sync.work

import android.app.Application
import android.content.Context
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import onlasdan.gallery.encryption.domain.SessionRepository
import onlasdan.gallery.settings.data.Config
import onlasdan.gallery.sync.rclone.RcloneController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.lang.reflect.Method
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Unit tests for [HashRegistry] — exercises the registry JSON
 * serialization/deserialization round-trip, the GZIP compression round-trip,
 * and the full GCM encryption/decryption round-trip through the public
 * [HashRegistry.uploadToRemote] / [HashRegistry.downloadAndCache] pipeline
 * with a mocked [RcloneController] capturing the on-wire bytes.
 *
 * The VMK is a freshly generated random 256-bit AES key — no Android Keystore
 * involvement (the registry uses raw JCA `Cipher.getInstance("AES/GCM/NoPadding")`).
 *
 * @since Batch 3 — unit tests for key components
 */
@RunWith(RobolectricTestRunner::class)
class HashRegistryTest {

    private val app: Application by lazy { RuntimeEnvironment.getApplication() }
    private val rcloneController: RcloneController by lazy { mockk(relaxed = true) }
    private val dao: HashRegistryDao by lazy { mockk(relaxed = true) }
    private val sessionRepository: SessionRepository by lazy { mockk(relaxed = true) }

    private val registry: HashRegistry by lazy {
        HashRegistry(app, rcloneController, dao, sessionRepository)
    }

    /** Mock VMK — fresh random 256-bit AES key per test. */
    private val vmk: ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }

    @Before
    fun setUp() {
        // The registry reads the chosen remote name from the app's SharedPreferences.
        // Pre-seed it so uploadToRemote / downloadAndCache don't bail out early.
        val prefs = app.getSharedPreferences(Config.FILE_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(Config.SYNC_CHOSEN_REMOTE, "testremote").commit()
    }

    // ─── JSON serialization round-trip ──────────────────────────────────────

    /**
     * serialize → parse must round-trip all entry fields exactly.
     * Tests the JSON format separately from the crypto (per task spec).
     */
    @Test
    fun `serializeRegistry then parseRegistryJson round-trips entries`() {
        val entries = listOf(
            HashRegistryEntry(
                contentHash = "hash-1",
                uuid = "uuid-1",
                filename = "photo.jpg",
                albumPath = "Camera/2024",
                size = 1_048_576L,
                type = "JPEG",
                thumbnailPack = null,
                thumbnailOffset = 0L,
                thumbnailLength = 0L,
                deleted = false,
            ),
            HashRegistryEntry(
                contentHash = "hash-2",
                uuid = "uuid-2",
                filename = "video.mp4",
                albumPath = null,
                size = 10_485_760L,
                type = "MP4",
                thumbnailPack = "pack-0001",
                thumbnailOffset = 4_096L,
                thumbnailLength = 17_361L,
                deleted = true,
            ),
        )

        val json = invokePrivate("serializeRegistry", entries) as String
        assertTrue("JSON must be versioned", json.contains("\"version\":1"))
        assertTrue("JSON must contain entries array", json.contains("\"entries\":["))

        val parsed = invokePrivate("parseRegistryJson", json) as List<*>
        assertEquals("Round-trip must preserve entry count", entries.size, parsed.size)

        val parsed1 = parsed[0] as HashRegistryEntry
        assertEquals(entries[0].contentHash, parsed1.contentHash)
        assertEquals(entries[0].uuid, parsed1.uuid)
        assertEquals(entries[0].filename, parsed1.filename)
        assertEquals(entries[0].albumPath, parsed1.albumPath)
        assertEquals(entries[0].size, parsed1.size)
        assertEquals(entries[0].type, parsed1.type)
        assertEquals(entries[0].thumbnailPack, parsed1.thumbnailPack)
        assertEquals(entries[0].thumbnailOffset, parsed1.thumbnailOffset)
        assertEquals(entries[0].thumbnailLength, parsed1.thumbnailLength)
        assertEquals(entries[0].deleted, parsed1.deleted)

        val parsed2 = parsed[1] as HashRegistryEntry
        assertEquals(entries[1].contentHash, parsed2.contentHash)
        assertEquals(entries[1].uuid, parsed2.uuid)
        assertEquals(entries[1].albumPath, parsed2.albumPath)
        assertEquals(entries[1].thumbnailPack, parsed2.thumbnailPack)
        assertEquals(entries[1].deleted, parsed2.deleted)
    }

    /** An empty entry list must serialize to a valid empty-registry JSON and parse back to empty. */
    @Test
    fun `serializeRegistry with empty list round-trips to empty list`() {
        val json = invokePrivate("serializeRegistry", emptyList<HashRegistryEntry>()) as String
        val parsed = invokePrivate("parseRegistryJson", json) as List<*>
        assertEquals(0, parsed.size)
    }

    /** Special characters in filenames must survive the round-trip (escapeJson handles `"`, `\`, newlines). */
    @Test
    fun `serializeRegistry preserves special characters in filename`() {
        val entries = listOf(
            HashRegistryEntry(
                contentHash = "h",
                uuid = "u",
                filename = """weird"name\with\nnewline""",
                albumPath = "path/with\"quote",
                size = 0L,
                type = "JPEG",
                thumbnailPack = null,
                thumbnailOffset = 0L,
                thumbnailLength = 0L,
                deleted = false,
            ),
        )
        val json = invokePrivate("serializeRegistry", entries) as String
        val parsed = invokePrivate("parseRegistryJson", json) as List<*>
        val parsedEntry = parsed[0] as HashRegistryEntry
        assertEquals(entries[0].filename, parsedEntry.filename)
        assertEquals(entries[0].albumPath, parsedEntry.albumPath)
    }

    // ─── GZIP compression round-trip ────────────────────────────────────────

    /** gzipCompress → gzipDecompressToString must round-trip arbitrary UTF-8 strings. */
    @Test
    fun `gzipCompress then gzipDecompressToString round-trips data`() {
        val original = "Hello, world! ".repeat(1000) + "unicode: αβγδ 你好 🚀"
        val data = original.toByteArray(Charsets.UTF_8)

        val compressed = invokePrivate("gzipCompress", data) as ByteArray
        assertNotEquals("Compressed data must differ from plaintext", data.toList(), compressed.toList())

        val decompressed = invokePrivate("gzipDecompressToString", compressed) as String
        assertEquals(original, decompressed)
    }

    /** GZIP must actually shrink text-heavy JSON-like input (the registry's whole reason for using it). */
    @Test
    fun `gzipCompress shrinks text-heavy input`() {
        val text = "{\"content_hash\":\"abc\",\"uuid\":\"xyz\"}".repeat(500)
        val data = text.toByteArray(Charsets.UTF_8)

        val compressed = invokePrivate("gzipCompress", data) as ByteArray
        assertTrue(
            "GZIP must shrink repetitive text input (orig=${data.size}, compressed=${compressed.size})",
            compressed.size < data.size / 2,
        )
    }

    // ─── GCM encryption/decryption round-trip via upload/download ──────────

    /**
     * End-to-end round-trip: upload entries to remote (encrypts with VMK via
     * AES-256-GCM + GZIP), then download them back (decrypts + decompresses +
     * parses). The mocked rclone controller captures the uploaded temp-file
     * bytes and returns them on the next download call.
     */
    @Test
    fun `uploadToRemote then downloadAndCache round-trips entries through GCM and GZIP`() = runTest {
        val entries = listOf(
            HashRegistryEntry(
                contentHash = "gcm-hash-1",
                uuid = "gcm-uuid-1",
                filename = "photo1.jpg",
                albumPath = "Album1",
                size = 100_000L,
                type = "JPEG",
                thumbnailPack = null,
                thumbnailOffset = 0L,
                thumbnailLength = 0L,
                deleted = false,
            ),
            HashRegistryEntry(
                contentHash = "gcm-hash-2",
                uuid = "gcm-uuid-2",
                filename = "photo2.png",
                albumPath = null,
                size = 200_000L,
                type = "PNG",
                thumbnailPack = "pack-0001",
                thumbnailOffset = 0L,
                thumbnailLength = 8192L,
                deleted = false,
            ),
        )

        coEvery { dao.getAll() } returns entries

        // ─── Capture uploaded bytes from uploadToRemote ────────────────────
        // uploadToRemote writes the encrypted blob to a temp file in cacheDir,
        // then calls rcloneController.uploadFile(tempFile.absolutePath, remotePath).
        // We capture the temp file's bytes by reading them inside the mock.
        val capturedBytesSlot = slot<ByteArray>()
        coEvery { rcloneController.uploadFile(any(), any()) } answers {
            val localPath = firstArg<String>()
            val bytes = File(localPath).readBytes()
            capturedBytesSlot.captured = bytes
            Result.success(Unit)
        }

        registry.uploadToRemote(vmk)

        assertTrue("Upload must capture the encrypted bytes", capturedBytesSlot.isCaptured)
        val onWireBytes = capturedBytesSlot.captured
        assertTrue("On-wire bytes must include the 1-byte version prefix + 12-byte nonce + ciphertext",
            onWireBytes.size > 1 + 12 + 16)

        // The first byte must be the GZIP-format version tag (0x02).
        assertEquals(
            "On-wire format must start with version byte 0x02 (GZIP format)",
            0x02.toByte(),
            onWireBytes[0],
        )

        // ─── Feed captured bytes back through downloadAndCache ─────────────
        // downloadAndCache calls rcloneController.downloadFile(remotePath, localPath)
        // then reads the temp file. We write the captured bytes to the temp file
        // inside the mock.
        coEvery { rcloneController.downloadFile(any(), any()) } answers {
            val localPath = secondArg<String>()
            File(localPath).writeBytes(onWireBytes)
            Result.success(Unit)
        }

        // downloadAndCache REPLACES the local cache via dao.clear() + dao.upsertAll(entries).
        // Track the upserted entries so we can assert them.
        val upsertedSlot = slot<List<HashRegistryEntry>>()
        coEvery { dao.clear() } just Runs
        coEvery { dao.upsertAll(capture(upsertedSlot)) } just Runs

        val count = registry.downloadAndCache(vmk)

        assertEquals("downloadAndCache must return the entry count", entries.size, count)
        assertTrue("downloadAndCache must upsert the parsed entries", upsertedSlot.isCaptured)

        val upserted = upsertedSlot.captured
        assertEquals(entries.size, upserted.size)
        assertEquals(entries[0].contentHash, upserted[0].contentHash)
        assertEquals(entries[0].uuid, upserted[0].uuid)
        assertEquals(entries[0].filename, upserted[0].filename)
        assertEquals(entries[0].albumPath, upserted[0].albumPath)
        assertEquals(entries[0].size, upserted[0].size)
        assertEquals(entries[0].type, upserted[0].type)
        assertEquals(entries[0].deleted, upserted[0].deleted)

        assertEquals(entries[1].contentHash, upserted[1].contentHash)
        assertEquals(entries[1].thumbnailPack, upserted[1].thumbnailPack)
        assertEquals(entries[1].thumbnailOffset, upserted[1].thumbnailOffset)
        assertEquals(entries[1].thumbnailLength, upserted[1].thumbnailLength)
    }

    /**
     * downloadAndCache with the WRONG VMK must not crash (GCM auth-tag verification
     * throws AEADBadTagException — caught and returned as 0 entries).
     */
    @Test
    fun `downloadAndCache with wrong VMK returns zero entries without throwing`() = runTest {
        val wrongVmk = ByteArray(32).also { SecureRandom().nextBytes(it) }

        // Manually build an encrypted blob with the CORRECT vmk, then attempt
        // to downloadAndCache with the WRONG vmk.
        val plaintext = """{"version":1,"entries":[]}""".toByteArray(Charsets.UTF_8)
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val key = SecretKeySpec(vmk, "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
        val ciphertext = cipher.doFinal(plaintext)
        val onWireBytes = ByteArray(1 + 12 + ciphertext.size)
        onWireBytes[0] = 0x02
        System.arraycopy(nonce, 0, onWireBytes, 1, 12)
        System.arraycopy(ciphertext, 0, onWireBytes, 13, ciphertext.size)

        coEvery { rcloneController.downloadFile(any(), any()) } answers {
            val localPath = secondArg<String>()
            File(localPath).writeBytes(onWireBytes)
            Result.success(Unit)
        }
        coEvery { dao.clear() } just Runs
        coEvery { dao.upsertAll(any()) } just Runs

        val count = registry.downloadAndCache(wrongVmk)
        assertEquals("Wrong VMK must yield 0 entries (GCM auth-tag mismatch caught)", 0, count)
    }

    /**
     * downloadAndCache when the registry doesn't exist on the remote (fresh repo)
     * must return 0 — the download call fails and the registry treats it as empty.
     */
    @Test
    fun `downloadAndCache returns zero when registry not on remote`() = runTest {
        coEvery { rcloneController.downloadFile(any(), any()) } returns Result.failure(
            java.io.IOException("not found"),
        )

        val count = registry.downloadAndCache(vmk)
        assertEquals(0, count)
    }

    // ─── Helpers ────────────────────────────────────────────────────────────

    /** Invoke a private method on [registry] by name with a single argument. */
    private fun invokePrivate(methodName: String, arg: Any?): Any? {
        val method: Method = when (arg) {
            is String -> HashRegistry::class.java.getDeclaredMethod(methodName, String::class.java)
            is ByteArray -> HashRegistry::class.java.getDeclaredMethod(methodName, ByteArray::class.java)
            is List<*> -> HashRegistry::class.java.getDeclaredMethod(methodName, List::class.java)
            else -> error("Unsupported argument type for $methodName: ${arg?.javaClass}")
        }
        method.isAccessible = true
        return method.invoke(registry, arg)
    }

    /** Smoke-check that the test fixture's mocks are wired correctly. */
    @Test
    fun `test fixture wires up HashRegistry without error`() {
        assertNotNull(registry)
        assertNotNull(vmk)
        assertEquals(32, vmk.size)
    }
}
