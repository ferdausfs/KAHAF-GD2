package com.kb.blocker.analyzer

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.kb.blocker.data.PrefsManager
import com.kb.blocker.ml.ModelManager

/**
 * Thin wrapper around ModelManager.
 * ContentBlockerService calls this for image analysis.
 */
class ImageAnalyzer(context: Context) {

    private val prefs = PrefsManager(context)
    val modelManager = ModelManager(context)

    companion object {
        private const val TAG = "ImageAnalyzer"
    }

    /**
     * Analyze a bitmap using all loaded TFLite models.
     * Returns true if adult content is detected (should block).
     * Returns false on error to avoid false positives.
     */
    fun isAdultContent(bitmap: Bitmap): Boolean {
        if (!prefs.imageAnalysisEnabled) return false
        if (modelManager.getModelCount() == 0) {
            Log.w(TAG, "No models available")
            return false
        }
        return try {
            modelManager.isAdultContent(bitmap, prefs.imageThreshold)
        } catch (e: Exception) {
            Log.e(TAG, "Analysis error - skipping block", e)
            false
        }
    }

    /** Reload all user models — call after a new model file is added. */
    fun reloadModels() {
        modelManager.loadAllModels()
        Log.d(TAG, "Models reloaded: ${modelManager.getModelCount()} loaded")
    }

    fun close() {
        try { modelManager.close() } catch (e: Exception) {
            Log.e(TAG, "Close error", e)
        }
    }
}
