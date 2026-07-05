buildscript {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    dependencies {
        classpath("com.android.tools.build:gradle:8.13.1")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.2.21")
        classpath("org.jetbrains.kotlin:kotlin-serialization:2.2.21")
        classpath("androidx.navigation:navigation-safe-args-gradle-plugin:2.9.6")
        classpath("com.google.dagger:hilt-android-gradle-plugin:2.57.2")
        classpath("com.jaredsburrows:gradle-license-plugin:0.9.8")
        // Sprint 4 — code quality tooling
        classpath("io.gitlab.arturbosch.detekt:detekt-gradle-plugin:1.23.8")
        classpath("org.jlleitschuh.gradle:ktlint-gradle:14.2.0")
        // Sprint 4 — KSP (Kotlin Symbol Processing) for Hilt + Room.
        // Version 2.2.21-2.0.5 matches Kotlin 2.2.21 exactly (KSP1 line).
        // KSP is 2x faster than kapt. We still keep kapt for DataBinding
        // (KSP doesn't support DataBinding yet — mixed mode).
        classpath("com.google.devtools.ksp:com.google.devtools.ksp.gradle.plugin:2.2.21-2.0.5")
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

apply("./gradle/updateVersion.gradle.kts")
apply("./gradle/updateTranslations.gradle.kts")