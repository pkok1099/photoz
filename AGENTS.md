# AGENTS.md — PhotoZ Codebase Guide

This document is the authoritative guide for AI agents working in the PhotoZ codebase.  
Read it fully before writing any code.

---

## Table of Contents

1. [Project Overview](#project-overview)
2. [Repository Layout](#repository-layout)
3. [Architecture](#architecture)
4. [UI Patterns](#ui-patterns)
5. [Encryption System](#encryption-system)
6. [Cloud Sync (rclone)](#cloud-sync-rclone)
7. [Auth & Login Flow](#auth--login-flow)
8. [Key Libraries](#key-libraries)
9. [Database](#database)
10. [Dependency Injection](#dependency-injection)
11. [Translations & Strings](#translations--strings)
12. [Testing](#testing)
13. [Product Flavors](#product-flavors)
14. [Build & CI](#build--ci)
15. [Rules & Conventions](#rules--conventions)

---

## Project Overview

PhotoZ is an Android app (Kotlin) that provides an on-device encrypted photo and video vault. All media is stored inside the Android private files directory, encrypted with AES-256 (CBC for media bodies, GCM for metadata, chunked-GCM for video streaming). The app has no server component — cloud sync uses the user's own rclone remote via gomobile JNI.

Key features: gallery, albums, backup/restore, biometric unlock, recovery phrase, password change, dark/light theme, hide-app mode (stealth dialer), settings screen, cloud sync (rclone gomobile JNI), multi-vault, content-hash dedup, two-layer key escrow, SQLCipher at-rest DB encryption.

---

## Repository Layout

The top-level directory contains `app/`, `gradle/`, `adr/`, `docs/`, `ENCRYPTION.md`, and this file. All source code lives under `app/src/main/java/onlasdan/gallery/`.

Full documentation is in `docs/` — see `docs/README.md` for an index of all 10 documentation files.

Each feature follows the same internal structure:

- **`data/`** — Room DAOs, table entities, repository implementations.
- **`domain/`** — pure Kotlin interfaces, models, use cases. No Android imports.
- **`di/`** — Hilt modules that bind `data` implementations to `domain` interfaces.
- **`ui/`** — ViewModels, Fragments, Compose screens, navigator classes.
  - **`ui/compose/`** — screen-level and sub-composables.

Shared UI components and the theme live in `ui/`. Legacy base classes (`Bindable*`, `Base*`) live in `uicomponnets/` (note: typo preserved for backward compat). Extensions and misc utilities live in `other/`.

---

## Architecture

### Core Pattern

The app follows a **feature-first layered architecture**:

- **`domain`** — pure Kotlin. Interfaces, models, use cases. No Android imports.
- **`data`** — Room tables, DAOs, repository implementations. Implements `domain` interfaces.
- **`di`** — Hilt modules that bind `data` implementations to `domain` interfaces.
- **`ui`** — ViewModels + Compose screens + Fragments + Navigator classes.

### Single Activity

There is a single `MainActivity` (with `DataBinding`). All screens are **Fragments** navigated via the Jetpack Navigation Component (`main_nav_graph.xml`). Fragments host Compose UIs via `ComposeView`.

### Navigation

- Declared in `main_nav_graph.xml` with Safe Args.
- Bottom-tab navigation (`MainMenu`, a Compose component) connects to top-level destinations: Gallery, Albums, Settings.
- Fragment-level navigation uses typed `Navigator` classes injected via Hilt.
- `NavigateToGallery` is the gate: `config.repoConfirmed` must be `true` to enter gallery.

---

## UI Patterns

### Compose First

**New screens must use Jetpack Compose (Material3).** Legacy screens use XML DataBinding via the `Bindable*` base classes; do not add new DataBinding screens.

### Simple MVI

Every screen follows a **simple, flat MVI**. There is no dedicated MVI framework — it is plain Kotlin + StateFlow.

**The four files per screen:**

| File | Role |
|------|------|
| `XyzFragment.kt` | `@AndroidEntryPoint Fragment`. Creates a `ComposeView`, provides `CompositionLocal`s, collects navigation event flows. |
| `XyzViewModel.kt` | `@HiltViewModel`. Exposes `val uiState: StateFlow<XyzUiState>`. Accepts events via `fun handleUiEvent(event: XyzUiEvent)`. |
| `XyzUiState.kt` | `sealed interface XyzUiState`. Common states: `Empty`, `Loading`, `Content(...)`. |
| `XyzUiEvent.kt` | `sealed interface XyzUiEvent`. One `data class`/`data object` per user action. |
| `XyzScreen.kt` | Top-level `@Composable` that takes the `ViewModel`, collects state with `collectAsStateWithLifecycle()`, and branches on the sealed state. |

Navigation events that must leave the ViewModel are sent via a `Channel<XyzNavigationEvent>` and collected in the Fragment.

**Canonical example** — `GalleryFragment` / `GalleryViewModel` / `GalleryUiState` / `GalleryUiEvent` / `GalleryScreen`.

### Legacy DataBinding Screens

Still used in `unlock` and a few others. They extend `BindableFragment<ViewDataBinding>` / `BindableActivity<ViewDataBinding>`. ViewModels can extend `ObservableViewModel` for two-way bindings. **Do not create new DataBinding screens.**

### Theme

`AppTheme` (in `ui/theme/Theme.kt`) wraps every Compose entry point. It respects the system dark/light setting and supports Material You dynamic color (Android 12+). Always call `AppTheme { ... }` at the root of a Fragment's `ComposeView.setContent { }`.

### Performance Rules

- **Always use `remember`** for expensive calculations in composition (e.g. password strength checks, regex patterns, Brush objects).
- **Use `rememberSaveable`** for form state that should survive rotation (password fields, dialog text).
- **Add `key = { it.id }`** to all `LazyColumn` / `LazyVerticalGrid` items for stable identity.
- **Coil memory cache is ENABLED** — decrypted bitmaps are cached in memory (25% heap). Disk cache is DISABLED for security.
- **Use `collectAsStateWithLifecycle()`** — never `collectAsState()` (lifecycle-aware collection prevents unnecessary recomposition when app is backgrounded).

### CompositionLocals

Shared objects are injected into the Compose tree via `CompositionLocal`. Check `ui/CompositionLocals.kt` and feature-specific files (e.g. `transcoding/compose/LocalEncryptedImageLoader.kt`, `settings/ui/compose/ConfigCompositionLocal.kt`) for the current set.

Provide them in the Fragment's `setContent { }` block using `CompositionLocalProvider`.

### Compose Components

Reusable composables live in `ui/components/` (e.g., `AppName`, `ConfirmationDialog`, `MagicFab`, `MultiSelectionMenu`). **Compose first** means building components here and reusing them across features.

---

## Encryption System

> **Read `ENCRYPTION.md` and `docs/03-encryption.md` before touching any encryption-related code.**

### Current Formats

| Version | Cipher | Use Case |
|---------|--------|----------|
| 0x01 | AES-CBC + salt | Legacy (v1.x migration only) |
| 0x02 | AES-CBC/PKCS7Padding | Photos (default) |
| 0x03 | AES-GCM (single-stream) | Metadata |
| 0x04 | AES-GCM (chunked, 1MB chunks) | Videos (progressive streaming + random access) |

### Key Hierarchy

- **VMK (Vault Master Key):** 256-bit random AES key. Encrypts all media files. Never stored plaintext.
- **KEK (Key Encryption Key):** Derived from password (Argon2id) or biometrics (Android Keystore). Wraps the VMK.
- **DB Key:** 32-byte random key backed by Android Keystore (StrongBox if available, TEE fallback). Encrypts `photok.db` via SQLCipher. Stored separately from VMK.

### KDF

- **Argon2id** (default for all new vaults) — 64MB memory, 3 iterations, parallelism=1. Bouncy Castle implementation.
- **PBKDF2** (legacy, 100k iterations) — kept for backwards-compatible unlock of old vaults.
- IV encoding for Argon2id: 4-byte big-endian memory cost prefix + 16-byte AES wrapping IV (Base64-encoded together in the `iv` field).

### Key Classes

All encryption classes live under `encryption/`. Start with `VaultService` (in `encryption/domain/`) to understand the entry point. `HybridCryptoEngine` (renamed from `CbcCryptoEngine`) handles all 4 format versions via version byte dispatch. `VaultFileStorage` (in `io/`) is the only place that opens encrypted file streams.

### SQLCipher

- Main DB (`photok.db`): SQLCipher-encrypted, key from Android Keystore.
- Bootstrap DB (`photok_meta.db`): plaintext, holds `vault_protection` table (must be readable before encrypted DB unlock).
- `SqlCipherKeyProvider`: StrongBox → TEE fallback. If both fail, `FallbackSqlCipherKeyProvider` (plaintext SharedPreferences) + `Config.keystoreFallbackActive` flag + Settings UI warning banner.
- Schema version: 17 (see `PhotokDatabase.kt`). Bootstrap DB version: 2.

### Typed Exceptions (F-ENC-024)

`HybridCryptoEngine.createEncryptStream()` / `createDecryptStream()` throw typed exceptions (was: return null):
- `CorruptHeaderException` — file header is truncated or malformed
- `InvalidVersionByteException` — version byte not recognized (0x01-0x04)
- `UnsupportedAlgorithmException` — cipher algorithm not supported

### Rules for Encryption Code

- **Never** store the raw VMK to disk or shared preferences.
- **Never** delete `legacyPasswordHash` or `legacyUserSalt` from shared preferences (migration fail-safe).
- Use `CryptoEngine` interface — do not instantiate `HybridCryptoEngine` directly in UI or repository code.
- All file I/O goes through `VaultFileStorage`.
- **Never** catch `Exception` broadly in unlock paths — catch only `AEADBadTagException` + `BadPaddingException` (auth failures). Other exceptions should propagate as real errors.

---

## Cloud Sync (rclone)

> **Read `docs/02-rclone-integration.md` for full details.**

### Architecture

rclone is loaded as a **gomobile JNI shared library** (`libgojni.so` via `System.loadLibrary("gojni")`), NOT as a subprocess. This is W^X-safe on Android 16.

- **AAR:** `app/libs/librclone.aar` (37 MB, rclone v1.68.2, 16KB page-aligned)
- **JNI entry:** `gomobile.Gomobile.rcloneRPC(method, input) → RcloneRPCResult`
- **AAR build:** `scripts/build-rclone-gomobile.sh` (CGO_ENABLED=1, 16KB alignment, arm64 + arm32)

### RcloneController API

12 RC operations: `uploadFile`, `downloadFile`, `uploadFileWithProgress`, `downloadFileWithProgress`, `listRemote`, `deleteFile`, `verifyFileExists`, `verifyRemote`, `createDir`, `moveFile`, `moveDir`, `copyDir`, `removeDir`, `hashFile`.

Key implementation details:
- **Path splitting:** `uploadFile`/`downloadFile` split `remotePath` at last `/` → `dstFs` (directory context) + `dstRemote` (filename only). Without this, `operations/copyfile` fails with "is a file not a directory".
- **Error detection:** `hasRpcError()` uses `JSONObject.has("error")` + `getStatus() != 200` (not substring matching).
- **RPC timeout:** 30s via `withTimeoutOrNull` + `runBlocking`.
- **Reflection caching:** `Class.forName` + `getMethod` cached via `lazy`.
- **`touch()` called before `rcloneInitialize()`** to init gomobile runtime.
- **`createDir` before subdirectory uploads** — `operations/copyfile` does NOT auto-create parent directories.

### Remote Layout

```
<remote>:photoz-backup/
├── repo-config.json              # marker (plaintext JSON)
├── registry.json.crypt           # dedup registry (GCM encrypted with VMK)
├── originals/<uuid>.crypt        # encrypted photo/video
├── thumbnails/pack-*.pack        # thumbnail packs (≤5 MB each)
├── video-previews/<uuid>.crypt.vp
└── vault-protection/
    ├── recovery-phrase.json.crypt  # Layer 1: VMK wrapped with phrase KEK
    └── wrapped-phrase.json.crypt   # Layer 2: phrase wrapped with password KEK
```

### Config Management

`RcloneConfigManager` caches the parsed config after `import()` to avoid re-reading + re-parsing the file on every call. Cache is invalidated by `clear()`.

### Anti-Dedup

- `softDelete()` checks `PhotoDao.countLiveByContentHash()` before tombstoning — if >1 live photo references the hash, skip tombstone (anti-dedup).
- `gcOriginals()` double-checks before deleting remote file — un-tombstones if a live reference is found (race safety).

---

## Auth & Login Flow

> **Read `docs/01-architecture.md` + `docs/05-sync-workflow.md` for full details.**

### Fresh Install (Register)

```
InitialFragment → systemFirstStart=true → FIRST_START
→ OnBoardingFragment → systemFirstStart=false
→ RepoSetupFragment → import rclone.conf → pick remote → detectRepo
  → NOT_INITIALIZED → registerRepo → createDir + upload marker → Completed
→ SetupFragment → create password → create VMK → create recovery phrase → upload escrows
→ Gallery
```

### Fresh Install (Login — Password + Phrase)

```
InitialFragment → systemFirstStart=true → FIRST_START
→ OnBoardingFragment → RepoSetupFragment → detectRepo → LOGGED_IN
→ loginRepo → download Layer 1 + Layer 2 escrows → EscrowType.PASSWORD_PLUS_PHRASE
→ restoreThumbnailsAfterLogin → NeedsPasswordEntry
→ submitPassword → unwrapPhrase(password) → vaultService.unlock(RecoveryPhrase(phrase))
→ createPasswordProtectionFromSession(password, session) → persist Password row
→ Gallery
```

### Fresh Install (Login — Phrase Only)

```
... → loginRepo → Layer 2 not available → EscrowType.PHRASE_ONLY
→ NeedsPhraseEntry → RecoveryPhraseRestoreScreen → enter phrase
→ vaultService.unlock(RecoveryPhrase(phrase)) → VMK in memory
→ config.pendingPasswordSetup = true
→ Gallery (VMK in memory, but NO Password row yet)
→ User must create password via SetupFragment
```

### Anti-Data-Loss: pendingPasswordSetup + Process Death

If user closes app after PHRASE_ONLY login (before creating password):
- `InitialViewModel`: `canUnlock()=false` + `pendingPasswordSetup=true` → set `systemFirstStart=true` → re-login via RepoSetup
- `SetupViewModel`: `pendingPasswordSetup=true` + `session=null` (process death) → do NOT create new VMK → redirect to re-login

### Returning User

```
InitialFragment → systemFirstStart=false → canUnlock()=true → LOCKED
→ UnlockFragment → enter password → vaultService.unlock(Password)
→ Gallery
```

### Gallery Gate

`NavigateToGallery` checks `config.repoConfirmed` — if false, redirects to `RepoSetupFragment` instead of gallery.

---

## Key Libraries

Check `app/build.gradle.kts` for the current library list. Key areas to know:

- **Jetpack Compose + Material3** — all new UI.
- **Hilt / Dagger 2.60** — DI throughout.
- **Room 2.8.4 + SQLCipher 4.9.0** — SQLite ORM with at-rest encryption. Schema v17.
- **Bouncy Castle 1.84** — Argon2id KDF.
- **Navigation Component** — single-activity fragment navigation, with Safe Args.
- **Coil 2.7.0** — image loading; custom `EncryptedImageFetcher` in `transcoding/` that decrypts on-the-fly. Memory cache ENABLED, disk cache DISABLED.
- **ExoPlayer / Media3 1.10.1** — video playback. Uses `ChunkedGcmRandomAccessDataSource` for v0x04 videos (random access seek).
- **rclone gomobile JNI** — `app/libs/librclone.aar` (v1.68.2, 16KB aligned).
- **WorkManager 2.11.2** — background sync via `PhotoSyncWorker` (foreground service).
- **jBCrypt** — legacy password hashing, used for migration only.
- **Gson** — backup JSON serialization.
- **Timber** — logging. Use exclusively; never use `android.util.Log` directly.
- **kotlinx-coroutines** — async throughout.
- **Biometric** — fingerprint/face unlock.
- **TelemetryDeck** — analytics, play flavor only.

---

## Database

Two Room databases:

| Database | File | Encryption | Schema | Purpose |
|----------|------|------------|--------|---------|
| `PhotoZDatabase` | `photok.db` | SQLCipher (Keystore key) | v17 | Photos, albums, sort, hash_registry |
| `BootstrapDatabase` | `photok_meta.db` | Plaintext | v2 | `vault_protection` table (readable before unlock) |

The `PhotoZDatabase` class in `model/database/` is the source of truth for all entities and the current schema version.

All schema changes must use **Room auto-migrations** declared in `@Database(autoMigrations = [...])`. Always add a new `AutoMigration(from = N, to = N+1)` entry and bump `DATABASE_VERSION` when changing the schema. Manual migrations (`MIGRATION_15_16`, `MIGRATION_16_17`) are used only when auto-migration can't handle the change (e.g. entity removal, orphan table cleanup).

BootstrapDatabase `MIGRATION_1_2` renames column `wrappedVMK` → `wrapped_vmk` (snake_case consistency).

---

## Dependency Injection

Hilt is used throughout. Each feature that needs DI has a `di/` sub-package containing a Hilt module — look there for the current bindings. The top-level `di/AppModule.kt` provides app-wide singletons (database, DAOs, config, Gson, etc.).

Use `@Singleton` for expensive objects. ViewModels are `@HiltViewModel`.

**Note:** `RcloneConfigManager` uses `dagger.Lazy<RcloneController>` to break a dependency cycle (RcloneController injects RcloneConfigManager).

---

## Translations & Strings

The supported locales are the `values-*/` directories under `app/src/main/res/`. Check those directories for the current list — do not rely on any enumeration in this file.

### Rule: Always add strings to every locale file

When you add a new string to `values/strings.xml`, you **must** also add a copy of it to every other `values-*/strings.xml` file. Use the English text as the placeholder and annotate with an XML comment `<!-- TODO -->` on the same line.

---

## Testing

Unit tests live in `app/src/test/` and use JUnit 4, Robolectric (Android runtime emulation), MockK, and `kotlinx-coroutines-test`.

Current test coverage: 67+ tests across 7 test classes (0 failures):
- `BackupMappersTest` — Photo metadata round-trip (13 tests)
- `KeyGenArgon2idTest` — Argon2id KDF correctness (9 tests)
- `ChunkedGcmStreamTest` — chunked GCM round-trip + tamper detection (10 tests)
- `Argon2idIvEncodingTest` — IV encoding consistency (7 tests)
- `SqlCipherMigrationHelperTest` — detection logic (5 tests)
- `DedupTest` — vault_id scoped lookup (10 tests)
- `RcloneJsonParsingTest` — JSON parsing + operation formats (27 tests)

When writing tests:
- Prefer integration tests for crypto flows.
- Mock only at the domain/data boundary — avoid mocking internal crypto primitives.
- Use `runTest` for anything involving coroutines.
- Use `@RunWith(RobolectricTestRunner::class)` for tests that use Android SDK classes (`JSONObject`, etc.).

---

## Product Flavors

| Flavor | `BuildConfig.PLAY` | Notes |
|--------|--------------------|-------|
| `play` | `true` | Google Play release; includes TelemetryDeck |
| `foss` | `false` | F-Droid / sideload release; no telemetry |

Flavor-specific code goes in `src/play/` or `src/foss/`. Use `playImplementation` / `fossImplementation` in `build.gradle.kts` for flavor-specific dependencies.

---

## Build & CI

- **Gradle:** 9.6.1 (via wrapper)
- **AGP:** 9.1.0 (builtInKotlin=false, kapt for DataBinding)
- **Kotlin:** 2.4.0
- **compileSdk:** 37 (suppress warning via `android.suppressUnsupportedCompileSdk=37.0`)
- **minSdk:** 35, **targetSdk:** 36
- **ABI:** arm64-v8a only (rclone gomobile AAR)
- **R8 full mode:** enabled (`android.enableR8.fullMode=true`)
- **useLegacyPackaging:** false (gomobile JNI uses dlopen, not exec)

### Quality Gates (CI)

CI runs 4 parallel jobs on every push/PR:
1. **Code Quality** — `detekt` + `ktlintCheck` (both blocking)
2. **Unit Tests** — `testFossDebugUnitTest`
3. **Lint** — `lintFossDebug`
4. **Build** — `assembleFossDebug` (arm64 only)

Fix violations with `./gradlew ktlintFormat` (auto-fix) before committing.

### AAR Rebuild

`scripts/build-rclone-gomobile.sh` builds rclone v1.68.2 as gomobile AAR with:
- `CGO_ENABLED=1` (Android DNS resolution)
- `CGO_LDFLAGS="-Wl,-z,max-page-size=16384"` (16KB alignment for Android 15+)
- `-target=android/arm64,android/arm` (both ABIs)

CI workflow: `.github/workflows/build-rclone-gomobile.yml` (manual trigger or tag push).

---

## Rules & Conventions

### Git

- **Do not commit on your own.** Stage and propose changes, but never run `git commit` or `git push`.
- If you are on a feature branch, run `git diff main` (or `git diff $(git merge-base HEAD main)`) early to understand what has already changed in this feature.

### Code Style

- **Compose first.** Prefer writing new UI in Jetpack Compose with Material3. Only touch legacy DataBinding code when modifying existing screens that still use it.
- **Simple over complex.** Prefer a straightforward implementation with a few lines of logic over elaborate abstractions, extra layers, or design patterns beyond what the codebase already uses.
- **Stick to the architecture.** New features must follow the `data / domain / di / ui` split. Domain code must not depend on Android SDK classes. UI code must not reach into `data` directly.
- **Compose decomposition.** Break screens into small, focused composables. Follow the existing pattern: `XyzScreen` → `XyzContent` / `XyzPlaceholder` → leaf components.
- **Comments.** Only comment code that genuinely needs clarification. Do not add redundant comments that restate what the code already says.
- **License header.** All new `.kt` files must include the Apache 2.0 license header (copy from any existing file).

### Naming

| Artifact | Convention | Example |
|----------|-----------|---------|
| ViewModel | `<Feature>ViewModel` | `GalleryViewModel` |
| UiState | `<Feature>UiState` (sealed interface) | `GalleryUiState` |
| UiEvent | `<Feature>UiEvent` (sealed interface) | `GalleryUiEvent` |
| Fragment | `<Feature>Fragment` | `GalleryFragment` |
| Composable screen | `<Feature>Screen` | `GalleryScreen` |
| Sub-composable | `<Feature>Content`, `<Feature>Placeholder`, etc. | `GalleryContent` |
| Navigator | `<Feature>Navigator` | `GalleryNavigator` |
| Repository interface | `<Feature>Repository` | `AlbumRepository` |
| Repository impl | `<Feature>RepositoryImpl` | `AlbumRepositoryImpl` |
| Hilt module | `<Feature>Module` | `AlbumsModule` |
| Room table entity | `<Feature>Table` | `AlbumTable` |
| Crypto engine | `HybridCryptoEngine` (not `CbcCryptoEngine`) | — |

### Logging

Use **Timber** exclusively. Never use `android.util.Log` directly.

```kotlin
Timber.d("Debug info")
Timber.e("Error: $e")
Timber.w("Warning")
```

### Coroutines

- All database and I/O work runs on `Dispatchers.IO` inside `withContext` or repository/use-case `suspend` functions.
- ViewModels use `viewModelScope`. App-level coroutines use the `CoroutineScope(Dispatchers.Default)` provided via Hilt.
- Collect flows in the Fragment with `launchLifecycleAwareJob` (from `other/extensions`), never in a raw `lifecycleScope.launch` without `repeatOnLifecycle`.

### Result Handling

Prefer `Result<T>` and `.onSuccess { } .onFailure { }` for operations that can fail, consistent with `VaultService.unlock()`.

### rclone RC API

- **Always split path** at last `/` in `uploadFile`/`downloadFile` — `dstFs` must include the directory, `dstRemote` must be filename only.
- **Always `createDir`** before uploading to a subdirectory — `operations/copyfile` does NOT auto-create parent directories.
- **Use `hasRpcError()`** (JSONObject-based) for error detection, not substring matching.
- **Call `touch()` before `rcloneInitialize()`** to init gomobile runtime.
- **Check `getStatus()`** in addition to `getOutput()` — some rclone methods return non-200 without an "error" field.
