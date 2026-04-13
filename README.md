# 🐶 Bulldog Blocker v2.0 — Adult Content Blocker

Native Android app (Kotlin) with TFLite ML-based image classification,
Device Admin protection, and uninstall delay enforcement.

---

## 🐛 Bug Fixes (v2.0)

### Bug 1 — DeviceAdminReceiver compile error (CRASH)
`DeviceAdminReceiver.kt` imported `android.app.admin.DeviceAdminReceiver` and then
extended it using the same simple name — causing the class to extend **itself**.

**Fix:** Removed the import. Used fully-qualified parent class name:
```kotlin
class DeviceAdminReceiver : android.app.admin.DeviceAdminReceiver()
```

### Bug 2 — ContentClassifier compile error (CRASH)
`useXNNPACK = true` is not valid Kotlin property syntax for `Interpreter.Options`
in TFLite 2.14.0 — the class only exposes a Java setter with no getter.

**Fix:** Called the setter directly:
```kotlin
setUseXNNPACK(true)
```

### Bug 3 — Missing ProGuard rules (release build crash)
No TFLite keep rules → ProGuard stripped all `org.tensorflow.lite.*` classes
in release builds, causing an instant crash at runtime.

**Fix:** Added to `proguard-rules.pro`:
```
-keep class org.tensorflow.lite.** { *; }
```

### Bug 4 — Missing `activity-ktx` dependency
`UninstallDelayActivity` uses `OnBackPressedCallback` from `androidx.activity`,
but the dependency wasn't declared explicitly.

**Fix:** Added `androidx.activity:activity-ktx:1.8.2` to `build.gradle`.

---

## 📁 Project Structure

```
BulldogBlocker/
├── app/src/main/
│   ├── java/com/ftt/bulldogblocker/
│   │   ├── admin/DeviceAdminReceiver.kt       ← FIXED: class name conflict
│   │   ├── ml/ContentClassifier.kt            ← FIXED: useXNNPACK API
│   │   ├── receiver/BootReceiver.kt
│   │   ├── service/
│   │   │   ├── BlockerAccessibilityService.kt
│   │   │   └── BlockerForegroundService.kt
│   │   └── ui/
│   │       ├── MainActivity.kt
│   │       ├── BlockScreenActivity.kt
│   │       └── UninstallDelayActivity.kt
│   ├── res/xml/
│   │   ├── accessibility_service.xml
│   │   └── device_admin.xml
│   └── AndroidManifest.xml
├── app/build.gradle                           ← FIXED: buildConfig, activity-ktx
├── app/proguard-rules.pro                     ← FIXED: TFLite keep rules
└── README.md
```

---

## 🚀 Setup

### Step 1 — Build & Install
```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

### Step 2 — Enable Device Admin
Open the app → tap **"Device Admin সক্রিয় করুন"** → confirm.

### Step 3 — Enable Accessibility Service
Tap **"Accessibility সক্রিয় করুন"** → find **Bulldog Content Blocker** → enable.

### Step 4 — Upload TFLite Model
Tap **"Model Upload করুন"** → select your `saved_model.tflite` file.

Model requirements:
- Input: `[1, 224, 224, 3]` float32 (RGB, normalized 0–1)
- Output: `[1, 2]` → `[safe_score, unsafe_score]`
- Threshold: unsafe ≥ 0.60 → blocked

Recommended: [NSFW Detection Model](https://github.com/GantMan/nsfw_model)

---

## 🔐 How Anti-Uninstall Works

| Layer | Mechanism | Effect |
|-------|-----------|--------|
| 1 | Device Admin | OS blocks uninstall entirely |
| 2 | Accessibility intercept | Detects uninstall attempt, opens delay screen |
| 3 | 60s countdown screen | Forces a waiting period before proceeding |
| 4 | Foreground service watchdog | Alerts if admin is removed |

---

## 📱 Minimum Requirements

- Android 8.0 (API 26) minimum
- Android 11 (API 30) required for ML screenshot blocking
- ~50MB free storage for TFLite model
