package com.kb.blocker.accessibility

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
import com.kb.blocker.analyzer.ImageAnalyzer
import com.kb.blocker.analyzer.TextAnalyzer
import com.kb.blocker.data.PrefsManager
import com.kb.blocker.data.db.AppDatabase
import com.kb.blocker.service.BlockerForegroundService
import kotlinx.coroutines.*

class ContentBlockerService : AccessibilityService() {

    companion object {
        private const val TAG = "ContentBlockerService"

        /** ServiceWatchdog এই flag দেখে service alive কিনা */
        @Volatile
        var isRunning = false
    }

    /**
     * SupervisorJob: একটা child coroutine crash করলে
     * বাকিগুলো চলতে থাকবে — service মরবে না।
     */
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private lateinit var textAnalyzer: TextAnalyzer
    private lateinit var imageAnalyzer: ImageAnalyzer
    private lateinit var prefs: PrefsManager

    private val mainHandler = Handler(Looper.getMainLooper())

    // Image analysis throttle — এর মধ্যে দ্বিতীয় analysis হবে না
    @Volatile private var lastImageTime = 0L

    // Block চলাকালীন নতুন event ignore করো (loop prevent)
    @Volatile private var isBlocking = false

    // ─── Lifecycle ────────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        isRunning = true
        Log.d(TAG, "✅ Accessibility Service connected")

        try {
            prefs = PrefsManager(this)
            textAnalyzer = TextAnalyzer(this)
            imageAnalyzer = ImageAnalyzer(this)

            // DB থেকে keywords real-time observe করো
            observeKeywords()

            // Foreground service চালু করো → process alive থাকবে
            startBlockerForegroundService()

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

                    // 1️⃣ Text analysis — fast, sync
                    if (prefs.textAnalysisEnabled) {
                        analyzeText(event)
                    }

                    // 2️⃣ Image analysis — API 30+, async, throttled
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                        prefs.imageAnalysisEnabled
                    ) {
                        maybeAnalyzeImage()
                    }
                }
            }
        } catch (e: Exception) {
            // কখনো re-throw করব না — service এর stability সবার আগে
            Log.e(TAG, "Event error (caught, service continues)", e)
        }
    }

    // ─── Text Analysis ────────────────────────────────────────────

    private fun analyzeText(event: AccessibilityEvent) {
        val source = event.source ?: return
        try {
            val sb = StringBuilder()
            collectText(source, sb)
            if (sb.isNotEmpty() && textAnalyzer.containsBlockedContent(sb.toString())) {
                Log.d(TAG, "🚫 Text block triggered")
                performBlock()
            }
        } finally {
            try { source.recycle() } catch (_: Exception) {}
        }
    }

    /** Node tree থেকে সব visible text collect করো */
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

    // ─── Image Analysis ───────────────────────────────────────────

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
                            handleScreenshot(result)
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

    private fun handleScreenshot(result: ScreenshotResult) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

        serviceScope.launch {
            var bmp: Bitmap? = null
            try {
                // HardwareBuffer → software Bitmap (TFLite needs software)
                bmp = Bitmap.wrapHardwareBuffer(
                    result.hardwareBuffer,
                    result.colorSpace
                )?.copy(Bitmap.Config.ARGB_8888, false)

                if (bmp != null && imageAnalyzer.isAdultContent(bmp)) {
                    Log.d(TAG, "🚫 Image block triggered")
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

    // ─── Block Action ─────────────────────────────────────────────

    /**
     * Back press দিয়ে current app থেকে বের হয়ে যাও।
     * 1 second পর isBlocking reset হয় — নতুন event গ্রহণ করবে।
     */
    private fun performBlock() {
        if (isBlocking) return
        isBlocking = true
        performGlobalAction(GLOBAL_ACTION_BACK)
        mainHandler.postDelayed({ isBlocking = false }, 1000L)
    }

    // ─── DB Observe ───────────────────────────────────────────────

    private fun observeKeywords() {
        serviceScope.launch {
            try {
                val dao = AppDatabase.getInstance(applicationContext).keywordDao()

                // Initial load
                val init = dao.getAllKeywordsSync()
                textAnalyzer.updateKeywords(init.map { it.keyword })

                // Real-time updates
                dao.getAllKeywords().collect { list ->
                    textAnalyzer.updateKeywords(list.map { it.keyword })
                }
            } catch (e: Exception) {
                Log.e(TAG, "observeKeywords error", e)
            }
        }
    }

    // ─── Foreground Service ───────────────────────────────────────

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

    // ─── Cleanup ──────────────────────────────────────────────────

    override fun onInterrupt() {
        // AccessibilityService নিজেই recover করে — এখানে কিছু করার নেই
        Log.w(TAG, "⚠️ onInterrupt — system will auto-recover")
    }

    override fun onDestroy() {
        isRunning = false
        Log.w(TAG, "⚠️ Service destroyed")
        serviceScope.cancel()
        try { imageAnalyzer.close() } catch (_: Exception) {}
        super.onDestroy()
    }
}
