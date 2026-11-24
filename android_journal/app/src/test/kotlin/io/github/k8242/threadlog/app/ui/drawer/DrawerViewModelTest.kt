// Copyright (c) 2026 k8242
// SPDX-License-Identifier: MIT

package io.github.k8242.threadlog.app.ui.drawer

import androidx.paging.PagingData
import com.google.common.truth.Truth.assertThat
import io.github.k8242.threadlog.core.model.AppColorScheme
import io.github.k8242.threadlog.core.model.BackupCadence
import io.github.k8242.threadlog.core.model.CalendarDayCount
import io.github.k8242.threadlog.core.model.CompactEntrySummary
import io.github.k8242.threadlog.core.model.DrawerOverviewSummary
import io.github.k8242.threadlog.core.model.DrawerTagSummary
import io.github.k8242.threadlog.core.model.EntryWithDetails
import io.github.k8242.threadlog.core.model.Tag
import io.github.k8242.threadlog.core.model.TagRootCategory
import io.github.k8242.threadlog.core.model.TaskStatus
import io.github.k8242.threadlog.core.model.ThemeMode
import io.github.k8242.threadlog.core.model.UserSettings
import io.github.k8242.threadlog.core.model.WeekStart
import io.github.k8242.threadlog.core.model.contract.EntryRepository
import io.github.k8242.threadlog.core.model.contract.PendingAttachment
import io.github.k8242.threadlog.core.model.contract.SettingsRepository
import io.github.k8242.threadlog.core.model.contract.TagRepository
import io.github.k8242.threadlog.core.parser.DefaultLeafDisambiguator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DrawerViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun deleteTag_delegatesToTagRepository() = runTest(testDispatcher) {
        val sampleTag = Tag(
            id = "tag-proj-1",
            fullPath = "projects/apollo",
            leafName = "apollo",
            rootCategory = TagRootCategory.PROJECTS,
            colorToken = "projects",
        )
        val tagSummariesFlow = MutableStateFlow(
            listOf(
                DrawerTagSummary(
                    tag = sampleTag,
                    entryCount = 2,
                    openTodoCount = 1,
                    latestPreview = "Kickoff notes",
                    latestCreatedAtMs = 1_763_600_000_000L,
                ),
            ),
        )
        val deletedIds = mutableListOf<String>()

        val fakeEntryRepo = object : EntryRepository {
            override fun observePagedEntries(): Flow<PagingData<EntryWithDetails>> = flowOf(PagingData.empty())
            override fun observeEntry(entryId: String): Flow<EntryWithDetails?> = flowOf(null)
            override suspend fun getEntry(entryId: String): EntryWithDetails? = null
            override suspend fun createEntry(
                bodyRaw: String,
                userTagIds: List<String>,
                replyToId: String?,
                pendingAttachments: List<PendingAttachment>,
                nowMs: Long,
                zoneId: String?,
                taskStatus: TaskStatus,
            ): EntryWithDetails = error("unused")

            override suspend fun editEntry(entryId: String, newBodyRaw: String, nowMs: Long): EntryWithDetails =
                error("unused")

            override suspend fun softDeleteEntry(entryId: String, nowMs: Long) = Unit
            override suspend fun undoSoftDelete(entryId: String) = Unit
            override fun observeCompactSummaries(): Flow<List<CompactEntrySummary>> = flowOf(emptyList())
            override fun observeCalendarDayCounts(
                yearMonth: String,
                filterTagPathPrefix: String?,
            ): Flow<List<CalendarDayCount>> = flowOf(emptyList())

            override fun observeEntriesForDate(
                localDate: String,
                filterTagPathPrefix: String?,
            ): Flow<List<EntryWithDetails>> = flowOf(emptyList())

            override fun observeContextWindow(startMs: Long, endMs: Long): Flow<List<EntryWithDetails>> =
                flowOf(emptyList())

            override fun observeEntriesWithPendingAutoTags(): Flow<List<EntryWithDetails>> = flowOf(emptyList())
            override fun observeDrawerOverview(): Flow<DrawerOverviewSummary> = flowOf(DrawerOverviewSummary())
            override fun observeDrawerTagSummaries(): Flow<List<DrawerTagSummary>> = tagSummariesFlow
        }

        val fakeTagRepo = object : TagRepository {
            override fun observeAllTags(): Flow<List<Tag>> = flowOf(listOf(sampleTag))
            override suspend fun getAllTags(): List<Tag> = listOf(sampleTag)
            override suspend fun createOrGetTagByPath(fullPath: String, aliases: List<String>): Tag = sampleTag
            override suspend fun renameOrMoveTag(tagId: String, newFullPath: String) = Unit
            override suspend fun updateAliases(tagId: String, aliases: List<String>) = Unit
            override suspend fun deleteTag(tagId: String) {
                deletedIds += tagId
                tagSummariesFlow.value = tagSummariesFlow.value.filterNot { it.tag.id == tagId }
            }

            override suspend fun setUserTag(entryId: String, tagId: String, attached: Boolean, nowMs: Long) = Unit
            override suspend fun applyAutoTags(
                entryId: String,
                suggestions: List<Pair<String, Float>>,
                nowMs: Long,
            ) = Unit

            override suspend fun promoteAutoTag(entryId: String, tagId: String, nowMs: Long) = Unit
            override suspend fun rejectAutoTag(entryId: String, tagId: String, nowMs: Long) = Unit
        }

        val fakeSettingsRepo = object : SettingsRepository {
            override val settings: Flow<UserSettings> = flowOf(UserSettings())
            override suspend fun getSettings(): UserSettings = UserSettings()
            override suspend fun setWeekStart(weekStart: WeekStart) = Unit
            override suspend fun setOfflineOnlyLinks(enabled: Boolean) = Unit
            override suspend fun setShowHiddenAutoTagsInChat(show: Boolean) = Unit
            override suspend fun setScheduledBackupCadence(cadence: BackupCadence) = Unit
            override suspend fun updateSyncLinks(
                spreadsheetId: String?,
                mediaFolderId: String?,
                lastExportIso: String?,
            ) = Unit

            override suspend fun setColorScheme(colorScheme: AppColorScheme) = Unit
            override suspend fun setThemeMode(themeMode: ThemeMode) = Unit
        }

        val vm = DrawerViewModel(
            entryRepository = fakeEntryRepo,
            tagRepository = fakeTagRepo,
            settingsRepository = fakeSettingsRepo,
            leafDisambiguator = DefaultLeafDisambiguator(),
        )
        advanceUntilIdle()
        assertThat(vm.uiState.value.allTagSummaries).hasSize(1)

        vm.deleteTag(sampleTag.id)
        advanceUntilIdle()

        assertThat(deletedIds).containsExactly(sampleTag.id)
        assertThat(vm.uiState.value.allTagSummaries).isEmpty()
    }

    @Test
    fun toggleShowHiddenPlaceholderMessages_persistsAndTracksArchivedCount() = runTest(testDispatcher) {
        val overviewFlow = MutableStateFlow(
            DrawerOverviewSummary(
                totalEntryCount = 12,
                openTodoCount = 3,
                archivedCount = 4,
            ),
        )
        val settingsFlow = MutableStateFlow(UserSettings(showHiddenPlaceholderMessages = false))
        val recordedShowHiddenCalls = mutableListOf<Boolean>()

        val fakeEntryRepo = object : EntryRepository {
            override fun observePagedEntries(): Flow<PagingData<EntryWithDetails>> = flowOf(PagingData.empty())
            override fun observeEntry(entryId: String): Flow<EntryWithDetails?> = flowOf(null)
            override suspend fun getEntry(entryId: String): EntryWithDetails? = null
            override suspend fun createEntry(
                bodyRaw: String,
                userTagIds: List<String>,
                replyToId: String?,
                pendingAttachments: List<PendingAttachment>,
                nowMs: Long,
                zoneId: String?,
                taskStatus: TaskStatus,
            ): EntryWithDetails = error("unused")

            override suspend fun editEntry(entryId: String, newBodyRaw: String, nowMs: Long): EntryWithDetails =
                error("unused")

            override suspend fun softDeleteEntry(entryId: String, nowMs: Long) = Unit
            override suspend fun undoSoftDelete(entryId: String) = Unit
            override fun observeCompactSummaries(): Flow<List<CompactEntrySummary>> = flowOf(emptyList())
            override fun observeCalendarDayCounts(
                yearMonth: String,
                filterTagPathPrefix: String?,
            ): Flow<List<CalendarDayCount>> = flowOf(emptyList())

            override fun observeEntriesForDate(
                localDate: String,
                filterTagPathPrefix: String?,
            ): Flow<List<EntryWithDetails>> = flowOf(emptyList())

            override fun observeContextWindow(startMs: Long, endMs: Long): Flow<List<EntryWithDetails>> =
                flowOf(emptyList())

            override fun observeEntriesWithPendingAutoTags(): Flow<List<EntryWithDetails>> = flowOf(emptyList())
            override fun observeDrawerOverview(): Flow<DrawerOverviewSummary> = overviewFlow
            override fun observeDrawerTagSummaries(): Flow<List<DrawerTagSummary>> = flowOf(emptyList())
        }

        val fakeTagRepo = object : TagRepository {
            override fun observeAllTags(): Flow<List<Tag>> = flowOf(emptyList())
            override suspend fun getAllTags(): List<Tag> = emptyList()
            override suspend fun createOrGetTagByPath(fullPath: String, aliases: List<String>): Tag = error("unused")
            override suspend fun renameOrMoveTag(tagId: String, newFullPath: String) = Unit
            override suspend fun updateAliases(tagId: String, aliases: List<String>) = Unit
            override suspend fun deleteTag(tagId: String) = Unit
            override suspend fun setUserTag(entryId: String, tagId: String, attached: Boolean, nowMs: Long) = Unit
            override suspend fun applyAutoTags(
                entryId: String,
                suggestions: List<Pair<String, Float>>,
                nowMs: Long,
            ) = Unit

            override suspend fun promoteAutoTag(entryId: String, tagId: String, nowMs: Long) = Unit
            override suspend fun rejectAutoTag(entryId: String, tagId: String, nowMs: Long) = Unit
        }

        val fakeSettingsRepo = object : SettingsRepository {
            override val settings: Flow<UserSettings> = settingsFlow
            override suspend fun getSettings(): UserSettings = settingsFlow.value
            override suspend fun setWeekStart(weekStart: WeekStart) = Unit
            override suspend fun setOfflineOnlyLinks(enabled: Boolean) = Unit
            override suspend fun setShowHiddenAutoTagsInChat(show: Boolean) = Unit
            override suspend fun setShowHiddenPlaceholderMessages(show: Boolean) {
                recordedShowHiddenCalls += show
                settingsFlow.value = settingsFlow.value.copy(showHiddenPlaceholderMessages = show)
            }

            override suspend fun setScheduledBackupCadence(cadence: BackupCadence) = Unit
            override suspend fun updateSyncLinks(
                spreadsheetId: String?,
                mediaFolderId: String?,
                lastExportIso: String?,
            ) = Unit

            override suspend fun setColorScheme(colorScheme: AppColorScheme) = Unit
            override suspend fun setThemeMode(themeMode: ThemeMode) = Unit
        }

        val vm = DrawerViewModel(
            entryRepository = fakeEntryRepo,
            tagRepository = fakeTagRepo,
            settingsRepository = fakeSettingsRepo,
            leafDisambiguator = DefaultLeafDisambiguator(),
        )
        advanceUntilIdle()

        assertThat(vm.uiState.value.overview.archivedCount).isEqualTo(4)
        assertThat(vm.uiState.value.showHiddenPlaceholderMessages).isFalse()

        vm.toggleShowHiddenPlaceholderMessages()
        advanceUntilIdle()
        assertThat(vm.uiState.value.showHiddenPlaceholderMessages).isTrue()
        assertThat(recordedShowHiddenCalls).containsExactly(true)

        vm.toggleShowHiddenPlaceholderMessages()
        advanceUntilIdle()
        assertThat(vm.uiState.value.showHiddenPlaceholderMessages).isFalse()
        assertThat(recordedShowHiddenCalls).containsExactly(true, false).inOrder()

        overviewFlow.value = overviewFlow.value.copy(archivedCount = 7)
        advanceUntilIdle()
        assertThat(vm.uiState.value.overview.archivedCount).isEqualTo(7)
    }
}
