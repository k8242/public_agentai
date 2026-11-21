// Copyright (c) 2026 k8242
// SPDX-License-Identifier: MIT

package io.github.k8242.threadlog.feature.search

import androidx.paging.PagingData
import io.github.k8242.threadlog.core.model.BackupCadence
import io.github.k8242.threadlog.core.model.CalendarDayCount
import io.github.k8242.threadlog.core.model.CompactEntrySummary
import io.github.k8242.threadlog.core.model.Entry
import io.github.k8242.threadlog.core.model.EntryTag
import io.github.k8242.threadlog.core.model.EntryWithDetails
import io.github.k8242.threadlog.core.model.HybridSearchResult
import io.github.k8242.threadlog.core.model.SearchHit
import io.github.k8242.threadlog.core.model.Tag
import io.github.k8242.threadlog.core.model.TagRootCategory
import io.github.k8242.threadlog.core.model.TagState
import io.github.k8242.threadlog.core.model.UserSettings
import io.github.k8242.threadlog.core.model.WeekStart
import io.github.k8242.threadlog.core.model.contract.EntryRepository
import io.github.k8242.threadlog.core.model.contract.PendingAttachment
import io.github.k8242.threadlog.core.model.contract.SearchRepository
import io.github.k8242.threadlog.core.model.contract.SettingsRepository
import io.github.k8242.threadlog.core.model.contract.TagRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map

internal data class RecordedSearchCall(
    val query: String,
    val includeHiddenAutoTags: Boolean,
)

internal class FakeSearchRepository(
    var handler: suspend (query: String, includeHiddenAutoTags: Boolean) -> HybridSearchResult = { query, includeHidden ->
        HybridSearchResult(
            query = query,
            includeHiddenAutoTags = includeHidden,
            cutoffEpochMs = 0L,
            tierARecent = emptyList(),
            tierBOlder = emptyList(),
        )
    },
) : SearchRepository {

    val recordedCalls = mutableListOf<RecordedSearchCall>()

    override suspend fun search(
        query: String,
        includeHiddenAutoTags: Boolean,
        nowMs: Long,
    ): HybridSearchResult {
        recordedCalls += RecordedSearchCall(query = query, includeHiddenAutoTags = includeHiddenAutoTags)
        return handler(query, includeHiddenAutoTags)
    }
}

internal class FakeTagRepository(
    initialTags: List<Tag> = emptyList(),
    private val onTagStateChanged: ((entryId: String, tagId: String, state: TagState) -> Unit)? = null,
) : TagRepository {

    val tagsFlow = MutableStateFlow(initialTags)
    val promotedCalls = mutableListOf<Pair<String, String>>()
    val rejectedCalls = mutableListOf<Pair<String, String>>()

    override fun observeAllTags(): Flow<List<Tag>> = tagsFlow

    override suspend fun getAllTags(): List<Tag> = tagsFlow.value

    override suspend fun createOrGetTagByPath(fullPath: String, aliases: List<String>): Tag {
        error("Not used in search tests")
    }

    override suspend fun renameOrMoveTag(tagId: String, newFullPath: String) = Unit

    override suspend fun updateAliases(tagId: String, aliases: List<String>) = Unit

    override suspend fun deleteTag(tagId: String) = Unit

    override suspend fun setUserTag(entryId: String, tagId: String, attached: Boolean, nowMs: Long) = Unit

    override suspend fun applyAutoTags(entryId: String, suggestions: List<Pair<String, Float>>, nowMs: Long) = Unit

    override suspend fun promoteAutoTag(entryId: String, tagId: String, nowMs: Long) {
        promotedCalls += entryId to tagId
        onTagStateChanged?.invoke(entryId, tagId, TagState.AUTO_PROMOTED)
    }

    override suspend fun rejectAutoTag(entryId: String, tagId: String, nowMs: Long) {
        rejectedCalls += entryId to tagId
        onTagStateChanged?.invoke(entryId, tagId, TagState.AUTO_REJECTED)
    }
}

