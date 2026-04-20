// app/src/main/java/com/guardian/shield/service/detection/PinManager.kt
package com.guardian.shield.service.detection

import com.guardian.shield.data.local.datastore.SecureStorage
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PinManager @Inject constructor(
    private val secureStorage: SecureStorage
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

    fun savePin(pin: String): Boolean {
        if (pin.length < MIN_PIN_LENGTH || pin.length > MAX_PIN_LENGTH) return false
        if (!pin.all { it.isDigit() }) return false
        secureStorage.savePinHash(hash(pin))
        return true
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

    fun clearPin() = secureStorage.clearPin()

    fun getFailCount(): Int = secureStorage.getFailCount()

    private fun hash(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes  = digest.digest(value.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}