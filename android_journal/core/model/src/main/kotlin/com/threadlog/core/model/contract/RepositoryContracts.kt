package com.threadlog.core.model.contract

import androidx.paging.PagingData
import com.threadlog.core.model.AutoTagRule
import com.threadlog.core.model.BackupCadence
import com.threadlog.core.model.Bookmark
import com.threadlog.core.model.CalendarDayCount
import com.threadlog.core.model.CompactEntrySummary
import com.threadlog.core.model.EntryWithDetails
import com.threadlog.core.model.HybridSearchResult
import com.threadlog.core.model.Tag
import com.threadlog.core.model.UserSettings
import com.threadlog.core.model.WeekStart
import kotlinx.coroutines.flow.Flow

interface EntryRepository {
    fun observePagedEntries(): Flow<PagingData<EntryWithDetails>>
    fun observeEntry(entryId: String): Flow<EntryWithDetails?>
    suspend fun getEntry(entryId: String): EntryWithDetails?
    suspend fun createEntry(
        bodyRaw: String,
        userTagIds: List<String>,
        replyToId: String? = null,
        pendingAttachments: List<PendingAttachment> = emptyList(),
        nowMs: Long = System.currentTimeMillis(),
        zoneId: String? = null,
    ): EntryWithDetails
    suspend fun editEntry(
        entryId: String,
        newBodyRaw: String,
        nowMs: Long = System.currentTimeMillis(),
    ): EntryWithDetails
    suspend fun softDeleteEntry(entryId: String, nowMs: Long = System.currentTimeMillis())
    suspend fun undoSoftDelete(entryId: String)
    fun observeCompactSummaries(): Flow<List<CompactEntrySummary>>
    fun observeCalendarDayCounts(yearMonth: String, filterTagPathPrefix: String? = null): Flow<List<CalendarDayCount>>
    fun observeEntriesForDate(localDate: String, filterTagPathPrefix: String? = null): Flow<List<EntryWithDetails>>
    fun observeContextWindow(startMs: Long, endMs: Long): Flow<List<EntryWithDetails>>
    fun observeEntriesWithPendingAutoTags(): Flow<List<EntryWithDetails>>
}

interface TagRepository {
    fun observeAllTags(): Flow<List<Tag>>
    suspend fun getAllTags(): List<Tag>
    suspend fun createOrGetTagByPath(fullPath: String, aliases: List<String> = emptyList()): Tag
    suspend fun renameOrMoveTag(tagId: String, newFullPath: String)
    suspend fun updateAliases(tagId: String, aliases: List<String>)
    suspend fun deleteTag(tagId: String)
    suspend fun setUserTag(entryId: String, tagId: String, attached: Boolean, nowMs: Long = System.currentTimeMillis())
    suspend fun applyAutoTags(entryId: String, suggestions: List<Pair<String, Float>>, nowMs: Long = System.currentTimeMillis())
    suspend fun promoteAutoTag(entryId: String, tagId: String, nowMs: Long = System.currentTimeMillis())
    suspend fun rejectAutoTag(entryId: String, tagId: String, nowMs: Long = System.currentTimeMillis())
}

interface BookmarkRepository {
    suspend fun updateBookmarkTitle(bookmarkId: String, title: String)
    suspend fun setCollapsed(bookmarkId: String, collapsed: Boolean)
    suspend fun getBookmarksMissingTitle(entryId: String): List<Bookmark>
}

interface SearchRepository {
    suspend fun search(
        query: String,
        includeHiddenAutoTags: Boolean = true,
        nowMs: Long = System.currentTimeMillis(),
    ): HybridSearchResult
}

interface AutoTagRuleRepository {
    fun observeRules(): Flow<List<AutoTagRule>>
    suspend fun getEnabledRules(): List<AutoTagRule>
    suspend fun upsertRule(rule: AutoTagRule)
    suspend fun deleteRule(ruleId: String)
}

interface SettingsRepository {
    val settings: Flow<UserSettings>
    suspend fun getSettings(): UserSettings
    suspend fun setWeekStart(weekStart: WeekStart)
    suspend fun setOfflineOnlyLinks(enabled: Boolean)
    suspend fun setShowHiddenAutoTagsInChat(show: Boolean)
    suspend fun setScheduledBackupCadence(cadence: BackupCadence)
    suspend fun updateSyncLinks(spreadsheetId: String?, mediaFolderId: String?, lastExportIso: String?)
}
