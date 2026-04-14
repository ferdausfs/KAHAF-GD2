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

        private val SYSTEM_UI_PACKAGES = setOf(
            "android",
            "com.android.systemui",
            "com.google.android.inputmethod.latin",
            "com.google.android.inputmethod.pinyin",
            "com.samsung.android.honeyboard",
            "com.sec.android.inputmethod",
            "com.touchtype.swiftkey",
            "com.swiftkey.swiftkeyapp",
            "com.nuance.swype.dtc",
            "com.nuance.swype.trial",
            "com.lge.ime",
            "com.lg.lgime",
            "com.miui.msa.global",
            "com.miui.systemAdSolution",
            "com.android.settings.intelligence"
        )

        private val SYSTEM_UI_PREFIXES = arrayOf(
            "com.android.systemui.",
            "com.samsung.android.app.taskbar",
            "com.oneplus.",
            "com.nothing.launcher"
        )

        fun isSystemUiPackage(pkg: String): Boolean {
            if (pkg in SYSTEM_UI_PACKAGES) return true
            for (prefix in SYSTEM_UI_PREFIXES) {
                if (pkg.startsWith(prefix)) return true
            }
            return false
        }

        const val ACTION_RELOAD_MODEL = "com.ftt.bulldogblocker.RELOAD_MODEL"

        private val BLOCKED_KEYWORDS = listOf(
            "porn","xxx","adult","sex","nude","nsfw","hentai","erotic",
            "xvideos","xhamster","pornhub","redtube","youporn","onlyfans",
            "chaturbate","xnxx","brazzers","bangbros","18+","nudity",
            "sexy","boobs","tits","ass","dick","cock","pussy","vagina",
            "penis","naked","strip","lingerie","bikini model","hot girls"
        )

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

    @Volatile private var currentForegroundPkg: String = ""

    // v8.1: Content warning overlay (threshold পার হওয়ার আগে দেখায়)
    // BUG FIX v8.1: ReportOverlayManager সরানো হয়েছে — v8.1-এ আর ব্যবহার হয় না।
    //   আগে: reportOverlay.show() → ছোট popup warning
    //   এখন: contentOverlay.show() → full-screen warning overlay
    private var contentOverlay: ContentOverlayManager? = null

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
        // BUG FIX v8.1: reportOverlay সরানো হয়েছে (dead code — show() কোথাও call হয় না)
        contentOverlay = ContentOverlayManager(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return

        // ── Foreground tracking + overlay auto-hide ───────────────────
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            !isSystemUiPackage(pkg)) {
            val prevPkg = currentForegroundPkg
            currentForegroundPkg = pkg
            // BUG FIX: overlay showing থাকলে শুধু তখনই hideAll() করো
            // যখন সত্যিকারের app switch হয়েছে (ভিন্ন package)।
            // আগে: Facebook-এর internal fragment transition-এ prevPkg=="" বা prevPkg!=pkg
            //       হলেই hideAll() → overlay দেখানোর সাথে সাথে hide হয়ে যেত।
            // এখন: pkg যদি currentForegroundPkg-এর সাথে মেলে (same app) তাহলে hide করব না।
            if (prevPkg.isNotEmpty() && prevPkg != pkg) {
                serviceScope.launch(Dispatchers.Main) { contentOverlay?.hideAll() }
            }
        }

        // ── Blocked app enforcement ───────────────────────────────────
        // BUG FIX v8.2: আগে এই check শুধু TYPE_WINDOW_STATE_CHANGED-এর ভেতরে ছিল।
        // ফলে: blocked app-এর TYPE_WINDOW_CONTENT_CHANGED events handleWindowTextEvent-এ
        //       পৌঁছাতো → addReport() + blockApp() repeatedly call হতো → cooldown ছাড়াই
        //       triggerBlockScreen() বারবার fire করতো (block screen spam)।
        // এখন:
        //   • সব event type-এর জন্য early return → content scanner blocked app পাবে না।
        //   • Block screen শুধু TYPE_WINDOW_STATE_CHANGED-এ, BLOCK_COOLDOWN সহ।
        if (pkg != OUR_PACKAGE && pkg !in whitelistCache &&
            AppReportManager.isBlocked(applicationContext, pkg)) {
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                val now = System.currentTimeMillis()
                if (now - lastBlockTime >= BLOCK_COOLDOWN) {
                    lastBlockTime = now
                    val remMs  = AppReportManager.getBlockRemainingMs(applicationContext, pkg)
                    val remMin = (remMs / 60_000L + 1L).toInt()
                    serviceScope.launch(Dispatchers.Main) {
                        triggerBlockScreen(
                            reason = "⏳ App ব্লক — আর $remMin মিনিট বাকি",
                            pkg    = pkg   // BUG FIX: countdown সঠিক দেখাতে pkg pass করতে হবে
                        )
                    }
                }
            }
            return  // blocked app-এর কোনো event-ই content scanner-এ পৌঁছাবে না
        }

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
        contentOverlay?.destroy()
        contentOverlay = null
        // BUG FIX v8.1: reportOverlay?.destroy() সরানো হয়েছে — field আর নেই
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
            service = this,
            scope   = serviceScope,
            isForegroundPkgWhitelisted = {
                whitelistCache.contains(currentForegroundPkg) ||
                currentForegroundPkg == OUR_PACKAGE
            },
            onAdultDetected = { reason, hash, score, showReport ->
                serviceScope.launch(Dispatchers.Main) {
                    triggerBlock(
                        reason     = reason,
                        hash       = hash,
                        score      = score,
                        showReport = showReport,
                        pkg        = currentForegroundPkg
                    )
                }
            }
        )
        blocker.start(c)
        screenshotBlocker = blocker
    }

    // ── Block trigger ─────────────────────────────────────────────────

    /**
     * v8.1 — Hybrid warning + block system:
     *
     *  count < threshold
     *    → ContentOverlayManager.show("সতর্কতা X/N")
     *      App-এর উপরে warning popup, app চলতে থাকে
     *      User "বুঝলাম" দিয়ে dismiss করতে পারে
     *
     *  count >= threshold
     *    → AppReportManager.blockApp(pkg)
     *    → triggerBlockScreen() → Home + Full block screen
     *      App নির্দিষ্ট সময়ের জন্য lock
     *
     *  Browser URL → countReport=false → সরাসরি block screen
     */
    private fun triggerBlock(
        reason:      String,
        hash:        Long    = 0L,
        score:       Float   = 1f,
        showReport:  Boolean = false,
        pkg:         String? = null,
        countReport: Boolean = true
    ) {
        if (pkg == OUR_PACKAGE) return

        val now = System.currentTimeMillis()
        if (now - lastBlockTime < BLOCK_COOLDOWN) return
        lastBlockTime = now

        val ctx = applicationContext

        if (countReport && pkg != null && pkg.isNotEmpty()) {
            val safePkg = pkg   // BUG FIX: lambda-র ভেতরে String? smart cast কাজ করে না
            (screenshotBlocker as? ScreenshotBlocker)?.resetCooldown()

            // User-decision overlay দেখাও
            contentOverlay?.show(
                reason  = reason,
                onBlock = {
                    // "বন্ধ করো" বা report "✅ সঠিক" → app block
                    AppReportManager.blockApp(ctx, safePkg)
                    val blockMin = AppReportManager.getBlockDurationMs(ctx) / 60_000L
                    triggerBlockScreen(
                        reason     = "$reason\n🔴 $blockMin মিনিটের জন্য app ব্লক হয়েছে",
                        hash       = hash,
                        score      = score,
                        showReport = showReport,
                        pkg        = safePkg
                    )
                },
                onFalse = {
                    // Report "❌ না, ভুল ছিল" → ScreenshotBlocker 1 min pause
                    (screenshotBlocker as? ScreenshotBlocker)?.pauseFor(60_000L)
                }
            )
            return
        }

        // Browser URL block → সরাসরি block screen (পরিবর্তন নেই)
        triggerBlockScreen(
            reason     = reason,
            hash       = hash,
            score      = score,
            showReport = showReport,
            pkg        = pkg ?: ""
        )
    }

    private fun triggerBlockScreen(
        reason:     String,
        hash:       Long    = 0L,
        score:      Float   = 1f,
        showReport: Boolean = false,
        pkg:        String  = ""
    ) {
        contentOverlay?.hideAll()
        (screenshotBlocker as? ScreenshotBlocker)?.resetCooldown()
        performGlobalAction(GLOBAL_ACTION_HOME)
        try {
            startActivity(Intent(this, BlockScreenActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra("reason",      reason)
                putExtra("image_hash",  hash)
                putExtra("score",       score)
                putExtra("show_report", showReport)
                putExtra("pkg",         pkg)
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

    // ── Keyword match ─────────────────────────────────────────────────

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
