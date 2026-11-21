// Copyright (c) 2026 k8242
// SPDX-License-Identifier: MIT

package io.github.k8242.threadlog.app.di

import io.github.k8242.threadlog.app.BuildConfig
import io.github.k8242.threadlog.feature.backup.cloud.GoogleOAuthConfig
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
