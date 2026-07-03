# Batch 1: Auto-lock fix + Stealth verify + Hash verification + Re-encrypt fix

**Task ID**: batch1
**Agent**: code-editing-subagent
**Date**: 2026-07-03
**versionCode**: 91 → 92

## Summary

Implemented four fixes/features in the photok-sync-fork Android project (package `onlasdan.gallery`).

---

## Item 1: Auto-lock timer fix (persist `wentToBackgroundAt`)

**Problem**: `BaseApplication.onStart()` checks `securityLockTimeout` but `wentToBackgroundAt`
was an in-memory `Long` that reset to `0L` after process kill. So after a force-close + reopen,
the auto-lock never triggered.

**Fix**:
- Added `Config.lastBackgroundedAt: Long` (prefs-backed, default `0L`).
- Added constants `LAST_BACKGROUNDED_AT` + `LAST_BACKGROUNDED_AT_DEFAULT`.
- `BaseApplication.onCreate()`: restore `wentToBackgroundAt = config.lastBackgroundedAt`.
- `BaseApplication.onStop()`: persist `config.lastBackgroundedAt = wentToBackgroundAt`.
- `BaseApplication.lockApp()`: clear `config.lastBackgroundedAt = 0L` so the next cold
  start doesn't immediately re-lock.
- Added diagnostic log in `onCreate()` so the restore is visible in logcat.

**Files touched**:
- `app/src/main/java/onlasdan/gallery/settings/data/Config.kt`
- `app/src/main/java/onlasdan/gallery/BaseApplication.kt`

---

## Item 2: Stealth mode verify + anti-screenshot

**Verified** (no changes needed):

- Anti-screenshot: `BaseActivity.onCreate()` adds `WindowManager.LayoutParams.FLAG_SECURE`
  when `!config.securityAllowScreenshots`. `MainActivity` extends `BindableActivity`
  extends `BaseActivity` → inherits FLAG_SECURE automatically. `ImageViewerFragment` is
  a Fragment hosted by `MainActivity`, so it inherits the secure flag — no need to add
  it to MainActivity.onCreate() again.
- Stealth launcher: `AndroidManifest.xml` declares two activity-aliases:
  - `.MainLauncher` → `MainActivity` (default enabled)
  - `.StealthLauncher` → `ForwardDialerActivity` (default disabled, icon = `ic_phone`,
    label = `hide_app_app_name_stealth`)
- `ToggleMainComponentUseCase` swaps enabled state between the two launchers via
  `PackageManager.setComponentEnabledSetting()`.
- `DialLauncher` (BroadcastReceiver for `android.provider.Telephony.SECRET_CODE`) starts
  `MainActivity` when the user dials `*#*#<securityDialLaunchCode>#*#*`.
- `ForwardDialerActivity` → `ForwardDialerViewModel` either forwards to the system dialer
  or opens the recovery menu (if airplane mode is on and tapped twice within 5s).

All references use the correct `onlasdan.gallery.*` package paths after the rebrand.
No broken references found.

---

## Item 3: Hash verification for upload (`syncVerifyHash` option)

**Problem**: Upload verification was size-only on backends that don't support server-side
hashsum (e.g. Koofr). The existing `verifyRemote()` call falls through to
`HashNotSupportedException` and silently degrades to a size-only check.

**Fix**:
- Added `Config.syncVerifyHash: Boolean` (prefs-backed, default `false`).
- Added constants `SYNC_VERIFY_HASH` + `SYNC_VERIFY_HASH_DEFAULT`.
- Added `Preference.Switch` row in `PreferenceScreenConfig.kt` (Cloud Sync section).
- Added strings `settings_sync_verify_hash_title` + `settings_sync_verify_hash_summary`
  in `strings.xml`.
