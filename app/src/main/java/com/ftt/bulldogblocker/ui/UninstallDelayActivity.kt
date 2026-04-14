package com.ftt.bulldogblocker.ui

import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.ftt.bulldogblocker.UninstallProtectionManager
import com.ftt.bulldogblocker.admin.DeviceAdminReceiver

/**
 * Uninstall Delay Screen — 3 modes (set in MainActivity):
 *
 *   🧪 TESTING  → 60-second in-screen countdown
 *   ⏱ BY_TIME   → N minutes countdown (persistent: timer survives restarts)
 *   📅 BY_DATE   → N days countdown   (persistent: timer survives restarts)
 *
 * For BY_TIME & BY_DATE the unlock time is stored persistently so the
 * user cannot bypass the timer by closing and re-opening this screen.
 */
class UninstallDelayActivity : AppCompatActivity() {

    private lateinit var tvCountdown : TextView
    private lateinit var progressBar : ProgressBar
    private lateinit var btnProceed  : Button

    private val tickHandler  = Handler(Looper.getMainLooper())
    private var countdownDone = false
    private var totalMs       = 60_000L

    private val tickRunnable = object : Runnable {
        override fun run() {
            updateCountdown()
            if (!countdownDone) tickHandler.postDelayed(this, 1_000L)
        }
    }

    // ── Lifecycle ────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Record first attempt — persists across restarts (BY_TIME / BY_DATE)
        UninstallProtectionManager.recordFirstAttempt(this)

        totalMs = UninstallProtectionManager.getTotalDelayMs(this)

        setContentView(buildLayout())
        setupBackHandler()

        if (UninstallProtectionManager.isUnlocked(this)) {
            onCountdownFinished()
        } else {
            tickHandler.post(tickRunnable)
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-check in case screen was open across the unlock boundary
        if (!countdownDone && UninstallProtectionManager.isUnlocked(this)) {
            onCountdownFinished()
        }
    }

