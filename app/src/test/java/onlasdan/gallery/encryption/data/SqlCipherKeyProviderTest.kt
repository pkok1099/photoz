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

package onlasdan.gallery.encryption.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import onlasdan.gallery.settings.data.Config
import org.robolectric.RuntimeEnvironment

/**
 * Unit tests for [SqlCipherKeyProvider] and [FallbackSqlCipherKeyProvider].
 *
 * ## AndroidKeyStore availability
 *
 * Robolectric does NOT provide a fake `AndroidKeyStore` implementation by
 * default. The Keystore-backed [SqlCipherKeyProvider] tests are therefore
 * guarded by `assumeTrue(androidKeyStoreAvailable())` — they are SKIPPED
 * (not failed) when the Keystore is unavailable. This is the standard
 * JUnit pattern for environment-dependent tests.
 *
 * On real Android devices + instrumented tests (androidTest), the
 * AndroidKeyStore IS available and these tests will run.
 *
 * The [FallbackSqlCipherKeyProvider] tests always run — the fallback
 * uses SharedPreferences (no Keystore), so it works in Robolectric.
 *
 * @since v16 — Sprint 3 / TODO #6 SQLCipher
 */
@RunWith(RobolectricTestRunner::class)
class SqlCipherKeyProviderTest {
	/**
	 * Check if AndroidKeyStore is available in the current test environment.
	 * Returns true on real devices + instrumented tests, false in Robolectric.
	 */
	private fun androidKeyStoreAvailable(): Boolean =
		try {
			val ks = java.security.KeyStore.getInstance("AndroidKeyStore")
			ks.load(null)
			true
		} catch (e: Exception) {
			false
		}

	@Test
	fun `getOrCreatePassphrase returns 32 bytes`() {
		assumeTrue("AndroidKeyStore not available in Robolectric — skipping", androidKeyStoreAvailable())
		val provider = SqlCipherKeyProvider(RuntimeEnvironment.getApplication(), FallbackSqlCipherKeyProvider(RuntimeEnvironment.getApplication()), Config(RuntimeEnvironment.getApplication()))
		val passphrase = provider.getOrCreatePassphrase()
		assertEquals("SQLCipher passphrase must be 32 bytes (AES-256)", 32, passphrase.size)
	}

	@Test
	fun `getOrCreatePassphrase is non-zero sanity check`() {
		assumeTrue("AndroidKeyStore not available in Robolectric — skipping", androidKeyStoreAvailable())
		val provider = SqlCipherKeyProvider(RuntimeEnvironment.getApplication(), FallbackSqlCipherKeyProvider(RuntimeEnvironment.getApplication()), Config(RuntimeEnvironment.getApplication()))
		val passphrase = provider.getOrCreatePassphrase()
		val allZero = passphrase.all { it == 0.toByte() }
		assertTrue("Passphrase must not be all-zero (SecureRandom failure?)", !allZero)
	}

	@Test
	fun `getOrCreatePassphrase returns same bytes on repeated calls`() {
		assumeTrue("AndroidKeyStore not available in Robolectric — skipping", androidKeyStoreAvailable())
		val provider = SqlCipherKeyProvider(RuntimeEnvironment.getApplication(), FallbackSqlCipherKeyProvider(RuntimeEnvironment.getApplication()), Config(RuntimeEnvironment.getApplication()))
		val first = provider.getOrCreatePassphrase()
		val second = provider.getOrCreatePassphrase()
		// Same Keystore alias → same key. The encoded bytes must match.
		org.junit.Assert.assertArrayEquals(
			"Repeated getOrCreatePassphrase must return identical bytes",
			first,
			second,
		)
	}

	@Test
	fun `deleteKey clears the Keystore entry`() {
		assumeTrue("AndroidKeyStore not available in Robolectric — skipping", androidKeyStoreAvailable())
		val provider = SqlCipherKeyProvider(RuntimeEnvironment.getApplication(), FallbackSqlCipherKeyProvider(RuntimeEnvironment.getApplication()), Config(RuntimeEnvironment.getApplication()))
		// Generate the key first
		val first = provider.getOrCreatePassphrase()
		// Delete it
		provider.deleteKey()
		// Generate again — should be a NEW random key
		val second = provider.getOrCreatePassphrase()
		assertNotNull(second)
		assertEquals(32, second.size)
		// Note: we can't strictly assert first != second because there's
		// a 1 in 2^256 chance they're equal. But it's astronomically
		// unlikely — if this assertion ever fails, buy a lottery ticket.
		org.junit.Assert.assertNotEquals(
			"After deleteKey + regenerate, the new key should differ from the old",
			first.toList(),
			second.toList(),
		)
	}

	// ─── FallbackSqlCipherKeyProvider tests — always run (no Keystore needed) ──

	@Test
	fun `fallback provider returns 32 bytes`() {
		val provider = FallbackSqlCipherKeyProvider(RuntimeEnvironment.getApplication())
		val passphrase = provider.getOrCreatePassphrase()
		assertEquals(32, passphrase.size)
	}

	@Test
	fun `fallback provider returns same bytes across instances`() {
		// Two instances of the fallback provider share the same
		// SharedPreferences file — they should return the same passphrase
		// after the first call generates + persists it.
		val app = RuntimeEnvironment.getApplication()
		val first = FallbackSqlCipherKeyProvider(app).getOrCreatePassphrase()
		val second = FallbackSqlCipherKeyProvider(app).getOrCreatePassphrase()
		org.junit.Assert.assertArrayEquals(
			"Fallback provider must persist the passphrase across instances",
			first,
			second,
		)
	}

	@Test
	fun `fallback provider passphrase is non-zero`() {
		val provider = FallbackSqlCipherKeyProvider(RuntimeEnvironment.getApplication())
		val passphrase = provider.getOrCreatePassphrase()
		val allZero = passphrase.all { it == 0.toByte() }
		assertTrue("Fallback passphrase must not be all-zero", !allZero)
	}
}
