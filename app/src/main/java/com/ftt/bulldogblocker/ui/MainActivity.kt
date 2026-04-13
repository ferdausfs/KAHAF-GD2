package com.ftt.bulldogblocker.ui

import android.app.Activity
import android.app.AlertDialog
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.ftt.bulldogblocker.AppListManager
import com.ftt.bulldogblocker.admin.DeviceAdminReceiver
import com.ftt.bulldogblocker.ml.ContentClassifier
import com.ftt.bulldogblocker.service.BlockerAccessibilityService
import com.ftt.bulldogblocker.service.BlockerForegroundService
import android.media.projection.MediaProjectionManager
import com.ftt.bulldogblocker.service.MediaProjectionCaptureService
import kotlinx.coroutines.*
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQ_ADMIN        = 101
        private const val REQ_MODEL_PICK   = 102
        private const val REQ_IMAGE_PICK   = 103
        private const val REQ_MEDIA_PROJ   = 104
    }

    private lateinit var tvOverallStatus : TextView
    private lateinit var tvAdminStatus   : TextView
    private lateinit var btnAdmin        : Button
    private lateinit var tvAccessStatus  : TextView
    private lateinit var btnAccess       : Button
    private lateinit var tvModelStatus   : TextView
    private lateinit var tvModelInfo     : TextView
    private lateinit var tvMlScanStatus  : TextView
    private lateinit var tvVideoScanStatus : TextView
    private lateinit var btnVideoScan    : Button
    private lateinit var btnUploadModel  : Button
    private lateinit var ivTestImage     : ImageView
    private lateinit var btnPickImage    : Button
    private lateinit var btnRunTest      : Button
    private lateinit var tvTestResult    : TextView
    private lateinit var progressTest    : ProgressBar

    // App list containers
    private lateinit var llBlacklist : LinearLayout
    private lateinit var llWhitelist : LinearLayout

    private val uiScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var classifier: ContentClassifier? = null
    private var testBitmap: Bitmap? = null

    // ─────────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        startForegroundServiceSafe()
        if (intent.getBooleanExtra("admin_lost", false)) {
            Toast.makeText(this, "⚠️ Device Admin নিষ্ক্রিয়! পুনরায় সক্রিয় করুন।", Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        refreshAppLists()
    }

    override fun onDestroy() {
        testBitmap?.recycle(); testBitmap = null
        classifier?.close()
        uiScope.cancel()
        super.onDestroy()
    }

    // ─────────────────────────────────────────────────────────────────

    private fun startForegroundServiceSafe() {
        try {
            val intent = Intent(this, BlockerForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                startForegroundService(intent) else startService(intent)
        } catch (e: Exception) { e.printStackTrace() }
    }

    // ════════════════════════════════════════════════════════════════
    // UI BUILD
    // ════════════════════════════════════════════════════════════════

    private fun buildUi() {
        val scroll = ScrollView(this)
        val root   = vbox(bg = "#0F0F1A", pad = 0)
        scroll.addView(root)

        // ── Header ──────────────────────────────────────────────────
        root.addView(vbox(bg = "#1A1A35", pad = 48).apply {
            addView(tv("🐶", size = 40f, center = true))
            addView(tv("Bulldog Blocker", size = 24f, color = "#FFFFFF", center = true))
            addView(tv("Adult Content Protection", size = 13f, color = "#888888", center = true))
            addView(gap(12))
            tvOverallStatus = tv("", size = 16f, center = true)
            addView(tvOverallStatus)
        })

        root.addView(gap(24))

        // ── Protection ──────────────────────────────────────────────
        root.addView(sectionLabel("🔐 PROTECTION"))

        root.addView(card().apply {
            addView(tv("Device Admin", size = 15f, color = "#FFFFFF"))
            tvAdminStatus = tv("", size = 13f, color = "#888888")
            addView(tvAdminStatus)
            addView(gap(8))
            btnAdmin = btn("সক্রিয় করুন", "#E91E63") { requestDeviceAdmin() }
            addView(btnAdmin)
        })

        root.addView(gap(8))

        root.addView(card().apply {
            addView(tv("Accessibility Service", size = 15f, color = "#FFFFFF"))
            tvAccessStatus = tv("", size = 13f, color = "#888888")
            addView(tvAccessStatus)
            addView(gap(8))
            btnAccess = btn("সক্রিয় করুন", "#E91E63") { openAccessibility() }
            addView(btnAccess)
        })

        root.addView(gap(24))

        // ── Blacklist Apps ───────────────────────────────────────────
        root.addView(sectionLabel("📵 BLACKLIST APPS"))
        root.addView(card().apply {
            addView(tv(
                "এই apps-এ blocker পুরো active থাকবে (ML + keyword scan)",
                size = 12f, color = "#AAAAAA"
            ))
            addView(gap(12))
            llBlacklist = vbox(bg = "#0F0F1A", pad = 0)
            addView(llBlacklist)
            addView(gap(8))
            addView(btn("➕  App যোগ করুন", "#1B5E20") { showAppPicker(isBlacklist = true) })
        })

        root.addView(gap(8))

        // ── Whitelist Apps ───────────────────────────────────────────
        root.addView(sectionLabel("✅ WHITELIST APPS"))
        root.addView(card().apply {
            addView(tv(
                "এই apps-এ blocker কিছুই করবে না (সম্পূর্ণ bypass)",
                size = 12f, color = "#AAAAAA"
            ))
            addView(gap(12))
            llWhitelist = vbox(bg = "#0F0F1A", pad = 0)
            addView(llWhitelist)
            addView(gap(8))
            addView(btn("➕  App যোগ করুন", "#1A237E") { showAppPicker(isBlacklist = false) })
        })

        root.addView(gap(24))

        // ── Video Scan (MediaProjection) ─────────────────────────────
        root.addView(sectionLabel("🎥 VIDEO SCAN (Optional)"))
        root.addView(card().apply {
            addView(tv("Video content-ও ML দিয়ে block করুন", size = 15f, color = "#FFFFFF"))
            addView(gap(4))
            addView(tv("চালু করলে একবার system permission dialog আসবে", size = 12f, color = "#AAAAAA"))
            addView(gap(10))
            tvVideoScanStatus = tv("", size = 13f)
            addView(tvVideoScanStatus)
            addView(gap(10))
            btnVideoScan = btn("🎥 Video Scan চালু করুন", "#4A148C") { toggleVideoScan() }
            addView(btnVideoScan)
        })

        root.addView(gap(24))

        // ── Model Upload ─────────────────────────────────────────────
        root.addView(sectionLabel("🤖 TFLITE MODEL"))

        root.addView(card().apply {
            tvModelStatus = tv("", size = 14f)
            addView(tvModelStatus)
            addView(gap(4))
            tvModelInfo = tv("", size = 12f, color = "#AAAAAA")
            addView(tvModelInfo)
            addView(gap(8))
            tvMlScanStatus = tv("", size = 12f, color = "#AAAAAA")
            addView(tvMlScanStatus)
            addView(gap(12))
            btnUploadModel = btn("📂  Model Upload করুন (.tflite)", "#1565C0") { pickModelFile() }
            addView(btnUploadModel)
        })

        root.addView(gap(24))

        // ── Test Panel ───────────────────────────────────────────────
        root.addView(sectionLabel("🧪 TEST MODEL"))

        root.addView(card().apply {
            ivTestImage = ImageView(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 400)
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(Color.parseColor("#222222"))
                setImageResource(android.R.drawable.ic_menu_gallery)
            }
            addView(ivTestImage)
            addView(gap(12))

            val row = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            btnPickImage = Button(this@MainActivity).apply {
                text = "🖼 ছবি বেছে নিন"
                setBackgroundColor(Color.parseColor("#1565C0"))
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { pickTestImage() }
            }
            btnRunTest = Button(this@MainActivity).apply {
                text = "▶ Test করুন"
                setBackgroundColor(Color.parseColor("#2E7D32"))
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = 8 }
                setOnClickListener { runTest() }
            }
            row.addView(btnPickImage); row.addView(btnRunTest)
            addView(row)
            addView(gap(12))

            progressTest = ProgressBar(this@MainActivity).apply {
                visibility = View.GONE
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            addView(progressTest)

            tvTestResult = tv("ছবি বেছে নিয়ে Test করুন", size = 15f, color = "#AAAAAA", center = true).apply {
                setPadding(24, 24, 24, 24)
                setBackgroundColor(Color.parseColor("#1A1A1A"))
            }
            addView(tvTestResult)
        })

        root.addView(gap(48))
        setContentView(scroll)
    }

    // ════════════════════════════════════════════════════════════════
    // APP LIST UI
    // ════════════════════════════════════════════════════════════════

    private fun refreshAppLists() {
        val black = AppListManager.getBlacklist(this)
        val white = AppListManager.getWhitelist(this)
        renderAppList(llBlacklist, black, isBlacklist = true)
        renderAppList(llWhitelist, white, isBlacklist = false)
    }

    private fun renderAppList(container: LinearLayout, pkgs: Set<String>, isBlacklist: Boolean) {
        container.removeAllViews()
        if (pkgs.isEmpty()) {
            container.addView(tv(
                if (isBlacklist) "কোনো app যোগ করা হয়নি" else "কোনো app whitelist-এ নেই",
                size = 12f, color = "#555555"
            ).apply { setPadding(8, 4, 8, 4) })
            return
        }
        pkgs.forEach { pkg ->
            val name = AppListManager.getAppName(this, pkg)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setPadding(8, 6, 8, 6)
            }
            val icon = if (isBlacklist) "📵" else "✅"
            val label = TextView(this).apply {
                text = "$icon  $name"
                textSize = 13f
                setTextColor(Color.parseColor("#DDDDDD"))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val removeBtn = Button(this).apply {
                text = "✕"
                textSize = 11f
                setBackgroundColor(Color.parseColor("#B71C1C"))
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(80, LinearLayout.LayoutParams.WRAP_CONTENT)
                setOnClickListener {
                    if (isBlacklist) AppListManager.removeBlacklist(this@MainActivity, pkg)
                    else             AppListManager.removeWhitelist(this@MainActivity, pkg)
                    refreshAppLists()
                }
            }
            row.addView(label)
            row.addView(removeBtn)
            container.addView(row)
        }
    }

    private fun showAppPicker(isBlacklist: Boolean) {
        val title = if (isBlacklist) "Blacklist-এ যোগ করুন" else "Whitelist-এ যোগ করুন"

        uiScope.launch {
            val loadingDlg = AlertDialog.Builder(this@MainActivity)
                .setMessage("Apps লোড হচ্ছে...")
                .setCancelable(false)
                .create()
            loadingDlg.show()

            val apps = withContext(Dispatchers.IO) {
                AppListManager.getInstalledUserApps(this@MainActivity)
            }

            loadingDlg.dismiss()

            if (apps.isEmpty()) {
                Toast.makeText(this@MainActivity, "কোনো app পাওয়া যায়নি", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val names  = apps.map { it.name }.toTypedArray()
            val checked = BooleanArray(apps.size) { i ->
                val pkg = apps[i].pkg
                if (isBlacklist) AppListManager.getBlacklist(this@MainActivity).contains(pkg)
                else             AppListManager.getWhitelist(this@MainActivity).contains(pkg)
            }
            val selected = checked.toMutableList()

            AlertDialog.Builder(this@MainActivity)
                .setTitle(title)
                .setMultiChoiceItems(names, checked) { _, which, isChecked ->
                    selected[which] = isChecked
                }
                .setPositiveButton("সংরক্ষণ") { _, _ ->
                    apps.forEachIndexed { i, app ->
                        if (selected[i]) {
                            if (isBlacklist) AppListManager.addBlacklist(this@MainActivity, app.pkg)
                            else             AppListManager.addWhitelist(this@MainActivity, app.pkg)
                        } else {
                            if (isBlacklist) AppListManager.removeBlacklist(this@MainActivity, app.pkg)
                            else             AppListManager.removeWhitelist(this@MainActivity, app.pkg)
                        }
                    }
                    refreshAppLists()
                    Toast.makeText(this@MainActivity, "✅ সংরক্ষিত হয়েছে", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("বাতিল", null)
                .show()
        }
    }

    // ════════════════════════════════════════════════════════════════
    // STATUS
    // ════════════════════════════════════════════════════════════════

    private fun refreshStatus() {
        val adminOk  = DeviceAdminReceiver.isAdminActive(this)
        val accessOk = isAccessibilityEnabled()
        val modelOk  = ContentClassifier.isReady(this)

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

        if (accessOk) {
            tvAccessStatus.text = "✅ সক্রিয় — ব্লকিং চালু"
            tvAccessStatus.setTextColor(Color.parseColor("#4CAF50"))
            btnAccess.text = "✔ সক্রিয়"
            btnAccess.isEnabled = false
            btnAccess.setBackgroundColor(Color.DKGRAY)
        } else {
            tvAccessStatus.text = "❌ নিষ্ক্রিয় — ব্লকিং বন্ধ"
            tvAccessStatus.setTextColor(Color.parseColor("#F44336"))
            btnAccess.text = "Accessibility সক্রিয় করুন"
            btnAccess.isEnabled = true
            btnAccess.setBackgroundColor(Color.parseColor("#E91E63"))
        }

        if (modelOk) {
            val mb = ContentClassifier.modelFile(this).length() / (1024 * 1024)
            tvModelStatus.text = "✅ Model লোড হয়েছে"
            tvModelStatus.setTextColor(Color.parseColor("#4CAF50"))
            tvModelInfo.text = "📁 saved_model.tflite  •  ${mb} MB"
            btnUploadModel.text = "🔄 Model পরিবর্তন করুন"
        } else {
            tvModelStatus.text = "⚠️ Model আপলোড করা হয়নি"
            tvModelStatus.setTextColor(Color.parseColor("#FF9800"))
            tvModelInfo.text = ".tflite ফাইল বেছে নিন"
            btnUploadModel.text = "📂 Model Upload করুন (.tflite)"
        }

        // ML Auto-Scanner status
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            tvMlScanStatus.text = "📸 Auto ML Scan: চালু (প্রতি 300ms, threshold 22%)"
            tvMlScanStatus.setTextColor(Color.parseColor("#4CAF50"))
        } else {
            tvMlScanStatus.text = "⚠️ Auto ML Scan: Android 11+ লাগবে (আপনার Android ${Build.VERSION.RELEASE})"
            tvMlScanStatus.setTextColor(Color.parseColor("#FF9800"))
        }

        // Video scan status
        val videoOn = MediaProjectionCaptureService.isEnabled(this)
        tvVideoScanStatus.text = if (videoOn) "✅ চালু — video frame ML দিয়ে scan হচ্ছে"
                                 else          "⭕ বন্ধ — video content scan হচ্ছে না"
        tvVideoScanStatus.setTextColor(Color.parseColor(if (videoOn) "#4CAF50" else "#888888"))
        btnVideoScan.text = if (videoOn) "⏹ Video Scan বন্ধ করুন" else "🎥 Video Scan চালু করুন"
        btnVideoScan.setBackgroundColor(Color.parseColor(if (videoOn) "#B71C1C" else "#4A148C"))

        tvOverallStatus.text = if (adminOk && accessOk && modelOk) "🛡️ সম্পূর্ণ সুরক্ষিত" else "⚠️ সেটআপ অসম্পূর্ণ"
        tvOverallStatus.setTextColor(Color.parseColor(if (adminOk && accessOk && modelOk) "#4CAF50" else "#FF9800"))
    }

    // ════════════════════════════════════════════════════════════════
    // MODEL UPLOAD
    // ════════════════════════════════════════════════════════════════

    private fun pickModelFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
        }
        startActivityForResult(Intent.createChooser(intent, "saved_model.tflite বেছে নিন"), REQ_MODEL_PICK)
    }

    private fun importModel(uri: Uri) {
        uiScope.launch {
            tvModelStatus.text = "⏳ কপি হচ্ছে..."
            tvModelStatus.setTextColor(Color.parseColor("#FF9800"))
            btnUploadModel.isEnabled = false

            val ok = withContext(Dispatchers.IO) {
                try {
                    val inp = contentResolver.openInputStream(uri) ?: return@withContext false
                    inp.use { input ->
                        FileOutputStream(ContentClassifier.modelFile(this@MainActivity))
                            .use { out -> input.copyTo(out) }
                    }
                    true
                } catch (e: Exception) { e.printStackTrace(); false }
            }

            btnUploadModel.isEnabled = true
            if (ok) {
                Toast.makeText(this@MainActivity, "✅ Model আপলোড সফল!", Toast.LENGTH_SHORT).show()
                classifier?.close()
                val newClassifier = withContext(Dispatchers.IO) {
                    ContentClassifier(applicationContext).also { it.load() }
                }
                classifier = newClassifier
                sendBroadcast(Intent(BlockerAccessibilityService.ACTION_RELOAD_MODEL))
            } else {
                Toast.makeText(this@MainActivity, "❌ আপলোড ব্যর্থ", Toast.LENGTH_SHORT).show()
            }
            refreshStatus()
        }
    }

    // ════════════════════════════════════════════════════════════════
    // TEST
    // ════════════════════════════════════════════════════════════════

    private fun pickTestImage() {
        val intent = Intent(Intent.ACTION_PICK).apply { type = "image/*" }
        startActivityForResult(Intent.createChooser(intent, "Test Image বেছে নিন"), REQ_IMAGE_PICK)
    }

    private fun runTest() {
        val bmp = testBitmap ?: run {
            Toast.makeText(this, "আগে ছবি বেছে নিন", Toast.LENGTH_SHORT).show(); return
        }
        if (!ContentClassifier.isReady(this)) {
            Toast.makeText(this, "আগে Model আপলোড করুন", Toast.LENGTH_SHORT).show(); return
        }
        uiScope.launch {
            progressTest.visibility = View.VISIBLE
            btnRunTest.isEnabled    = false
            tvTestResult.text       = "⏳ বিশ্লেষণ চলছে..."
            tvTestResult.setTextColor(Color.parseColor("#AAAAAA"))
            tvTestResult.setBackgroundColor(Color.parseColor("#1A1A1A"))

            val result = withContext(Dispatchers.Default) {
                if (classifier == null)
                    classifier = ContentClassifier(applicationContext).also { it.load() }
                classifier?.classify(bmp)
            }

            progressTest.visibility = View.GONE
            btnRunTest.isEnabled    = true

            if (result == null) {
                tvTestResult.text = "❌ Classifier লোড হয়নি।"
                tvTestResult.setTextColor(Color.parseColor("#FF5252"))
                tvTestResult.setBackgroundColor(Color.parseColor("#3E0000"))
                return@launch
            }

            tvTestResult.text = buildString {
                appendLine(result.label)
                appendLine()
                appendLine("Safe:   ${"%.1f".format(result.safeScore * 100)}%")
                append("Unsafe: ${"%.1f".format(result.unsafeScore * 100)}%")
            }
            tvTestResult.setTextColor(if (result.isAdult) Color.parseColor("#FF5252") else Color.parseColor("#69F0AE"))
            tvTestResult.setBackgroundColor(if (result.isAdult) Color.parseColor("#3E0000") else Color.parseColor("#003E1F"))
        }
    }

    // ════════════════════════════════════════════════════════════════
    // PERMISSIONS
    // ════════════════════════════════════════════════════════════════

    private fun toggleVideoScan() {
        val isOn = MediaProjectionCaptureService.isEnabled(this)
        if (isOn) {
            // বন্ধ করো
            MediaProjectionCaptureService.setEnabled(this, false)
            MediaProjectionCaptureService.stop(this)
            refreshStatus()
        } else {
            // চালু করো — permission dialog
            val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(mgr.createScreenCaptureIntent(), REQ_MEDIA_PROJ)
        }
    }

    private fun requestDeviceAdmin() {
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, DeviceAdminReceiver.getComponentName(this@MainActivity))
            putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Device Admin হলে app সহজে uninstall করা যাবে না।")
        }
        startActivityForResult(intent, REQ_ADMIN)
    }

    private fun openAccessibility() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        Toast.makeText(this, "Bulldog Blocker খুঁজে চালু করুন", Toast.LENGTH_LONG).show()
    }

    private fun isAccessibilityEnabled(): Boolean {
        val fullComponent = "$packageName/${BlockerAccessibilityService::class.java.name}"
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return enabled.split(":").any { it.equals(fullComponent, ignoreCase = true) }
    }

    // ════════════════════════════════════════════════════════════════
    // ACTIVITY RESULT
    // ════════════════════════════════════════════════════════════════

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        // MediaProjection: data একটা Intent (Uri নয়)
        if (requestCode == REQ_MEDIA_PROJ) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                MediaProjectionCaptureService.setEnabled(this, true)
                MediaProjectionCaptureService.start(this, resultCode, data)
                refreshStatus()
                Toast.makeText(this, "🎥 Video Scan চালু হয়েছে", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Permission দেওয়া হয়নি", Toast.LENGTH_SHORT).show()
            }
            return
        }

        if (resultCode != Activity.RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            REQ_MODEL_PICK -> importModel(uri)
            REQ_IMAGE_PICK -> {
                val bmp = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: return
                testBitmap?.recycle(); testBitmap = bmp
                ivTestImage.setImageBitmap(bmp)
                tvTestResult.text = "▶ Test করুন বোতাম চাপুন"
                tvTestResult.setTextColor(Color.parseColor("#AAAAAA"))
                tvTestResult.setBackgroundColor(Color.parseColor("#1A1A1A"))
            }
            REQ_MEDIA_PROJ -> {
                MediaProjectionCaptureService.setEnabled(this, true)
                MediaProjectionCaptureService.start(this, resultCode, uri ?: return)
                refreshStatus()
                Toast.makeText(this, "🎥 Video Scan চালু হয়েছে", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ════════════════════════════════════════════════════════════════
    // UI HELPERS
    // ════════════════════════════════════════════════════════════════

    private fun vbox(bg: String = "#1E1E30", pad: Int = 32) =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.parseColor(bg))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

    private fun card() = vbox(bg = "#1E1E30", pad = 28).apply {
        (layoutParams as LinearLayout.LayoutParams).apply { marginStart = 16; marginEnd = 16 }
    }

    private fun sectionLabel(text: String) = TextView(this).apply {
        this.text = text; textSize = 11f
        setTextColor(Color.parseColor("#666666"))
        setPadding(32, 0, 0, 8)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }

    private fun tv(text: String, size: Float = 14f, color: String = "#DDDDDD", center: Boolean = false) =
        TextView(this).apply {
            this.text = text; textSize = size
            setTextColor(Color.parseColor(color))
            if (center) gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

    private fun btn(label: String, bg: String, onClick: () -> Unit) =
        Button(this).apply {
            text = label
            setBackgroundColor(Color.parseColor(bg))
            setTextColor(Color.WHITE); textSize = 13f
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            setOnClickListener { onClick() }
        }

    private fun gap(dp: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp)
    }
}
