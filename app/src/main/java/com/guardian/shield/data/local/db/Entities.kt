package com.guardian.shield.data.local.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.guardian.shield.domain.model.BlockReason

@Entity(tableName = "app_rules")
data class AppRuleEntity(
    @PrimaryKey
    val packageName: String,
    val appName: String,
    val isWhitelisted: Boolean = false,
    val isBlocked: Boolean = false,
    val addedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "keyword_rules")
data class KeywordRuleEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val keyword: String,
    val isActive: Boolean = true,
    val addedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "block_events")
data class BlockEventEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val packageName: String,
    val appName: String,
    val reason: String,       // BlockReason.name
    val detail: String = "",
    val timestamp: Long = System.currentTimeMillis()
)
