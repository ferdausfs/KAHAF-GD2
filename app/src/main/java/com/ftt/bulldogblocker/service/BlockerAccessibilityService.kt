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
import com.ftt.bulldogblocker.AppReportManager
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

        // Word-boundary regex — substring match এড়াতে
        private val BLOCKED_KEYWORD_REGEXES: List<Pair<String, Regex>> by lazy {
            BLOCKED_KEYWORDS.map { kw ->
                kw to Regex("(?i)(?<![a-zA-Z])${Regex.escape(kw)}(?![a-zA-Z])")
            }
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @Volatile private var classifier: ContentClassifier? = null
    @Volatile private var classifierLoaded = false
    @Volatile private var screenshotBlocker: Any? = null

    @Volatile private var lastBlockTime     = 0L
    @Volatile private var lastUninstallTime = 0L
    private var urlDebounceJob: Job?        = null
    private var windowDebounceJob: Job?     = null

    @Volatile private var blacklistCache: Set<String> = emptySet()
    @Volatile private var whitelistCache: Set<String> = emptySet()

    // Foreground package tracker — ScreenshotBlocker & report system-এর জন্য
    @Volatile private var currentForegroundPkg: String = ""

    // ── Report Overlay ────────────────────────────────────────────────
    private var reportOverlay: ReportOverlayManager? = null

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
        reportOverlay = ReportOverlayManager(applicationContext)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return

        // Track foreground package for ScreenshotBlocker + report system
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            currentForegroundPkg = pkg

            // ── Blocked app enforcement ──────────────────────────────
            // যদি blocked app খোলার চেষ্টা হয় → block screen দেখাও
            if (pkg != OUR_PACKAGE && pkg !in whitelistCache &&
                AppReportManager.isBlocked(applicationContext, pkg)) {
                val remMs  = AppReportManager.getBlockRemainingMs(applicationContext, pkg)
                val remMin = (remMs / 60_000L + 1L).toInt()
                serviceScope.launch(Dispatchers.Main) {
                    // BUG FIX: countReport parameter triggerBlockScreen() থেকে সরানো হয়েছে।
                    // এই call-এ শুধু reason — কোনো extra param নেই।
                    triggerBlockScreen(reason = "⏳ App ব্লক — আর $remMin মিনিট বাকি")
                }
                return
            }
        }

        // Whitelist → সব skip
        if (pkg in whitelistCache) return

        when {
            pkg in INSTALLER_PACKAGES     -> handleUninstallViaInstaller(event)
            pkg == "com.android.settings" -> handleUninstallViaSettings(event)
            pkg in BROWSER_PACKAGES       -> handleBrowserEvent(event, pkg)
            pkg in SOCIAL_PACKAGES        -> handleWindowTextEvent(event, pkg)
            pkg in blacklistCache         -> handleWindowTextEvent(event, pkg)
        }
    }

    override fun onInterrupt() { Log.w(TAG, "Service interrupted") }

    override fun onDestroy() {
        (screenshotBlocker as? ScreenshotBlocker)?.stop()
        screenshotBlocker = null
        reportOverlay?.destroy()
        reportOverlay = null
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
            val c  = ContentClassifier(applicationContext)
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
            service                    = this,
            scope                      = serviceScope,
            isForegroundPkgWhitelisted = { whitelistCache.contains(currentForegroundPkg) },
            onAdultDetected            = { reason, hash, score, showReport ->
                serviceScope.launch(Dispatchers.Main) {
                    triggerBlock(
                        reason      = reason,
                        hash        = hash,
                        score       = score,
                        showReport  = showReport,
                        pkg         = currentForegroundPkg
                    )
                }
            }
        )
        blocker.start(c)
        screenshotBlocker = blocker
    }

    // ── Block trigger — CORE LOGIC ────────────────────────────────────

    /**
     * Content detect হলে এই function ডাকা হয়।
     *
     * @param pkg          যে app-এ content পাওয়া গেছে (null = browser/unknown)
     * @param countReport  false হলে report counter বাড়ানো হবে না।
     *                     Browser events-এ false pass করা হয় কারণ Chrome/Firefox-কে
     *                     app হিসেবে block করা উচিত নয়।
     */
    private fun triggerBlock(
        reason:      String,
        hash:        Long    = 0L,
        score:       Float   = 1f,
        showReport:  Boolean = false,
        pkg:         String? = null,
        countReport: Boolean = true
    ) {
        // BUG FIX #2: lastBlockTime এখন শুধু BlockScreenActivity দেখালে set হয়।
        // আগে triggerBlock()-এর শুরুতেই set হতো → overlay দেখালেও ৩ সেকেন্ড blocked।
        // এখন overlay শুধু overlay — cooldown নেই।
        val now = System.currentTimeMillis()

        val ctx = applicationContext

        // ── Report counter logic ──────────────────────────────────────
        if (countReport && pkg != null && pkg != OUR_PACKAGE && pkg.isNotEmpty()) {

            val count     = AppReportManager.addReport(ctx, pkg)
            val threshold = AppReportManager.getReportThreshold(ctx)

            if (count < threshold) {
                // Warning overlay — no full block, no cooldown
                Log.d(TAG, "Report $count/$threshold for $pkg")
                (screenshotBlocker as? ScreenshotBlocker)?.resetCooldown()
                reportOverlay?.show(count, threshold)
                return   // ← no BlockScreenActivity, no cooldown update
            } else {
                // BUG FIX #2: cooldown check এখন addReport()-এর পরে আছে।
                // আগে: addReport() → count>=threshold → cooldown active → return (blockApp ডাকা হয়নি)
                //       কিন্তু count ইতিমধ্যে বেড়ে গেছে → পরের call-এ আরও বাড়বে → overshoot।
                // Fix: cooldown active থাকলেও blockApp() ডাকো — block টা persistent তাই double-call নিরাপদ।
                if (now - lastBlockTime < BLOCK_COOLDOWN) {
                    AppReportManager.blockApp(ctx, pkg)   // idempotent — বারবার call করা নিরাপদ
                    return
                }
                lastBlockTime = now
                AppReportManager.blockApp(ctx, pkg)
                val blockMin = AppReportManager.getBlockDurationMs(ctx) / 60_000L
                val fullReason = "$reason\n🔴 App $blockMin মিনিটের জন্য ব্লক হয়েছে"
                triggerBlockScreen(reason = fullReason, hash = hash, score = score, showReport = showReport)
                return
            }
        }

        // ── Direct block (countReport=false বা browser) ───────────────
        if (now - lastBlockTime < BLOCK_COOLDOWN) return
        lastBlockTime = now
        triggerBlockScreen(reason = reason, hash = hash, score = score, showReport = showReport)
    }

    // BUG FIX #4: countReport parameter সরানো হয়েছে — unused ছিল।
    private fun triggerBlockScreen(
        reason:     String,
        hash:       Long    = 0L,
        score:      Float   = 1f,
        showReport: Boolean = false
    ) {
        (screenshotBlocker as? ScreenshotBlocker)?.resetCooldown()
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
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val root = rootInActiveWindow ?: return
        try {
            val text = collectAllText(root).lowercase()
            if ("bulldog" in text || OUR_PACKAGE in text) launchUninstallDelay()
        } finally { root.recycle() }
    }

    private fun handleUninstallViaSettings(event: AccessibilityEvent) {
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

    // ── Browser event ─────────────────────────────────────────────────

    private fun handleBrowserEvent(event: AccessibilityEvent, pkg: String) {
        if (event.eventType != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return
        debounceCheck {
            val root = rootInActiveWindow ?: return@debounceCheck
            try {
                val url = getUrlBarText(root)?.lowercase()
                if (url != null && containsBlockedKeyword(url) != null) {
                    withContext(Dispatchers.Main) {
                        // BUG FIX #3: countReport=false — browser app (Chrome/Firefox) কে
                        // block করা উচিত নয়, শুধু সেই navigation/URL block হবে।
                        // আগে countReport=true ছিল → ৩ বার bad URL = Chrome নিজেই blocked!
                        triggerBlock("ব্লক করা সাইট: $url", pkg = pkg, countReport = false)
                    }
                }
            } finally { root.recycle() }
        }
    }

    // ── Social / Blacklist apps ───────────────────────────────────────

    private fun handleWindowTextEvent(event: AccessibilityEvent, pkg: String) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        debounceWindowCheck {
            val root = rootInActiveWindow ?: return@debounceWindowCheck
            try {
                val text = collectAllText(root).lowercase()
                val hit  = containsBlockedKeyword(text)
                if (hit != null) {
                    withContext(Dispatchers.Main) {
                        triggerBlock("ব্লক করা কন্টেন্ট: $hit", pkg = pkg)
                    }
                }
            } finally { root.recycle() }
        }
    }

    // ── Keyword match (word-boundary) ─────────────────────────────────

    private fun containsBlockedKeyword(text: String): String? =
        BLOCKED_KEYWORD_REGEXES.firstOrNull { (_, rx) -> rx.containsMatchIn(text) }?.first

    // ── Debounce helpers ──────────────────────────────────────────────

    private fun debounceCheck(block: suspend CoroutineScope.() -> Unit) {
        urlDebounceJob?.cancel()
        urlDebounceJob = serviceScope.launch {
            delay(URL_DEBOUNCE)
            if (System.currentTimeMillis() - lastBlockTime < BLOCK_COOLDOWN) return@launch
            block()
        }
    }

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
