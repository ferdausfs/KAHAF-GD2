# 🐶 Bulldog Blocker v8.0.0

Native Android app (Kotlin) — TFLite ML image classification + AccessibilityService content blocking.

---

## ⚡ Optimizations & Fixes (v8.0.0)

### Fix 1 — `typeViewScrolled` সরানো (Performance)
**সমস্যা:** `accessibility_service.xml`-এ `typeViewScrolled` register ছিল কিন্তু কোড কখনো handle করত না।
প্রতিটি scroll event-এ service ডাকা হতো → unnecessary CPU usage।
**Fix:** event type list থেকে `typeViewScrolled` সরানো হয়েছে।

---

### Fix 2 — `ReportOverlayManager` dead code (Cleanup)
**সমস্যা:** `TYPE_PHONE` fallback ছিল `Build.VERSION_CODES.O` এর নিচে, কিন্তু `minSdk = 26 = O` → এই branch কখনো execute হতো না। `Build` import unnecessary ছিল।
**Fix:** Dead code ও unused import সরানো হয়েছে।

---

### Fix 3 — ForegroundService restart Android 12+ (Reliability)
**সমস্যা:** Android 12+ (API 31+)-এ background-এ `startForegroundService()` restricted। Service kill হলে শুধু broadcast দিয়ে restart reliable ছিল না।
**Fix:** `onDestroy()`-এ আগে direct `startForegroundService()` try, fail হলে broadcast fallback।

---

### Fix 4 — `isSystemUiPackage()` helper + prefix detection
**সমস্যা:** `com.android.systemui` subpackages (like `.overlay`, `.recents`) এবং OnePlus/Samsung launcher packages cover হতো না।
**Fix:** `SYSTEM_UI_PREFIXES` array যোগ করা হয়েছে, `isSystemUiPackage()` helper দিয়ে check।

---

### Fix 5 — `ContentClassifier` thread-safety (Race Condition)
**সমস্যা:** `inputBuf`/`pixelBuf` pre-allocated fields। `bitmapToBuffer()` synchronized block-এর বাইরে call হতো → MainActivity test + ScreenshotBlocker concurrent চললে buffer corruption।
**Fix:** `bitmapToBuffer()` synchronized block-এর ভেতরে নিয়ে আসা হয়েছে।

---

### Optimization 1 — `shouldSkipFrame()` Welford single-pass algorithm
**আগে:** ৩টা `FloatArray(1024)` allocate + ৩টা separate loop → প্রতি 1.5s screenshot-এ extra heap allocation।
**এখন:** Welford's online algorithm — একটাই loop, কোনো extra allocation নেই। ~3x কম memory churn।

---

### Optimization 2 — `ContentClassifier` buffer pre-allocation
**আগে:** প্রতিটি `classify()` call-এ নতুন `ByteBuffer.allocateDirect()` + `IntArray` → GC pressure।
**এখন:** `inputBuf` ও `pixelBuf` instance-level pre-allocated, reused সব call-এ।

---

### Optimization 3 — `AppReportManager.addReport()` double prefs call fix
**আগে:** Block expire হলে `prefs(ctx)` দুইবার call হতো।
**এখন:** একবার `prefs()` reference নিয়ে reuse।



### Bug 1 — BootReceiver: BOOT_COMPLETED কখনো fire হতো না (CRITICAL)

**সমস্যা:**
`<receiver android:permission="com.ftt.bulldogblocker.RESTART_PERMISSION">` মানে
যেকোনো sender-কে ওই permission hold করতে হবে।
Android system BOOT_COMPLETED পাঠানোর সময় app-level permission রাখে না।
ফলে phone restart-এ BootReceiver কখনো trigger হতো না → protection বন্ধ।

**Fix:** `android:permission` receiver tag থেকে সরানো হয়েছে।
RESTART_SERVICE এর security explicit Intent দিয়ে maintain করা হয়।

---

### Bug 2 — Whitelist app-এও block হতো (CRITICAL)

