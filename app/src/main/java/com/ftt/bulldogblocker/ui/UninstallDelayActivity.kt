package com.ftt.bulldogblocker.ui

import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.CountDownTimer
import android.view.Gravity
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.ftt.bulldogblocker.admin.DeviceAdminReceiver

/**
 * Uninstall Delay Screen — enforces a 60-second wait before uninstall.
 *
 * Flow:
 *   User tries to uninstall -> Accessibility intercepts -> this screen opens
 *   -> 60s countdown -> "Proceed" button unlocks -> admin deactivated -> uninstall dialog
 */
class UninstallDelayActivity : AppCompatActivity() {

    companion object {
        private const val DELAY_MS = 60_000L
        private const val TICK_MS  = 1_000L
        private const val KEY_REMAINING_MS  = "remaining_ms"
        private const val KEY_COUNTDOWN_DONE = "countdown_done"
    }

    private lateinit var tvCountdown : TextView
    private lateinit var progressBar : ProgressBar
    private lateinit var btnProceed  : Button

    private var timer: CountDownTimer? = null
    private var countdownDone = false
    // FIX: Track remaining time so screen rotation resumes from correct position
    private var remainingMs: Long = DELAY_MS

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // FIX: Restore state across rotation — without this, every rotation resets to 60s
        if (savedInstanceState != null) {
            remainingMs   = savedInstanceState.getLong(KEY_REMAINING_MS, DELAY_MS)
            countdownDone = savedInstanceState.getBoolean(KEY_COUNTDOWN_DONE, false)
        }
        setContentView(buildLayout())
        setupBackHandler()
        if (countdownDone) {
            // Already finished — just unlock the button immediately
            onCountdownFinished()
        } else {
            startCountdown(remainingMs)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(KEY_REMAINING_MS, remainingMs)
        outState.putBoolean(KEY_COUNTDOWN_DONE, countdownDone)
    }

    override fun onDestroy() {
        timer?.cancel()
        super.onDestroy()
    }

    // Block hardware back button during countdown — use modern API
    private fun setupBackHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (countdownDone) {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
                // If countdown not done, silently consume the back press
            }
        })
    }

    private fun buildLayout(): ScrollView {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
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
            text = "Bulldog Blocker"
            textSize = 26f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            layoutParams = lp()
        })
        root.addView(TextView(this).apply {
            text = "Uninstall Protection Active"
            textSize = 15f
            setTextColor(Color.parseColor("#AAAAAA"))
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 40)
            layoutParams = lp()
        })
        root.addView(TextView(this).apply {
            text = "Please wait before proceeding.\nMake sure you want to remove content protection."
            textSize = 14f
            setTextColor(Color.parseColor("#FF9800"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 32)
            layoutParams = lp()
        })

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = (DELAY_MS / TICK_MS).toInt()
            progress = 0
            layoutParams = lp()
        }
        root.addView(progressBar)

        tvCountdown = TextView(this).apply {
            text = "${DELAY_MS / 1000} seconds remaining..."
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 48)
            layoutParams = lp()
        }
        root.addView(tvCountdown)

        root.addView(Button(this).apply {
            text = "Cancel — Stay Protected"
            setBackgroundColor(Color.parseColor("#2E7D32"))
            setTextColor(Color.WHITE)
            layoutParams = lp()
            setOnClickListener { finish() }
        })

        root.addView(android.view.View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 16)
        })

        btnProceed = Button(this).apply {
            text = "Uninstall (please wait...)"
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

    private fun startCountdown(fromMs: Long = DELAY_MS) {
        val totalTicks = (DELAY_MS / TICK_MS).toInt()
        timer = object : CountDownTimer(fromMs, TICK_MS) {
            override fun onTick(ms: Long) {
                remainingMs = ms
                val secs = ms / 1000
                tvCountdown.text = "$secs seconds remaining..."
                progressBar.progress = totalTicks - secs.toInt()
            }
            override fun onFinish() {
                remainingMs = 0
                onCountdownFinished()
            }
        }.start()
    }

    private fun onCountdownFinished() {
        val totalTicks = (DELAY_MS / TICK_MS).toInt()
        tvCountdown.text = "You may now uninstall"
        progressBar.progress = totalTicks
        btnProceed.isEnabled = true
        btnProceed.alpha = 1f
        btnProceed.text = "Uninstall"
        countdownDone = true
    }

    private fun proceedWithUninstall() {
        if (!countdownDone) return
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
}
