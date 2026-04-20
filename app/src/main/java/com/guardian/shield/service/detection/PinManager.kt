// app/src/main/java/com/guardian/shield/service/detection/PinManager.kt
package com.guardian.shield.service.detection

import com.guardian.shield.data.local.datastore.SecureStorage
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PinManager @Inject constructor(
    private val secureStorage: SecureStorage,
    private val pinRecovery: PinRecovery
) {
    companion object {
        const val MIN_PIN_LENGTH = 4
        const val MAX_PIN_LENGTH = 8
    }

    sealed class VerifyResult {
        object Success : VerifyResult()
        object WrongPin : VerifyResult()
        data class LockedOut(val remainingMs: Long) : VerifyResult()
        object NotSet : VerifyResult()
    }

    fun isPinSet(): Boolean = secureStorage.isPinSet()

    /**
     * Saves PIN and generates a recovery code if none exists.
     * Returns the recovery code if newly generated, null if already exists.
     */
    fun savePin(pin: String): Boolean {
        if (pin.length < MIN_PIN_LENGTH || pin.length > MAX_PIN_LENGTH) return false
        if (!pin.all { it.isDigit() }) return false
        secureStorage.savePinHash(hash(pin))
        // Auto-generate recovery code on first PIN setup
        if (!pinRecovery.hasRecoveryCode()) {
            pinRecovery.generateRecoveryCode()
        }
        return true
    }

    /**
     * Save PIN and return recovery code (for initial setup flow).
     */
    fun savePinWithRecoveryCode(pin: String): String? {
        if (!savePin(pin)) return null
        return if (!pinRecovery.hasRecoveryCode()) {
            pinRecovery.generateRecoveryCode()
        } else null
    }

    fun verifyPinWithLockout(input: String): VerifyResult {
        if (!isPinSet()) return VerifyResult.NotSet

        if (secureStorage.isLockedOut()) {
            return VerifyResult.LockedOut(secureStorage.getLockoutRemainingMs())
        }

        val stored = secureStorage.getPinHash() ?: return VerifyResult.NotSet
        return if (hash(input) == stored) {
            secureStorage.resetFailCount()
            VerifyResult.Success
        } else {
            secureStorage.recordFailedAttempt()
            if (secureStorage.isLockedOut()) {
                VerifyResult.LockedOut(secureStorage.getLockoutRemainingMs())
            } else {
                VerifyResult.WrongPin
            }
        }
    }

    fun verifyPin(input: String): Boolean {
        return verifyPinWithLockout(input) is VerifyResult.Success
    }

    /**
     * Reset PIN using recovery code. Clears old PIN so user can set a new one.
     */
    fun resetPinWithRecoveryCode(recoveryCode: String): Boolean {
        if (!pinRecovery.verifyRecoveryCode(recoveryCode)) return false
        secureStorage.clearPin()
        return true
    }

    fun clearPin() {
        secureStorage.clearPin()
        pinRecovery.clearRecoveryCode()
    }

    fun getFailCount(): Int = secureStorage.getFailCount()

    fun getRecoveryCode(): String? = if (isPinSet()) {
        secureStorage.getRecoveryCode()
    } else null

    fun regenerateRecoveryCode(): String = pinRecovery.generateRecoveryCode()

    private fun hash(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes  = digest.digest(value.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}