package com.threadlog.feature.autotag

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WorkManagerAutoTagSchedulerTest {

    private lateinit var context: Context
    private lateinit var workManager: WorkManager
    private lateinit var capturedEntryIds: MutableList<String?>

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        capturedEntryIds = mutableListOf()

        val entryRepo = FakeEntryRepository()
        val tagRepo = FakeTagRepository()
        val ruleRepo = FakeAutoTagRuleRepository()
        val provider = FakeAutoTagProvider { _, _, _, _ -> emptyList() }

        val capturingFactory = object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters,
            ): ListenableWorker? {
                if (workerClassName != AutoTagWorker::class.java.name) return null
                capturedEntryIds += workerParameters.inputData.getString(AutoTagWorker.KEY_ENTRY_ID)
                return AutoTagWorker(
                    appContext = appContext,
                    params = workerParameters,
                    entryRepository = entryRepo,
                    tagRepository = tagRepo,
                    autoTagRuleRepository = ruleRepo,
                    autoTagProvider = provider,
                )
            }
        }

        val config = Configuration.Builder()
            .setExecutor(SynchronousExecutor())
            .setWorkerFactory(capturingFactory)
            .build()

        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        workManager = WorkManager.getInstance(context)
    }

    @Test
    fun enqueueAutoTag_enqueuesUniqueWorkWithEntryIdInputData() {
        val scheduler = WorkManagerAutoTagScheduler(workManager)

        scheduler.enqueueAutoTag("entry-123")

        val workInfos = workManager
            .getWorkInfosForUniqueWork(AutoTagWorker.uniqueWorkName("entry-123"))
            .get()

        assertThat(workInfos).hasSize(1)
        assertThat(workInfos.single().state).isAnyOf(
            WorkInfo.State.ENQUEUED,
            WorkInfo.State.RUNNING,
            WorkInfo.State.SUCCEEDED,
        )
        assertThat(capturedEntryIds).containsExactly("entry-123")
    }

    @Test
    fun enqueueAutoTag_replacesExistingUniqueWorkForSameEntryId() {
        val scheduler = WorkManagerAutoTagScheduler(workManager)

        scheduler.enqueueAutoTag("entry-dup")
        val firstId = workManager
            .getWorkInfosForUniqueWork("autotag_entry-dup")
            .get()
            .single()
            .id

        scheduler.enqueueAutoTag("entry-dup")
        val workInfosAfterReplace = workManager
            .getWorkInfosForUniqueWork("autotag_entry-dup")
            .get()

        assertThat(workInfosAfterReplace).hasSize(1)
        assertThat(workInfosAfterReplace.single().id).isNotEqualTo(firstId)
    }

    @Test
    fun enqueueAutoTag_ignoresBlankEntryId() {
        val scheduler = WorkManagerAutoTagScheduler(workManager)

        scheduler.enqueueAutoTag("   ")

        assertThat(capturedEntryIds).isEmpty()
    }
}
