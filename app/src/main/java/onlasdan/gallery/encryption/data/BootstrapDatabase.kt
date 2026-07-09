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

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import onlasdan.gallery.model.database.Converters

/**
 * Plaintext bootstrap Room database — the chicken-and-egg solution for
 * SQLCipher-encrypted [onlasdan.gallery.model.database.PhotoZDatabase].
 *
 * ## Why this exists
 *
 * The main vault DB (`photok.db`) is encrypted with a 32-byte SQLCipher
 * key stored in Android Keystore (see [SqlCipherKeyProvider]). To unlock
 * a vault, [VaultService] reads `vault_protection` rows to find one
 * whose wrapped VMK successfully unwraps with the user's password.
 *
 * With the original "VMK-wrapped DB key" design (now deferred — see
 * `ROADMAP2.md`), this was a true chicken-and-egg: to read
 * `vault_protection` we need the DB open; to open the DB we need the
 * VMK; to get the VMK we need to read `vault_protection`. The bootstrap
 * DB was the escape hatch.
 *
 * With the current Keystore-backed design, the chicken-and-egg is
 * softer — the DB key is always available, so `photok.db` could
 * theoretically be opened at app start. But we still keep
 * `vault_protection` in a separate plaintext DB because:
 *  - It's a cleaner separation (keystore-ish metadata vs user data)
 *  - It future-proofs for the VMK-wrapped DB key enhancement
 *  - It makes `vault_protection` queries fast (no SQLCipher key check)
 *
 * ## What's in here
 *
 * Only [VaultProtectionTable] — the wrapped VMK, KDF params, and salt/IV.
 * Nothing user-facing (no photo metadata, no album paths, no EXIF). An
 * investigator with disk access sees only opaque wrapped blobs + KDF
 * parameters (salt, iteration count).
 *
 * ## Security trade-off
 *
 * The bootstrap DB itself is NOT encrypted. This is acceptable because:
 *  - `wrappedVMK` is encrypted with a password-derived KEK (PBKDF2 or
 *    Argon2id). Brute-forcing the password offline still requires
 *    PBKDF2/Argon2 work — same threat model as today.
 *  - The `vault_protection` rows reveal HOW MANY vaults exist and the
 *    KDF parameters used. This is a minor metadata leak compared to
 *    encrypting all photo/album metadata (the actual target of TODO #6).
 *
 * The SQLCipher DB key for `photok.db` is stored in Android Keystore
 * (see [SqlCipherKeyProvider]) — NOT wrapped per-vault with VMK. This
 * is a deliberate trade-off: it keeps background DB queries (e.g.
 * `CleanupDeadFilesUseCase` at app start) functional without vault
 * unlock, at the cost of NOT enforcing "vault lock = DB lock" semantics.
 * See `ROADMAP2.md` for the full rationale.
 *
 * @since v16 — Sprint 3 / TODO #6 SQLCipher
 */
@Database(
	entities = [VaultProtectionTable::class],
	version = 2, // F-ENC-017: bump v1→v2 for wrappedVMK → wrapped_vmk column rename
	exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class BootstrapDatabase : RoomDatabase() {
	abstract fun getVaultProtectionDao(): VaultProtectionDao

	companion object {
		/** Plaintext bootstrap DB filename (lives in app's filesDir). */
		const val DATABASE_NAME = "photok_meta.db"

		/**
		 * F-ENC-017: Rename column wrappedVMK → wrapped_vmk (snake_case consistency).
		 * SQLite supports ALTER TABLE RENAME COLUMN since API 30+.
		 */
		val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
			override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
				db.execSQL("ALTER TABLE vault_protection RENAME COLUMN wrappedVMK TO wrapped_vmk")
			}
		}
	}
}
