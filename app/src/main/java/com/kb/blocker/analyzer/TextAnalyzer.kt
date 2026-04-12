package com.kb.blocker.analyzer

import android.content.Context
import android.util.Log
import com.kb.blocker.data.PrefsManager
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Thread-safe keyword text analyzer.
 * Uses CopyOnWriteArraySet so the service thread can read
 * while the DB/main thread writes — no explicit lock needed.
 */
class TextAnalyzer(context: Context) {

    private val prefs = PrefsManager(context)
    private val keywords = CopyOnWriteArraySet<String>()

    companion object {
        private const val TAG = "TextAnalyzer"
    }

    /** Call this whenever the keyword list changes (from DB observer). */
    fun updateKeywords(newKeywords: List<String>) {
        keywords.clear()
        keywords.addAll(newKeywords.map { it.trim().lowercase() })
        Log.d(TAG, "Keywords updated: ${keywords.size} total")
    }

    /**
     * Returns true if the given text contains any blocked keyword.
     * Case-insensitive, partial match.
     */
    fun containsBlockedContent(text: String): Boolean {
        if (!prefs.textAnalysisEnabled) return false
        if (keywords.isEmpty()) return false
        if (text.isBlank()) return false

        val lowerText = text.lowercase()
        return try {
            keywords.any { keyword ->
                keyword.isNotBlank() && lowerText.contains(keyword)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error analyzing text", e)
            false
        }
    }

    fun getKeywordCount(): Int = keywords.size
}
