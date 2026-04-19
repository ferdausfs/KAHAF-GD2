// app/src/main/java/com/guardian/shield/ui/settings/SettingsActivity.kt
package com.guardian.shield.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.guardian.shield.databinding.ActivitySettingsBinding
import com.guardian.shield.service.detection.AiDetector
import com.guardian.shield.service.accessibility.GuardianAccessibilityService
import com.guardian.shield.viewmodel.SettingsViewModel
import com.guardian.shield.viewmodel.KeywordViewModel
import com.guardian.shield.viewmodel.AppListViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

@AndroidEntryPoint
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val settingsVm: SettingsViewModel by viewModels()
    private val keywordVm: KeywordViewModel by viewModels()
    private val appListVm: AppListViewModel by viewModels()

    private var isUpdatingFromState = false

    // Model file picker
    private val modelPickLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { importModel(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Settings"
        setupRecyclerViews()
        setupViews()
        observeSettings()
        observeKeywords()
        observeAppLists()
    }

    override fun onResume() {
        super.onResume()
        // Refresh model status when coming back
        updateModelStatus()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    private fun setupRecyclerViews() {
        binding.rvKeywords.apply {
            layoutManager = LinearLayoutManager(this@SettingsActivity)
            adapter = KeywordAdapter { id -> keywordVm.removeKeyword(id) }
        }
        binding.rvBlockedApps.apply {
            layoutManager = LinearLayoutManager(this@SettingsActivity)
            adapter = AppRuleAdapter { pkg -> appListVm.removeRule(pkg) }
        }
        binding.rvWhitelistedApps.apply {
            layoutManager = LinearLayoutManager(this@SettingsActivity)
            adapter = AppRuleAdapter { pkg -> appListVm.removeRule(pkg) }
        }
    }

    private fun setupViews() {
        // Switch listeners with guard
        binding.switchKeyword.setOnCheckedChangeListener { _, checked ->
            if (!isUpdatingFromState) {
                settingsVm.toggleKeyword(checked)
            }
        }
        binding.switchAi.setOnCheckedChangeListener { _, checked ->
            if (!isUpdatingFromState) {
                // BUG FIX: Pass modelAvailable so ViewModel can broadcast AFTER pref is saved.
                // Previously, broadcast was sent before DataStore write completed (race condition).
                val modelAvail = AiDetector.isModelAvailable(this)
                settingsVm.toggleAi(checked, modelAvailable = modelAvail)
                if (checked && !modelAvail) {
                    settingsVm.showMessage("⚠️ Upload a .tflite model first!")
                }
            }
        }
        // BUG FIX: switchStrictMode listener was missing — toggle clicked but nothing happened.
        // Now properly calls toggleStrictMode() which saves pref + notifies service.
        binding.switchStrictMode.setOnCheckedChangeListener { _, checked ->
            if (!isUpdatingFromState) {
                settingsVm.toggleStrictMode(checked)
            }
        }

        // Delay unlock slider
        binding.sliderDelay.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val secs = value.toInt()
                settingsVm.setDelaySeconds(secs)
                binding.tvDelayValue.text = "${secs}s"
            }
        }

        // AI threshold slider
        binding.sliderAiThreshold.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                settingsVm.setAiThreshold(value)
                binding.tvAiThresholdValue.text = "${(value * 100).toInt()}%"
            }
        }

        // Model upload
        binding.btnUploadModel.setOnClickListener {
            modelPickLauncher.launch("*/*")  // Accept any file type for .tflite
        }

        // Keyword add
        binding.btnAddKeyword.setOnClickListener {
            keywordVm.addKeyword()
        }
        binding.etKeywordInput.setOnEditorActionListener { _, _, _ ->
            keywordVm.addKeyword()
            true
        }
        binding.etKeywordInput.doAfterTextChanged { text ->
            keywordVm.updateInput(text.toString())
        }

        // App picker buttons
        binding.btnAddBlockedApp.setOnClickListener {
            showAppPickerDialog(isWhitelist = false)
        }
        binding.btnAddWhitelistedApp.setOnClickListener {
            showAppPickerDialog(isWhitelist = true)
        }
    }

    private fun showAppPickerDialog(isWhitelist: Boolean) {
        val state = appListVm.uiState.value
        if (state.installedApps.isEmpty()) {
            appListVm.loadInstalledApps()
            Snackbar.make(binding.root, "Loading app list…", Snackbar.LENGTH_SHORT).show()
            return
        }
        val already = if (isWhitelist) state.whitelistedApps.map { it.packageName }.toSet()
                      else state.blockedApps.map { it.packageName }.toSet()
        val available = state.installedApps.filter { it.packageName !in already }
        if (available.isEmpty()) {
            Snackbar.make(binding.root, "No more apps to add", Snackbar.LENGTH_SHORT).show()
            return
        }
        val names = available.map { it.appName }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(if (isWhitelist) "Add Trusted App" else "Block App")
            .setItems(names) { _, idx ->
                val app = available[idx]
                if (isWhitelist) appListVm.addToWhitelist(app)
                else appListVm.addToBlockedList(app)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun observeSettings() {
        lifecycleScope.launch {
            settingsVm.uiState.collectLatest { state ->
                isUpdatingFromState = true

                binding.switchKeyword.isChecked   = state.isKeywordEnabled
                binding.switchAi.isChecked        = state.isAiEnabled
                // BUG FIX: switchStrictMode was never synced from state — always showed wrong value.
                binding.switchStrictMode.isChecked = state.isStrictMode
                binding.sliderDelay.value         = state.delayUnlockSeconds.toFloat()
                binding.tvDelayValue.text         = "${state.delayUnlockSeconds}s"
                binding.sliderAiThreshold.value   = state.aiThreshold
                binding.tvAiThresholdValue.text   = "${(state.aiThreshold * 100).toInt()}%"
                binding.layoutAiOptions.isVisible = state.isAiEnabled

                isUpdatingFromState = false

                updateModelStatus()

                state.snackMessage?.let { msg ->
                    Snackbar.make(binding.root, msg, Snackbar.LENGTH_SHORT).show()
                    settingsVm.clearMessage()
                }
            }
        }
    }

    private fun updateModelStatus() {
        val modelAvail = AiDetector.isModelAvailable(this)
        val modelFile = AiDetector.modelFile(this)

        if (modelAvail) {
            val sizeKB = modelFile.length() / 1024
            binding.tvModelStatus.text = "✓ Model loaded (${sizeKB}KB)"
            binding.tvModelStatus.setTextColor(getColor(android.R.color.holo_green_dark))
        } else {
            binding.tvModelStatus.text = "⚠️ No model — upload .tflite file"
            binding.tvModelStatus.setTextColor(getColor(android.R.color.holo_orange_dark))
        }
    }

    private fun observeKeywords() {
        lifecycleScope.launch {
            keywordVm.uiState.collectLatest { state ->
                binding.tvKeywordError.isVisible = state.errorMessage != null
                binding.tvKeywordError.text      = state.errorMessage ?: ""
                (binding.rvKeywords.adapter as? KeywordAdapter)?.submitList(state.keywords)
            }
        }
    }

    private fun observeAppLists() {
        lifecycleScope.launch {
            appListVm.uiState.collectLatest { state ->
                (binding.rvBlockedApps.adapter as? AppRuleAdapter)?.submitList(state.blockedApps)
                (binding.rvWhitelistedApps.adapter as? AppRuleAdapter)?.submitList(state.whitelistedApps)
            }
        }
    }

    // KEY FIX: Proper model import with validation
    private fun importModel(uri: Uri) {
        Timber.d("Importing model from: $uri")
        lifecycleScope.launch {
            try {
                val dest = AiDetector.modelFile(this@SettingsActivity)

                // Copy file
                withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { input ->
                        dest.outputStream().use { output ->
                            val bytes = input.copyTo(output)
                            Timber.d("Model copied: $bytes bytes → ${dest.absolutePath}")
                        }
                    } ?: throw Exception("Could not open input stream")
                }

                // Validate file
                if (!dest.exists() || dest.length() < 1024) {
                    dest.delete()
                    settingsVm.showMessage("❌ Invalid model file (too small)")
                    return@launch
                }

                val sizeKB = dest.length() / 1024
                Timber.d("Model file saved: ${sizeKB}KB at ${dest.absolutePath}")

                // Update UI
                updateModelStatus()

                // If AI is enabled, immediately reload in service
                val aiEnabled = settingsVm.uiState.value.isAiEnabled
                if (aiEnabled) {
                    Timber.d("AI is enabled — sending RELOAD_MODEL to service")
                    sendBroadcast(
                        Intent(GuardianAccessibilityService.ACTION_RELOAD_MODEL).apply {
                            setPackage(packageName)
                        }
                    )
                    settingsVm.showMessage("✓ Model imported (${sizeKB}KB) — AI reloading...")
                } else {
                    settingsVm.showMessage("✓ Model imported (${sizeKB}KB) — Enable AI Detection to activate")
                }

            } catch (e: Exception) {
                Timber.e(e, "Model import FAILED")
                settingsVm.showMessage("❌ Import failed: ${e.message}")
            }
        }
    }
}