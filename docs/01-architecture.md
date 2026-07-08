# 01 — Arsitektur Aplikasi

> **File referensi**: `AGENTS.md` (root), `app/src/main/java/onlasdan/gallery/`

---

## Overview

PhotoZ mengikuti **feature-first layered architecture** dengan single Activity + Fragment-based navigation.

```
┌─────────────────────────────────────────────────────────┐
│                    MainActivity                          │
│  (single Activity, hosts all Fragments via NavHost)     │
└──────────────────────┬──────────────────────────────────┘
                       │
        ┌──────────────┴──────────────┐
        │      Jetpack Navigation     │
        │    (main_nav_graph.xml)     │
        └──────────────┬──────────────┘
                       │
    ┌──────────────────┼──────────────────┐
    │                  │                  │
┌───▼───┐        ┌────▼────┐       ┌────▼────┐
│Gallery│        │ Albums  │       │Settings │
│Fragment│       │Fragment │       │Fragment │
└───┬───┘        └────┬────┘       └────┬────┘
    │                 │                  │
    └─────────────────┼──────────────────┘
                      │
              ┌───────▼───────┐
              │   ViewModel   │
              │ (StateFlow)   │
              └───────┬───────┘
                      │
              ┌───────▼───────┐
              │  Repository   │
              │  (domain)     │
              └───────┬───────┘
                      │
         ┌────────────┼────────────┐
         │            │            │
    ┌────▼───┐  ┌────▼────┐  ┌───▼────┐
    │  Room  │  │  Hilt   │  │ rclone │
    │  DAO   │  │  DI     │  │  JNI   │
    └────────┘  └─────────┘  └────────┘
```

---

## Package Layout

Root package: `onlasdan.gallery` (sebelumnya `dev.leonlatsch.photok` — renamed di fork)

```
app/src/main/java/onlasdan/gallery/
├── BaseApplication.kt          # @HiltAndroidApp, WorkManager config
├── DialLauncher.kt             # Stealth dialer receiver
├── ApplicationState.kt         # Global app state enum
├── appstart/                   # Initial load screen
├── backup/                     # Backup/restore (V1-V5)
│   ├── data/                   # BackupMetaData, BackupMappers
│   ├── domain/                 # RestoreBackupV1-V5, BackupStrategy
│   └── ui/                     # BackupViewModel, RestoreBackupDialog
├── encryption/                 # Crypto + vault protection
│   ├── data/                   # SqlCipherKeyProvider, BootstrapDatabase
│   ├── domain/                 # VaultService, KeyGen, CryptoEngine
│   │   ├── crypto/             # CbcCryptoEngine, ChunkedGcm*, KeyGen
│   │   ├── handlers/           # Password/Biometric/RecoveryPhrase handlers
│   │   └── models/             # Algorithm, Kdf, VaultProtectionParams
│   ├── migration/              # LegacyEncryptionMigrator
│   └── ui/                     # Unlock, RecoveryPhrase screens
├── gallery/                    # Main gallery + albums
│   ├── albums/                 # Album management
│   ├── components/             # PhotoTile, AlbumPicker, ImportMenu
│   ├── importing/              # Import flow
│   └── ui/                     # GalleryFragment, GalleryViewModel
├── imageviewer/                # Full-screen photo/video viewer
├── io/                         # VaultFileStorage, IO utilities
├── model/                      # Data layer
│   ├── database/               # Room entities, DAOs, PhotoZDatabase
│   │   ├── dao/                # PhotoDao, AlbumDao
│   │   ├── entity/             # Photo, AlbumTable
│   │   └── ref/                # AlbumPhotoCrossRef
│   ├── io/                     # FastStartUseCase, ExifExtractor
│   └── repositories/           # PhotoRepository
├── notifications/              # Notification channels
├── onboarding/                 # First-run onboarding
├── recoverymenu/               # Recovery menu (forgot password)
├── reposetup/                  # rclone repo setup UI
├── security/                   # PanicWipe, SelfDestruct, FakeCrash
├── settings/                   # Settings screens
│   ├── data/                   # Config (SharedPreferences wrapper)
│   ├── domain/                 # PreferenceScreenConfig
│   └── ui/                     # SettingsFragment, Compose screens
├── sort/                       # Sort config
├── sync/                       # Cloud sync (rclone)
│   ├── debug/                  # SyncLogger, CrashLogger
│   ├── di/                     # SyncModule (Hilt)
│   ├── domain/                 # SyncState, SyncConfig, Dedup
│   ├── rclone/                 # RcloneController, RepoManager
│   └── work/                   # PhotoSyncWorker, HashRegistry
├── telemetry/                  # TelemetryDeck (play flavor only)
├── transcoding/                # Encrypted image/video loading
│   ├── compose/                # EncryptedImagePainter
│   └── data/                   # AesCbcRandomAccessDataSource, ChunkedGcm*
├── trash/                      # Recycle bin
├── uicomponnets/               # Shared UI (legacy DataBinding base)
└── unlock/                     # Unlock screen
```

---

## UI Patterns

