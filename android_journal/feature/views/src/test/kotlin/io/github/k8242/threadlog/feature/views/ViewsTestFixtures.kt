// Copyright (c) 2026 k8242
// SPDX-License-Identifier: MIT

package io.github.k8242.threadlog.feature.views

import androidx.paging.PagingData
import io.github.k8242.threadlog.core.model.BackupCadence
import io.github.k8242.threadlog.core.model.CalendarDayCount
import io.github.k8242.threadlog.core.model.CompactEntrySummary
import io.github.k8242.threadlog.core.model.Entry
import io.github.k8242.threadlog.core.model.EntryTag
import io.github.k8242.threadlog.core.model.EntryWithDetails
import io.github.k8242.threadlog.core.model.Tag
import io.github.k8242.threadlog.core.model.TagRootCategory
import io.github.k8242.threadlog.core.model.TagState
import io.github.k8242.threadlog.core.model.UserSettings
import io.github.k8242.threadlog.core.model.WeekStart
import io.github.k8242.threadlog.core.model.contract.EntryRepository
import io.github.k8242.threadlog.core.model.contract.PendingAttachment
import io.github.k8242.threadlog.core.model.contract.SettingsRepository
import io.github.k8242.threadlog.core.model.contract.TagRepository
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    val testDispatcher: TestDispatcher = StandardTestDispatcher(),
) : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(testDispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}

internal data class CalendarCountQuery(
    val yearMonth: String,
    val filterTagPathPrefix: String?,
)

internal data class DateEntriesQuery(
    val localDate: String,
    val filterTagPathPrefix: String?,
)

internal class FakeEntryRepository : EntryRepository {

    val compactSummariesFlow = MutableStateFlow<List<CompactEntrySummary>>(emptyList())
    val entriesByIdFlow = MutableStateFlow<Map<String, EntryWithDetails>>(emptyMap())

    val getEntryCalls = mutableListOf<String>()
    val calendarCountQueries = mutableListOf<CalendarCountQuery>()
    val dateEntryQueries = mutableListOf<DateEntriesQuery>()

    fun setEntries(entries: List<EntryWithDetails>) {
        entriesByIdFlow.value = entries.associateBy { it.entry.id }
    }

    override fun observePagedEntries(): Flow<PagingData<EntryWithDetails>> = emptyFlow()

    override fun observeEntry(entryId: String): Flow<EntryWithDetails?> =
        entriesByIdFlow.map { it[entryId] }

    override suspend fun getEntry(entryId: String): EntryWithDetails? {
        getEntryCalls += entryId
        return entriesByIdFlow.value[entryId]
    }

    override suspend fun createEntry(
        bodyRaw: String,
        userTagIds: List<String>,
        replyToId: String?,
        pendingAttachments: List<PendingAttachment>,
        nowMs: Long,
        zoneId: String?,
    ): EntryWithDetails = throw UnsupportedOperationException()

    override suspend fun editEntry(
        entryId: String,
        newBodyRaw: String,
        nowMs: Long,
    ): EntryWithDetails = throw UnsupportedOperationException()

    override suspend fun softDeleteEntry(entryId: String, nowMs: Long) =
        throw UnsupportedOperationException()

    override suspend fun undoSoftDelete(entryId: String) =
        throw UnsupportedOperationException()

    override fun observeCompactSummaries(): Flow<List<CompactEntrySummary>> =
        compactSummariesFlow

    override fun observeCalendarDayCounts(
        yearMonth: String,
        filterTagPathPrefix: String?,
    ): Flow<List<CalendarDayCount>> {
        calendarCountQueries += CalendarCountQuery(yearMonth, filterTagPathPrefix)
        return entriesByIdFlow.map { map ->
            map.values
                .asSequence()
                .filter { !it.entry.isDeleted }
                .filter { it.entry.localDate.startsWith(yearMonth) }
                .filter { matchesTagPrefix(it, filterTagPathPrefix) }
                .groupingBy { it.entry.localDate }
                .eachCount()
                .entries
                .sortedBy { it.key }
                .map { (date, count) -> CalendarDayCount(localDate = date, entryCount = count) }
        }
    }

    override fun observeEntriesForDate(
        localDate: String,
        filterTagPathPrefix: String?,
    ): Flow<List<EntryWithDetails>> {
        dateEntryQueries += DateEntriesQuery(localDate, filterTagPathPrefix)
        return entriesByIdFlow.map { map ->
            map.values
                .asSequence()
                .filter { !it.entry.isDeleted }
                .filter { it.entry.localDate == localDate }
                .filter { matchesTagPrefix(it, filterTagPathPrefix) }
                .sortedWith(compareBy<EntryWithDetails> { it.entry.createdAtMs }.thenBy { it.entry.id })
                .toList()
        }
    }

