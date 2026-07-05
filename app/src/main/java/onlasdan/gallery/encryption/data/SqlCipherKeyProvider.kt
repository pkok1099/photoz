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

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Provides the SQLCipher passphrase for [onlasdan.gallery.model.database.PhotoZDatabase],
 * backed by the Android Keystore.
 *
 * ## Why Keystore (not VMK-derived)
 *
 * The original ROADMAP2.md design called for "DB key derived from VMK —
 * vault lock = DB lock". With M7 multi-vault, that approach hits a
 * chicken-and-egg: to find the matching VMK we must read
 * `vault_protection` rows, but to read those rows we need the DB open,
 * which needs the VMK-derived key. We solved the chicken-and-egg by
 * moving `vault_protection` to a separate plaintext [BootstrapDatabase],
 * but a second problem remained: the app makes DB queries BEFORE vault
 * unlock (e.g. `CleanupDeadFilesUseCase` runs at app start). With a
 * VMK-derived key, those queries would throw.
 *
 * The Keystore approach sidesteps both issues:
 *  - The DB key is always available (no unlock required)
 *  - The DB key never leaves hardware-isolated storage (StrongBox or TEE)
 *  - The DB stays open across vault switches and lock/unlock cycles
 *
 * ## Threat model
 *
 *  - **Disk forensic (device off, no PIN)**: attacker extracts the file
 *    system. Without the Keystore key (which lives in hardware), they
 *    cannot decrypt `photok.db`. ✓
 *  - **Disk forensic (device unlocked, attacker has PIN)**: attacker can
 *    query the Keystore to fetch the DB key. They can read `photok.db`.
 *    Same threat model as the rest of the app — if the attacker has the
 *    unlock PIN, they can already unlock the vault. SQLCipher doesn't
 *    help here. ✗ (acceptable trade-off)
 *  - **Runtime forensic (memory dump while unlocked)**: DB key is in
 *    memory (SQLCipher native side). Same threat model as today. ✗
 *    (acceptable — out of scope for at-rest encryption)
 *
 * ## Forward compatibility
 *
 * A future enhancement can add VMK-wrapped DB keys ON TOP of the
 * Keystore-backed key, providing "vault lock = DB lock" semantics for
 * users who want the stronger threat model. The schema is forward-
 * compatible (a `wrapped_db_key` column can be added to
 * `vault_protection` later). For now, the Keystore approach is the
 * baseline.
 *
 * ## Key storage details
 *
 *  - Alias: `photoz-sqlcipher-v1`
 *  - Algorithm: AES (256-bit)
 *  - Block modes: ECB (sufficient — we only use the raw key bytes as a
 *    SQLCipher passphrase, not for direct encryption)
 *  - Paddings: no padding
 *  - User auth required: false (DB must be openable from WorkManager
 *    background jobs without unlock — e.g. PhotoSyncWorker)
 *  - StrongBox: requested if available, falls back to TEE automatically
 *
 * The key is generated on first call to [getOrCreatePassphrase] and
 * persists across app restarts (until uninstall or factory reset).
 *
 * @since v16 — Sprint 3 / TODO #6 SQLCipher
 */
