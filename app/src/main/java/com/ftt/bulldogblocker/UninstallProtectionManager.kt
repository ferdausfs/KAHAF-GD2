package com.ftt.bulldogblocker

import android.content.Context

/**
 * Uninstall Protection — 3 modes:
 *   TESTING  → 60-second countdown (default)
 *   BY_TIME  → N minutes/hours countdown
 *   BY_DATE  → N days countdown (persistent across restarts)
 *
 * For BY_TIME & BY_DATE: first attempt timestamp is stored so the user
 * can't bypass by re-opening the delay screen.
 */
object UninstallProtectionManager {

    enum class Mode { TESTING, BY_TIME, BY_DATE }

    private const val PREFS             = "bdb_uninstall_prot"
    private const val KEY_MODE          = "mode"
    private const val KEY_DELAY_MIN     = "delay_minutes"
    private const val KEY_DELAY_DAYS    = "delay_days"
    private const val KEY_FIRST_ATTEMPT = "first_attempt_ms"

    const val DEFAULT_DELAY_MIN  = 60    // 1 hour
    const val DEFAULT_DELAY_DAYS = 3     // 3 days

    // ── Mode ──────────────────────────────────────────────────────────

    fun getMode(ctx: Context): Mode {
        val name = prefs(ctx).getString(KEY_MODE, Mode.TESTING.name) ?: Mode.TESTING.name
        return try { Mode.valueOf(name) } catch (_: Exception) { Mode.TESTING }
    }

    fun setMode(ctx: Context, mode: Mode) =
        prefs(ctx).edit().putString(KEY_MODE, mode.name).apply()

    // ── Delay values ──────────────────────────────────────────────────

    fun getDelayMinutes(ctx: Context): Int =
        prefs(ctx).getInt(KEY_DELAY_MIN, DEFAULT_DELAY_MIN).coerceIn(1, 10080)

    fun setDelayMinutes(ctx: Context, min: Int) =
        prefs(ctx).edit().putInt(KEY_DELAY_MIN, min.coerceIn(1, 10080)).apply()

    fun getDelayDays(ctx: Context): Int =
        prefs(ctx).getInt(KEY_DELAY_DAYS, DEFAULT_DELAY_DAYS).coerceIn(1, 30)

    fun setDelayDays(ctx: Context, days: Int) =
        prefs(ctx).edit().putInt(KEY_DELAY_DAYS, days.coerceIn(1, 30)).apply()

    // ── Persistent first-attempt tracking ────────────────────────────

    /**
     * প্রথমবার uninstall attempt হলে এই timestamp store করো।
     * পরে activity খোলা হলেও same timestamp থাকে — timer bypass হয় না।
     */
    fun recordFirstAttempt(ctx: Context) {
        if (prefs(ctx).getLong(KEY_FIRST_ATTEMPT, 0L) == 0L) {
            prefs(ctx).edit().putLong(KEY_FIRST_ATTEMPT, System.currentTimeMillis()).apply()
        }
    }

    /** Reset করো (uninstall সম্পন্ন হলে বা admin manually reset করলে) */
    fun resetAttempt(ctx: Context) =
        prefs(ctx).edit().remove(KEY_FIRST_ATTEMPT).apply()

    /** Total delay in ms for the current mode */
    fun getTotalDelayMs(ctx: Context): Long = when (getMode(ctx)) {
        Mode.TESTING -> 60_000L
        Mode.BY_TIME -> getDelayMinutes(ctx) * 60_000L
        Mode.BY_DATE -> getDelayDays(ctx) * 24L * 3_600_000L
    }

    /**
     * এই time-এ unlock হবে (epoch ms).
     * recordFirstAttempt() ডাকার আগে 0L return করে।
     */
    fun getUnlockTimeMs(ctx: Context): Long {
        val first = prefs(ctx).getLong(KEY_FIRST_ATTEMPT, 0L)
        if (first == 0L) return 0L
        return first + getTotalDelayMs(ctx)
    }

    fun getRemainingMs(ctx: Context): Long {
        val unlock = getUnlockTimeMs(ctx)
        if (unlock == 0L) return getTotalDelayMs(ctx)
        return (unlock - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    fun isUnlocked(ctx: Context): Boolean {
        val unlock = getUnlockTimeMs(ctx)
        return unlock > 0L && System.currentTimeMillis() >= unlock
    }

    /** Human-readable remaining time */
    fun formatRemaining(ms: Long): String {
        if (ms <= 0L) return "✅ সময় শেষ — proceed করুন"
        val days  = ms / 86_400_000L
        val hours = (ms % 86_400_000L) / 3_600_000L
        val mins  = (ms % 3_600_000L) / 60_000L
        val secs  = (ms % 60_000L) / 1_000L
        return when {
            days  > 0 -> "$days দিন $hours ঘণ্টা $mins মিনিট বাকি"
            hours > 0 -> "$hours ঘণ্টা $mins মিনিট $secs সেকেন্ড বাকি"
            mins  > 0 -> "$mins মিনিট $secs সেকেন্ড বাকি"
            else      -> "$secs সেকেন্ড বাকি"
        }
    }

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
