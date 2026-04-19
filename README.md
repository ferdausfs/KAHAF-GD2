# 🛡️ Guardian Shield

**Adult Content Blocker & Digital Discipline App for Android**

Native Android app — Kotlin, Clean Architecture, MVVM, Hilt, Room, TFLite.

---

## 🚀 Quick Start — Build APK via GitHub Actions

1. Push this repo to GitHub (any branch: `main` / `master` / `dev`)
2. GitHub Actions starts automatically
3. Go to **Actions** tab → `Build Debug APK` → download `GuardianShield-debug-*.apk` artifact

That's it. No local Android Studio needed.

---

## 🏗️ Local Build (Android Studio)

```bash
git clone https://github.com/YOUR_USER/GuardianShield
cd GuardianShield
./gradlew assembleDebug
# APK → app/build/outputs/apk/debug/app-debug.apk
```

**Requirements:**
- JDK 17+
- Android SDK (API 34)
- Gradle 8.4 (auto-downloaded by wrapper)

---

## 📱 First-Time Setup on Device

1. Install APK (enable "Install from unknown sources")
2. Open app → **Set PIN** (protects settings)
3. Tap **"Enable Accessibility Service"** → Settings → Guardian Shield → Enable
4. Add apps to **Blocked List** in Settings
5. Optionally upload `.tflite` model for AI detection

---

## 🧠 Architecture

```
Clean Architecture + MVVM

UI Layer        → Activities, Adapters
ViewModel Layer → StateFlow, lifecycle-aware
Domain Layer    → UseCases, Models
Data Layer      → Room DB, DataStore, EncryptedSharedPrefs

Key Services:
  RulesEngine              ← whitelist-first detection priority
  GuardianAccessibilityService ← event-driven, no polling
  BlockingEngine           ← HOME + BlockOverlayActivity
  AiDetector               ← TFLite, event-triggered only
  PinManager               ← SHA-256 hash, EncryptedSharedPrefs
```

**Whitelist Priority (immutable order):**
```
1. Own package       → always allow
2. System UI         → always allow
3. WHITELIST         → always allow (overrides EVERYTHING)
4. Blocked app list  → block
5. Keyword match     → block
6. AI detection      → block
```

---

## 📦 Tech Stack

| Component | Library |
|-----------|---------|
| Language | Kotlin |
| DI | Hilt 2.50 |
| DB | Room 2.6.1 |
| Prefs | DataStore 1.0 |
| Secure Storage | EncryptedSharedPreferences |
| Async | Coroutines + StateFlow |
| AI | TensorFlow Lite 2.14 |
| UI | Material Design 3 + ViewBinding |
| Logging | Timber |
| Min SDK | API 26 (Android 8.0) |

---

## 🔐 Security

- PIN stored as **SHA-256 hash** only — never plain text
- Sensitive config in **EncryptedSharedPreferences** (AES-256-GCM)
- No network calls — fully offline
- No analytics / tracking
- ProGuard enabled for release builds

---

## 🤖 AI Detection (Optional)

Supply your own `.tflite` model:
- **2-class**: `[safe_score, unsafe_score]`
- **5-class**: `[drawings, hentai, neutral, porn, sexy]`

Upload via Settings → AI Screen Detection → Upload Model.

Model is **not bundled** in the APK. Stored in `filesDir/guardian_model.tflite`.

---

## 📁 Project Structure

```
app/src/main/java/com/guardian/shield/
├── domain/
│   ├── model/          AppRule, KeywordRule, BlockEvent, DetectionResult
│   └── usecase/        12 clean use cases
├── data/
│   ├── local/db/       Room entities, DAOs, mappers
│   ├── local/datastore/ GuardianPreferences, SecureStorage
│   └── repository/     interfaces + implementations
├── service/
│   ├── detection/      RulesEngine, PinManager, AiDetector
│   ├── blocker/        BlockingEngine, ForegroundService
│   └── accessibility/  GuardianAccessibilityService
├── di/                 Hilt modules
├── viewmodel/          Dashboard, AppList, Settings, Keyword, PIN
├── ui/
│   ├── dashboard/      MainActivity, BlockEventAdapter
│   ├── overlay/        BlockOverlayActivity
│   ├── unlock/         DelayUnlockActivity
│   ├── setup/          PinSetupActivity, PinVerifyActivity
│   └── settings/       SettingsActivity, Adapters
└── receiver/           BootReceiver
```
