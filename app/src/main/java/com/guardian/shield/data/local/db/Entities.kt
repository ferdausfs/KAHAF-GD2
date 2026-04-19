// app/src/main/java/com/guardian/shield/data/local/db/Entities.kt
package com.guardian.shield.data.local.db

import androidx.room.Entity
import androidx.room.Index
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

// BUG FIX #9: Added @Index on `timestamp`.
// getTodayCount(startOfDay) and observeRecent() both filter/sort by timestamp.
// Without this index, every query is a full table scan — gets slow as events accumulate.
// With the index, both queries are O(log n) regardless of table size.
@Entity(
    tableName = "block_events",
    indices = [Index(value = ["timestamp"])]
)
data class BlockEventEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val packageName: String,
    val appName: String,
    val reason: String,       // BlockReason.name
    val detail: String = "",
    val timestamp: Long = System.currentTimeMillis()
)
