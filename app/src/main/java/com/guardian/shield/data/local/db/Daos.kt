package com.guardian.shield.data.local.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface AppRuleDao {

    @Query("SELECT * FROM app_rules WHERE isBlocked = 1")
    fun observeBlockedApps(): Flow<List<AppRuleEntity>>

    @Query("SELECT * FROM app_rules WHERE isWhitelisted = 1")
    fun observeWhitelistedApps(): Flow<List<AppRuleEntity>>

    @Query("SELECT * FROM app_rules")
    fun observeAllRules(): Flow<List<AppRuleEntity>>

    @Query("SELECT * FROM app_rules WHERE packageName = :pkg LIMIT 1")
    suspend fun getRule(pkg: String): AppRuleEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: AppRuleEntity)

    @Delete
    suspend fun delete(entity: AppRuleEntity)

    @Query("DELETE FROM app_rules WHERE packageName = :pkg")
    suspend fun deleteByPackage(pkg: String)

    @Query("SELECT packageName FROM app_rules WHERE isBlocked = 1")
    suspend fun getBlockedPackages(): List<String>

    @Query("SELECT packageName FROM app_rules WHERE isWhitelisted = 1")
    suspend fun getWhitelistedPackages(): List<String>
}

@Dao
interface KeywordRuleDao {

    @Query("SELECT * FROM keyword_rules ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<KeywordRuleEntity>>

    @Query("SELECT keyword FROM keyword_rules WHERE isActive = 1")
    suspend fun getActiveKeywords(): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: KeywordRuleEntity): Long

    @Query("UPDATE keyword_rules SET isActive = :active WHERE id = :id")
    suspend fun setActive(id: Long, active: Boolean)

    @Query("DELETE FROM keyword_rules WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT COUNT(*) FROM keyword_rules WHERE isActive = 1")
    suspend fun getActiveCount(): Int
}

@Dao
interface BlockEventDao {

    @Query("SELECT * FROM block_events ORDER BY timestamp DESC LIMIT 100")
    fun observeRecent(): Flow<List<BlockEventEntity>>

    @Insert
    suspend fun insert(entity: BlockEventEntity)

    @Query("SELECT COUNT(*) FROM block_events")
    suspend fun getTotalCount(): Int

    @Query("SELECT COUNT(*) FROM block_events WHERE timestamp >= :startOfDay")
    suspend fun getTodayCount(startOfDay: Long): Int

    @Query("SELECT * FROM block_events ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatest(): BlockEventEntity?

    @Query("DELETE FROM block_events WHERE timestamp < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)
}
