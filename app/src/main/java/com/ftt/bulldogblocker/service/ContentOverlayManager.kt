package com.ftt.bulldogblocker.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * ContentOverlayManager — Adult content শনাক্ত হলে app-এর উপর warning overlay দেখায়।
 *
 * Behavior:
 *   count < threshold  → "সতর্কতা X/N" warning overlay — app চলতে থাকে
 *   count >= threshold → BlockerAccessibilityService app block করে (এই class ব্যবহার হয় না)
 *
 * TYPE_ACCESSIBILITY_OVERLAY ব্যবহার করে:
 *   → SYSTEM_ALERT_WINDOW permission লাগে না
 *   → AccessibilityService থেকে directly কাজ করে
 *
 * BUG FIX v8.1: show() ও hide() internally mainHandler.post() করে।
 *   → যেকোনো thread থেকে safe call করা যাবে
 *   → wm.addView/removeView সবসময় main thread-এ চলে
 *
 * User "বুঝলাম" বাটন দিয়ে dismiss করতে পারবে।
 * App বদলালে overlay auto-hide হয়।
 */
class ContentOverlayManager(private val service: AccessibilityService) {

    companion object {
        private const val TAG = "BDB_ContentOverlay"
    }

    private val wm: WindowManager =
        service.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    // BUG FIX v8.1: Main thread handler — wm.addView/removeView must be on main thread
    private val mainHandler = Handler(Looper.getMainLooper())

    private var overlayView: LinearLayout? = null

    @Volatile
    var isShowing: Boolean = false
        private set

    // ── Public API ────────────────────────────────────────────────────

    /**
     * @param reason    কেন block হলো (content detail)
     * @param count     এই app-এ এখন পর্যন্ত কতবার detection হয়েছে
     * @param threshold কতবার হলে app block হবে
     */
    fun show(reason: String, count: Int, threshold: Int) {
        // BUG FIX v8.1: mainHandler.post দিয়ে main thread-এ dispatch
        mainHandler.post {
            if (isShowing) return@post
            try {
                val view = buildView(reason, count, threshold)
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                )
                wm.addView(view, params)
                overlayView = view
                isShowing = true
                Log.d(TAG, "Warning overlay shown ($count/$threshold)")
            } catch (e: Exception) {
                Log.e(TAG, "Overlay show failed", e)
            }
        }
    }

    fun hide() {
        // BUG FIX v8.1: mainHandler.post দিয়ে main thread-এ dispatch
        mainHandler.post {
            val v = overlayView ?: return@post
            try { wm.removeView(v) } catch (e: Exception) { Log.w(TAG, "removeView error", e) }
            overlayView = null
            isShowing = false
            Log.d(TAG, "Overlay hidden")
        }
    }

    fun destroy() {
        mainHandler.removeCallbacksAndMessages(null)
        hide()
    }

    // ── View builder ──────────────────────────────────────────────────

    private fun buildView(reason: String, count: Int, threshold: Int): LinearLayout {
        val ctx = service.applicationContext
        val d   = ctx.resources.displayMetrics.density
        fun Int.dp() = (this * d).toInt()

        val remaining = threshold - count  // আর কতবার বাকি আছে block-এর আগে

        // Background: semi-transparent dark overlay — content টা cover হয়ে যাবে
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity     = Gravity.CENTER
            setPadding(48.dp(), 0, 48.dp(), 0)
            setBackgroundColor(Color.parseColor("#E8000000"))
        }

        // ── Warning badge: "সতর্কতা X / N" ─────────────────────────
        val badge = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER
            setPadding(24.dp(), 10.dp(), 24.dp(), 10.dp())
            background  = roundRect("#B71C1C", 50.dp())
        }
        badge.addView(TextView(ctx).apply {
            text      = "⚠️  সতর্কতা  $count / $threshold"
            textSize  = 16f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
        })
        root.addView(badge)

        root.addView(space(ctx, 20.dp()))

        // ── Main icon ────────────────────────────────────────────────
        root.addView(TextView(ctx).apply {
            text     = "🔞"
            textSize = 56f
            gravity  = Gravity.CENTER
        })

        root.addView(space(ctx, 12.dp()))

        // ── Title ────────────────────────────────────────────────────
        root.addView(TextView(ctx).apply {
            text      = "Adult কন্টেন্ট শনাক্ত হয়েছে"
            textSize  = 20f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            gravity   = Gravity.CENTER
        })

        root.addView(space(ctx, 8.dp()))

        // ── Reason detail ─────────────────────────────────────────────
        root.addView(TextView(ctx).apply {
            text     = reason
            textSize = 13f
            setTextColor(Color.parseColor("#FFBBBB"))
            gravity  = Gravity.CENTER
        })

        root.addView(space(ctx, 20.dp()))

        // ── Block warning: আর কতবার বাকি ────────────────────────────
        val warnColor = if (remaining <= 1) "#FF5252" else "#FFAB40"
        val warnText  = when {
            remaining <= 1 -> "⛔ পরেরবার detect হলেই app block হবে!"
            else           -> "আর $remaining বার পর app ব্লক হয়ে যাবে"
        }
        root.addView(TextView(ctx).apply {
            text     = warnText
            textSize = 13f
            setTextColor(Color.parseColor(warnColor))
            setTypeface(null, Typeface.BOLD)
            gravity  = Gravity.CENTER
        })

        root.addView(space(ctx, 32.dp()))

        // ── Dismiss button ────────────────────────────────────────────
        root.addView(Button(ctx).apply {
            text     = "বুঝলাম, বন্ধ করো"
            setBackgroundColor(Color.parseColor("#C62828"))
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(40.dp(), 14.dp(), 40.dp(), 14.dp())
            setOnClickListener { hide() }
        })

        return root
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private fun space(ctx: Context, heightPx: Int) = android.view.View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, heightPx
        )
    }

    /** Simple rounded rectangle background drawable */
    private fun roundRect(hexColor: String, cornerPx: Int): android.graphics.drawable.GradientDrawable {
        return android.graphics.drawable.GradientDrawable().apply {
            shape       = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = cornerPx.toFloat()
            setColor(Color.parseColor(hexColor))
        }
    }
}
