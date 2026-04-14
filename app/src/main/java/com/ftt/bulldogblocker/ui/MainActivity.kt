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
import com.ftt.bulldogblocker.AppReportManager
import com.ftt.bulldogblocker.ThresholdManager
import com.ftt.bulldogblocker.UninstallProtectionManager
import com.ftt.bulldogblocker.admin.DeviceAdminReceiver
import com.ftt.bulldogblocker.ml.ContentClassifier
import com.ftt.bulldogblocker.service.BlockerAccessibilityService
import com.ftt.bulldogblocker.service.BlockerForegroundService
import kotlinx.coroutines.*
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQ_ADMIN      = 101
        private const val REQ_MODEL_PICK = 102
        private const val REQ_IMAGE_PICK = 103
    }

    private lateinit var tvOverallStatus : TextView
    private lateinit var tvAdminStatus   : TextView
    private lateinit var btnAdmin        : Button
    private lateinit var tvAccessStatus  : TextView
    private lateinit var btnAccess       : Button
    private lateinit var tvModelStatus   : TextView
    private lateinit var tvModelInfo     : TextView
    private lateinit var tvMlScanStatus  : TextView
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

        // ── Uninstall Protection Settings ────────────────────────────
        root.addView(sectionLabel("🛡️ UNINSTALL PROTECTION"))
        root.addView(buildUninstallProtectionCard())

        root.addView(gap(24))

        // ── Content Report Settings ───────────────────────────────────
        root.addView(sectionLabel("📊 CONTENT REPORT SETTINGS"))
        root.addView(buildReportSettingsCard())

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

        root.addView(gap(24))

        // ── Threshold Settings ───────────────────────────────────────
        root.addView(sectionLabel("⚙️ THRESHOLD SETTINGS"))

        root.addView(card().apply {
            addView(tv(
                "কম threshold = বেশি কঠোর (বেশি false positive হতে পারে)\nবেশি threshold = কম কঠোর (কিছু content মিস হতে পারে)",
                size = 11f, color = "#888888"
            ))
            addView(gap(16))

            // ── Manual Test Threshold ──────────────────────────────
            val manualPct = (ThresholdManager.getManual(this@MainActivity) * 100).toInt()
            val tvManualLabel = tv("🧪 Manual Test Threshold: ${manualPct}%", size = 14f, color = "#FFFFFF")
            addView(tvManualLabel)
            addView(gap(8))

            val sbManual = android.widget.SeekBar(this@MainActivity).apply {
                max      = 80          // 0–80 → 10%–90%
                progress = manualPct - 10
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                        val pct = p + 10
                        tvManualLabel.text = "🧪 Manual Test Threshold: ${pct}%"
                    }
                    override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
                    override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {
                        val pct = (sb?.progress ?: 30) + 10
                        ThresholdManager.setManual(this@MainActivity, pct / 100f)
                        Toast.makeText(this@MainActivity, "✅ Manual threshold: ${pct}%", Toast.LENGTH_SHORT).show()
                    }
                })
            }
            addView(sbManual)
            addView(tv("← বেশি কঠোর (10%)    কম কঠোর (90%) →", size = 10f, color = "#666666"))
            addView(gap(20))

            // ── Screenshot Auto-scan Threshold ────────────────────
            val screenshotPct = (ThresholdManager.getScreenshot(this@MainActivity) * 100).toInt()
            val tvSsLabel = tv("📸 Auto-Scan Threshold: ${screenshotPct}%", size = 14f, color = "#FFFFFF")
            addView(tvSsLabel)
            addView(gap(4))
            addView(tv(
                "কম রাখলে ভালো — screenshot-এ content dilute হয়",
                size = 11f, color = "#888888"
            ))
            addView(gap(8))

            val sbScreenshot = android.widget.SeekBar(this@MainActivity).apply {
                max      = 55          // 0–55 → 5%–60%
                progress = screenshotPct - 5
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                        val pct = p + 5
                        tvSsLabel.text = "📸 Auto-Scan Threshold: ${pct}%"
                    }
                    override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
                    override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {
                        val pct = (sb?.progress ?: 17) + 5
                        ThresholdManager.setScreenshot(this@MainActivity, pct / 100f)
                        refreshStatus()
                        Toast.makeText(this@MainActivity, "✅ Auto-scan threshold: ${pct}%", Toast.LENGTH_SHORT).show()
                    }
                })
            }
            addView(sbScreenshot)
            addView(tv("← বেশি কঠোর (5%)    কম কঠোর (60%) →", size = 10f, color = "#666666"))

            addView(gap(20))

            // ── Sexy Alone Threshold (5-class model only) ─────────────
            // BUG FIX v8.2: ThresholdManager-এ sexy_alone threshold আছে কিন্তু
            // MainActivity-তে এর কোনো SeekBar ছিল না।
            // inception_v3 (5-class) model ব্যবহারকারীরা এই value adjust করতে পারতেন না।
            val sexyPct = (ThresholdManager.getSexyAlone(this@MainActivity) * 100).toInt()
            val tvSexyLabel = tv("💃 Sexy-Alone Threshold: ${sexyPct}%", size = 14f, color = "#FFFFFF")
            addView(tvSexyLabel)
            addView(gap(4))
            addView(tv(
                "5-class model: শুধু sexy score এই মান পার করলেই block (bra/lingerie)\n2-class model-এ এই setting কাজ করে না",
                size = 11f, color = "#888888"
            ))
            addView(gap(8))

            val sbSexy = android.widget.SeekBar(this@MainActivity).apply {
                max      = 60          // 0–60 → 20%–80%
                progress = sexyPct - 20
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                        val pct = p + 20
                        tvSexyLabel.text = "💃 Sexy-Alone Threshold: ${pct}%"
                    }
                    override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
                    override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {
                        val pct = (sb?.progress ?: 25) + 20
                        ThresholdManager.setSexyAlone(this@MainActivity, pct / 100f)
                        Toast.makeText(this@MainActivity, "✅ Sexy-alone threshold: ${pct}%", Toast.LENGTH_SHORT).show()
                    }
                })
            }
            addView(sbSexy)
            addView(tv("← বেশি কঠোর (20%)    কম কঠোর (80%) →", size = 10f, color = "#666666"))
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
                    // BUG FIX: আগে সব installed app-এর জন্য আলাদা add/remove ডাকা হতো
                    // (50 apps → 50-100 broadcast)। এখন: একটাই batch update + একটাই broadcast।
                    val newSelected = apps
                        .filterIndexed { i, _ -> selected[i] }
                        .map { it.pkg }
                        .toSet()

                    if (isBlacklist) AppListManager.setBlacklist(this@MainActivity, newSelected)
                    else             AppListManager.setWhitelist(this@MainActivity, newSelected)

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
            val mlPath = ContentClassifier.mlDir(this).absolutePath
            tvModelInfo.text = "📁 ${ContentClassifier.ML_DIR_NAME}/${ContentClassifier.MODEL_FILENAME}  •  ${mb} MB\n🗂 $mlPath"
            btnUploadModel.text = "🔄 Model পরিবর্তন করুন"
        } else {
            tvModelStatus.text = "⚠️ Model আপলোড করা হয়নি"
            tvModelStatus.setTextColor(Color.parseColor("#FF9800"))
            tvModelInfo.text = ".tflite ফাইল বেছে নিন"
            btnUploadModel.text = "📂 Model Upload করুন (.tflite)"
        }

        // ML Auto-Scanner status
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // BUG FIX: hardcoded "22%" এর বদলে ThresholdManager থেকে পড়া হচ্ছে
            val ssThresholdPct = (ThresholdManager.getScreenshot(this) * 100).toInt()
            tvMlScanStatus.text = "📸 Auto ML Scan: চালু (প্রতি 1500ms, threshold ${ssThresholdPct}%)"
            tvMlScanStatus.setTextColor(Color.parseColor("#4CAF50"))
        } else {
            tvMlScanStatus.text = "⚠️ Auto ML Scan: Android 11+ লাগবে (আপনার Android ${Build.VERSION.RELEASE})"
            tvMlScanStatus.setTextColor(Color.parseColor("#FF9800"))
        }

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
                // BUG FIX: add setPackage() — unprotected broadcast allowed any app to trigger model reload
                sendBroadcast(Intent(BlockerAccessibilityService.ACTION_RELOAD_MODEL).apply {
                    setPackage(packageName)
                })
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
            REQ_ADMIN -> refreshStatus()
        }
    }

    // ════════════════════════════════════════════════════════════════
    // UNINSTALL PROTECTION CARD
    // ════════════════════════════════════════════════════════════════

    private fun buildUninstallProtectionCard(): LinearLayout {
        val card = card()
        val ctx  = this

        card.addView(tv(
            "Uninstall করার আগে কতক্ষণ অপেক্ষা করতে হবে তা সেট করুন:",
            size = 12f, color = "#AAAAAA"
        ))
        card.addView(gap(12))

        // ── Mode selector ────────────────────────────────────────────
        val currentMode = UninstallProtectionManager.getMode(this)

        val radioGroup = android.widget.RadioGroup(this).apply {
            orientation = android.widget.RadioGroup.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        fun makeRadio(label: String, mode: UninstallProtectionManager.Mode) =
            android.widget.RadioButton(ctx).apply {
                text = label
                id   = android.view.View.generateViewId()
                setTextColor(Color.parseColor("#DDDDDD"))
                textSize = 12f
                tag  = mode
                isChecked = (mode == currentMode)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

        val rbTesting = makeRadio("🧪 Testing", UninstallProtectionManager.Mode.TESTING)
        val rbTime    = makeRadio("⏱ By Time",  UninstallProtectionManager.Mode.BY_TIME)
        val rbDate    = makeRadio("📅 By Date",  UninstallProtectionManager.Mode.BY_DATE)

        radioGroup.addView(rbTesting)
        radioGroup.addView(rbTime)
        radioGroup.addView(rbDate)
        card.addView(radioGroup)
        card.addView(gap(16))

        // ── BY_TIME controls ─────────────────────────────────────────
        val timePanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility  = if (currentMode == UninstallProtectionManager.Mode.BY_TIME)
                android.view.View.VISIBLE else android.view.View.GONE
        }
        val delayMin = UninstallProtectionManager.getDelayMinutes(this)
        val tvTimeLabel = tv("⏱ Delay: ${formatMinutes(delayMin)}", size = 14f, color = "#FFFFFF")
        timePanel.addView(tvTimeLabel)
        timePanel.addView(gap(8))

        val sbTime = android.widget.SeekBar(this).apply {
            max      = 299     // 1–300 minutes
            progress = (delayMin - 1).coerceIn(0, 299)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                    val m = p + 1
                    tvTimeLabel.text = "⏱ Delay: ${formatMinutes(m)}"
                }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {
                    val m = (sb?.progress ?: 59) + 1
                    UninstallProtectionManager.setDelayMinutes(ctx, m)
                    UninstallProtectionManager.resetAttempt(ctx)   // reset so new delay applies
                    Toast.makeText(ctx, "✅ Delay: ${formatMinutes(m)}", Toast.LENGTH_SHORT).show()
                }
            })
        }
        timePanel.addView(sbTime)
        timePanel.addView(tv("← 1 মিনিট                        300 মিনিট →", size = 10f, color = "#666666"))
        card.addView(timePanel)

        // ── BY_DATE controls ─────────────────────────────────────────
        val datePanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility  = if (currentMode == UninstallProtectionManager.Mode.BY_DATE)
                android.view.View.VISIBLE else android.view.View.GONE
        }
        val delayDays = UninstallProtectionManager.getDelayDays(this)
        val tvDateLabel = tv("📅 Delay: ${delayDays} দিন", size = 14f, color = "#FFFFFF")
        datePanel.addView(tvDateLabel)
        datePanel.addView(gap(8))

        val sbDate = android.widget.SeekBar(this).apply {
            max      = 29     // 1–30 days
            progress = (delayDays - 1).coerceIn(0, 29)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                    tvDateLabel.text = "📅 Delay: ${p + 1} দিন"
                }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {
                    val d = (sb?.progress ?: 2) + 1
                    UninstallProtectionManager.setDelayDays(ctx, d)
                    UninstallProtectionManager.resetAttempt(ctx)
                    Toast.makeText(ctx, "✅ Delay: $d দিন", Toast.LENGTH_SHORT).show()
                }
            })
        }
        datePanel.addView(sbDate)
        datePanel.addView(tv("← ১ দিন                              ৩০ দিন →", size = 10f, color = "#666666"))
        card.addView(datePanel)

        // ── Radio group change listener ──────────────────────────────
        // BUG FIX #6: isRadioInitializing guard।
        // RadioGroup কখনো startup-এ checked state reconcile করতে গিয়ে listener fire করতে পারে।
        // সেই ক্ষেত্রে resetAttempt() ডাকলে uninstall timer reset হয়ে যাবে।
        // Fix: listener addView-এর পরে set করা হয়েছে + isRadioInitializing flag দিয়ে guard।
        var isRadioInitializing = true
        radioGroup.setOnCheckedChangeListener { _, _ ->
            if (isRadioInitializing) return@setOnCheckedChangeListener
            val mode = when {
                rbTesting.isChecked -> UninstallProtectionManager.Mode.TESTING
                rbTime.isChecked    -> UninstallProtectionManager.Mode.BY_TIME
                else                -> UninstallProtectionManager.Mode.BY_DATE
            }
            UninstallProtectionManager.setMode(ctx, mode)
            UninstallProtectionManager.resetAttempt(ctx)
            timePanel.visibility = if (mode == UninstallProtectionManager.Mode.BY_TIME)
                android.view.View.VISIBLE else android.view.View.GONE
            datePanel.visibility = if (mode == UninstallProtectionManager.Mode.BY_DATE)
                android.view.View.VISIBLE else android.view.View.GONE
            val label = when (mode) {
                UninstallProtectionManager.Mode.TESTING -> "🧪 Testing (60s)"
                UninstallProtectionManager.Mode.BY_TIME -> "⏱ By Time"
                UninstallProtectionManager.Mode.BY_DATE -> "📅 By Date"
            }
            Toast.makeText(ctx, "✅ Mode: $label", Toast.LENGTH_SHORT).show()
        }
        isRadioInitializing = false   // listener এখন থেকে real user input-এ fire করবে

        // ── Testing mode info ────────────────────────────────────────
        card.addView(gap(8))
        card.addView(tv(
            "🧪 Testing = 60 সেকেন্ড  ·  ⏱ By Time = মিনিট/ঘণ্টা  ·  📅 By Date = দিন\nPhone restart করলেও timer চলতে থাকে (By Time / By Date)",
            size = 11f, color = "#666666"
        ))

        return card
    }

    private fun formatMinutes(min: Int): String = when {
        min < 60   -> "$min মিনিট"
        min % 60 == 0 -> "${min / 60} ঘণ্টা"
        else       -> "${min / 60} ঘণ্টা ${min % 60} মিনিট"
    }

    // ════════════════════════════════════════════════════════════════
    // CONTENT REPORT SETTINGS CARD
    // ════════════════════════════════════════════════════════════════

    private fun buildReportSettingsCard(): LinearLayout {
        val card = card()
        val ctx  = this

        card.addView(tv(
            "Content ধরা পড়লে সাথে সাথে block হবে না — নির্দিষ্ট বার পর block হবে।\nWarning overlay app-এর উপরে দেখাবে, threshold পার হলে app lock হবে।",
            size = 12f, color = "#AAAAAA"
        ))
        card.addView(gap(16))

        // ── Report threshold ─────────────────────────────────────────
        val curThreshold = AppReportManager.getReportThreshold(this)
        val tvThreshLabel = tv("⚠️ Report Threshold: $curThreshold বার", size = 14f, color = "#FFFFFF")
        card.addView(tvThreshLabel)
        card.addView(gap(4))
        card.addView(tv("এতবার content ধরা পড়লে app block হবে", size = 11f, color = "#888888"))
        card.addView(gap(8))

        val sbThresh = android.widget.SeekBar(this).apply {
            max      = 9    // 1–10
            progress = (curThreshold - 1).coerceIn(0, 9)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                    tvThreshLabel.text = "⚠️ Report Threshold: ${p + 1} বার"
                }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {
                    val n = (sb?.progress ?: 2) + 1
                    AppReportManager.setReportThreshold(ctx, n)
                    Toast.makeText(ctx, "✅ Threshold: $n বার", Toast.LENGTH_SHORT).show()
                }
            })
        }
        card.addView(sbThresh)
        card.addView(tv("← ১ বার (কঠোর)                    ১০ বার (নমনীয়) →", size = 10f, color = "#666666"))

        card.addView(gap(20))

        // ── Block duration ────────────────────────────────────────────
        val curBlockMin = AppReportManager.getBlockDurationMinutes(this)
        val tvBlockLabel = tv("⏱ Block Duration: $curBlockMin মিনিট", size = 14f, color = "#FFFFFF")
        card.addView(tvBlockLabel)
        card.addView(gap(4))
        card.addView(tv("Threshold পার হলে app এতক্ষণ ব্লক থাকবে", size = 11f, color = "#888888"))
        card.addView(gap(8))

        val sbBlock = android.widget.SeekBar(this).apply {
            max      = 115    // 5–120 minutes (step = 1, offset = 5)
            progress = (curBlockMin - 5).coerceIn(0, 115)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                    val m = p + 5
                    tvBlockLabel.text = "⏱ Block Duration: $m মিনিট"
                }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {
                    val m = (sb?.progress ?: 10) + 5
                    AppReportManager.setBlockDurationMs(ctx, m * 60_000L)
                    Toast.makeText(ctx, "✅ Block duration: $m মিনিট", Toast.LENGTH_SHORT).show()
                }
            })
        }
        card.addView(sbBlock)
        card.addView(tv("← ৫ মিনিট                         ১২০ মিনিট →", size = 10f, color = "#666666"))

        return card
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
