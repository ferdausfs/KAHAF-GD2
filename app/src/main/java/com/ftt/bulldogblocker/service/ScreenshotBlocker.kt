package com.ftt.bulldogblocker.service

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import android.view.Display
import androidx.annotation.RequiresApi
import com.ftt.bulldogblocker.ThresholdManager
import com.ftt.bulldogblocker.ml.ContentClassifier
import kotlinx.coroutines.*

/**
 * ⚠️ IMPORTANT: This class is in a SEPARATE file on purpose.
 * See BlockerAccessibilityService for explanation.
 */
@RequiresApi(Build.VERSION_CODES.R)
class ScreenshotBlocker(
    private val service: AccessibilityService,
    private val scope: CoroutineScope,
    private val onAdultDetected: (String) -> Unit
) {
    companion object {
        private const val TAG = "BDB_Screenshot"
        private const val INTERVAL_MS  = 300L    // প্রতি 300ms — আগে 500ms ছিল

        // ── BUG FIX: SCREENSHOT_THRESHOLD আর hardcode নেই ─────────────
        // আগে: private const val SCREENSHOT_THRESHOLD = 0.22f
        // এখন: analyze() প্রতিবার ThresholdManager.getScreenshot() পড়ে।
        // UI থেকে পরিবর্তন করলে সাথে সাথে কাজ করে।
        // ──────────────────────────────────────────────────────────────

        private const val COOLDOWN_MS  = 3_000L
        private const val BUSY_TIMEOUT = 4_000L  // busy stuck হলে এর পরে force reset
    }

    @Volatile private var busy          = false
    @Volatile private var busyStartTime = 0L
    @Volatile private var lastBlock     = 0L
    private var job: Job?               = null
    private var classifier: ContentClassifier? = null

    fun start(c: ContentClassifier) {
        classifier = c
        job?.cancel()
        job = scope.launch {
            while (isActive) {
                delay(INTERVAL_MS)
                val now = System.currentTimeMillis()
                if (now - lastBlock < COOLDOWN_MS) continue

                // busy stuck হলে force reset (capture callback miss হলে)
                if (busy && now - busyStartTime > BUSY_TIMEOUT) {
                    Log.w(TAG, "busy timeout — force reset")
                    busy = false
                }
                if (busy) continue

                capture()
            }
        }
        Log.d(TAG, "Screenshot loop started — interval=${INTERVAL_MS}ms, threshold=dynamic(ThresholdManager)")
    }

    fun updateClassifier(c: ContentClassifier) { classifier = c }

    fun stop() { job?.cancel(); job = null }

    fun resetCooldown() { lastBlock = System.currentTimeMillis() }

    // ── Capture ───────────────────────────────────────────────────────

    @SuppressLint("NewApi")
    private fun capture() {
        busy = true
        busyStartTime = System.currentTimeMillis()
        try {
            service.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                service.mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        analyze(result)
                    }
                    override fun onFailure(errorCode: Int) {
                        busy = false
                        if (errorCode != 2) // 2 = TAKE_SCREENSHOT_ERROR_SECURE_WINDOW (skip logging)
                            Log.w(TAG, "takeScreenshot failed: code=$errorCode")
                    }
                }
            )
        } catch (e: Exception) {
            busy = false
            Log.e(TAG, "takeScreenshot error", e)
        }
    }

    // ── Analyze ───────────────────────────────────────────────────────

    @SuppressLint("NewApi")
    private fun analyze(result: AccessibilityService.ScreenshotResult) {
        scope.launch {
            var full: Bitmap?    = null
            var cropped: Bitmap? = null
            try {
                val hb = result.hardwareBuffer ?: run { busy = false; return@launch }

                full = Bitmap.wrapHardwareBuffer(hb, result.colorSpace)
                    ?.copy(Bitmap.Config.ARGB_8888, false)
                    ?: run { busy = false; return@launch }

                // ── Crop: status bar (top 7%) + nav bar (bottom 9%) বাদ দাও ──
                // Full screen screenshot-এ UI chrome content কে dilute করে।
                // Content area crop করলে model আরো accurate হয়।
                val w = full.width
                val h = full.height
                val topCut    = (h * 0.07f).toInt()
                val bottomCut = (h * 0.09f).toInt()
                val cropH = h - topCut - bottomCut

                cropped = if (cropH > 100) {
                    Bitmap.createBitmap(full, 0, topCut, w, cropH)
                } else full

                // ── Black screen check: FLAG_SECURE বা SurfaceView video ──
                // যদি screen mostly কালো হয়, skip করো (classify করে লাভ নেই)
                if (isMostlyBlack(cropped)) {
                    return@launch
                }

                val c = classifier ?: return@launch
                if (!c.isLoaded()) return@launch

                // FIX: threshold প্রতিবার ThresholdManager থেকে পড়া হয় (আর hardcoded 0.22f নয়)
                val threshold = ThresholdManager.getScreenshot(service.applicationContext)
                val res = c.classifyWithThreshold(cropped, threshold)
                if (res.isAdult) {
                    Log.w(TAG, "Adult detected: unsafe=${res.unsafeScore} (threshold=$threshold)")
                    lastBlock = System.currentTimeMillis()
                    withContext(Dispatchers.Main) {
                        onAdultDetected("ML: adult content শনাক্ত (${(res.unsafeScore * 100).toInt()}%)")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "analyze error", e)
            } finally {
                try { result.hardwareBuffer?.close() } catch (_: Exception) {}
                if (cropped !== full) cropped?.recycle()
                full?.recycle()
                busy = false
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────

    /**
     * Screen mostly black = FLAG_SECURE app বা video SurfaceView।
     * 224x224 sample-এ যদি avg brightness < 15 হয় তাহলে skip।
     */
    private fun isMostlyBlack(bmp: Bitmap): Boolean {
        val sample = Bitmap.createScaledBitmap(bmp, 32, 32, false)
        var totalBrightness = 0L
        val pixels = IntArray(32 * 32)
        sample.getPixels(pixels, 0, 32, 0, 0, 32, 32)
        sample.recycle()
        for (p in pixels) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8)  and 0xFF
            val b =  p         and 0xFF
            totalBrightness += (r + g + b)
        }
        val avg = totalBrightness / (pixels.size * 3)
        return avg < 15  // avg brightness < 15/255 = mostly black
    }
}
