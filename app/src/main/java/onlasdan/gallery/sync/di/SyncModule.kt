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

package onlasdan.gallery.sync.di

import android.app.Application
import androidx.work.Configuration
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import onlasdan.gallery.encryption.domain.RecoveryPhraseStore
import onlasdan.gallery.encryption.domain.VaultProtectionRepository
import onlasdan.gallery.encryption.domain.crypto.PhraseEscrowWrapper
import onlasdan.gallery.model.database.dao.PhotoDao
import onlasdan.gallery.settings.data.Config
import onlasdan.gallery.sync.rclone.RepoManager
import onlasdan.gallery.sync.rclone.RcloneConfigManager
import onlasdan.gallery.sync.rclone.RcloneController
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
        // @since Part A fix + Part B two-layer escrow — RepoManager uses
        // PhraseEscrowWrapper to wrap/unwrap the recovery phrase with a
        // password-derived KEK (Layer 2 of the two-layer escrow). The wrapper
        // has @Inject constructor + @Singleton, so Hilt provides it directly.
        phraseEscrowWrapper: PhraseEscrowWrapper,
        // @since Part A fix + Part B two-layer escrow — RepoManager reads the
        // locally-stored recovery phrase (encrypted-at-rest with the VMK) so it
        // can be re-wrapped with the password and uploaded as wrapped-phrase.json.
        // Already bound by Hilt (RecoveryPhraseStoreImpl has @Inject constructor,
        // bound to RecoveryPhraseStore interface by EncryptionBindingModule).
        recoveryPhraseStore: RecoveryPhraseStore,
    ) = RepoManager(
        app,
        config,
        rcloneController,
        configManager,
        photoDao,
        vaultProtectionRepository,
        phraseEscrowWrapper,
        recoveryPhraseStore,
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
