package com.threadlog.feature.autotag

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.threadlog.core.model.contract.AutoTagScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WorkManager-backed [AutoTagScheduler] that enqueues a unique per-entry [AutoTagWorker] job.
 */
@Singleton
class WorkManagerAutoTagScheduler internal constructor(
    private val workManagerProvider: () -> WorkManager,
) : AutoTagScheduler {

    @Inject
    constructor(@ApplicationContext context: Context) : this(
        workManagerProvider = { WorkManager.getInstance(context) },
    )

    constructor(workManager: WorkManager) : this(
        workManagerProvider = { workManager },
    )

    override fun enqueueAutoTag(entryId: String) {
        val trimmedId = entryId.trim()
        if (trimmedId.isEmpty()) return

        val request = OneTimeWorkRequestBuilder<AutoTagWorker>()
            .setInputData(workDataOf(AutoTagWorker.KEY_ENTRY_ID to trimmedId))
            .build()

        workManagerProvider().enqueueUniqueWork(
            AutoTagWorker.uniqueWorkName(trimmedId),
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }
}
