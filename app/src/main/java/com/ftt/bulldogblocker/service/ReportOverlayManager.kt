package com.ftt.bulldogblocker.service

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/**
 * Floating overlay popup shown ON TOP of the foreground app.
 *
 * Uses TYPE_ACCESSIBILITY_OVERLAY — no SYSTEM_ALERT_WINDOW permission needed.
 * Must be created & used from an AccessibilityService context.
 *
 * Auto-dismisses after 4.5 seconds. Tap ✕ to dismiss manually.
 */
class ReportOverlayManager(private val context: Context) {

    companion object {
        private const val TAG              = "BDB_ReportOverlay"
        private const val AUTO_DISMISS_MS  = 4_500L
    }

    private val wm: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val mainHandler = Handler(Looper.getMainLooper())
    private val dismissRunnable = Runnable { hide() }
    private var currentView: android.view.View? = null

    // ── Public API ────────────────────────────────────────────────────

    /**
     * @param reportCount  current report count for this app (after increment)
     * @param maxReports   threshold before full block
     */
    fun show(reportCount: Int, maxReports: Int) {
        mainHandler.post {
            hide()   // dismiss any existing overlay first

            val view = buildView(reportCount, maxReports)
            currentView = view

            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

            val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type, flags, PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.FILL_HORIZONTAL
                y = dp(60)
            }

            try {
                wm.addView(view, params)
                mainHandler.postDelayed(dismissRunnable, AUTO_DISMISS_MS)
            } catch (e: Exception) {
                Log.e(TAG, "addView failed: ${e.message}")
                currentView = null
            }
        }
    }

    fun hide() {
        mainHandler.removeCallbacks(dismissRunnable)
        val v = currentView ?: return
        currentView = null
        try { wm.removeView(v) } catch (_: Exception) {}
    }

    fun destroy() {
        mainHandler.removeCallbacksAndMessages(null)
        hide()
    }

    // ── View builder ─────────────────────────────────────────────────

    private fun buildView(count: Int, max: Int): android.view.View {
        val remaining   = (max - count).coerceAtLeast(0)
        val pct         = ((count.toFloat() / max.toFloat()) * 100).toInt().coerceIn(0, 100)
        val isLastWarn  = remaining <= 1
        val fillColor   = if (isLastWarn) Color.parseColor("#F44336")
                          else            Color.parseColor("#FF9800")
        val borderColor = if (isLastWarn) Color.parseColor("#F44336")
                          else            Color.parseColor("#FF9800")

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(12))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#EE0D0D1F"))
                cornerRadius = dp(14).toFloat()
                setStroke(dp(2), borderColor)
            }
        }

        // ── Top row: icon + info + close ──────────────────────────────
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // Emoji
        row.addView(TextView(context).apply {
            text     = if (isLastWarn) "🚨" else "⚠️"
            textSize = 22f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(10) }
        })

        // Info block
        val info = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        info.addView(TextView(context).apply {
            text     = "🔍 রিপোর্ট  $count / $max"
            textSize = 15f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
        })
        val subText = when {
            remaining <= 0 -> "🔴 App এখন ব্লক হয়ে যাচ্ছে!"
            remaining == 1 -> "⚡ আর ১টি সনাক্তে app ব্লক হবে!"
            else           -> "আর $remaining টি সনাক্তে app ব্লক হবে"
        }
        info.addView(TextView(context).apply {
            text     = subText
            textSize = 11f
            setTextColor(Color.parseColor("#FFAB40"))
        })
        row.addView(info)

        // Close button
        row.addView(TextView(context).apply {
            text     = "✕"
            textSize = 18f
            setTextColor(Color.parseColor("#999999"))
            setPadding(dp(10), 0, dp(4), 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { hide() }
        })

        root.addView(row)

        // ── Progress bar ──────────────────────────────────────────────
        root.addView(ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            this.max = 100
            progress  = pct
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(6)
            ).apply { setMargins(0, dp(8), 0, 0) }
            progressTintList           = ColorStateList.valueOf(fillColor)
            progressBackgroundTintList = ColorStateList.valueOf(Color.parseColor("#3A3A5C"))
        })

        return root
    }

    private fun dp(v: Int): Int =
        (v * context.resources.displayMetrics.density + 0.5f).toInt()
}
