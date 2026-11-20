package com.threadlog.core.media.di

import com.threadlog.core.media.AndroidMediaCompressor
import com.threadlog.core.media.FileMediaStorage
import com.threadlog.core.model.contract.MediaCompressor
import com.threadlog.core.model.contract.MediaStorage
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class MediaDiModule {

    @Binds
    @Singleton
    abstract fun bindMediaStorage(impl: FileMediaStorage): MediaStorage

    @Binds
    @Singleton
    abstract fun bindMediaCompressor(impl: AndroidMediaCompressor): MediaCompressor
}
