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
import com.ftt.bulldogblocker.ml.FalsePositiveDB
import com.ftt.bulldogblocker.ml.ImageHashUtil
import kotlinx.coroutines.*

/**
 * ⚠️ IMPORTANT: This class is in a SEPARATE file on purpose.
 * See BlockerAccessibilityService for explanation.
 */
@RequiresApi(Build.VERSION_CODES.R)
class ScreenshotBlocker(
    private val service: AccessibilityService,
    private val scope: CoroutineScope,
    // BUG FIX #1: Whitelist check lambda — BlockerAccessibilityService থেকে আসে।
    // Foreground app whitelisted হলে screenshot নেওয়াই হয় না।
    // Default: { false } → backward compat (না পাঠালে সব block হয়)
    private val isForegroundPkgWhitelisted: () -> Boolean = { false },
    // reason, imageHash, unsafeScore, showReportButtons
    private val onAdultDetected: (String, Long, Float, Boolean) -> Unit
) {
    companion object {
        private const val TAG = "BDB_Screenshot"
        private const val INTERVAL_MS  = 300L

        private const val COOLDOWN_MS     = 3_000L
        private const val BUSY_TIMEOUT    = 4_000L

        const val REPORT_THRESHOLD = 0.85f
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

                // BUG FIX #1: Whitelisted app-এ screenshot নেওয়া বন্ধ করো
                // এই lambda BlockerAccessibilityService থেকে আসে এবং
                // currentForegroundPkg ∈ whitelistCache কিনা check করে।
                // আগে: whitelist করা app খোলা থাকলেও ML loop চলতো → block হতো
                // এখন: whitelisted হলে পুরো iteration skip
                if (isForegroundPkgWhitelisted()) {
                    Log.d(TAG, "Skip — foreground app is whitelisted")
                    continue
                }

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
                        if (errorCode != 2)
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

                val w = full.width
                val h = full.height
                val topCut    = (h * 0.07f).toInt()
                val bottomCut = (h * 0.09f).toInt()
                val cropH = h - topCut - bottomCut

                cropped = if (cropH > 100) {
                    Bitmap.createBitmap(full, 0, topCut, w, cropH)
                } else full

                if (isMostlyBlack(cropped)) {
                    return@launch
                }

                val c = classifier ?: return@launch
                if (!c.isLoaded()) return@launch

                val hash = ImageHashUtil.computeHash(cropped)
                val ctx  = service.applicationContext

                if (FalsePositiveDB.isFalsePositive(ctx, hash)) {
                    Log.d(TAG, "Skip — false positive DB match (hash=$hash)")
                    return@launch
                }

                val threshold = ThresholdManager.getScreenshot(ctx)
                val res = c.classifyWithThreshold(cropped, threshold)

                if (res.isAdult) {
                    val score = res.unsafeScore
                    Log.w(TAG, "Adult detected: unsafe=${score} (threshold=$threshold, hash=$hash)")
                    lastBlock = System.currentTimeMillis()

                    val isConfirmed  = FalsePositiveDB.isConfirmedTrue(ctx, hash)
                    val showReport   = score < REPORT_THRESHOLD && !isConfirmed

                    withContext(Dispatchers.Main) {
                        onAdultDetected(
                            "ML: adult content শনাক্ত (${(score * 100).toInt()}%)",
                            hash,
                            score,
                            showReport
                        )
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

    private fun isMostlyBlack(bmp: Bitmap): Boolean {
        val sample = Bitmap.createScaledBitmap(bmp, 32, 32, false)
        var totalBrightness = 0L
        val pixels = IntArray(32 * 32)
        sample.getPixels(pixels, 0, 32, 0, 0, 32, 32)
        if (sample !== bmp) sample.recycle()
        for (p in pixels) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8)  and 0xFF
            val b =  p         and 0xFF
            totalBrightness += (r + g + b)
        }
        val avg = totalBrightness / (pixels.size * 3)
        return avg < 15
    }
}
