package com.ftt.bulldogblocker.ui

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.ftt.bulldogblocker.admin.DeviceAdminReceiver
import com.ftt.bulldogblocker.ml.ContentClassifier
import com.ftt.bulldogblocker.service.BlockerForegroundService
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream

/**
 * Main Dashboard.
 *
 * Sections:
 *  ① Protection status (Device Admin + Accessibility)
 *  ② Model Management  → Upload .tflite + model info card
 *  ③ Test Panel        → Pick image → run classifier → show result
 */
class MainActivity : AppCompatActivity() {

    // ── Request codes ────────────────────────────
    companion object {
        private const val REQ_ADMIN        = 101
        private const val REQ_MODEL_PICK   = 102
        private const val REQ_IMAGE_PICK   = 103
    }

    // ── Views ────────────────────────────────────
    private lateinit var tvOverallStatus    : TextView
    private lateinit var tvAdminStatus      : TextView
    private lateinit var btnAdmin           : Button
    private lateinit var tvAccessStatus     : TextView
    private lateinit var btnAccess          : Button

    // Model section
    private lateinit var tvModelStatus      : TextView
    private lateinit var btnUploadModel     : Button
    private lateinit var tvModelInfo        : TextView

    // Test section
    private lateinit var ivTestImage        : ImageView
    private lateinit var btnPickImage       : Button
    private lateinit var btnRunTest         : Button
    private lateinit var tvTestResult       : TextView
    private lateinit var progressTest       : ProgressBar

    private val uiScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var classifier: ContentClassifier? = null
    private var testBitmap: Bitmap? = null

    // ────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        startService(Intent(this, BlockerForegroundService::class.java))
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    override fun onDestroy() {
        classifier?.close()
        uiScope.cancel()
        super.onDestroy()
    }

    // ════════════════════════════════════════════
    // UI BUILD
    // ════════════════════════════════════════════

    private fun buildUi() {
        val scroll = ScrollView(this)
        val root   = col(bg = "#0F0F1A", pad = 0).also { scroll.addView(it) }

        // ── Header ──────────────────────────────
        root.addView(col(bg = "#1A1A35", pad = 48).apply {
            addView(tv("🐶", size = 40f, center = true))
            addView(tv("Bulldog Blocker", size = 24f, color = "#FFFFFF", center = true))
            addView(tv("Adult Content Protection", size = 13f, color = "#888888", center = true))
            addView(space(12))
            tvOverallStatus = tv("", size = 16f, center = true)
            addView(tvOverallStatus)
        })

        root.addView(space(24))

        // ── Section 1: Protection status ────────
        root.addView(sectionLabel("🔐 PROTECTION"))
        root.addView(card().apply {
            addView(tv("Device Admin", size = 15f, color = "#FFFFFF"))
            tvAdminStatus = tv("", size = 13f, color = "#888")
            addView(tvAdminStatus)
            addView(space(8))
            btnAdmin = btn("সক্রিয় করুন", "#E91E63") { requestDeviceAdmin() }
            addView(btnAdmin)
        })
        root.addView(space(8))
        root.addView(card().apply {
            addView(tv("Accessibility Service", size = 15f, color = "#FFFFFF"))
            tvAccessStatus = tv("", size = 13f, color = "#888")
            addView(tvAccessStatus)
            addView(space(8))
            btnAccess = btn("সক্রিয় করুন", "#E91E63") { openAccessibility() }
            addView(btnAccess)
        })

        root.addView(space(24))

        // ── Section 2: Model Upload ──────────────
        root.addView(sectionLabel("🤖 TFLITE MODEL"))
        root.addView(card().apply {
            tvModelStatus = tv("", size = 14f)
            addView(tvModelStatus)
            addView(space(8))
            tvModelInfo = tv("", size = 12f, color = "#AAAAAA")
            addView(tvModelInfo)
            addView(space(12))
            btnUploadModel = btn("📂  Model Upload করুন (.tflite)", "#1565C0") {
                pickModelFile()
            }
            addView(btnUploadModel)
        })

        root.addView(space(24))

        // ── Section 3: Test ──────────────────────
        root.addView(sectionLabel("🧪 TEST MODEL"))
        root.addView(card().apply {

            // Image preview
            ivTestImage = ImageView(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 400)
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(Color.parseColor("#1E1E1E"))
                setImageResource(android.R.drawable.ic_menu_gallery)
            }
            addView(ivTestImage)
            addView(space(12))

            // Two buttons side by side
            addView(row().apply {
                btnPickImage = btn("🖼 ছবি বেছে নিন", "#1565C0") { pickTestImage() }
                    .also { it.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                            addView(it) }
                addView(space(8))
                btnRunTest = btn("▶ Test Run করুন", "#2E7D32") { runTest() }
                    .also { it.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                            addView(it) }
            })

            addView(space(12))
            progressTest = ProgressBar(this@MainActivity).apply {
                visibility = android.view.View.GONE
            }
            addView(progressTest)

            // Result card
            tvTestResult = tv("ছবি বেছে নিয়ে Test Run করুন", size = 15f,
                color = "#AAAAAA", center = true).apply {
                setPadding(24, 24, 24, 24)
                setBackgroundColor(Color.parseColor("#1A1A1A"))
            }
            addView(tvTestResult)
        })

