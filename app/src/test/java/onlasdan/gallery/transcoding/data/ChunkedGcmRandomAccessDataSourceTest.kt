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

import onlasdan.gallery.encryption.domain.SessionRepository
import onlasdan.gallery.encryption.domain.crypto.ChunkedGcmOutputStream
import onlasdan.gallery.encryption.domain.crypto.CHUNK_SIZE
import onlasdan.gallery.encryption.domain.models.VaultSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException
import javax.crypto.spec.SecretKeySpec

@androidx.media3.common.util.UnstableApi
class ChunkedGcmRandomAccessDataSourceTest {
	private val vmk: javax.crypto.SecretKey = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")

	private fun makeSessionRepository(session: VaultSession?): SessionRepository =
		object : SessionRepository {
			override fun get(): VaultSession? = session
			override fun set(session: VaultSession) { /* no-op for test */ }
			override fun require(): VaultSession = session ?: error("no session")
			override fun reset() { /* no-op */ }
		}

	private fun makeVaultFile(plaintext: ByteArray): File {
		val tmp = File.createTempFile("test_v0x04_", ".crypt")
		tmp.deleteOnExit()
		val fos = java.io.FileOutputStream(tmp)
		val out = ChunkedGcmOutputStream(fos, vmk)
		out.write(plaintext)
		out.close()
		return tmp
	}

	private fun makeDataSource(file: File, sessionRepo: SessionRepository): ChunkedGcmRandomAccessDataSource =
		ChunkedGcmRandomAccessDataSource(
			sessionRepository = sessionRepo,
			availableBytesProvider = { _ -> -1L },     // always "fully available"
			downloadCompleteProvider = { _ -> true },
		)

	private fun makeDataSpec(file: File, position: Long = 0L): androidx.media3.datasource.DataSpec =
		androidx.media3.datasource.DataSpec(android.net.Uri.fromFile(file), position, androidx.media3.common.C.LENGTH_UNBOUNDED.toLong())

	@Test
	fun `F-002 open() returns non-negative when seek exactly at totalPlaintextSize`() {
		// Write a file with known plaintext size, then seek to exactly the end.
		val plaintext = ByteArray(100) { it.toByte() }
		val file = makeVaultFile(plaintext)
		val repo = makeSessionRepository(VaultSession(vmk))
		val ds = makeDataSource(file, repo)
		val dataSpec = makeDataSpec(file, position = plaintext.size.toLong()) // seek == totalPlaintextSize
		val returned = ds.open(dataSpec)
		assertTrue("open() must return non-negative, got $returned", returned >= 0)
		ds.close()
	}

	@Test
	fun `F-002 open() throws IOException when seek past EOF`() {
		val plaintext = ByteArray(100) { it.toByte() }
		val file = makeVaultFile(plaintext)
		val repo = makeSessionRepository(VaultSession(vmk))
		val ds = makeDataSource(file, repo)
		val dataSpec = makeDataSpec(file, position = plaintext.size.toLong() + 1L) // past EOF
		try {
			ds.open(dataSpec)
			fail("open() must throw IOException when dataSpec.position > totalPlaintextSize")
		} catch (e: IOException) {
			assertTrue("expected 'EOF' message, got: ${e.message}", e.message?.contains("EOF") == true)
		}
		ds.close()
	}

	@Test
	fun `F-002 open() return value equals remaining bytes for valid seek`() {
		val plaintext = ByteArray(1000) { it.toByte() }
		val file = makeVaultFile(plaintext)
		val repo = makeSessionRepository(VaultSession(vmk))
		val ds = makeDataSource(file, repo)
		val seekPos = 200L
		val dataSpec = makeDataSpec(file, position = seekPos)
		val returned = ds.open(dataSpec)
		assertEquals("remaining bytes", plaintext.size - seekPos, returned)
		ds.close()
	}
}
