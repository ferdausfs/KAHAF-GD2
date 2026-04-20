// app/src/main/java/com/guardian/shield/service/detection/PinRecovery.kt
package com.guardian.shield.service.detection

import com.guardian.shield.data.local.datastore.SecureStorage
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Generates and verifies recovery codes for PIN reset.
 * Format: XXXX-XXXX-XXXX (12 alphanumeric chars + dashes)
 */
@Singleton
class PinRecovery @Inject constructor(
    private val secureStorage: SecureStorage
) {
    companion object {
        private const val RECOVERY_CODE_LENGTH = 12
        private val ALPHABET = (('A'..'Z') + ('0'..'9')).toList()
    }

    /**
     * Generate a new recovery code and persist it.
     * Show this to the user ONCE — they should write it down.
     */
    fun generateRecoveryCode(): String {
        val random = SecureRandom()
        val code = StringBuilder()
        repeat(RECOVERY_CODE_LENGTH) { idx ->
            if (idx > 0 && idx % 4 == 0) code.append('-')
            code.append(ALPHABET[random.nextInt(ALPHABET.size)])
        }
        val codeStr = code.toString()
        secureStorage.saveRecoveryCode(codeStr)
        return codeStr
    }

    /**
     * Verify user-entered recovery code.
     * Normalizes spacing/case before comparison.
     */
    fun verifyRecoveryCode(input: String): Boolean {
        val stored = secureStorage.getRecoveryCode() ?: return false
        val normalized = input.trim().uppercase().replace(" ", "")
        val normalizedStored = stored.replace(" ", "")
        return normalized == normalizedStored
    }

    fun hasRecoveryCode(): Boolean = secureStorage.getRecoveryCode() != null

    fun clearRecoveryCode() = secureStorage.clearRecoveryCode()
}