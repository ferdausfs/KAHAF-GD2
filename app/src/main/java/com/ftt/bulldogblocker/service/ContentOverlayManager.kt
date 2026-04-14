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
 * ContentOverlayManager v8.3
 *
 * Adult content detect হলে দুটো overlay দেখায়:
 *
 * ① Main overlay (full-screen):
 *      "বন্ধ করো" → onBlock() → app block + BlockScreen
 *      "এড়িয়ে যাই" → main overlay hide → ② Report dialog
 *
 * ② Report dialog (bottom sheet style):
 *      "✅ হ্যাঁ, সঠিক ছিল" → onBlock() → app block
 *      "❌ না, ভুল ছিল"     → onFalse() → blocker 1 min pause
 *
 * App পালটালে উভয় overlay auto-hide হয় (hideAll() call)।
 */
class ContentOverlayManager(private val service: AccessibilityService) {

    companion object {
        private const val TAG = "BDB_ContentOverlay"
    }

    private val wm: WindowManager =
        service.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val mainHandler = Handler(Looper.getMainLooper())

    private var mainView: LinearLayout?   = null
    private var reportView: LinearLayout? = null

    // isShowing: main overlay — @Volatile কারণ service thread থেকে read হয়
    @Volatile
    var isShowing: Boolean = false
        private set

    // reportShowing: শুধু main thread থেকে access — volatile লাগে না
    private var reportShowing: Boolean = false

    // BUG FIX: overlay দেখানোর পর minimum এই সময় hideAll() ignore করো।
    // Facebook-এর internal fragment/activity event-এ overlay তৎক্ষণাৎ hide হয়ে যেত।
    private var shownAtMs = 0L
    private val MIN_VISIBLE_MS = 3_000L

    private val isAnyShowing: Boolean
        get() = isShowing || reportShowing

    // ── Main overlay ──────────────────────────────────────────────────

    /**
     * @param reason   কেন detect হলো
     * @param onBlock  "বন্ধ করো" বা report-এ "✅ সঠিক" → app block করো
     * @param onFalse  report-এ "❌ ভুল" → blocker pause করো
     */
    fun show(
        reason:  String,
        onBlock: () -> Unit,
        onFalse: () -> Unit
    ) {
        mainHandler.post {
            if (isAnyShowing) return@post
            try {
                val view = buildMainView(reason, onBlock, onFalse)
                wm.addView(view, fullScreenParams())
                mainView  = view
                isShowing = true
                shownAtMs = System.currentTimeMillis()
                Log.d(TAG, "Main overlay shown")
            } catch (e: Exception) {
                Log.e(TAG, "Main overlay show failed", e)
            }
        }
    }

    fun hide() {
        mainHandler.post {
            removeView(mainView)
            mainView  = null
            isShowing = false
            Log.d(TAG, "Main overlay hidden")
        }
    }

    // ── Report dialog ─────────────────────────────────────────────────

    private fun showReport(onBlock: () -> Unit, onFalse: () -> Unit) {
        mainHandler.post {
            if (reportShowing) return@post
            try {
                val view = buildReportView(onBlock, onFalse)
                wm.addView(view, bottomSheetParams())
                reportView    = view
                reportShowing = true
                Log.d(TAG, "Report dialog shown")
            } catch (e: Exception) {
                Log.e(TAG, "Report dialog show failed", e)
            }
        }
    }

    private fun hideReport() {
        mainHandler.post {
            removeView(reportView)
            reportView    = null
            reportShowing = false
            Log.d(TAG, "Report dialog hidden")
        }
    }

    // ── Public: app পালটালে সব hide ──────────────────────────────────

    fun hideAll() {
        // BUG FIX: overlay দেখানোর সাথে সাথে hide হয়ে যাওয়া রোধ করতে
        // minimum visible time check। Facebook-এর internal event-এ
        // overlay flash করে মিলিয়ে যেত এই check ছাড়া।
        if (isShowing && System.currentTimeMillis() - shownAtMs < MIN_VISIBLE_MS) {
            Log.d(TAG, "hideAll() ignored — overlay shown <${MIN_VISIBLE_MS}ms ago")
            return
        }
        hide()
        hideReport()
    }

    fun destroy() {
        mainHandler.removeCallbacksAndMessages(null)
        // BUG FIX: destroy()-এ hideAll() call করলে mainHandler.post() করে,
        // কিন্তু removeCallbacksAndMessages()-এর পরে post করা callback execute হয় না।
        // তাই সরাসরি removeView() call করতে হবে — main thread-এ call হচ্ছে (onDestroy)।
        removeView(mainView);   mainView  = null; isShowing     = false
        removeView(reportView); reportView = null; reportShowing = false
    }

    // ── Main overlay view ─────────────────────────────────────────────

    private fun buildMainView(
        reason:  String,
        onBlock: () -> Unit,
        onFalse: () -> Unit
    ): LinearLayout {
        val ctx = service.applicationContext
        val d   = ctx.resources.displayMetrics.density
        fun Int.dp() = (this * d).toInt()

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity     = Gravity.CENTER
            setPadding(48.dp(), 0, 48.dp(), 0)
            setBackgroundColor(Color.parseColor("#E8000000"))
        }

        // ── Icon ──────────────────────────────────────────────────────
        root.addView(TextView(ctx).apply {
            text     = "🔞"
            textSize = 64f
            gravity  = Gravity.CENTER
        })

        root.addView(vspace(ctx, 16.dp()))

