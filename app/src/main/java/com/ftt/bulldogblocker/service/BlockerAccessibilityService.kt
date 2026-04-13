package com.ftt.bulldogblocker.service

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.ftt.bulldogblocker.ml.ContentClassifier
import com.ftt.bulldogblocker.ui.BlockScreenActivity
import com.ftt.bulldogblocker.ui.UninstallDelayActivity
import kotlinx.coroutines.*

class BlockerAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG            = "BDB_Accessibility"
        private const val OUR_PACKAGE    = "com.ftt.bulldogblocker"
        private const val BLOCK_COOLDOWN = 3_000L
        private const val URL_DEBOUNCE   = 600L

        private val BROWSER_PACKAGES = setOf(
            "com.android.chrome", "org.mozilla.firefox", "com.opera.browser",
            "com.brave.browser", "com.microsoft.emmx", "com.UCMobile.intl",
            "com.sec.android.app.sbrowser", "com.kiwibrowser.browser"
        )
        private val INSTALLER_PACKAGES = setOf(
            "com.android.packageinstaller", "com.google.android.packageinstaller",
            "com.miui.packageinstaller"
        )
        const val ACTION_RELOAD_MODEL = "com.ftt.bulldogblocker.RELOAD_MODEL"
        private val BLOCKED_KEYWORDS = listOf(
            "porn","xxx","adult","sex","nude","nsfw","hentai","erotic",
            "xvideos","xhamster","pornhub","redtube","youporn","onlyfans",
            "chaturbate","xnxx","brazzers","bangbros"
        )
    }

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var classifier: ContentClassifier? = null
    private var classifierLoaded = false

    // Held as Any? — zero compile-time API-30 type references in THIS file.
    // This prevents VerifyError / NoClassDefFoundError on API 26-29 devices.
    private var screenshotBlocker: Any? = null

    @Volatile private var lastBlockTime     = 0L
    @Volatile private var lastUninstallTime = 0L
    private var urlDebounceJob: Job?        = null

    private val modelReloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action == ACTION_RELOAD_MODEL) {
                classifierLoaded = false
                classifier?.close()
                classifier = null
                loadClassifier()
            }
        }
    }

    override fun onServiceConnected() {
        Log.d(TAG, "Service connected")
        loadClassifier()
        registerModelReloadReceiver()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return
        when {
            pkg in INSTALLER_PACKAGES     -> handleUninstallViaInstaller(event)
            pkg == "com.android.settings" -> handleUninstallViaSettings(event)
            pkg in BROWSER_PACKAGES       -> handleBrowserEvent(event)
        }
    }

    override fun onInterrupt() { Log.w(TAG, "Service interrupted") }

    override fun onDestroy() {
        (screenshotBlocker as? ScreenshotBlocker)?.stop()
        screenshotBlocker = null
        serviceScope.cancel()
        classifier?.close()
        classifier = null
        try { unregisterReceiver(modelReloadReceiver) } catch (_: Exception) {}
    }

    private fun loadClassifier() {
        if (classifierLoaded) return
        serviceScope.launch {
            val c = ContentClassifier(applicationContext)
            val ok = c.load()
            classifierLoaded = ok
            if (ok) {
                classifier = c
                Log.d(TAG, "Classifier loaded")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    startScreenshotBlocker(c)
                }
            } else {
                delay(30_000L)
                classifierLoaded = false
                loadClassifier()
            }
        }
    }

    // ScreenshotBlocker instantiated only on API 30+
    private fun startScreenshotBlocker(c: ContentClassifier) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val existing = screenshotBlocker as? ScreenshotBlocker
        if (existing != null) { existing.updateClassifier(c); return }
        val blocker = ScreenshotBlocker(
            service         = this,
            scope           = serviceScope,
            onAdultDetected = { reason ->
                serviceScope.launch(Dispatchers.Main) { triggerBlock(reason) }
            }
        )
        blocker.start(c)
        screenshotBlocker = blocker
    }

    private fun triggerBlock(reason: String) {
        val now = System.currentTimeMillis()
        if (now - lastBlockTime < BLOCK_COOLDOWN) return
        lastBlockTime = now
        (screenshotBlocker as? ScreenshotBlocker)?.resetCooldown()
        performGlobalAction(GLOBAL_ACTION_BACK)
        try {
            startActivity(Intent(this, BlockScreenActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra("reason", reason)
            })
        } catch (e: Exception) { Log.e(TAG, "launch BlockScreen failed", e) }
    }

    private fun handleUninstallViaInstaller(event: AccessibilityEvent) {
        val root = rootInActiveWindow ?: return
        try {
            if ("bulldog" in collectAllText(root).lowercase() ||
                OUR_PACKAGE in collectAllText(root).lowercase()) launchUninstallDelay()
        } finally { root.recycle() }
    }

    private fun handleUninstallViaSettings(event: AccessibilityEvent) {
        val root = rootInActiveWindow ?: return
        try {
            val t = collectAllText(root).lowercase()
            if ("bulldog" in t && "uninstall" in t) launchUninstallDelay()
        } finally { root.recycle() }
    }

    private fun launchUninstallDelay() {
        val now = System.currentTimeMillis()
        if (now - lastUninstallTime < 5_000L) return
        lastUninstallTime = now
        try {
            startActivity(Intent(this, UninstallDelayActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            })
        } catch (e: Exception) { Log.e(TAG, "launch UninstallDelay failed", e) }
    }

    private fun handleBrowserEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return
        urlDebounceJob?.cancel()
        urlDebounceJob = serviceScope.launch {
            delay(URL_DEBOUNCE)
            if (System.currentTimeMillis() - lastBlockTime < BLOCK_COOLDOWN) return@launch
            val root = rootInActiveWindow ?: return@launch
            try {
                val url = getUrlBarText(root)?.lowercase() ?: return@launch
                if (BLOCKED_KEYWORDS.any { url.contains(it) }) {
                    withContext(Dispatchers.Main) { triggerBlock("ব্লক করা সাইট: $url") }
                }
            } finally { root.recycle() }
        }
    }

    private fun getUrlBarText(root: AccessibilityNodeInfo): String? {
        listOf(
            "com.android.chrome:id/url_bar",
            "com.android.chrome:id/search_box_text",
            "org.mozilla.firefox:id/mozac_browser_toolbar_url_view",
            "com.brave.browser:id/url_bar"
        ).forEach { id ->
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            if (nodes.isNotEmpty()) {
                val text = nodes[0].text?.toString()
                nodes.forEach { it.recycle() }
                if (text != null) return text
            }
        }
        return findEditableText(root)
    }

    private fun findEditableText(node: AccessibilityNodeInfo): String? {
        if (node.isEditable && node.text != null) return node.text.toString()
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val r = findEditableText(child); child.recycle()
            if (r != null) return r
        }
        return null
    }

    private fun collectAllText(node: AccessibilityNodeInfo): String {
        val sb = StringBuilder()
        fun go(n: AccessibilityNodeInfo?) {
            n ?: return
            n.text?.let { sb.append(it).append(' ') }
            n.contentDescription?.let { sb.append(it).append(' ') }
            for (i in 0 until n.childCount) { val c = n.getChild(i) ?: continue; go(c); c.recycle() }
        }
        go(node); return sb.toString()
    }

    private fun registerModelReloadReceiver() {
        try {
            val f = IntentFilter(ACTION_RELOAD_MODEL)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                registerReceiver(modelReloadReceiver, f, RECEIVER_NOT_EXPORTED)
            else
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(modelReloadReceiver, f)
        } catch (e: Exception) { Log.e(TAG, "registerReceiver error", e) }
    }
}
