package com.ftt.bulldogblocker

import android.content.Context

/**
 * Per-app content detection report counter + timed block manager.
 *
 * When adult content is detected in App X:
 *   → addReport(pkg)  returns new count
 *   → count < threshold  → show overlay popup warning
 *   → count >= threshold → blockApp(pkg) + full BlockScreenActivity
 *   → after blockDuration expires, count auto-resets
 */
object AppReportManager {

    private const val PREFS         = "bdb_app_reports"
    private const val KEY_THRESHOLD = "report_threshold"
    private const val KEY_BLOCK_MS  = "block_duration_ms"

    const val DEFAULT_THRESHOLD  = 3
    const val DEFAULT_BLOCK_MS   = 15 * 60_000L  // 15 minutes

    // ── Global settings ───────────────────────────────────────────────

    fun getReportThreshold(ctx: Context): Int =
        prefs(ctx).getInt(KEY_THRESHOLD, DEFAULT_THRESHOLD).coerceIn(1, 10)

    fun setReportThreshold(ctx: Context, n: Int) =
        prefs(ctx).edit().putInt(KEY_THRESHOLD, n.coerceIn(1, 10)).apply()

    /** Block duration in milliseconds */
    fun getBlockDurationMs(ctx: Context): Long =
        prefs(ctx).getLong(KEY_BLOCK_MS, DEFAULT_BLOCK_MS).coerceIn(60_000L, 24 * 3_600_000L)

    fun setBlockDurationMs(ctx: Context, ms: Long) =
        prefs(ctx).edit().putLong(KEY_BLOCK_MS, ms.coerceIn(60_000L, 24 * 3_600_000L)).apply()

    // ── Per-app operations ────────────────────────────────────────────

    fun getReportCount(ctx: Context, pkg: String): Int {
        if (checkAndClearExpiredBlock(ctx, pkg)) return 0
        return prefs(ctx).getInt(countKey(pkg), 0)
    }

    /** Report যোগ করো, নতুন count return করো */
    fun addReport(ctx: Context, pkg: String): Int {
        if (checkAndClearExpiredBlock(ctx, pkg)) return 1.also {
            prefs(ctx).edit().putInt(countKey(pkg), 1).apply()
        }
        val n = prefs(ctx).getInt(countKey(pkg), 0) + 1
        prefs(ctx).edit().putInt(countKey(pkg), n).apply()
        return n
    }

    fun resetReports(ctx: Context, pkg: String) {
        prefs(ctx).edit()
            .remove(countKey(pkg))
            .remove(blockUntilKey(pkg))
            .apply()
    }

    fun isBlocked(ctx: Context, pkg: String): Boolean {
        if (checkAndClearExpiredBlock(ctx, pkg)) return false
        return prefs(ctx).getLong(blockUntilKey(pkg), 0L) > 0L
    }

    fun blockApp(ctx: Context, pkg: String) {
        val until = System.currentTimeMillis() + getBlockDurationMs(ctx)
        prefs(ctx).edit().putLong(blockUntilKey(pkg), until).apply()
    }

    fun getBlockRemainingMs(ctx: Context, pkg: String): Long {
        val until = prefs(ctx).getLong(blockUntilKey(pkg), 0L)
        return (until - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    fun getBlockDurationMinutes(ctx: Context): Int =
        (getBlockDurationMs(ctx) / 60_000L).toInt()

    // ── Internal ──────────────────────────────────────────────────────

    /** Block expired হলে clear করো, true return করো */
    private fun checkAndClearExpiredBlock(ctx: Context, pkg: String): Boolean {
        val until = prefs(ctx).getLong(blockUntilKey(pkg), 0L)
        if (until > 0L && System.currentTimeMillis() > until) {
            resetReports(ctx, pkg)
            return true
        }
        return false
    }

    private fun countKey(pkg: String)      = "cnt_$pkg"
    private fun blockUntilKey(pkg: String) = "blk_$pkg"

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
