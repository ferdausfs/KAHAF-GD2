package com.guardian.shield.data.local.datastore

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Secure storage for sensitive data (PIN hash).
 * Uses EncryptedSharedPreferences backed by Android Keystore.
 * PIN is stored as SHA-256 hash — never plain text.
 *
 * BUG FIX: All EncryptedSharedPreferences READ/WRITE operations are now
 * wrapped in try-catch. On Samsung (Knox/OneUI) and certain Android 9-12
 * devices, crypto operations on EncryptedSharedPreferences can throw
 * SecurityException or AEADBadTagException AFTER successful create().
 * Without this protection, the exception propagates to MainActivity.onResume()
 * and crashes the app on first launch.
 */
@Singleton
class SecureStorage @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val PREFS_NAME  = "guardian_secure"
        private const val KEY_PIN_HASH = "pin_hash"
        private const val KEY_PIN_SET  = "pin_set"
        private const val TAG = "SecureStorage"
    }

    private val prefs: SharedPreferences by lazy { buildPrefs() }

    // ── Initialization ────────────────────────────────────────────────

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
            // Fallback: Samsung Knox / older Android Keystore failure
            Timber.e(e, "$TAG EncryptedSharedPreferences init failed — using fallback")
            context.getSharedPreferences("${PREFS_NAME}_fallback", Context.MODE_PRIVATE)
        }
    }

    // ── Public API ────────────────────────────────────────────────────

    /**
     * BUG FIX: Wrapped in try-catch.
     * EncryptedSharedPreferences.getBoolean() can throw AEADBadTagException
     * on Samsung devices on first access after creation.
     */
    fun isPinSet(): Boolean = try {
        prefs.getBoolean(KEY_PIN_SET, false)
    } catch (e: Exception) {
        Timber.e(e, "$TAG isPinSet failed — returning false")
        false
    }

    /**
     * BUG FIX: Wrapped in try-catch.
     * EncryptedSharedPreferences.getString() can throw on Samsung/Knox devices.
     */
    fun getPinHash(): String? = try {
        prefs.getString(KEY_PIN_HASH, null)
    } catch (e: Exception) {
        Timber.e(e, "$TAG getPinHash failed — returning null")
        null
    }

    /**
     * BUG FIX: Wrapped in try-catch.
     * EncryptedSharedPreferences.edit().put*().apply() can throw on writes too.
     */
    fun savePinHash(hash: String) {
        try {
            prefs.edit()
                .putString(KEY_PIN_HASH, hash)
                .putBoolean(KEY_PIN_SET, true)
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
                .apply()
        } catch (e: Exception) {
            Timber.e(e, "$TAG clearPin failed")
        }
    }
}
