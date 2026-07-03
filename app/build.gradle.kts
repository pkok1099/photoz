import com.android.sdklib.AndroidVersion.VersionCodes

plugins {
    id("com.android.application")
    id("com.jaredsburrows.license")
    kotlin("android")
    kotlin("kapt")
    kotlin("plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21"
}

val isReleaseBuildInvocation: Boolean = gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }

val appVersionName: String by project
val appVersionCode: String by project

val telemetryDeckAppId: String? by project

apply(plugin = "androidx.navigation.safeargs.kotlin")
apply(plugin = "dagger.hilt.android.plugin")

android {
    compileSdk = VersionCodes.BAKLAVA

    defaultConfig {
        applicationId = "onlasdan.gallery"
        minSdk = 35
        targetSdk = 36

        versionCode = appVersionCode.toInt()
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        javaCompileOptions {
            annotationProcessorOptions {
                arguments += "room.incremental" to "true"
                arguments += "room.schemaLocation" to "$projectDir/schemas"
            }
        }

        base {
            archivesName = "photok-$versionName"
        }

        buildConfigField(
            "String",
            "TELEMETRY_DECK_APP_ID",
            "\"${telemetryDeckAppId.orEmpty()}\""
        )
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("play") {
            dimension = "distribution"
            buildConfigField("Boolean", "PLAY", "true")

            if (!isReleaseBuildInvocation) {
                versionNameSuffix = "-play-debug"
            }
        }

        create("foss") {
            dimension = "distribution"
            buildConfigField("Boolean", "PLAY", "false")

            if (!isReleaseBuildInvocation) {
                versionNameSuffix = "-foss-debug"
            }
        }
    }

    buildTypes {
        getByName("debug") {
            isDebuggable = true
        }

        getByName("release") {
            isMinifyEnabled = true

            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        dataBinding = true
        compose = true
        buildConfig = true
    }

    compileOptions {
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    lint {
        lintConfig = file("$rootDir/gradle/lint.xml")
        baseline = file("$rootDir/gradle/lint-baseline.xml")
    }
    namespace = "onlasdan.gallery"

    // ─── Native library packaging — fix for "rclone binary not found" on Android 14+ ─────
    //
    // The app bundles a real Go-built CLI executable (rclone v1.68.2, statically linked,
    // ELF type EXEC) under `jniLibs/<abi>/librclone.so` and runs it via `ProcessBuilder`.
    //
    // AGP's default since 4.2 (and the only behavior with minSdk >= 23) is
    // `useLegacyPackaging = null` → `extractNativeLibs = false`: `.so` files are stored
    // UNCOMPRESSED + page-aligned inside the APK and `mmap`'d directly from the APK zip
    // by `dlopen` at runtime. They are NEVER extracted to `nativeLibraryDir` on disk.
    //
    // This is fine for genuine shared libraries loaded via `System.loadLibrary()`, but
    // fatal for our use case: there is no on-disk file for `ProcessBuilder.start()` to
    // `execve()`. Symptom on Android 14/15/16:
    //   `nativeLibraryDir=.../lib/arm64`
    //   `nativeSrcLib(librclone.so) exists=false size=0 canRead=false`
    //
    // Setting `useLegacyPackaging = true` forces the OS to extract `.so` files to
    // `nativeLibraryDir` as real files at install time — restoring the pre-AGP-4.2
    // behavior that `ProcessBuilder` needs. Termux and PojavLauncher rely on this exact
    // setting for the same reason (bundling real executables in jniLibs).
    //
    // Source: https://developer.android.com/guide/practices/page-sizes
    // Source: https://developer.android.com/reference/tools/gradle-api/8.0/com/android/build/api/dsl/JniLibsPackagingOptions
    // Source: https://github.com/termux/termux-packages/wiki/For-maintainers
    //
    // NOTE: this is the AGP DSL setting; the manifest `android:extractNativeLibs` attribute
    // is IGNORED by AGP 9.0+ and replaced by this DSL flag. Do not also set the manifest
    // attribute — they would conflict.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

licenseReport {
    generateCsvReport = false
    generateHtmlReport = true
    generateJsonReport = false
    generateTextReport = false

    copyHtmlReportToAssets = true

    useVariantSpecificAssetDirs = true
}

fun DependencyHandler.playImplementation(dependencyNotation: Any) {
    add("playImplementation", dependencyNotation)
}

fun DependencyHandler.fossImplementation(dependencyNotation: Any) {
    add("fossImplementation", dependencyNotation)
}

dependencies {
    // Architectural Components
    // NOTE: Lifecycle 2.11.0 requires compileSdk 37 + AGP 9.1.0 — reverted to 2.10.0
    // (AGP 9 migration is out of scope for Batch 3; will be done as a separate effort).
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-extensions:2.2.0")

    // Room
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-paging:2.8.4")
    kapt("androidx.room:room-compiler:2.8.4")

    // Kotlin Extensions and Coroutines support for Room
    implementation("androidx.room:room-ktx:2.8.4")

    // ViewPager2
    implementation("androidx.viewpager2:viewpager2:1.1.0")

    // Coroutines
    // Bumped 1.10.2 → 1.11.0 (Batch 3 — safe dep bumps from dependabot branch).
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // Coroutine Lifecycle Scopes
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")

    // Navigation Components
    // Bumped 2.9.7 → 2.9.8 (Batch 3 — safe dep bumps from dependabot branch).
    implementation("androidx.navigation:navigation-fragment-ktx:2.9.8")
    implementation("androidx.navigation:navigation-ui-ktx:2.9.8")

    // Timber Logging
    implementation("com.jakewharton.timber:timber:5.0.1")

    // Dagger Core
    // NOTE: Dagger/Hilt 2.60 fails to load dagger.spi.internal.shaded...JavacBasicAnnotationProcessor
    // with Room 2.8.4 — reverted to 2.57.2 (Batch 3 dep bump reverted due to build failure).
    val daggerVersion = "2.57.2"
    implementation("com.google.dagger:dagger:$daggerVersion")
    kapt("com.google.dagger:dagger-compiler:$daggerVersion")

    // Dagger - Hilt
    implementation("com.google.dagger:hilt-android:$daggerVersion")
    kapt("com.google.dagger:hilt-android-compiler:$daggerVersion")
    implementation("androidx.hilt:hilt-navigation-compose:1.3.0")

    kapt("androidx.hilt:hilt-compiler:1.3.0")

    // Hilt-Work — allows @HiltWorker injection into WorkManager Workers.
    // Required by onlasdan.gallery.sync.work.PhotoSyncWorker.
    implementation("androidx.hilt:hilt-work:1.3.0")

    // WorkManager — required by onlasdan.gallery.sync.work.PhotoSyncWorker for cloud sync.
    // Bumped 2.10.0 → 2.11.2 (Batch 3 — safe dep bumps from dependabot branch).
    implementation("androidx.work:work-runtime-ktx:2.11.2")

    // kotlinx-serialization — used by RcloneController for JSON (de)serialization of rclone rc API.
    // Bumped 1.7.3 → 1.11.0 (Batch 3 — safe dep bumps from dependabot branch).
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    // Activity KTX for viewModels()
    implementation("androidx.activity:activity-ktx:1.12.4")

    // Compose
    implementation(platform("androidx.compose:compose-bom:2026.02.00"))
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose")

    // Compose Material Icons (extended) — @since PR2 sync
    // Required for Icons.Filled.Cloud / CloudDone / CloudUpload / Warning used in the
    // per-thumbnail sync-state badge and the gallery top-bar sync status indicator.
    // The material-icons-core bundle (pulled in transitively by material3) only ships
    // ~30 icons; Cloud*, CloudDone, CloudUpload live in the extended artifact.
    // material-icons-extended removed — it adds ~30MB to the APK. The 4 cloud
    // icons needed for sync-state badges are provided as XML vector drawables
    // in res/drawable/ instead (ic_cloud_done, ic_cloud_upload, ic_cloud_off,
    // ic_cloud). The Warning icon is already in material-icons-core (bundled
    // with material3).

    // ZXing - QR code generation and scanning
    implementation("com.google.zxing:core:3.5.3")

    // CameraX - camera access for QR scanning
    // Bumped 1.4.2 → 1.6.1 for Android 16 16KB-page-size compatibility.
    // CameraX 1.5+ ships 16KB-aligned .so files (libsurface_util_jni.so,
    // libimage_processing_util_jni.so). See:
    // https://developer.android.com/guide/practices/page-sizes
    val cameraXVersion = "1.6.1"
    implementation("androidx.camera:camera-core:$cameraXVersion")
    implementation("androidx.camera:camera-camera2:$cameraXVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraXVersion")
    implementation("androidx.camera:camera-view:$cameraXVersion")

    // androidx.graphics:graphics-path — explicit declaration to force a
    // 16KB-aligned version. This is a transitive dependency (pulled in by
    // Compose/AndroidX UI libs) whose default version ships a 4KB-aligned
    // libandroidx.graphics.path.so. Explicitly declaring 1.0.1+ ensures the
    // 16KB-aligned build is used. See:
    // https://developer.android.com/guide/practices/page-sizes
    implementation("androidx.graphics:graphics-path:1.0.1")

    // jBCrypt for Password Hashing
    implementation("org.mindrot", "jbcrypt", "0.4")

    // Gson
    implementation("com.google.code.gson", "gson", "2.13.2")

    // Androidx ExifInterface
    implementation("androidx.exifinterface", "exifinterface", "1.4.2")

    // Telephoto
    implementation("me.saket.telephoto:zoomable-image-coil:0.18.0")

    // Coil
    val coilVersion = "2.7.0"
    implementation("io.coil-kt:coil-compose:$coilVersion")
    implementation("io.coil-kt:coil-gif:$coilVersion")
    implementation("io.coil-kt:coil-video:$coilVersion")

    // Exoplayer
    // Bumped 1.9.2 → 1.10.1 (Batch 3 — safe dep bumps from dependabot branch).
    val media3Version = "1.10.1"
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-ui:$media3Version")
    implementation("androidx.media3:media3-ui-compose:$media3Version")
    implementation("androidx.media3:media3-ui-compose-material3:$media3Version")

    implementation(fileTree("libs").matching {
        include("*.jar")
    })
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("androidx.activity:activity:1.12.4")

    // Material Components for Android — Sprint 1 / P4 (Dynamic Color)
    // Provides Theme.Material3.* parents and DynamicColors API used by MainActivity.
    // The .Bridge theme parent keeps AppCompat-style attributes working on legacy
    // XML screens while enabling Material 3 + dynamic color overlays on Android 12+.
    implementation("com.google.android.material:material:1.12.0")

    // Biometric
    implementation("androidx.biometric:biometric:1.1.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("io.mockk:mockk:1.14.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("com.google.dagger:hilt-android-testing:$daggerVersion")
    kaptTest("com.google.dagger:hilt-android-compiler:$daggerVersion")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")

    // Telemetry
    implementation("com.telemetrydeck:kotlin-sdk:6.3.0")

    // Play Review
    playImplementation("com.google.android.play:review:2.0.2")
    playImplementation("com.google.android.play:review-ktx:2.0.2")
}
