# 08 — Build & Deploy

> **File referensi**: `app/build.gradle.kts`, `gradle.properties`, `.github/workflows/`

---

## Prerequisites

| Tool | Version | Purpose |
|------|---------|---------|
| JDK | 21+ | Java compiler |
| Android SDK | Platform 37 + Build-Tools 37.0.0 | Android API |
| Gradle | 9.6.1 (via wrapper) | Build system |
| AGP | 9.1.0 | Android Gradle Plugin |
| Kotlin | 2.4.0 | Language |
| Go | 1.22+ | Build rclone gomobile AAR (optional) |
| Android NDK | r27c | Build rclone gomobile AAR (optional) |

---

## Build Variants

### Product Flavors

| Flavor | `BuildConfig.PLAY` | Notes |
|--------|--------------------|-------|
| `play` | `true` | Google Play — TelemetryDeck, In-App Review |
| `foss` | `false` | F-Droid / sideload — no telemetry |

### Build Types

| Type | Minify | Shrink Resources | Debuggable | Notes |
|------|--------|------------------|------------|-------|
| `debug` | false | false | true | LeakCanary, Timber DebugTree |
| `release` | true (R8 full mode) | true | false | ProGuard rules, signed |

---

## Build Commands

### Debug Build

```bash
# FOSS flavor (no telemetry)
./gradlew :app:assembleFossDebug

# Play flavor (with TelemetryDeck)
./gradlew :app:assemblePlayDebug
```

Output: `app/build/outputs/apk/foss/debug/photok-<version>-foss-debug.apk`

### Release Build

```bash
# FOSS release
./gradlew :app:assembleFossRelease

# Play release
./gradlew :app:assemblePlayRelease
```

Output: `app/build/outputs/apk/foss/release/photok-<version>.apk`

### Run Tests

```bash
# Unit tests
./gradlew :app:testFossDebugUnitTest

# Compile only (faster feedback)
./gradlew :app:compileFossDebugKotlin
./gradlew :app:compileFossDebugUnitTestKotlin
```

---

## Build Configuration

### gradle.properties

```properties
# AGP 9.1.0 with built-in Kotlin DISABLED (kapt needed for DataBinding)
android.builtInKotlin=false
android.newDsl=false

# R8 full mode for aggressive tree-shaking
android.enableR8.fullMode=true

# Jetifier disabled (project fully AndroidX)
android.enableJetifier=false

# KSP2 architecture (2x faster than kapt)
# kapt kept for DataBinding only (mixed mode)
```

### app/build.gradle.kts

```kotlin
android {
    compileSdk = 37
    defaultConfig {
        applicationId = "onlasdan.gallery"
        minSdk = 35
        targetSdk = 36

        ndk {
            abiFilters += "arm64-v8a"  // rclone gomobile: arm64 only
        }
    }

    packaging {
        jniLibs {
            // F-MAIN-002: gomobile JNI uses dlopen, not exec —
            // useLegacyPackaging=false (default) works fine
            useLegacyPackaging = false
        }
    }
}
```

### ProGuard / R8

`app/proguard-rules.pro`:
- `-dontobfuscate` — keep readable stack traces
- `-keep class onlasdan.gallery.**` — keep all app classes (Gson, Room, Hilt)
- `-keep class gomobile.**` — keep rclone JNI bindings
- `-keep class net.zetetic.database.sqlcipher.**` — keep SQLCipher JNI

---

## Dependencies

Key dependencies (`app/build.gradle.kts`):

```kotlin
// Room
implementation("androidx.room:room-runtime:2.8.4")
ksp("androidx.room:room-compiler:2.8.4")

// SQLCipher (TODO #6)
implementation("net.zetetic:sqlcipher-android:4.9.0")

// Bouncy Castle (Argon2id, TODO #3)
implementation("org.bouncycastle:bcprov-jdk18on:1.84")

// Hilt
implementation("com.google.dagger:hilt-android:2.60")
ksp("com.google.dagger:hilt-android-compiler:2.60")

// WorkManager
implementation("androidx.work:work-runtime-ktx:2.11.2")

// rclone gomobile AAR
implementation(fileTree("libs").matching { include("*.jar", "*.aar") })

// Compose
implementation(platform("androidx.compose:compose-bom:2026.06.01"))
implementation("androidx.compose.material3:material3:1.4.0")

// Media3 / ExoPlayer
implementation("androidx.media3:media3-exoplayer:1.10.1")

// Coil (image loading)
implementation("io.coil-kt:coil-compose:2.7.0")
```

---

## rclone AAR Build

### Manual Build

