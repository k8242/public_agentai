package com.threadlog.feature.autotag

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.threadlog.core.model.TagState
import com.threadlog.core.model.contract.AutoTagProvider
import com.threadlog.core.model.contract.AutoTagRuleRepository
import com.threadlog.core.model.contract.EntryRepository
import com.threadlog.core.model.contract.TagRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException

/**
 * Background [CoroutineWorker] that evaluates offline auto-tag rules and existing tags for a
 * single entry and persists any new suggestions via [TagRepository.applyAutoTags].
 */
@HiltWorker
class AutoTagWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val entryRepository: EntryRepository,
    private val tagRepository: TagRepository,
    private val autoTagRuleRepository: AutoTagRuleRepository,
    private val autoTagProvider: AutoTagProvider,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val entryId = inputData.getString(KEY_ENTRY_ID)?.trim()
        if (entryId.isNullOrEmpty()) {
            return Result.failure()
        }

        return try {
            val entryWithDetails = entryRepository.getEntry(entryId)
            if (entryWithDetails == null || entryWithDetails.entry.isDeleted) {
                return Result.success()
            }

            val allTags = tagRepository.getAllTags()
            val rules = autoTagRuleRepository.getEnabledRules()

            val suggestions = autoTagProvider.suggestTags(
                entry = entryWithDetails.entry,
                bookmarks = entryWithDetails.bookmarks,
                allTags = allTags,
                rules = rules,
            )

            // Defense in depth: exclude tags already decided by the user (attached, promoted, or rejected).
            val excludedCanonicalPaths = entryWithDetails.tags
                .asSequence()
                .filter { it.state in EXCLUDED_TAG_STATES }
                .map { TagPathNormalizer.canonicalKey(it.tag.fullPath) }
                .filter { it.isNotEmpty() }
                .toSet()

            val filteredPairs = suggestions
                .asSequence()
                .filter { suggestion ->
                    val key = TagPathNormalizer.canonicalKey(suggestion.tagPath)
                    key.isNotEmpty() && key !in excludedCanonicalPaths
                }
                .map { it.tagPath to it.confidence }
                .toList()

            if (filteredPairs.isNotEmpty()) {
                tagRepository.applyAutoTags(entryId, filteredPairs)
            }

            Result.success()
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            if (runAttemptCount < MAX_RETRY_ATTEMPTS) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }

    companion object {
        const val KEY_ENTRY_ID: String = "entry_id"
        const val MAX_RETRY_ATTEMPTS: Int = 3

        private val EXCLUDED_TAG_STATES: Set<TagState> = setOf(
            TagState.USER,
            TagState.AUTO_PROMOTED,
            TagState.AUTO_REJECTED,
        )

        fun uniqueWorkName(entryId: String): String = "autotag_$entryId"
    }
}
