# Task: two-layer-escrow — Part A fix (register-always-triggered) + Part B two-layer escrow

## Scope

Two related changes to the photok-sync-fork Android app:

1. **Part A fix** — Remove the premature `uploadVaultProtectionEscrow()` call from
   `RepoManager.registerRepo()`. That call ALWAYS skipped because
   `VaultProtection(RecoveryPhrase)` doesn't exist in the local DB at that point
   in the flow (the row is created LATER inside `SetupFragment.onSetupClicked()`).
   Due to Part 3's onboarding reorder (RepoSetup BEFORE SetupFragment), the
   escrow upload was premature, and fresh-install login always fell through to
   `NoEscrowAvailable → continueWithoutEscrow → Completed → SetupFragment → new
   phrase`. The escrow upload is now performed from `SetupViewModel` AFTER both
   password + phrase `VaultProtection` rows exist in the local DB.

2. **Part B two-layer escrow** — Add a second escrow layer: the user's password
   wraps the recovery phrase. On a fresh install, the user enters their password
   (more memorable than the 12-word phrase) → unwrap phrase → feed phrase into
   the existing `RecoveryPhraseVaultProtectionHandler.unlock()` → get VMK.
   Layer 1 (phrase wraps VMK — existing) is unchanged. The new Layer 2 is
   implemented in an isolated `PhraseEscrowWrapper` class so it can be swapped
   out or made optional later without touching the core VMK/VaultProtection code.

**No commit / push.** Edits only.

## Files touched

1. `app/src/main/java/dev/leonlatsch/photok/encryption/domain/crypto/PhraseEscrowWrapper.kt` — NEW
2. `app/src/main/java/dev/leonlatsch/photok/sync/rclone/RepoManager.kt` — Part A + Part B
3. `app/src/main/java/dev/leonlatsch/photok/setup/ui/SetupViewModel.kt` — Part A
4. `app/src/main/java/dev/leonlatsch/photok/reposetup/ui/RepoSetupViewModel.kt` — Part B
5. `app/src/main/java/dev/leonlatsch/photok/reposetup/ui/RepoSetupFragment.kt` — Part B
6. `app/src/main/java/dev/leonlatsch/photok/sync/di/SyncModule.kt` — DI
7. `app/src/main/res/values/strings.xml` — new strings

## Changes per file

### 1. `PhraseEscrowWrapper.kt` (NEW)

New isolated `@Singleton` class in the `dev.leonlatsch.photok.encryption.domain.crypto`
package, paired with a `WrappedPhrase` data class.

- `WrappedPhrase.toJson()` — hand-built JSON (regex-based parse on the way back,
  mirrors the existing `parseMarker`/`parseVaultProtection` style in `RepoManager`).
- `WrappedPhrase.fromJson(json: String): WrappedPhrase?` — returns null on
  missing fields / unknown enum values / any exception (graceful missing-artifact
  case for old repos).
- `wrapPhrase(phrase, password)` — fresh salt + IV via `SecureRandom`, KEK
  derived via `KeyGen.derivePasswordKeyEncryptionKey` (same KDF primitive as
  `PasswordVaultProtectionHandler` and `RecoveryPhraseVaultProtectionHandler`,
  reuses `Kdf.PBKDF2WithHmacSHA256` + 100k iterations + 256-bit key size),
  AES/CBC/PKCS7Padding cipher, returns `WrappedPhrase`.
- `unwrapPhrase(wrapped, password): RecoveryPhrase?` — derives the KEK,
  decrypts, returns `RecoveryPhrase.from(plaintext)` on success. Wrong
  password → padding error → caught → `null` (NOT a crash; the caller surfaces
  "Incorrect password").
