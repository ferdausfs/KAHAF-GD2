package com.kb.blocker.data

import android.content.Context
import androidx.core.content.edit

class PrefsManager(context: Context) {

    private val prefs = context.getSharedPreferences("kb_prefs", Context.MODE_PRIVATE)

    // Image analysis confidence threshold (0.0 - 1.0)
    // Higher = less sensitive, Lower = more sensitive
    var imageThreshold: Float
        get() = prefs.getFloat("image_threshold", 0.75f)
        set(value) = prefs.edit { putFloat("image_threshold", value) }

    // Toggle image (ML) analysis
    var imageAnalysisEnabled: Boolean
        get() = prefs.getBoolean("image_analysis", true)
        set(value) = prefs.edit { putBoolean("image_analysis", value) }

    // Toggle text (keyword) analysis
    var textAnalysisEnabled: Boolean
        get() = prefs.getBoolean("text_analysis", true)
        set(value) = prefs.edit { putBoolean("text_analysis", value) }

    // Minimum ms between two screenshot analyses (throttle)
    var imageIntervalMs: Long
        get() = prefs.getLong("image_interval", 2000L)
        set(value) = prefs.edit { putLong("image_interval", value) }
}
