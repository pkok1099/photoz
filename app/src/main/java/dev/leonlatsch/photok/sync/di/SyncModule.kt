/*
 *   Copyright 2020–2026 Leon Latsch
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

package dev.leonlatsch.photok.sync.di

import android.app.Application
import androidx.work.Configuration
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.leonlatsch.photok.encryption.domain.VaultProtectionRepository
import dev.leonlatsch.photok.model.database.dao.PhotoDao
import dev.leonlatsch.photok.settings.data.Config
import dev.leonlatsch.photok.sync.rclone.RepoManager
import dev.leonlatsch.photok.sync.rclone.RcloneConfigManager
import dev.leonlatsch.photok.sync.rclone.RcloneController
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SyncModule {

    @Provides
    @Singleton
    fun provideRcloneConfigManager(
        app: Application,
        config: Config,
    ) = RcloneConfigManager(app, config)

    @Provides
    @Singleton
    fun provideRcloneController(
        app: Application,
        configManager: RcloneConfigManager,
    ) = RcloneController(app, configManager)

    @Provides
    @Singleton
    fun provideRepoManager(
        app: Application,
        config: Config,
        rcloneController: RcloneController,
        configManager: RcloneConfigManager,
        // @since PR4 sync — RepoManager.restoreThumbnailsAfterLogin() inserts Photo
        // DB rows for each thumbnail pulled back from the remote after login.
        photoDao: PhotoDao,
        // @since key-escrow — RepoManager needs to read/write the recovery-phrase
        // VaultProtection row to escrow it to the remote during register and
        // restore it from the remote during login. Already bound by Hilt
        // (VaultProtectionRepositoryImpl has @Inject constructor).
        vaultProtectionRepository: VaultProtectionRepository,
    ) = RepoManager(
        app,
        config,
        rcloneController,
        configManager,
        photoDao,
        vaultProtectionRepository,
    )

    @Provides
    @Singleton
    fun provideWorkManagerConfiguration(
        workerFactory: androidx.hilt.work.HiltWorkerFactory,
    ): Configuration = Configuration.Builder()
        .setWorkerFactory(workerFactory)
        .setMinimumLoggingLevel(android.util.Log.INFO)
        .build()
}
