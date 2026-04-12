package com.ftt.bulldogblocker.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.ftt.bulldogblocker.admin.DeviceAdminReceiver
import com.ftt.bulldogblocker.ui.BlockScreenActivity
import com.ftt.bulldogblocker.ui.UninstallDelayActivity

/**
 * Accessibility Service — two responsibilities:
 *
 * 1. URL BLOCKING: Watches browser address bars for adult keywords / domains.
 *    When detected → launch BlockScreenActivity as overlay.
 *
 * 2. UNINSTALL INTERCEPTION: Watches for the system package installer
 *    being opened for OUR package. When detected → redirect to
 *    UninstallDelayActivity which enforces a 60-second countdown
 *    BEFORE allowing admin deactivation + uninstall.
 */
class BlockerAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "BDB_Accessibility"
        private const val OUR_PACKAGE = "com.ftt.bulldogblocker"

        // Known browser packages to monitor
        private val BROWSER_PACKAGES = setOf(
            "com.android.chrome",
            "org.mozilla.firefox",
            "com.opera.browser",
            "com.brave.browser",
            "com.microsoft.emmx",
            "com.UCMobile.intl",
            "com.sec.android.app.sbrowser"
        )

        // System package installer packages
        private val INSTALLER_PACKAGES = setOf(
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.miui.packageinstaller"
        )

        // Adult keyword list for URL bar detection
        private val BLOCKED_KEYWORDS = listOf(
            "porn", "xxx", "adult", "sex", "nude", "nsfw",
            "hentai", "erotic", "xvideos", "xhamster", "pornhub",
            "redtube", "youporn", "onlyfans", "chaturbate"
        )
    }

    override fun onServiceConnected() {
        Log.d(TAG, "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        val packageName = event.packageName?.toString() ?: return

        // ── 1. Check for uninstall attempt via Package Installer ──
        if (packageName in INSTALLER_PACKAGES) {
            handlePossibleUninstallAttempt(event)
            return
        }

        // ── 2. Check Settings > Apps page for our app ──
        if (packageName == "com.android.settings") {
            handleSettingsPage(event)
            return
        }

        // ── 3. Monitor browser URL bar ──
        if (packageName in BROWSER_PACKAGES) {
            handleBrowserEvent(event)
        }
    }

    // ─────────────────────────────────────────────
    // Uninstall Interception
    // ─────────────────────────────────────────────

    private fun handlePossibleUninstallAttempt(event: AccessibilityEvent) {
        val root = rootInActiveWindow ?: return
        val allText = getAllText(root).lowercase()

        // If our app name appears in the package installer UI → intercept
        if ("bulldog" in allText || OUR_PACKAGE in allText) {
            Log.w(TAG, "Uninstall attempt detected via package installer!")
            launchUninstallDelay()
        }
    }

    private fun handleSettingsPage(event: AccessibilityEvent) {
        val root = rootInActiveWindow ?: return
        val allText = getAllText(root).lowercase()

        // Detect "App info" page for our app with "Uninstall" button visible
        if (("bulldog blocker" in allText || "bulldogblocker" in allText)
            && "uninstall" in allText) {
            Log.w(TAG, "Uninstall attempt detected via Settings!")
            launchUninstallDelay()
        }
    }

    private fun launchUninstallDelay() {
        val intent = Intent(this, UninstallDelayActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(intent)
    }

    // ─────────────────────────────────────────────
    // Browser URL Blocking
    // ─────────────────────────────────────────────

    private fun handleBrowserEvent(event: AccessibilityEvent) {
        val root = rootInActiveWindow ?: return
        val urlText = getUrlBarText(root)?.lowercase() ?: return

        if (BLOCKED_KEYWORDS.any { urlText.contains(it) }) {
            Log.w(TAG, "Blocked URL detected: $urlText")
            performGlobalAction(GLOBAL_ACTION_BACK)
            launchBlockScreen("🚫 সাইটটি ব্লক করা হয়েছে")
        }
    }

    private fun getUrlBarText(node: AccessibilityNodeInfo): String? {
        // Common resource IDs for browser address bars
        val urlBarIds = listOf(
            "com.android.chrome:id/url_bar",
            "com.android.chrome:id/search_box_text",
            "org.mozilla.firefox:id/mozac_browser_toolbar_url_view",
            "com.brave.browser:id/url_bar"
        )
        for (id in urlBarIds) {
            val nodes = node.findAccessibilityNodeInfosByViewId(id)
            if (nodes.isNotEmpty()) return nodes[0].text?.toString()
        }
        // Fallback: scan all editable text nodes
        return findEditableText(node)
    }

    private fun findEditableText(node: AccessibilityNodeInfo): String? {
        if (node.isEditable && node.text != null) return node.text.toString()
        for (i in 0 until node.childCount) {
            val result = findEditableText(node.getChild(i) ?: continue)
            if (result != null) return result
        }
        return null
    }

    private fun launchBlockScreen(reason: String) {
        val intent = Intent(this, BlockScreenActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("reason", reason)
        }
        startActivity(intent)
    }

    // ─────────────────────────────────────────────
    // Helper
    // ─────────────────────────────────────────────

    private fun getAllText(node: AccessibilityNodeInfo): String {
        val sb = StringBuilder()
        fun traverse(n: AccessibilityNodeInfo?) {
            n ?: return
            n.text?.let { sb.append(it).append(" ") }
            n.contentDescription?.let { sb.append(it).append(" ") }
            for (i in 0 until n.childCount) traverse(n.getChild(i))
        }
        traverse(node)
        return sb.toString()
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility service interrupted")
    }
}
