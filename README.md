# 🐶 Bulldog Blocker — Adult Content Blocker

Native Android app (Kotlin) with TFLite ML-based image classification,
Device Admin protection, and uninstall delay enforcement.

---

## 📁 Project Structure

```
BulldogBlocker/
├── app/
│   ├── src/main/
│   │   ├── java/com/ftt/bulldogblocker/
│   │   │   ├── admin/
│   │   │   │   └── DeviceAdminReceiver.kt      ← Device Admin (uninstall block)
│   │   │   ├── ml/
│   │   │   │   └── ContentClassifier.kt         ← TFLite model wrapper
│   │   │   ├── receiver/
│   │   │   │   └── BootReceiver.kt              ← Auto-start on boot
│   │   │   ├── service/
│   │   │   │   ├── BlockerAccessibilityService.kt ← URL block + uninstall intercept
│   │   │   │   └── BlockerForegroundService.kt  ← Keep-alive service
│   │   │   └── ui/
│   │   │       ├── MainActivity.kt              ← Dashboard
│   │   │       ├── BlockScreenActivity.kt       ← Full-screen block overlay
│   │   │       └── UninstallDelayActivity.kt    ← 60s countdown before uninstall
│   │   ├── assets/
│   │   │   └── saved_model.tflite               ← ⚠️ YOU MUST ADD THIS FILE
│   │   ├── res/xml/
│   │   │   ├── device_admin.xml
│   │   │   └── accessibility_service.xml
│   │   └── AndroidManifest.xml
│   ├── build.gradle
│   └── proguard-rules.pro
├── .github/workflows/build.yml                  ← GitHub Actions CI
├── build.gradle
├── settings.gradle
└── README.md
```

---

## 🔐 Anti-Uninstall Protection — How It Works

### Layer 1: Device Administrator
When active, Android OS **physically blocks uninstall** from Settings.
User must first go to:
```
Settings → Security → Device Admins → Bulldog Blocker → Deactivate
```
Only after deactivating admin can they proceed to uninstall.

### Layer 2: Accessibility Service (Uninstall Interceptor)
`BlockerAccessibilityService` watches for:
- Package installer being opened for our app
- Settings "App Info" page showing "Uninstall" button for our app

When detected → `UninstallDelayActivity` launches immediately.

### Layer 3: UninstallDelayActivity (60-second Countdown)
- Full-screen overlay with red background
- 60-second countdown timer (configurable via `DELAY_MS`)
- "Uninstall" button disabled until countdown finishes
- Only after countdown → removes Device Admin → opens real uninstall dialog
- Back button disabled during countdown

### Layer 4: Foreground Service Watchdog
- Runs a background watchdog every 30s
- If Device Admin is somehow removed → alerts user to re-enable
- Service is `START_STICKY` → OS restarts it if killed
- Boot receiver re-starts it on device reboot

---

## 🤖 TFLite Model Setup

The file `saved_model.tflite` must be placed at:
```
app/src/main/assets/saved_model.tflite
```

### Option A — Direct (small model, < 100MB)
```bash
cp /path/to/saved_model.tflite app/src/main/assets/
```
Commit it to the repo. The `.gitignore` is configured to **include** it.

### Option B — GitHub Secret (large model or private)
1. Convert model to base64:
```bash
base64 -w 0 saved_model.tflite > model.b64
```
2. Go to GitHub repo → **Settings → Secrets → Actions**
3. Create secret named: `TFLITE_MODEL_B64`
4. Paste the base64 content
5. The CI workflow will decode it automatically before building.

### Model Output Format
`ContentClassifier.kt` expects output shape `[1][2]`:
```
output[0][0] = safe_score   (0.0 – 1.0)
output[0][1] = unsafe_score (0.0 – 1.0)
```
Default block threshold: **0.60** (60% confidence).
Adjust in `ContentClassifier.kt`:
```kotlin
private const val ADULT_THRESHOLD = 0.60f
```

---

## 🚀 GitHub Repository Setup

### Step 1: Create repo (from phone via Termux)
```bash
# Install git if needed
pkg install git

cd /path/to/BulldogBlocker

# Initialize
git init
git add .
git commit -m "feat: initial Bulldog Blocker project"

# Create repo on GitHub first, then:
git remote add origin https://github.com/YOUR_USERNAME/BulldogBlocker.git
git branch -M main
git push -u origin main
```

### Step 2: Add TFLite model secret (if using Option B above)
GitHub → Settings → Secrets → Actions → `TFLITE_MODEL_B64`

### Step 3: Trigger first build
Push any commit or go to:
**GitHub → Actions → Build Debug APK → Run workflow**

### Step 4: Download APK
**Actions → latest run → Artifacts → BulldogBlocker-debug → Download**

---

## 🔨 Local Build (Termux / Android phone)

```bash
# Install requirements
pkg install openjdk-17

# Set JAVA_HOME
export JAVA_HOME=/data/data/com.termux/files/usr/opt/openjdk-17
export PATH=$JAVA_HOME/bin:$PATH

# Place TFLite model
cp /sdcard/saved_model.tflite app/src/main/assets/

# Build
chmod +x gradlew
./gradlew assembleDebug

# APK location:
# app/build/outputs/apk/debug/app-debug.apk

# Install to connected device
adb install app/build/outputs/apk/debug/app-debug.apk
```

---

## 📱 First-Time Setup on Device

After installing the APK:

1. **Open Bulldog Blocker**
2. Tap **"Device Admin সক্রিয় করুন"** → Allow
3. Tap **"Accessibility সক্রিয় করুন"** → Find "Bulldog Blocker" → Turn ON
4. Dashboard should show **"🛡️ সম্পূর্ণ সুরক্ষিত"**

Done. The app is now protected.

---

## ⚙️ Configuration

| Constant | File | Default | Description |
|---|---|---|---|
| `DELAY_MS` | `UninstallDelayActivity.kt` | `60_000` | Uninstall delay in ms |
| `ADULT_THRESHOLD` | `ContentClassifier.kt` | `0.60f` | ML block confidence |
| `BLOCKED_KEYWORDS` | `BlockerAccessibilityService.kt` | (list) | URL keyword blocklist |

---

## 🔧 Troubleshooting

**Gradle build fails with `TFLite not found`**
→ Make sure `saved_model.tflite` is in `app/src/main/assets/`

**`FOREGROUND_SERVICE_SPECIAL_USE` permission error**
→ Target SDK must be 34. Already set in `app/build.gradle`.

**Accessibility service not showing in Settings**
→ Check `AndroidManifest.xml` has the correct `<service>` declaration.
→ Some MIUI/One UI ROMs require "Auto-start" permission too.

**Device Admin activation dialog not showing**
→ `DeviceAdminReceiver` must match the `android:name` in manifest exactly.
