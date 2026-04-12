package com.kb.blocker.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.kb.blocker.analyzer.ImageAnalyzer
import com.kb.blocker.analyzer.TextAnalyzer
import com.kb.blocker.data.PrefsManager
import com.kb.blocker.data.db.AppDatabase
import com.kb.blocker.service.BlockerForegroundService
import kotlinx.coroutines.*

class ContentBlockerService : AccessibilityService() {

    companion object {
        private const val TAG = "ContentBlockerService"
        const val ACTION_RELOAD_MODELS = "com.kb.blocker.RELOAD_MODELS"

        // --- Timing constants ---

        // How long to wait after the last event before running text analysis.
        // Prevents triggering on every single keystroke while typing.
        private const val TEXT_DEBOUNCE_MS = 600L

        // Minimum gap between two block actions.
        // Prevents the service from spamming BACK press.
        private const val BLOCK_COOLDOWN_MS = 2500L

        // Minimum gap between two screenshot captures.
        private const val IMAGE_INTERVAL_MS = 2000L

        @Volatile
        var isRunning = false
    }

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private lateinit var textAnalyzer: TextAnalyzer
    private lateinit var imageAnalyzer: ImageAnalyzer
    private lateinit var prefs: PrefsManager

    private val mainHandler = Handler(Looper.getMainLooper())

    // --- Debounce state ---
    // Pending text analysis job. Cancelled and restarted on every new event.
    private var textDebounceJob: Job? = null

    // --- Block cooldown ---
    // Timestamp of the last block action. Prevents double-blocks.
    @Volatile private var lastBlockTime = 0L

    // --- Image throttle ---
    @Volatile private var lastImageTime = 0L

