package com.guardian.shield.data.repository

import com.guardian.shield.domain.model.*
import kotlinx.coroutines.flow.Flow

interface AppRuleRepository {
    fun observeBlockedApps(): Flow<List<AppRule>>
    fun observeWhitelistedApps(): Flow<List<AppRule>>
    fun observeAllRules(): Flow<List<AppRule>>
    suspend fun addBlockedApp(rule: AppRule)
    suspend fun addWhitelistedApp(rule: AppRule)
    suspend fun removeRule(packageName: String)
    suspend fun getBlockedPackages(): Set<String>
    suspend fun getWhitelistedPackages(): Set<String>
    suspend fun isBlocked(pkg: String): Boolean
    suspend fun isWhitelisted(pkg: String): Boolean
}

interface KeywordRepository {
    fun observeAll(): Flow<List<KeywordRule>>
    suspend fun addKeyword(keyword: String)
    suspend fun removeKeyword(id: Long)
    suspend fun toggleKeyword(id: Long, active: Boolean)
    suspend fun getActiveKeywords(): List<String>
}

interface BlockEventRepository {
    fun observeRecent(): Flow<List<BlockEvent>>
    suspend fun logEvent(event: BlockEvent)
    suspend fun getStats(): BlockStats
    suspend fun cleanup(olderThanDays: Int = 30)
}
