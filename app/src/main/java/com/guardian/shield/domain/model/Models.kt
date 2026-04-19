package com.guardian.shield.domain.model

/**
 * Represents an app entry in blocked or whitelisted list.
 */
data class AppRule(
    val packageName: String,
    val appName: String,
    val isWhitelisted: Boolean = false,
    val isBlocked: Boolean = false,
    val addedAt: Long = System.currentTimeMillis()
)

/**
 * A keyword rule for content filtering.
 */
data class KeywordRule(
    val id: Long = 0,
    val keyword: String,
    val isActive: Boolean = true,
    val addedAt: Long = System.currentTimeMillis()
)

/**
 * A block event log entry.
 */
data class BlockEvent(
    val id: Long = 0,
    val packageName: String,
    val appName: String,
    val reason: BlockReason,
    val detail: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

enum class BlockReason {
    APP_BLOCKED,
    KEYWORD_DETECTED,
    AI_DETECTED
}

/**
 * Aggregated stats for the dashboard.
 */
data class BlockStats(
    val totalBlocked: Int = 0,
    val todayBlocked: Int = 0,
    val lastBlockedApp: String = "",
    val lastBlockedAt: Long = 0L
)

/**
 * Result from the rules engine — what action to take.
 *
 * Blur: AI detected unsafe content → blur screen, track cumulative time.
 *       When cumulative blur >= 60s, escalate to Block.
 * Block: Hard block (app list / keyword / cumulative blur threshold reached).
 */
sealed class DetectionResult {
    object Allow : DetectionResult()
    object Whitelist : DetectionResult()
    data class Block(val reason: BlockReason, val detail: String = "") : DetectionResult()
    data class Blur(
        val reason: BlockReason,
        val detail: String = "",
        val unsafeScore: Float = 0f
    ) : DetectionResult()
}

/**
 * App protection state for the dashboard.
 */
data class ProtectionState(
    val isAccessibilityEnabled: Boolean = false,
    val isOverlayPermissionGranted: Boolean = false,
    val isPinSet: Boolean = false,
    val isProtectionActive: Boolean = false
)
