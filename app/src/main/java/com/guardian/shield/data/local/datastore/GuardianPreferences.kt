// app/src/main/java/com/guardian/shield/data/local/datastore/GuardianPreferences.kt
package com.guardian.shield.data.local.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "guardian_prefs")

@Singleton
class GuardianPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        val KEY_PROTECTION_ENABLED  = booleanPreferencesKey("protection_enabled")
        val KEY_AI_DETECTION_ON     = booleanPreferencesKey("ai_detection_enabled")
        val KEY_KEYWORD_DETECTION_ON= booleanPreferencesKey("keyword_detection_enabled")
        val KEY_STRICT_MODE         = booleanPreferencesKey("strict_mode")
        val KEY_DELAY_UNLOCK_SECS   = intPreferencesKey("delay_unlock_seconds")
        val KEY_FIRST_RUN           = booleanPreferencesKey("first_run")
        val KEY_AI_THRESHOLD        = floatPreferencesKey("ai_threshold")
        val KEY_AI_INTERVAL_MS      = longPreferencesKey("ai_interval_ms")
    }

    val isProtectionEnabled: Flow<Boolean> = context.dataStore.data
        .catch { e -> Timber.e(e, "DataStore read error"); emit(emptyPreferences()) }
        .map { it[KEY_PROTECTION_ENABLED] ?: true }

    val isAiDetectionEnabled: Flow<Boolean> = context.dataStore.data
        .catch { e -> Timber.e(e, "DataStore read error"); emit(emptyPreferences()) }
        .map { it[KEY_AI_DETECTION_ON] ?: false }

    val isKeywordDetectionEnabled: Flow<Boolean> = context.dataStore.data
        .catch { e -> Timber.e(e, "DataStore read error"); emit(emptyPreferences()) }
        .map { it[KEY_KEYWORD_DETECTION_ON] ?: true }

    val isStrictMode: Flow<Boolean> = context.dataStore.data
        .catch { e -> Timber.e(e, "DataStore read error"); emit(emptyPreferences()) }
        .map { it[KEY_STRICT_MODE] ?: false }

    val delayUnlockSeconds: Flow<Int> = context.dataStore.data
        .catch { e -> Timber.e(e, "DataStore read error"); emit(emptyPreferences()) }
        .map { (it[KEY_DELAY_UNLOCK_SECS] ?: 30).coerceIn(10, 300) }

    val isFirstRun: Flow<Boolean> = context.dataStore.data
        .catch { e -> Timber.e(e, "DataStore read error"); emit(emptyPreferences()) }
        .map { it[KEY_FIRST_RUN] ?: true }

    val aiThreshold: Flow<Float> = context.dataStore.data
        .catch { e -> Timber.e(e, "DataStore read error"); emit(emptyPreferences()) }
        .map { it[KEY_AI_THRESHOLD] ?: 0.40f }

    // BUG FIX #6: Default interval reduced from 2500ms → 1000ms.
    // Old 2500ms + 12 tile inferences (~600ms) + overhead = ~3.5s blur delay.
    // New 1000ms = blur appears within ~1.5s of content being detected.
    // Range kept 1000-10000ms. Users who need less CPU usage can increase via settings.
    val aiIntervalMs: Flow<Long> = context.dataStore.data
        .catch { e -> Timber.e(e, "DataStore read error"); emit(emptyPreferences()) }
        .map { it[KEY_AI_INTERVAL_MS] ?: 1_000L }

    suspend fun setProtectionEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_PROTECTION_ENABLED] = enabled }
    }

    suspend fun setAiDetection(enabled: Boolean) {
        context.dataStore.edit { it[KEY_AI_DETECTION_ON] = enabled }
    }

    suspend fun setKeywordDetection(enabled: Boolean) {
        context.dataStore.edit { it[KEY_KEYWORD_DETECTION_ON] = enabled }
    }

    suspend fun setStrictMode(enabled: Boolean) {
        context.dataStore.edit { it[KEY_STRICT_MODE] = enabled }
    }

    suspend fun setDelayUnlockSeconds(secs: Int) {
        context.dataStore.edit { it[KEY_DELAY_UNLOCK_SECS] = secs.coerceIn(10, 300) }
    }

    suspend fun setFirstRunDone() {
        context.dataStore.edit { it[KEY_FIRST_RUN] = false }
    }

    suspend fun setAiThreshold(v: Float) {
        context.dataStore.edit { it[KEY_AI_THRESHOLD] = v.coerceIn(0.10f, 0.90f) }
    }

    suspend fun setAiIntervalMs(ms: Long) {
        context.dataStore.edit { it[KEY_AI_INTERVAL_MS] = ms.coerceIn(1_000L, 10_000L) }
    }
}
