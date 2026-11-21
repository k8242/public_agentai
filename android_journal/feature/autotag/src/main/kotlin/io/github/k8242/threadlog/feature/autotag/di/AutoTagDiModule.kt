package io.github.k8242.threadlog.feature.autotag.di

import io.github.k8242.threadlog.core.model.contract.AutoTagProvider
import io.github.k8242.threadlog.core.model.contract.AutoTagScheduler
import io.github.k8242.threadlog.feature.autotag.OfflineRuleAutoTagger
import io.github.k8242.threadlog.feature.autotag.WorkManagerAutoTagScheduler
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
