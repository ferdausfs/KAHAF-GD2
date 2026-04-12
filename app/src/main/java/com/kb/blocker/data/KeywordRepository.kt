package com.kb.blocker.data

import android.content.Context
import com.kb.blocker.data.db.AppDatabase
import com.kb.blocker.data.db.KeywordEntity
import kotlinx.coroutines.flow.Flow

class KeywordRepository(context: Context) {

    private val dao = AppDatabase.getInstance(context).keywordDao()

    val allKeywords: Flow<List<KeywordEntity>> = dao.getAllKeywords()

    suspend fun addKeyword(keyword: String): Boolean {
        if (keyword.isBlank()) return false
        val result = dao.insert(KeywordEntity(keyword = keyword.trim().lowercase()))
        return result != -1L
    }

    suspend fun deleteKeyword(entity: KeywordEntity) {
        dao.delete(entity)
    }

    suspend fun deleteAll() {
        dao.deleteAll()
    }

    suspend fun getAllSync(): List<String> {
        return dao.getAllKeywordsSync().map { it.keyword }
    }
}
