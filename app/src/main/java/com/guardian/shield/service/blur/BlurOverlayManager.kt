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
import timber.log.Timber

/**
 * BlurOverlayManager — full-screen overlay using TYPE_ACCESSIBILITY_OVERLAY.
 *
 * TYPE_ACCESSIBILITY_OVERLAY is exclusively for AccessibilityServices —
 * NO extra permissions needed (no SYSTEM_ALERT_WINDOW).
 *
 * ⚠️  Must be created with the AccessibilityService as [context].
 *     Call from onServiceConnected(), store as a field.
 */
class BlurOverlayManager(private val context: Context) {

    companion object {
        private const val TAG         = "Guardian_BlurOverlay"
        private const val BLUR_RADIUS = 25   // API 31+ gaussian radius
    }

    @Volatile var isShowing = false
        private set

    private var overlayView: View? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    fun show() {
        if (isShowing) return
        mainHandler.post {
            if (isShowing) return@post
            addOverlay()
        }
    }

    fun hide() {
        if (!isShowing) return
        mainHandler.post {
            if (!isShowing) return@post
            removeOverlay()
        }
    }

    private fun addOverlay() {
        try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

            val view = View(context).apply {
                // Deep dark fill — hides content on all API levels
                setBackgroundColor(Color.argb(217, 0, 0, 0))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START

                // Android 12+: real GPU blur behind the overlay
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    @Suppress("DEPRECATION")
                    flags = flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                    blurBehindRadius = BLUR_RADIUS
                }
            }

            wm.addView(view, params)
            overlayView = view
            isShowing   = true
            Timber.d("$TAG SHOWN (API ${Build.VERSION.SDK_INT})")

        } catch (e: Exception) {
            Timber.e(e, "$TAG addOverlay FAILED: ${e.message}")
        }
    }

    private fun removeOverlay() {
        try {
            overlayView?.let {
                val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                wm.removeViewImmediate(it)
            }
        } catch (e: Exception) {
            Timber.w("$TAG removeOverlay (harmless): ${e.message}")
        } finally {
            overlayView = null
            isShowing   = false
            Timber.d("$TAG HIDDEN")
        }
    }
}
