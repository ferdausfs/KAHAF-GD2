// app/src/main/java/com/guardian/shield/di/AccessibilityServiceEntryPoint.kt
package com.guardian.shield.di

import com.guardian.shield.data.local.datastore.GuardianPreferences
import com.guardian.shield.data.repository.AppRuleRepository
import com.guardian.shield.data.repository.BlockEventRepository
import com.guardian.shield.data.repository.KeywordRepository
import com.guardian.shield.service.blur.CumulativeBlurTracker
import com.guardian.shield.service.blocker.BlockingEngine
import com.guardian.shield.service.detection.AiDetector
import com.guardian.shield.service.detection.RulesEngine
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface AccessibilityServiceEntryPoint {
    fun rulesEngine(): RulesEngine
    fun blockingEngine(): BlockingEngine
    fun aiDetector(): AiDetector
    fun appRuleRepo(): AppRuleRepository
    fun keywordRepo(): KeywordRepository
    fun blockEventRepo(): BlockEventRepository
    fun prefs(): GuardianPreferences
    // BlurOverlayManager is NOT injected — it's created manually in the
    // AccessibilityService with the service as context (TYPE_ACCESSIBILITY_OVERLAY)
    fun cumulativeBlurTracker(): CumulativeBlurTracker
}
