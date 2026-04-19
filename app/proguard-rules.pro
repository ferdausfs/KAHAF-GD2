# Guardian Shield ProGuard Rules

# Keep TFLite
-keep class org.tensorflow.lite.** { *; }
-keep class org.tensorflow.lite.support.** { *; }

# Keep Room entities
-keep class com.guardian.shield.data.local.db.**Entity { *; }

# Keep Hilt generated code
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keepclassmembers class * {
    @javax.inject.Inject <init>(...);
    @javax.inject.Inject <fields>;
}

# Keep data classes (used with Room / Flow)
-keep class com.guardian.shield.domain.model.** { *; }

# Timber
-dontwarn org.jetbrains.annotations.**

# Kotlin coroutines
-keepclassmembers class kotlinx.coroutines.** { *; }

# DataStore
-keepclassmembers class androidx.datastore.** { *; }

# EncryptedSharedPreferences / Security
-keep class androidx.security.crypto.** { *; }

# Keep enum names (used by BlockReason.valueOf())
-keepclassmembers enum com.guardian.shield.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
