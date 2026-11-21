// Copyright (c) 2026 k8242
// SPDX-License-Identifier: MIT

package io.github.k8242.threadlog.feature.chat.ui

import androidx.paging.PagingData
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.github.k8242.threadlog.core.model.Attachment
import io.github.k8242.threadlog.core.model.BackupCadence
import io.github.k8242.threadlog.core.model.Bookmark
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
import io.github.k8242.threadlog.core.model.contract.AutoTagScheduler
import io.github.k8242.threadlog.core.model.contract.BookmarkRepository
import io.github.k8242.threadlog.core.model.contract.BookmarkTitleScheduler
import io.github.k8242.threadlog.core.model.contract.EntryRepository
import io.github.k8242.threadlog.core.model.contract.MediaCompressor
import io.github.k8242.threadlog.core.model.contract.MediaStorage
import io.github.k8242.threadlog.core.model.contract.PendingAttachment
import io.github.k8242.threadlog.core.model.contract.SettingsRepository
import io.github.k8242.threadlog.core.model.contract.TagRepository
import io.github.k8242.threadlog.core.parser.DefaultDateWeekCalculator
import io.github.k8242.threadlog.core.parser.DefaultLeafDisambiguator
import io.github.k8242.threadlog.core.parser.DefaultMessageParser
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
class ChatViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var entryRepo: FakeEntryRepository
    private lateinit var tagRepo: FakeTagRepository
    private lateinit var bookmarkRepo: FakeBookmarkRepository
    private lateinit var settingsRepo: FakeSettingsRepository
    private lateinit var mediaCompressor: FakeMediaCompressor
    private lateinit var mediaStorage: FakeMediaStorage
    private lateinit var autoTagScheduler: RecordingAutoTagScheduler
    private lateinit var bookmarkTitleScheduler: RecordingBookmarkTitleScheduler

    private val lexTag = Tag(
        id = "tag-lex",
        fullPath = "people/friends/Lex",
        leafName = "Lex",
        rootCategory = TagRootCategory.PEOPLE,
        colorToken = "people",
        aliases = listOf("Alexis"),
    )
    private val alexWorkTag = Tag(
        id = "tag-alex-work",
        fullPath = "people/work/Alex",
        leafName = "Alex",
        rootCategory = TagRootCategory.PEOPLE,
        colorToken = "people",
    )
    private val journalProjectTag = Tag(
        id = "tag-journal",
        fullPath = "projects/journal-app",
        leafName = "journal-app",
        rootCategory = TagRootCategory.PROJECTS,
        colorToken = "projects",
        aliases = listOf("threadlog"),
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        entryRepo = FakeEntryRepository()
        tagRepo = FakeTagRepository(listOf(lexTag, alexWorkTag, journalProjectTag))
        bookmarkRepo = FakeBookmarkRepository()
        settingsRepo = FakeSettingsRepository()
        mediaCompressor = FakeMediaCompressor()
        mediaStorage = FakeMediaStorage()
        autoTagScheduler = RecordingAutoTagScheduler()
        bookmarkTitleScheduler = RecordingBookmarkTitleScheduler()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): ChatViewModel = ChatViewModel(
        entryRepository = entryRepo,
        tagRepository = tagRepo,
        bookmarkRepository = bookmarkRepo,
        settingsRepository = settingsRepo,
        messageParser = DefaultMessageParser(),
        leafDisambiguator = DefaultLeafDisambiguator(),
        dateWeekCalculator = DefaultDateWeekCalculator(),
        mediaCompressor = mediaCompressor,
        mediaStorage = mediaStorage,
        autoTagScheduler = autoTagScheduler,
        bookmarkTitleScheduler = bookmarkTitleScheduler,
    )

    @Test
    fun `at-trigger filters to people only and matches aliases while hash-trigger searches all tags`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        // '@' matches people alias "Alexis" -> Lex, excludes projects/journal-app
        vm.onComposerTextChanged("Coffee with @alexi", cursor = 18)
        assertThat(vm.uiState.value.activeTagTrigger).isNotNull()
        assertThat(vm.uiState.value.tagSuggestions).containsExactly(lexTag)

        // '@' empty query lists only PEOPLE tags
        vm.onComposerTextChanged("Talked to @", cursor = 11)
        assertThat(vm.uiState.value.tagSuggestions).containsExactly(alexWorkTag, lexTag)

        // '#' matches alias "threadlog" on projects/journal-app
        vm.onComposerTextChanged("Shipping #thread", cursor = 16)
        assertThat(vm.uiState.value.tagSuggestions).containsExactly(journalProjectTag)
    }

    @Test
    fun `selecting tag suggestion replaces token cleanly and deduplicates selectedTagIds`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.onComposerTextChanged("Lunch with @le", cursor = 14)
        vm.onSelectTagSuggestion(lexTag)

        assertThat(vm.uiState.value.composerText).isEqualTo("Lunch with @Lex ")
        assertThat(vm.uiState.value.composerCursor).isEqualTo(16)
        assertThat(vm.uiState.value.activeTagTrigger).isNull()
        assertThat(vm.uiState.value.tagSuggestions).isEmpty()
        assertThat(vm.uiState.value.selectedTagIds).containsExactly("tag-lex")

        // Selecting the same tag again should not duplicate tag id
        vm.onComposerTextChanged("Lunch with @Lex and @le", cursor = 23)
        vm.onSelectTagSuggestion(lexTag)
        assertThat(vm.uiState.value.selectedTagIds).containsExactly("tag-lex")
    }

    @Test
    fun `image compression appends deduplicated pending attachment and surfaces failure`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        val sampleAttachment = PendingAttachment(
            localFilename = "media/img1.webp",
            mimeType = "image/webp",
            byteSize = 120_000L,
            width = 1280,
            height = 720,
            sha256 = "abc123",
        )
        mediaCompressor.nextResult = Result.success(sampleAttachment)

        vm.onImageBytesReceived(byteArrayOf(1, 2, 3))
        advanceUntilIdle()
        assertThat(vm.uiState.value.isCompressingImage).isFalse()
        assertThat(vm.uiState.value.pendingAttachments).containsExactly(sampleAttachment)

        // Duplicate sha256 should not be added twice
        vm.onImageBytesReceived(byteArrayOf(1, 2, 3))
        advanceUntilIdle()
        assertThat(vm.uiState.value.pendingAttachments).hasSize(1)

        // Failure surfaces errorMessage
        mediaCompressor.nextResult = Result.failure(IllegalArgumentException("Unsupported image format"))
        vm.onImageBytesReceived(byteArrayOf(9, 9))
        advanceUntilIdle()
        assertThat(vm.uiState.value.isCompressingImage).isFalse()
        assertThat(vm.uiState.value.errorMessage).isEqualTo("Unsupported image format")

        vm.removePendingAttachment("media/img1.webp")
        assertThat(vm.uiState.value.pendingAttachments).isEmpty()
    }

    @Test
    fun `sendOrSave creates entry and enqueues auto-tag and bookmark title only when online`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        val replyTarget = sampleEntry("entry-parent", "Parent thought")
        vm.startReply(replyTarget)
        vm.toggleSelectedTag(lexTag.id)
        vm.onComposerTextChanged("Check https://kotlinlang.org/docs", cursor = 33)

        vm.sendOrSave()
        advanceUntilIdle()

        assertThat(entryRepo.createdCalls).hasSize(1)
        val created = entryRepo.createdCalls.single()
        assertThat(created.bodyRaw).isEqualTo("Check https://kotlinlang.org/docs")
        assertThat(created.userTagIds).containsExactly(lexTag.id)
        assertThat(created.replyToId).isEqualTo("entry-parent")
        assertThat(autoTagScheduler.enqueuedEntryIds).containsExactly(created.createdId)
        assertThat(bookmarkTitleScheduler.enqueuedEntryIds).containsExactly(created.createdId)

        // Draft cleared after send
        assertThat(vm.uiState.value.composerText).isEmpty()
        assertThat(vm.uiState.value.selectedTagIds).isEmpty()
        assertThat(vm.uiState.value.replyTarget).isNull()

        // Enable offlineOnlyLinks -> bookmark title fetch must NOT be enqueued
        settingsRepo.setOfflineOnlyLinks(true)
        advanceUntilIdle()

        vm.onComposerTextChanged("Offline link https://example.com/a", cursor = 34)
        vm.sendOrSave()
        advanceUntilIdle()

        assertThat(autoTagScheduler.enqueuedEntryIds).hasSize(2)
        assertThat(bookmarkTitleScheduler.enqueuedEntryIds).hasSize(1)
    }

    @Test
    fun `edit flow updates entry enqueues schedulers and clears edit state`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        val existing = sampleEntry("entry-edit", "Original note")
        vm.startEdit(existing)
        assertThat(vm.uiState.value.editingEntry).isEqualTo(existing)
        assertThat(vm.uiState.value.composerText).isEqualTo("Original note")

        vm.onComposerTextChanged("Updated note https://android.com", cursor = 32)
        vm.sendOrSave()
        advanceUntilIdle()

        assertThat(entryRepo.editedCalls).containsExactly("entry-edit" to "Updated note https://android.com")
        assertThat(autoTagScheduler.enqueuedEntryIds).containsExactly("entry-edit")
        assertThat(bookmarkTitleScheduler.enqueuedEntryIds).containsExactly("entry-edit")
        assertThat(vm.uiState.value.editingEntry).isNull()
        assertThat(vm.uiState.value.composerText).isEmpty()
    }

    @Test
    fun `soft delete sets undo id and undoDelete restores entry`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.deleteEntry("entry-del")
        advanceUntilIdle()

        assertThat(entryRepo.softDeletedIds).containsExactly("entry-del")
        assertThat(vm.uiState.value.undoDeletedEntryId).isEqualTo("entry-del")

        vm.undoDelete()
        advanceUntilIdle()

        assertThat(entryRepo.restoredIds).containsExactly("entry-del")
        assertThat(vm.uiState.value.undoDeletedEntryId).isNull()
    }

    @Test
    fun `hidden auto-tag toggle promote reject and pendingAutoTagCount react cleanly`() = runTest {
        val vm = createViewModel()

        vm.uiState.test {
            val initial = awaitItem()
            assertThat(initial.showHiddenAutoTags).isFalse()

            // Emit pending auto-tag entries and initial tags/settings
            entryRepo.pendingAutoTagFlow.value = listOf(
                sampleEntry("entry-a", "A"),
                sampleEntry("entry-b", "B"),
            )
            advanceUntilIdle()

            val afterPending = expectMostRecentItem()
            assertThat(afterPending.pendingAutoTagCount).isEqualTo(2)

            vm.toggleShowHiddenAutoTags()
            advanceUntilIdle()

            val afterToggle = expectMostRecentItem()
            assertThat(afterToggle.showHiddenAutoTags).isTrue()
            assertThat(settingsRepo.getSettings().showHiddenAutoTagsInChat).isTrue()

            cancelAndIgnoreRemainingEvents()
        }

        vm.promoteAutoTag("entry-a", "tag-journal")
        vm.rejectAutoTag("entry-b", "tag-lex")
        vm.toggleBookmarkCollapsed("bm-1", currentlyCollapsed = true)
        vm.setEntryTag("entry-a", "tag-lex", attached = true)
        advanceUntilIdle()

        assertThat(tagRepo.promotedCalls).containsExactly("entry-a" to "tag-journal")
        assertThat(tagRepo.rejectedCalls).containsExactly("entry-b" to "tag-lex")
        assertThat(bookmarkRepo.collapsedUpdates).containsExactly("bm-1" to false)
        assertThat(tagRepo.setUserTagCalls).containsExactly(Triple("entry-a", "tag-lex", true))
    }

    private fun sampleEntry(id: String, body: String): EntryWithDetails = EntryWithDetails(
        entry = Entry(
            id = id,
            createdAtMs = 1_763_560_000_000L,
            zoneId = "America/Los_Angeles",
            localDate = "2026-11-19",
            isoWeek = "2026-W47",
            bodyRaw = body,
            bodyPlain = body,
            contentHash = "hash-$id",
        ),
        tags = listOf(
            EntryTag(
                entryId = id,
                tagId = lexTag.id,
                tag = lexTag,
                state = TagState.USER,
                updatedAtMs = 1_763_560_000_000L,
            ),
        ),
        bookmarks = emptyList(),
        attachments = emptyList(),
    )

    private data class CreatedCall(
        val createdId: String,
        val bodyRaw: String,
        val userTagIds: List<String>,
        val replyToId: String?,
        val attachments: List<PendingAttachment>,
    )

    private class FakeEntryRepository : EntryRepository {
        private var nextSeq = 1
        val createdCalls = mutableListOf<CreatedCall>()
        val editedCalls = mutableListOf<Pair<String, String>>()
        val softDeletedIds = mutableListOf<String>()
        val restoredIds = mutableListOf<String>()
        val pendingAutoTagFlow = MutableStateFlow<List<EntryWithDetails>>(emptyList())

        override fun observePagedEntries(): Flow<PagingData<EntryWithDetails>> =
            flowOf(PagingData.empty())

        override fun observeEntry(entryId: String): Flow<EntryWithDetails?> = flowOf(null)
        override suspend fun getEntry(entryId: String): EntryWithDetails? = null

        override suspend fun createEntry(
            bodyRaw: String,
            userTagIds: List<String>,
            replyToId: String?,
            pendingAttachments: List<PendingAttachment>,
            nowMs: Long,
            zoneId: String?,
        ): EntryWithDetails {
            val id = "created-${nextSeq++}"
            createdCalls += CreatedCall(id, bodyRaw, userTagIds, replyToId, pendingAttachments)
            val bookmarks = if (bodyRaw.contains("http")) {
                listOf(
                    Bookmark(
                        id = "bm-$id",
                        entryId = id,
                        urlRaw = "https://example.com",
                        domain = "example.com",
                    ),
                )
            } else {
                emptyList()
            }
            return EntryWithDetails(
                entry = Entry(
                    id = id,
                    createdAtMs = nowMs,
                    zoneId = zoneId ?: "UTC",
                    localDate = "2026-11-19",
                    isoWeek = "2026-W47",
                    bodyRaw = bodyRaw,
                    bodyPlain = bodyRaw,
                    replyToId = replyToId,
                    contentHash = "hash-$id",
                ),
                tags = emptyList(),
                bookmarks = bookmarks,
                attachments = pendingAttachments.mapIndexed { idx, att ->
                    Attachment(
                        id = "att-$idx",
                        entryId = id,
                        localFilename = att.localFilename,
                        mimeType = att.mimeType,
                        byteSize = att.byteSize,
                        width = att.width,
                        height = att.height,
                        sha256 = att.sha256,
                    )
                },
            )
        }

        override suspend fun editEntry(
            entryId: String,
            newBodyRaw: String,
            nowMs: Long,
        ): EntryWithDetails {
            editedCalls += entryId to newBodyRaw
            val bookmarks = if (newBodyRaw.contains("http")) {
                listOf(
                    Bookmark(
                        id = "bm-edit-$entryId",
                        entryId = entryId,
                        urlRaw = "https://android.com",
                        domain = "android.com",
                    ),
                )
            } else {
                emptyList()
            }
            return EntryWithDetails(
                entry = Entry(
                    id = entryId,
                    createdAtMs = 1_763_560_000_000L,
                    zoneId = "UTC",
                    localDate = "2026-11-19",
                    isoWeek = "2026-W47",
                    bodyRaw = newBodyRaw,
                    bodyPlain = newBodyRaw,
                    contentHash = "hash-edit",
                    updatedAtMs = nowMs,
                ),
                tags = emptyList(),
                bookmarks = bookmarks,
                attachments = emptyList(),
            )
        }

        override suspend fun softDeleteEntry(entryId: String, nowMs: Long) {
            softDeletedIds += entryId
        }

        override suspend fun undoSoftDelete(entryId: String) {
            restoredIds += entryId
        }

        override fun observeCompactSummaries(): Flow<List<CompactEntrySummary>> = flowOf(emptyList())
        override fun observeCalendarDayCounts(yearMonth: String, filterTagPathPrefix: String?): Flow<List<CalendarDayCount>> =
            flowOf(emptyList())

        override fun observeEntriesForDate(localDate: String, filterTagPathPrefix: String?): Flow<List<EntryWithDetails>> =
            flowOf(emptyList())

        override fun observeContextWindow(startMs: Long, endMs: Long): Flow<List<EntryWithDetails>> =
            flowOf(emptyList())

        override fun observeEntriesWithPendingAutoTags(): Flow<List<EntryWithDetails>> = pendingAutoTagFlow
    }

    private class FakeTagRepository(initialTags: List<Tag>) : TagRepository {
        private val tagsFlow = MutableStateFlow(initialTags)
        val promotedCalls = mutableListOf<Pair<String, String>>()
        val rejectedCalls = mutableListOf<Pair<String, String>>()
        val setUserTagCalls = mutableListOf<Triple<String, String, Boolean>>()

        override fun observeAllTags(): Flow<List<Tag>> = tagsFlow
        override suspend fun getAllTags(): List<Tag> = tagsFlow.value

        override suspend fun createOrGetTagByPath(fullPath: String, aliases: List<String>): Tag {
            val existing = tagsFlow.value.firstOrNull { it.fullPath.equals(fullPath, ignoreCase = true) }
            if (existing != null) return existing
            val category = TagRootCategory.fromPath(fullPath)
            val created = Tag(
                id = "tag-${tagsFlow.value.size + 1}",
                fullPath = fullPath,
                leafName = fullPath.substringAfterLast('/'),
                rootCategory = category,
                colorToken = category.key,
                aliases = aliases,
            )
            tagsFlow.value = tagsFlow.value + created
            return created
        }

        override suspend fun renameOrMoveTag(tagId: String, newFullPath: String) = Unit
        override suspend fun updateAliases(tagId: String, aliases: List<String>) = Unit
        override suspend fun deleteTag(tagId: String) = Unit

        override suspend fun setUserTag(entryId: String, tagId: String, attached: Boolean, nowMs: Long) {
            setUserTagCalls += Triple(entryId, tagId, attached)
        }

        override suspend fun applyAutoTags(entryId: String, suggestions: List<Pair<String, Float>>, nowMs: Long) = Unit

        override suspend fun promoteAutoTag(entryId: String, tagId: String, nowMs: Long) {
            promotedCalls += entryId to tagId
        }

        override suspend fun rejectAutoTag(entryId: String, tagId: String, nowMs: Long) {
            rejectedCalls += entryId to tagId
        }
    }

    private class FakeBookmarkRepository : BookmarkRepository {
        val collapsedUpdates = mutableListOf<Pair<String, Boolean>>()
        override suspend fun updateBookmarkTitle(bookmarkId: String, title: String) = Unit
        override suspend fun setCollapsed(bookmarkId: String, collapsed: Boolean) {
            collapsedUpdates += bookmarkId to collapsed
        }

        override suspend fun getBookmarksMissingTitle(entryId: String): List<Bookmark> = emptyList()
    }

    private class FakeSettingsRepository : SettingsRepository {
        private val state = MutableStateFlow(UserSettings())
        override val settings: Flow<UserSettings> = state
        override suspend fun getSettings(): UserSettings = state.value
        override suspend fun setWeekStart(weekStart: WeekStart) {
            state.value = state.value.copy(weekStart = weekStart)
        }

        override suspend fun setOfflineOnlyLinks(enabled: Boolean) {
            state.value = state.value.copy(offlineOnlyLinks = enabled)
        }

        override suspend fun setShowHiddenAutoTagsInChat(show: Boolean) {
            state.value = state.value.copy(showHiddenAutoTagsInChat = show)
        }

        override suspend fun setScheduledBackupCadence(cadence: BackupCadence) {
            state.value = state.value.copy(scheduledBackupCadence = cadence)
        }

        override suspend fun updateSyncLinks(
            spreadsheetId: String?,
            mediaFolderId: String?,
            lastExportIso: String?,
        ) = Unit
    }

    private class FakeMediaCompressor : MediaCompressor {
        var nextResult: Result<PendingAttachment> = Result.failure(IllegalStateException("Unconfigured"))
        override suspend fun compressAndStore(rawBytes: ByteArray): Result<PendingAttachment> = nextResult
    }

    private class FakeMediaStorage : MediaStorage {
        override fun resolveFilePath(localFilename: String): String = "/files/$localFilename"
        override suspend fun readBytes(localFilename: String): ByteArray? = null
        override suspend fun writeVerifiedBytes(uuid: String, mimeType: String, bytes: ByteArray): PendingAttachment =
            error("Not needed in unit test")

        override suspend fun findBySha256(sha256: String): String? = null
    }

    private class RecordingAutoTagScheduler : AutoTagScheduler {
        val enqueuedEntryIds = mutableListOf<String>()
        override fun enqueueAutoTag(entryId: String) {
            enqueuedEntryIds += entryId
        }
    }

    private class RecordingBookmarkTitleScheduler : BookmarkTitleScheduler {
        val enqueuedEntryIds = mutableListOf<String>()
        override fun enqueueTitleFetch(entryId: String) {
            enqueuedEntryIds += entryId
        }
    }
}