- Uses `kotlin.io.encoding.Base64` (matches `RecoveryPhraseStoreImpl`'s style).
- Reuses the package-local `IV_SIZE` (16) and `SALT_SIZE` (16) constants from
  `Constants.kt`.

### 2. `RepoManager.kt`

- **Imports**: added `RecoveryPhraseStore`, `PhraseEscrowWrapper`, `VaultSession`,
  `kotlinx.coroutines.flow.first`. (`first()` is needed to read the first
  emission from `recoveryPhraseStore.observe(session)`.)
- **Constructor**: added 2 new params at positions 7 + 8:
  - `phraseEscrowWrapper: PhraseEscrowWrapper`
  - `recoveryPhraseStore: RecoveryPhraseStore`
- **New `enum class EscrowType { NONE, PHRASE_ONLY, PASSWORD_PLUS_PHRASE }`**
  nested in the class — replaces the prior `escrowAvailable: Boolean` flag so
  the login branch can distinguish "password-entry UI" from "phrase-entry UI".
- **`LoginResult.Success`** — changed from `Success(escrowAvailable: Boolean)`
  to `Success(escrow: EscrowType)`. The `Failure(message)` variant is unchanged.
- **`registerRepo()`** — REMOVED the `uploadVaultProtectionEscrow()` call (with
  its surrounding try/catch + diag logging). Replaced with a long explanatory
  comment describing why the escrow upload now happens in `SetupViewModel` after
  `vaultService.create(CreateRequest.RecoveryPhrase(session, ...))` returns.
- **`loginRepo()`** — rewrote the escrow-download half:
  - Downloads Layer 1 (`recovery-phrase.json`) via `downloadVaultProtectionEscrow()`.
    Failure / missing → `EscrowType.NONE`.
  - If Layer 1 succeeded, downloads Layer 2 (`wrapped-phrase.json`) via the new
    `downloadWrappedPhraseEscrow()`. Failure / missing → `EscrowType.PHRASE_ONLY`
    (falls back to the existing phrase-entry UI for old repos).
  - Both present → `EscrowType.PASSWORD_PLUS_PHRASE` and the artifact is stored
    in a new `@Volatile private var downloadedWrappedPhrase` field.
- **New `@Volatile private var downloadedWrappedPhrase: WrappedPhrase?`** +
  **`fun getDownloadedWrappedPhrase(): WrappedPhrase?`** — the artifact
  downloaded during `loginRepo` is stored on the singleton RepoManager so the
  ViewModel doesn't need to thread it through state. Reset to `null` whenever
  `loginRepo` doesn't end in `PASSWORD_PLUS_PHRASE` (avoids stale artifacts
  across login attempts).
- **Renamed `uploadVaultProtectionEscrow()` → `uploadRecoveryPhraseEscrow()`**
  — pure rename for clarity (it uploads the `recovery-phrase.json` file =
  Layer 1). Behavior unchanged: reads the local `VaultProtection(RecoveryPhrase)`
  row, serializes to JSON via `java.util.Base64`, writes to a temp file in
  `app.cacheDir`, uploads via `rcloneController.uploadFile`, verifies via
  independent `listRemote`, deletes temp file in `finally`. Logs every step
  via `diag()`. The renamed function is now `private` (was `private`) — same
  visibility, called only by `uploadAllEscrows()`.
- **New `suspend fun uploadAllEscrows(password: String, session: VaultSession): Result<Unit>`**
  — the call site that REPLACES the prior premature `uploadVaultProtectionEscrow()`
  invocation in `registerRepo`. This is the one called by `SetupViewModel`
  after `vaultService.create(CreateRequest.RecoveryPhrase(session, ...))`.
  1. Uploads Layer 1 via `uploadRecoveryPhraseEscrow()` (throws on failure).
  2. Reads the phrase from `recoveryPhraseStore.observe(session).first()` —
     the phrase was just stored by `RecoveryPhraseVaultProtectionHandler.create()`
     inside `vaultService.create(CreateRequest.RecoveryPhrase(...))`. Uses the
     freshly-unlocked `VaultSession` (passed in by `SetupViewModel`) to decrypt
     the at-rest phrase.
  3. If phrase is null → skips Layer 2 (non-fatal; Layer 1 already uploaded).
  4. Otherwise uploads Layer 2 via the new `uploadWrappedPhraseEscrow(phrase, password)`.
  All failures non-fatal — the caller (`SetupViewModel`) wraps in try/catch and
  logs via `Timber.w` but does NOT block setup completion.
