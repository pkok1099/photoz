/*
 *   Copyright 2020-2026 Leon Latsch
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

package onlasdan.gallery.encryption.domain

import onlasdan.gallery.encryption.domain.handlers.VaultProtectionHandler
import onlasdan.gallery.encryption.domain.models.Algorithm
import onlasdan.gallery.encryption.domain.models.CreateRequest
import onlasdan.gallery.encryption.domain.models.Kdf
import onlasdan.gallery.encryption.domain.models.UnlockRequest
import onlasdan.gallery.encryption.domain.models.VaultProtection
import onlasdan.gallery.encryption.domain.models.VaultProtectionParams
import onlasdan.gallery.encryption.domain.models.VaultProtectionType
import onlasdan.gallery.encryption.domain.crypto.KeyGen
import onlasdan.gallery.encryption.domain.crypto.IV_SIZE
import onlasdan.gallery.encryption.domain.crypto.SALT_SIZE
import onlasdan.gallery.encryption.domain.models.VaultSession
import onlasdan.gallery.settings.data.Config
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.inject.Inject
import kotlin.io.encoding.Base64

private const val KEK_SIZE = 256
private const val KEK_ITERATIONS = 100_000

class VaultService @Inject constructor(
    private val vaultProtectionRepository: VaultProtectionRepository,
    private val passwordProtectionHandler: VaultProtectionHandler<UnlockRequest.Password, CreateRequest.Password>,
    private val biometricProtectionHandler: VaultProtectionHandler<UnlockRequest.Biometric, CreateRequest.Biometric>,
    private val recoveryPhraseProtectionHandler: VaultProtectionHandler<UnlockRequest.RecoveryPhrase, CreateRequest.RecoveryPhrase>,
    private val keyGen: KeyGen,
    private val config: Config,
) {
    suspend fun unlock(request: UnlockRequest): Result<VaultSession> {
        val type = request.protectionType
        var protection = vaultProtectionRepository.getProtection(type)

        return runCatching {
            val vmk = when (request) {
                is UnlockRequest.Password -> {
                    if (protection == null) {
                        protection = passwordProtectionHandler.migrate(request)
                        vaultProtectionRepository.createProtection(protection)
                        passwordProtectionHandler.onMigrationPersisted()
                    }

                    passwordProtectionHandler.unlock(request, protection)
                }
                is UnlockRequest.Biometric -> {
                    if (protection == null) {
                        protection = biometricProtectionHandler.migrate(request)
                        vaultProtectionRepository.createProtection(protection)
                        biometricProtectionHandler.onMigrationPersisted()
                    }

                    biometricProtectionHandler.unlock(request, protection)
                }
                is UnlockRequest.RecoveryPhrase -> {
                    requireNotNull(protection)
                    recoveryPhraseProtectionHandler.unlock(request, protection)
                }
            }

            config.lastUsedUnlockMethod = request.protectionType

            VaultSession(
                vmk = vmk,
            )
        }
    }

    suspend fun create(request: CreateRequest) {
        val protection = when (request) {
            is CreateRequest.Password -> passwordProtectionHandler.create(request)
            is CreateRequest.Biometric -> biometricProtectionHandler.create(request)
            is CreateRequest.RecoveryPhrase -> recoveryPhraseProtectionHandler.create(request)
        }

        vaultProtectionRepository.createProtection(protection)
    }

    /**
     * Create a local [VaultProtection] of type [VaultProtectionType.Password] that wraps
     * an EXISTING VMK (from [session]) with the given [password]. Used after a login-branch
     * unlock to persist a local Password protection so [canUnlock] returns true on subsequent
     * app opens.
     *
     * This is distinct from [create]([CreateRequest.Password]) which generates a NEW VMK —
     * that would be wrong here because the VMK must match the one that encrypted the
     * already-uploaded photos.
     *
     * @since data-loss fix — login branch must persist local Password protection
     */
    suspend fun createPasswordProtectionFromSession(password: String, session: VaultSession) {
        val salt = ByteArray(SALT_SIZE).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(IV_SIZE).also { SecureRandom().nextBytes(it) }
        val kdf = Kdf.PBKDF2WithHmacSHA256
        val algorithm = Algorithm.AesCbcPkcs7Padding

        val params = VaultProtectionParams(
            salt = Base64.encode(salt),
            iv = Base64.encode(iv),
            kdf = kdf,
            kdfIterations = KEK_ITERATIONS,
            algorithm = algorithm,
            keySize = KEK_SIZE,
        )

        val kek = keyGen.derivePasswordKeyEncryptionKey(
            password = password,
            salt = salt,
            kdf = kdf,
            kdfIterations = KEK_ITERATIONS,
            keySize = KEK_SIZE,
        )

        val cipher = Cipher.getInstance(algorithm.value).apply {
            init(Cipher.ENCRYPT_MODE, kek, IvParameterSpec(iv))
        }

        val wrappedVmk = cipher.doFinal(session.vmk.encoded)

        val protection = VaultProtection(
            id = UUID.randomUUID().toString(),
            type = VaultProtectionType.Password,
            wrappedVMK = wrappedVmk,
            params = params,
        )
        vaultProtectionRepository.createProtection(protection)
        android.util.Log.e("RcloneDiag",
            "VaultService.createPasswordProtectionFromSession: created local VaultProtection(Password) " +
                "wrapping existing VMK (id=${protection.id}) — canUnlock will now return true on next open")
    }

    suspend fun reset(type: VaultProtectionType) {
        vaultProtectionRepository.removeProtection(type)

        when (type) {
            VaultProtectionType.Password -> passwordProtectionHandler.reset()
            VaultProtectionType.Biometric -> {
                config.biometricAuthenticationEnabled = false
                biometricProtectionHandler.reset()
            }
            VaultProtectionType.RecoveryPhrase -> recoveryPhraseProtectionHandler.reset()
        }
    }

    suspend fun isSetup(type: VaultProtectionType): Boolean {
        return vaultProtectionRepository.getProtection(type) != null
    }

    suspend fun canMigrate(type: VaultProtectionType): Boolean = when (type) {
        VaultProtectionType.Password -> passwordProtectionHandler.canMigrate()
        VaultProtectionType.Biometric -> biometricProtectionHandler.canMigrate()
        VaultProtectionType.RecoveryPhrase -> recoveryPhraseProtectionHandler.canMigrate()
    }

    suspend fun canUnlock(): Boolean {
        val passwordSetup = isSetup(VaultProtectionType.Password)
        val biometricSetup = isSetup(VaultProtectionType.Biometric)
        val recoveryPhraseSetup = isSetup(VaultProtectionType.RecoveryPhrase)
        val protectionsAreSetup = passwordSetup || biometricSetup || recoveryPhraseSetup
        val canMigrate = passwordProtectionHandler.canMigrate() || biometricProtectionHandler.canMigrate()

        android.util.Log.e("RcloneDiag",
            "VaultService.canUnlock: passwordSetup=$passwordSetup biometricSetup=$biometricSetup " +
                "recoveryPhraseSetup=$recoveryPhraseSetup " +
                "protectionsAreSetup=$protectionsAreSetup canMigrate=$canMigrate → " +
                "result=${protectionsAreSetup || canMigrate}")

        return protectionsAreSetup || canMigrate
    }
}