    // BroadcastReceiver for model reload signal from MainActivity
    private val modelReloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_RELOAD_MODELS) {
                Log.d(TAG, "Model reload broadcast received")
                imageAnalyzer.reloadModels()
            }
        }
    }

    // ---------- Lifecycle ----------

    override fun onServiceConnected() {
        super.onServiceConnected()
        isRunning = true
        Log.d(TAG, "Accessibility Service connected")

        try {
            prefs = PrefsManager(this)
            textAnalyzer = TextAnalyzer(this)
            imageAnalyzer = ImageAnalyzer(this)

            observeKeywords()
            startBlockerForegroundService()
            registerModelReloadReceiver()
        } catch (e: Exception) {
            Log.e(TAG, "onServiceConnected error", e)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // If we are still in the cooldown window, ignore all events.
        val now = System.currentTimeMillis()
        if (now - lastBlockTime < BLOCK_COOLDOWN_MS) return

        try {
            when (event.eventType) {
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
                AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                    // Content is changing rapidly (typing, scrolling).
                    // Debounce: cancel any pending analysis and restart the timer.
                    // Analysis only runs after TEXT_DEBOUNCE_MS of silence.
                    if (prefs.textAnalysisEnabled) {
                        scheduleTextAnalysis(event)
                    }
                }

                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                    // A new window/screen appeared. Run text check immediately
                    // (no debounce) since the content is now stable.
                    if (prefs.textAnalysisEnabled) {
                        runTextAnalysisNow(event)
                    }
                    // Also trigger image analysis on screen change
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                        && prefs.imageAnalysisEnabled
                    ) {
                        maybeAnalyzeImage()
                    }
                }
            }

            // Image analysis also runs on scroll (throttled to IMAGE_INTERVAL_MS)
            if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                && prefs.imageAnalysisEnabled
            ) {
                maybeAnalyzeImage()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Event error (caught, service continues)", e)
        }
    }

    // ---------- Text Analysis ----------

    /**
     * Debounced text check.
     * Cancels the previous pending job and schedules a new one after
     * TEXT_DEBOUNCE_MS. This means analysis only runs when the user
     * has stopped typing/scrolling for that duration.
     */
    private fun scheduleTextAnalysis(event: AccessibilityEvent) {
        // Snapshot the node source before the coroutine runs
        val source = event.source ?: return

        textDebounceJob?.cancel()
        textDebounceJob = serviceScope.launch {
            delay(TEXT_DEBOUNCE_MS)

            // Check cooldown again after the delay
            if (System.currentTimeMillis() - lastBlockTime < BLOCK_COOLDOWN_MS) {
                source.recycle()
                return@launch
            }

            val sb = StringBuilder()
            collectText(source, sb)
            try { source.recycle() } catch (_: Exception) {}

            if (sb.isNotEmpty() && textAnalyzer.containsBlockedContent(sb.toString())) {
                Log.d(TAG, "Text block triggered (debounced)")
                withContext(Dispatchers.Main) { performBlock() }
            }
        }
    }

    /**
     * Immediate text check for stable screens (window state changed).
     * No debounce needed since the screen just loaded.
     */
    private fun runTextAnalysisNow(event: AccessibilityEvent) {
        val source = event.source ?: return
        serviceScope.launch {
            try {
                val sb = StringBuilder()
                collectText(source, sb)
                if (sb.isNotEmpty() && textAnalyzer.containsBlockedContent(sb.toString())) {
                    Log.d(TAG, "Text block triggered (window change)")
                    withContext(Dispatchers.Main) { performBlock() }
                }
            } finally {
                try { source.recycle() } catch (_: Exception) {}
            }
        }
    }

    /** Recursively collect all visible text from the accessibility node tree. */
    private fun collectText(node: AccessibilityNodeInfo, sb: StringBuilder) {
        try {
            node.text?.let { sb.append(it).append(' ') }
            node.contentDescription?.let { sb.append(it).append(' ') }
            for (i in 0 until node.childCount) {
                try {
                    val child = node.getChild(i) ?: continue
                    collectText(child, sb)
                    child.recycle()
                } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.v(TAG, "collectText minor error: ${e.message}")
        }
    }

    // ---------- Image Analysis ----------

    private fun maybeAnalyzeImage() {
        val now = System.currentTimeMillis()
        if (now - lastImageTime < IMAGE_INTERVAL_MS) return
        lastImageTime = now

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    mainExecutor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(result: ScreenshotResult) {
                            processScreenshot(result)
                        }
                        override fun onFailure(errorCode: Int) {
                            Log.v(TAG, "Screenshot failed: $errorCode")
                        }
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "takeScreenshot error", e)
            }
        }
    }

    private fun processScreenshot(result: ScreenshotResult) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

        serviceScope.launch {
            var bmp: Bitmap? = null
            try {
                bmp = Bitmap.wrapHardwareBuffer(
                    result.hardwareBuffer,
                    result.colorSpace
                )?.copy(Bitmap.Config.ARGB_8888, false)

                if (bmp != null && imageAnalyzer.isAdultContent(bmp)) {
                    Log.d(TAG, "Image block triggered")
                    withContext(Dispatchers.Main) { performBlock() }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Screenshot processing error", e)
            } finally {
                try { result.hardwareBuffer.close() } catch (_: Exception) {}
                try { bmp?.recycle() } catch (_: Exception) {}
            }
        }
    }

    // ---------- Block Action ----------

    /**
     * Press BACK to exit the current app.
     *
     * Uses lastBlockTime instead of a boolean flag so that:
     * 1. The cooldown window is precise (BLOCK_COOLDOWN_MS).
     * 2. All pending debounce jobs automatically respect it.
     */
    private fun performBlock() {
        val now = System.currentTimeMillis()
        if (now - lastBlockTime < BLOCK_COOLDOWN_MS) return

        lastBlockTime = now
        textDebounceJob?.cancel() // Cancel any pending debounce
        performGlobalAction(GLOBAL_ACTION_BACK)
        Log.d(TAG, "BACK pressed - cooldown ${BLOCK_COOLDOWN_MS}ms")
    }

    // ---------- DB Keyword Observer ----------

    private fun observeKeywords() {
        serviceScope.launch {
            try {
                val dao = AppDatabase.getInstance(applicationContext).keywordDao()
                val init = dao.getAllKeywordsSync()
                textAnalyzer.updateKeywords(init.map { it.keyword })
                dao.getAllKeywords().collect { list ->
                    textAnalyzer.updateKeywords(list.map { it.keyword })
                }
            } catch (e: Exception) {
                Log.e(TAG, "observeKeywords error", e)
            }
        }
    }

    // ---------- Foreground Service ----------

    private fun startBlockerForegroundService() {
        try {
            val intent = Intent(this, BlockerForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "startForegroundService error", e)
        }
    }

    // ---------- Model Reload Receiver ----------

    private fun registerModelReloadReceiver() {
        try {
            val filter = IntentFilter(ACTION_RELOAD_MODELS)
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

    // ---------- Cleanup ----------

    override fun onInterrupt() {
        Log.w(TAG, "onInterrupt - system will auto-recover")
    }

    override fun onDestroy() {
        isRunning = false
        Log.w(TAG, "Service destroyed")
        serviceScope.cancel()
        try { unregisterReceiver(modelReloadReceiver) } catch (_: Exception) {}
        try { imageAnalyzer.close() } catch (_: Exception) {}
        super.onDestroy()
    }
}
