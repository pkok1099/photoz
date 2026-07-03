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

package onlasdan.gallery.encryption.domain

import onlasdan.gallery.encryption.domain.handlers.VaultProtectionHandler
import onlasdan.gallery.encryption.domain.models.Algorithm
import onlasdan.gallery.encryption.domain.models.CreateRequest
import onlasdan.gallery.encryption.domain.models.Kdf
import onlasdan.gallery.encryption.domain.models.UnlockRequest
import onlasdan.gallery.encryption.domain.models.VaultProtection
import onlasdan.gallery.encryption.domain.models.VaultProtectionParams
import onlasdan.gallery.encryption.domain.models.VaultProtectionType
import onlasdan.gallery.encryption.domain.models.VaultSession
import onlasdan.gallery.encryption.domain.crypto.KeyGen
import onlasdan.gallery.encryption.domain.crypto.IV_SIZE
import onlasdan.gallery.encryption.domain.crypto.SALT_SIZE
import onlasdan.gallery.settings.data.Config
import timber.log.Timber
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
    /**
     * Sprint 2 / M7 — vault_id backfill hook.
     *
     * When non-null, [unlock] calls it after a successful unlock so the
     * backfill use-case can UPDATE all rows with `vault_id IS NULL` to
     * the freshly-computed vault_id. The hook is injected (rather than
     * VaultService depending on PhotoDao directly) to keep the encryption
     * module decoupled from the data module.
     *
     * Injected via [onlasdan.gallery.encryption.di.EncryptionModule.provideVaultIdBackfillHook].
     */
    private val vaultIdBackfillHook: VaultIdBackfillHook? = null,
) {
    suspend fun unlock(request: UnlockRequest): Result<VaultSession> {
        val type = request.protectionType

        return runCatching {
            val vmk: javax.crypto.SecretKey = when (request) {
                is UnlockRequest.Password -> {
                    // ─── Sprint 2 / M7: Multi-vault unlock ───────────────────────
                    // Iterate ALL VaultProtection(Password) rows. For each, derive
                    // KEK from the user's password + row's salt, try to unwrap the
                    // row's wrappedVMK. The GCM/CBC auth verification naturally
                    // fails for wrong rows — only the correct row's VMK emerges.
                    //
                    // First success = active vault. The app has no concept of
                    // "real" vs "decoy" — every successful unlock is treated
                    // identically.
                    //
                    // Performance: N rows × PBKDF2(100k iters) ≈ 100N ms.
                    // N=1 (most common) → 100ms. N=5 → 500ms. N=10 → 1s.
                    // Cellebrite-style brute force is unaffected — they'd have
                    // to try every password against every row.
                    val passwordProtections = vaultProtectionRepository.getAllProtections(
                        VaultProtectionType.Password
                    )

                    if (passwordProtections.isEmpty()) {
                        // ─── Legacy migration path (1.x.x / 2.x.x users) ───────
                        // No Password rows in the new format — try migrating from
                        // legacyPasswordHash. This is the original code path,
                        // preserved for users upgrading from a pre-M7 build.
                        if (passwordProtectionHandler.canMigrate()) {
                            val migrated = passwordProtectionHandler.migrate(request)
                            vaultProtectionRepository.createProtection(migrated)
                            passwordProtectionHandler.onMigrationPersisted()
                            passwordProtectionHandler.unlock(request, migrated)
                        } else {
                            // No password protection AND nothing to migrate —
                            // vault isn't set up. Throw to surface as wrong-password.
                            throw IllegalStateException("No password protection found and no legacy migration available")
                        }
                    } else {
                        unlockMultiVaultPassword(request, passwordProtections)
                    }
                }
                is UnlockRequest.Biometric -> {
                    val protection = vaultProtectionRepository.getProtection(type)
                    if (protection == null) {
                        val migrated = biometricProtectionHandler.migrate(request)
                        vaultProtectionRepository.createProtection(migrated)
                        biometricProtectionHandler.onMigrationPersisted()
                        biometricProtectionHandler.unlock(request, migrated)
                    } else {
                        biometricProtectionHandler.unlock(request, protection)
                    }
                }
                is UnlockRequest.RecoveryPhrase -> {
                    val protection = vaultProtectionRepository.getProtection(type)
                    requireNotNull(protection)
                    recoveryPhraseProtectionHandler.unlock(request, protection)
                }
            }

            config.lastUsedUnlockMethod = request.protectionType

            val session = VaultSession(vmk = vmk)

            // ─── Sprint 2 / M7: vault_id backfill (one-time migration) ─────────
            // On first unlock after v10→v11 upgrade, all Photo/Album/HashRegistryEntry
            // rows have vault_id = NULL. Backfill them with the current session's
            // vault_id. Subsequent unlocks find no NULL rows and skip this cheaply.
            //
            // Non-fatal: if backfill fails (DB busy, etc.), the gallery just shows
            // the user's photos as usual — the vault_id filter falls back to
            // "match current vault_id OR match NULL", which catches the un-backfilled
            // rows. The next successful unlock will retry the backfill.
            try {
                vaultIdBackfillHook?.backfillVaultId(session.vaultId)
            } catch (e: Exception) {
                Timber.w(e, "vault_id backfill failed (non-fatal) — will retry next unlock")
            }

            session
        }
    }

    /**
     * Try unwrapping each [passwordProtections] row with the user's password.
     * Returns the first VMK that successfully unwraps (auth tag verifies).
     * Throws if no row unwraps successfully (= wrong password).
     */
    private suspend fun unlockMultiVaultPassword(
        request: UnlockRequest.Password,
        passwordProtections: List<VaultProtection>,
    ): javax.crypto.SecretKey {
        for (protection in passwordProtections) {
            try {
                return passwordProtectionHandler.unlock(request, protection)
            } catch (e: Exception) {
                // Auth tag verification failed (wrong password for THIS row) —
                // try the next row. The exception type varies (AEADBadTagException
                // for GCM, BadPaddingException for CBC) so we catch broadly.
                Timber.d("unlockMultiVaultPassword: row ${protection.id} rejected password — trying next")
            }
        }
        throw IllegalStateException("Password did not unlock any vault")
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
     * Create a new vault protected by [password].
     *
     * Sprint 2 / M7 — Multi-vault entry point. Called from Settings →
     * "Create new vault". Generates a fresh VMK (distinct from the current
     * session's VMK), wraps it with the new password, persists a NEW
     * VaultProtection(Password) row. The new vault is empty and starts
     * local-only (no recovery phrase, no sync).
     *
     * Caller is responsible for:
     *  1. Verifying [password] doesn't already unlock an existing vault
     *     (call [unlock] first and expect it to fail).
     *  2. Resetting the current session and re-unlocking with [password]
     *     to activate the new vault.
     *
     * @return the [VaultSession] for the newly-created vault (caller may
     *   immediately activate it via [SessionRepository.set]).
     *
     * @since v11 — Sprint 2 / M7 multi-vault
     */
    suspend fun createNewVault(password: String): VaultSession {
        // create() generates a fresh VMK internally and wraps it with the password.
        // The new VaultProtection row is persisted via the repository.
        create(CreateRequest.Password(password))

        // Unlock the new vault to get its VMK + computed vault_id.
        // The unlock path will iterate all Password rows; the new row's wrappedVMK
        // is the only one that successfully unwraps with this password (assuming
        // the password is unique — caller MUST verify this before calling).
        val result = unlock(UnlockRequest.Password(password))
        return result.getOrThrow()
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

/**
 * Hook called by [VaultService.unlock] after a successful unlock to backfill
 * any rows with `vault_id IS NULL` (created before v11 migration).
 *
 * Implemented by [onlasdan.gallery.encryption.domain.VaultIdBackfillUseCase]
 * in the data module (which has access to PhotoDao / AlbumDao / HashRegistryDao).
 *
 * @since v11 — Sprint 2 / M7 multi-vault
 */
fun interface VaultIdBackfillHook {
    /**
     * UPDATE all rows with `vault_id IS NULL` to set `vault_id = [vaultId]`.
     * Called from the encryption module after a successful unlock.
     */
    suspend fun backfillVaultId(vaultId: String)
}