- **New `private suspend fun uploadWrappedPhraseEscrow(phrase, password): Result<Unit>`**
  — wraps the phrase via `phraseEscrowWrapper.wrapPhrase(phrase, password)`
  (FRESH salt + IV — NOT reusing the password-VaultProtection's salt, per the
  Part B spec), serializes via `WrappedPhrase.toJson()`, writes to a temp file,
  uploads to `$remote:$WRAPPED_PHRASE_REMOTE_PATH`, verifies via independent
  `listRemote` (same pattern as Layer 1 + the repo marker upload), deletes
  temp file in `finally`, logs every step via `diag()`.
- **New `suspend fun downloadWrappedPhraseEscrow(): Result<WrappedPhrase?>`**
  — counterpart of `uploadWrappedPhraseEscrow`. Downloads `wrapped-phrase.json`,
  parses via `WrappedPhrase.fromJson`. Returns `Result.success(null)` on
  not-found / empty file (old repo case — falls back to `PHRASE_ONLY`),
  `Result.failure(e)` on other download errors, `Result.success(wrapped)` on
  success. Same "not found" pattern as `downloadVaultProtectionEscrow` —
  matches "not found", "doesn't exist", "does not exist", "no such file",
  "object not found" (case-insensitive).
- **Updated doc comments** — `downloadVaultProtectionEscrow` and
  `parseVaultProtection` now refer to the renamed `uploadRecoveryPhraseEscrow`.
- **Companion object**: added two new constants:
  - `WRAPPED_PHRASE_FILENAME = "wrapped-phrase.json"`
  - `WRAPPED_PHRASE_REMOTE_PATH = "$REPO_DIR/$VAULT_PROTECTION_DIR/$WRAPPED_PHRASE_FILENAME"`
  Updated the doc comment on the existing `VAULT_PROTECTION_*` constants to
  mention they're now written by `uploadRecoveryPhraseEscrow` (called via
  `uploadAllEscrows` from `SetupViewModel`, NOT from `registerRepo`).

### 3. `SetupViewModel.kt`

- **Imports**: added `RepoManager`, `timber.log.Timber`.
- **Constructor**: added 6th param `repoManager: RepoManager`.
- **`onSetupClicked()`** — after
  `vaultService.create(CreateRequest.RecoveryPhrase(session, Bip39WordCount.Twelve))`
  (line 96), added a guarded `if (config.repoConfirmed)` block that launches a
  coroutine calling `repoManager.uploadAllEscrows(password, session)`. Failures
  are non-fatal — logged via `Timber.w(...)` but do NOT block setup completion.
  The check `config.repoConfirmed` ensures we only attempt escrow upload on a
  repo-confirmed device (i.e. Part 3 onboarding reorder: RepoSetup ran first).
  Detailed inline comment explains why this is here (Part A root cause) and
  why it's non-fatal.

### 4. `RepoSetupViewModel.kt`

- **Imports**: added `SessionRepository`, `VaultService`, `PhraseEscrowWrapper`,
  `UnlockRequest`.
- **`RepoSetupState` sealed interface**: added two new states:
  - `data class NeedsPasswordEntry(loading: Boolean = false, error: String? = null)`
    — carries the loading flag (set while `submitPassword` is unwrapping +
    unlocking) and an optional error message (set when the password is wrong).
  - `object Unlocked` — signals the Fragment's `LaunchedEffect` to call
    `onUnlocked` (= `navigateToGallery`). Distinct from `Completed` (which
    navigates to `SetupFragment`).
- **Constructor**: added 3 new params:
  - `phraseEscrowWrapper: PhraseEscrowWrapper`
  - `vaultService: VaultService`
  - `sessionRepository: SessionRepository`
