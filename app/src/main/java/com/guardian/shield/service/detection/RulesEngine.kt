package com.guardian.shield.service.detection

import com.guardian.shield.domain.model.BlockReason
import com.guardian.shield.domain.model.DetectionResult
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * RulesEngine — the single authority for ALL block decisions.
 *
 * Priority order (MUST NOT be changed):
 *   1. OWN package → always allow
 *   2. WHITELIST → always allow (overrides everything)
 *   3. BLOCKED app list → block
 *   4. KEYWORD match → block
 *   5. AI detection → block
 *   6. Default → allow
 *
 * This engine holds in-memory caches refreshed from DB.
 * The AccessibilityService calls evaluate() on every relevant event.
 */
@Singleton
class RulesEngine @Inject constructor() {

    companion object {
        const val OUR_PACKAGE = "com.guardian.shield"
        private const val TAG = "Guardian_Rules"

        // System packages that should never be interfered with
        private val SYSTEM_PACKAGES = setOf(
            "android",
            "com.android.systemui",
            "com.google.android.inputmethod.latin",
            "com.samsung.android.honeyboard",
            "com.sec.android.inputmethod",
            "com.touchtype.swiftkey",
            "com.android.settings.intelligence",
            "com.miui.msa.global"
        )

        private val SYSTEM_PREFIXES = arrayOf(
            "com.android.systemui.",
            "com.oneplus.",
            "com.nothing.launcher",
            "com.samsung.android.app.taskbar"
        )
    }

    // In-memory caches — refreshed via refreshCaches()
    @Volatile private var blockedPackages: Set<String>     = emptySet()
    @Volatile private var whitelistedPackages: Set<String> = emptySet()
    @Volatile private var isKeywordDetectionOn: Boolean    = true
    @Volatile private var isProtectionEnabled: Boolean     = true
    @Volatile private var isStrictMode: Boolean            = false

    /**
     * BUG FIX C: Pre-compiled keyword patterns with word boundaries.
     *
     * Old code stored raw strings and used String.contains() which caused
     * false positives — e.g. keyword "sex" would match "Sussex", "Essex",
     * "assessment"; keyword "ass" would match "class", "glass", "assistance".
     *
     * Fix: On refreshKeywords(), compile each keyword into a Regex with \\b
     * (word boundary) anchors so only whole-word matches trigger a block.
     * Patterns are compiled ONCE and reused on every text scan — no allocation
     * per scan cycle unlike the old approach.
     *
     * Multi-word keywords (e.g. "adult content") also benefit from this since
     * \\b anchors the start and end of the whole phrase.
     */
    private data class KeywordPattern(
        val keyword: String,   // original for logging
        val pattern: Regex     // pre-compiled with word boundaries
    )

    @Volatile private var keywordPatterns: List<KeywordPattern> = emptyList()

    // ── Cache refresh (called by service on startup + DB change) ──────

    fun refreshBlockedApps(packages: Set<String>) {
        blockedPackages = packages
        Timber.d("$TAG blocked list refreshed: ${packages.size} apps")
    }

    fun refreshWhitelistedApps(packages: Set<String>) {
        whitelistedPackages = packages
        Timber.d("$TAG whitelist refreshed: ${packages.size} apps")
    }

    /**
     * BUG FIX C: Compile each keyword into a word-boundary Regex.
     * Stored as List<KeywordPattern> instead of List<String>.
     */
    fun refreshKeywords(keywords: List<String>) {
        keywordPatterns = keywords.map { kw ->
            val normalized = kw.lowercase().trim()
            KeywordPattern(
                keyword = normalized,
                // \\b = word boundary — prevents "sex" matching "Sussex"
                pattern = Regex("\\b${Regex.escape(normalized)}\\b")
            )
        }
        Timber.d("$TAG keywords refreshed: ${keywords.size} keywords (pre-compiled with word boundaries)")
    }

