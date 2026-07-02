# Task: key-escrow-impl — Implement key-escrow upload/download + login-branch phrase entry UI

## Scope

Implement key-escrow for the photok-sync-fork Android app:
1. Upload the wrapped VMK (recovery-phrase `VaultProtection`) to the remote during repo registration.
2. Download it during login and persist into the local DB.
3. Wire up the existing phrase-entry UI (`RecoveryPhraseRestoreScreen`) for the login branch.

**No commit / push.** Edits only.

## Files touched

1. `app/src/main/java/dev/leonlatsch/photok/sync/rclone/RepoManager.kt` — Steps 1 + 2
2. `app/src/main/java/dev/leonlatsch/photok/sync/di/SyncModule.kt` — DI update
3. `app/src/main/java/dev/leonlatsch/photok/reposetup/ui/RepoSetupViewModel.kt` — Step 2 result handling + Step 3 state
4. `app/src/main/java/dev/leonlatsch/photok/reposetup/ui/RepoSetupFragment.kt` — Step 3 UI
5. `app/src/main/res/values/strings.xml` — new strings

## Changes per file

### 1. `RepoManager.kt`

- **Imports added**: `VaultProtectionRepository`, `VaultProtection`, `VaultProtectionParams`,
  `VaultProtectionType`, `Algorithm`, `Kdf`, `java.util.Base64`.
- **Constructor**: added 6th param `vaultProtectionRepository: VaultProtectionRepository`.
- **New nested sealed class `LoginResult`** (next to `RepoState`):
  - `Success(escrowAvailable: Boolean)`
  - `Failure(message: String)`
- **`registerRepo()`**: inserted escrow-upload step between marker verification and
  `config.repoConfirmed = true`. Wraps `uploadVaultProtectionEscrow()` in try/catch —
  logs failure but does NOT block registration.
- **`loginRepo()`**: return type changed from `Result<Unit>` to `LoginResult`. After
  setting `config.repoConfirmed = true`, calls `downloadVaultProtectionEscrow()` and
  returns `Success(escrowAvailable = true|false)` (false if missing-or-failed,
  non-fatal). `Failure(message)` if the connect itself throws.
- **New private suspend `uploadVaultProtectionEscrow(): Result<Unit>`**:
  - Reads `VaultProtectionType.RecoveryPhrase` from local DB; skips (success) if null.
  - Hand-builds JSON matching the model shape (consistent with existing marker-file style);
    uses `java.util.Base64` (NOT `android.util.Base64`).
  - Writes JSON to a temp file in `app.cacheDir`.
  - Uploads via `rcloneController.uploadFile(localPath, "$remote:$VAULT_PROTECTION_REMOTE_PATH")`.
  - Independent verification via `listRemote("$remote:", "$REPO_DIR/$VAULT_PROTECTION_DIR")` —
    confirms file is present with `size > 0` (same pattern as marker verify).
  - Deletes temp file in `finally`.
  - Logs every step via `diag()`.
- **New private suspend `downloadVaultProtectionEscrow(): Result<VaultProtection?>`**:
  - Downloads `$remote:$VAULT_PROTECTION_REMOTE_PATH` to a temp file.
  - On "not found"-style errors → `Result.success(null)` (graceful missing-artifact case
    for old repos).
  - On other download errors → `Result.failure(e)`.
  - On success: parses JSON via `parseVaultProtection()`, then persists via
    `createProtection` OR `updateProtection` if a RecoveryPhrase row already exists
    (no duplicates).
  - Temp file deleted in `finally`.
- **New private `parseVaultProtection(json: String): VaultProtection?`**: regex-based
  minimal parser, mirrors `parseMarker` style. Base64-decodes `wrappedVMK` back to
  `ByteArray`.
- **Companion object**: added constants
  - `VAULT_PROTECTION_DIR = "vault-protection"`
  - `VAULT_PROTECTION_FILENAME = "recovery-phrase.json"`
  - `VAULT_PROTECTION_REMOTE_PATH = "$REPO_DIR/$VAULT_PROTECTION_DIR/$VAULT_PROTECTION_FILENAME"`