    override fun onDestroy() {
        tickHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ── Back handler ─────────────────────────────────────────────────

    private fun setupBackHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (countdownDone) {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
                // Silently consume back press during countdown
            }
        })
    }

    // ── Countdown tick ───────────────────────────────────────────────

    private fun updateCountdown() {
        if (UninstallProtectionManager.isUnlocked(this)) {
            onCountdownFinished()
            return
        }
        val remaining = UninstallProtectionManager.getRemainingMs(this)
        val elapsed   = (totalMs - remaining).coerceAtLeast(0L)
        val progress  = ((elapsed.toDouble() / totalMs) * 10_000).toInt()

        tvCountdown.text = UninstallProtectionManager.formatRemaining(remaining)
        progressBar.progress = progress
    }

    private fun onCountdownFinished() {
        tickHandler.removeCallbacksAndMessages(null)
        countdownDone = true
        progressBar.progress = 10_000

        tvCountdown.text = "✅ অপেক্ষার সময় শেষ — এগিয়ে যেতে পারবেন"
        tvCountdown.setTextColor(Color.parseColor("#4CAF50"))

        btnProceed.isEnabled = true
        btnProceed.alpha     = 1f
        btnProceed.text      = "🗑 Uninstall করুন"
        btnProceed.setBackgroundColor(Color.parseColor("#C62828"))
    }

    // ── Uninstall ─────────────────────────────────────────────────────

    private fun proceedWithUninstall() {
        if (!countdownDone) return
        UninstallProtectionManager.resetAttempt(this)          // clear timer for next time
        try {
            val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
            dpm.removeActiveAdmin(DeviceAdminReceiver.getComponentName(this))
        } catch (_: Exception) {}
        startActivity(Intent(Intent.ACTION_DELETE).apply {
            data = Uri.parse("package:${packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        finish()
    }

    // ── Layout ────────────────────────────────────────────────────────

    private fun buildLayout(): ScrollView {
        val mode = UninstallProtectionManager.getMode(this)

        val scroll = ScrollView(this)
        val root   = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity     = Gravity.CENTER_HORIZONTAL
            setPadding(56, 100, 56, 56)
            setBackgroundColor(Color.parseColor("#1A0000"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }

        fun lp() = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )

        // Header
        root.addView(TextView(this).apply {
            text = "🐶 Bulldog Blocker"
            textSize = 24f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            layoutParams = lp()
        })

        root.addView(TextView(this).apply {
            text = "Uninstall Protection Active"
            textSize = 14f
            setTextColor(Color.parseColor("#AAAAAA"))
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 32)
            layoutParams = lp()
        })

        // Mode badge
        val (modeName, modeColor) = when (mode) {
            UninstallProtectionManager.Mode.TESTING -> "🧪 Testing Mode" to "#1565C0"
            UninstallProtectionManager.Mode.BY_TIME -> "⏱ Time Delay Mode" to "#6A1B9A"
            UninstallProtectionManager.Mode.BY_DATE -> "📅 Date Delay Mode" to "#1B5E20"
        }
        root.addView(TextView(this).apply {
            text = modeName
            textSize = 12f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(24, 8, 24, 8)
            setBackgroundColor(Color.parseColor(modeColor))
            layoutParams = lp().apply { setMargins(0, 0, 0, 24) }
        })

        // Info message
        val infoText = when (mode) {
            UninstallProtectionManager.Mode.TESTING ->
                "60 সেকেন্ড অপেক্ষা করুন।\nএরপর uninstall করা সম্ভব হবে।"
            UninstallProtectionManager.Mode.BY_TIME -> {
                // BUG FIX: আগে raw minutes দেখাতো ("120 মিনিট")।
                // এখন formatted: "2 ঘণ্টা" বা "90 মিনিট" ইত্যাদি।
                val min = UninstallProtectionManager.getDelayMinutes(this)
                val formatted = if (min < 60) "$min মিনিট"
                                else if (min % 60 == 0) "${min / 60} ঘণ্টা"
                                else "${min / 60} ঘণ্টা ${min % 60} মিনিট"
                "$formatted অপেক্ষা করুন।\nসময় শেষে uninstall সম্ভব হবে।"
            }
            UninstallProtectionManager.Mode.BY_DATE ->
                "${UninstallProtectionManager.getDelayDays(this)} দিন পর uninstall সম্ভব।\nPhone restart করলেও timer চলতে থাকবে।"
        }
        root.addView(TextView(this).apply {
            text = infoText
            textSize = 13f
            setTextColor(Color.parseColor("#FF9800"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 24)
            layoutParams = lp()
        })

        // Progress bar (max = 10000 for smooth display)
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 10_000
            progress = 0
            layoutParams = lp()
        }
        root.addView(progressBar)

        // Countdown text
        tvCountdown = TextView(this).apply {
            text = UninstallProtectionManager.formatRemaining(
                UninstallProtectionManager.getRemainingMs(this@UninstallDelayActivity)
            )
            textSize = 17f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 48)
            layoutParams = lp()
        }
        root.addView(tvCountdown)

        // Cancel button
        root.addView(Button(this).apply {
            text = "Cancel — সুরক্ষিত থাকুন"
            setBackgroundColor(Color.parseColor("#2E7D32"))
            setTextColor(Color.WHITE)
            layoutParams = lp()
            setOnClickListener { finish() }
        })

        root.addView(android.view.View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 16
            )
        })

        // Proceed button (disabled initially)
        btnProceed = Button(this).apply {
            text = "⏳ অপেক্ষা করুন..."
            setBackgroundColor(Color.parseColor("#C62828"))
            setTextColor(Color.WHITE)
            isEnabled = false
            alpha = 0.4f
            layoutParams = lp()
            setOnClickListener { proceedWithUninstall() }
        }
        root.addView(btnProceed)

        scroll.addView(root)
        return scroll
    }
}
