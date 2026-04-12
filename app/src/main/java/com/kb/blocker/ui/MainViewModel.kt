package com.kb.blocker.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kb.blocker.data.KeywordRepository
import com.kb.blocker.data.db.KeywordEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = KeywordRepository(app)

    /** UI এ collect করো — lifecycle-aware */
    val keywords = repo.allKeywords.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList()
    )

    fun addKeyword(keyword: String) {
        viewModelScope.launch { repo.addKeyword(keyword) }
    }

    fun deleteKeyword(entity: KeywordEntity) {
        viewModelScope.launch { repo.deleteKeyword(entity) }
    }

    fun deleteAll() {
        viewModelScope.launch { repo.deleteAll() }
    }
}
