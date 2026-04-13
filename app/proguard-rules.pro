# ─── TensorFlow Lite ────────────────────────────────────────────────────────
# Keep all TFLite classes to prevent release build crashes
-keep class org.tensorflow.lite.** { *; }
-keep class org.tensorflow.lite.gpu.** { *; }
-keep class org.tensorflow.lite.support.** { *; }
-dontwarn org.tensorflow.lite.**
-dontwarn org.tensorflow.lite.support.**

# FIX: Keep TFLite native methods (JNI) — without this, release minify strips them
-keepclasseswithmembernames class * {
    native <methods>;
}

# ─── Bulldog Blocker ─────────────────────────────────────────────────────────
-keep class com.ftt.bulldogblocker.** { *; }

# ─── Android Accessibility / DeviceAdmin ─────────────────────────────────────
-keep class * extends android.accessibilityservice.AccessibilityService { *; }
-keep class * extends android.app.admin.DeviceAdminReceiver { *; }
-keep class * extends android.content.BroadcastReceiver { *; }
-keep class * extends android.app.Service { *; }

# ─── Kotlin Coroutines ──────────────────────────────────────────────────────
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**
# FIX: Coroutine debug info causes issues in release — suppress
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# ─── AndroidX / AppCompat ────────────────────────────────────────────────────
-keep class androidx.** { *; }
-dontwarn androidx.**
