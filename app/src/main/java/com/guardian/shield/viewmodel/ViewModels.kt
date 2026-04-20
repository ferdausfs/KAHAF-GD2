// app/src/main/java/com/guardian/shield/viewmodel/ViewModels.kt
package com.guardian.shield.viewmodel

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.guardian.shield.data.local.datastore.GuardianPreferences
import com.guardian.shield.domain.model.KeywordRule
import com.guardian.shield.domain.usecase.*
import com.guardian.shield.service.detection.PinManager
import com.guardian.shield.service.accessibility.GuardianAccessibilityService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

data class SettingsUiState(
    val isAiEnabled: Boolean = false,
    val isKeywordEnabled: Boolean = true,
    val isStrictMode: Boolean = false,
    val delayUnlockSeconds: Int = 30,
    val aiThreshold: Float = 0.35f,
    val aiIntervalMs: Long = 1_500L,
    val isPinSet: Boolean = false,
    val snackMessage: String? = null
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
            combine(
                prefs.isAiDetectionEnabled,
                prefs.isKeywordDetectionEnabled,
                prefs.isStrictMode,
                prefs.delayUnlockSeconds,
                prefs.aiThreshold
            ) { ai, keyword, strict, delay, threshold ->
                SettingsSnapshot(ai, keyword, strict, delay, threshold)
            }.collect { snap ->
                _uiState.update {
                    it.copy(
                        isAiEnabled = snap.ai,
                        isKeywordEnabled = snap.keyword,
                        isStrictMode = snap.strict,
                        delayUnlockSeconds = snap.delay,
                        aiThreshold = snap.threshold
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

    fun toggleAi(enabled: Boolean, modelAvailable: Boolean = false) {
        viewModelScope.launch {
            toggleAiDetectionUseCase(enabled)
            if (enabled && modelAvailable) {
                notifyService(GuardianAccessibilityService.ACTION_RELOAD_MODEL)
                _uiState.update { it.copy(snackMessage = "AI detection enabled ✓") }
            } else if (!enabled) {
                notifyService(GuardianAccessibilityService.ACTION_RELOAD_MODEL)
                _uiState.update { it.copy(snackMessage = "AI detection disabled") }
            }
        }
    }

    fun toggleKeyword(enabled: Boolean) {
        viewModelScope.launch {
            toggleKeywordDetectionUseCase(enabled)
            notifyService(GuardianAccessibilityService.ACTION_REFRESH_RULES)
        }
    }

    fun toggleStrictMode(enabled: Boolean) {
        viewModelScope.launch {
            toggleStrictModeUseCase(enabled)
            notifyService(GuardianAccessibilityService.ACTION_REFRESH_RULES)
            _uiState.update {
                it.copy(snackMessage = if (enabled)
                    "Strict mode ON — only trusted apps allowed"
                else
                    "Strict mode OFF")
            }
        }
    }

    fun setDelaySeconds(secs: Int) {
        viewModelScope.launch {
            setDelayUnlockSecondsUseCase(secs.coerceIn(10, 300))
            notifyService(GuardianAccessibilityService.ACTION_UPDATE_SETTINGS)
        }
    }

    fun setAiThreshold(v: Float) {
        viewModelScope.launch {
            prefs.setAiThreshold(v)
            notifyService(GuardianAccessibilityService.ACTION_UPDATE_SETTINGS)
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

data class KeywordUiState(
    val keywords: List<KeywordRule> = emptyList(),
    val inputText: String = "",
    val errorMessage: String? = null
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
        viewModelScope.launch {
            val text = _uiState.value.inputText.trim()
            if (text.length < 2) {
                _uiState.update { it.copy(errorMessage = "At least 2 characters") }
                return@launch
            }
            val added = addKeywordUseCase(text)
            if (added) {
                _uiState.update { it.copy(inputText = "", errorMessage = null) }
                notifyService()
            } else {
                _uiState.update { it.copy(errorMessage = "Too short") }
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
            Timber.e(e, "notifyService")
        }
    }
}

data class PinUiState(
    val input: String = "",
    val confirm: String = "",
    val error: String? = null,
    val isVerified: Boolean = false
)

@HiltViewModel
class PinViewModel @Inject constructor(
    private val verifyPinUseCase: VerifyPinUseCase,
    private val setupPinUseCase: SetupPinUseCase,
    private val isPinSetUseCase: IsPinSetUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(PinUiState())
    val uiState: StateFlow<PinUiState> = _uiState.asStateFlow()

    fun updateInput(input: String) {
        _uiState.update { it.copy(input = input, error = null) }
    }

    fun updateConfirm(confirm: String) {
        _uiState.update { it.copy(confirm = confirm, error = null) }
    }

    fun setupPin() {
        val pin = _uiState.value.input
        val confirm = _uiState.value.confirm
        when (setupPinUseCase(pin, confirm)) {
            PinSetupResult.Success -> _uiState.update { it.copy(isVerified = true, error = null) }
            PinSetupResult.TooShort -> _uiState.update { it.copy(error = "Min ${PinManager.MIN_PIN_LENGTH} digits") }
            PinSetupResult.Mismatch -> _uiState.update { it.copy(error = "PINs do not match") }
            PinSetupResult.InvalidChars -> _uiState.update { it.copy(error = "Digits only") }
            PinSetupResult.Failed -> _uiState.update { it.copy(error = "Failed to save") }
        }
    }

    // FIX #6: Handle lockout result
    fun verifyPin() {
        val pin = _uiState.value.input
        when (val result = verifyPinUseCase(pin)) {
            is PinManager.VerifyResult.Success ->
                _uiState.update { it.copy(isVerified = true, error = null) }
            is PinManager.VerifyResult.WrongPin ->
                _uiState.update { it.copy(input = "", error = "Incorrect PIN") }
            is PinManager.VerifyResult.LockedOut -> {
                val seconds = (result.remainingMs / 1000).toInt()
                val mins = seconds / 60
                val secs = seconds % 60
                val msg = if (mins > 0) "Locked. Try in ${mins}m ${secs}s"
                          else "Locked. Try in ${secs}s"
                _uiState.update { it.copy(input = "", error = msg) }
            }
            is PinManager.VerifyResult.NotSet ->
                _uiState.update { it.copy(error = "PIN not set") }
        }
    }

    fun isPinSet(): Boolean = isPinSetUseCase()

    fun reset() {
        _uiState.update { PinUiState() }
    }
}