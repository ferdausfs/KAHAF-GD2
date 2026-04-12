package com.kb.blocker.analyzer

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.kb.blocker.data.PrefsManager
import com.kb.blocker.ml.ModelManager

/**
 * ImageAnalyzer — ModelManager এর উপর পাতলা wrapper।
 * ContentBlockerService এটাকেই call করে।
 */
class ImageAnalyzer(context: Context) {

    private val prefs = PrefsManager(context)
    val modelManager = ModelManager(context)

    companion object {
        private const val TAG = "ImageAnalyzer"
    }

    /**
     * Bitmap analyze করো।
     * @return true → block করো
     */
    suspend fun isAdultContent(bitmap: Bitmap): Boolean {
        if (!prefs.imageAnalysisEnabled) return false
        if (modelManager.getModelCount() == 0) {
            Log.w(TAG, "No models available")
            return false
        }
        return try {
            modelManager.isAdultContent(bitmap, prefs.imageThreshold)
        } catch (e: Exception) {
            Log.e(TAG, "Analysis error — skipping block", e)
            false // Error হলে false দাও, false positive এড়াতে
        }
    }

    fun close() {
        try { modelManager.close() } catch (e: Exception) {
            Log.e(TAG, "Close error", e)
        }
    }
}
