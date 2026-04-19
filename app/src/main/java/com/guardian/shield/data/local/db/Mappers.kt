package com.guardian.shield.data.local.db

import com.guardian.shield.domain.model.*

fun AppRuleEntity.toDomain() = AppRule(
    packageName = packageName,
    appName     = appName,
    isWhitelisted = isWhitelisted,
    isBlocked   = isBlocked,
    addedAt     = addedAt
)

fun AppRule.toEntity() = AppRuleEntity(
    packageName = packageName,
    appName     = appName,
    isWhitelisted = isWhitelisted,
    isBlocked   = isBlocked,
    addedAt     = addedAt
)

fun KeywordRuleEntity.toDomain() = KeywordRule(
    id       = id,
    keyword  = keyword,
    isActive = isActive,
    addedAt  = addedAt
)

fun KeywordRule.toEntity() = KeywordRuleEntity(
    id       = id,
    keyword  = keyword,
    isActive = isActive,
    addedAt  = addedAt
)

fun BlockEventEntity.toDomain() = BlockEvent(
    id          = id,
    packageName = packageName,
    appName     = appName,
    // BUG FIX: BlockReason.valueOf() throws IllegalArgumentException if the DB contains
    // an unknown string (e.g. after a rename or old DB from a previous version).
    // Use a safe fallback instead of crashing.
    reason      = try { BlockReason.valueOf(reason) } catch (_: Exception) { BlockReason.APP_BLOCKED },
    detail      = detail,
    timestamp   = timestamp
)

fun BlockEvent.toEntity() = BlockEventEntity(
    packageName = packageName,
    appName     = appName,
    reason      = reason.name,
    detail      = detail,
    timestamp   = timestamp
)
