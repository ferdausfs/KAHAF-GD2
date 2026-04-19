// app/src/main/java/com/guardian/shield/data/local/db/GuardianDatabase.kt
package com.guardian.shield.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase

// BUG FIX #9 (cont.): Bumped version from 1 → 2 because we added an index to
// block_events (Entities.kt). Room detects schema changes via version number.
// Without bumping: Room will crash at runtime with "Expected identity hash doesn't match".
// AppModule uses fallbackToDestructiveMigration() so no manual migration needed —
// existing events are cleared (acceptable for a security app where events are non-critical).
@Database(
    entities = [
        AppRuleEntity::class,
        KeywordRuleEntity::class,
        BlockEventEntity::class
    ],
    version = 2,         // ← bumped from 1 (schema changed: added index on block_events.timestamp)
    exportSchema = false
)
abstract class GuardianDatabase : RoomDatabase() {
    abstract fun appRuleDao(): AppRuleDao
    abstract fun keywordRuleDao(): KeywordRuleDao
    abstract fun blockEventDao(): BlockEventDao
}