### 2. `SyncModule.kt`

- Import added: `VaultProtectionRepository`.
- `provideRepoManager()`: added `vaultProtectionRepository: VaultProtectionRepository` param
  and passes it as the 6th constructor arg to `RepoManager`.

### 3. `RepoSetupViewModel.kt`

- `RepoSetupState` sealed interface: added two new states
  - `NeedsPhraseEntry` — escrow downloaded successfully, user must enter phrase.
  - `NoEscrowAvailable` — old repo / download failed non-fatally; degraded mode.
- `checkRemoteAndDetectRepo()`: rewrote the `LOGGED_IN` branch. Now pattern-matches on
  `LoginResult`:
  - `Success(escrowAvailable=true)` → `RestoringBackup` → restore thumbnails → `NeedsPhraseEntry`.
  - `Success(escrowAvailable=false)` → `RestoringBackup` → restore thumbnails → `NoEscrowAvailable`.
  - `Failure(message)` → `Error(message)`.
- New `continueWithoutEscrow()` method — transitions `NoEscrowAvailable` → `Completed`
  (which chains forward to SetupFragment for the normal PIN/password path).

### 4. `RepoSetupFragment.kt`

- Import added: `dev.leonlatsch.photok.encryption.ui.RecoveryPhraseRestoreScreen`.
- `RepoSetupFragment.onCreateView`: now passes `onUnlocked` (= `navigateToGallery(findNavController())`)
  and `onBack` (= `findNavController().navigateUp()`) lambdas into `RepoSetupScreen`.
- `RepoSetupScreen` signature: added `onUnlocked: () -> Unit` and `onBack: () -> Unit` params.
- Layout: `NeedsPhraseEntry` is rendered full-screen (early-out before the padded Column)
  because `RecoveryPhraseRestoreScreen` has its own `Scaffold`. Other states remain inside
  the centered/padded Column.
- Inner `when` over `RepoSetupState`: added `NoEscrowAvailable` and `NeedsPhraseEntry`
  branches (the latter is unreachable due to the early-out, but kept for exhaustiveness).
- New composables:
  - `NeedsPhraseEntryContent(onUnlocked, onBack)` — embeds `RecoveryPhraseRestoreScreen`
    AS-IS (reused, not rebuilt). KDoc explains the unlock → gallery flow and why no
    additional decrypt-verify step is needed (a wrong phrase would have thrown inside
    `vaultService.unlock()`).
  - `NoEscrowAvailableContent(viewModel)` — title + body message + "Continue to gallery"
    button that calls `viewModel.continueWithoutEscrow()`.

### 5. `strings.xml`

Three new strings (no localization of body — matches the existing pattern where the
English source strings live here and translations live in `values-*` siblings):

- `repo_setup_no_escrow_title` — "Recovery phrase restore unavailable"
- `repo_setup_no_escrow_body` — full degraded-mode explanation.
- `repo_setup_no_escrow_continue` — "Continue to gallery"

## Verification

- Could NOT run `./gradlew compileDebugKotlin` — no Android SDK installed in the sandbox
  (ANDROID_HOME unset, no `platform-tools`/`platforms` directories found).
- Visually verified all edits: imports, signatures, sealed exhaustiveness,
  `when`-branch coverage, and the `LoginResult`/`RepoSetupState` transitions are
  internally consistent. Only one external call site for `loginRepo()` exists (in
  `RepoSetupViewModel.checkRemoteAndDetectRepo()`), and it has been updated to handle
  `LoginResult`.

## Non-Goals (per task spec)

- Did NOT modify `deleteLocalAfterUpload` or any other existing sync logic.
- Did NOT touch the standalone `RecoveryPhraseRestoreFragment` — only the embedded
  reuse via `RecoveryPhraseRestoreScreen`.
- Did NOT change `RepoManager.detectRepo()` semantics.
- Did NOT push or commit.
