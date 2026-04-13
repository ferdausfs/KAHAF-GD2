# ─── TensorFlow Lite ────────────────────────────────────────────────────────
# Keep all TFLite classes to prevent release build crashes
-keep class org.tensorflow.lite.** { *; }
-keep class org.tensorflow.lite.gpu.** { *; }
-dontwarn org.tensorflow.lite.**

# ─── Bulldog Blocker ─────────────────────────────────────────────────────────
-keep class com.ftt.bulldogblocker.** { *; }

# ─── Android Accessibility / DeviceAdmin ─────────────────────────────────────
-keep class * extends android.accessibilityservice.AccessibilityService { *; }
-keep class * extends android.app.admin.DeviceAdminReceiver { *; }
-keep class * extends android.content.BroadcastReceiver { *; }

# ─── Kotlin Coroutines ──────────────────────────────────────────────────────
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# ─── AndroidX / AppCompat ────────────────────────────────────────────────────
-keep class androidx.** { *; }
-dontwarn androidx.**
