package com.guardian.shield.di

import android.content.Context
import androidx.room.Room
import com.guardian.shield.data.local.db.*
import com.guardian.shield.data.repository.*
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

// ── Database Module ───────────────────────────────────────────────────

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext ctx: Context): GuardianDatabase =
        Room.databaseBuilder(ctx, GuardianDatabase::class.java, "guardian_db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideAppRuleDao(db: GuardianDatabase): AppRuleDao = db.appRuleDao()

    @Provides
    fun provideKeywordRuleDao(db: GuardianDatabase): KeywordRuleDao = db.keywordRuleDao()

    @Provides
    fun provideBlockEventDao(db: GuardianDatabase): BlockEventDao = db.blockEventDao()
}

// ── Repository Module ─────────────────────────────────────────────────

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindAppRuleRepo(impl: AppRuleRepositoryImpl): AppRuleRepository

    @Binds
    @Singleton
    abstract fun bindKeywordRepo(impl: KeywordRepositoryImpl): KeywordRepository

    @Binds
    @Singleton
    abstract fun bindBlockEventRepo(impl: BlockEventRepositoryImpl): BlockEventRepository
}
