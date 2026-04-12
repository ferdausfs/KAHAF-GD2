package com.kb.blocker.analyzer

import android.content.Context
import android.util.Log
import com.kb.blocker.data.PrefsManager
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Thread-safe keyword analyzer.
 * CopyOnWriteArraySet ব্যবহার করে — service thread থেকে read,
 * main/DB thread থেকে write — কোনো lock লাগবে না।
 */
class TextAnalyzer(context: Context) {

    private val prefs = PrefsManager(context)
    private val keywords = CopyOnWriteArraySet<String>()

    companion object {
        private const val TAG = "TextAnalyzer"
    }

    /**
     * DB থেকে নতুন keyword list পেলে এটা call করো।
     */
    fun updateKeywords(newKeywords: List<String>) {
        keywords.clear()
        keywords.addAll(newKeywords.map { it.trim().lowercase() })
        Log.d(TAG, "Keywords updated: ${keywords.size} total")
    }

    /**
     * Text এ blocked keyword আছে কিনা check করে।
     * Case-insensitive, partial match।
     * @return true → block করো
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