### Compose First (new screens)

Setiap screen baru harus pakai Jetpack Compose (Material3). Pattern per screen:

| File | Role |
|------|------|
| `XyzFragment.kt` | `@AndroidEntryPoint Fragment` — creates `ComposeView`, provides `CompositionLocal`s |
| `XyzViewModel.kt` | `@HiltViewModel` — exposes `val uiState: StateFlow<XyzUiState>` |
| `XyzUiState.kt` | `sealed interface XyzUiState` — `Empty`, `Loading`, `Content(...)` |
| `XyzUiEvent.kt` | `sealed interface XyzUiEvent` — user actions |
| `XyzScreen.kt` | `@Composable` — collects state, branches on sealed state |

**Contoh kanonik**: `GalleryFragment` / `GalleryViewModel` / `GalleryUiState` / `GalleryScreen`

### Legacy DataBinding (existing screens)

Beberapa screen lama masih pakai XML DataBinding via `Bindable*` base classes (di `uicomponnets/`):
- `unlock/` — UnlockFragment
- `settings/` — beberapa sub-screen

**Jangan tambah screen DataBinding baru** — selalu pakai Compose.

### Navigation

- Single Activity (`MainActivity`) + Jetpack Navigation Component
- `main_nav_graph.xml` mendefinisikan semua destinations
- Bottom-tab nav: Gallery, Albums, Settings
- Fragment-level nav via typed `Navigator` classes (Hilt-injected)
- Safe Args untuk type-safe navigation

---

## Dependency Injection (Hilt)

### Application

```kotlin
@HiltAndroidApp
class BaseApplication : Application(), Configuration.Provider {
    // WorkManager custom config for @HiltWorker injection
}
```

### Module Pattern

Setiap feature yang butuh DI punya `di/` sub-package:

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object FeatureModule {
    @Provides
    @Singleton
    fun provideFeatureRepo(dao: FeatureDao): FeatureRepository = FeatureRepositoryImpl(dao)
}
```

### Key Modules

| Module | Scope | Provides |
|--------|-------|----------|
| `AppModule` | Singleton | BootstrapDatabase, PhotoZDatabase, Config, Gson, CoroutineScope |
| `EncryptionModule` | Singleton | VaultService, KeyGen, CryptoEngine, handlers |
| `SyncModule` | Singleton | RcloneController, RcloneConfigManager, HashRegistry, PhotoSyncWorker deps |

### @HiltWorker

`PhotoSyncWorker` pakai `@HiltWorker` + `@AssistedInject` untuk WorkManager injection. Butuh custom `Configuration.Provider` di `BaseApplication` (HiltWorkerFactory).

---

## State Management

### Simple MVI (flat)

Tidak ada MVI framework — plain Kotlin + StateFlow:

```kotlin
@HiltViewModel
class GalleryViewModel @Inject constructor(
    private val photoRepository: PhotoRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow<GalleryUiState>(GalleryUiState.Loading)
    val uiState: StateFlow<GalleryUiState> = _uiState.asStateFlow()

    fun handleUiEvent(event: GalleryUiEvent) {
        when (event) {
            is GalleryUiEvent.DeletePhoto -> deletePhoto(event.uuid)
            // ...
        }
    }
}
```

### Navigation Events

Navigation events yang harus keluar dari ViewModel dikirim via `Channel<XyzNavigationEvent>` dan di-collect di Fragment.

---

## Coroutines

- **Database + I/O**: `Dispatchers.IO` inside `withContext` atau `suspend` functions
- **ViewModel**: `viewModelScope.launch`
- **App-level**: `CoroutineScope(Dispatchers.Default)` via Hilt
- **Collect flows di Fragment**: `launchLifecycleAwareJob` (dari `other/extensions`), atau `repeatOnLifecycle`

---

## Build Variants

### Product Flavors

| Flavor | `BuildConfig.PLAY` | Notes |
|--------|--------------------|-------|
| `play` | `true` | Google Play — TelemetryDeck, In-App Review |
| `foss` | `false` | F-Droid / sideload — no telemetry, no Play Review |

### Build Types

| Type | Minify | Shrink Resources | Notes |
|------|--------|------------------|-------|
| `debug` | false | false | Debuggable, LeakCanary, Timber DebugTree |
| `release` | true (R8 full mode) | true | ProGuard rules, signed |

---

## Key Conventions (AGENTS.md)

1. **Timber only** — never `android.util.Log` directly
2. **Compose first** — no new DataBinding screens
3. **License header** — Apache 2.0 di semua `.kt` files
4. **Strings** — tambah ke `values/strings.xml` + semua `values-*/strings.xml` dengan `<!-- TODO -->` untuk translations
5. **Room migrations** — auto-migration via `@Database(autoMigrations = [...])`; manual migration hanya jika auto tidak bisa
6. **Crypto** — never instantiate `CbcCryptoEngine` directly; pakai `CryptoEngine` interface
7. **File I/O** — semua via `VaultFileStorage`
8. **Git** — stage + propose, never `git commit` / `git push` tanpa explicit permission
