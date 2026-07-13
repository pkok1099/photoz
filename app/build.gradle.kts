plugins {
	id("com.android.application")
	id("com.jaredsburrows.license")
	// Sprint 7: AGP 9.1.0 with builtInKotlin=false (kapt needed for DataBinding)
	kotlin("android")
	kotlin("kapt")
	kotlin("plugin.serialization")
	id("org.jetbrains.kotlin.plugin.compose") version "2.4.0"

	// ─── KSP 2.3.9 (Sprint 6) ─────────────────────────────────────────────
	// KSP2 architecture — version not tied to Kotlin version.
	// 2x faster than kapt. Used for Hilt + Room annotation processing.
	// kapt kept for DataBinding only (mixed mode).
	// @since Sprint 6 — KSP retry with KSP2
	id("com.google.devtools.ksp")

	// ─── Code quality gates (Sprint 4) ────────────────────────────────────
	id("io.gitlab.arturbosch.detekt")
	id("org.jlleitschuh.gradle.ktlint")

	// ─── Coverage (SonarCloud) — jacoco for code coverage reports ─────────
	// Required by .github/workflows/android.yml SonarCloud job which runs
	// `./gradlew jacocoTestReport` and passes the XML to sonar-scanner via
	// -Dsonar.coverage.jacoco.xmlReportPaths. Generates report at
	// app/build/reports/jacoco/jacocoTestReport/jacocoTestReport.xml.
	jacoco
}

val isReleaseBuildInvocation: Boolean = gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }

// F-WARN-006: replaced deprecated `by project` (provideDelegate) with explicit
// project.property() calls. Gradle 9.6 deprecates the property delegate syntax.
val appVersionName: String = project.property("appVersionName") as String
val appVersionCode: String = project.property("appVersionCode") as String

val telemetryDeckAppId: String? = project.findProperty("telemetryDeckAppId") as String?

apply(plugin = "androidx.navigation.safeargs.kotlin")
apply(plugin = "dagger.hilt.android.plugin")