**সমস্যা:**
`currentForegroundPkg` keyboard/SystemUI event-এ overwrite হতো।
WhatsApp whitelist করা → keyboard খুলল → `currentForegroundPkg` = keyboard package →
`isForegroundPkgWhitelisted()` = false → ML scan চালু → WhatsApp-এ block!

**Fix:** `SYSTEM_UI_PACKAGES` set যোগ করা হয়েছে।
এই packages থেকে `TYPE_WINDOW_STATE_CHANGED` এলে `currentForegroundPkg` update হয় না।

---

### Bug 3 — BulldogBlocker নিজেকে block করত (CRITICAL)

**সমস্যা:**
BlockScreenActivity খোলার সময় `currentForegroundPkg = "com.ftt.bulldogblocker"` হতো।
ML scan চলতে থাকত → BlockScreen-এর screenshot → `triggerBlock(pkg = OUR_PACKAGE)` →
`countReport` block skip → direct block path-এ পড়ে → BlockScreen আবার fire → infinite loop!

**Fix 1:** `triggerBlock()` এর শুরুতে `if (pkg == OUR_PACKAGE) return` যোগ।
**Fix 2:** `isForegroundPkgWhitelisted` lambda-তে `currentForegroundPkg == OUR_PACKAGE` যোগ।

---

### Bug 4 — Popup দেখার সময় পাওয়া যেত না (HIGH)

**সমস্যা:**
`INTERVAL_MS = 300ms` → প্রতি সেকেন্ডে ৩টা screenshot → ৩টা report → threshold পার →
পুরো ৯০০ms এর মধ্যে block screen। Overlay popup কোনো সময়ই দেখা যেত না।

**Fix:** `INTERVAL_MS = 1_500ms`, `COOLDOWN_MS = 5_000ms`।
এখন report মাঝে ৫ সেকেন্ড gap থাকে — user overlay দেখতে পাবে।

---

### Bug 5 — Uniform color false positive (HIGH)

**সমস্যা:**
`isMostlyBlack()` শুধু কালো screen skip করত।
সাদা loading screen, splash screen, solid UI → ML classifier-এ যেত → false positive।

**Fix:** `shouldSkipFrame()` — pixel variance analysis যোগ।
Variance < 150 = সব pixel একই রঙ = uniform screen → skip।

---

### Bug 6 — Version mismatch (MEDIUM)

README বলছিল v2.0, build.gradle বলছিল v5.0.0।
**Fix:** উভয়ই `v7.0.0` করা হয়েছে।

---

## 📁 Changed Files (v7)

```
AndroidManifest.xml                    ← FIX: BootReceiver permission সরানো
service/BlockerAccessibilityService.kt ← FIX: whitelist bypass + self-block
service/ScreenshotBlocker.kt           ← FIX: interval + uniform color skip
ui/MainActivity.kt                     ← FIX: "300ms" → "1500ms" text
app/build.gradle                       ← versionCode 7, versionName 7.0.0
```

---

## 🚀 Setup

### Step 1 — Build & Install
```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

### Step 2 — Enable Device Admin
Open app → **Device Admin সক্রিয় করুন** → confirm।

### Step 3 — Enable Accessibility Service
**Accessibility সক্রিয় করুন** → Bulldog Content Blocker → enable।

### Step 4 — Upload TFLite Model
**Model Upload করুন** → select `saved_model.tflite`।

---

## 🔐 Anti-Uninstall Layers

| Layer | Mechanism | Effect |
|-------|-----------|--------|
| 1 | Device Admin | OS blocks uninstall |
| 2 | Accessibility intercept | Detects attempt, shows delay screen |
| 3 | Countdown screen | Waiting period before proceeding |
| 4 | Foreground service watchdog | Alerts if admin removed |

---

## 📱 Requirements

- Android 8.0 (API 26) minimum
- Android 11 (API 30) for ML screenshot blocking
- ~50MB for TFLite model
