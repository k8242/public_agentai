package com.threadlog.feature.chat.bookmark

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.threadlog.core.model.contract.BookmarkTitleScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Enqueues unique WorkManager jobs to resolve bookmark `<title>` metadata when network is available.
 */
@Singleton
class WorkManagerBookmarkTitleScheduler private constructor(
    private val workManagerProvider: () -> WorkManager,
) : BookmarkTitleScheduler {

    @Inject
    constructor(@ApplicationContext context: Context) : this({ WorkManager.getInstance(context) })

    /** Secondary constructor for unit testing with a fake/test [WorkManager]. */
    constructor(workManager: WorkManager) : this({ workManager })

    override fun enqueueTitleFetch(entryId: String) {
        if (entryId.isBlank()) return

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<BookmarkTitleWorker>()
            .setInputData(workDataOf(BookmarkTitleWorker.KEY_ENTRY_ID to entryId))
            .setConstraints(constraints)
            .addTag(WORK_TAG)
            .build()

        workManagerProvider().enqueueUniqueWork(
            uniqueWorkName(entryId),
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    companion object {
        const val WORK_TAG = "bookmark_title_fetch"
        const val UNIQUE_WORK_PREFIX = "bookmark_title_"

        fun uniqueWorkName(entryId: String): String = "$UNIQUE_WORK_PREFIX$entryId"
    }
}
