package com.threadlog.feature.backup

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.threadlog.core.model.BackupCadence
import com.threadlog.core.model.contract.BackupScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WorkManager-backed [BackupScheduler] managing the unique periodic work
 * `"threadlog_scheduled_backup"` for [BackupCadence.OFF], [BackupCadence.DAILY], and
 * [BackupCadence.WEEKLY].
 */
@Singleton
class WorkManagerBackupScheduler internal constructor(
    private val workManager: WorkManager,
) : BackupScheduler {

    @Inject
    constructor(@ApplicationContext context: Context) : this(
        workManager = WorkManager.getInstance(context),
    )

    override fun updateSchedule(cadence: BackupCadence) {
        when (cadence) {
            BackupCadence.OFF -> {
                workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
            }

            BackupCadence.DAILY -> {
                enqueuePeriodic(repeatIntervalHours = DAILY_INTERVAL_HOURS)
            }

            BackupCadence.WEEKLY -> {
                enqueuePeriodic(repeatIntervalHours = WEEKLY_INTERVAL_HOURS)
            }
        }
    }

    private fun enqueuePeriodic(repeatIntervalHours: Long) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = PeriodicWorkRequestBuilder<ScheduledBackupWorker>(
            repeatInterval = repeatIntervalHours,
            repeatIntervalTimeUnit = TimeUnit.HOURS,
        )
            .setConstraints(constraints)
            .build()

        workManager.enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    companion object {
        const val UNIQUE_WORK_NAME: String = "threadlog_scheduled_backup"
        const val DAILY_INTERVAL_HOURS: Long = 24L
        const val WEEKLY_INTERVAL_HOURS: Long = 24L * 7L
    }
}
