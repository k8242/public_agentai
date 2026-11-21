package io.github.k8242.threadlog.feature.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import io.github.k8242.threadlog.core.model.BackupCadence
import java.util.concurrent.TimeUnit
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WorkManagerBackupSchedulerTest {

    private lateinit var workManager: WorkManager
    private lateinit var scheduler: WorkManagerBackupScheduler

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val config = Configuration.Builder()
            .setExecutor(SynchronousExecutor())
            .setTaskExecutor(SynchronousExecutor())
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        workManager = WorkManager.getInstance(context)
        scheduler = WorkManagerBackupScheduler(workManager)
    }

    @Test
    fun updateSchedule_daily_enqueuesConnected24HourPeriodicWork() {
        scheduler.updateSchedule(BackupCadence.DAILY)

        val infos = workManager
            .getWorkInfosForUniqueWork(WorkManagerBackupScheduler.UNIQUE_WORK_NAME)
            .get()

        assertThat(infos).hasSize(1)
        val info = infos.single()
        assertThat(info.state).isEqualTo(WorkInfo.State.ENQUEUED)
        assertThat(info.constraints.requiredNetworkType).isEqualTo(NetworkType.CONNECTED)
        assertThat(info.periodicityInfo?.repeatIntervalMillis)
            .isEqualTo(TimeUnit.HOURS.toMillis(WorkManagerBackupScheduler.DAILY_INTERVAL_HOURS))
    }

    @Test
    fun updateSchedule_weekly_updatesUniquePeriodicWorkTo7Days() {
        scheduler.updateSchedule(BackupCadence.DAILY)
        scheduler.updateSchedule(BackupCadence.WEEKLY)

        val infos = workManager
            .getWorkInfosForUniqueWork(WorkManagerBackupScheduler.UNIQUE_WORK_NAME)
            .get()

        assertThat(infos).hasSize(1)
        val info = infos.single()
        assertThat(info.state).isEqualTo(WorkInfo.State.ENQUEUED)
        assertThat(info.constraints.requiredNetworkType).isEqualTo(NetworkType.CONNECTED)
        assertThat(info.periodicityInfo?.repeatIntervalMillis)
            .isEqualTo(TimeUnit.HOURS.toMillis(WorkManagerBackupScheduler.WEEKLY_INTERVAL_HOURS))
    }

    @Test
    fun updateSchedule_off_cancelsUniquePeriodicWork() {
        scheduler.updateSchedule(BackupCadence.DAILY)
        scheduler.updateSchedule(BackupCadence.OFF)

        val infos = workManager
            .getWorkInfosForUniqueWork(WorkManagerBackupScheduler.UNIQUE_WORK_NAME)
            .get()

        assertThat(infos).hasSize(1)
        assertThat(infos.single().state).isEqualTo(WorkInfo.State.CANCELLED)
    }
}
