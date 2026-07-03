# Items 1–4 — pack perf fix, .crypt toggle, single-page login, unified register

Code-editing subagent. Implements 4 features, builds, and pushes.

## Item 1: Thumbnail pack audit — fix the O(n²) array copy

### Changes
- `app/src/main/java/onlasdan/gallery/sync/domain/SyncConfig.kt`:
  - `THUMBNAIL_PACK_SIZE_BYTES`: 50 MB → 5 MB (~300 thumbnails at 17KB each — a more
    reasonable pack size for mobile).
- `app/src/main/java/onlasdan/gallery/sync/work/HashRegistry.kt`:
  - `flushPendingThumbnailPacks`: replaced `ByteArray` + `System.arraycopy`
    growth pattern with `java.io.ByteArrayOutputStream`. `currentPackStream.write(pt.bytes)`
    is O(1) amortized (was O(n) per append → O(n²) for a full pack).
  - All references to `currentPackBytes.size` replaced with `currentPackStream.size()`.
  - `packFile.writeBytes(currentPackBytes)` → `packFile.writeBytes(currentPackStream.toByteArray())`.
  - `currentPackBytes = ByteArray(0)` (reset after upload) → `currentPackStream.reset()`.
  - Comment strings updated (`≤50 MB` → `≤5 MB`) for consistency.

### Why
~3000 array copies per 50MB pack (50MB / 17KB ≈ 3000 thumbnails, each append copies
the entire growing buffer). With `ByteArrayOutputStream`, growth is geometric
(2× capacity) so amortized O(1) per write.

## Item 2: Restore .crypt toggle + "Clean .crypt" button

### Changes
- `app/src/main/java/onlasdan/gallery/sync/work/PhotoSyncWorker.kt`:
  - Restored `if (config.syncDeleteAfterUpload)` guard around
    `deleteLocalOriginalAfterUpload(photo)` — was made unconditional in the
    previous round; reverted to conditional per the spec.