    override fun observeContextWindow(startMs: Long, endMs: Long): Flow<List<EntryWithDetails>> =
        emptyFlow()

    override fun observeEntriesWithPendingAutoTags(): Flow<List<EntryWithDetails>> =
        emptyFlow()

    private fun matchesTagPrefix(details: EntryWithDetails, prefix: String?): Boolean {
        if (prefix.isNullOrBlank()) return true
        val normalized = prefix.trim().trim('/').lowercase()
        val subtreePrefix = "$normalized/"
        return details.visibleTags.any { entryTag ->
            val path = entryTag.tag.fullPath.trim().trim('/').lowercase()
            path == normalized || path.startsWith(subtreePrefix)
        }
    }
}

internal class FakeTagRepository(
    initialTags: List<Tag> = emptyList(),
) : TagRepository {
    val tagsFlow = MutableStateFlow(initialTags)

    override fun observeAllTags(): Flow<List<Tag>> = tagsFlow
    override suspend fun getAllTags(): List<Tag> = tagsFlow.value
    override suspend fun createOrGetTagByPath(fullPath: String, aliases: List<String>): Tag =
        throw UnsupportedOperationException()

    override suspend fun renameOrMoveTag(tagId: String, newFullPath: String) =
        throw UnsupportedOperationException()

    override suspend fun updateAliases(tagId: String, aliases: List<String>) =
        throw UnsupportedOperationException()

    override suspend fun deleteTag(tagId: String) = throw UnsupportedOperationException()
    override suspend fun setUserTag(entryId: String, tagId: String, attached: Boolean, nowMs: Long) =
        throw UnsupportedOperationException()

    override suspend fun applyAutoTags(
        entryId: String,
        suggestions: List<Pair<String, Float>>,
        nowMs: Long,
    ) = throw UnsupportedOperationException()

    override suspend fun promoteAutoTag(entryId: String, tagId: String, nowMs: Long) =
        throw UnsupportedOperationException()

    override suspend fun rejectAutoTag(entryId: String, tagId: String, nowMs: Long) =
        throw UnsupportedOperationException()
}

internal class FakeSettingsRepository(
    initial: UserSettings = UserSettings(),
) : SettingsRepository {
    val settingsFlow = MutableStateFlow(initial)
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
    ) {
        settingsFlow.value = settingsFlow.value.copy(
            linkedSpreadsheetId = spreadsheetId,
            linkedMediaFolderId = mediaFolderId,
            lastExportIso = lastExportIso,
        )
    }
}

internal object ViewsTestData {

    fun epochMs(
        localDate: String,
        hour: Int,
        minute: Int,
        zoneId: String = "UTC",
    ): Long = LocalDate.parse(localDate)
        .atTime(LocalTime.of(hour, minute))
        .atZone(ZoneId.of(zoneId))
        .toInstant()
        .toEpochMilli()

    fun tag(
        id: String,
        fullPath: String,
        leafName: String = fullPath.substringAfterLast('/'),
    ): Tag {
        val root = TagRootCategory.fromPath(fullPath)
        return Tag(
            id = id,
            fullPath = fullPath,
            leafName = leafName,
            rootCategory = root,
            colorToken = root.key,
        )
    }

    fun entryDetails(
        id: String,
        localDate: String,
        hour: Int = 9,
        minute: Int = 0,
        zoneId: String = "UTC",
        bodyPlain: String = "Entry $id",
        tags: List<Tag> = emptyList(),
        tagState: TagState = TagState.USER,
    ): EntryWithDetails {
        val createdAt = epochMs(localDate, hour, minute, zoneId)
        val entry = Entry(
            id = id,
            createdAtMs = createdAt,
            zoneId = zoneId,
            localDate = localDate,
            isoWeek = "2026-W47",
            bodyRaw = bodyPlain,
            bodyPlain = bodyPlain,
            contentHash = "hash-$id",
        )
        return EntryWithDetails(
            entry = entry,
            tags = tags.map { t ->
                EntryTag(
                    entryId = id,
                    tagId = t.id,
                    tag = t,
                    state = tagState,
                    updatedAtMs = createdAt,
                )
            },
            bookmarks = emptyList(),
            attachments = emptyList(),
        )
    }

    fun compactSummary(
        id: String,
        localDate: String,
        hour: Int = 9,
        minute: Int = 0,
        zoneId: String = "UTC",
        preview140: String = "Preview $id",
        hasAttachments: Boolean = false,
        hasBookmarks: Boolean = false,
        hasCodeBlock: Boolean = false,
    ): CompactEntrySummary = CompactEntrySummary(
        id = id,
        createdAtMs = epochMs(localDate, hour, minute, zoneId),
        zoneId = zoneId,
        localDate = localDate,
        preview140 = preview140,
        hasAttachments = hasAttachments,
        hasBookmarks = hasBookmarks,
        hasCodeBlock = hasCodeBlock,
    )
}
