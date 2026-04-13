package com.ftt.bulldogblocker

import android.content.Context

/**
 * Threshold settings — SharedPreferences-এ সংরক্ষিত।
 * UI থেকে পরিবর্তন করা যায়; Classifier এবং ScreenshotBlocker
 * প্রতিটি classification-এ এখান থেকে পড়ে।
 */
object ThresholdManager {

    private const val PREFS           = "bdb_threshold"
    private const val KEY_MANUAL      = "threshold_manual"
    private const val KEY_SCREENSHOT  = "threshold_screenshot"
    private const val KEY_SEXY_ALONE  = "threshold_sexy_alone"

    // ── Defaults ─────────────────────────────────────────────────────
    const val DEFAULT_MANUAL      = 0.40f  // Manual test threshold (40%)
    const val DEFAULT_SCREENSHOT  = 0.35f  // v6 FIX: 0.22→0.35 (false positive কমাতে)
    const val DEFAULT_SEXY_ALONE  = 0.45f  // NEW: sexy standalone → bra/panty/lingerie block

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

    // ── Sexy standalone threshold (20% – 80%) ────────────────────────
    // 5-class model-এ sexy score এই value পার করলে semi-nude block।
    // combined score পার না করলেও এটা পার করলেই block।
    fun getSexyAlone(ctx: Context): Float =
        prefs(ctx).getFloat(KEY_SEXY_ALONE, DEFAULT_SEXY_ALONE)
            .coerceIn(0.20f, 0.80f)

    fun setSexyAlone(ctx: Context, v: Float) =
        prefs(ctx).edit().putFloat(KEY_SEXY_ALONE, v.coerceIn(0.20f, 0.80f)).apply()

    // ── Helpers ───────────────────────────────────────────────────────
    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
