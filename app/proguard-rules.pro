# keep readable stack traces & class names
-dontobfuscate

# keep line numbers for crash reports
-keepattributes SourceFile,LineNumberTable

# Keep generic type info (needed by Gson)
-keepattributes Signature

# Keep annotations (SerializedName etc.)
-keepattributes *Annotation*

# Keep fields
-keepclassmembers class dev.leonlatsch.photok.** {
    <fields>;
}
-keep class dev.leonlatsch.photok.**

# Keep TypeToken
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# ─── Batch 3 — R8/ProGuard keep rules for release builds ──────────────────
# With isMinifyEnabled = true on the release build type, R8 strips/removes
# anything it can prove is unused. These rules ensure critical reflection-based
# or JNI-bound components survive minification. Each block is scoped to the
# narrowest package that fully covers the at-risk classes.

# Hilt/Dagger — keep generated classes (Hilt uses reflection + codegen to wire
# @HiltAndroidApp / @AndroidEntryPoint entry points and the HiltViewModelFactory).
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.android.internal.lifecycle.HiltViewModelFactory$ViewModelFactoriesEntryPoint { *; }
-keep,allowobfuscation @dagger.hilt.android.HiltAndroidApp class *
-keep,allowobfuscation @dagger.hilt.android.AndroidEntryPoint class *

# Room — keep entities and DAOs (Room generates reflection-based impls of the
# abstract DAO methods at runtime; the entity classes are referenced by name in
# the generated *_Impl classes).
-keep class onlasdan.gallery.model.database.entity.** { *; }
-keep class onlasdan.gallery.model.database.dao.** { *; }
-keep class * extends androidx.room.RoomDatabase { *; }

# kotlinx.serialization — keep serializers (companion serializer(...) lookups
# are reflection-based; stripping the companion breaks decoder dispatch).
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# rclone binary (librclone.so) — keep the JNI-bound wrapper classes that load
# and invoke the bundled Go executable via ProcessBuilder.
-keep class onlasdan.gallery.sync.rclone.** { *; }

# Crypto — keep cipher classes (Algorithm/Kdf enums are referenced by name
# from encrypted blobs stored on disk; the JCA Cipher.getInstance(alg.value)
# lookups must resolve).
-keep class onlasdan.gallery.encryption.domain.** { *; }
-keep class onlasdan.gallery.encryption.domain.crypto.** { *; }

# WorkManager — keep worker classes (WorkManager instantiates Workers by
# reflection via the HiltWorkerFactory).
-keep class * extends androidx.work.Worker { *; }
-keep class * extends androidx.work.CoroutineWorker { *; }

# Compose — keep composables (the Compose compiler emits call-site lookups by
# function name; stripping composables breaks @Composable call dispatch).
-keep class onlasdan.gallery.**.compose.** { *; }
-keep class onlasdan.gallery.**.ui.** { *; }

# Media3/ExoPlayer — keep DataSource factories (Media3 loads DataSource
# implementations by reflection via @DefaultDataSinkFactory etc.).
-keep class onlasdan.gallery.transcoding.** { *; }