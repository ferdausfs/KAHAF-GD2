// app/src/main/java/com/guardian/shield/service/blur/BlurOverlayManager.kt
package com.guardian.shield.service.blur

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * BlurOverlayManager — shows / hides a full-screen blur overlay using WindowManager.
 *
 * Uses TYPE_APPLICATION_OVERLAY (requires SYSTEM_ALERT_WINDOW permission, already
 * granted for the block overlay feature).
 *
 * Visual effect:
 *   • Android 12+ (API 31): real gaussian blur behind the overlay via blurBehindRadius
 *   • All versions: dark frosted-glass overlay (semi-transparent black)
 *
 * All WindowManager calls are posted to the main thread — safe to call from any thread.
 */
@Singleton
class BlurOverlayManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val TAG = "Guardian_BlurOverlay"
        private const val OVERLAY_ALPHA = 0.82f       // 82% opaque dark overlay
        private const val BLUR_RADIUS   = 30           // API 31+ gaussian radius
    }

    @Volatile var isShowing = false
        private set

    private var overlayView: View? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // ── Public API ─────────────────────────────────────────────────────

    /**
     * Show the blur overlay. Safe to call repeatedly — no-op if already showing.
     * Must be called with SYSTEM_ALERT_WINDOW permission granted.
     */
    fun show() {
        if (isShowing) return
        mainHandler.post {
            if (isShowing) return@post    // double-check after post
            addOverlay()
        }
    }

    /**
     * Hide the blur overlay. Safe to call repeatedly — no-op if not showing.
     */
    fun hide() {
        if (!isShowing) return
        mainHandler.post {
            if (!isShowing) return@post
            removeOverlay()
        }
    }

    // ── Internal ───────────────────────────────────────────────────────

    private fun addOverlay() {
        try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

            val view = buildOverlayView()

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 0; y = 0

                // Android 12+ real blur behind the overlay window
                // BUG FIX: Check isCrossWindowBlurEnabled() first — many OEMs (Samsung, Xiaomi)
                // disable cross-window blur. Without this check the flag is set but has no effect,
                // making it look like the overlay is just dark with no blur.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val wm2 = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                    val blurSupported = wm2.isCrossWindowBlurEnabled
                    if (blurSupported) {
                        @Suppress("DEPRECATION")
                        flags = flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                        blurBehindRadius = BLUR_RADIUS
                        Timber.d("$TAG cross-window blur enabled — using gaussian blur")
                    } else {
                        Timber.d("$TAG cross-window blur disabled by device/OEM — using dark overlay only")
                    }
                }
            }

            wm.addView(view, params)
            overlayView = view
            isShowing = true
            Timber.d("$TAG overlay SHOWN")

        } catch (e: SecurityException) {
            Timber.e(e, "$TAG No SYSTEM_ALERT_WINDOW permission — overlay blocked")
        } catch (e: Exception) {
            Timber.e(e, "$TAG addOverlay failed")
        }
    }

    private fun removeOverlay() {
        try {
            val view = overlayView ?: return
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm.removeViewImmediate(view)
            overlayView = null
            isShowing = false
            Timber.d("$TAG overlay HIDDEN")
        } catch (e: Exception) {
            // View may have already been removed by the system
            overlayView = null
            isShowing = false
            Timber.w("$TAG removeOverlay exception (may be harmless): ${e.message}")
        }
    }

    /**
     * Build the visual overlay view.
     *
     * Design: deep dark frosted glass.
     * • Pure black fill at OVERLAY_ALPHA for strong content occlusion.
     * • On API 31+, the window-level gaussian blur adds the frosted effect behind.
     * • On older APIs the dark fill alone is sufficient to block content.
     */
    private fun buildOverlayView(): View {
        return View(context).apply {
            // Dark semi-transparent fill — occludes any content underneath
            setBackgroundColor(
                Color.argb(
                    (OVERLAY_ALPHA * 255).toInt(),
                    0, 0, 0
                )
            )
            // Accessibility: mark as non-interactive decoration
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            contentDescription = null
        }
    }
}
