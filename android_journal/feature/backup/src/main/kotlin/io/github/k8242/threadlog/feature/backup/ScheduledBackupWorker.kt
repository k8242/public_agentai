// Copyright (c) 2026 k8242
// SPDX-License-Identifier: MIT

package io.github.k8242.threadlog.feature.backup

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.k8242.threadlog.core.model.BackupCadence
import io.github.k8242.threadlog.core.model.contract.GoogleAuthProvider
import io.github.k8242.threadlog.core.model.contract.GoogleAuthState
import io.github.k8242.threadlog.core.model.contract.SettingsRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first

/**
 * Periodic WorkManager worker performing optional scheduled Google Sheets & Drive backups.
 *
 * Exits cleanly with [Result.success] when cloud sync is unconfigured, signed out, or
 * scheduled cadence is [BackupCadence.OFF].
 */
@HiltWorker
class ScheduledBackupWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val googleAuthProvider: GoogleAuthProvider,
    private val settingsRepository: SettingsRepository,
    private val backupCoordinator: BackupCoordinator,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        if (!googleAuthProvider.isConfigured()) {
            return Result.success()
        }

        val settings = settingsRepository.getSettings()
        if (settings.scheduledBackupCadence == BackupCadence.OFF) {
            return Result.success()
        }

        val currentAuth = googleAuthProvider.observeAuthState().first()
        if (currentAuth !is GoogleAuthState.Connected) {
            return Result.success()
        }

        val pushResult = backupCoordinator.pushToGoogleSheets()
        return if (pushResult.isSuccess) {
            Result.success()
        } else if (runAttemptCount < MAX_RETRY_ATTEMPTS) {
            Result.retry()
        } else {
            Result.failure()
        }
    }

    private companion object {
        const val MAX_RETRY_ATTEMPTS = 3
    }
}
