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

        /** Broadcast action sent from MainActivity after a new model is added. */
        const val ACTION_RELOAD_MODELS = "com.kb.blocker.RELOAD_MODELS"

        @Volatile
        var isRunning = false
    }

    /**
     * SupervisorJob: if one child coroutine crashes, the rest keep running.
     * The service stays alive even if a single coroutine fails.
     */
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private lateinit var textAnalyzer: TextAnalyzer
    private lateinit var imageAnalyzer: ImageAnalyzer
    private lateinit var prefs: PrefsManager

    private val mainHandler = Handler(Looper.getMainLooper())

    // Minimum ms between two screenshot analyses (throttle)
    @Volatile private var lastImageTime = 0L

    // While blocking, ignore new events to prevent a loop
    @Volatile private var isBlocking = false

    // Receives ACTION_RELOAD_MODELS broadcast from MainActivity
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
        if (event == null || isBlocking) return

        try {
            when (event.eventType) {
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
                AccessibilityEvent.TYPE_VIEW_SCROLLED -> {

                    // 1. Text analysis — fast, runs on the binder thread
                    if (prefs.textAnalysisEnabled) {
                        analyzeText(event)
                    }

                    // 2. Image analysis — API 30+ only, throttled, async
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                        && prefs.imageAnalysisEnabled
                    ) {
                        maybeAnalyzeImage()
                    }
                }
            }
        } catch (e: Exception) {
            // Never re-throw — service stability is top priority
            Log.e(TAG, "Event error (caught, service continues)", e)
        }
    }

    // ---------- Text Analysis ----------

    private fun analyzeText(event: AccessibilityEvent) {
        val source = event.source ?: return
        try {
            val sb = StringBuilder()
            collectText(source, sb)
            if (sb.isNotEmpty() && textAnalyzer.containsBlockedContent(sb.toString())) {
                Log.d(TAG, "Text block triggered")
                performBlock()
            }
        } finally {
            // Always recycle the root node to avoid memory leaks
            try { source.recycle() } catch (_: Exception) {}
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
        if (now - lastImageTime < prefs.imageIntervalMs) return
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
                // HardwareBuffer → software Bitmap (TFLite requires software-backed bitmap)
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
     * isBlocking is reset after 1 second to accept new events.
     */
    private fun performBlock() {
        if (isBlocking) return
        isBlocking = true
        performGlobalAction(GLOBAL_ACTION_BACK)
        mainHandler.postDelayed({ isBlocking = false }, 1000L)
    }

    // ---------- DB Keyword Observer ----------

    private fun observeKeywords() {
        serviceScope.launch {
            try {
                val dao = AppDatabase.getInstance(applicationContext).keywordDao()

                // Initial load before real-time collection starts
                val init = dao.getAllKeywordsSync()
                textAnalyzer.updateKeywords(init.map { it.keyword })

                // Collect real-time updates from Room Flow
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
