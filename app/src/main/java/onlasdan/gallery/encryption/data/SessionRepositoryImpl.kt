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

package onlasdan.gallery.encryption.data

import onlasdan.gallery.encryption.domain.SessionRepository
import onlasdan.gallery.encryption.domain.models.VaultSession
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SessionRepositoryImpl
	@Inject
	constructor() : SessionRepository {
		private var session: VaultSession? = null

		override fun set(session: VaultSession) {
			// If there's an existing session being replaced, destroy its VMK
			// before overwriting the reference — prevents the old key from
			// lingering in heap until GC collects it.
			this.session?.let { destroySession(it) }
			this.session = session
		}

		override fun get(): VaultSession? = session

		override fun require(): VaultSession = session ?: error("Vault is locked")

		override fun reset() {
			// Sprint 10+ / (roadmap #7) — Explicitly zero the VMK bytes before
			// dereferencing the session. Kotlin/JVM does NOT guarantee when
			// GC will collect the SecretKey object — the raw key bytes can
			// linger in heap for seconds to minutes after reset().
			//
			// SecretKeySpec (the typical implementation of SecretKey used by
			// KeyGen.generateVaultMasterKey) implements javax.security.auth.Destroyable.
			// Calling destroy() zeros the internal byte[] that holds the key material.
			//
			// This is a defense-in-depth measure: it doesn't prevent a live memory
			// dump while the vault is unlocked, but it ensures the key is gone
			// from heap as soon as the user locks / backgrounds the app.
			session?.let { destroySession(it) }
			session = null
		}

		/**
		 * Best-effort VMK zeroing. Calls [Destroyable.destroy()] on the VMK's
		 * SecretKey, which zeros the internal byte[] in SecretKeySpec.
		 *
		 * Non-fatal: some SecretKey implementations (e.g. AndroidKeyStore-backed
		 * keys) may not support destroy() — in that case the key material stays
		 * in the KeyStore's native backing store, which is acceptable (it's
		 * hardware-isolated).
		 */
		// F-WARN-011: removed redundant `is Destroyable` check — SecretKey extends Key
		// which extends Destroyable (since Java 9), so the check always evaluated to true.
		private fun destroySession(s: VaultSession) {
			try {
				val vmk = s.vmk
				if (!vmk.isDestroyed) {
					vmk.destroy()
				}
			} catch (e: Exception) {
				// destroy() may throw DestroyFailedException on some implementations.
				// Best-effort — the key will eventually be GC'd anyway.
				Timber.d(e, "SessionRepository: VMK destroy() failed (non-fatal) — key will be GC'd")
			}
		}
	}
