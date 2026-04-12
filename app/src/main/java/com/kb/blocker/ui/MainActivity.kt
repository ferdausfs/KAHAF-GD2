package com.kb.blocker.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.kb.blocker.databinding.ActivityMainBinding
import com.kb.blocker.ml.ModelManager
import com.kb.blocker.watchdog.ServiceWatchdog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val vm: MainViewModel by viewModels()
    private lateinit var adapter: KeywordAdapter

    // File picker — user model (.tflite) add করার জন্য
    private val modelPicker = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { importModel(it) } }

    // ─── Lifecycle ────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupButtons()
        observeViewModel()

        // Watchdog schedule (idempotent — duplicate হবে না)
        ServiceWatchdog.schedule(this)

        // Model status দেখাও
        refreshModelStatus()

        // Battery optimization exempt request
        requestIgnoreBatteryOptimization()
    }

    override fun onResume() {
        super.onResume()
        refreshServiceStatus()
        refreshModelStatus()
    }

    // ─── Setup ────────────────────────────────────────────────────

    private fun setupRecyclerView() {
        adapter = KeywordAdapter { entity -> vm.deleteKeyword(entity) }
        binding.rvKeywords.adapter = adapter
    }

    private fun setupButtons() {
        binding.btnEnableService.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        binding.btnAddKeyword.setOnClickListener { showAddKeywordDialog() }

        binding.btnAddModel.setOnClickListener {
            modelPicker.launch("*/*")
        }

        binding.btnClearAll.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("সব keywords মুছবে?")
                .setMessage("এই action undo করা যাবে না।")
                .setPositiveButton("হ্যাঁ, মুছো") { _, _ -> vm.deleteAll() }
                .setNegativeButton("বাতিল", null)
                .show()
        }
    }

    private fun showAddKeywordDialog() {
        val input = TextInputEditText(this).apply {
            hint = "keyword লিখুন"
            setPadding(48, 24, 48, 24)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("নতুন Keyword")
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                val kw = input.text?.toString()?.trim() ?: ""
                if (kw.isNotEmpty()) {
                    vm.addKeyword(kw)
                } else {
                    Toast.makeText(this, "Keyword খালি হতে পারবে না!", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("বাতিল", null)
            .show()
    }

    // ─── Observe ─────────────────────────────────────────────────

    private fun observeViewModel() {
        lifecycleScope.launch {
            vm.keywords.collect { list ->
                adapter.submitList(list)
                binding.tvKeywordCount.text = "${list.size} keywords active"
            }
        }
    }

    private fun refreshServiceStatus() {
        val on = ServiceWatchdog.isAccessibilityEnabled(this)
        binding.tvServiceStatus.text = if (on) "✅ Service চালু" else "❌ Service বন্ধ"
        binding.btnEnableService.text = if (on) "Accessibility Settings" else "Service চালু করুন"
    }

    // ─── Model Import ─────────────────────────────────────────────

    /**
     * User-selected URI থেকে .tflite file internal storage এ copy করো।
     * ContentBlockerService এর ModelManager auto-reload করবে।
     */
    private fun importModel(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val name = getFileNameFromUri(uri)
                    ?: "model_${System.currentTimeMillis()}.tflite"

                // Extension validate করো
                if (!name.endsWith(".tflite") && !name.endsWith(".lite")) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@MainActivity,
                            "❌ শুধু .tflite বা .lite file সমর্থিত",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    return@launch
                }

                // filesDir/models/ এ copy করো
                val modelsDir = File(filesDir, "models").also { it.mkdirs() }
                val dest = File(modelsDir, name)

                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(dest).use { output -> input.copyTo(output) }
                }

                // Test: model সত্যিই load হয় কিনা check করো
                val mgr = ModelManager(applicationContext)
                val loaded = mgr.getModelCount() > 0
                mgr.close()

                withContext(Dispatchers.Main) {
                    if (loaded) {
                        Toast.makeText(
                            this@MainActivity,
                            "✅ Model '$name' যোগ হয়েছে! Image analysis চালু।",
                            Toast.LENGTH_LONG
                        ).show()
                        refreshModelStatus()
                    } else {
                        // Invalid file হলে delete করো
                        dest.delete()
                        Toast.makeText(
                            this@MainActivity,
                            "❌ Invalid TFLite file! সঠিক model দিন।",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        "❌ Error: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    /** Model folder এ কতটা model আছে দেখাও */
    private fun refreshModelStatus() {
        val count = File(filesDir, "models")
            .listFiles { f -> f.extension == "tflite" || f.extension == "lite" }
            ?.size ?: 0
        binding.tvModelStatus.text = if (count > 0)
            "🤖 $count AI Model active"
        else
            "⚠️ কোনো AI Model নেই — Image analysis বন্ধ"
    }

    private fun getFileNameFromUri(uri: Uri): String? {
        return try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val col = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                cursor.moveToFirst()
                if (col >= 0) cursor.getString(col) else null
            }
        } catch (_: Exception) { null }
    }

    // ─── Battery Optimization ─────────────────────────────────────

    private fun requestIgnoreBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(android.os.PowerManager::class.java)
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:$packageName")
                        }
                    )
                } catch (_: Exception) {
                    // Some devices don't support this intent — silently ignore
                }
            }
        }
    }
}
