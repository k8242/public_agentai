package io.github.k8242.threadlog.core.media.di

import io.github.k8242.threadlog.core.media.AndroidMediaCompressor
import io.github.k8242.threadlog.core.media.FileMediaStorage
import io.github.k8242.threadlog.core.model.contract.MediaCompressor
import io.github.k8242.threadlog.core.model.contract.MediaStorage
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
