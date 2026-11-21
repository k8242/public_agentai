package com.threadlog.feature.backup

import com.threadlog.core.model.contract.BackupScheduler
import com.threadlog.core.model.contract.GoogleAuthProvider
import com.threadlog.feature.backup.cloud.GoogleDriveClient
import com.threadlog.feature.backup.cloud.GoogleSheetsClient
import com.threadlog.feature.backup.cloud.PlayServicesGoogleAuthProvider
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class BackupOkHttpClient

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class BackupJson

@Module
@InstallIn(SingletonComponent::class)
object BackupDiModule {

    @Provides
    @Singleton
    @BackupOkHttpClient
    fun provideBackupOkHttpClient(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

    @Provides
    @Singleton
    @BackupJson
    fun provideBackupJson(): Json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    @Provides
    @Singleton
    fun provideGoogleSheetsClient(
        @BackupOkHttpClient okHttpClient: OkHttpClient,
        @BackupJson json: Json,
    ): GoogleSheetsClient = GoogleSheetsClient(
        okHttpClient = okHttpClient,
        json = json,
    )

    @Provides
    @Singleton
    fun provideGoogleDriveClient(
        @BackupOkHttpClient okHttpClient: OkHttpClient,
        @BackupJson json: Json,
    ): GoogleDriveClient = GoogleDriveClient(
        okHttpClient = okHttpClient,
        json = json,
    )
}

@Module
@InstallIn(SingletonComponent::class)
abstract class BackupBindingsModule {

    @Binds
    @Singleton
    abstract fun bindBackupScheduler(impl: WorkManagerBackupScheduler): BackupScheduler

    @Binds
    @Singleton
    abstract fun bindGoogleAuthProvider(impl: PlayServicesGoogleAuthProvider): GoogleAuthProvider
}
