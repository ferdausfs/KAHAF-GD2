// app/src/main/java/com/guardian/shield/service/blur/CumulativeBlurTracker.kt
package com.guardian.shield.service.blur

import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * CumulativeBlurTracker — tracks per-app total blur time.
 *
 * Logic:
 *   • onUnsafeDetected(pkg) → called every AI scan cycle when content is unsafe.
 *     Starts a blur session if not already started.
 *     Returns BLOCK if cumulative total (including current session) >= 60s.
 *
 *   • onSafeDetected(pkg) → called when content clears.
 *     Ends the active blur session, adds its duration to the total.
 *     Returns BLOCK if total >= 60s.
 *
 *   • onAppChanged(pkg) → called when foreground app changes.
 *     Ends any active session for the old package (without resetting total).
 *     Resets the state for the new package entirely (fresh start per visit).
 *
 * Example timeline for one session:
 *   t=0   unsafe → sessionStart=0
 *   t=10  safe   → total += 10s  (total=10s)  → no block
 *   t=25  unsafe → sessionStart=25
 *   t=27  safe   → total += 2s   (total=12s)  → no block
 *   t=60  unsafe → sessionStart=60
 *   t=108 scan   → total + (108-60) = 12+48 = 60s → BLOCK
 */
@Singleton
class CumulativeBlurTracker @Inject constructor() {

    companion object {
        private const val TAG = "Guardian_BlurTracker"
        const val BLOCK_THRESHOLD_MS = 60_000L // 1 minute
    }

    private data class BlurState(
        var totalMs: Long = 0L,
        var sessionStartMs: Long? = null  // null = no active blur session
    )

    private val states = ConcurrentHashMap<String, BlurState>()

    // ── Public API ─────────────────────────────────────────────────────

    /**
     * Call when AI detects unsafe content.
     * @return true if cumulative blur has reached 60s (caller should block app)
     */
    fun onUnsafeDetected(pkg: String): Boolean {
        val state = states.getOrPut(pkg) { BlurState() }
        if (state.sessionStartMs == null) {
            state.sessionStartMs = System.currentTimeMillis()
            Timber.d("$TAG [$pkg] blur session started. accumulated so far: ${state.totalMs}ms")
        }
        val currentSessionMs = System.currentTimeMillis() - state.sessionStartMs!!
        val total = state.totalMs + currentSessionMs
        Timber.d("$TAG [$pkg] unsafe scan — total blur so far: ${total}ms / ${BLOCK_THRESHOLD_MS}ms")
        return total >= BLOCK_THRESHOLD_MS
    }

    /**
     * Call when AI detects safe content (content has cleared).
     * @return true if cumulative blur has reached 60s (caller should block app)
     */
    fun onSafeDetected(pkg: String): Boolean {
        val state = states[pkg] ?: return false
        val sessionStart = state.sessionStartMs ?: return false

        val sessionMs = System.currentTimeMillis() - sessionStart
        state.totalMs += sessionMs
        state.sessionStartMs = null

        Timber.d("$TAG [$pkg] safe — ended session of ${sessionMs}ms. total: ${state.totalMs}ms")
        return state.totalMs >= BLOCK_THRESHOLD_MS
    }

    /**
     * Call when the foreground app changes.
     * Ends any active blur session for the previous app (without resetting).
     * Resets state for the new app (fresh 60s counter per visit).
     */
    fun onAppChanged(prevPkg: String, newPkg: String) {
        // End active session for previous app (don't reset total — keeps across revisits
        // within the same process lifetime)
        states[prevPkg]?.let { state ->
            val sessionStart = state.sessionStartMs
            if (sessionStart != null) {
                state.totalMs += System.currentTimeMillis() - sessionStart
                state.sessionStartMs = null
                Timber.d("$TAG [$prevPkg] app changed — paused blur session")
            }
        }
        // Reset the new app's counter entirely (fresh start)
        states.remove(newPkg)
        Timber.d("$TAG [$newPkg] reset for new foreground visit")
    }

    /** Reset a specific app's blur state (e.g. after blocking). */
    fun resetApp(pkg: String) {
        states.remove(pkg)
        Timber.d("$TAG [$pkg] state reset")
    }

    /** Current total blur ms (including active session if running). */
    fun getTotalBlurMs(pkg: String): Long {
        val state = states[pkg] ?: return 0L
        val sessionMs = state.sessionStartMs?.let { System.currentTimeMillis() - it } ?: 0L
        return state.totalMs + sessionMs
    }

    /** Whether a blur session is currently active for this package. */
    fun isBlurActive(pkg: String): Boolean = states[pkg]?.sessionStartMs != null

    /** Remaining ms before block threshold is reached. */
    fun remainingMs(pkg: String): Long =
        (BLOCK_THRESHOLD_MS - getTotalBlurMs(pkg)).coerceAtLeast(0L)
}