```bash
# Prerequisites: Go 1.22+, Android NDK r27c, gomobile installed
go install golang.org/x/mobile/cmd/gomobile@latest

# Build AAR
bash scripts/build-rclone-gomobile.sh
```

Output: `app/libs/librclone.aar` (37 MB)

### CI Build

`.github/workflows/build-rclone-gomobile.yml`:
- Trigger: manual (`workflow_dispatch`) atau tag push `rclone-v*`
- Builds arm64-v8a + armeabi-v7a
- 16KB page alignment verified
- Commits AAR ke `app/libs/` (optional)

---

## Signing

### Debug

```kotlin
// app/build.gradle.kts
getByName("debug") {
    signingConfig = signingConfigs.create("debugCi") {
        storeFile = rootProject.file("debug.keystore")
        storePassword = "android"
        keyAlias = "debug"
        keyPassword = "android"
    }
}
```

`debug.keystore` committed ke repo (debug keys are not sensitive).

### Release

Release signing via `.github/workflows/build-signed-release.yml`:
- Keystore dari GitHub Secrets
- Upload ke GitHub Releases

---

## CI/CD

### GitHub Actions Workflows

| Workflow | File | Trigger | Purpose |
|----------|------|---------|---------|
| Android CI | `android.yml` | push/PR | Build + test (fossDebug) |
| Build Rclone Gomobile | `build-rclone-gomobile.yml` | manual/tag | Build AAR |
| Build Signed Release | `build-signed-release.yml` | manual | Signed APK |
| Create Release | `create-rc.yml` | manual | GitHub release |
| Translation Badges | `translation-badges.yml` | push | Update translation % badges |

### CI Test Configuration

```yaml
# android.yml (simplified)
- name: Run tests
  run: ./gradlew :app:testFossDebugUnitTest
```

**Note**: `playDebug` tests skipped — `VaultLifecycleTest` flaky (Sprint 8 fix).

---

## Local Build Setup

### 1. Install Android SDK

```bash
# Download cmdline-tools
wget https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip cmdline-tools-linux-*.zip -d $HOME/android-sdk/cmdline-tools
mv $HOME/android-sdk/cmdline-tools/cmdline-tools $HOME/android-sdk/cmdline-tools/latest

# Accept licenses + install platforms
export ANDROID_HOME=$HOME/android-sdk
yes | $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --licenses
$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager \
  "platforms;android-37.0" \
  "build-tools;37.0.0" \
  "platform-tools"
```

### 2. Create local.properties

```bash
echo "sdk.dir=$HOME/android-sdk" > local.properties
```

### 3. Build

```bash
export JAVA_HOME=/path/to/jdk-21
./gradlew :app:assembleFossDebug
```

---

## Release Process

### Pre-release Checklist

- [ ] Bump `appVersionCode` + `appVersionName` di `gradle.properties`
- [ ] Update `fastlane/metadata/android/en-US/` descriptions if needed
- [ ] Run full test suite: `./gradlew :app:testFossDebugUnitTest`
- [ ] Build release APK: `./gradlew :app:assembleFossRelease`
- [ ] Smoke test pada device
- [ ] Update `ROADMAP.md` / `TODO_SYNC.md` status

### Create Release

1. Tag push: `git tag v<x.y.z> && git push origin v<x.y.z>`
2. Trigger `build-signed-release.yml` workflow
3. Verify signed APK
4. Create GitHub Release dengan release notes
5. Upload APK ke GitHub Release

---

## Common Build Issues

### "Failed to find target with hash string 'android-37'"

**Penyebab**: AGP 9.1.0 tidak support compileSdk 37 (max 36.1).
**Fix**: Install `platforms;android-37.0` + ensure AGP version compatible, atau temporary set `compileSdk = 36`.

### "Could not read Password for github.com"

**Penyebab**: Token expired atau tidak punya write scope.
**Fix**: Buat PAT baru dengan `repo` scope (classic) atau `Contents: Read and write` (fine-grained).

### "Unresolved reference: gomobile.Gomobile"

**Penyebab**: `librclone.aar` tidak ada di `app/libs/`.
**Fix**: Run `bash scripts/build-rclone-gomobile.sh` atau download dari CI artifacts.

### "kaptGenerateStubsFossDebugKotlin FAILED"

**Penyebab**: JDK tidak provide JAVA_COMPILER (hanya JRE).
**Fix**: Install JDK (bukan JRE): `apt install openjdk-21-jdk-headless`.

### Hilt dependency cycle

**Penyebab**: A injects B, B injects A.
**Fix**: Gunakan `dagger.Lazy<A>` di salah satu constructor (defers instantiation ke runtime).
