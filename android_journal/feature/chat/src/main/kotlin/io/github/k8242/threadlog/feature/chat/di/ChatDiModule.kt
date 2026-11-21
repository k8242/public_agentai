package io.github.k8242.threadlog.feature.chat.di

import io.github.k8242.threadlog.core.model.contract.BookmarkTitleScheduler
import io.github.k8242.threadlog.feature.chat.bookmark.WorkManagerBookmarkTitleScheduler
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Qualifier for the OkHttpClient used exclusively by `:feature:chat` bookmark title fetching
 * so it never collides with OkHttpClient bindings in other feature modules (e.g., `:feature:backup`).
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ChatOkHttpClient

@Module
@InstallIn(SingletonComponent::class)
abstract class ChatDiModule {

    @Binds
    @Singleton
    abstract fun bindBookmarkTitleScheduler(
        impl: WorkManagerBookmarkTitleScheduler,
    ): BookmarkTitleScheduler

    companion object {
        private const val CONNECT_TIMEOUT_SECONDS = 6L
        private const val IO_TIMEOUT_SECONDS = 8L

        @Provides
        @Singleton
        @ChatOkHttpClient
        fun provideChatOkHttpClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(IO_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(IO_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .callTimeout(IO_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
    }
}
