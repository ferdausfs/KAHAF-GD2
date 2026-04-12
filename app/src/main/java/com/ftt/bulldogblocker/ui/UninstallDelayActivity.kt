package com.ftt.bulldogblocker.ui

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import com.ftt.bulldogblocker.R
import com.ftt.bulldogblocker.admin.DeviceAdminReceiver

/**
 * Uninstall Delay Screen.
 *
 * Shown when the user attempts to uninstall the app.
 * Forces a 60-second wait, then offers two buttons:
 *   ① Cancel  → finish (stay protected)
 *   ② Proceed → deactivate Device Admin, then open real uninstall
 *
 * Flow:
 *   Uninstall tap → this screen → 60s countdown → user confirms →
 *   admin deactivated → normal uninstall dialog opens.
 */
class UninstallDelayActivity : Activity() {

    companion object {
        private const val DELAY_MS = 60_000L    // 60 seconds — adjust as needed
        private const val TICK_MS  = 1_000L
    }

    private lateinit var tvTitle:       TextView
    private lateinit var tvCountdown:   TextView
    private lateinit var tvWarning:     TextView
    private lateinit var progressBar:   ProgressBar
    private lateinit var btnCancel:     Button
    private lateinit var btnProceed:    Button

    private var timer: CountDownTimer? = null
    private var countdownDone = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ── Build UI programmatically (no XML needed) ──
        buildUi()
        startCountdown()
    }

    private fun buildUi() {
        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(64, 120, 64, 64)
            gravity = android.view.Gravity.CENTER
            setBackgroundColor(android.graphics.Color.parseColor("#1A1A2E"))
        }

        tvTitle = TextView(this).apply {
            text = "🐶 Bulldog Blocker"
            textSize = 26f
            setTextColor(android.graphics.Color.WHITE)
            gravity = android.view.Gravity.CENTER
        }

        val tvSub = TextView(this).apply {
            text = "আনইনস্টল সুরক্ষা সক্রিয়"
            textSize = 16f
            setTextColor(android.graphics.Color.parseColor("#AAAAAA"))
            gravity = android.view.Gravity.CENTER
            setPadding(0, 8, 0, 40)
        }

        tvWarning = TextView(this).apply {
            text = "⚠️ আনইনস্টল করতে চাইলে নিচে অপেক্ষা করুন।\n" +
                   "আপনার সন্তানের সুরক্ষা সরিয়ে ফেলার আগে নিশ্চিত হন।"
            textSize = 15f
            setTextColor(android.graphics.Color.parseColor("#FF9800"))
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 32)
        }

        progressBar = ProgressBar(this, null,
            android.R.attr.progressBarStyleHorizontal).apply {
            max = (DELAY_MS / TICK_MS).toInt()
            progress = 0
        }

        tvCountdown = TextView(this).apply {
            text = "${DELAY_MS / 1000} সেকেন্ড বাকি..."
            textSize = 18f
            setTextColor(android.graphics.Color.WHITE)
            gravity = android.view.Gravity.CENTER
            setPadding(0, 16, 0, 48)
        }

        btnCancel = Button(this).apply {
            text = "❌ বাতিল করুন (সুরক্ষিত থাকুন)"
            setBackgroundColor(android.graphics.Color.parseColor("#2E7D32"))
            setTextColor(android.graphics.Color.WHITE)
            setOnClickListener { finish() }
        }

        btnProceed = Button(this).apply {
            text = "আনইনস্টল করুন"
            setBackgroundColor(android.graphics.Color.parseColor("#C62828"))
            setTextColor(android.graphics.Color.WHITE)
            isEnabled = false
            alpha = 0.4f
            setOnClickListener { proceedWithUninstall() }
        }

        root.apply {
            addView(tvTitle)
            addView(tvSub)
            addView(tvWarning)
            addView(progressBar)
            addView(tvCountdown)
            addView(btnCancel)
            addView(android.view.View(context).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 16)
            })
            addView(btnProceed)
        }

        setContentView(root)
    }

    private fun startCountdown() {
        val total = (DELAY_MS / TICK_MS).toInt()
        timer = object : CountDownTimer(DELAY_MS, TICK_MS) {
            override fun onTick(millisUntilFinished: Long) {
                val secsLeft = millisUntilFinished / 1000
                tvCountdown.text = "$secsLeft সেকেন্ড বাকি..."
                progressBar.progress = total - secsLeft.toInt()
            }

            override fun onFinish() {
                tvCountdown.text = "✅ এখন আনইনস্টল করা সম্ভব"
                progressBar.progress = total
                btnProceed.isEnabled = true
                btnProceed.alpha = 1f
                countdownDone = true
            }
        }.start()
    }

    private fun proceedWithUninstall() {
        if (!countdownDone) return

        // Step 1: Deactivate Device Admin (required before uninstall)
        val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        dpm.removeActiveAdmin(DeviceAdminReceiver.getComponentName(this))

        // Step 2: Open standard uninstall dialog
        val intent = Intent(Intent.ACTION_DELETE).apply {
            data = android.net.Uri.parse("package:com.ftt.bulldogblocker")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
        finish()
    }

    override fun onBackPressed() {
        // Prevent back button from skipping the countdown
        if (!countdownDone) return
        super.onBackPressed()
    }

    override fun onDestroy() {
        timer?.cancel()
        super.onDestroy()
    }
}
