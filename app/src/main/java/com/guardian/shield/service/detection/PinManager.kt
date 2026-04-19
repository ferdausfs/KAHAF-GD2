package com.guardian.shield.service.detection

import com.guardian.shield.data.local.datastore.SecureStorage
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PIN management — all PIN logic lives here.
 * PIN is NEVER stored in plain text.
 * Storage: SHA-256 hash in EncryptedSharedPreferences.
 */
@Singleton
class PinManager @Inject constructor(
    private val secureStorage: SecureStorage
) {
    companion object {
        const val MIN_PIN_LENGTH = 4
        const val MAX_PIN_LENGTH = 8
    }

    fun isPinSet(): Boolean = secureStorage.isPinSet()

    /**
     * Save a new PIN (hashed).
     * Returns false if PIN is too short/long.
     */
    fun savePin(pin: String): Boolean {
        if (pin.length < MIN_PIN_LENGTH || pin.length > MAX_PIN_LENGTH) return false
        if (!pin.all { it.isDigit() }) return false
        secureStorage.savePinHash(hash(pin))
        return true
    }

    /**
     * Verify PIN against stored hash.
     */
    fun verifyPin(input: String): Boolean {
        val stored = secureStorage.getPinHash() ?: return false
        return hash(input) == stored
    }

    fun clearPin() = secureStorage.clearPin()

    private fun hash(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes  = digest.digest(value.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
