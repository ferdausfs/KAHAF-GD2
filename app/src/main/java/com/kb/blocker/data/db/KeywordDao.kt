package com.kb.blocker.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface KeywordDao {

    @Query("SELECT * FROM keywords ORDER BY createdAt DESC")
    fun getAllKeywords(): Flow<List<KeywordEntity>>

    @Query("SELECT * FROM keywords ORDER BY createdAt DESC")
    suspend fun getAllKeywordsSync(): List<KeywordEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(keyword: KeywordEntity): Long

    @Delete
    suspend fun delete(keyword: KeywordEntity)

    @Query("DELETE FROM keywords WHERE id = :id")
    suspend fun deleteById(id: Int)

    @Query("DELETE FROM keywords")
    suspend fun deleteAll()
}
