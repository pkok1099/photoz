// ─── Plugin management — Sprint 4 code quality tooling ───────────────────
// Required for the `plugins {}` DSL to resolve detekt + ktlint-gradle
// from Gradle Plugin Portal. Existing plugins (AGP, Kotlin, Hilt) are
// declared in the root buildscript classpath and don't need this.
// @since Sprint 4 — detekt + ktlint
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

include(":app")
// Sprint 8 / L7 — Baseline profile generation module.
// Generates a baseline-prof file via macrobenchmark tests that's embedded
// in the release APK to speed up cold start by 15-40%. The module is
// included but the actual profile generation needs a device/emulator —
// the scaffold is here so a developer can run
// `./gradlew :baselineprofile:connectedBenchmarkAndroidTest` to generate.
// @since v13 — Sprint 8 / L7 baseline profiles
include(":baselineprofile")
rootProject.name = "Photok"
