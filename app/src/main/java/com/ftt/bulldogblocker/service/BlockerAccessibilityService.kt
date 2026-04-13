package com.ftt.bulldogblocker.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.ftt.bulldogblocker.ml.ContentClassifier
import com.ftt.bulldogblocker.ui.BlockScreenActivity
import com.ftt.bulldogblocker.ui.UninstallDelayActivity
import android.content.BroadcastReceiver
import android.content.IntentFilter
import kotlinx.coroutines.*

/**
 * Accessibility Service with two responsibilities:
 *
 * 1. SCREENSHOT ML BLOCKING (API 30+):
 *    Captures the screen every 500 ms and runs the TFLite model.
 *    If adult content is detected → launches BlockScreenActivity.
 *
 * 2. URL TEXT BLOCKING (all APIs):
 *    Watches browser address bars for adult keywords/domains.
 *    Uses debounce so it does NOT spam BACK press.
 *
 * 3. UNINSTALL INTERCEPTION:
 *    Watches package installer / Settings for our package name.
 *    Redirects to UninstallDelayActivity (60-second countdown).
 */
class BlockerAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "BDB_Accessibility"
        private const val OUR_PACKAGE = "com.ftt.bulldogblocker"

        // How often to capture a screenshot and run the ML model (ms)
        private const val SCREENSHOT_INTERVAL_MS = 500L

        // Minimum time between two block actions (ms)
        // Prevents BACK from being spammed while the user is still typing
        private const val BLOCK_COOLDOWN_MS = 3000L

        // Debounce delay for URL bar text changes (ms)
        private const val URL_DEBOUNCE_MS = 600L

        private val BROWSER_PACKAGES = setOf(
            "com.android.chrome",
            "org.mozilla.firefox",
            "com.opera.browser",
            "com.brave.browser",
            "com.microsoft.emmx",
            "com.UCMobile.intl",
            "com.sec.android.app.sbrowser"
        )

        private val INSTALLER_PACKAGES = setOf(
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.miui.packageinstaller"
        )

        const val ACTION_RELOAD_MODEL = "com.ftt.bulldogblocker.RELOAD_MODEL"

        private val BLOCKED_KEYWORDS = listOf(
            "porn", "xxx", "adult", "sex", "nude", "nsfw",
            "hentai", "erotic", "xvideos", "xhamster", "pornhub",
            "redtube", "youporn", "onlyfans", "chaturbate"
        )
    }

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())

    // Shared classifier instance — loaded once, reused for every screenshot
    private var classifier: ContentClassifier? = null
    private var classifierLoaded = false

    // Prevents overlapping screenshot+inference jobs
    @Volatile private var screenshotBusy = false

    // Timestamp of last block action — used for cooldown
    @Volatile private var lastBlockTime = 0L

    // Uninstall interception cooldown — prevents spamming the delay screen
    @Volatile private var lastUninstallTime = 0L

    // Debounce job for URL bar changes
    private var urlDebounceJob: Job? = null

    // Continuous screenshot loop job
    private var screenshotJob: Job? = null

    // Receives ACTION_RELOAD_MODEL broadcast from MainActivity after model upload
    private val modelReloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: android.content.Context?, intent: android.content.Intent?) {
            if (intent?.action == ACTION_RELOAD_MODEL) {
                Log.d(TAG, "Model reload broadcast received")
                classifierLoaded = false
                classifier?.close()
                classifier = null
                loadClassifier()
            }
        }
    }

    // ---------- Lifecycle ----------

    override fun onServiceConnected() {
        Log.d(TAG, "Accessibility service connected")
        loadClassifier()
        startScreenshotLoop()
        registerModelReloadReceiver()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return

        when {
            pkg in INSTALLER_PACKAGES -> handleUninstallViaInstaller(event)
            pkg == "com.android.settings" -> handleUninstallViaSettings(event)
            pkg in BROWSER_PACKAGES -> handleBrowserEvent(event)
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Service interrupted — restarting screenshot loop")
        screenshotJob?.cancel()
        startScreenshotLoop()
    }

    override fun onDestroy() {
        screenshotJob?.cancel()
        serviceScope.cancel()
        classifier?.close()
        classifier = null
        try { unregisterReceiver(modelReloadReceiver) } catch (_: Exception) {}
        Log.d(TAG, "Service destroyed")
    }

    // ---------- Classifier ----------

    private fun loadClassifier() {
        if (classifierLoaded) return
        serviceScope.launch {
            val c = ContentClassifier(applicationContext)
            classifierLoaded = c.load()
            if (classifierLoaded) {
                classifier = c
                Log.d(TAG, "ContentClassifier loaded successfully")
            } else {
                Log.w(TAG, "ContentClassifier load failed — model not uploaded yet")
            }
        }
    }

    // ---------- Continuous Screenshot Loop ----------

    /**
     * Captures a screenshot every SCREENSHOT_INTERVAL_MS and runs the ML model.
     * Only runs on Android 11+ (API 30) where takeScreenshot() is available.
     * If the model is not loaded yet, the loop runs silently and retries loading.
     */
    private fun startScreenshotLoop() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Log.w(TAG, "Screenshot API requires Android 11+. ML blocking disabled.")
            return
        }

        screenshotJob?.cancel()
        screenshotJob = serviceScope.launch {
            while (isActive) {
                delay(SCREENSHOT_INTERVAL_MS)

                // Skip if in block cooldown
                if (System.currentTimeMillis() - lastBlockTime < BLOCK_COOLDOWN_MS) continue

                // Skip if a previous screenshot is still being processed
                if (screenshotBusy) continue

                // If model not loaded, try again
                if (!classifierLoaded) {
                    loadClassifier()
                    continue
                }

                captureAndAnalyze()
            }
        }
        Log.d(TAG, "Screenshot loop started (interval: ${SCREENSHOT_INTERVAL_MS}ms)")
    }

    private fun captureAndAnalyze() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        screenshotBusy = true

        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        processScreenshot(result)
                    }
                    override fun onFailure(errorCode: Int) {
                        screenshotBusy = false
                        Log.v(TAG, "Screenshot failed: $errorCode")
                    }
                }
            )
        } catch (e: Exception) {
            screenshotBusy = false
            Log.e(TAG, "takeScreenshot error", e)
        }
    }

    private fun processScreenshot(result: ScreenshotResult) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

        serviceScope.launch {
            var bmp: Bitmap? = null
            try {
                // Convert HardwareBuffer to a software-backed Bitmap for TFLite
                bmp = Bitmap.wrapHardwareBuffer(
                    result.hardwareBuffer,
                    result.colorSpace
                )?.copy(Bitmap.Config.ARGB_8888, false)

                val c = classifier
                if (bmp != null && c != null && classifierLoaded) {
                    val classification = c.classify(bmp)
                    Log.v(TAG, "ML: ${classification.label}")

                    if (classification.isAdult) {
                        Log.w(TAG, "Adult content detected — blocking")
                        withContext(Dispatchers.Main) {
                            triggerBlock("ML model detected adult content")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Screenshot analysis error", e)
            } finally {
                try { result.hardwareBuffer.close() } catch (_: Exception) {}
                try { bmp?.recycle() } catch (_: Exception) {}
                screenshotBusy = false
            }
        }
    }

    // ---------- Block Action ----------

    /**
     * Press BACK and launch BlockScreenActivity.
     * Respects BLOCK_COOLDOWN_MS to prevent repeated triggers.
     */
    private fun triggerBlock(reason: String) {
        val now = System.currentTimeMillis()
        if (now - lastBlockTime < BLOCK_COOLDOWN_MS) return
        lastBlockTime = now

        performGlobalAction(GLOBAL_ACTION_BACK)
        launchBlockScreen(reason)
    }

    private fun launchBlockScreen(reason: String) {
        try {
            startActivity(Intent(this, BlockScreenActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra("reason", reason)
            })
        } catch (e: Exception) {
            Log.e(TAG, "Cannot launch BlockScreenActivity", e)
        }
    }

    // ---------- Uninstall Interception ----------

    private fun handleUninstallViaInstaller(event: AccessibilityEvent) {
        val root = rootInActiveWindow ?: return
        try {
            val text = collectAllText(root).lowercase()
            if ("bulldog" in text || OUR_PACKAGE in text) {
                Log.w(TAG, "Uninstall attempt via package installer!")
                launchUninstallDelay()
            }
        } finally {
            // MUST recycle rootInActiveWindow to prevent memory leaks
            root.recycle()
        }
    }

    private fun handleUninstallViaSettings(event: AccessibilityEvent) {
        val root = rootInActiveWindow ?: return
        try {
            val text = collectAllText(root).lowercase()
            if (("bulldog blocker" in text || "bulldogblocker" in text) && "uninstall" in text) {
                Log.w(TAG, "Uninstall attempt via Settings!")
                launchUninstallDelay()
            }
        } finally {
            root.recycle()
        }
    }

    private fun launchUninstallDelay() {
        val now = System.currentTimeMillis()
        if (now - lastUninstallTime < 5000L) return  // Debounce: max once per 5s
        lastUninstallTime = now
        try {
            startActivity(Intent(this, UninstallDelayActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            })
        } catch (e: Exception) {
            Log.e(TAG, "Cannot launch UninstallDelayActivity", e)
        }
    }

    // ---------- Browser URL Blocking ----------

    /**
     * Debounced URL check.
     * Cancels any pending check and restarts the timer after URL_DEBOUNCE_MS.
     * This prevents spamming BACK on every keystroke while typing in the address bar.
     */
    private fun handleBrowserEvent(event: AccessibilityEvent) {
        // Only react to text/content changes, not every window event
        if (event.eventType != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return

        urlDebounceJob?.cancel()
        urlDebounceJob = serviceScope.launch {
            delay(URL_DEBOUNCE_MS)

            // Respect cooldown
            if (System.currentTimeMillis() - lastBlockTime < BLOCK_COOLDOWN_MS) return@launch

            val root = rootInActiveWindow ?: return@launch
            try {
                val urlText = getUrlBarText(root)?.lowercase() ?: return@launch
                if (BLOCKED_KEYWORDS.any { urlText.contains(it) }) {
                    Log.w(TAG, "Blocked URL: $urlText")
                    withContext(Dispatchers.Main) {
                        triggerBlock("Blocked website: $urlText")
                    }
                }
            } finally {
                // MUST recycle to prevent memory leaks
                root.recycle()
            }
        }
    }

    private fun getUrlBarText(root: AccessibilityNodeInfo): String? {
        val urlBarIds = listOf(
            "com.android.chrome:id/url_bar",
            "com.android.chrome:id/search_box_text",
            "org.mozilla.firefox:id/mozac_browser_toolbar_url_view",
            "com.brave.browser:id/url_bar"
        )
        for (id in urlBarIds) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            if (nodes.isNotEmpty()) {
                val text = nodes[0].text?.toString()
                // Recycle all found nodes
                nodes.forEach { it.recycle() }
                if (text != null) return text
            }
        }
        return findEditableText(root)
    }

    private fun findEditableText(node: AccessibilityNodeInfo): String? {
        if (node.isEditable && node.text != null) return node.text.toString()
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findEditableText(child)
            child.recycle()   // Recycle every child after traversal
            if (result != null) return result
        }
        return null
    }

    // ---------- Model Reload Receiver ----------

    private fun registerModelReloadReceiver() {
        try {
            val filter = IntentFilter(ACTION_RELOAD_MODEL)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(modelReloadReceiver, filter, RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(modelReloadReceiver, filter)
            }
        } catch (e: Exception) {
            Log.e(TAG, "registerModelReloadReceiver error", e)
        }
    }

    // ---------- Helper ----------

    /**
     * Collect all visible text from a node tree.
     * Recycles every child node after traversal to prevent memory leaks.
     */
    private fun collectAllText(node: AccessibilityNodeInfo): String {
        val sb = StringBuilder()
        fun traverse(n: AccessibilityNodeInfo?) {
            n ?: return
            n.text?.let { sb.append(it).append(' ') }
            n.contentDescription?.let { sb.append(it).append(' ') }
            for (i in 0 until n.childCount) {
                val child = n.getChild(i) ?: continue
                traverse(child)
                child.recycle()  // Recycle after use
            }
        }
        traverse(node)
        return sb.toString()
    }
}
