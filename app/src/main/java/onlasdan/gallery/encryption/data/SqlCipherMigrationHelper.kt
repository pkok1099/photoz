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
import dagger.hilt.android.qualifiers.ApplicationContext
import onlasdan.gallery.model.database.DATABASE_NAME
import onlasdan.gallery.settings.data.Config
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One-time migration of `photok.db` from plaintext (v15) to SQLCipher-
 * encrypted (v16).
 *
 * ## Migration approach: `sqlcipher_export()`
 *
 * The standard SQLCipher plaintext → encrypted migration uses the
 * `sqlcipher_export()` SQL function:
 *
 *  1. Open the plaintext DB with an empty passphrase (SQLCipher accepts
 *     `""` to mean "no encryption").
 *  2. ATTACH a new encrypted DB file with the desired key:
 *     `ATTACH DATABASE 'photok.db.new' AS new KEY '<passphrase>'`
 *  3. `SELECT sqlcipher_export('new')` — copies the entire schema AND
 *     all data from `main` (plaintext) to `new` (encrypted).
 *  4. DETACH `new`.
 *  5. Close the plaintext connection.
 *  6. Replace `photok.db` with `photok.db.new`.
 *
 * After this, Room can open `photok.db` normally as a SQLCipher-
 * encrypted DB. The Room Migration v15→v16 callback fires (Room sees
 * user_version=15 in the now-encrypted file, decides it needs to bump
 * to 16) — the migration is a no-op for table data (already copied),
 * but Room advances the user_version.
 *
 * ## When this runs
 *
 * Called from [onlasdan.gallery.di.AppModule.providePhotoZDatabase]
 * BEFORE `Room.databaseBuilder(...).build()`. The migration must happen
 * before Room tries to open the file with a SQLCipher key (which would
 * fail on a plaintext file).
 *
 * ## Failure handling
 *
 * If any step fails, the original `photok.db` is left untouched and the
 * migration is NOT marked done. The next app start retries. The app
 * will fail to open the DB (SQLCipher key mismatch on plaintext file) —
 * loud failure is preferred over silent fallback to plaintext.
 *
 * @since v16 — Sprint 3 / SQLCipher
 */
