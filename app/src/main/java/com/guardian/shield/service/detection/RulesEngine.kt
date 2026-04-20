// app/src/main/java/com/guardian/shield/service/detection/RulesEngine.kt
package com.guardian.shield.service.detection

import com.guardian.shield.domain.model.BlockReason
import com.guardian.shield.domain.model.DetectionResult
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RulesEngine @Inject constructor() {

    companion object {
        const val OUR_PACKAGE = "com.guardian.shield"
        private const val TAG = "Guardian_Rules"

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
        
        // FIX: Common launcher packages that should NEVER be blocked even in strict mode
        private val LAUNCHER_PACKAGES = setOf(
            "com.android.launcher", "com.android.launcher3",
            "com.google.android.apps.nexuslauncher",
            "com.sec.android.app.launcher",
            "com.miui.home", "com.oneplus.launcher",
            "com.nothing.launcher", "com.oppo.launcher",
            "com.vivo.launcher", "com.huawei.android.launcher"
        )
    }

    @Volatile private var blockedPackages: Set<String> = emptySet()
    @Volatile private var whitelistedPackages: Set<String> = emptySet()
    @Volatile private var isKeywordDetectionOn: Boolean = true
    @Volatile private var isProtectionEnabled: Boolean = true
    @Volatile private var isStrictMode: Boolean = false

    private data class KeywordPattern(
        val keyword: String,
        val pattern: Regex,
        val isUnicode: Boolean  // FIX: Track unicode keywords for different matching
    )

    @Volatile private var keywordPatterns: List<KeywordPattern> = emptyList()

    fun refreshBlockedApps(packages: Set<String>) {
        blockedPackages = packages
        Timber.d("$TAG blocked: ${packages.size} apps")
    }

    fun refreshWhitelistedApps(packages: Set<String>) {
        whitelistedPackages = packages
        Timber.d("$TAG whitelist: ${packages.size} apps")
    }

    // FIX: Better keyword matching - handles unicode (Bangla/Hindi/Arabic)
    fun refreshKeywords(keywords: List<String>) {
        keywordPatterns = keywords.mapNotNull { kw ->
            val normalized = kw.lowercase().trim()
            if (normalized.isEmpty()) return@mapNotNull null
            
            // Check if keyword contains non-ASCII (unicode)
            val isUnicode = normalized.any { it.code > 127 }
            
            try {
                val pattern = if (isUnicode) {
                    // For unicode: use lookaround instead of \b (which doesn't work for Bangla/Hindi)
                    // Match if surrounded by non-letter chars or start/end
                    Regex("(?<![\\p{L}\\p{N}])${Regex.escape(normalized)}(?![\\p{L}\\p{N}])")
                } else {
                    // ASCII: word boundary works fine
                    Regex("\\b${Regex.escape(normalized)}\\b")
                }
                KeywordPattern(normalized, pattern, isUnicode)
            } catch (e: Exception) {
                Timber.e(e, "$TAG invalid keyword regex: $normalized")
                null
            }
        }
        Timber.d("$TAG keywords: ${keywords.size} compiled (${keywordPatterns.count { it.isUnicode }} unicode)")
    }

    fun setKeywordDetectionEnabled(enabled: Boolean) { isKeywordDetectionOn = enabled }
    fun setProtectionEnabled(enabled: Boolean) { isProtectionEnabled = enabled }
    fun setStrictMode(enabled: Boolean) {
        isStrictMode = enabled
        Timber.d("$TAG strict mode: $enabled")
    }

    fun evaluateApp(packageName: String): DetectionResult {
        if (!isProtectionEnabled) return DetectionResult.Allow
        if (packageName == OUR_PACKAGE) return DetectionResult.Allow
        if (isSystemPackage(packageName)) return DetectionResult.Allow

        // Whitelist always wins
        if (packageName in whitelistedPackages) {
            Timber.d("$TAG ALLOW (whitelist): $packageName")
            return DetectionResult.Whitelist
        }

        // Blocked list
        if (packageName in blockedPackages) {
            Timber.d("$TAG BLOCK (app list): $packageName")
            return DetectionResult.Block(BlockReason.APP_BLOCKED, packageName)
        }

        // FIX: STRICT MODE - block anything not whitelisted (except launchers/system)
        if (isStrictMode && !isLauncherPackage(packageName)) {
            Timber.d("$TAG BLOCK (strict mode): $packageName")
            return DetectionResult.Block(
                BlockReason.APP_BLOCKED,
                "Not in trusted apps list"
            )
        }

        return DetectionResult.Allow
    }

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
            Timber.d("$TAG BLOCK (keyword '${hit.keyword}' unicode=${hit.isUnicode}): $packageName")
            DetectionResult.Block(BlockReason.KEYWORD_DETECTED, hit.keyword)
        } else DetectionResult.Allow
    }

    fun evaluateAiResult(packageName: String, unsafeScore: Float, threshold: Float): DetectionResult {
        if (!isProtectionEnabled) return DetectionResult.Allow
        if (packageName == OUR_PACKAGE) return DetectionResult.Allow
        if (packageName in whitelistedPackages) return DetectionResult.Whitelist

        return if (unsafeScore >= threshold) {
            Timber.d("$TAG BLUR (AI=${unsafeScore}): $packageName")
            DetectionResult.Blur(
                reason = BlockReason.AI_DETECTED,
                detail = "${(unsafeScore * 100).toInt()}% unsafe",
                unsafeScore = unsafeScore
            )
        } else DetectionResult.Allow
    }

    fun isWhitelisted(packageName: String): Boolean =
        packageName == OUR_PACKAGE || packageName in whitelistedPackages

    fun isSystemUi(pkg: String): Boolean = isSystemPackage(pkg)

    fun isProtectionActive(): Boolean = isProtectionEnabled

    private fun isSystemPackage(pkg: String): Boolean {
        if (pkg in SYSTEM_PACKAGES) return true
        SYSTEM_PREFIXES.forEach { if (pkg.startsWith(it)) return true }
        return false
    }

    private fun isLauncherPackage(pkg: String): Boolean {
        if (pkg in LAUNCHER_PACKAGES) return true
        // Common launcher patterns
        return pkg.contains("launcher", ignoreCase = true) ||
               pkg.endsWith(".home") ||
               pkg.contains("homescreen", ignoreCase = true)
    }
}