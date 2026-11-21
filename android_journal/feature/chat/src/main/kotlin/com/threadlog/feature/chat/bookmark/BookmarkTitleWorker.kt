package com.threadlog.feature.chat.bookmark

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.threadlog.core.model.contract.BookmarkRepository
import com.threadlog.core.model.contract.SettingsRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import java.io.IOException

/**
 * Background [CoroutineWorker] that fetches missing HTML page titles for bookmarks attached to an entry.
 *
 * Strictly respects `offlineOnlyLinks` in [SettingsRepository]: when enabled, returns immediately
 * without issuing any network requests.
 */
@HiltWorker
class BookmarkTitleWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val bookmarkRepository: BookmarkRepository,
    private val settingsRepository: SettingsRepository,
    private val titleFetcher: BookmarkTitleFetcher,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val entryId = inputData.getString(KEY_ENTRY_ID)?.trim()
        if (entryId.isNullOrEmpty()) {
            return Result.failure()
        }

        if (settingsRepository.getSettings().offlineOnlyLinks) {
            return Result.success()
        }

        val bookmarks = bookmarkRepository.getBookmarksMissingTitle(entryId)
        if (bookmarks.isEmpty()) {
            return Result.success()
        }

        var hadIoFailure = false
        for (bookmark in bookmarks) {
            try {
                val title = titleFetcher.fetchTitle(bookmark.urlRaw)
                if (!title.isNullOrBlank()) {
                    bookmarkRepository.updateBookmarkTitle(bookmark.id, title)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: IOException) {
                hadIoFailure = true
            } catch (_: Exception) {
                // Non-I/O parsing or unexpected per-URL error should not block remaining bookmarks.
            }
        }

        return if (hadIoFailure && runAttemptCount < MAX_RETRY_ATTEMPTS) {
            Result.retry()
        } else {
            Result.success()
        }
    }

    companion object {
        const val KEY_ENTRY_ID = "entry_id"
        const val MAX_RETRY_ATTEMPTS = 3
    }
}