    fun setKeywordDetectionEnabled(enabled: Boolean) { isKeywordDetectionOn = enabled }
    fun setProtectionEnabled(enabled: Boolean)        { isProtectionEnabled  = enabled }
    fun setStrictMode(enabled: Boolean)               { isStrictMode         = enabled }

    // ── Main evaluation ───────────────────────────────────────────────

    /**
     * Evaluate foreground app package.
     * Returns DetectionResult.Allow, .Whitelist, or .Block
     */
    fun evaluateApp(packageName: String): DetectionResult {
        if (!isProtectionEnabled) return DetectionResult.Allow

        // Rule 1: Own package — never block ourselves
        if (packageName == OUR_PACKAGE) return DetectionResult.Allow

        // Rule 2: System UI — never block
        if (isSystemPackage(packageName)) return DetectionResult.Allow

        // Rule 3: WHITELIST — highest priority, overrides everything
        if (packageName in whitelistedPackages) {
            Timber.d("$TAG ALLOW (whitelist): $packageName")
            return DetectionResult.Whitelist
        }

        // Rule 4: Blocked app list
        if (packageName in blockedPackages) {
            Timber.d("$TAG BLOCK (app list): $packageName")
            return DetectionResult.Block(BlockReason.APP_BLOCKED, packageName)
        }

        return DetectionResult.Allow
    }

    /**
     * Evaluate screen text for keyword matches.
     * Only called if keyword detection is enabled and app is not whitelisted.
     *
     * BUG FIX C: Uses pre-compiled word-boundary patterns instead of contains().
     * Each keyword is checked with Regex("\\b<keyword>\\b") so only whole-word
     * matches trigger a block. Patterns are compiled in refreshKeywords(), not here.
     */
    fun evaluateText(packageName: String, text: String): DetectionResult {
        if (!isProtectionEnabled) return DetectionResult.Allow
        if (!isKeywordDetectionOn) return DetectionResult.Allow
        if (packageName == OUR_PACKAGE) return DetectionResult.Allow
        if (packageName in whitelistedPackages) return DetectionResult.Whitelist

        val lower = text.lowercase()
        val hit = keywordPatterns.firstOrNull { kp ->
            kp.pattern.containsMatchIn(lower)
        }

        return if (hit != null) {
            Timber.d("$TAG BLOCK (keyword '${hit.keyword}'): $packageName")
            DetectionResult.Block(BlockReason.KEYWORD_DETECTED, hit.keyword)
        } else {
            DetectionResult.Allow
        }
    }

    /**
     * Evaluate AI model result.
     * Returns Blur (not Block) — the blur engine accumulates time and
     * escalates to Block only after 60 seconds of cumulative blur.
     */
    fun evaluateAiResult(packageName: String, unsafeScore: Float, threshold: Float): DetectionResult {
        if (!isProtectionEnabled) return DetectionResult.Allow
        if (packageName == OUR_PACKAGE) return DetectionResult.Allow
        if (packageName in whitelistedPackages) return DetectionResult.Whitelist

        return if (unsafeScore >= threshold) {
            Timber.d("$TAG BLUR (AI score=${unsafeScore}): $packageName")
            DetectionResult.Blur(
                reason      = BlockReason.AI_DETECTED,
                detail      = "${(unsafeScore * 100).toInt()}% unsafe",
                unsafeScore = unsafeScore
            )
        } else {
            DetectionResult.Allow
        }
    }

    /**
     * Quick whitelist check for use in AI scanner loop.
     */
    fun isWhitelisted(packageName: String): Boolean =
        packageName == OUR_PACKAGE || packageName in whitelistedPackages

    fun isSystemUi(pkg: String): Boolean = isSystemPackage(pkg)

    fun isProtectionActive(): Boolean = isProtectionEnabled

    private fun isSystemPackage(pkg: String): Boolean {
        if (pkg in SYSTEM_PACKAGES) return true
        SYSTEM_PREFIXES.forEach { if (pkg.startsWith(it)) return true }
        return false
    }
}
