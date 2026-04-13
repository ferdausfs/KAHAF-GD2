package com.ftt.bulldogblocker

import android.content.Context

/**
 * Threshold settings — SharedPreferences-এ সংরক্ষিত।
 * UI থেকে পরিবর্তন করা যায়; Classifier এবং ScreenshotBlocker
 * প্রতিটি classification-এ এখান থেকে পড়ে।
 */
object ThresholdManager {

    private const val PREFS          = "bdb_threshold"
    private const val KEY_MANUAL     = "threshold_manual"
    private const val KEY_SCREENSHOT = "threshold_screenshot"

    // ── Defaults ─────────────────────────────────────────────────────
    const val DEFAULT_MANUAL     = 0.40f   // Manual test threshold (40%)
    const val DEFAULT_SCREENSHOT = 0.22f   // Auto screenshot scan threshold (22%)

    // ── Manual test threshold (10% – 90%) ────────────────────────────
    fun getManual(ctx: Context): Float =
        prefs(ctx).getFloat(KEY_MANUAL, DEFAULT_MANUAL)
            .coerceIn(0.10f, 0.90f)

    fun setManual(ctx: Context, v: Float) =
        prefs(ctx).edit().putFloat(KEY_MANUAL, v.coerceIn(0.10f, 0.90f)).apply()

    // ── Screenshot auto-scan threshold (5% – 60%) ────────────────────
    fun getScreenshot(ctx: Context): Float =
        prefs(ctx).getFloat(KEY_SCREENSHOT, DEFAULT_SCREENSHOT)
            .coerceIn(0.05f, 0.60f)

    fun setScreenshot(ctx: Context, v: Float) =
        prefs(ctx).edit().putFloat(KEY_SCREENSHOT, v.coerceIn(0.05f, 0.60f)).apply()

    // ── Helpers ───────────────────────────────────────────────────────
    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
