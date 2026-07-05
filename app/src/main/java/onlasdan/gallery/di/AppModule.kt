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

package onlasdan.gallery.di

import android.content.Context
import android.content.res.Resources
import androidx.room.Room
import androidx.room.RoomDatabase
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import onlasdan.gallery.BuildConfig
import onlasdan.gallery.encryption.data.BootstrapDatabase
import onlasdan.gallery.encryption.data.SqlCipherKeyProvider
import onlasdan.gallery.encryption.data.SqlCipherMigrationHelper
import onlasdan.gallery.encryption.data.VaultProtectionDao
import onlasdan.gallery.gallery.ui.importing.SharedUrisStore
import onlasdan.gallery.model.database.DATABASE_NAME
import onlasdan.gallery.model.database.MIGRATION_15_16
import onlasdan.gallery.model.database.PhotoZDatabase
import onlasdan.gallery.settings.data.Config
import timber.log.Timber
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import javax.inject.Singleton

/**
 * Hilt Module for [SingletonComponent].
 *
 * @since 1.0.0
 * @author PhotoZ
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /**
     * Plaintext bootstrap database — Singleton.
     *
     * Holds only [onlasdan.gallery.encryption.data.VaultProtectionTable]
     * (wrapped VMKs + KDF params). Read by VaultService BEFORE the
     * encrypted [PhotoZDatabase] can be opened — see
     * [onlasdan.gallery.encryption.data.BootstrapDatabase] for the
     * chicken-and-egg explanation.
     *
     * @since v16 — Sprint 3 / TODO #6 SQLCipher
     */
    @Provides
    @Singleton
    fun provideBootstrapDatabase(@ApplicationContext app: Context): BootstrapDatabase =
        Room.databaseBuilder(
            app,
            BootstrapDatabase::class.java,
            BootstrapDatabase.DATABASE_NAME,
        )
            // Plaintext — must be readable pre-unlock. No SQLCipher.
            .build()

    /**
     * VaultProtectionDao — sourced from the plaintext [BootstrapDatabase].
     *
     * @since v16 — Sprint 3 / TODO #6 SQLCipher (was previously sourced
     *   from PhotoZDatabase)
     */
    @Provides
    @Singleton
    fun provideVaultProtectionDao(db: BootstrapDatabase): VaultProtectionDao =
        db.getVaultProtectionDao()

    /**
     * SQLCipher-encrypted main Room database — Singleton.
     *
     * ## Sprint 3 / TODO #6 SQLCipher
     *
     * The DB is encrypted with a 32-byte passphrase backed by the Android
     * Keystore (see [SqlCipherKeyProvider]). The key:
     *  - Is generated on first run and persisted in hardware-isolated
     *    Keystore (StrongBox if available, TEE fallback).
     *  - Never leaves Keystore in plaintext form (only the encoded bytes
     *    are read out, briefly, to pass to SQLCipher).
     *  - Persists across app restarts and vault lock/unlock cycles.
     *
     * The DB stays open across vault switches — multi-vault data
     * separation is enforced by the `vault_id` column on every
     * photo/album/hash_registry row (Sprint 2 / M7), not by the DB
     * encryption. This is a deliberate trade-off: we lose "vault lock =
     * DB lock" semantics (the original design called for VMK-derived DB
     * keys) but gain the ability to run background DB queries (e.g.
     * CleanupDeadFilesUseCase at app start, PhotoSyncWorker in
     * background) without requiring vault unlock.
     *
     * ## Migration
     *
     * The one-time plaintext → encrypted migration is handled by
     * [SqlCipherMigrationHelper.migrateIfNecessary], called BEFORE
     * `Room.databaseBuilder(...).build()` so that the plaintext file is
     * renamed out of the way before SQLCipher tries to open it.
     *
     * @since v16 — Sprint 3 / TODO #6 SQLCipher
     */
    @Provides
    @Singleton
    fun providePhotoZDatabase(
        @ApplicationContext app: Context,
        sqlCipherKeyProvider: SqlCipherKeyProvider,
        migrationHelper: SqlCipherMigrationHelper,
    ): PhotoZDatabase {
        // Step 1: run the one-time plaintext → encrypted migration if needed.
        // This MUST happen before Room.databaseBuilder().build() so that
        // SQLCipher doesn't try to open a plaintext file with a key (which
        // would fail with "file is not a database" error).
        //
        // The migration uses SQLCipher's sqlcipher_export() to copy the
        // entire plaintext DB into a new encrypted DB file, then swaps
        // the files. After this, photok.db is SQLCipher-encrypted.
        if (!migrationHelper.migrateIfNecessary()) {
            Timber.e("SQLCipher migration failed — DB will likely fail to open. " +
                "User may need to clear app data and re-import photos.")
        }

        // Step 2: fetch the SQLCipher passphrase from Keystore.
        val passphrase = sqlCipherKeyProvider.getOrCreatePassphrase()

        // Step 3: build the SupportOpenHelperFactory with the passphrase.
        // SupportOpenHelperFactory passes the passphrase to SQLCipher,
        // which internally PBKDF2-derives the page-encryption key. The
        // passphrase bytes themselves never touch disk.
        val factory = net.sqlcipher.database.SupportFactory(
            passphrase, null, false,
        )

        // Step 4: build the Room database with the SQLCipher factory +
        // manual v15→v16 migration stub (no-op — data copy happens in
        // the migration helper above, this just advances user_version).
        return Room.databaseBuilder(
            app,
            PhotoZDatabase::class.java,
            DATABASE_NAME,
        )
            .openHelperFactory(factory)
            .addMigrations(MIGRATION_15_16)
            .apply {
                if (BuildConfig.DEBUG) {
                    setQueryCallback(object : RoomDatabase.QueryCallback {
                        override fun onQuery(
                            sqlQuery: String,
                            bindArgs: List<Any?>,
                        ) {
                            Timber.d("SQL: $sqlQuery | args: $bindArgs")
                        }
                    }, Executors.newSingleThreadExecutor())
                }
            }
            .build()
    }

    @Provides
    @Singleton
    fun providePhotoDao(database: PhotoZDatabase) = database.getPhotoDao()

    @Provides
    @Singleton
    fun provideAlbumDao(database: PhotoZDatabase) = database.getAlbumDao()

    @Provides
    @Singleton
    fun provideConfig(@ApplicationContext app: Context) = Config(app)

    @Provides
    @Singleton
    fun provideSharedUrisStore() = SharedUrisStore()

    @Provides
    fun provideResources(@ApplicationContext context: Context): Resources = context.resources

    @Provides
    fun provideGson(): Gson = GsonBuilder()
        .setPrettyPrinting()
        .create()

    @Provides
    fun provideAppScope() = CoroutineScope(Dispatchers.Default)
}
