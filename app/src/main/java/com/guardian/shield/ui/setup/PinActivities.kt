package com.guardian.shield.ui.setup

import android.content.Intent
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.guardian.shield.databinding.ActivityPinSetupBinding
import com.guardian.shield.databinding.ActivityPinVerifyBinding
import com.guardian.shield.ui.settings.SettingsActivity
import com.guardian.shield.viewmodel.PinViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

// ─────────────────────────────────────────────────────────────────────
// PIN Setup
// ─────────────────────────────────────────────────────────────────────

@AndroidEntryPoint
class PinSetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPinSetupBinding
    private val viewModel: PinViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPinSetupBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.title = "Set PIN"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        setup()
        observe()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    private fun setup() {
        binding.etPin.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_NEXT) {
                binding.etConfirm.requestFocus(); true
            } else false
        }
        binding.etConfirm.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { confirmSetup(); true }
            else false
        }
        binding.btnSavePin.setOnClickListener { confirmSetup() }
    }

    private fun confirmSetup() {
        viewModel.updateInput(binding.etPin.text.toString())
        viewModel.updateConfirm(binding.etConfirm.text.toString())
        viewModel.setupPin()
    }

    private fun observe() {
        lifecycleScope.launch {
            viewModel.uiState.collectLatest { state ->
                binding.tilPin.error     = null
                binding.tilConfirm.error = null
                state.error?.let { err ->
                    if ("match" in err.lowercase()) binding.tilConfirm.error = err
                    else binding.tilPin.error = err
                }
                if (state.isVerified) {
                    Snackbar.make(binding.root, "PIN saved ✓", Snackbar.LENGTH_SHORT).show()
                    binding.root.postDelayed({ finish() }, 800)
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────
// PIN Verify
// ─────────────────────────────────────────────────────────────────────

@AndroidEntryPoint
class PinVerifyActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_DESTINATION = "destination"
        const val DEST_SETTINGS     = "settings"
        const val DEST_DISABLE      = "disable"
    }

    private lateinit var binding: ActivityPinVerifyBinding
    private val viewModel: PinViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPinVerifyBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupNumpad()
        observe()
    }

    private fun setupNumpad() {
        val np = binding.numpad
        mapOf(
            np.btn0 to "0", np.btn1 to "1", np.btn2 to "2",
            np.btn3 to "3", np.btn4 to "4", np.btn5 to "5",
            np.btn6 to "6", np.btn7 to "7", np.btn8 to "8",
            np.btn9 to "9"
        ).forEach { (btn, digit) ->
            btn.setOnClickListener {
                val cur = viewModel.uiState.value.input
                if (cur.length < 8) viewModel.updateInput(cur + digit)
            }
        }
        np.btnBackspace.setOnClickListener {
            val cur = viewModel.uiState.value.input
            if (cur.isNotEmpty()) viewModel.updateInput(cur.dropLast(1))
        }
        binding.btnConfirm.setOnClickListener { viewModel.verifyPin() }
    }

    private fun observe() {
        lifecycleScope.launch {
            viewModel.uiState.collectLatest { state ->
                updatePinDots(state.input.length)
                binding.tvError.isVisible = state.error != null
                binding.tvError.text      = state.error ?: ""
                if (state.isVerified) {
                    when (intent.getStringExtra(EXTRA_DESTINATION)) {
                        DEST_SETTINGS ->
                            startActivity(Intent(this@PinVerifyActivity, SettingsActivity::class.java))
                        else -> { /* caller decides */ }
                    }
                    finish()
                }
            }
        }
    }

    private fun updatePinDots(length: Int) {
        listOf(binding.dot1, binding.dot2, binding.dot3,
               binding.dot4, binding.dot5, binding.dot6)
            .forEachIndexed { i, dot -> dot.isActivated = i < length }
    }
}
