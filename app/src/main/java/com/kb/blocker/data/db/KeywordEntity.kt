package com.kb.blocker.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "keywords",
    indices = [Index(value = ["keyword"], unique = true)]
)
data class KeywordEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val keyword: String,
    val createdAt: Long = System.currentTimeMillis()
)
