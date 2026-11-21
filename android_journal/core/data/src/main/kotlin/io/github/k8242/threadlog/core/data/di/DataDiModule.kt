// Copyright (c) 2026 k8242
// SPDX-License-Identifier: MIT

package io.github.k8242.threadlog.core.data.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.k8242.threadlog.core.data.local.ThreadLogDatabase
import io.github.k8242.threadlog.core.data.local.ThreadLogDatabaseCallback
import io.github.k8242.threadlog.core.data.local.dao.AttachmentDao
import io.github.k8242.threadlog.core.data.local.dao.AutoTagRuleDao
import io.github.k8242.threadlog.core.data.local.dao.BookmarkDao
import io.github.k8242.threadlog.core.data.local.dao.EntryDao
import io.github.k8242.threadlog.core.data.local.dao.SyncStateDao
import io.github.k8242.threadlog.core.data.local.dao.TagDao
import io.github.k8242.threadlog.core.data.repository.DataStoreSettingsRepository
import io.github.k8242.threadlog.core.data.repository.DefaultAutoTagRuleRepository
import io.github.k8242.threadlog.core.data.repository.DefaultBackupDataGateway
import io.github.k8242.threadlog.core.data.repository.DefaultBookmarkRepository
import io.github.k8242.threadlog.core.data.repository.DefaultEntryRepository
import io.github.k8242.threadlog.core.data.repository.DefaultSearchRepository
import io.github.k8242.threadlog.core.data.repository.DefaultTagRepository
import io.github.k8242.threadlog.core.model.contract.AutoTagRuleRepository
import io.github.k8242.threadlog.core.model.contract.BackupDataGateway
import io.github.k8242.threadlog.core.model.contract.BookmarkRepository
import io.github.k8242.threadlog.core.model.contract.DateWeekCalculator
import io.github.k8242.threadlog.core.model.contract.EntryRepository
import io.github.k8242.threadlog.core.model.contract.LeafDisambiguator
import io.github.k8242.threadlog.core.model.contract.MessageParser
import io.github.k8242.threadlog.core.model.contract.SearchRepository
import io.github.k8242.threadlog.core.model.contract.SettingsRepository
import io.github.k8242.threadlog.core.model.contract.TagRepository
import io.github.k8242.threadlog.core.parser.DefaultDateWeekCalculator
import io.github.k8242.threadlog.core.parser.DefaultLeafDisambiguator
import io.github.k8242.threadlog.core.parser.DefaultMessageParser
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DataDiModule {

    @Binds
    @Singleton
    abstract fun bindEntryRepository(impl: DefaultEntryRepository): EntryRepository

    @Binds
    @Singleton
    abstract fun bindTagRepository(impl: DefaultTagRepository): TagRepository

    @Binds
    @Singleton
    abstract fun bindBookmarkRepository(impl: DefaultBookmarkRepository): BookmarkRepository

    @Binds
    @Singleton
    abstract fun bindSearchRepository(impl: DefaultSearchRepository): SearchRepository

    @Binds
    @Singleton
    abstract fun bindAutoTagRuleRepository(impl: DefaultAutoTagRuleRepository): AutoTagRuleRepository

    @Binds
    @Singleton
    abstract fun bindSettingsRepository(impl: DataStoreSettingsRepository): SettingsRepository

    @Binds
    @Singleton
    abstract fun bindBackupDataGateway(impl: DefaultBackupDataGateway): BackupDataGateway

    companion object {

        @Provides
        @Singleton
        fun provideThreadLogDatabase(
            @ApplicationContext context: Context,
        ): ThreadLogDatabase =
            Room.databaseBuilder(
                context = context,
                klass = ThreadLogDatabase::class.java,
                name = ThreadLogDatabase.DATABASE_NAME,
            )
                .setDriver(BundledSQLiteDriver())
                .addCallback(ThreadLogDatabaseCallback())
                .build()

        @Provides
        fun provideEntryDao(database: ThreadLogDatabase): EntryDao = database.entryDao()

        @Provides
        fun provideTagDao(database: ThreadLogDatabase): TagDao = database.tagDao()

        @Provides
        fun provideBookmarkDao(database: ThreadLogDatabase): BookmarkDao = database.bookmarkDao()

        @Provides
        fun provideAttachmentDao(database: ThreadLogDatabase): AttachmentDao = database.attachmentDao()

        @Provides
        fun provideAutoTagRuleDao(database: ThreadLogDatabase): AutoTagRuleDao = database.autoTagRuleDao()

        @Provides
        fun provideSyncStateDao(database: ThreadLogDatabase): SyncStateDao = database.syncStateDao()

        @Provides
        @Singleton
        fun providePreferencesDataStore(
            @ApplicationContext context: Context,
        ): DataStore<Preferences> =
            PreferenceDataStoreFactory.create(
                produceFile = {
                    context.preferencesDataStoreFile(DataStoreSettingsRepository.SETTINGS_DATASTORE_NAME)
                },
            )

        @Provides
        @Singleton
        fun provideMessageParser(): MessageParser = DefaultMessageParser()

        @Provides
        @Singleton
        fun provideLeafDisambiguator(): LeafDisambiguator = DefaultLeafDisambiguator()

        @Provides
        @Singleton
        fun provideDateWeekCalculator(): DateWeekCalculator = DefaultDateWeekCalculator()
    }
}
