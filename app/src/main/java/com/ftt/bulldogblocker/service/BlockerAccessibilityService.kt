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
import com.ftt.bulldogblocker.AppListManager
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
        // Social apps — window text scan (কোনো URL bar নেই)
        private val SOCIAL_PACKAGES = setOf(
            "com.facebook.katana", "com.facebook.lite",
            "com.twitter.android", "com.x.android",
            "com.instagram.android", "com.whatsapp",
            "com.snapchat.android", "com.reddit.frontpage"
        )
        private val INSTALLER_PACKAGES = setOf(
            "com.android.packageinstaller", "com.google.android.packageinstaller",
            "com.miui.packageinstaller"
        )

        const val ACTION_RELOAD_MODEL = "com.ftt.bulldogblocker.RELOAD_MODEL"

        private val BLOCKED_KEYWORDS = listOf(
            "porn","xxx","adult","sex","nude","nsfw","hentai","erotic",
            "xvideos","xhamster","pornhub","redtube","youporn","onlyfans",
            "chaturbate","xnxx","brazzers","bangbros","18+","nudity",
            "sexy","boobs","tits","ass","dick","cock","pussy","vagina",
            "penis","naked","strip","lingerie","bikini model","hot girls"
        )
    }

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @Volatile private var classifier: ContentClassifier? = null
    // BUG FIX: @Volatile missing — written by broadcast receiver (main thread),
    // read/written in loadClassifier() coroutine (Dispatchers.Default) → data race
    @Volatile private var classifierLoaded = false

    // Held as Any? — zero compile-time API-30 type references in THIS file.
    // BUG FIX: @Volatile missing — written on Dispatchers.Default (startScreenshotBlocker),
    // read on main thread (triggerBlock, onDestroy) → data race
    @Volatile private var screenshotBlocker: Any? = null

    @Volatile private var lastBlockTime     = 0L
    @Volatile private var lastUninstallTime = 0L
    private var urlDebounceJob: Job?        = null
    private var windowDebounceJob: Job?     = null  // BUG FIX: separate job — shared job caused browser/social events to cancel each other

    // ── App list cache (reload on broadcast) ─────────────────────────
    @Volatile private var blacklistCache: Set<String> = emptySet()
    @Volatile private var whitelistCache: Set<String> = emptySet()

    // ── Receivers ────────────────────────────────────────────────────

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

    private val listsChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action == AppListManager.ACTION_LISTS_CHANGED) {
                reloadAppLists()
            }
        }
    }

    // ── Lifecycle ────────────────────────────────────────────────────

    override fun onServiceConnected() {
        Log.d(TAG, "Service connected")
        reloadAppLists()
        loadClassifier()
        registerReceivers()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return

        // Whitelist → সব skip
        if (pkg in whitelistCache) return

        when {
            pkg in INSTALLER_PACKAGES     -> handleUninstallViaInstaller(event)
            pkg == "com.android.settings" -> handleUninstallViaSettings(event)
            pkg in BROWSER_PACKAGES       -> handleBrowserEvent(event)
            // Social apps + user-defined blacklist apps → window text keyword scan
            pkg in SOCIAL_PACKAGES        -> handleWindowTextEvent(event)
            pkg in blacklistCache         -> handleWindowTextEvent(event)
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
        try { unregisterReceiver(listsChangedReceiver) } catch (_: Exception) {}
    }

    // ── App list cache ────────────────────────────────────────────────

    private fun reloadAppLists() {
        blacklistCache = AppListManager.getBlacklist(applicationContext)
        whitelistCache = AppListManager.getWhitelist(applicationContext)
        Log.d(TAG, "Lists reloaded — black:${blacklistCache.size} white:${whitelistCache.size}")
    }

    // ── Classifier ───────────────────────────────────────────────────

    private fun loadClassifier() {
        if (classifierLoaded) return
        serviceScope.launch {
            val c = ContentClassifier(applicationContext)
            val ok = c.load()
            classifierLoaded = ok
            if (ok) {
                classifier?.close()
                classifier = c
                Log.d(TAG, "Classifier loaded")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    startScreenshotBlocker(c)
                }
            } else {
                c.close()
                delay(30_000L)
                classifierLoaded = false
                loadClassifier()
            }
        }
    }

    private fun startScreenshotBlocker(c: ContentClassifier) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val existing = screenshotBlocker as? ScreenshotBlocker
        if (existing != null) { existing.updateClassifier(c); return }
        val blocker = ScreenshotBlocker(
            service         = this,
            scope           = serviceScope,
            onAdultDetected = { reason, hash, score, showReport ->
                serviceScope.launch(Dispatchers.Main) { triggerBlock(reason, hash, score, showReport) }
            }
        )
        blocker.start(c)
        screenshotBlocker = blocker
    }

    // ── Block trigger ─────────────────────────────────────────────────

    private fun triggerBlock(
        reason:      String,
        hash:        Long    = 0L,
        score:       Float   = 1f,
        showReport:  Boolean = false
    ) {
        val now = System.currentTimeMillis()
        if (now - lastBlockTime < BLOCK_COOLDOWN) return
        lastBlockTime = now
        (screenshotBlocker as? ScreenshotBlocker)?.resetCooldown()
        // BUG FIX: BACK → HOME — browser থেকে সম্পূর্ণ বের হয়
        performGlobalAction(GLOBAL_ACTION_HOME)
        try {
            startActivity(Intent(this, BlockScreenActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra("reason",      reason)
                putExtra("image_hash",  hash)
                putExtra("score",       score)
                putExtra("show_report", showReport)
            })
        } catch (e: Exception) { Log.e(TAG, "launch BlockScreen failed", e) }
    }

    // ── Uninstall protection ──────────────────────────────────────────

    private fun handleUninstallViaInstaller(event: AccessibilityEvent) {
        // BUG FIX: no event type filter — was scanning full accessibility tree on EVERY event
        // (scrolls, clicks, focus changes) from installer apps. Very expensive.
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val root = rootInActiveWindow ?: return
        try {
            val text = collectAllText(root).lowercase()
            if ("bulldog" in text || OUR_PACKAGE in text) launchUninstallDelay()
        } finally { root.recycle() }
    }

    private fun handleUninstallViaSettings(event: AccessibilityEvent) {
        // BUG FIX: same missing event type filter as handleUninstallViaInstaller
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
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

    // ── Browser event — URL bar keyword check ─────────────────────────

    private fun handleBrowserEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return
        debounceCheck {
            val root = rootInActiveWindow ?: return@debounceCheck
            try {
                // BUG FIX: was `?: return@debounceCheck` inside try-finally block.
                // return@label inside try-finally in a non-inline suspend lambda is a compile error.
                // Fix: use null check with if-else instead.
                val url = getUrlBarText(root)?.lowercase()
                if (url != null && BLOCKED_KEYWORDS.any { url.contains(it) }) {
                    withContext(Dispatchers.Main) { triggerBlock("ব্লক করা সাইট: $url") }
                }
            } finally { root.recycle() }
        }
    }

    // ── Social / Blacklist apps — window text keyword scan ────────────

    private fun handleWindowTextEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        // BUG FIX: dedicated windowDebounceJob — avoids cancelling browser URL debounce
        debounceWindowCheck {
            val root = rootInActiveWindow ?: return@debounceWindowCheck  // BUG FIX: was return@debounceCheck → unresolved after rename
            try {
                val text = collectAllText(root).lowercase()
                val hit = BLOCKED_KEYWORDS.firstOrNull { text.contains(it) }
                if (hit != null) {
                    withContext(Dispatchers.Main) { triggerBlock("ব্লক করা কন্টেন্ট: $hit") }
                }
            } finally { root.recycle() }
        }
    }

    // ── Debounce helper ───────────────────────────────────────────────

    private fun debounceCheck(block: suspend CoroutineScope.() -> Unit) {
        urlDebounceJob?.cancel()
        urlDebounceJob = serviceScope.launch {
            delay(URL_DEBOUNCE)
            if (System.currentTimeMillis() - lastBlockTime < BLOCK_COOLDOWN) return@launch
            block()
        }
    }

    // BUG FIX: separate debounce for window-text events so they don't cancel URL debounce
    private fun debounceWindowCheck(block: suspend CoroutineScope.() -> Unit) {
        windowDebounceJob?.cancel()
        windowDebounceJob = serviceScope.launch {
            delay(URL_DEBOUNCE)
            if (System.currentTimeMillis() - lastBlockTime < BLOCK_COOLDOWN) return@launch
            block()
        }
    }

    // ── URL bar helpers ───────────────────────────────────────────────

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

    private fun findEditableText(node: AccessibilityNodeInfo, depth: Int = 0): String? {
        if (depth > 20) return null
        if (node.isEditable && node.text != null) return node.text.toString()
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val r = findEditableText(child, depth + 1); child.recycle()
            if (r != null) return r
        }
        return null
    }

    private fun collectAllText(node: AccessibilityNodeInfo): String {
        val sb = StringBuilder()
        fun go(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 20) return
            n.text?.let { sb.append(it).append(' ') }
            n.contentDescription?.let { sb.append(it).append(' ') }
            for (i in 0 until n.childCount) {
                val c = n.getChild(i) ?: continue
                go(c, depth + 1)
                c.recycle()
            }
        }
        go(node, 0)
        return sb.toString()
    }

    // ── Receiver registration ─────────────────────────────────────────

    private fun registerReceivers() {
        // BUG FIX: `val flags` was declared here but never used — dead code removed

        fun reg(receiver: BroadcastReceiver, action: String) {
            try {
                val f = IntentFilter(action)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    registerReceiver(receiver, f, RECEIVER_NOT_EXPORTED)
                else
                    @Suppress("UnspecifiedRegisterReceiverFlag")
                    registerReceiver(receiver, f)
            } catch (e: Exception) { Log.e(TAG, "registerReceiver error: $action", e) }
        }

        reg(modelReloadReceiver, ACTION_RELOAD_MODEL)
        reg(listsChangedReceiver, AppListManager.ACTION_LISTS_CHANGED)
    }
}
