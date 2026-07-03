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
import onlasdan.gallery.gallery.ui.importing.SharedUrisStore
import onlasdan.gallery.model.database.DATABASE_NAME
import onlasdan.gallery.model.database.PhotoZDatabase
import onlasdan.gallery.settings.data.Config
import timber.log.Timber
import java.util.concurrent.Executor
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

    @Provides
    @Singleton
    fun providePhotoZDatabase(@ApplicationContext app: Context) = Room.databaseBuilder(
        app,
        PhotoZDatabase::class.java,
        DATABASE_NAME
    ).apply {
        if (BuildConfig.DEBUG) {
            setQueryCallback(object : RoomDatabase.QueryCallback {
                override fun onQuery(
                    sqlQuery: String,
                    bindArgs: List<Any?>
                ) {
                    Timber.d("SQL: $sqlQuery | args: $bindArgs")
                }
            }, Executors.newSingleThreadExecutor())
        } else {
            this
        }

        // ─── Sprint 2 / M7 — v10 → v11 migration ──────────────────────────
        // No manual migration needed: Room's AutoMigration handles BOTH:
        //   1. The additive column additions (vault_id on photo / album /
        //      hash_registry) — auto-generated ALTER TABLE ADD COLUMN.
        //   2. The DROP INDEX for the removed unique index on
        //      vault_protection.type — Room auto-generates DROP INDEX when
        //      an @Index is removed from an entity.
        // The vault_id columns are nullable with default NULL, so existing
        // rows survive the migration with NULL vault_id and are backfilled
        // lazily on the first unlock (see VaultIdBackfillUseCase).
    }.build()

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