// app/src/main/java/com/guardian/shield/service/blur/CumulativeBlurTracker.kt
package com.guardian.shield.service.blur

import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CumulativeBlurTracker @Inject constructor() {

    companion object {
        private const val TAG = "Guardian_BlurTracker"
        // FIX: Increased to 90 seconds for more reasonable threshold
        const val BLOCK_THRESHOLD_MS = 90_000L
        // FIX: Reset threshold if user avoided content for 30s
        const val RESET_AFTER_SAFE_MS = 30_000L
    }

    private class BlurState {
        @Volatile var totalMs: Long = 0L
        @Volatile var sessionStartMs: Long? = null
        @Volatile var lastSafeMs: Long = 0L  // FIX: Track last safe detection
    }

    private val states = ConcurrentHashMap<String, BlurState>()

    @Synchronized
    fun onUnsafeDetected(pkg: String): Boolean {
        val state = states.getOrPut(pkg) { BlurState() }
        
        // FIX: If user stayed safe for > 30s, reset accumulator
        if (state.lastSafeMs > 0) {
            val safeDuration = System.currentTimeMillis() - state.lastSafeMs
            if (safeDuration > RESET_AFTER_SAFE_MS && state.sessionStartMs == null) {
                Timber.d("$TAG [$pkg] reset after ${safeDuration}ms safe")
                state.totalMs = 0L
                state.lastSafeMs = 0L
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
            Timber.d("$TAG [$pkg] safe — session ${sessionMs}ms. total: ${state.totalMs}ms")
        } else {
            state.lastSafeMs = System.currentTimeMillis()
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
                Timber.d("$TAG [$prevPkg] paused session")
            }
        }
        // Don't reset new app state here - let it accumulate across visits
    }

    @Synchronized
    fun resetApp(pkg: String) {
        states.remove(pkg)
        Timber.d("$TAG [$pkg] reset")
    }

    @Synchronized
    fun getTotalBlurMs(pkg: String): Long {
        val state = states[pkg] ?: return 0L
        val sessionMs = state.sessionStartMs?.let { System.currentTimeMillis() - it } ?: 0L
        return state.totalMs + sessionMs
    }

    @Synchronized
    fun isBlurActive(pkg: String): Boolean = states[pkg]?.sessionStartMs != null

    fun remainingMs(pkg: String): Long =
        (BLOCK_THRESHOLD_MS - getTotalBlurMs(pkg)).coerceAtLeast(0L)
}