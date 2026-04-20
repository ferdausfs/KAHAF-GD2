// app/src/main/java/com/guardian/shield/data/local/datastore/SecureStorage.kt
package com.guardian.shield.data.local.datastore

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SecureStorage @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val PREFS_NAME  = "guardian_secure"
        private const val KEY_PIN_HASH = "pin_hash"
        private const val KEY_PIN_SET  = "pin_set"
        // FIX #6: PIN rate-limiting keys
        private const val KEY_FAIL_COUNT = "pin_fail_count"
        private const val KEY_LOCKOUT_UNTIL = "pin_lockout_until"
        private const val TAG = "SecureStorage"

        // FIX #6: Rate limit thresholds
        const val MAX_FAILS_BEFORE_LOCKOUT = 5
        const val LOCKOUT_DURATION_MS = 60_000L // 1 minute
        const val EXTENDED_LOCKOUT_MS = 300_000L // 5 minutes after 10 fails
        const val HARD_LOCKOUT_MS = 1_800_000L // 30 minutes after 15 fails
    }

    private val prefs: SharedPreferences by lazy { buildPrefs() }

    private fun buildPrefs(): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Timber.e(e, "$TAG EncryptedSharedPreferences init failed — using fallback")
            context.getSharedPreferences("${PREFS_NAME}_fallback", Context.MODE_PRIVATE)
        }
    }

    fun isPinSet(): Boolean = try {
        prefs.getBoolean(KEY_PIN_SET, false)
    } catch (e: Exception) {
        Timber.e(e, "$TAG isPinSet failed")
        false
    }

    fun getPinHash(): String? = try {
        prefs.getString(KEY_PIN_HASH, null)
    } catch (e: Exception) {
        Timber.e(e, "$TAG getPinHash failed")
        null
    }

    fun savePinHash(hash: String) {
        try {
            prefs.edit()
                .putString(KEY_PIN_HASH, hash)
                .putBoolean(KEY_PIN_SET, true)
                .putInt(KEY_FAIL_COUNT, 0)
                .putLong(KEY_LOCKOUT_UNTIL, 0)
                .apply()
        } catch (e: Exception) {
            Timber.e(e, "$TAG savePinHash failed")
        }
    }

    fun clearPin() {
        try {
            prefs.edit()
                .remove(KEY_PIN_HASH)
                .putBoolean(KEY_PIN_SET, false)
                .putInt(KEY_FAIL_COUNT, 0)
                .putLong(KEY_LOCKOUT_UNTIL, 0)
                .apply()
        } catch (e: Exception) {
            Timber.e(e, "$TAG clearPin failed")
        }
    }

    // FIX #6: Rate limiting methods

    fun getFailCount(): Int = try {
        prefs.getInt(KEY_FAIL_COUNT, 0)
    } catch (e: Exception) { 0 }

    fun recordFailedAttempt() {
        try {
            val newCount = getFailCount() + 1
            val lockoutMs = when {
                newCount >= 15 -> HARD_LOCKOUT_MS
                newCount >= 10 -> EXTENDED_LOCKOUT_MS
                newCount >= MAX_FAILS_BEFORE_LOCKOUT -> LOCKOUT_DURATION_MS
                else -> 0L
            }
            val lockoutUntil = if (lockoutMs > 0) System.currentTimeMillis() + lockoutMs else 0L
            prefs.edit()
                .putInt(KEY_FAIL_COUNT, newCount)
                .putLong(KEY_LOCKOUT_UNTIL, lockoutUntil)
                .apply()
            Timber.w("$TAG failed attempt #$newCount, lockout=${lockoutMs}ms")
        } catch (e: Exception) {
            Timber.e(e, "$TAG recordFailedAttempt failed")
        }
    }

    fun resetFailCount() {
        try {
            prefs.edit()
                .putInt(KEY_FAIL_COUNT, 0)
                .putLong(KEY_LOCKOUT_UNTIL, 0)
                .apply()
        } catch (e: Exception) {
            Timber.e(e, "$TAG resetFailCount failed")
        }
    }

    fun isLockedOut(): Boolean {
        return try {
            val until = prefs.getLong(KEY_LOCKOUT_UNTIL, 0L)
            System.currentTimeMillis() < until
        } catch (e: Exception) { false }
    }

    fun getLockoutRemainingMs(): Long {
        return try {
            val until = prefs.getLong(KEY_LOCKOUT_UNTIL, 0L)
            (until - System.currentTimeMillis()).coerceAtLeast(0L)
        } catch (e: Exception) { 0L }
    }
}