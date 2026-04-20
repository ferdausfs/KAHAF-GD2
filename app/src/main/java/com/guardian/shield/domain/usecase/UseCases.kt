// app/src/main/java/com/guardian/shield/domain/usecase/UseCases.kt
package com.guardian.shield.domain.usecase

import com.guardian.shield.data.local.datastore.GuardianPreferences
import com.guardian.shield.data.repository.AppRuleRepository
import com.guardian.shield.data.repository.BlockEventRepository
import com.guardian.shield.data.repository.KeywordRepository
import com.guardian.shield.domain.model.*
import com.guardian.shield.service.detection.PinManager
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

// ── App Rules ─────────────────────────────────────────────────────────

class ObserveBlockedAppsUseCase @Inject constructor(
    private val repo: AppRuleRepository
) {
    operator fun invoke(): Flow<List<AppRule>> = repo.observeBlockedApps()
}

class ObserveWhitelistedAppsUseCase @Inject constructor(
    private val repo: AppRuleRepository
) {
    operator fun invoke(): Flow<List<AppRule>> = repo.observeWhitelistedApps()
}

class AddBlockedAppUseCase @Inject constructor(
    private val repo: AppRuleRepository
) {
    suspend operator fun invoke(rule: AppRule) = repo.addBlockedApp(rule)
}

class AddWhitelistedAppUseCase @Inject constructor(
    private val repo: AppRuleRepository
) {
    suspend operator fun invoke(rule: AppRule) = repo.addWhitelistedApp(rule)
}

class RemoveAppRuleUseCase @Inject constructor(
    private val repo: AppRuleRepository
) {
    suspend operator fun invoke(packageName: String) = repo.removeRule(packageName)
}

// ── Keywords ──────────────────────────────────────────────────────────

class ObserveKeywordsUseCase @Inject constructor(
    private val repo: KeywordRepository
) {
    operator fun invoke(): Flow<List<KeywordRule>> = repo.observeAll()
}

class AddKeywordUseCase @Inject constructor(
    private val repo: KeywordRepository
) {
    suspend operator fun invoke(keyword: String): Boolean {
        val trimmed = keyword.trim()
        if (trimmed.length < 2) return false
        repo.addKeyword(trimmed)
        return true
    }
}

class RemoveKeywordUseCase @Inject constructor(
    private val repo: KeywordRepository
) {
    suspend operator fun invoke(id: Long) = repo.removeKeyword(id)
}

// ── Block Events / Stats ──────────────────────────────────────────────

class ObserveBlockEventsUseCase @Inject constructor(
    private val repo: BlockEventRepository
) {
    operator fun invoke(): Flow<List<BlockEvent>> = repo.observeRecent()
}

class GetBlockStatsUseCase @Inject constructor(
    private val repo: BlockEventRepository
) {
    suspend operator fun invoke(): BlockStats = repo.getStats()
}

// ── PIN ───────────────────────────────────────────────────────────────

class SetupPinUseCase @Inject constructor(
    private val pinManager: PinManager
) {
    operator fun invoke(pin: String, confirmPin: String): PinSetupResult {
        if (pin.length < PinManager.MIN_PIN_LENGTH) return PinSetupResult.TooShort
        if (pin != confirmPin) return PinSetupResult.Mismatch
        if (!pin.all { it.isDigit() }) return PinSetupResult.InvalidChars
        val saved = pinManager.savePin(pin)
        return if (saved) PinSetupResult.Success else PinSetupResult.Failed
    }
}

// FIX #6: Now returns VerifyResult instead of Boolean
class VerifyPinUseCase @Inject constructor(
    private val pinManager: PinManager
) {
    operator fun invoke(input: String): PinManager.VerifyResult =
        pinManager.verifyPinWithLockout(input)
}

class IsPinSetUseCase @Inject constructor(
    private val pinManager: PinManager
) {
    operator fun invoke(): Boolean = pinManager.isPinSet()
}

enum class PinSetupResult {
    Success, TooShort, Mismatch, InvalidChars, Failed
}

// ── Protection Settings ───────────────────────────────────────────────

class ToggleProtectionUseCase @Inject constructor(
    private val prefs: GuardianPreferences
) {
    suspend operator fun invoke(enabled: Boolean) = prefs.setProtectionEnabled(enabled)
}

class ToggleAiDetectionUseCase @Inject constructor(
    private val prefs: GuardianPreferences
) {
    suspend operator fun invoke(enabled: Boolean) = prefs.setAiDetection(enabled)
}

class ToggleKeywordDetectionUseCase @Inject constructor(
    private val prefs: GuardianPreferences
) {
    suspend operator fun invoke(enabled: Boolean) = prefs.setKeywordDetection(enabled)
}

class ToggleStrictModeUseCase @Inject constructor(
    private val prefs: GuardianPreferences
) {
    suspend operator fun invoke(enabled: Boolean) = prefs.setStrictMode(enabled)
}

class SetDelayUnlockSecondsUseCase @Inject constructor(
    private val prefs: GuardianPreferences
) {
    suspend operator fun invoke(seconds: Int) = prefs.setDelayUnlockSeconds(seconds)
}