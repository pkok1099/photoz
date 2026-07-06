// Sprint 8 / L7 — Baseline Profile generation module.
//
// This module contains macrobenchmark tests that generate a baseline-prof
// file for the app. The profile is embedded in the release APK and speeds
// up cold start by 15-40% (Android Runtime pre-compiles the hot paths
// identified by the profile).
//
// To generate the profile:
//  1. Connect a device/emulator running Android 14+ (API 34+)
//  2. Run: ./gradlew :baselineprofile:connectedBenchmarkAndroidTest
//  3. The profile is written to app/src/release/baseline-prof.txt
//  4. Rebuild the release APK — the profile is embedded automatically
//
// The module uses androidx.benchmark:macro-junit4 which drives the app
// from outside (like a user would) and records the JIT hot paths.
//
// @since v13 — Sprint 8 / L7 baseline profiles

plugins {
    id("com.android.test")
    kotlin("android")
}

android {
    namespace = "onlasdan.gallery.baselineprofile"
    compileSdk = 36

    defaultConfig {
        minSdk = 35
        targetSdk = 36

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true

    buildTypes {
        create("benchmark") {
            isDebuggable = false
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Kotlin 2.4.0+ — kotlinOptions {} removed, must use compilerOptions DSL.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.test.ext:junit:1.3.0")
    implementation("androidx.test.espresso:espresso-core:3.7.0")
    implementation("androidx.test.uiautomator:uiautomator:2.3.0")
    implementation("androidx.benchmark:benchmark-macro-junit4:1.3.4")
}
