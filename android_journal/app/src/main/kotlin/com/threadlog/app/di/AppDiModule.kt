package com.threadlog.app.di

import com.threadlog.app.BuildConfig
import com.threadlog.feature.backup.cloud.GoogleOAuthConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppDiModule {

    @Provides
    @Singleton
    fun provideGoogleOAuthConfig(): GoogleOAuthConfig = object : GoogleOAuthConfig {
        override val webClientId: String = BuildConfig.GOOGLE_WEB_CLIENT_ID
    }
}
