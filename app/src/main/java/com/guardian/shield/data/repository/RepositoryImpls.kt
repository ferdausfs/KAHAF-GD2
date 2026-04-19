package com.guardian.shield.data.repository

import com.guardian.shield.data.local.db.*
import com.guardian.shield.domain.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppRuleRepositoryImpl @Inject constructor(
    private val dao: AppRuleDao
) : AppRuleRepository {

    override fun observeBlockedApps(): Flow<List<AppRule>> =
        dao.observeBlockedApps().map { list -> list.map { it.toDomain() } }

    override fun observeWhitelistedApps(): Flow<List<AppRule>> =
        dao.observeWhitelistedApps().map { list -> list.map { it.toDomain() } }

    override fun observeAllRules(): Flow<List<AppRule>> =
        dao.observeAllRules().map { list -> list.map { it.toDomain() } }

    override suspend fun addBlockedApp(rule: AppRule) {
        dao.upsert(rule.copy(isBlocked = true, isWhitelisted = false).toEntity())
    }

    override suspend fun addWhitelistedApp(rule: AppRule) {
        dao.upsert(rule.copy(isWhitelisted = true, isBlocked = false).toEntity())
    }

    override suspend fun removeRule(packageName: String) {
        dao.deleteByPackage(packageName)
    }

    override suspend fun getBlockedPackages(): Set<String> =
        dao.getBlockedPackages().toSet()

    override suspend fun getWhitelistedPackages(): Set<String> =
        dao.getWhitelistedPackages().toSet()

    override suspend fun isBlocked(pkg: String): Boolean =
        dao.getRule(pkg)?.isBlocked == true

    override suspend fun isWhitelisted(pkg: String): Boolean =
        dao.getRule(pkg)?.isWhitelisted == true
}

@Singleton
class KeywordRepositoryImpl @Inject constructor(
    private val dao: KeywordRuleDao
) : KeywordRepository {

    override fun observeAll(): Flow<List<KeywordRule>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun addKeyword(keyword: String) {
        dao.insert(KeywordRuleEntity(keyword = keyword.trim().lowercase()))
    }

    override suspend fun removeKeyword(id: Long) {
        dao.deleteById(id)
    }

    override suspend fun toggleKeyword(id: Long, active: Boolean) {
        dao.setActive(id, active)
    }

    override suspend fun getActiveKeywords(): List<String> =
        dao.getActiveKeywords()
}

@Singleton
class BlockEventRepositoryImpl @Inject constructor(
    private val dao: BlockEventDao
) : BlockEventRepository {

    override fun observeRecent(): Flow<List<BlockEvent>> =
        dao.observeRecent().map { list -> list.map { it.toDomain() } }

    override suspend fun logEvent(event: BlockEvent) {
        dao.insert(event.toEntity())
    }

    override suspend fun getStats(): BlockStats {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startOfDay = cal.timeInMillis
        val latest = dao.getLatest()
        return BlockStats(
            totalBlocked  = dao.getTotalCount(),
            todayBlocked  = dao.getTodayCount(startOfDay),
            lastBlockedApp = latest?.appName ?: "",
            lastBlockedAt  = latest?.timestamp ?: 0L
        )
    }

    override suspend fun cleanup(olderThanDays: Int) {
        val cutoff = System.currentTimeMillis() - (olderThanDays * 24 * 60 * 60 * 1000L)
        dao.deleteOlderThan(cutoff)
    }
}
