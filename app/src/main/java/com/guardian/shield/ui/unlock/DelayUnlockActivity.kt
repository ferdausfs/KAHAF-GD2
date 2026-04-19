// app/src/main/java/com/guardian/shield/ui/unlock/DelayUnlockActivity.kt
package com.guardian.shield.ui.unlock

import android.os.Bundle
import android.os.CountDownTimer
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.guardian.shield.databinding.ActivityDelayUnlockBinding
import com.guardian.shield.viewmodel.PinViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class DelayUnlockActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDelayUnlockBinding
    private val pinViewModel: PinViewModel by viewModels()
    private var countdownTimer: CountDownTimer? = null
    private var delaySecs = 30

    // FIX #6: Proper back press callback
    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            if (binding.groupCountdown.isVisible) {
                // Block during countdown — do nothing
                return
            }
            // Allow after countdown (but user should use PIN)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivityDelayUnlockBinding.inflate(layoutInflater)
        setContentView(binding.root)

        onBackPressedDispatcher.addCallback(this, backCallback)

        delaySecs = intent.getIntExtra("delay_seconds", 30).coerceIn(5, 300)
        startCountdown()
        setupNumpad()
        observePinState()
    }

    override fun onDestroy() {
        countdownTimer?.cancel()
        super.onDestroy()
    }

    // ── Phase 1: Countdown ─────────────────────────────────────────────

    private fun startCountdown() {
        binding.groupCountdown.isVisible = true
        binding.groupPin.isVisible       = false

        countdownTimer = object : CountDownTimer(delaySecs * 1000L, 1000L) {
            override fun onTick(remaining: Long) {
                val secs = (remaining / 1000).toInt() + 1
                binding.tvCountdown.text           = secs.toString()
                binding.progressCountdown.progress =
                    ((secs.toFloat() / delaySecs) * 100).toInt()
            }
            override fun onFinish() {
                binding.tvCountdown.text           = "0"
                binding.progressCountdown.progress = 0
                transitionToPin()
            }
        }.start()
    }

    private fun transitionToPin() {
        binding.groupCountdown.isVisible = false
        binding.groupPin.isVisible       = true
    }

    // ── Phase 2: PIN numpad ────────────────────────────────────────────

    private fun setupNumpad() {
        val np = binding.numpad
        mapOf(
            np.btn0 to "0", np.btn1 to "1", np.btn2 to "2",
            np.btn3 to "3", np.btn4 to "4", np.btn5 to "5",
            np.btn6 to "6", np.btn7 to "7", np.btn8 to "8",
            np.btn9 to "9"
        ).forEach { (btn, digit) ->
            btn.setOnClickListener {
                val cur = pinViewModel.uiState.value.input
                if (cur.length < 8) pinViewModel.updateInput(cur + digit)
            }
        }
        np.btnBackspace.setOnClickListener {
            val cur = pinViewModel.uiState.value.input
            if (cur.isNotEmpty()) pinViewModel.updateInput(cur.dropLast(1))
        }
        binding.btnPinConfirm.setOnClickListener { pinViewModel.verifyPin() }
    }

    private fun observePinState() {
        lifecycleScope.launch {
            pinViewModel.uiState.collectLatest { state ->
                updateDots(state.input.length)
                binding.tvPinError.isVisible = state.error != null
                binding.tvPinError.text      = state.error ?: ""
                if (state.error != null) shakePin()
                if (state.isVerified) finishAffinity()
            }
        }
    }

    private fun updateDots(length: Int) {
        listOf(binding.dot1, binding.dot2, binding.dot3,
               binding.dot4, binding.dot5, binding.dot6)
            .forEachIndexed { i, dot -> dot.isActivated = i < length }
    }

    private fun shakePin() {
        binding.pinView.animate()
            .translationX(14f).setDuration(60).withEndAction {
                binding.pinView.animate()
                    .translationX(-14f).setDuration(60).withEndAction {
                        binding.pinView.animate()
                            .translationX(0f).setDuration(60).start()
                    }.start()
            }.start()
    }

    // ── Block hardware keys during countdown ──────────────────────────

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && binding.groupCountdown.isVisible)
            return true
        return super.onKeyDown(keyCode, event)
    }
}