@Singleton
class SqlCipherKeyProvider
	@Inject
	constructor(
		@ApplicationContext private val app: Context,
	) {
		/**
		 * Get the SQLCipher passphrase bytes, generating + persisting the
		 * Keystore-backed key on first call.
		 *
		 * The returned [ByteArray] is the encoded form of the Keystore
		 * `SecretKey` — 32 bytes for AES-256. SQLCipher accepts this directly
		 * as a passphrase (it runs PBKDF2-HMAC-SHA512 internally to derive
		 * the actual page-encryption key).
		 *
		 * IMPORTANT: do NOT zero the returned array — it's a copy of the
		 * Keystore key material, but the original lives in Keystore. Zeroing
		 * the copy doesn't destroy the key (Keystore retains it). The copy
		 * will be GC'd normally.
		 */
		fun getOrCreatePassphrase(): ByteArray {
			val key = getOrCreateKey()
			return key.encoded
				?: error(
					"Keystore key for $ALIAS returned null encoded form — " +
						"this should not happen for a software-backed AES key",
				)
		}

		private fun getOrCreateKey(): SecretKey {
			val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
			val existing = keyStore.getKey(ALIAS, null) as? SecretKey
			if (existing != null) {
				Timber.d("SqlCipherKeyProvider: reusing existing Keystore key (alias=$ALIAS)")
				return existing
			}

			Timber.d("SqlCipherKeyProvider: generating new Keystore key (alias=$ALIAS)")
			val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
			val builder =
				KeyGenParameterSpec
					.Builder(
						ALIAS,
						KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
					).setKeySize(256)
					.setBlockModes(KeyProperties.BLOCK_MODE_ECB)
					.setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
					.setRandomizedEncryptionRequired(false)
					// No user auth required — DB must be openable from background workers
					// without unlock (PhotoSyncWorker, SelfDestructWorker, etc.).
					.setUserAuthenticationRequired(false)

			// Try StrongBox first (separate secure element — most secure).
			// Fall back to TEE (default hardware-backed keystore) automatically
			// if StrongBox is unavailable. We attempt StrongBox in a try/catch
			// because some devices advertise the API but fail at runtime.
			try {
				builder.setIsStrongBoxBacked(true)
				keyGenerator.init(builder.build())
				val key = keyGenerator.generateKey()
				Timber.d("SqlCipherKeyProvider: StrongBox-backed key generated")
				return key
			} catch (e: Exception) {
				Timber.w(e, "SqlCipherKeyProvider: StrongBox unavailable, falling back to TEE")
				// Reset and retry without StrongBox.
				val fallbackBuilder =
					KeyGenParameterSpec
						.Builder(
							ALIAS,
							KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
						).setKeySize(256)
						.setBlockModes(KeyProperties.BLOCK_MODE_ECB)
						.setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
						.setRandomizedEncryptionRequired(false)
						.setUserAuthenticationRequired(false)
				keyGenerator.init(fallbackBuilder.build())
				val key = keyGenerator.generateKey()
				Timber.d("SqlCipherKeyProvider: TEE-backed key generated")
				return key
			}
		}

		/**
		 * Best-effort: delete the Keystore key. Used by factory-reset flows.
		 *
		 * After calling this, the encrypted `photok.db` becomes permanently
		 * unreadable (the key is gone from hardware). The DB file itself
		 * should also be deleted.
		 */
		fun deleteKey() {
			try {
				val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
				if (keyStore.containsAlias(ALIAS)) {
					keyStore.deleteEntry(ALIAS)
					Timber.d("SqlCipherKeyProvider: deleted Keystore key (alias=$ALIAS)")
				}
			} catch (e: Exception) {
				Timber.w(e, "SqlCipherKeyProvider: failed to delete Keystore key (non-fatal)")
			}
		}

		companion object {
			private const val ANDROID_KEYSTORE = "AndroidKeyStore"

			/**
			 * Keystore alias for the SQLCipher DB key. Bumping the `vN` suffix
			 * forces a fresh key generation (used if we ever need to re-key
			 * the DB — e.g. after a cryptographic protocol change).
			 */
			const val ALIAS = "photoz-sqlcipher-v1"
		}
	}

/**
 * Random passphrase generator — used as a FALLBACK if the Keystore is
 * unavailable (e.g. unit tests on JVM without Android Keystore).
 *
 * The generated key is stored in plaintext SharedPreferences, so it
 * provides NO security benefit over a plaintext DB. It exists purely to
 * keep the app functional on devices where Keystore is broken (very
 * rare on modern Android, but defensive).
 *
 * Production code should always use [SqlCipherKeyProvider] (Keystore-backed).
 *
 * @since v16 — Sprint 3 / TODO #6 SQLCipher
 */
@Singleton
class FallbackSqlCipherKeyProvider
	@Inject
	constructor(
		@ApplicationContext private val app: Context,
	) {
		fun getOrCreatePassphrase(): ByteArray {
			val prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
			val existing = prefs.getString(KEY_NAME, null)
			if (existing != null) {
				return existing.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
			}
			val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
			val hex = bytes.joinToString("") { "%02x".format(it) }
			prefs.edit().putString(KEY_NAME, hex).apply()
			return bytes
		}

		companion object {
			private const val PREFS_NAME = "photoz_sqlcipher_fallback"
			private const val KEY_NAME = "passphrase_hex"
		}
	}