- `app/src/main/java/onlasdan/gallery/settings/data/Config.kt`:
  - `syncDeleteAfterUpload` property restored (removed `@Suppress("unused")` and
    the "NOW UNUSED" comment block — it's actively read by `PhotoSyncWorker` again).
- `app/src/main/java/onlasdan/gallery/settings/domain/PreferenceScreenConfig.kt`:
  - Uncommented the `SYNC_DELETE_AFTER_UPLOAD` Switch preference in the Cloud Sync
    section.
  - Added a new `Preference.Simple` for "Clean cached originals" (`KEY_ACTION_CLEAN_CACHED_ORIGINALS`).
- `app/src/main/java/onlasdan/gallery/settings/ui/SettingsFragment.kt`:
  - Added `KEY_ACTION_CLEAN_CACHED_ORIGINALS = "action_clean_cached_originals"` constant.
- `app/src/main/java/onlasdan/gallery/settings/ui/compose/SettingsViewModel.kt`:
  - New `cleanCachedOriginals(): Pair<Int, Long>` function. Iterates every photo,
    skips any whose `syncState != SyncState.UPLOADED` (SAFETY: never touches
    LOCAL_ONLY or UPLOAD_PENDING), deletes the local `.crypt` file at
    `filesDir/<internalFileName>`, returns (count, bytesFreed). Shows toast on
    start, success (with `Formatter.formatFileSize` for the bytes), nothing-to-do,
    and failure.
- `app/src/main/java/onlasdan/gallery/settings/ui/compose/SettingsScreen.kt`:
  - Registered `KEY_ACTION_CLEAN_CACHED_ORIGINALS` callback that calls
    `viewModel.cleanCachedOriginals()` in a coroutine scope.
- `app/src/main/res/values/strings.xml`:
  - Added 4 new strings: `settings_clean_cached_originals_title`,
    `settings_clean_cached_originals_summary`, `settings_clean_cached_originals_toast_started`,
    `settings_clean_cached_originals_toast_success` (uses `%1$d` count + `%2$s`
    human-readable bytes), `settings_clean_cached_originals_toast_nothing`,
    `settings_clean_cached_originals_toast_failed`.

### Why
- The unconditional-delete change in the previous round was too aggressive — users
  who want offline access lose their local originals immediately on every upload.
  Restoring the toggle lets users opt-in.
- The "Clean cached originals" button provides a one-shot cleanup of cached
  originals for users who keep the toggle OFF but want to reclaim space periodically.
- The SAFETY check (`syncState != UPLOADED → skip`) ensures we never delete a
  local original that hasn't been verified on the remote — guards against
  accidental data loss if the user taps the button before a sync completes.

## Item 3: Redesign remote-picker + login into single page

### Changes
- `app/src/main/java/onlasdan/gallery/sync/rclone/RepoManager.kt`:
  - New `suspend fun hasRepo(remoteName: String): Boolean?` method. Lightweight
    repo-existence check that uses `rcloneController.listRemote("$remoteName:",
    REPO_DIR)` directly WITHOUT mutating `Config.syncChosenRemote`. Returns:
    - `true` if the repo marker file exists on the remote
    - `false` if the remote is reachable but no marker (or dir doesn't exist)
    - `null` if the remote is unreachable (network/auth error)
- `app/src/main/java/onlasdan/gallery/reposetup/ui/RepoSetupViewModel.kt`:
  - New sealed interface `RemoteCheckStatus` (UNCHECKED / CHECKING / REPO_FOUND /
    NO_REPO / ERROR) + `data class RemoteStatus(remote, status)`.
  - New `_remoteStatuses: MutableStateFlow<List<RemoteStatus>>` + exposed
    `remoteStatuses: StateFlow`.
  - New `_pendingPassword: MutableStateFlow<String>` + exposed
    `pendingPassword: StateFlow`.
  - New `checkAllRemotes()` function. Probes every remote in the imported
    rclone.conf via `repoManager.hasRepo()` concurrently — each row updates
    independently as its check completes.
  - Modified `chooseRemote(name, pendingPassword = "")` — accepts an optional
    pending password. If non-blank, stashes it in `_pendingPassword` so the
    login flow can auto-submit it when `PASSWORD_PLUS_PHRASE` escrow is
    available (saving the user from re-typing on the dedicated
    `NeedsPasswordEntry` screen).
  - New `setPendingPassword(password)` for explicit password stash.
  - Modified `checkRemoteAndDetectRepo()` — when `LoginResult.escrow ==
    PASSWORD_PLUS_PHRASE` and `_pendingPassword.value` is non-empty, auto-calls
    `submitPassword(pendingPassword)` so the user goes straight to the gallery
    without seeing the dedicated password-entry screen.
  - Modified `submitPassword()` — clears `_pendingPassword` after consumption
    (success or failure) so any retry on the dedicated screen starts fresh.
- `app/src/main/java/onlasdan/gallery/reposetup/ui/RepoSetupFragment.kt`:
  - Replaced `NeedsRemoteChoiceContent` (dropdown picker) with a single-page
    Composable showing:
    1. Title
    2. "Check All" `OutlinedButton` → `viewModel.checkAllRemotes()`
    3. Scrollable `LazyColumn` of remotes (capped at 320.dp). Each row is a
       `RadioButton` + name + type + status badge (UNCHECKED/CHECKING/
       REPO_FOUND/NO_REPO/ERROR) with color-coded text.
    4. Inline `OutlinedTextField` for vault password (with hint).
    5. "Connect" `Button` → `viewModel.chooseRemote(selected, pendingPassword =
       password)`.
  - Removed unused dropdown imports (`DropdownMenu`, `DropdownMenuItem`,
    `ExposedDropdownMenuBox`, `ExposedDropdownMenuDefaults`).
  - Added new imports: `clickable`, `heightIn`, `LazyColumn`, `items`,
    `rememberScrollState`, `CircleShape`, `RoundedCornerShape`,
    `verticalScroll`, `RadioButton`, `FontWeight`, `sp`.
- `app/src/main/res/values/strings.xml`:
  - Added 9 new strings for the single-page picker: `repo_setup_check_all`,
    `repo_setup_remote_status_unchecked`, `repo_setup_remote_status_checking`,
    `repo_setup_remote_status_repo_found`, `repo_setup_remote_status_no_repo`,
    `repo_setup_remote_status_error`, `repo_setup_connect`,
    `repo_setup_password_label`, `repo_setup_password_hint`.

### Why
- Dropdown was clunky when there are many remotes — no per-remote status.
- Single-page picker lets the user see "Repo found" / "No repo" before picking,
  so they don't pick a wrong remote and have to retry.
- Inline password + Connect button collapses the multi-state flow
  (NeedRemoteChoice → Checking → Connecting → NeedsPasswordEntry → Unlocked)
  into a single user action for the common case (existing repo + escrow).
- The `hasRepo()` method bypasses `detectRepo()` to avoid mutating
  `Config.syncChosenRemote` during the check (which would side-effect the
  chosen remote before the user actually picks one).

## Item 4: Unify register screen — phrase + password on one screen

### Changes
- `app/src/main/java/onlasdan/gallery/setup/ui/SetupFragment.kt`:
  - When `setupState == SHOW_RECOVERY_PHRASE`, instead of navigating to
    `RecoveryPhraseSetupFragment`, the fragment now calls
    `showRecoveryPhraseInline()` which:
    1. Hides the existing password-setup LinearLayout (first child of root
       FrameLayout) via `View.GONE`.
    2. Creates a `ComposeView` hosting `RecoveryPhraseSetupScreen` (the same
       composable used by the standalone fragment) and adds it to the root
       FrameLayout with `MATCH_PARENT` layout params.
    3. Wires `onContinue` to the same POST_NOTIFICATIONS permission + gallery
       navigation flow that `RecoveryPhraseSetupFragment.onContinue` used.
  - Added `navigateToGalleryOrPop()` helper — mirrors
    `RecoveryPhraseSetupFragment.navigateToGalleryOrPop()` so back behavior is
    identical (pops back to Settings if that's where we came from, otherwise
    navigates to the gallery).
  - New imports: `Manifest`, `PackageManager`, `Build`, `LayoutInflater`,
    `ViewGroup`, `FrameLayout`, `LinearLayout`,
    `rememberLauncherForActivityResult`, `ActivityResultContracts`,
    `ComposeView`, `ContextCompat`, `children`, `AppTheme`.
  - Removed the navigation call to `action_global_recoveryPhraseSetupFragment`
    from the SHOW_RECOVERY_PHRASE branch (the navigation action + the
    standalone fragment remain in the codebase — they're still used by
    UnlockFragment and SettingsScreen for other entry points).

### Why
- The two-screen flow (password → navigate to phrase screen) required an extra
  navigation step and made the recovery-phrase display feel disconnected from
  the password setup.
- Showing the phrase inline on the same screen keeps the user in one place for
  the entire setup flow — password creation → recovery phrase display →
  permission request → gallery.
- The standalone `RecoveryPhraseSetupFragment` is kept (not deleted) because:
  - `UnlockFragment` uses it for the "import recovery phrase" unlock path
    (different use case — not fresh setup).
  - `SettingsScreen` uses it for the "view recovery phrase" action from
    Settings (also different use case).
  - Deleting it would require also removing the nav graph entry + those two
    call sites — out of scope for this item.

## Build
- `gradle.properties`: bumped `appVersionCode` 88 → 89.
- Build command: `./gradlew :app:assembleFossDebug --no-daemon --console=plain -Pandroid.injected.build.abi=arm64-v8a`
- Result: BUILD SUCCESSFUL in 55s. Only pre-existing warnings (deprecated
  `legacyPasswordHash`/`legacyUserSalt`, `@param:` annotation target hint,
  Java source/target 8 deprecation).

## Commit
- Message: `feat: pack perf fix + .crypt toggle + single-page login + unified register`
- Pushed to `origin/main`.
