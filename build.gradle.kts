buildscript {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    dependencies {
        classpath("com.android.tools.build:gradle:9.1.0")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.0")
        classpath("org.jetbrains.kotlin:kotlin-serialization:2.4.0")
        classpath("androidx.navigation:navigation-safe-args-gradle-plugin:2.9.8")
        classpath("com.google.dagger:hilt-android-gradle-plugin:2.60")
        classpath("com.jaredsburrows:gradle-license-plugin:0.9.9")
        // Sprint 4 — code quality tooling
        classpath("io.gitlab.arturbosch.detekt:detekt-gradle-plugin:1.23.8")
        classpath("org.jlleitschuh.gradle:ktlint-gradle:14.2.0")
        // Sprint 6 — KSP 2.3.9 (KSP2 architecture, standalone — not tied to Kotlin version)
        // Works with Kotlin 2.2.21. KSP2 fixes the Kotlin backend bug that caused
        // CI #124 failure with KSP 2.2.21-2.0.5 (Hilt KSP + suspend functions).
        classpath("com.google.devtools.ksp:com.google.devtools.ksp.gradle.plugin:2.3.9")
        // Sprint 6 — Paparazzi for screenshot testing (no device needed)
        classpath("app.cash.paparazzi:paparazzi-gradle-plugin:1.3.5")
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