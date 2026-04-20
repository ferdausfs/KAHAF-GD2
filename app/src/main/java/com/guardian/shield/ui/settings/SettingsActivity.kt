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
import java.io.File

@AndroidEntryPoint
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val settingsVm: SettingsViewModel by viewModels()
    private val keywordVm: KeywordViewModel by viewModels()
    private val appListVm: AppListViewModel by viewModels()

    private var isUpdatingFromState = false

    // FIX #1: OpenDocument is more reliable for .tflite files than GetContent
    // GetContent uses MIME filtering which greys out .tflite in many file managers
    private val modelPickLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
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
        binding.switchKeyword.setOnCheckedChangeListener { _, checked ->
            if (!isUpdatingFromState) settingsVm.toggleKeyword(checked)
        }

        binding.switchAi.setOnCheckedChangeListener { _, checked ->
            if (!isUpdatingFromState) {
                val modelAvail = AiDetector.isModelAvailable(this)
                if (checked && !modelAvail) {
                    isUpdatingFromState = true
                    binding.switchAi.isChecked = false
                    isUpdatingFromState = false
                    settingsVm.showMessage("⚠️ Upload a .tflite model first!")
                    return@setOnCheckedChangeListener
                }
                settingsVm.toggleAi(checked, modelAvailable = modelAvail)
            }
        }

        binding.switchStrictMode.setOnCheckedChangeListener { _, checked ->
            if (!isUpdatingFromState) {
                if (checked) {
                    MaterialAlertDialogBuilder(this)
                        .setTitle("Enable Strict Mode?")
                        .setMessage("Only apps in your Trusted Apps list will work. All other apps (except launcher) will be blocked. Make sure your essential apps are in the Trusted list first.")
                        .setPositiveButton("Enable") { _, _ ->
                            settingsVm.toggleStrictMode(true)
                        }
                        .setNegativeButton("Cancel") { _, _ ->
                            isUpdatingFromState = true
                            binding.switchStrictMode.isChecked = false
                            isUpdatingFromState = false
                        }
                        .setCancelable(false)
                        .show()
                } else {
                    settingsVm.toggleStrictMode(false)
                }
            }
        }

        binding.sliderDelay.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val secs = value.toInt()
                settingsVm.setDelaySeconds(secs)
                binding.tvDelayValue.text = "${secs}s"
            }
        }

        binding.sliderAiThreshold.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                settingsVm.setAiThreshold(value)
                binding.tvAiThresholdValue.text = "${(value * 100).toInt()}%"
            }
        }

        // FIX #1: Use multiple MIME types for better file picker compatibility
        binding.btnUploadModel.setOnClickListener {
            try {
                modelPickLauncher.launch(arrayOf(
                    "application/octet-stream",
                    "application/x-tflite",
                    "application/tflite",
                    "*/*"
                ))
            } catch (e: Exception) {
                Timber.e(e, "Failed to launch file picker")
                settingsVm.showMessage("❌ Cannot open file picker")
            }
        }

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
            Snackbar.make(binding.root, "No more apps", Snackbar.LENGTH_SHORT).show()
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

                binding.switchKeyword.isChecked = state.isKeywordEnabled
                binding.switchAi.isChecked = state.isAiEnabled
                binding.switchStrictMode.isChecked = state.isStrictMode
                binding.sliderDelay.value = state.delayUnlockSeconds.toFloat()
                binding.tvDelayValue.text = "${state.delayUnlockSeconds}s"
                binding.sliderAiThreshold.value = state.aiThreshold
                binding.tvAiThresholdValue.text = "${(state.aiThreshold * 100).toInt()}%"
                binding.layoutAiOptions.isVisible = state.isAiEnabled

                isUpdatingFromState = false

                updateModelStatus()

                state.snackMessage?.let { msg ->
                    Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).show()
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
            binding.tvModelStatus.text = "⚠️ No model — upload .tflite"
            binding.tvModelStatus.setTextColor(getColor(android.R.color.holo_orange_dark))
        }
    }

    private fun observeKeywords() {
        lifecycleScope.launch {
            keywordVm.uiState.collectLatest { state ->
                binding.tvKeywordError.isVisible = state.errorMessage != null
                binding.tvKeywordError.text = state.errorMessage ?: ""
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

    /**
     * FIX #3: Proper TFLite file validation using magic bytes.
     * TFLite files have "TFL3" signature at offset 4.
     */
    private suspend fun isValidTfliteFile(file: File): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!file.exists() || file.length() < 8) return@withContext false
            file.inputStream().use { input ->
                val magic = ByteArray(8)
                val read = input.read(magic)
                if (read < 8) return@withContext false
                // TFLite magic: bytes 4-7 should be "TFL3"
                magic[4] == 'T'.code.toByte() &&
                magic[5] == 'F'.code.toByte() &&
                magic[6] == 'L'.code.toByte() &&
                magic[7] == '3'.code.toByte()
            }
        } catch (e: Exception) {
            Timber.e(e, "Validation error")
            false
        }
    }

    private fun importModel(uri: Uri) {
        Timber.d("Importing model from: $uri")
        lifecycleScope.launch {
            try {
                val dest = AiDetector.modelFile(this@SettingsActivity)
                val tempFile = File(this@SettingsActivity.filesDir, "temp_model.tflite")

                // Copy to temp file first for validation
                val bytesCopied = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { input ->
                        tempFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    } ?: throw Exception("Could not open selected file")
                }

                Timber.d("Temp copy done: $bytesCopied bytes")

                // Validate size
                if (bytesCopied < 1024) {
                    tempFile.delete()
                    settingsVm.showMessage("❌ File too small — not a valid model")
                    return@launch
                }

                // FIX #3: Validate TFLite magic bytes
                if (!isValidTfliteFile(tempFile)) {
                    tempFile.delete()
                    settingsVm.showMessage("❌ Not a valid .tflite file (wrong format)")
                    return@launch
                }

                // Move temp → final
                withContext(Dispatchers.IO) {
                    if (dest.exists()) dest.delete()
                    if (!tempFile.renameTo(dest)) {
                        // Fallback: copy + delete
                        tempFile.inputStream().use { input ->
                            dest.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        tempFile.delete()
                    }
                }

                if (!dest.exists() || dest.length() < 1024) {
                    settingsVm.showMessage("❌ Failed to save model")
                    return@launch
                }

                val sizeKB = dest.length() / 1024
                updateModelStatus()

                val aiEnabled = settingsVm.uiState.value.isAiEnabled
                if (aiEnabled) {
                    sendBroadcast(
                        Intent(GuardianAccessibilityService.ACTION_RELOAD_MODEL).apply {
                            setPackage(packageName)
                        }
                    )
                    settingsVm.showMessage("✓ Model imported (${sizeKB}KB) — reloading...")
                } else {
                    settingsVm.toggleAi(true, modelAvailable = true)
                    settingsVm.showMessage("✓ Model imported & AI enabled (${sizeKB}KB)")
                }

            } catch (e: SecurityException) {
                Timber.e(e, "Permission denied")
                settingsVm.showMessage("❌ Permission denied — try again")
            } catch (e: Exception) {
                Timber.e(e, "Model import failed")
                settingsVm.showMessage("❌ Import failed: ${e.message ?: "Unknown error"}")
            }
        }
    }
}