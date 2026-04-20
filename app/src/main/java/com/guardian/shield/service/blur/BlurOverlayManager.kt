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
import android.widget.FrameLayout
import android.widget.TextView
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BlurOverlayManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "Guardian_BlurOverlay"
        private const val OVERLAY_ALPHA = 1.0f
        private const val BLUR_RADIUS = 30
    }

    @Volatile var isShowing = false
        private set

    @Volatile private var overlayView: View? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()

    fun show() {
        mainHandler.post {
            synchronized(lock) {
                if (isShowing) return@post
                addOverlay()
            }
        }
    }

    fun hide() {
        mainHandler.post {
            synchronized(lock) {
                if (!isShowing) return@post
                removeOverlay()
            }
        }
    }

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

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val wm2 = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                    if (wm2.isCrossWindowBlurEnabled) {
                        flags = flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                        blurBehindRadius = BLUR_RADIUS
                        Timber.d("$TAG gaussian blur enabled")
                    }
                }
            }

            wm.addView(view, params)
            overlayView = view
            isShowing = true
            Timber.d("$TAG overlay SHOWN")
        } catch (e: SecurityException) {
            Timber.e(e, "$TAG No SYSTEM_ALERT_WINDOW permission")
            isShowing = false
            overlayView = null
        } catch (e: Exception) {
            Timber.e(e, "$TAG addOverlay failed")
            isShowing = false
            overlayView = null
        }
    }

    private fun removeOverlay() {
        try {
            val view = overlayView ?: return
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm.removeViewImmediate(view)
            Timber.d("$TAG overlay HIDDEN")
        } catch (e: Exception) {
            Timber.w("$TAG removeOverlay: ${e.message}")
        } finally {
            overlayView = null
            isShowing = false
        }
    }

    private fun buildOverlayView(): View {
        return FrameLayout(context).apply {
            setBackgroundColor(Color.argb((OVERLAY_ALPHA * 255).toInt(), 15, 15, 20))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            contentDescription = null

            val messageView = TextView(context).apply {
                text = "🛡️\n\nContent Blurred\nby Guardian Shield"
                setTextColor(Color.WHITE)
                textSize = 22f
                gravity = Gravity.CENTER
                setPadding(48, 48, 48, 48)
            }
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER }
            addView(messageView, lp)
        }
    }
}