// app/src/main/java/com/guardian/shield/service/blur/CumulativeBlurTracker.kt
package com.guardian.shield.service.blur

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CumulativeBlurTracker @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val TAG = "Guardian_BlurTracker"
        const val BLOCK_THRESHOLD_MS = 90_000L
        const val RESET_AFTER_SAFE_MS = 30_000L
        private const val PREFS_NAME = "blur_tracker_state"
        private const val KEY_TOTAL_PREFIX = "total_"
        private const val KEY_LAST_SAFE_PREFIX = "lastSafe_"
    }

    private class BlurState {
        @Volatile var totalMs: Long = 0L
        @Volatile var sessionStartMs: Long? = null
        @Volatile var lastSafeMs: Long = 0L
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val states = ConcurrentHashMap<String, BlurState>()

    private fun getOrLoadState(pkg: String): BlurState {
        return states.getOrPut(pkg) {
            BlurState().apply {
                totalMs = prefs.getLong("$KEY_TOTAL_PREFIX$pkg", 0L)
                lastSafeMs = prefs.getLong("$KEY_LAST_SAFE_PREFIX$pkg", 0L)
            }
        }
    }

    private fun persistState(pkg: String, state: BlurState) {
        try {
            prefs.edit()
                .putLong("$KEY_TOTAL_PREFIX$pkg", state.totalMs)
                .putLong("$KEY_LAST_SAFE_PREFIX$pkg", state.lastSafeMs)
                .apply()
        } catch (e: Exception) {
            Timber.w("$TAG persistState error: ${e.message}")
        }
    }

    @Synchronized
    fun onUnsafeDetected(pkg: String): Boolean {
        val state = getOrLoadState(pkg)

        if (state.lastSafeMs > 0) {
            val safeDuration = System.currentTimeMillis() - state.lastSafeMs
            if (safeDuration > RESET_AFTER_SAFE_MS && state.sessionStartMs == null) {
                Timber.d("$TAG [$pkg] reset after ${safeDuration}ms safe")
                state.totalMs = 0L
                state.lastSafeMs = 0L
                persistState(pkg, state)
            }
        }

        if (state.sessionStartMs == null) {
            state.sessionStartMs = System.currentTimeMillis()
            Timber.d("$TAG [$pkg] blur session started. total: ${state.totalMs}ms")
        }
        val currentSessionMs = System.currentTimeMillis() - state.sessionStartMs!!
        val total = state.totalMs + currentSessionMs
        Timber.d("$TAG [$pkg] unsafe — total: ${total}ms / ${BLOCK_THRESHOLD_MS}ms")
        return total >= BLOCK_THRESHOLD_MS
    }

    @Synchronized
    fun onSafeDetected(pkg: String): Boolean {
        val state = states[pkg] ?: return false
        val sessionStart = state.sessionStartMs

        if (sessionStart != null) {
            val sessionMs = System.currentTimeMillis() - sessionStart
            state.totalMs += sessionMs
            state.sessionStartMs = null
            state.lastSafeMs = System.currentTimeMillis()
            persistState(pkg, state)
            Timber.d("$TAG [$pkg] safe — session ${sessionMs}ms. total: ${state.totalMs}ms")
        } else {
            state.lastSafeMs = System.currentTimeMillis()
            persistState(pkg, state)
        }

        return state.totalMs >= BLOCK_THRESHOLD_MS
    }

    @Synchronized
    fun onAppChanged(prevPkg: String, newPkg: String) {
        states[prevPkg]?.let { state ->
            val sessionStart = state.sessionStartMs
            if (sessionStart != null) {
                state.totalMs += System.currentTimeMillis() - sessionStart
                state.sessionStartMs = null
                persistState(prevPkg, state)
                Timber.d("$TAG [$prevPkg] paused session, persisted total: ${state.totalMs}ms")
            }
        }
    }

    @Synchronized
    fun resetApp(pkg: String) {
        states.remove(pkg)
        try {
            prefs.edit()
                .remove("$KEY_TOTAL_PREFIX$pkg")
                .remove("$KEY_LAST_SAFE_PREFIX$pkg")
                .apply()
        } catch (e: Exception) {
            Timber.w("$TAG resetApp error: ${e.message}")
        }
        Timber.d("$TAG [$pkg] reset")
    }

    @Synchronized
    fun getTotalBlurMs(pkg: String): Long {
        val state = getOrLoadState(pkg)
        val sessionMs = state.sessionStartMs?.let { System.currentTimeMillis() - it } ?: 0L
        return state.totalMs + sessionMs
    }

    @Synchronized
    fun isBlurActive(pkg: String): Boolean = states[pkg]?.sessionStartMs != null

    fun remainingMs(pkg: String): Long =
        (BLOCK_THRESHOLD_MS - getTotalBlurMs(pkg)).coerceAtLeast(0L)
}