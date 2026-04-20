// app/src/main/java/com/guardian/shield/service/accessibility/GuardianAccessibilityService.kt
package com.guardian.shield.service.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.guardian.shield.data.local.datastore.GuardianPreferences
import com.guardian.shield.data.repository.AppRuleRepository
import com.guardian.shield.data.repository.BlockEventRepository
import com.guardian.shield.data.repository.KeywordRepository
import com.guardian.shield.di.AccessibilityServiceEntryPoint
import com.guardian.shield.domain.model.BlockEvent
import com.guardian.shield.domain.model.BlockReason
import com.guardian.shield.domain.model.DetectionResult
import com.guardian.shield.service.blur.BlurOverlayManager
import com.guardian.shield.service.blur.CumulativeBlurTracker
import com.guardian.shield.service.blur.RegionBlurOverlayManager
import com.guardian.shield.service.blur.TileAnalyzer
import com.guardian.shield.service.blocker.BlockingEngine
import com.guardian.shield.service.blocker.GuardianForegroundService
import com.guardian.shield.service.detection.AiDetector
import com.guardian.shield.service.detection.RulesEngine
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import timber.log.Timber

private val SYSTEM_UI_SKIP = setOf(
    "android", "com.android.systemui",
    "com.google.android.inputmethod.latin",
    "com.samsung.android.honeyboard",
    "com.sec.android.inputmethod",
    "com.touchtype.swiftkey",
    "com.swiftkey.swiftkeyapp"
)

class GuardianAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "Guardian_Service"
        const val ACTION_REFRESH_RULES = "com.guardian.shield.REFRESH_RULES"
        const val ACTION_RELOAD_MODEL  = "com.guardian.shield.RELOAD_MODEL"
        const val ACTION_UPDATE_SETTINGS = "com.guardian.shield.UPDATE_SETTINGS"
        private const val TEXT_DEBOUNCE_MS = 1200L  // Increased from 700ms (battery)
        
        // FIX: Blur verification interval - check if content is still unsafe WHILE blur is shown
        private const val BLUR_RECHECK_MS = 2000L   // Recheck every 2s while blur is visible
        private const val MAX_BLUR_HOLD_MS = 5000L  // Force recheck after 5s

        private val BROWSER_PACKAGES = setOf(
            "com.android.chrome", "org.mozilla.firefox", "com.opera.browser",
            "com.brave.browser", "com.microsoft.emmx", "com.UCMobile.intl",
            "com.sec.android.app.sbrowser", "com.kiwibrowser.browser"
        )
    }

    private lateinit var rulesEngine: RulesEngine
    private lateinit var blockingEngine: BlockingEngine
    private lateinit var aiDetector: AiDetector
    private lateinit var appRuleRepo: AppRuleRepository
    private lateinit var keywordRepo: KeywordRepository
    private lateinit var blockEventRepo: BlockEventRepository
    private lateinit var prefs: GuardianPreferences
    private lateinit var blurTracker: CumulativeBlurTracker
    private lateinit var blurOverlayManager: BlurOverlayManager
    private lateinit var regionBlurManager: RegionBlurOverlayManager
    private lateinit var tileAnalyzer: TileAnalyzer

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @Volatile private var currentForegroundPkg = ""
    @Volatile private var aiEnabled = false
    @Volatile private var aiThreshold = 0.40f
    @Volatile private var aiIntervalMs = 1_000L
    @Volatile private var aiScanJob: Job? = null
    @Volatile private var aiBusy = false
    @Volatile private var isInjected = false
    
    // FIX: Track blur state timing for smart recheck
    @Volatile private var blurShownAt = 0L
    @Volatile private var lastBlurRegions: List<Rect> = emptyList()

    private var textDebounceJob: Job? = null
    @Volatile private var watchdogJob: Job? = null

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (!isInjected) return
            Timber.d("$TAG receiver: ${intent?.action}")
            when (intent?.action) {
                ACTION_REFRESH_RULES -> serviceScope.launch {
                    loadRulesIntoEngine()
                    loadSettings()
                }
                ACTION_RELOAD_MODEL -> serviceScope.launch {
                    Timber.d("$TAG RELOAD_MODEL received")
                    loadSettings()
                    reloadAiModel()
                }
                ACTION_UPDATE_SETTINGS -> serviceScope.launch {
                    Timber.d("$TAG UPDATE_SETTINGS received")
                    loadSettings()
                    blockingEngine.loadSettings()
                }
            }
        }
    }

    override fun onServiceConnected() {
        Timber.d("$TAG onServiceConnected")

        try {
            val entryPoint = EntryPointAccessors.fromApplication(
                applicationContext,
                AccessibilityServiceEntryPoint::class.java
            )
            rulesEngine = entryPoint.rulesEngine()
            blockingEngine = entryPoint.blockingEngine()
            aiDetector = entryPoint.aiDetector()
            appRuleRepo = entryPoint.appRuleRepo()
            keywordRepo = entryPoint.keywordRepo()
            blockEventRepo = entryPoint.blockEventRepo()
            prefs = entryPoint.prefs()
            blurTracker = entryPoint.cumulativeBlurTracker()
            blurOverlayManager = entryPoint.blurOverlayManager()
            regionBlurManager = entryPoint.regionBlurOverlayManager()
            tileAnalyzer = entryPoint.tileAnalyzer()
            isInjected = true
            Timber.d("$TAG injection successful")
        } catch (e: Exception) {
            Timber.e(e, "$TAG injection FAILED")
            return
        }

        // FIX: Load everything BEFORE starting scan loop
        serviceScope.launch {
            loadRulesIntoEngine()
            loadSettings()
            blockingEngine.loadSettings()

            // FIX: Always try to load AI model if file exists, regardless of toggle
            if (AiDetector.isModelAvailable(applicationContext)) {
                Timber.d("$TAG Model file found — loading")
                reloadAiModel()
            } else {
                Timber.w("$TAG No model file available")
            }
        }
        registerReceivers()
        startForegroundWatchdog()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isInjected) return
        event ?: return
        val pkg = event.packageName?.toString() ?: return

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (!SYSTEM_UI_SKIP.contains(pkg) && !rulesEngine.isSystemUi(pkg)) {
                val prev = currentForegroundPkg
                if (prev != pkg && prev.isNotBlank()) {
                    // FIX: Hide all blur immediately on app change
                    hideAllBlurImmediate()
                    blurTracker.onAppChanged(prev, pkg)
                    Timber.d("$TAG app changed: $prev → $pkg")
                }
                currentForegroundPkg = pkg
                handleAppEvent(pkg)
            }
        }

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            val scanPkg = currentForegroundPkg.takeIf { it.isNotBlank() } ?: return
            if (!SYSTEM_UI_SKIP.contains(scanPkg) && !rulesEngine.isSystemUi(scanPkg)) {
                debounceTextScan(scanPkg)
            }
        }
    }

    override fun onInterrupt() {
        Timber.w("$TAG interrupted")
    }

    override fun onDestroy() {
        stopAiScanLoop()
        if (isInjected) {
            hideAllBlurImmediate()
        }
        serviceScope.cancel()
        try { unregisterReceiver(refreshReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    // FIX: Centralized blur hide with state cleanup
    private fun hideAllBlurImmediate() {
        try {
            regionBlurManager.hide()
            blurOverlayManager.hide()
            blurShownAt = 0L
            lastBlurRegions = emptyList()
        } catch (e: Exception) {
            Timber.e(e, "$TAG hideAllBlurImmediate error")
        }
    }

    private fun handleAppEvent(pkg: String) {
        serviceScope.launch(Dispatchers.Default) {
            try {
                when (val result = rulesEngine.evaluateApp(pkg)) {
                    is DetectionResult.Block -> {
                        val appName = getAppName(pkg)
                        logAndBlock(pkg, appName, result.reason, result.detail)
                    }
                    else -> { }
                }
            } catch (e: Exception) {
                Timber.e(e, "$TAG handleAppEvent error")
            }
        }
    }

    private fun debounceTextScan(pkg: String) {
        textDebounceJob?.cancel()
        textDebounceJob = serviceScope.launch {
            delay(TEXT_DEBOUNCE_MS)
            if (!isInjected) return@launch
            if (rulesEngine.isWhitelisted(pkg)) return@launch
            if (blockingEngine.isCoolingDown()) return@launch

            val root = try { rootInActiveWindow } catch (_: Exception) { null } ?: return@launch
            try {
                val text = collectAllText(root)
                if (text.isBlank()) return@launch

                val scanText = if (pkg in BROWSER_PACKAGES) {
                    val url = getUrlBarText(root)
                    if (url != null) "$text $url" else text
                } else text

                val result = rulesEngine.evaluateText(pkg, scanText)
                if (result is DetectionResult.Block) {
                    val appName = getAppName(pkg)
                    logAndBlock(pkg, appName, result.reason, result.detail)
                }
            } catch (e: Exception) {
                Timber.e(e, "$TAG debounceTextScan error")
            } finally {
                try { root.recycle() } catch (_: Exception) {}
            }
        }
    }

    private suspend fun reloadAiModel() {
        try {
            aiEnabled = prefs.isAiDetectionEnabled.first()
            aiThreshold = prefs.aiThreshold.first()
            aiIntervalMs = prefs.aiIntervalMs.first()
        } catch (e: Exception) {
            Timber.e(e, "$TAG reloadAiModel: settings read failed")
        }

        if (!aiEnabled) {
            Timber.d("$TAG AI disabled — stopping scan loop")
            stopAiScanLoop()
            aiDetector.unload()
            return
        }

        if (!AiDetector.isModelAvailable(applicationContext)) {
            Timber.w("$TAG AI enabled but no model file!")
            sendBroadcast(Intent("com.guardian.shield.MODEL_MISSING"))
            stopAiScanLoop()
            return
        }

        val ok = withContext(Dispatchers.IO) { aiDetector.reload() }

        if (ok) {
            Timber.d("$TAG AI model loaded: ${aiDetector.getModelType()}")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startAiScanLoop()
                Timber.d("$TAG AI scan loop started (threshold=$aiThreshold, interval=${aiIntervalMs}ms)")
            } else {
                Timber.w("$TAG AI screenshot requires Android 11+")
                sendBroadcast(Intent("com.guardian.shield.AI_UNSUPPORTED"))
            }
        } else {
            Timber.e("$TAG AI model load FAILED")
            stopAiScanLoop()
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private fun startAiScanLoop() {
        stopAiScanLoop()
        aiScanJob = serviceScope.launch {
            Timber.d("$TAG AI scan loop STARTED")
            while (isActive) {
                // FIX: Use dynamic interval - shorter when blur is showing to recheck faster
                val isBlurShowing = blurOverlayManager.isShowing || regionBlurManager.isShowing
                val currentInterval = if (isBlurShowing) BLUR_RECHECK_MS else aiIntervalMs
                delay(currentInterval)

                if (currentForegroundPkg.isBlank()) continue
                if (!rulesEngine.isProtectionActive()) {
                    // Protection turned off - hide any blur
                    if (isBlurShowing) {
                        withContext(Dispatchers.Main) { hideAllBlurImmediate() }
                    }
                    continue
                }

                if (!aiDetector.isLoaded()) continue
                if (rulesEngine.isWhitelisted(currentForegroundPkg)) {
                    if (isBlurShowing) {
                        withContext(Dispatchers.Main) { hideAllBlurImmediate() }
                    }
                    continue
                }
                if (blockingEngine.isCoolingDown()) continue
                if (aiBusy) continue

                // FIX: Smart blur recheck logic
                if (isBlurShowing) {
                    val blurAge = System.currentTimeMillis() - blurShownAt
                    // Force recheck after MAX_BLUR_HOLD_MS to prevent stuck blur
                    if (blurAge < BLUR_RECHECK_MS) continue
                    
                    Timber.d("$TAG Blur recheck after ${blurAge}ms")
                    // Hide blur temporarily to take clean screenshot
                    withContext(Dispatchers.Main) {
                        regionBlurManager.hide()
                        blurOverlayManager.hide()
                    }
                    delay(150)  // Wait for overlay to actually disappear
                }

                captureAndAnalyze()
            }
        }
    }

    private fun stopAiScanLoop() {
        aiScanJob?.cancel()
        aiScanJob = null
        Timber.d("$TAG AI scan loop STOPPED")
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    @android.annotation.SuppressLint("NewApi")
    private fun captureAndAnalyze() {
        aiBusy = true

        watchdogJob?.cancel()
        watchdogJob = serviceScope.launch {
            delay(15_000)
            if (aiBusy) {
                Timber.w("$TAG watchdog fired — resetting aiBusy")
                aiBusy = false
            }
        }

        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        serviceScope.launch(Dispatchers.Default) {
                            analyzeScreenshot(result)
                            watchdogJob?.cancel()
                            watchdogJob = null
                        }
                    }
                    override fun onFailure(errorCode: Int) {
                        watchdogJob?.cancel()
                        watchdogJob = null
                        aiBusy = false
                        if (errorCode != 2) {
                            Timber.w("$TAG screenshot failed: $errorCode")
                        }
                    }
                }
            )
        } catch (e: Exception) {
            watchdogJob?.cancel()
            watchdogJob = null
            aiBusy = false
            Timber.e(e, "$TAG captureAndAnalyze error")
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    @android.annotation.SuppressLint("NewApi")
    private suspend fun analyzeScreenshot(result: ScreenshotResult) {
        var full: Bitmap? = null
        var cropped: Bitmap? = null
        var tileResult: TileAnalyzer.TileAnalysisResult? = null
        try {
            val hb = result.hardwareBuffer ?: return
            val hwBmp = Bitmap.wrapHardwareBuffer(hb, result.colorSpace)
            full = hwBmp?.copy(Bitmap.Config.ARGB_8888, false)
            hwBmp?.recycle()
            if (full == null) return

            val w = full.width
            val h = full.height
            val topCut = getStatusBarHeight()
            val botCut = getNavBarHeight()
            val cropH = h - topCut - botCut
            cropped = if (cropH > 100) {
                Bitmap.createBitmap(full, 0, topCut, w, cropH)
            } else full

            if (aiDetector.shouldSkipFrame(cropped)) {
                Timber.d("$TAG skip frame (uniform)")
                return
            }

            val overallResult = aiDetector.classify(cropped, aiThreshold)
            val pkg = currentForegroundPkg

            Timber.d("$TAG AI[$pkg]: unsafe=${overallResult.unsafeScore}, label=${overallResult.label}")

            if (overallResult.isUnsafe) {
                val evalResult = rulesEngine.evaluateAiResult(pkg, overallResult.unsafeScore, aiThreshold)

                if (evalResult is DetectionResult.Blur) {
                    tileResult = tileAnalyzer.analyzeTiles(
                        croppedBitmap = cropped,
                        fullBitmap = full,
                        threshold = aiThreshold,
                        screenOffsetY = topCut
                    )

                    withContext(Dispatchers.Main) {
                        if (tileResult!!.isAnyUnsafe) {
                            regionBlurManager.showRegions(tileResult!!.unsafeTiles)
                            blurOverlayManager.hide()
                            Timber.d("$TAG REGION BLUR: $pkg (${tileResult!!.unsafeTiles.size} tiles)")
                        } else {
                            blurOverlayManager.show()
                            regionBlurManager.hide()
                            Timber.d("$TAG FULL BLUR: $pkg")
                        }
                        blurShownAt = System.currentTimeMillis()
                    }
                    tileResult = null  // ownership transferred

                    val shouldBlock = blurTracker.onUnsafeDetected(pkg)
                    val totalMs = blurTracker.getTotalBlurMs(pkg)

                    if (shouldBlock) {
                        Timber.w("$TAG CUMULATIVE BLOCK: $pkg — ${totalMs}ms")
                        withContext(Dispatchers.Main) { hideAllBlurImmediate() }
                        blurTracker.resetApp(pkg)
                        val appName = getAppName(pkg)
                        logAndBlock(
                            pkg, appName,
                            BlockReason.AI_DETECTED,
                            "Extended unsafe content: ${totalMs / 1000}s"
                        )
                    }
                } else if (evalResult is DetectionResult.Whitelist) {
                    withContext(Dispatchers.Main) { hideAllBlurImmediate() }
                }
            } else {
                // SAFE content detected
                withContext(Dispatchers.Main) {
                    if (blurOverlayManager.isShowing || regionBlurManager.isShowing) {
                        hideAllBlurImmediate()
                        Timber.d("$TAG content SAFE — blur removed")
                    }
                }

                if (currentForegroundPkg == pkg) {
                    val shouldBlock = blurTracker.onSafeDetected(pkg)
                    if (shouldBlock) {
                        Timber.w("$TAG CUMULATIVE BLOCK on safe-clear: $pkg")
                        blurTracker.resetApp(pkg)
                        val appName = getAppName(pkg)
                        logAndBlock(pkg, appName, BlockReason.AI_DETECTED, "Blur timeout reached")
                    }
                }
            }

        } catch (e: Exception) {
            Timber.e(e, "$TAG analyzeScreenshot error")
        } finally {
            try { result.hardwareBuffer?.close() } catch (_: Exception) {}
            tileResult?.recycle()
            if (cropped != null && cropped !== full) cropped.recycle()
            full?.recycle()
            aiBusy = false
        }
    }

    private suspend fun logAndBlock(
        pkg: String,
        appName: String,
        reason: BlockReason,
        detail: String
    ) {
        try {
            withContext(Dispatchers.IO) {
                blockEventRepo.logEvent(
                    BlockEvent(
                        packageName = pkg,
                        appName = appName,
                        reason = reason,
                        detail = detail
                    )
                )
            }
        } catch (e: Exception) {
            Timber.e(e, "$TAG log event failed")
        }

        withContext(Dispatchers.Main) {
            blockingEngine.executeBlock(
                this@GuardianAccessibilityService, pkg, appName, reason, detail
            )
        }
    }

    private suspend fun loadRulesIntoEngine() {
        try {
            rulesEngine.refreshBlockedApps(appRuleRepo.getBlockedPackages())
            rulesEngine.refreshWhitelistedApps(appRuleRepo.getWhitelistedPackages())
            rulesEngine.refreshKeywords(keywordRepo.getActiveKeywords())
            Timber.d("$TAG rules loaded")
        } catch (e: Exception) {
            Timber.e(e, "$TAG loadRulesIntoEngine failed")
        }
    }

    private suspend fun loadSettings() {
        try {
            aiEnabled = prefs.isAiDetectionEnabled.first()
            aiThreshold = prefs.aiThreshold.first()
            aiIntervalMs = prefs.aiIntervalMs.first()
            val protectionOn = prefs.isProtectionEnabled.first()
            val keywordOn = prefs.isKeywordDetectionEnabled.first()
            val strictMode = prefs.isStrictMode.first()
            rulesEngine.setProtectionEnabled(protectionOn)
            rulesEngine.setKeywordDetectionEnabled(keywordOn)
            rulesEngine.setStrictMode(strictMode)
            Timber.d("$TAG settings: ai=$aiEnabled, keyword=$keywordOn, protection=$protectionOn, strict=$strictMode, threshold=$aiThreshold")
        } catch (e: Exception) {
            Timber.e(e, "$TAG loadSettings failed")
        }
    }

    private fun getStatusBarHeight(): Int {
        return try {
            val id = resources.getIdentifier("status_bar_height", "dimen", "android")
            if (id > 0) resources.getDimensionPixelSize(id) else dpToPx(24)
        } catch (e: Exception) { dpToPx(24) }
    }

    private fun getNavBarHeight(): Int {
        return try {
            val id = resources.getIdentifier("navigation_bar_height", "dimen", "android")
            if (id > 0) resources.getDimensionPixelSize(id) else 0
        } catch (e: Exception) { 0 }
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density + 0.5f).toInt()

    private fun getAppName(pkg: String): String {
        return try {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(pkg, 0)
            ).toString()
        } catch (_: Exception) { pkg }
    }

    private fun collectAllText(node: AccessibilityNodeInfo): String {
        val sb = StringBuilder(512)
        var nodeCount = 0
        val maxNodes = 200

        fun go(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 15 || nodeCount >= maxNodes) return
            nodeCount++
            n.text?.let { if (it.length < 500) sb.append(it).append(' ') }
            n.contentDescription?.let { if (it.length < 200) sb.append(it).append(' ') }
            val childCount = minOf(n.childCount, 50)
            for (i in 0 until childCount) {
                if (nodeCount >= maxNodes) break
                val c = try { n.getChild(i) } catch (_: Exception) { null } ?: continue
                go(c, depth + 1)
                try { c.recycle() } catch (_: Exception) {}
            }
        }
        go(node, 0)
        return sb.toString()
    }

    private fun getUrlBarText(root: AccessibilityNodeInfo): String? {
        val ids = listOf(
            "com.android.chrome:id/url_bar",
            "com.android.chrome:id/search_box_text",
            "org.mozilla.firefox:id/mozac_browser_toolbar_url_view",
            "com.brave.browser:id/url_bar"
        )
        for (id in ids) {
            try {
                val nodes = root.findAccessibilityNodeInfosByViewId(id)
                if (nodes.isNotEmpty()) {
                    val text = nodes[0].text?.toString()
                    nodes.forEach { try { it.recycle() } catch (_: Exception) {} }
                    if (text != null) return text
                }
            } catch (_: Exception) {}
        }
        return null
    }

    private fun startForegroundWatchdog() {
        try {
            val intent = Intent(this, GuardianForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                startForegroundService(intent)
            else
                startService(intent)
        } catch (e: Exception) {
            Timber.e(e, "$TAG failed to start foreground service")
        }
    }

    private fun registerReceivers() {
        val filter = IntentFilter().apply {
            addAction(ACTION_REFRESH_RULES)
            addAction(ACTION_RELOAD_MODEL)
            addAction(ACTION_UPDATE_SETTINGS)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                registerReceiver(refreshReceiver, filter, RECEIVER_NOT_EXPORTED)
            else
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(refreshReceiver, filter)
            Timber.d("$TAG receivers registered")
        } catch (e: Exception) {
            Timber.e(e, "$TAG registerReceivers failed")
        }
    }
}