- Injected `CryptoEngine` into `PhotoSyncWorker` constructor.
- In `performUpload()`, AFTER the existing `verifyFileExists` + `verifyRemote` calls,
  added an optional full verification pass gated on `config.syncVerifyHash`:
  1. Download the freshly-uploaded remote file to `cacheDir/verify-<uuid>.crypt`.
  2. Open a `FileInputStream` and wrap it in `cryptoEngine.createDecryptStream(...)`
     using the current vault session's VMK.
  3. Stream-decrypt and feed bytes into a `MessageDigest("SHA-256")`.
  4. Compare the resulting hex hash against `photo.contentHash` (case-insensitive).
  5. On mismatch: throw `IOException("Hash mismatch: expected=… actual=…")` so the
     upload is retried. On match: log success.
  6. Always delete the temp file in a `finally` block.

The verification is **opt-in** (default OFF) because it doubles bandwidth per upload
(download = upload size again). Power users / paranoiac users can enable it in
Settings → Cloud Sync → "Verify uploads by hash".

If the vault session is null at verification time (e.g. delayed WorkManager retry after
the app was backgrounded and locked), the verification is skipped with a diagnostic log —
the size check still passed, so the upload is still considered valid.

**Files touched**:
- `app/src/main/java/onlasdan/gallery/settings/data/Config.kt`
- `app/src/main/java/onlasdan/gallery/settings/domain/PreferenceScreenConfig.kt`
- `app/src/main/res/values/strings.xml`
- `app/src/main/java/onlasdan/gallery/sync/work/PhotoSyncWorker.kt`

---

## Item 4: Re-encrypt fix (password change)

**Verified** the existing `ChangePasswordUseCase` is correct:
- Line 38: `val session = sessionRepository.require()`
- Line 55: `cipher.doFinal(session.vmk.encoded)` — re-wraps the EXISTING VMK with the
  new password-derived KEK. The VMK itself does NOT change, so already-uploaded `.crypt`
  photo files do NOT need re-encryption. Only the key wrapping changes.

**Fixed the missing escrow re-upload**:
- `ChangePasswordViewModel` previously called `changePasswordUseCase(newPassword)` and
  stopped there. The local DB was updated, but the remote escrow artifacts were stale.
- After a password change, two artifacts on the remote need attention:
  - `recovery-phrase.json.crypt` (Layer 1) — VMK wrapped by recovery phrase. NOT affected
    by password change, but re-uploading is harmless (fresh GCM nonce → different
    ciphertext, same payload).
  - `wrapped-phrase.json.crypt` (Layer 2) — recovery phrase wrapped by the user's
    PASSWORD. This DOES change — the old password no longer unwraps it.
- Injected `SessionRepository` and `RepoManager` into `ChangePasswordViewModel`.
- After `changePasswordUseCase` succeeds, if `sessionRepository.get() != null` AND
  `config.repoConfirmed`, call `repoManager.uploadAllEscrows(newPassword, session)`.
  This re-uploads BOTH layers atomically (Layer 1 is byte-different but functionally
  identical; Layer 2 has the new password wrapping).
- Escrow upload failure is non-fatal: the password change still succeeded locally.
  The user can still unlock with the new password on THIS device. Only fresh-install
  restore would be broken until they re-run setup.

**Files touched**:
- `app/src/main/java/onlasdan/gallery/settings/ui/changepassword/ChangePasswordViewModel.kt`

---

## Build

- `./gradlew :app:assembleFossDebug --no-daemon --console=plain -Pandroid.injected.build.abi=arm64-v8a`
- BUILD SUCCESSFUL in 59s (45 actionable tasks: 20 executed, 25 up-to-date).
- Only pre-existing warnings (deprecation warnings on `legacyPasswordHash`, `legacyUserSalt`,
  and Kotlin annotation-target warnings) — no new errors or warnings from this batch.
- Output APK: `app/build/intermediates/apk/foss/debug/photok-1.0.0-foss-debug.apk` (88M).

## Version bump

- `gradle.properties`: `appVersionCode` 91 → 92.

## Commit

`feat: auto-lock fix + stealth verify + hash verification + re-encrypt fix`
