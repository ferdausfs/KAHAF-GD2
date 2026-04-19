// app/src/main/java/com/guardian/shield/viewmodel/ViewModels.kt
package com.guardian.shield.viewmodel

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.guardian.shield.data.local.datastore.GuardianPreferences
import com.guardian.shield.domain.model.KeywordRule
import com.guardian.shield.domain.usecase.*
import com.guardian.shield.service.accessibility.GuardianAccessibilityService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

// ─────────────────────────────────────────────────────────────────────
// Settings ViewModel
// ─────────────────────────────────────────────────────────────────────

data class SettingsUiState(
    val isAiEnabled: Boolean            = false,
    val isKeywordEnabled: Boolean       = true,
    val isStrictMode: Boolean           = false,
    val delayUnlockSeconds: Int         = 30,
    val aiThreshold: Float              = 0.40f,
    val aiIntervalMs: Long              = 2500L,
    val isPinSet: Boolean               = false,
    val snackMessage: String?           = null
)

private data class SettingsSnapshot(
    val ai: Boolean,
    val keyword: Boolean,
    val strict: Boolean,
    val delay: Int,
    val threshold: Float
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: GuardianPreferences,
    private val toggleAiDetectionUseCase: ToggleAiDetectionUseCase,
    private val toggleKeywordDetectionUseCase: ToggleKeywordDetectionUseCase,
    // BUG FIX: inject the now-existing ToggleStrictModeUseCase
    private val toggleStrictModeUseCase: ToggleStrictModeUseCase,
    private val setDelayUnlockSecondsUseCase: SetDelayUnlockSecondsUseCase,
    private val isPinSetUseCase: IsPinSetUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        observePrefs()
        refreshPinStatus()
    }

    private fun observePrefs() {
        viewModelScope.launch {
            // BUG FIX: Was using vararg combine { values -> values[3] as Int } which
            // can throw ClassCastException on ART when unboxing Integer/Float as Any?.
            // Fix: use typed 5-arg combine overload with explicit parameter types.
            combine(
                prefs.isAiDetectionEnabled,
                prefs.isKeywordDetectionEnabled,
                prefs.isStrictMode,
                prefs.delayUnlockSeconds,
                prefs.aiThreshold
            ) { ai: Boolean, keyword: Boolean, strict: Boolean, delay: Int, threshold: Float ->
                SettingsSnapshot(
                    ai        = ai,
                    keyword   = keyword,
                    strict    = strict,
                    delay     = delay,
                    threshold = threshold
                )
            }.collect { snap ->
                _uiState.update {
                    it.copy(
                        isAiEnabled        = snap.ai,
                        isKeywordEnabled   = snap.keyword,
                        isStrictMode       = snap.strict,
                        delayUnlockSeconds = snap.delay,
                        aiThreshold        = snap.threshold
                    )
                }
            }
        }
        viewModelScope.launch {
            prefs.aiIntervalMs.collect { ms ->
                _uiState.update { it.copy(aiIntervalMs = ms) }
            }
        }
    }

    fun refreshPinStatus() {
        _uiState.update { it.copy(isPinSet = isPinSetUseCase()) }
    }

    /**
     * Toggle AI detection — saves preference AND broadcasts to service.
     * Broadcast is sent AFTER DataStore write completes to avoid race condition
     * where service reads stale "false" value and immediately stops AI.
     */
    fun toggleAi(enabled: Boolean, modelAvailable: Boolean = false) {
        viewModelScope.launch {
            toggleAiDetectionUseCase(enabled)
            // Pref is now saved — safe to notify the service
            if (enabled && modelAvailable) {
                notifyService(GuardianAccessibilityService.ACTION_RELOAD_MODEL)
                _uiState.update { it.copy(snackMessage = "AI detection enabled ✓") }
            } else if (!enabled) {
                notifyService(GuardianAccessibilityService.ACTION_REFRESH_RULES)
                _uiState.update { it.copy(snackMessage = "AI detection disabled") }
            }
            // If enabled but no model: SettingsActivity shows the warning, no broadcast needed
        }
    }

    fun toggleKeyword(enabled: Boolean) {
        viewModelScope.launch {
            toggleKeywordDetectionUseCase(enabled)
            notifyService(GuardianAccessibilityService.ACTION_REFRESH_RULES)
        }
    }

    // BUG FIX: toggleStrictMode() was missing — SettingsActivity had no way to call it.
    // ToggleStrictModeUseCase now exists; this method saves the pref and notifies the service.
    fun toggleStrictMode(enabled: Boolean) {
        viewModelScope.launch {
            toggleStrictModeUseCase(enabled)
            notifyService(GuardianAccessibilityService.ACTION_REFRESH_RULES)
        }
    }

    fun setDelaySeconds(secs: Int) {
        viewModelScope.launch {
            setDelayUnlockSecondsUseCase(secs.coerceIn(10, 300))
        }
    }

    fun setAiThreshold(v: Float) {
        viewModelScope.launch {
            prefs.setAiThreshold(v)
        }
    }

    fun showMessage(msg: String) {
        _uiState.update { it.copy(snackMessage = msg) }
    }

    fun clearMessage() {
        _uiState.update { it.copy(snackMessage = null) }
    }

    private fun notifyService(action: String) {
        try {
            context.sendBroadcast(Intent(action).apply {
                setPackage(context.packageName)
            })
        } catch (e: Exception) {
            Timber.e(e, "notifyService $action")
        }
    }
}

