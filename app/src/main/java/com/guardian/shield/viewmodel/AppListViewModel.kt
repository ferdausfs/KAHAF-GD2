package com.guardian.shield.viewmodel

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.guardian.shield.domain.model.AppRule
import com.guardian.shield.domain.usecase.*
import com.guardian.shield.service.accessibility.GuardianAccessibilityService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

data class InstalledApp(
    val packageName: String,
    val appName: String
)

data class AppListUiState(
    val blockedApps: List<AppRule>      = emptyList(),
    val whitelistedApps: List<AppRule>  = emptyList(),
    val installedApps: List<InstalledApp> = emptyList(),
    val isLoadingInstalled: Boolean     = false,
    val searchQuery: String             = "",
    val errorMessage: String?           = null
)

@HiltViewModel
class AppListViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val observeBlockedAppsUseCase: ObserveBlockedAppsUseCase,
    private val observeWhitelistedAppsUseCase: ObserveWhitelistedAppsUseCase,
    private val addBlockedAppUseCase: AddBlockedAppUseCase,
    private val addWhitelistedAppUseCase: AddWhitelistedAppUseCase,
    private val removeAppRuleUseCase: RemoveAppRuleUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(AppListUiState())
    val uiState: StateFlow<AppListUiState> = _uiState.asStateFlow()

    // Filtered installed apps based on search query
    val filteredInstalledApps: StateFlow<List<InstalledApp>> = combine(
        _uiState.map { it.installedApps },
        _uiState.map { it.searchQuery }
    ) { apps, query ->
        if (query.isBlank()) apps
        else apps.filter { it.appName.contains(query, ignoreCase = true) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        observeRules()
        loadInstalledApps()
    }

    private fun observeRules() {
        viewModelScope.launch {
            observeBlockedAppsUseCase()
                .catch { e -> Timber.e(e, "observeBlocked") }
                .collect { list -> _uiState.update { it.copy(blockedApps = list) } }
        }
        viewModelScope.launch {
            observeWhitelistedAppsUseCase()
                .catch { e -> Timber.e(e, "observeWhitelisted") }
                .collect { list -> _uiState.update { it.copy(whitelistedApps = list) } }
        }
    }

    fun loadInstalledApps() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingInstalled = true) }
            try {
                // BUG FIX: PackageManager.getInstalledApplications(GET_META_DATA) is a heavy IPC
                // call that blocks the calling thread. Must run on IO dispatcher to avoid ANR.
                val apps = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val pm = context.packageManager
                    // BUG FIX: was GET_META_DATA — loads ALL app metadata (icons, bundle data etc.)
                    // which is 5-10x slower and wastes memory. We only need label + packageName.
                    // Fix: pass 0 (no extra flags) — label is always available without flags.
                    pm.getInstalledApplications(0)
                        .filter { (it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0 }
                        .map { info ->
                            InstalledApp(
                                packageName = info.packageName,
                                appName     = pm.getApplicationLabel(info).toString()
                            )
                        }
                        .sortedBy { it.appName }
                }
                _uiState.update { it.copy(installedApps = apps, isLoadingInstalled = false) }
            } catch (e: Exception) {
                Timber.e(e, "loadInstalledApps")
                _uiState.update { it.copy(isLoadingInstalled = false, errorMessage = e.message) }
            }
        }
    }

    fun addToBlockedList(app: InstalledApp) {
        viewModelScope.launch {
            addBlockedAppUseCase(
                AppRule(packageName = app.packageName, appName = app.appName, isBlocked = true)
            )
            notifyService()
        }
    }

    fun addToWhitelist(app: InstalledApp) {
        viewModelScope.launch {
            addWhitelistedAppUseCase(
                AppRule(packageName = app.packageName, appName = app.appName, isWhitelisted = true)
            )
            notifyService()
        }
    }

    fun removeRule(packageName: String) {
        viewModelScope.launch {
            removeAppRuleUseCase(packageName)
            notifyService()
        }
    }

    fun updateSearch(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    private fun notifyService() {
        try {
            context.sendBroadcast(
                Intent(GuardianAccessibilityService.ACTION_REFRESH_RULES).apply {
                    setPackage(context.packageName)
                }
            )
        } catch (e: Exception) {
            Timber.e(e, "notifyService failed")
        }
    }
}
