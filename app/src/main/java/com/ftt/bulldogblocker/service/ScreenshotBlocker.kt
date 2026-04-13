package com.ftt.bulldogblocker.service

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import android.view.Display
import androidx.annotation.RequiresApi
import com.ftt.bulldogblocker.ml.ContentClassifier
import kotlinx.coroutines.*

/**
 * ⚠️ IMPORTANT: This class is in a SEPARATE file on purpose.
 *
 * TakeScreenshotCallback and ScreenshotResult are API 30+ inner types.
 * If they are referenced anywhere in BlockerAccessibilityService.kt (even inside
 * a version-guarded method), ART will fail to verify the entire class on API < 30
 * → instant VerifyError / NoClassDefFoundError crash.
 *
 * Solution: isolate ALL API 30+ references here, annotated @RequiresApi(30).
 * BlockerAccessibilityService only instantiates this class after checking the API level.
 */
@RequiresApi(Build.VERSION_CODES.R)
class ScreenshotBlocker(
    private val service: AccessibilityService,
    private val scope: CoroutineScope,
    private val onAdultDetected: (String) -> Unit
) {
    companion object {
        private const val TAG = "BDB_Screenshot"
        private const val INTERVAL_MS  = 500L
        private const val COOLDOWN_MS  = 3_000L
    }

    @Volatile private var busy        = false
    @Volatile private var lastBlock   = 0L
    private var job: Job?             = null
    private var classifier: ContentClassifier? = null

    fun start(c: ContentClassifier) {
        classifier = c
        job?.cancel()
        job = scope.launch {
            while (isActive) {
                delay(INTERVAL_MS)
                val now = System.currentTimeMillis()
                if (now - lastBlock < COOLDOWN_MS) continue
                if (busy) continue
                capture()
            }
        }
        Log.d(TAG, "Screenshot loop started")
    }

    fun updateClassifier(c: ContentClassifier) {
        classifier = c
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    fun resetCooldown() { lastBlock = System.currentTimeMillis() }

    @SuppressLint("NewApi")
    private fun capture() {
        busy = true
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
                    }
                }
            )
        } catch (e: Exception) {
            busy = false
            Log.e(TAG, "takeScreenshot error", e)
        }
    }

    @SuppressLint("NewApi")
    private fun analyze(result: AccessibilityService.ScreenshotResult) {
        scope.launch {
            var bmp: Bitmap? = null
            try {
                // FIX: hardwareBuffer can be null on some devices/ROMs — guard with ?.
                val hb = result.hardwareBuffer ?: run {
                    busy = false
                    return@launch
                }
                bmp = Bitmap.wrapHardwareBuffer(hb, result.colorSpace)
                    ?.copy(Bitmap.Config.ARGB_8888, false)

                val c = classifier
                if (bmp != null && c != null && c.isLoaded()) {
                    val res = c.classify(bmp)
                    if (res.isAdult) {
                        Log.w(TAG, "Adult content detected")
                        lastBlock = System.currentTimeMillis()
                        withContext(Dispatchers.Main) {
                            onAdultDetected("ML: adult content শনাক্ত হয়েছে")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "analyze error", e)
            } finally {
                try { result.hardwareBuffer?.close() } catch (_: Exception) {}
                try { bmp?.recycle() }               catch (_: Exception) {}
                busy = false
            }
        }
    }
}