// ─────────────────────────────────────────────────────────────────────
// Keyword ViewModel
// ─────────────────────────────────────────────────────────────────────

data class KeywordUiState(
    val keywords: List<KeywordRule>  = emptyList(),
    val inputText: String            = "",
    val errorMessage: String?        = null
)

@HiltViewModel
class KeywordViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val observeKeywordsUseCase: ObserveKeywordsUseCase,
    private val addKeywordUseCase: AddKeywordUseCase,
    private val removeKeywordUseCase: RemoveKeywordUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(KeywordUiState())
    val uiState: StateFlow<KeywordUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            observeKeywordsUseCase()
                .catch { e -> Timber.e(e) }
                .collect { list -> _uiState.update { it.copy(keywords = list) } }
        }
    }

    fun updateInput(text: String) {
        _uiState.update { it.copy(inputText = text, errorMessage = null) }
    }

    fun addKeyword() {
        val text = _uiState.value.inputText.trim()
        if (text.length < 2) {
            _uiState.update { it.copy(errorMessage = "Keyword must be at least 2 characters") }
            return
        }
        viewModelScope.launch {
            val added = addKeywordUseCase(text)
            if (added) {
                _uiState.update { it.copy(inputText = "", errorMessage = null) }
                notifyService()
            } else {
                _uiState.update { it.copy(errorMessage = "Could not add keyword") }
            }
        }
    }

    fun removeKeyword(id: Long) {
        viewModelScope.launch {
            removeKeywordUseCase(id)
            notifyService()
        }
    }

    private fun notifyService() {
        try {
            context.sendBroadcast(
                Intent(GuardianAccessibilityService.ACTION_REFRESH_RULES).apply {
                    setPackage(context.packageName)
                }
            )
        } catch (e: Exception) {
            Timber.e(e, "keyword notifyService")
        }
    }
}

// ─────────────────────────────────────────────────────────────────────
// PIN ViewModel
// ─────────────────────────────────────────────────────────────────────

data class PinUiState(
    val input: String           = "",
    val error: String?          = null,
    val isVerified: Boolean     = false,
    val isPinSet: Boolean       = false
)

@HiltViewModel
class PinViewModel @Inject constructor(
    private val setupPinUseCase: SetupPinUseCase,
    private val verifyPinUseCase: VerifyPinUseCase,
    private val isPinSetUseCase: IsPinSetUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(PinUiState())
    val uiState: StateFlow<PinUiState> = _uiState.asStateFlow()

    private val _confirmInput = MutableStateFlow("")

    init {
        _uiState.update { it.copy(isPinSet = isPinSetUseCase()) }
    }

    fun updateInput(pin: String) {
        if (pin.length > 8 || !pin.all { it.isDigit() }) return
        _uiState.update { it.copy(input = pin, error = null) }
    }

    fun updateConfirm(pin: String) {
        if (pin.length > 8 || !pin.all { it.isDigit() }) return
        _confirmInput.value = pin
        _uiState.update { it.copy(error = null) }
    }

    fun setupPin() {
        val result = setupPinUseCase(_uiState.value.input, _confirmInput.value)
        when (result) {
            PinSetupResult.Success      -> _uiState.update { it.copy(isVerified = true, isPinSet = true) }
            PinSetupResult.TooShort     -> _uiState.update { it.copy(error = "PIN must be at least 4 digits") }
            PinSetupResult.Mismatch     -> _uiState.update { it.copy(error = "PINs do not match") }
            PinSetupResult.InvalidChars -> _uiState.update { it.copy(error = "PIN must be digits only") }
            PinSetupResult.Failed       -> _uiState.update { it.copy(error = "Failed to save PIN") }
        }
    }

    fun verifyPin() {
        val correct = verifyPinUseCase(_uiState.value.input)
        if (correct) {
            _uiState.update { it.copy(isVerified = true, error = null) }
        } else {
            _uiState.update { it.copy(error = "Incorrect PIN", input = "") }
        }
    }

    fun clearInput() {
        _uiState.update { it.copy(input = "", error = null) }
    }
}