package com.threadlog.feature.autotag.di

import com.threadlog.core.model.contract.AutoTagProvider
import com.threadlog.core.model.contract.AutoTagScheduler
import com.threadlog.feature.autotag.OfflineRuleAutoTagger
import com.threadlog.feature.autotag.WorkManagerAutoTagScheduler
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AutoTagDiModule {

    @Binds
    @Singleton
    abstract fun bindAutoTagProvider(impl: OfflineRuleAutoTagger): AutoTagProvider

    @Binds
    @Singleton
    abstract fun bindAutoTagScheduler(impl: WorkManagerAutoTagScheduler): AutoTagScheduler
}
