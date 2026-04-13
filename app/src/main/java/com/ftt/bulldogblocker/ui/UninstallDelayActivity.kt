package com.ftt.bulldogblocker.ui

import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.CountDownTimer
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.ftt.bulldogblocker.admin.DeviceAdminReceiver

/**
 * Uninstall Delay Screen — enforces a 60-second wait before uninstall.
 *
 * MUST extend AppCompatActivity — MaterialComponents theme requires it.
 *
 * Flow:
 *   User tries to uninstall → Accessibility intercepts → this screen opens
 *   → 60s countdown → "Proceed" button unlocks → admin deactivated → uninstall opens
 */
class UninstallDelayActivity : AppCompatActivity() {

    companion object {
        private const val DELAY_MS = 60_000L
        private const val TICK_MS  = 1_000L
    }

    private lateinit var tvCountdown:  TextView
    private lateinit var progressBar:  ProgressBar
    private lateinit var btnCancel:    Button
    private lateinit var btnProceed:   Button

    private var timer: CountDownTimer? = null
    private var countdownDone = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildLayout())
        startCountdown()
    }

    // ─── Layout ─────────────────────────────────────────────────────

    private fun buildLayout(): ScrollView {
        val scroll = ScrollView(this)

        val root = LinearLayout(this).apply {
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

        root.addView(TextView(this).apply {
            text     = "🐶 Bulldog Blocker"
            textSize = 26f
            setTextColor(Color.WHITE)
            gravity  = Gravity.CENTER
            layoutParams = lp()
        })

        root.addView(TextView(this).apply {
            text     = "আনইনস্টল সুরক্ষা সক্রিয়"
            textSize = 15f
            setTextColor(Color.parseColor("#AAAAAA"))
            gravity  = Gravity.CENTER
            setPadding(0, 8, 0, 40)
            layoutParams = lp()
        })

        root.addView(TextView(this).apply {
            text = "⚠️ আনইনস্টল করতে চাইলে নিচে অপেক্ষা করুন।\n" +
                   "আপনার সন্তানের সুরক্ষা সরিয়ে ফেলার আগে নিশ্চিত হন।"
            textSize = 14f
            setTextColor(Color.parseColor("#FF9800"))
            gravity  = Gravity.CENTER
            setPadding(0, 0, 0, 32)
            layoutParams = lp()
        })

        progressBar = ProgressBar(
            this, null, android.R.attr.progressBarStyleHorizontal
        ).apply {
            max = (DELAY_MS / TICK_MS).toInt()
            progress = 0
            layoutParams = lp()
        }
        root.addView(progressBar)

        tvCountdown = TextView(this).apply {
            text     = "${DELAY_MS / 1000} সেকেন্ড বাকি..."
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity  = Gravity.CENTER
            setPadding(0, 16, 0, 48)
            layoutParams = lp()
        }
        root.addView(tvCountdown)

        btnCancel = Button(this).apply {
            text = "❌ বাতিল — সুরক্ষিত থাকুন"
            setBackgroundColor(Color.parseColor("#2E7D32"))
            setTextColor(Color.WHITE)
            layoutParams = lp()
            setOnClickListener { finish() }
        }
        root.addView(btnCancel)

        // spacer
        root.addView(android.view.View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 16)
        })

        btnProceed = Button(this).apply {
            text     = "আনইনস্টল করুন (অপেক্ষা করুন...)"
            setBackgroundColor(Color.parseColor("#C62828"))
            setTextColor(Color.WHITE)
            isEnabled = false
            alpha     = 0.4f
            layoutParams = lp()
            setOnClickListener { proceedWithUninstall() }
        }
        root.addView(btnProceed)

        scroll.addView(root)
        return scroll
    }

    // ─── Countdown ──────────────────────────────────────────────────

    private fun startCountdown() {
        val totalTicks = (DELAY_MS / TICK_MS).toInt()
        timer = object : CountDownTimer(DELAY_MS, TICK_MS) {
            override fun onTick(ms: Long) {
                val secs = ms / 1000
                tvCountdown.text = "$secs সেকেন্ড বাকি..."
                progressBar.progress = totalTicks - secs.toInt()
            }
            override fun onFinish() {
                tvCountdown.text = "✅ এখন আনইনস্টল করতে পারবেন"
                progressBar.progress = totalTicks
                btnProceed.isEnabled = true
                btnProceed.alpha     = 1f
                btnProceed.text      = "আনইনস্টল করুন"
                countdownDone = true
            }
        }.start()
    }

    // ─── Proceed ────────────────────────────────────────────────────

    private fun proceedWithUninstall() {
        if (!countdownDone) return
        // Step 1: Remove Device Admin (required before uninstall)
        try {
            val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
            dpm.removeActiveAdmin(DeviceAdminReceiver.getComponentName(this))
        } catch (e: Exception) { /* already removed */ }

        // Step 2: Open system uninstall dialog
        startActivity(Intent(Intent.ACTION_DELETE).apply {
            data = Uri.parse("package:${packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        finish()
    }

    // ─── Lifecycle ──────────────────────────────────────────────────

    override fun onBackPressed() {
        // Block back button during countdown
        if (!countdownDone) return
        super.onBackPressed()
    }

    override fun onDestroy() {
        timer?.cancel()
        super.onDestroy()
    }
}