        // ── Title ─────────────────────────────────────────────────────
        root.addView(TextView(ctx).apply {
            text      = "Adult কন্টেন্ট শনাক্ত হয়েছে"
            textSize  = 22f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            gravity   = Gravity.CENTER
        })

        root.addView(vspace(ctx, 8.dp()))

        // ── Reason ────────────────────────────────────────────────────
        root.addView(TextView(ctx).apply {
            text     = reason
            textSize = 13f
            setTextColor(Color.parseColor("#FFBBBB"))
            gravity  = Gravity.CENTER
        })

        root.addView(vspace(ctx, 48.dp()))

        // ── Buttons ───────────────────────────────────────────────────
        val btnRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER
        }

        // "এড়িয়ে যাই" → main overlay hide → report dialog
        btnRow.addView(Button(ctx).apply {
            text     = "এড়িয়ে যাই"
            textSize = 14f
            setBackgroundColor(Color.parseColor("#424242"))
            setTextColor(Color.WHITE)
            setPadding(32.dp(), 14.dp(), 32.dp(), 14.dp())
            setOnClickListener {
                hide()
                showReport(onBlock = onBlock, onFalse = onFalse)
            }
        })

        btnRow.addView(hspace(ctx, 16.dp()))

        // "বন্ধ করো" → সরাসরি block
        btnRow.addView(Button(ctx).apply {
            text     = "বন্ধ করো 🔒"
            textSize = 14f
            setBackgroundColor(Color.parseColor("#C62828"))
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            setPadding(32.dp(), 14.dp(), 32.dp(), 14.dp())
            setOnClickListener {
                hide()
                onBlock()
            }
        })

        root.addView(btnRow)
        return root
    }

    // ── Report dialog view ────────────────────────────────────────────

    private fun buildReportView(
        onBlock: () -> Unit,
        onFalse: () -> Unit
    ): LinearLayout {
        val ctx = service.applicationContext
        val d   = ctx.resources.displayMetrics.density
        fun Int.dp() = (this * d).toInt()

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity     = Gravity.CENTER_HORIZONTAL
            setPadding(24.dp(), 20.dp(), 24.dp(), 40.dp())
            setBackgroundColor(Color.parseColor("#1C1C3A"))
        }

        // ── Drag handle ───────────────────────────────────────────────
        root.addView(android.view.View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(40.dp(), 5.dp()).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                setMargins(0, 0, 0, 16.dp())
            }
            setBackgroundColor(Color.parseColor("#FFFFFF50"))
        })

        // ── Title ─────────────────────────────────────────────────────
        root.addView(TextView(ctx).apply {
            text      = "🔍  এটা কি সত্যিই Adult Content ছিল?"
            textSize  = 16f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            gravity   = Gravity.CENTER
        })

        root.addView(vspace(ctx, 6.dp()))

        root.addView(TextView(ctx).apply {
            text     = "তোমার উত্তর blocker-কে সঠিক সিদ্ধান্ত নিতে সাহায্য করবে"
            textSize = 12f
            setTextColor(Color.parseColor("#AAAAAA"))
            gravity  = Gravity.CENTER
        })

        root.addView(vspace(ctx, 24.dp()))

        // ── Buttons ───────────────────────────────────────────────────
        val btnRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER
        }

        // "❌ না" → blocker 1 min pause
        btnRow.addView(Button(ctx).apply {
            text     = "❌  না, ভুল ছিল"
            textSize = 13f
            setBackgroundColor(Color.parseColor("#1B5E20"))
            setTextColor(Color.WHITE)
            setPadding(24.dp(), 12.dp(), 24.dp(), 12.dp())
            setOnClickListener {
                hideReport()
                onFalse()
            }
        })

        btnRow.addView(hspace(ctx, 12.dp()))

        // "✅ সঠিক" → app block
        btnRow.addView(Button(ctx).apply {
            text     = "✅  হ্যাঁ, সঠিক ছিল"
            textSize = 13f
            setBackgroundColor(Color.parseColor("#B71C1C"))
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            setPadding(24.dp(), 12.dp(), 24.dp(), 12.dp())
            setOnClickListener {
                hideReport()
                onBlock()
            }
        })

        root.addView(btnRow)
        return root
    }

    // ── WindowManager params ──────────────────────────────────────────

    private fun fullScreenParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        // BUG FIX: FLAG_NOT_FOCUSABLE সরানো হয়েছে।
        // FLAG_NOT_FOCUSABLE মানে touch event system-এ pass-through হয়ে যায় —
        // overlay-র বাইরে touch করলেও overlay dismiss হয়ে যায়, button click কাজ করে না।
        // Full-screen block overlay-তে এই flag থাকা উচিত নয়।
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    )

    private fun bottomSheetParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        // BUG FIX: FLAG_NOT_FOCUSABLE সরানো — report dialog buttons touchable থাকতে হবে
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.BOTTOM
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private fun removeView(view: LinearLayout?) {
        view ?: return
        try { wm.removeView(view) } catch (e: Exception) { Log.w(TAG, "removeView: ${e.message}") }
    }

    /** Vertical spacer (MATCH_PARENT width) */
    private fun vspace(ctx: Context, heightPx: Int) = android.view.View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, heightPx
        )
    }

    /** Horizontal spacer (WRAP_CONTENT height) */
    private fun hspace(ctx: Context, widthPx: Int) = android.view.View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(
            widthPx, LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }
}
