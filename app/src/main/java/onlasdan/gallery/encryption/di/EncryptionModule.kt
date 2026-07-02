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

package onlasdan.gallery.encryption.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import onlasdan.gallery.encryption.data.RecoveryPhraseStoreImpl
import onlasdan.gallery.encryption.data.SessionRepositoryImpl
import onlasdan.gallery.encryption.data.VaultProtectionRepositoryImpl
import onlasdan.gallery.encryption.domain.RecoveryPhraseStore
import onlasdan.gallery.encryption.domain.SessionRepository
import onlasdan.gallery.encryption.domain.VaultProtectionRepository
import onlasdan.gallery.encryption.domain.crypto.CbcCryptoEngine
import onlasdan.gallery.encryption.domain.crypto.CryptoEngine
import onlasdan.gallery.encryption.domain.handlers.BiometricVaultProtectionHandler
import onlasdan.gallery.encryption.domain.handlers.PasswordVaultProtectionHandler
import onlasdan.gallery.encryption.domain.handlers.RecoveryPhraseVaultProtectionHandler
import onlasdan.gallery.encryption.domain.handlers.VaultProtectionHandler
import onlasdan.gallery.encryption.domain.models.CreateRequest
import onlasdan.gallery.encryption.domain.models.UnlockRequest
import onlasdan.gallery.model.database.PhotoZDatabase

@Module
@InstallIn(SingletonComponent::class)
interface EncryptionBindingModule {

    @Binds
    fun bindVaultProtectionRepository(impl: VaultProtectionRepositoryImpl): VaultProtectionRepository

    @Binds
    fun bindPasswordUnlocker(impl: PasswordVaultProtectionHandler): VaultProtectionHandler<UnlockRequest.Password, CreateRequest.Password>

    @Binds
    fun bindBiometricUnlocker(impl: BiometricVaultProtectionHandler): VaultProtectionHandler<UnlockRequest.Biometric, CreateRequest.Biometric>

    @Binds
    fun bindRecoveryPhraseHandler(impl: RecoveryPhraseVaultProtectionHandler): VaultProtectionHandler<UnlockRequest.RecoveryPhrase, CreateRequest.RecoveryPhrase>

    @Binds
    fun bindSessionRepository(impl: SessionRepositoryImpl): SessionRepository

    @Binds
    fun bindCryptoEngine(impl: CbcCryptoEngine): CryptoEngine

    @Binds
    fun bindRecoveryPhraseStore(impl: RecoveryPhraseStoreImpl): RecoveryPhraseStore
}

@Module
@InstallIn(SingletonComponent::class)
class EncryptionModule {

    @Provides
    fun provideVaultProtectionDao(database: PhotoZDatabase) = database.getVaultProtectionDao()
}