- **`checkRemoteAndDetectRepo()`** — rewrote the `LoginResult.Success` branch
  to pattern-match on `loginResult.escrow` (the new `EscrowType` enum):
  - `PASSWORD_PLUS_PHRASE` → `RepoSetupState.NeedsPasswordEntry()`
  - `PHRASE_ONLY` → `RepoSetupState.NeedsPhraseEntry` (existing — old repo path)
  - `NONE` → `RepoSetupState.NoEscrowAvailable` (existing — degraded mode)
- **New `fun submitPassword(password: String)`** — invoked from the
  `NeedsPasswordEntry` UI on submit:
  1. Sets state to `NeedsPasswordEntry(loading = true)`.
  2. Gets the wrapped-phrase artifact via `repoManager.getDownloadedWrappedPhrase()`.
     If null (shouldn't happen — only entered `NeedsPasswordEntry` on
     `PASSWORD_PLUS_PHRASE`), surfaces as "Incorrect password" error.
  3. Calls `phraseEscrowWrapper.unwrapPhrase(wrapped, password)`:
     - Wrong password → padding error → caught internally → `null` returned
       → state goes back to `NeedsPasswordEntry(error = "Incorrect password")`
       (clear message, NOT a crash).
     - Correct password → `RecoveryPhrase` recovered.
  4. Calls `vaultService.unlock(UnlockRequest.RecoveryPhrase(phrase))` — this
     uses the local `VaultProtection(RecoveryPhrase)` row that
     `downloadVaultProtectionEscrow()` persisted during `loginRepo()`.
     - Success → `sessionRepository.set(session)` + state goes to `Unlocked`
       → Fragment's `LaunchedEffect` observes → calls `onUnlocked` →
       `navigateToGallery`. The VMK is now in memory via `SessionRepository`.
     - Failure → state goes back to `NeedsPasswordEntry(error = e.message)`
       (rare; surfaces the underlying error rather than masking it).

### 5. `RepoSetupFragment.kt`

- **Imports**: added `Spacer`, `fillMaxWidth`, `height`, `KeyboardActions`,
  `KeyboardOptions`, `OutlinedTextField`, `ImeAction`, `KeyboardType`,
  `PasswordVisualTransformation`.
- **`RepoSetupScreen`'s `LaunchedEffect(state)`** — added a second branch:
  when `state is RepoSetupState.Unlocked`, calls `onUnlocked()` (= navigates
  straight to the gallery, skipping `SetupFragment`).
