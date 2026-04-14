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

@RequiresApi(Build.VERSION_CODES.R)
class ScreenshotBlocker(
    private val service: AccessibilityService,
    private val scope: CoroutineScope,
    private val isForegroundPkgWhitelisted: () -> Boolean = { false },
    // reason, imageHash, unsafeScore, showReportButtons
    private val onAdultDetected: (String, Long, Float, Boolean) -> Unit
) {
    companion object {
        private const val TAG = "BDB_Screenshot"

        // v7 BUG FIX #4: 300ms → 1500ms
        // আগে: প্রতি 300ms screenshot = প্রতি সেকেন্ডে ৩টা ML inference।
        //       ফলে: ৩টা screenshot → ৩টা report → threshold পার → block screen।
        //       পুরোটা ৯০০ms-এর মধ্যে — user popup দেখার সময়ই পায় না।
        // এখন: ১৫০০ms ব্যবধানে → user ৪-৫ সেকেন্ড popup দেখার সময় পাবে।
        // Battery drain ও কমবে (৩x কম screenshot = ৩x কম ML inference)।
        private const val INTERVAL_MS  = 1_500L

        // v7 FIX: Cooldown বাড়ানো হয়েছে — popup দেখার পর্যাপ্ত সময়।
        private const val COOLDOWN_MS  = 5_000L
        private const val BUSY_TIMEOUT = 4_000L

        const val REPORT_THRESHOLD = 0.85f

        // v7 BUG FIX #5: Uniform color variance threshold।
        // Mostly-black ছাড়াও সাদা/solid color screen skip করতে হবে।
        // Variance < 150 = সব pixel প্রায় একই রঙ = loading screen / splash / solid UI।
        private const val UNIFORM_VARIANCE_THRESHOLD = 150f
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

                if (isForegroundPkgWhitelisted()) {
                    Log.d(TAG, "Skip — foreground app is whitelisted or our own package")
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
        Log.d(TAG, "Screenshot loop started — interval=${INTERVAL_MS}ms")
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

                // v7 BUG FIX #5: shouldSkipFrame() — black + uniform color check।
                // আগে: শুধু isMostlyBlack() → সাদা/solid color screen ML-এ যেত → false positive।
                // এখন: pixel variance দিয়ে solid color detect করে skip করা হয়।
                if (shouldSkipFrame(cropped)) {
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
                val sexyAlone = ThresholdManager.getSexyAlone(ctx)
                val res = c.classifyWithThreshold(cropped, threshold, sexyAlone)

                if (res.isAdult) {
                    val score = res.unsafeScore
                    Log.w(TAG, "Adult detected: unsafe=${score} (threshold=$threshold, hash=$hash)")
                    lastBlock = System.currentTimeMillis()

                    val isConfirmed = FalsePositiveDB.isConfirmedTrue(ctx, hash)
                    val showReport  = score < REPORT_THRESHOLD && !isConfirmed

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

    // ── Frame skip logic ──────────────────────────────────────────────

    /**
     * v7 BUG FIX #5: shouldSkipFrame() — isMostlyBlack() replacement।
     *
     * দুইটা condition-এ frame skip করা হয়:
     *   1. Mostly black  (avg brightness < 15) — screen off / dark overlay
     *   2. Uniform color (variance < threshold) — white loading screen / splash / solid UI
     *
     * Optimized: single-pass mean+variance calculation (Welford's online algorithm)।
     * আগে ৩টা FloatArray allocate + ৩টা loop → এখন কোনো extra allocation নেই।
     */
    private fun shouldSkipFrame(bmp: Bitmap): Boolean {
        val sample = Bitmap.createScaledBitmap(bmp, 32, 32, false)
        val pixels = IntArray(32 * 32)
        sample.getPixels(pixels, 0, 32, 0, 0, 32, 32)
        if (sample !== bmp) sample.recycle()

        // Single-pass: compute brightness avg + variance for R,G,B simultaneously
        // Using Welford's online algorithm — no extra FloatArray allocations
        var rMean = 0.0; var rM2 = 0.0
        var gMean = 0.0; var gM2 = 0.0
        var bMean = 0.0; var bM2 = 0.0
        var totalBrightness = 0L

        for (i in pixels.indices) {
            val p  = pixels[i]
            val r  = ((p shr 16) and 0xFF).toDouble()
            val g  = ((p shr 8)  and 0xFF).toDouble()
            val b  = ( p         and 0xFF).toDouble()
            totalBrightness += (r + g + b).toLong()

            val n  = (i + 1).toDouble()
            val dr = r - rMean; rMean += dr / n; rM2 += dr * (r - rMean)
            val dg = g - gMean; gMean += dg / n; gM2 += dg * (g - gMean)
            val db = b - bMean; bMean += db / n; bM2 += db * (b - bMean)
        }

        // Check 1: mostly black
        val avg = totalBrightness / (pixels.size * 3)
        if (avg < 15) return true

        // Check 2: uniform color (variance of all 3 channels combined)
        val totalVariance = ((rM2 + gM2 + bM2) / pixels.size).toFloat()
        if (totalVariance < UNIFORM_VARIANCE_THRESHOLD) {
            Log.d(TAG, "Skip — uniform color frame (variance=${"%.1f".format(totalVariance)})")
            return true
        }

        return false
    }
}