android {
	compileSdk = 37

	defaultConfig {
		applicationId = "onlasdan.gallery"
		minSdk = 35
		targetSdk = 36

		versionCode = appVersionCode.toInt()
		versionName = appVersionName

		testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

		ndk {
			abiFilters += "arm64-v8a"
		}

		// F-WARN-007: removed annotationProcessorOptions (room.incremental, room.schemaLocation)
		// — these are kapt args but Room now uses KSP (see ksp { arg(...) } block below).
		// kapt only processes DataBinding which doesn't recognize Room args, causing
		// the build warning: "options not recognized by any processor".

		base {
			archivesName = "photok-$versionName"
		}

		buildConfigField(
			"String",
			"TELEMETRY_DECK_APP_ID",
			"\"${telemetryDeckAppId.orEmpty()}\"",
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

	// ─── Release signing config (CI-driven) ──────────────────────────────
	// Reads keystore credentials from env vars (set by GitHub Actions from
	// repository secrets). If env vars are missing (local dev), release
	// builds are unsigned and must be signed manually.
	//
	// Required env vars:
	//   SIGNING_KEYSTORE_PATH     — file path to decoded .keystore
	//   SIGNING_KEYSTORE_PASSWORD — keystore password
	//   SIGNING_KEY_ALIAS         — key alias
	//   SIGNING_KEY_PASSWORD      — key password
	val releaseSigningConfig =
		if (System.getenv("SIGNING_KEYSTORE_PATH") != null) {
			signingConfigs.create("release") {
				storeFile = file(System.getenv("SIGNING_KEYSTORE_PATH"))
				storePassword = System.getenv("SIGNING_KEYSTORE_PASSWORD")
				keyAlias = System.getenv("SIGNING_KEY_ALIAS")
				keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
			}
		} else {
			null
		}

	buildTypes {
		getByName("debug") {
			isDebuggable = true
			// Sprint 10+ — consistent debug signing key for CI builds.
			// Without this, each CI runner generates its own ephemeral debug
			// key, and the user can't install updates without uninstalling.
			// The debug.keystore is committed to the repo (debug keys are not
			// sensitive — they're for debug builds only).
			signingConfig =
				signingConfigs.create("debugCi") {
					storeFile = rootProject.file("debug.keystore")
					storePassword = "android"
					keyAlias = "debug"
					keyPassword = "android"
				}
		}

		getByName("release") {
			isMinifyEnabled = true
			// Sprint 8 / L7 — shrink resources in release (removes unused
			// drawables/strings). Pairs with R8 full mode for smaller APK.
			isShrinkResources = true

			proguardFiles(
				getDefaultProguardFile("proguard-android-optimize.txt"),
				"proguard-rules.pro",
			)

			// Sign with release keystore if env vars present; otherwise unsigned.
			releaseSigningConfig?.let { signingConfig = it }
		}
	}

	buildFeatures {
		dataBinding = true
		compose = true
		buildConfig = true
	}

	compileOptions {
		sourceCompatibility = JavaVersion.VERSION_11
		targetCompatibility = JavaVersion.VERSION_11
	}

	lint {
		lintConfig = file("$rootDir/gradle/lint.xml")
		baseline = file("$rootDir/gradle/lint-baseline.xml")
	}
	namespace = "onlasdan.gallery"

	// ─── Native library packaging — gomobile JNI (libgojni.so via dlopen) ──────────
	//
	// rclone is loaded via System.loadLibrary("gojni") (gomobile JNI, dlopen — NOT exec).
	// useLegacyPackaging = false (default for minSdk >= 23): .so files are mmap`d from APK.
	//
	// F-MAIN-002: OLD useLegacyPackaging = true was for the subprocess approach
	// (ProcessBuilder.start() needs on-disk file to execve()), removed in gomobile migration.
	// Switching to false saves ~110 MB disk + faster install.
	// 16KB page alignment verified by scripts/build-rclone-gomobile.sh.
	packaging {
		jniLibs {
			useLegacyPackaging = false
		}
	}
}

// Kotlin 2.4.0+ — kotlinOptions {} removed, must use compilerOptions DSL.
kotlin {
	compilerOptions {
		jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
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
	implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.11.0")
	implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
	// F-PERF-004 (v1.0.2): removed deprecated lifecycle-extensions:2.2.0 — was unused.
	// DefaultLifecycleObserver (used in BaseApplication) comes from lifecycle-common (transitive).

	// Room
	implementation("androidx.room:room-runtime:2.8.4")
	implementation("androidx.room:room-paging:2.8.4")
	ksp("androidx.room:room-compiler:2.8.4")

	// Kotlin Extensions and Coroutines support for Room
	implementation("androidx.room:room-ktx:2.8.4")

	// ─── TODO #6: SQLCipher for Room DB encryption ───────────────────────────
	// Sprint 3 / TODO #6 — at-rest encryption for the main vault DB (photok.db).
	//
	// The bootstrap metadata DB (photok_meta.db) stays plaintext — it must be
	// readable before unlock so VaultService can iterate vault_protection rows.
	// The main DB (photok.db) is encrypted with a 32-byte key backed by the
	// Android Keystore; the key is supplied to SQLCipher via SupportOpenHelperFactory.
	//
	// Pairing: `androidx.sqlite:sqlite` MUST match the version Room expects
	// (Room 2.8.4 uses androidx.sqlite 2.5.0+). The SupportFactory API lives
	// in androidx.sqlite.db — the SQLCipher library provides its own factory
	// implementation that we plug into Room via `openHelperFactory(...)`.
	//
	// Trade-off: ~5-15% slower DB queries, +3-5MB APK (native lib).
	// @since v16 — Sprint 3 / TODO #6 SQLCipher
	implementation("net.zetetic:sqlcipher-android:4.9.0")
	implementation("androidx.sqlite:sqlite:2.7.0")

	// ─── TODO #3: Bouncy Castle for Argon2id KDF ─────────────────────────────
	// Sprint 2 / TODO #3 — Argon2id memory-hard KDF for new vaults.
	// KeyGen.derivePasswordKeyEncryptionKey() uses Argon2BytesGenerator from
	// Bouncy Castle. Without this dependency, the build fails with
	// "Unresolved reference: Argon2BytesGenerator".
	//
	// bcprov-jdk18on is the JDK 1.8+ build of Bouncy Castle — compatible
	// with Android minSdk 35 (which requires JDK 8+).
	// @since v14 — Sprint 2 / TODO #3 Argon2id
	implementation("org.bouncycastle:bcprov-jdk18on:1.84")

	// ViewPager2
	// F-PERF-004 (v1.0.2): removed androidx.viewpager2:viewpager2:1.1.0 — was unused (all pagers migrated to Compose HorizontalPager).

	// Coroutines
	// Bumped 1.10.2 → 1.11.0 (Batch 3 — safe dep bumps from dependabot branch).
	implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
	implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

	// Coroutine Lifecycle Scopes
	implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.11.0")
	implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")

	// Navigation Components
	// Bumped 2.9.7 → 2.9.8 (Batch 3 — safe dep bumps from dependabot branch).
	implementation("androidx.navigation:navigation-fragment-ktx:2.9.8")
	implementation("androidx.navigation:navigation-ui-ktx:2.9.8")

	// Timber Logging
	implementation("com.jakewharton.timber:timber:5.0.1")

	// Dagger Core
	// NOTE: Dagger/Hilt 2.60 fails to load dagger.spi.internal.shaded...JavacBasicAnnotationProcessor
	// with Room 2.8.4 — reverted to 2.57.2 (Batch 3 dep bump reverted due to build failure).
	val daggerVersion = "2.60"
	implementation("com.google.dagger:dagger:$daggerVersion")
	ksp("com.google.dagger:dagger-compiler:$daggerVersion")

	// Dagger - Hilt
	implementation("com.google.dagger:hilt-android:$daggerVersion")
	ksp("com.google.dagger:hilt-android-compiler:$daggerVersion")
	implementation("androidx.hilt:hilt-navigation-compose:1.4.0")

	ksp("androidx.hilt:hilt-compiler:1.4.0")

	// Hilt-Work — allows @HiltWorker injection into WorkManager Workers.
	// Required by onlasdan.gallery.sync.work.PhotoSyncWorker.
	implementation("androidx.hilt:hilt-work:1.4.0")

	// WorkManager — required by onlasdan.gallery.sync.work.PhotoSyncWorker for cloud sync.
	// Bumped 2.10.0 → 2.11.2 (Batch 3 — safe dep bumps from dependabot branch).
	implementation("androidx.work:work-runtime-ktx:2.11.2")

	// kotlinx-serialization — used by RcloneController for JSON (de)serialization of rclone rc API.
	// Bumped 1.7.3 → 1.11.0 (Batch 3 — safe dep bumps from dependabot branch).
	implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

	// Activity KTX for viewModels()
	implementation("androidx.activity:activity-ktx:1.13.0")

	// Compose
	implementation(platform("androidx.compose:compose-bom:2026.06.01"))
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
	implementation("com.google.zxing:core:3.5.4")

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
	implementation("androidx.graphics:graphics-path:1.1.0")

	// jBCrypt for Password Hashing
	implementation("org.mindrot", "jbcrypt", "0.4")

	// Gson
	implementation("com.google.code.gson", "gson", "2.14.0")

	// Androidx ExifInterface
	implementation("androidx.exifinterface", "exifinterface", "1.4.2")

	// Telephoto
	implementation("me.saket.telephoto:zoomable-image-coil:0.19.0")

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

	implementation(
		fileTree("libs").matching {
			include("*.jar", "*.aar")
		},
	)
	implementation("androidx.core:core-ktx:1.19.0")
	implementation("androidx.appcompat:appcompat:1.7.1")
	implementation("androidx.constraintlayout:constraintlayout:2.2.1")
	implementation("androidx.activity:activity:1.13.0")
	implementation("androidx.documentfile:documentfile:1.1.0")

	// QtFastStart — vendored from https://github.com/ypresto/qtfaststart-java
	// (7KB, zero deps, MIT licensed). MP4 MOOV atom relocation for progressive
	// video streaming. Source included in app/src/main/java/net/ypresto/qtfaststart/
	// TODO #1 — progressive video streaming fix

	// Material Components for Android — Sprint 1 / P4 (Dynamic Color)
	// Provides Theme.Material3.* parents and DynamicColors API used by MainActivity.
	// The .Bridge theme parent keeps AppCompat-style attributes working on legacy
	// XML screens while enabling Material 3 + dynamic color overlays on Android 12+.
	implementation("com.google.android.material:material:1.14.0")

	// Biometric
	implementation("androidx.biometric:biometric:1.1.0")

	testImplementation("junit:junit:4.13.2")
	testImplementation("org.robolectric:robolectric:4.16.1")
	testImplementation("io.mockk:mockk:1.14.11")
	testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
	testImplementation("com.google.dagger:hilt-android-testing:$daggerVersion")
	kspTest("com.google.dagger:hilt-android-compiler:$daggerVersion")
	androidTestImplementation("androidx.test.ext:junit:1.3.0")
	androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")

	// Telemetry
	implementation("com.telemetrydeck:kotlin-sdk:7.0.0")

	// Play Review
	playImplementation("com.google.android.play:review:2.0.2")
	playImplementation("com.google.android.play:review-ktx:2.0.2")

	// ─── LeakCanary — memory leak detection (Sprint 4) ────────────────────
	// Zero-config: auto-installs in debug builds, shows a notification when
	// a leak is detected. No code changes needed — just add the dependency.
	//
	// Why: PhotoZ loads many images (Coil) + videos (ExoPlayer) + has many
	// Compose screens — prime candidates for memory leaks. LeakCanary catches
	// them silently in debug builds before they reach production.
	//
	// Release builds are unaffected — LeakCanary is debugImplementation only.
	// @since Sprint 4 — code quality tooling
	debugImplementation("com.squareup.leakcanary:leakcanary-android:2.14")

	// ─── TFLite — DISABLED for debugging startup crash (Sprint 8)
	// Re-enable after isolating the crash cause.
	// implementation("org.tensorflow:tensorflow-lite:2.16.1")
}

// ─── KSP arguments (Sprint 6 — KSP2) ─────────────────────────────────────
// KSP uses ksp { arg(...) } instead of kapt's annotationProcessorOptions.
// Room needs schemaLocation for auto-migration generation.
// @since Sprint 6 — KSP retry with KSP2
ksp {
	arg("room.schemaLocation", "$projectDir/schemas")
	arg("room.incremental", "true")
}

// ─── detekt configuration ────────────────────────────────────────────────
// Static analysis for Kotlin. Catches code smells, complexity, potential bugs.
// Config file: config/detekt/detekt.yml (project-level rules).
// Baseline: config/detekt/detekt-baseline.xml (existing violations — prevents
// detekt from blocking CI on day 1; new violations will fail the build).
//
// Run: ./gradlew detekt (check) or ./gradlew detektBaseline (generate baseline)
// @since Sprint 4 — code quality tooling
detekt {
	config.setFrom(rootProject.files("config/detekt/detekt.yml"))
	baseline = rootProject.file("config/detekt/detekt-baseline.xml")
	buildUponDefaultConfig = true
	parallel = true
	ignoreFailures = false
}

// ─── ktlint configuration ────────────────────────────────────────────────
// Enforces Kotlin official code style. Auto-fix via `./gradlew ktlintFormat`.
// @since Sprint 4 — code quality tooling
ktlint {
	// ktlint 14.x doesn't need much config — defaults are good.
	version.set("1.5.0") // ktlint engine version (separate from plugin version)
	// Sprint 4: ktlintFormat cleanup done (338 files reformatted, 177 detekt
	// violations auto-fixed). All remaining violations manually fixed.
	// ktlint is now a BLOCKING quality gate — new style violations fail CI.
	// @since Sprint 5 — ktlint now blocking
	ignoreFailures.set(false)
}

// ─── JaCoCo coverage report (for SonarCloud) ────────────────────────────────
// Generates app/build/reports/jacoco/jacocoTestReport/jacocoTestReport.xml
// Run via: ./gradlew jacocoTestReport
// Depends on: testFossDebugUnitTest (runs unit tests, generates .exec files)
tasks.register<JacocoReport>("jacocoTestReport") {
	group = "verification"
	description = "Generates JaCoCo coverage report for SonarCloud"

	dependsOn("testFossDebugUnitTest")

	reports {
		xml.required.set(true)
		html.required.set(false)
		csv.required.set(false)
		xml.outputLocation.set(file("${layout.buildDirectory.get()}/reports/jacoco/jacocoTestReport/jacocoTestReport.xml"))
	}

	// AGP 9+ — Kotlin classes are in tmp/kotlin-classes/<variant>/, Java classes
	// in intermediates/javac/<variant>/classes/. Include both for full coverage.
	val kotlinClasses = fileTree("${layout.buildDirectory.get()}/tmp/kotlin-classes/fossDebug")
	val javaClasses = fileTree("${layout.buildDirectory.get()}/intermediates/javac/fossDebug/classes")
	classDirectories.setFrom(kotlinClasses, javaClasses)

	sourceDirectories.setFrom(files("src/main/java", "src/main/kotlin"))
	executionData.setFrom(fileTree("${layout.buildDirectory.get()}/jacoco").include("*.exec"))
}