- **`when (val s = state)`** — added two new branches:
  - `is NeedsPasswordEntry -> NeedsPasswordEntryContent(s, viewModel)` —
    rendered inside the existing padded/centered Column (NOT an early-out —
    the password form doesn't need its own Scaffold).
  - `Unlocked -> { /* handled by LaunchedEffect */ }` — placeholder for
    exhaustiveness.
- **New `@Composable NeedsPasswordEntryContent(state, viewModel)`** — the
  password-entry UI for the login branch. Contains:
  - Title text (`repo_setup_password_entry_title`).
  - Subtitle text (`repo_setup_password_entry_subtitle`).
  - `OutlinedTextField` for the password, with:
    - `PasswordVisualTransformation` (masks the input).
    - `KeyboardOptions(keyboardType = Password, imeAction = Done)`.
    - `KeyboardActions.onDone` triggers `submitPassword` when non-empty +
      not loading.
    - `isError = state.error != null` + `supportingText` shows the error
      message in red.
    - `enabled = !state.loading`.
  - Either a `CircularProgressIndicator` (while loading) or a `Button`
    labeled `repo_setup_password_entry_submit` (when idle, disabled while
    password is empty).
  - Local `var password by remember { mutableStateOf("") }` — the password
    field is preserved across attempts (the user doesn't have to retype after
    a wrong password; they can edit + retry).

### 6. `SyncModule.kt`

- **Imports**: added `RecoveryPhraseStore`, `PhraseEscrowWrapper`.
- **`provideRepoManager()`** — added 2 new params (`phraseEscrowWrapper`,
  `recoveryPhraseStore`) and passes them as the 7th + 8th args to the
  `RepoManager` constructor. Both are already bound by Hilt:
  - `PhraseEscrowWrapper` has `@Inject constructor` + `@Singleton`.
  - `RecoveryPhraseStore` is bound to `RecoveryPhraseStoreImpl` via
    `EncryptionBindingModule.bindRecoveryPhraseStore` (which already existed).

### 7. `strings.xml`

Added 4 new strings in the "Repo setup: login-branch password entry" comment
section, immediately after the existing `repo_setup_no_escrow_*` block:

- `repo_setup_password_entry_title` = "Enter your password"
- `repo_setup_password_entry_subtitle` = "Enter your vault password to restore your backup"
- `repo_setup_password_entry_error` = "Incorrect password"
- `repo_setup_password_entry_submit` = "Restore"

## Verification

- Ran `./gradlew :app:compileFossDebugKotlin --offline` —
  **BUILD SUCCESSFUL** after one fix (initial version of
  `uploadAllEscrows` returned `Result<Any>` because the last expression in the
  `runCatching` block was the nested `Result<Unit>` from
  `uploadWrappedPhraseEscrow`; fixed by extracting to a local var + throwing
  on failure, then implicit `Unit` return).
- Could NOT run `:app:compilePlayDebugKotlin` — the Play flavor's
  `com.google.android.play:review:2.0.2` dependency isn't cached for offline
  mode (network/dependency issue, unrelated to my changes; the shared main
  source set is fully verified by the Foss compile).
- Did NOT run lint — not requested, and the existing `gradle/lint-baseline.xml`
  baseline covers the pre-existing warnings. The new code follows the existing
  patterns (hand-rolled JSON, regex-based parsing, `diag()` logging) so it
  shouldn't introduce new lint issues.

## Key constraints honored

- Did NOT change `RecoveryPhraseVaultProtectionHandler` (the phrase→VMK wrapping
  stays unchanged).
- Did NOT change `PasswordVaultProtectionHandler` (the password→VMK wrapping
  stays unchanged).
- `PhraseEscrowWrapper` is isolated in its own class (Part B3: don't paint into
  a corner) — no dependencies on the core VMK/VaultProtection code.
- Reused `KeyGen.derivePasswordKeyEncryptionKey()` — same KDF primitive
  (`PBKDF2WithHmacSHA256`), same parameters (100k iterations, 256-bit key) as
  the existing handlers.
- Fresh salt + IV for the phrase-wrapping via `SecureRandom()` — NOT reusing
  the password-VaultProtection's salt.
- Wrong password → catch padding error → clear "Incorrect password" message
  (NOT a crash). `PhraseEscrowWrapper.unwrapPhrase()` catches internally and
  returns `null`; `RepoSetupViewModel.submitPassword()` surfaces the
  `repo_setup_password_entry_error` string.
- Kept all existing `RcloneDiag` logging patterns (every step in
  `uploadAllEscrows`, `uploadWrappedPhraseEscrow`, `downloadWrappedPhraseEscrow`,
  and the rewritten `loginRepo` logs via `diag()`).
- Kept the "independent verification via re-list" pattern — both Layer 1 and
  Layer 2 uploads are verified by `listRemote` after the upload returns, same
  as the existing `registerRepo` marker upload and the existing
  `uploadRecoveryPhraseEscrow` (formerly `uploadVaultProtectionEscrow`).
- Kept the "graceful missing-artifact" pattern — both Layer 1 and Layer 2
  downloads treat "not found"-style rclone errors as `Result.success(null)`
  rather than `Result.failure`, so old repos created before each feature
  existed degrade gracefully instead of erroring out.

## Non-Goals (per task spec)

- Did NOT change `RecoveryPhraseVaultProtectionHandler` or
  `PasswordVaultProtectionHandler`.
- Did NOT touch the standalone `RecoveryPhraseRestoreFragment` — only the
  embedded reuse via `RecoveryPhraseRestoreScreen` in `RepoSetupFragment`
  (for the `PHRASE_ONLY` branch — unchanged from the prior task).
- Did NOT change `RepoManager.detectRepo()` semantics.
- Did NOT push or commit.