internal class FakeEntryRepository(
    initialEntries: List<EntryWithDetails> = emptyList(),
) : EntryRepository {

    val entriesFlow = MutableStateFlow(initialEntries)
    val observedRanges = mutableListOf<Pair<Long, Long>>()

    fun updateTagState(entryId: String, tagId: String, newState: TagState) {
        entriesFlow.value = entriesFlow.value.map { detail ->
            if (detail.entry.id != entryId) {
                detail
            } else {
                detail.copy(
                    tags = detail.tags.map { entryTag ->
                        if (entryTag.tagId == tagId) entryTag.copy(state = newState) else entryTag
                    },
                )
            }
        }
    }

    override fun observePagedEntries(): Flow<PagingData<EntryWithDetails>> = emptyFlow()

    override fun observeEntry(entryId: String): Flow<EntryWithDetails?> =
        entriesFlow.map { list -> list.firstOrNull { it.entry.id == entryId } }

    override suspend fun getEntry(entryId: String): EntryWithDetails? =
        entriesFlow.value.firstOrNull { it.entry.id == entryId }

    override suspend fun createEntry(
        bodyRaw: String,
        userTagIds: List<String>,
        replyToId: String?,
        pendingAttachments: List<PendingAttachment>,
        nowMs: Long,
        zoneId: String?,
    ): EntryWithDetails = error("Not used in search tests")

    override suspend fun editEntry(entryId: String, newBodyRaw: String, nowMs: Long): EntryWithDetails =
        error("Not used in search tests")

    override suspend fun softDeleteEntry(entryId: String, nowMs: Long) = Unit

    override suspend fun undoSoftDelete(entryId: String) = Unit

    override fun observeCompactSummaries(): Flow<List<CompactEntrySummary>> = emptyFlow()

    override fun observeCalendarDayCounts(
        yearMonth: String,
        filterTagPathPrefix: String?,
    ): Flow<List<CalendarDayCount>> = emptyFlow()

    override fun observeEntriesForDate(
        localDate: String,
        filterTagPathPrefix: String?,
    ): Flow<List<EntryWithDetails>> = emptyFlow()

    override fun observeContextWindow(startMs: Long, endMs: Long): Flow<List<EntryWithDetails>> {
        observedRanges += startMs to endMs
        return entriesFlow.map { list ->
            list.filter { it.entry.createdAtMs in startMs..endMs }
        }
    }

    override fun observeEntriesWithPendingAutoTags(): Flow<List<EntryWithDetails>> = emptyFlow()
}

internal class FakeSettingsRepository(
    initialSettings: UserSettings = UserSettings(),
) : SettingsRepository {

    val settingsFlow = MutableStateFlow(initialSettings)

    override val settings: Flow<UserSettings> = settingsFlow

    override suspend fun getSettings(): UserSettings = settingsFlow.value

    override suspend fun setWeekStart(weekStart: WeekStart) {
        settingsFlow.value = settingsFlow.value.copy(weekStart = weekStart)
    }

    override suspend fun setOfflineOnlyLinks(enabled: Boolean) {
        settingsFlow.value = settingsFlow.value.copy(offlineOnlyLinks = enabled)
    }

    override suspend fun setShowHiddenAutoTagsInChat(show: Boolean) {
        settingsFlow.value = settingsFlow.value.copy(showHiddenAutoTagsInChat = show)
    }

    override suspend fun setScheduledBackupCadence(cadence: BackupCadence) {
        settingsFlow.value = settingsFlow.value.copy(scheduledBackupCadence = cadence)
    }

    override suspend fun updateSyncLinks(
        spreadsheetId: String?,
        mediaFolderId: String?,
        lastExportIso: String?,
    ) = Unit
}

internal fun sampleTag(
    id: String,
    fullPath: String,
    leafName: String = fullPath.substringAfterLast('/'),
    rootCategory: TagRootCategory = TagRootCategory.fromPath(fullPath),
): Tag = Tag(
    id = id,
    fullPath = fullPath,
    leafName = leafName,
    rootCategory = rootCategory,
    colorToken = rootCategory.key,
)

internal fun sampleSearchHit(
    entryId: String,
    createdAtMs: Long,
    localDate: String,
    snippetMarked: String,
    zoneId: String = "UTC",
    visibleTags: List<Tag> = emptyList(),
    hiddenMatchedTags: List<Tag> = emptyList(),
    matchedViaHiddenAutoTag: Boolean = hiddenMatchedTags.isNotEmpty(),
): SearchHit = SearchHit(
    entryId = entryId,
    createdAtMs = createdAtMs,
    zoneId = zoneId,
    localDate = localDate,
    snippetMarked = snippetMarked,
    visibleTags = visibleTags,
    hiddenMatchedTags = hiddenMatchedTags,
    matchedViaHiddenAutoTag = matchedViaHiddenAutoTag,
)

internal fun sampleEntryWithDetails(
    id: String,
    createdAtMs: Long,
    localDate: String,
    bodyPlain: String,
    zoneId: String = "UTC",
    tags: List<EntryTag> = emptyList(),
): EntryWithDetails = EntryWithDetails(
    entry = Entry(
        id = id,
        createdAtMs = createdAtMs,
        zoneId = zoneId,
        localDate = localDate,
        isoWeek = "2026-W47",
        bodyRaw = bodyPlain,
        bodyPlain = bodyPlain,
        contentHash = "hash-$id",
    ),
    tags = tags,
    bookmarks = emptyList(),
    attachments = emptyList(),
)