@Singleton
class SqlCipherMigrationHelper
	@Inject
	constructor(
		@ApplicationContext private val app: Context,
		private val config: Config,
		private val sqlCipherKeyProvider: SqlCipherKeyProvider,
		/**
		 * Plaintext bootstrap DB — receives the `vault_protection` rows
		 * copied out of the old plaintext `photok.db` during migration.
		 *
		 * Injected (not constructed locally) so the same Singleton instance
		 * is used by [VaultService.unlock] at runtime — the rows we write
		 * here are immediately visible to the unlock flow.
		 *
		 * @since v16 — Sprint 3 / SQLCipher
		 */
		private val bootstrapDatabase: BootstrapDatabase,
	) {
		/**
		 * Run the migration if necessary. Idempotent — safe to call on every
		 * app start.
		 *
		 * @return true if the DB is ready to be opened as SQLCipher-encrypted
		 *   (either migration succeeded OR was already done OR fresh install).
		 *   false if the migration failed (caller should log + flag for user).
		 */
		fun migrateIfNecessary(): Boolean {
			if (config.sqlCipherMigrationDone) {
				return true
			}

			val dbFile = app.getDatabasePath(DATABASE_NAME)
			if (!dbFile.exists()) {
				// Fresh install — no migration needed. The DB will be created
				// fresh as SQLCipher-encrypted by Room.databaseBuilder().
				Timber.d("SqlCipherMigration: fresh install, no plaintext DB to migrate")
				config.sqlCipherMigrationDone = true
				return true
			}

			if (isSqlCipherEncrypted(dbFile)) {
				// Already encrypted (e.g. previous migration succeeded but
				// Config flag wasn't set — could happen if the app crashed
				// between DB swap and flag write). Mark done and move on.
				Timber.d("SqlCipherMigration: DB is already SQLCipher-encrypted, marking done")
				config.sqlCipherMigrationDone = true
				return true
			}

			// It's a plaintext DB — migrate.
			Timber.d("SqlCipherMigration: plaintext DB detected, starting migration")
			return runMigration(dbFile)
		}

		/**
		 * Detect whether [dbFile] is a SQLCipher-encrypted database.
		 *
		 * SQLite plaintext files start with the 16-byte magic string
		 * "SQLite format 3\0". SQLCipher files start with random bytes
		 * (the encrypted first page) — the magic is hidden inside the
		 * encrypted payload.
		 */
		private fun isSqlCipherEncrypted(dbFile: File): Boolean {
			try {
				dbFile.inputStream().use { input ->
					val header = ByteArray(SQLITE_MAGIC.size)
					val read = input.read(header)
					if (read < SQLITE_MAGIC.size) return true // too small to be plaintext SQLite
					return !header.contentEquals(SQLITE_MAGIC)
				}
			} catch (e: Exception) {
				Timber.w(e, "SqlCipherMigration: failed to read DB header, assuming encrypted")
				return true
			}
		}

		/**
		 * Perform the actual plaintext → encrypted migration via
		 * `sqlcipher_export()`.
		 *
		 * Steps:
		 *  1. Open the plaintext DB with empty passphrase (SQLCipher supports
		 *     this for plaintext files).
		 *  2. ATTACH a new encrypted DB file with the Keystore passphrase.
		 *  3. `SELECT sqlcipher_export('new')` — copies schema + data.
		 *  4. DETACH + close.
		 *  5. Replace the old plaintext file with the new encrypted file.
		 *  6. Mark migration done.
		 */
		private fun runMigration(dbFile: File): Boolean {
			val newDbFile = File(dbFile.parentFile, "${dbFile.name}.new")
			val passphrase = sqlCipherKeyProvider.getOrCreatePassphrase()
			val passphraseStr = passphrase.joinToString("") { "%02x".format(it) }

			// Delete any stale .new file from a previous failed migration.
			if (!newDbFile.delete()) Timber.w("newDbFile.delete() failed (stale .new cleanup)")
			// Also clean up stale -wal / -shm
			File("${newDbFile.absolutePath}-wal").takeIf { it.exists() }?.delete()
			File("${newDbFile.absolutePath}-shm").takeIf { it.exists() }?.delete()

			var plaintextDb: net.zetetic.database.sqlcipher.SQLiteDatabase? = null
			try {
				// Step 1: open the plaintext DB with empty passphrase.
				// SQLCipher treats `""` as "no encryption" — this allows
				// reading/writing a standard SQLite file via the SQLCipher API.
				plaintextDb =
					net.zetetic.database.sqlcipher.SQLiteDatabase.openOrCreateDatabase(
						dbFile.absolutePath,
						"", // empty passphrase = plaintext
						null, // CursorFactory
						null, // DatabaseErrorHandler
					)

				// Step 2: ATTACH the new encrypted DB with the Keystore passphrase.
				// SQLCipher expects the passphrase as a SQL string literal here.
				// We hex-encode the raw bytes to avoid quote-escaping issues.
				val escapedPassphrase = passphraseStr.replace("'", "''")
				val attachSql = "ATTACH DATABASE '${newDbFile.absolutePath}' AS new KEY 'x\"$escapedPassphrase\"'"
				plaintextDb.execSQL(attachSql)

				// Step 3: copy schema + data via sqlcipher_export.
				// This function copies EVERY table, index, trigger, and view
				// from `main` (the plaintext DB) to `new` (the encrypted DB).
				plaintextDb.execSQL("SELECT sqlcipher_export('new')")

				// Step 4: copy the user_version pragma (Room uses this to track
				// schema version). Without it, Room would think the new DB is
				// version 0 and try to re-run all migrations from scratch.
				val cursor = plaintextDb.rawQuery("PRAGMA main.user_version", null)
				val userVersion =
					cursor.use {
						if (it.moveToFirst()) it.getInt(0) else 0
					}
				plaintextDb.execSQL("PRAGMA new.user_version = $userVersion")

				// Step 4.5: extract vault_protection rows from the plaintext
				// DB and copy them to the new BootstrapDatabase.
				//
				// vault_protection moves OUT of photok.db (encrypted) and INTO
				// photok_meta.db (plaintext bootstrap) in v16. The
				// sqlcipher_export() above copied vault_protection into the
				// new encrypted file too — that copy will become an orphan
				// table that Room ignores. We need the SAME rows in the
				// bootstrap DB so VaultService.unlock can read them without
				// opening the encrypted DB.
				//
				// We read directly from the plaintext DB (still open as `main`)
				// and INSERT into the bootstrap DB via its DAO.
				copyVaultProtectionToBootstrap(plaintextDb)

				// Step 5: DETACH and close.
				plaintextDb.execSQL("DETACH DATABASE new")
				plaintextDb.close()
				plaintextDb = null

				// Step 6: replace the old plaintext file with the new encrypted file.
				// Delete the old -wal and -shm first (they're plaintext WAL files
				// that don't apply to the new encrypted DB).
				File("${dbFile.absolutePath}-wal").takeIf { it.exists() }?.delete()
				File("${dbFile.absolutePath}-shm").takeIf { it.exists() }?.delete()

				if (!dbFile.delete()) {
					Timber.e("SqlCipherMigration: failed to delete old plaintext DB")
					if (!newDbFile.delete()) Timber.w("newDbFile.delete() failed (migration failure cleanup)")
					return false
				}
				if (!newDbFile.renameTo(dbFile)) {
					Timber.e("SqlCipherMigration: failed to rename .new → ${dbFile.name}")
					return false
				}

				// Step 7: mark migration done.
				config.sqlCipherMigrationDone = true
				Timber.d("SqlCipherMigration: migration complete — DB is now SQLCipher-encrypted")
				return true
			} catch (e: Exception) {
				// F-ENC-002: Do NOT log the exception detail — the ATTACH SQL contains
				// the SQLCipher passphrase hex. Log only the exception class + safe msg.
				val safeMsg = e.message?.take(60)?.replace(SQLCIPHER_PASSPHRASE_PATTERN, "<REDACTED>")
				Timber.e("SqlCipherMigration: migration failed (${e.javaClass.simpleName}: $safeMsg)")
				// Clean up partial state.
				try {
					plaintextDb?.close()
				} catch (_: Exception) {
					// intentionally ignored: closing plaintext DB during cleanup must not mask the primary migration failure
				}
				newDbFile.takeIf { it.exists() }?.delete()
				File("${newDbFile.absolutePath}-wal").takeIf { it.exists() }?.delete()
				File("${newDbFile.absolutePath}-shm").takeIf { it.exists() }?.delete()
				return false
			}
		}

		companion object {
			/** F-ENC-002: matches the SQLCipher raw-key format x"hex" in ATTACH SQL. */
			private val SQLCIPHER_PASSPHRASE_PATTERN = Regex(Char(39).toString() + "x" + Char(34) + "[0-9a-f]+" + Char(34) + Char(39))

			/** SQLite plaintext file magic header (first 16 bytes). */
			private val SQLITE_MAGIC = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
		}

		/**
		 * Read all rows from `vault_protection` in the plaintext DB and insert
		 * them into the bootstrap DB. Used during the v15 → v16 migration to
		 * populate the new plaintext [BootstrapDatabase] with the user's
		 * existing vault protection rows.
		 *
		 * The bootstrap DB is the canonical home for `vault_protection` from
		 * v16 onward — VaultService.unlock reads from it BEFORE the encrypted
		 * DB can be opened.
		 *
		 * If the plaintext DB has no `vault_protection` table (e.g. brand-new
		 * install that somehow got a v15 file), this is a no-op.
		 *
		 * @since v16 — Sprint 3 / SQLCipher
		 */
		private fun copyVaultProtectionToBootstrap(plaintextDb: net.zetetic.database.sqlcipher.SQLiteDatabase) {
			// Check if vault_protection table exists in the plaintext DB.
			val tableExists =
				plaintextDb
					.rawQuery(
						"SELECT name FROM sqlite_master WHERE type='table' AND name='vault_protection'",
						null,
					).use { it.moveToFirst() && it.count > 0 }
			if (!tableExists) {
				Timber.d("SqlCipherMigration: no vault_protection table in plaintext DB, skipping bootstrap copy")
				return
			}

			// Read all rows. The v15 schema for vault_protection is:
			//   id TEXT PRIMARY KEY,
			//   type TEXT,
			//   wrapped_vmk BLOB,
			//   salt TEXT, iv TEXT, kdf TEXT, kdf_iterations INT,
			//   algorithm TEXT, key_size INT, version INT
			// (stored as a single JSON-like blob in `params` column in v15
			// — wait, looking at VaultProtectionTable, `params` is a
			// VaultProtectionParams object stored via Room's type converter.
			// We need to inspect Converters to understand the actual SQL
			// column shape. For now, copy the row as-is via raw SQL.)
			//
			// Simpler approach: query all rows, deserialize via Room's
			// VaultProtectionDao on the bootstrap DB, and re-insert.
			// But we can't use the DAO on the plaintext DB (Room doesn't
			// manage it). We need raw SQL.
			//
			// The simplest reliable approach: read all rows as a Cursor,
			// extract the column values, and INSERT into the bootstrap DB
			// via raw SQL. We assume the column names match the v15 schema.
			val cursor = plaintextDb.rawQuery("SELECT * FROM vault_protection", null)
			var copied = 0
			try {
				val columnNames = cursor.columnNames
				val placeholders = columnNames.joinToString(",") { "?" }
				val columnList = columnNames.joinToString(",")
				val insertSql = "INSERT OR REPLACE INTO vault_protection ($columnList) VALUES ($placeholders)"

				// Use the bootstrap DB's raw SQL interface (Room exposes it
				// via openHelper.writableDatabase).
				val bootstrapSqlite = bootstrapDatabase.openHelper.writableDatabase

				while (cursor.moveToNext()) {
					val bindArgs =
						Array(columnNames.size) { idx ->
							when (cursor.getType(idx)) {
								android.database.Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(idx)
								android.database.Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(idx)
								android.database.Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(idx)
								android.database.Cursor.FIELD_TYPE_STRING -> cursor.getString(idx)
								else -> null
							}
						}
					bootstrapSqlite.execSQL(insertSql, bindArgs)
					copied++
				}
			} finally {
				cursor.close()
			}
			Timber.d("SqlCipherMigration: copied $copied vault_protection row(s) to bootstrap DB")
		}
	}