        root.addView(space(48))
        setContentView(scroll)
    }

    // ════════════════════════════════════════════
    // STATUS REFRESH
    // ════════════════════════════════════════════

    private fun refreshStatus() {
        val adminOk  = DeviceAdminReceiver.isAdminActive(this)
        val accessOk = isAccessibilityEnabled()
        val modelOk  = ContentClassifier.isReady(this)

        // Admin
        if (adminOk) {
            tvAdminStatus.text = "✅ সক্রিয় — আনইনস্টল ব্লক আছে"
            tvAdminStatus.setTextColor(Color.parseColor("#4CAF50"))
            btnAdmin.text = "✔ সক্রিয়"
            btnAdmin.isEnabled = false
            btnAdmin.setBackgroundColor(Color.DKGRAY)
        } else {
            tvAdminStatus.text = "❌ নিষ্ক্রিয় — আনইনস্টল সম্ভব!"
            tvAdminStatus.setTextColor(Color.parseColor("#F44336"))
            btnAdmin.text = "Device Admin সক্রিয় করুন"
            btnAdmin.isEnabled = true
            btnAdmin.setBackgroundColor(Color.parseColor("#E91E63"))
        }

        // Accessibility
        if (accessOk) {
            tvAccessStatus.text = "✅ সক্রিয় — URL ব্লকিং চালু"
            tvAccessStatus.setTextColor(Color.parseColor("#4CAF50"))
            btnAccess.text = "✔ সক্রিয়"
            btnAccess.isEnabled = false
            btnAccess.setBackgroundColor(Color.DKGRAY)
        } else {
            tvAccessStatus.text = "❌ নিষ্ক্রিয় — URL ব্লকিং বন্ধ"
            tvAccessStatus.setTextColor(Color.parseColor("#F44336"))
            btnAccess.text = "Accessibility সক্রিয় করুন"
            btnAccess.isEnabled = true
            btnAccess.setBackgroundColor(Color.parseColor("#E91E63"))
        }

        // Model
        if (modelOk) {
            val size = ContentClassifier.modelFile(this).length() / (1024 * 1024)
            tvModelStatus.text = "✅ Model লোড হয়েছে"
            tvModelStatus.setTextColor(Color.parseColor("#4CAF50"))
            tvModelInfo.text = "📁 saved_model.tflite  •  ${size} MB  •  Internal storage"
            btnUploadModel.text = "🔄 Model পরিবর্তন করুন"
        } else {
            tvModelStatus.text = "⚠️ Model আপলোড করা হয়নি"
            tvModelStatus.setTextColor(Color.parseColor("#FF9800"))
            tvModelInfo.text = "নিচের বোতাম চেপে .tflite ফাইল বেছে নিন"
            btnUploadModel.text = "📂 Model Upload করুন (.tflite)"
        }

        // Overall
        val allOk = adminOk && accessOk && modelOk
        tvOverallStatus.text = if (allOk) "🛡️ সম্পূর্ণ সুরক্ষিত" else "⚠️ সেটআপ অসম্পূর্ণ"
        tvOverallStatus.setTextColor(
            Color.parseColor(if (allOk) "#4CAF50" else "#FF9800"))
    }

    // ════════════════════════════════════════════
    // MODEL UPLOAD
    // ════════════════════════════════════════════

    private fun pickModelFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"                          // tflite MIME not always recognized
            putExtra(Intent.EXTRA_MIME_TYPES,
                arrayOf("application/octet-stream", "*/*"))
        }
        startActivityForResult(
            Intent.createChooser(intent, "saved_model.tflite বেছে নিন"),
            REQ_MODEL_PICK
        )
    }

    private fun importModel(uri: Uri) {
        uiScope.launch {
            tvModelStatus.text = "⏳ Model কপি হচ্ছে..."
            tvModelStatus.setTextColor(Color.parseColor("#FF9800"))
            btnUploadModel.isEnabled = false

            val ok = withContext(Dispatchers.IO) {
                try {
                    contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(ContentClassifier.modelFile(this@MainActivity)).use { out ->
                            input.copyTo(out)
                        }
                    }
                    true
                } catch (e: Exception) { e.printStackTrace(); false }
            }

            btnUploadModel.isEnabled = true
            if (ok) {
                Toast.makeText(this@MainActivity, "✅ Model সফলভাবে আপলোড হয়েছে!", Toast.LENGTH_SHORT).show()
                // Pre-load classifier
                classifier?.close()
                classifier = ContentClassifier(this@MainActivity).also { it.load() }
            } else {
                Toast.makeText(this@MainActivity, "❌ আপলোড ব্যর্থ হয়েছে", Toast.LENGTH_SHORT).show()
            }
            refreshStatus()
        }
    }

    // ════════════════════════════════════════════
    // TEST
    // ════════════════════════════════════════════

    private fun pickTestImage() {
        val intent = Intent(Intent.ACTION_PICK).apply { type = "image/*" }
        startActivityForResult(
            Intent.createChooser(intent, "Test করার জন্য ছবি বেছে নিন"),
            REQ_IMAGE_PICK
        )
    }

    private fun runTest() {
        val bmp = testBitmap
        if (bmp == null) {
            Toast.makeText(this, "আগে একটি ছবি বেছে নিন", Toast.LENGTH_SHORT).show()
            return
        }
        if (!ContentClassifier.isReady(this)) {
            Toast.makeText(this, "আগে Model আপলোড করুন", Toast.LENGTH_SHORT).show()
            return
        }

        uiScope.launch {
            progressTest.visibility = android.view.View.VISIBLE
            btnRunTest.isEnabled = false
            tvTestResult.text = "⏳ বিশ্লেষণ চলছে..."
            tvTestResult.setTextColor(Color.parseColor("#AAAAAA"))

            val result = withContext(Dispatchers.Default) {
                if (classifier == null) {
                    classifier = ContentClassifier(this@MainActivity).also { it.load() }
                }
                classifier!!.classify(bmp)
            }

            progressTest.visibility = android.view.View.GONE
            btnRunTest.isEnabled = true

            // Show result with color
            tvTestResult.text = buildString {
                appendLine(result.label)
                appendLine()
                appendLine("Safe:   ${"%.1f".format(result.safeScore * 100)}%")
                append("Unsafe: ${"%.1f".format(result.unsafeScore * 100)}%")
            }
            tvTestResult.setTextColor(
                if (result.isAdult) Color.parseColor("#F44336")
                else                Color.parseColor("#4CAF50")
            )
            tvTestResult.setBackgroundColor(
                if (result.isAdult) Color.parseColor("#2A0000")
                else                Color.parseColor("#001A00")
            )
        }
    }

    // ════════════════════════════════════════════
    // PERMISSION HELPERS
    // ════════════════════════════════════════════

    private fun requestDeviceAdmin() {
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                DeviceAdminReceiver.getComponentName(this@MainActivity))
            putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "App-কে Device Admin করলে সহজে আনইনস্টল করা যাবে না।")
        }
        startActivityForResult(intent, REQ_ADMIN)
    }

    private fun openAccessibility() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        Toast.makeText(this, "Bulldog Blocker খুঁজে চালু করুন", Toast.LENGTH_LONG).show()
    }

    private fun isAccessibilityEnabled(): Boolean {
        val svc     = "${packageName}/.service.BlockerAccessibilityService"
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return svc in enabled
    }

    // ════════════════════════════════════════════
    // ACTIVITY RESULTS
    // ════════════════════════════════════════════

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK) return
        val uri = data?.data ?: return

        when (requestCode) {
            REQ_MODEL_PICK -> importModel(uri)

            REQ_IMAGE_PICK -> {
                val bmp = contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it)
                } ?: return
                testBitmap = bmp
                ivTestImage.setImageBitmap(bmp)
                tvTestResult.text = "▶ Test Run করুন বোতাম চাপুন"
                tvTestResult.setTextColor(Color.parseColor("#AAAAAA"))
                tvTestResult.setBackgroundColor(Color.parseColor("#1A1A1A"))
            }

            REQ_ADMIN -> refreshStatus()
        }
    }

    // ════════════════════════════════════════════
    // UI HELPERS
    // ════════════════════════════════════════════

    private fun col(bg: String = "#1E1E30", pad: Int = 32) =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.parseColor(bg))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
        }

    private fun row() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT)
    }

    private fun card() = col(bg = "#1E1E30", pad = 28).apply {
        (layoutParams as? LinearLayout.LayoutParams)?.let {
            it.marginStart  = 16
            it.marginEnd    = 16
        }
    }

    private fun sectionLabel(text: String) = tv(text, size = 11f, color = "#666666").apply {
        setPadding(32, 0, 0, 8)
    }

    private fun tv(
        text:   String,
        size:   Float  = 14f,
        color:  String = "#DDDDDD",
        center: Boolean = false
    ) = TextView(this).apply {
        this.text = text
        textSize  = size
        setTextColor(Color.parseColor(color))
        if (center) gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT)
    }

    private fun btn(label: String, bg: String, onClick: () -> Unit) =
        Button(this).apply {
            text = label
            setBackgroundColor(Color.parseColor(bg))
            setTextColor(Color.WHITE)
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
            setOnClickListener { onClick() }
        }

    private fun space(dp: Int) = android.view.View(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp)
    }
}
