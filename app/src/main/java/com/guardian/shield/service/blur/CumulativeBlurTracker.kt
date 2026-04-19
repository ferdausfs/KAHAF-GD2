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
 *   onUnsafeDetected(pkg) → starts a blur session if not already started.
 *     Returns BLOCK if cumulative total (including current session) >= 60s.
 *
 *   onSafeDetected(pkg) → ends the active blur session, adds its duration to total.
 *     Returns BLOCK if total >= 60s.
 *
 *   onAppChanged(pkg) → ends any active session for old pkg.
 *     Resets state for new pkg (fresh start per visit).
 *
 * BUG FIX #5: BlurState fields are @Volatile to prevent visibility issues
 * when accessed from both the AI scan coroutine (Dispatchers.Default) and
 * accessibility event callbacks concurrently. Write methods are @Synchronized
 * to prevent TOCTOU races.
 *
 * BUG FIX D: getTotalBlurMs() and isBlurActive() are now also @Synchronized.
 *
 * Old code left these two functions unsynchronized because they were "only
 * used for logging". But:
 *   1. getTotalBlurMs() reads TWO fields (totalMs + sessionStartMs) as a
 *      compound operation. Without a lock, a concurrent onSafeDetected() call
 *      could set sessionStartMs=null and increment totalMs between the two
 *      reads, producing a result that double-counts (or ignores) part of the
 *      current session. The result fed into the "Xms / 60s" log line would be
 *      misleading and hard to debug.
 *   2. isBlurActive() reads sessionStartMs once — a single volatile read is
 *      atomic, but it's exposed as API so callers may rely on it being
 *      consistent with the write methods. Synchronizing it costs nothing and
 *      prevents subtle bugs if usage ever expands to decision paths.
 */
@Singleton
class CumulativeBlurTracker @Inject constructor() {

    companion object {
        private const val TAG = "Guardian_BlurTracker"
        const val BLOCK_THRESHOLD_MS = 60_000L // 1 minute
    }

    /**
     * Per-app blur state.
     * All access is guarded by the outer class lock (@Synchronized methods).
     * @Volatile on individual fields provides cross-thread visibility as an
     * extra safety net, but the synchronized methods are the primary guard.
     */
    private class BlurState {
        @Volatile var totalMs: Long = 0L
        @Volatile var sessionStartMs: Long? = null  // null = no active session
    }

    private val states = ConcurrentHashMap<String, BlurState>()

    // ── Public API ─────────────────────────────────────────────────────

    /**
     * Call when AI detects unsafe content.
     * @return true if cumulative blur has reached 60s (caller should block app)
     */
    @Synchronized
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
    @Synchronized
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
     * Ends any active blur session for the previous app (preserves total — no reset).
     * Resets state for the new app (fresh 60s counter per visit).
     */
    @Synchronized
    fun onAppChanged(prevPkg: String, newPkg: String) {
        states[prevPkg]?.let { state ->
            val sessionStart = state.sessionStartMs
            if (sessionStart != null) {
                state.totalMs += System.currentTimeMillis() - sessionStart
                state.sessionStartMs = null
                Timber.d("$TAG [$prevPkg] app changed — paused blur session")
            }
        }
        // Reset the new app's counter entirely (fresh start per visit)
        states.remove(newPkg)
        Timber.d("$TAG [$newPkg] reset for new foreground visit")
    }

    /** Reset a specific app's blur state (e.g. after blocking). */
    @Synchronized
    fun resetApp(pkg: String) {
        states.remove(pkg)
        Timber.d("$TAG [$pkg] state reset")
    }

    /**
     * Current total blur ms (including active session if running).
     *
     * BUG FIX D: Now @Synchronized.
     * Old code read totalMs and sessionStartMs separately without a lock. A
     * concurrent onSafeDetected() could commit the session (adding to totalMs,
     * clearing sessionStartMs) between our two reads, causing us to add the
     * session duration twice. With the lock, both fields are read atomically.
     */
    @Synchronized
    fun getTotalBlurMs(pkg: String): Long {
        val state = states[pkg] ?: return 0L
        val sessionMs = state.sessionStartMs?.let { System.currentTimeMillis() - it } ?: 0L
        return state.totalMs + sessionMs
    }

    /**
     * Whether a blur session is currently active for this package.
     *
     * BUG FIX D: Now @Synchronized.
     * Although a single volatile read is technically atomic, synchronizing here
     * ensures consistency with the write methods and future-proofs against
     * callers that rely on this returning a value coherent with getTotalBlurMs().
     */
    @Synchronized
    fun isBlurActive(pkg: String): Boolean = states[pkg]?.sessionStartMs != null

    /** Remaining ms before block threshold is reached. */
    fun remainingMs(pkg: String): Long =
        (BLOCK_THRESHOLD_MS - getTotalBlurMs(pkg)).coerceAtLeast(0L)
}
