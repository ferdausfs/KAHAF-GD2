package com.guardian.shield.viewmodel

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.view.accessibility.AccessibilityManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.guardian.shield.data.local.datastore.GuardianPreferences
import com.guardian.shield.domain.model.BlockEvent
import com.guardian.shield.domain.model.BlockStats
import com.guardian.shield.domain.model.ProtectionState
import com.guardian.shield.domain.usecase.*
import com.guardian.shield.service.accessibility.GuardianAccessibilityService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

data class DashboardUiState(
    val protectionState: ProtectionState = ProtectionState(),
    val stats: BlockStats = BlockStats(),
    val recentEvents: List<BlockEvent> = emptyList(),
    val isProtectionOn: Boolean = true,
    val isAiOn: Boolean = false,
    val isKeywordOn: Boolean = true,
    val delayUnlockSeconds: Int = 30,
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val observeBlockEventsUseCase: ObserveBlockEventsUseCase,
    private val getBlockStatsUseCase: GetBlockStatsUseCase,
    private val toggleProtectionUseCase: ToggleProtectionUseCase,
    private val isPinSetUseCase: IsPinSetUseCase,
    private val prefs: GuardianPreferences
) : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    init {
        loadAll()
        observeRecentEvents()
        observePrefs()
    }

    private fun loadAll() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val stats = getBlockStatsUseCase()
                _uiState.update { it.copy(stats = stats, isLoading = false) }
            } catch (e: Exception) {
                Timber.e(e, "loadAll error")
                _uiState.update { it.copy(isLoading = false, errorMessage = e.message) }
            }
        }
    }

    private fun observeRecentEvents() {
        viewModelScope.launch {
            observeBlockEventsUseCase()
                .catch { e ->
                    Timber.e(e, "observeRecentEvents error")
                    _uiState.update { it.copy(errorMessage = "Failed to load events") }
                }
                .collect { events ->
                    _uiState.update { it.copy(recentEvents = events.take(10)) }
                    // BUG FIX: Wrap stats refresh in try-catch.
                    // Without this, a Room IO failure would propagate out of collect(),
                    // cancel the entire coroutine, and freeze the dashboard permanently.
                    try {
                        val stats = getBlockStatsUseCase()
                        _uiState.update { it.copy(stats = stats) }
                    } catch (e: Exception) {
                        Timber.e(e, "stats refresh error")
                    }
                }
        }
    }

    private fun observePrefs() {
        viewModelScope.launch {
            // BUG FIX: 4-arg combine uses the typed overload, avoiding Array<*> unboxing issues.
            combine(
                prefs.isProtectionEnabled,
                prefs.isAiDetectionEnabled,
                prefs.isKeywordDetectionEnabled,
                prefs.delayUnlockSeconds
            ) { protection: Boolean, ai: Boolean, keyword: Boolean, delay: Int ->
                _uiState.update {
                    it.copy(
                        isProtectionOn     = protection,
                        isAiOn             = ai,
                        isKeywordOn        = keyword,
                        delayUnlockSeconds = delay
                    )
                }
            }.collect()
        }
    }

    fun refreshProtectionState() {
        val isPinSet = isPinSetUseCase()
        val accessibility = isAccessibilityEnabled()
        val protState = ProtectionState(
            isAccessibilityEnabled      = accessibility,
            isOverlayPermissionGranted  = true, // overlay not strictly needed for this approach
            isPinSet                    = isPinSet,
            isProtectionActive          = accessibility && _uiState.value.isProtectionOn
        )
        _uiState.update { it.copy(protectionState = protState) }
    }

    fun toggleProtection(enabled: Boolean) {
        viewModelScope.launch {
            toggleProtectionUseCase(enabled)
            // Notify service
            notifyServiceRulesChanged()
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    private fun notifyServiceRulesChanged() {
        try {
            context.sendBroadcast(
                Intent(GuardianAccessibilityService.ACTION_REFRESH_RULES).apply {
                    setPackage(context.packageName)
                }
            )
        } catch (e: Exception) {
            Timber.e(e, "notifyServiceRulesChanged failed")
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        return try {
            val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { it.id.contains("GuardianAccessibilityService") }
        } catch (_: Exception) { false }
    }
}
