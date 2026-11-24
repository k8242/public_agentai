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
import io.github.k8242.threadlog.core.model.ChatFeedFilter
import io.github.k8242.threadlog.core.model.CompactEntrySummary
import io.github.k8242.threadlog.core.model.Entry
import io.github.k8242.threadlog.core.model.EntryTag
import io.github.k8242.threadlog.core.model.EntryWithDetails
import io.github.k8242.threadlog.core.model.Tag
import io.github.k8242.threadlog.core.model.TagRootCategory
import io.github.k8242.threadlog.core.model.TagState
import io.github.k8242.threadlog.core.model.TaskPriority
import io.github.k8242.threadlog.core.model.TaskStatus
import io.github.k8242.threadlog.core.model.TimelineDateBucket
import io.github.k8242.threadlog.core.model.TodoFilterMode
import io.github.k8242.threadlog.core.model.TodoSortOrder
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
import kotlinx.coroutines.test.advanceTimeBy
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
    fun `selecting tag suggestion strips inline trigger cleanly and deduplicates selectedTagIds`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.onComposerTextChanged("Lunch with @le", cursor = 14)
        vm.onSelectTagSuggestion(lexTag)

        assertThat(vm.uiState.value.composerText).isEqualTo("Lunch with ")
        assertThat(vm.uiState.value.composerCursor).isEqualTo(11)
        assertThat(vm.uiState.value.activeTagTrigger).isNull()
        assertThat(vm.uiState.value.tagSuggestions).isEmpty()
        assertThat(vm.uiState.value.selectedTagIds).containsExactly("tag-lex")

        // Selecting the same tag again in the middle of text collapses double spaces and deduplicates tag id
        vm.onComposerTextChanged("Lunch with @le tomorrow", cursor = 14)
        vm.onSelectTagSuggestion(lexTag)
        assertThat(vm.uiState.value.composerText).isEqualTo("Lunch with tomorrow")
        assertThat(vm.uiState.value.composerCursor).isEqualTo(11)
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
        // Feature #3: startReply automatically copies the referenced message's visible tags
        assertThat(vm.uiState.value.selectedTagIds).containsExactly(lexTag.id)
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

    @Test
    fun sendingNote_emitsScrollToNewestEntryId_andConsumeClearsIt() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()
        vm.onComposerTextChanged("Fresh note", 10)
        vm.sendOrSave()
        advanceUntilIdle()

        val createdId = entryRepo.createdCalls.single().createdId
        assertThat(vm.uiState.value.scrollToNewestEntryId).isEqualTo(createdId)

        vm.consumeScrollToNewest(createdId)
        assertThat(vm.uiState.value.scrollToNewestEntryId).isNull()
    }

    @Test
    fun selectingTodosFilter_preEnablesComposerTask_andPreservesAfterSend() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()
        vm.setFeedFilter(io.github.k8242.threadlog.core.model.ChatFeedFilter.Todos())
        assertThat(vm.uiState.value.composerIsTask).isTrue()

        vm.onComposerTextChanged("Buy groceries", 13)
        vm.sendOrSave()
        advanceUntilIdle()

        assertThat(entryRepo.createdCalls.single().taskStatus)
            .isEqualTo(io.github.k8242.threadlog.core.model.TaskStatus.TODO)
        assertThat(vm.uiState.value.composerIsTask).isTrue()
    }

    @Test
    fun selectingTagFilter_preSelectsTagInComposer_andClearsWhenReturningToAll() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()
        val filter = io.github.k8242.threadlog.core.model.ChatFeedFilter.TagSubtree(
            tagId = "t-lex",
            fullPath = "people/friends/Lex",
            displayLabel = "Lex",
        )
        vm.setFeedFilter(filter)
        assertThat(vm.uiState.value.selectedTagIds).contains("t-lex")

        vm.onComposerTextChanged("Coffee catchup", 14)
        vm.sendOrSave()
        advanceUntilIdle()
        assertThat(entryRepo.createdCalls.single().userTagIds).contains("t-lex")
        assertThat(vm.uiState.value.selectedTagIds).contains("t-lex")

        vm.setFeedFilter(io.github.k8242.threadlog.core.model.ChatFeedFilter.All)
        assertThat(vm.uiState.value.selectedTagIds).doesNotContain("t-lex")
    }

    @Test
    fun toggleNoteTask_andToggleTaskCompleted_delegateToRepository() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()
        val regular = sampleEntry("e-1", "Note").entry
        vm.toggleNoteTask(regular)
        advanceUntilIdle()
        assertThat(entryRepo.taskStatusUpdates)
            .containsExactly("e-1" to io.github.k8242.threadlog.core.model.TaskStatus.TODO)

        val openTodo = regular.copy(taskStatus = io.github.k8242.threadlog.core.model.TaskStatus.TODO)
        vm.toggleTaskCompleted(openTodo)
        advanceUntilIdle()
        assertThat(entryRepo.taskStatusUpdates.last())
            .isEqualTo("e-1" to io.github.k8242.threadlog.core.model.TaskStatus.DONE)

        val doneTodo = regular.copy(taskStatus = io.github.k8242.threadlog.core.model.TaskStatus.DONE)
        vm.toggleTaskCompleted(doneTodo)
        advanceUntilIdle()
        assertThat(entryRepo.taskStatusUpdates.last())
            .isEqualTo("e-1" to io.github.k8242.threadlog.core.model.TaskStatus.TODO)

        vm.toggleNoteTask(openTodo)
        advanceUntilIdle()
        assertThat(entryRepo.taskStatusUpdates.last())
            .isEqualTo("e-1" to io.github.k8242.threadlog.core.model.TaskStatus.NONE)
    }

    @Test
    fun projectPlusTrigger_suggestsOnlyProjects_andStripsPlusTriggerOnSelect() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.onComposerTextChanged("Working on +jour", 16)
        assertThat(vm.uiState.value.tagSuggestions.map { it.id }).containsExactly(journalProjectTag.id)

        vm.onSelectTagSuggestion(journalProjectTag)
        assertThat(vm.uiState.value.composerText).isEqualTo("Working on ")
        assertThat(vm.uiState.value.composerCursor).isEqualTo(11)
        assertThat(vm.uiState.value.selectedTagIds).contains(journalProjectTag.id)
    }

    @Test
    fun toggleReaction_delegatesToRepository() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.toggleReaction("e-1", "\uD83D\uDD25")
        advanceUntilIdle()

        assertThat(entryRepo.reactionToggles).containsExactly("e-1" to "\uD83D\uDD25")
    }

    @Test
    fun sendOrSave_blocksUnmaskedPasswordAndPin_keepsComposerText_andMasksWithXxxx() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.onComposerTextChanged("Wifi pw: Summer2026! and pin 4829", 33)
        vm.sendOrSave()
        advanceUntilIdle()

        assertThat(entryRepo.createdCalls).isEmpty()
        assertThat(vm.uiState.value.credentialWarning).isNotNull()
        assertThat(vm.uiState.value.composerText).isEqualTo("Wifi pw: Summer2026! and pin 4829")

        vm.maskCredentialsInComposer()
        assertThat(vm.uiState.value.credentialWarning).isNull()
        assertThat(vm.uiState.value.composerText).isEqualTo("Wifi pw: xxxx! and pin xxxx")

        vm.sendOrSave()
        advanceUntilIdle()
        assertThat(entryRepo.createdCalls).hasSize(1)
        assertThat(entryRepo.createdCalls.single().bodyRaw).isEqualTo("Wifi pw: xxxx! and pin xxxx")
    }

    @Test
    fun sendOrSave_blocksUnmaskedPinWhenEditingExistingEntry() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.startEdit(sampleEntry("e-1", "Original note"))
        vm.onComposerTextChanged("Gate pin: 1234", 14)
        vm.sendOrSave()
        advanceUntilIdle()

        assertThat(entryRepo.editedCalls).isEmpty()
        assertThat(vm.uiState.value.credentialWarning).isNotNull()
        assertThat(vm.uiState.value.editingEntry?.entry?.id).isEqualTo("e-1")
    }

    @Test
    fun deleteTag_removesFromRepositorySelectedTags_andResetsActiveFilter() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        val filter = io.github.k8242.threadlog.core.model.ChatFeedFilter.TagSubtree(
            tagId = lexTag.id,
            fullPath = lexTag.fullPath,
            displayLabel = "Lex",
        )
        vm.setFeedFilter(filter)
        assertThat(vm.uiState.value.selectedTagIds).contains(lexTag.id)

        var clearedCallbackInvoked = false
        vm.deleteTag(lexTag.id) { clearedCallbackInvoked = true }
        advanceUntilIdle()

        assertThat(tagRepo.deletedTagIds).containsExactly(lexTag.id)
        assertThat(clearedCallbackInvoked).isTrue()
        assertThat(vm.uiState.value.activeFilter)
            .isEqualTo(io.github.k8242.threadlog.core.model.ChatFeedFilter.All)
        assertThat(vm.uiState.value.selectedTagIds).doesNotContain(lexTag.id)
    }

    @Test
    fun composerBackdate_passesCustomTimestampAndZone_andKeepAdvancesByOneMinute() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        val pastMs = 1_660_425_060_000L // 2022-08-13T14:11:00-07:00
        vm.setComposerBackdate(
            epochMs = pastMs,
            zoneId = "Pacific/Honolulu",
            keepAfterSend = true,
        )
        vm.onComposerTextChanged("Backdated entry 1", 17)
        vm.sendOrSave()
        advanceUntilIdle()

        assertThat(entryRepo.createdCalls).hasSize(1)
        assertThat(entryRepo.createdCalls[0].nowMs).isEqualTo(pastMs)
        assertThat(entryRepo.createdCalls[0].zoneId).isEqualTo("Pacific/Honolulu")
        // Keep-after-send advances by +1 minute for consecutive entries.
        assertThat(vm.uiState.value.composerCustomTimestampMs).isEqualTo(pastMs + 60_000L)
        assertThat(vm.uiState.value.composerCustomZoneId).isEqualTo("Pacific/Honolulu")

        // Sending without keep clears backdate back to Now.
        vm.setComposerBackdate(
            epochMs = pastMs + 120_000L,
            zoneId = "America/Los_Angeles",
            keepAfterSend = false,
        )
        vm.onComposerTextChanged("Backdated entry 2", 17)
        vm.sendOrSave()
        advanceUntilIdle()

        assertThat(entryRepo.createdCalls[1].nowMs).isEqualTo(pastMs + 120_000L)
        assertThat(entryRepo.createdCalls[1].zoneId).isEqualTo("America/Los_Angeles")
        assertThat(vm.uiState.value.composerCustomTimestampMs).isNull()
        assertThat(vm.uiState.value.composerCustomZoneId).isNull()
    }

    @Test
    fun resolveJumpTarget_usesEntryOffset_fallsBackToDateOffset_andPulsesHighlight() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        entryRepo.entryOffsets["entry-old" to ChatFeedFilter.All] = 142
        entryRepo.dateOffsets["2022-08-13" to ChatFeedFilter.All] = 300

        vm.resolveJumpTarget(entryId = "entry-old", localDate = "2022-08-13")
        advanceUntilIdle()

        val pending = vm.uiState.value.pendingJump
        assertThat(pending).isNotNull()
        assertThat(pending?.offset).isEqualTo(142)
        assertThat(pending?.targetEntryId).isEqualTo("entry-old")
        assertThat(pending?.targetFilter).isEqualTo(ChatFeedFilter.All)

        vm.consumePendingJump(pending!!.nonce)
        assertThat(vm.uiState.value.pendingJump).isNull()
        assertThat(vm.uiState.value.highlightedEntryId).isEqualTo("entry-old")

        // Highlight pulse clears automatically after 1.5s
        advanceTimeBy(1_600L)
        advanceUntilIdle()
        assertThat(vm.uiState.value.highlightedEntryId).isNull()

        // Missing entry falls back to findDateOffset
        vm.resolveJumpTarget(entryId = "deleted-entry", localDate = "2022-08-13")
        advanceUntilIdle()
        val fallback = vm.uiState.value.pendingJump
        assertThat(fallback?.offset).isEqualTo(300)
        assertThat(fallback?.targetEntryId).isNull()
    }

    @Test
    fun jumpToReplyParent_checksActiveFilterThenSwitchesToAllOrShowsDeletedError() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        val todosFilter = ChatFeedFilter.Todos(mode = TodoFilterMode.OPEN)
        vm.setFeedFilter(todosFilter)
        entryRepo.entryOffsets["parent-in-all" to ChatFeedFilter.All] = 88

        var switchedFilter: ChatFeedFilter? = null
        vm.jumpToReplyParent("parent-in-all") { switchedFilter = it }
        advanceUntilIdle()

        assertThat(switchedFilter).isEqualTo(ChatFeedFilter.All)
        assertThat(vm.uiState.value.activeFilter).isEqualTo(ChatFeedFilter.All)
        assertThat(vm.uiState.value.pendingJump?.offset).isEqualTo(88)
        assertThat(vm.uiState.value.pendingJump?.targetEntryId).isEqualTo("parent-in-all")

        // Missing parent everywhere shows "Original message was deleted"
        vm.jumpToReplyParent("missing-parent")
        advanceUntilIdle()
        assertThat(vm.uiState.value.errorMessage).isEqualTo("Original message was deleted")
    }

    @Test
    fun editAttachments_keepsRemovesDeduplicatesAndSubmitsChangesOnSave() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        val existingAtt1 = Attachment(
            id = "att-keep",
            entryId = "e-media",
            localFilename = "media/keep.webp",
            mimeType = "image/webp",
            byteSize = 50_000L,
            width = 800,
            height = 600,
            sha256 = "sha-keep",
        )
        val existingAtt2 = Attachment(
            id = "att-drop",
            entryId = "e-media",
            localFilename = "media/drop.webp",
            mimeType = "image/webp",
            byteSize = 40_000L,
            width = 800,
            height = 600,
            sha256 = "sha-drop",
        )
        val entryWithMedia = sampleEntry("e-media", "Caption").copy(
            attachments = listOf(existingAtt1, existingAtt2),
        )

        vm.startEdit(entryWithMedia)
        assertThat(vm.uiState.value.editingKeptAttachments).containsExactly(existingAtt1, existingAtt2).inOrder()
        assertThat(vm.uiState.value.removedEditingAttachmentIds).isEmpty()

        // Duplicate of kept attachment is ignored by sha256
        mediaCompressor.nextResult = Result.success(
            PendingAttachment(
                localFilename = "media/dup.webp",
                mimeType = "image/webp",
                byteSize = 50_000L,
                width = 800,
                height = 600,
                sha256 = "sha-keep",
            ),
        )
        vm.onImageBytesReceived(byteArrayOf(1))
        advanceUntilIdle()
        assertThat(vm.uiState.value.pendingAttachments).isEmpty()

        // New unique image is added
        val addedPending = PendingAttachment(
            localFilename = "media/new.webp",
            mimeType = "image/webp",
            byteSize = 60_000L,
            width = 1024,
            height = 768,
            sha256 = "sha-new",
        )
        mediaCompressor.nextResult = Result.success(addedPending)
        vm.onImageBytesReceived(byteArrayOf(2))
        advanceUntilIdle()
        assertThat(vm.uiState.value.pendingAttachments).containsExactly(addedPending)

        // Remove one existing attachment & clear text -> still saveable because attachments remain
        vm.removeEditingAttachment("att-drop")
        vm.onComposerTextChanged("", 0)
        assertThat(vm.uiState.value.editingKeptAttachments).containsExactly(existingAtt1)
        assertThat(vm.uiState.value.removedEditingAttachmentIds).containsExactly("att-drop")

        vm.sendOrSave()
        advanceUntilIdle()

        val editCall = entryRepo.detailedEditCalls.single()
        assertThat(editCall.entryId).isEqualTo("e-media")
        assertThat(editCall.addedAttachments).containsExactly(addedPending)
        assertThat(editCall.removedAttachmentIds).containsExactly("att-drop")
        assertThat(vm.uiState.value.editingEntry).isNull()
        assertThat(vm.uiState.value.editingKeptAttachments).isEmpty()
        assertThat(vm.uiState.value.removedEditingAttachmentIds).isEmpty()
        assertThat(vm.uiState.value.pendingAttachments).isEmpty()
    }

    @Test
    fun taskPriority_enforcesTaskRule_passesToCreate_andUpdatesTodoSortAndFilter() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        // Setting priority auto-enables composerIsTask
        assertThat(vm.uiState.value.composerIsTask).isFalse()
        vm.setComposerTaskPriority(TaskPriority.HIGH)
        assertThat(vm.uiState.value.composerIsTask).isTrue()
        assertThat(vm.uiState.value.composerTaskPriority).isEqualTo(TaskPriority.HIGH)

        // Toggling task off clears priority back to NONE
        vm.toggleComposerTask()
        assertThat(vm.uiState.value.composerIsTask).isFalse()
        assertThat(vm.uiState.value.composerTaskPriority).isEqualTo(TaskPriority.NONE)

        // Create entry with MEDIUM priority
        vm.setComposerTaskPriority(TaskPriority.MEDIUM)
        vm.onComposerTextChanged("Prepare release", 15)
        vm.sendOrSave()
        advanceUntilIdle()

        assertThat(entryRepo.createdCalls.single().taskStatus).isEqualTo(TaskStatus.TODO)
        assertThat(entryRepo.createdCalls.single().taskPriority).isEqualTo(TaskPriority.MEDIUM)
        assertThat(vm.uiState.value.composerTaskPriority).isEqualTo(TaskPriority.NONE)

        // Direct priority update on existing entry
        vm.setEntryTaskPriority("e-1", TaskPriority.LOW)
        advanceUntilIdle()
        assertThat(entryRepo.taskPriorityUpdates).containsExactly("e-1" to TaskPriority.LOW)

        // Todo filter mode, priority filter, and sort order preserve each other
        vm.setFeedFilter(ChatFeedFilter.Todos(mode = TodoFilterMode.OPEN))
        vm.setTodoPriorityFilter(TaskPriority.HIGH)
        vm.toggleTodoSortOrder()
        vm.setTodoFilterMode(TodoFilterMode.ALL)
        assertThat(vm.uiState.value.activeFilter).isEqualTo(
            ChatFeedFilter.Todos(
                mode = TodoFilterMode.ALL,
                priorityFilter = TaskPriority.HIGH,
                sortOrder = TodoSortOrder.PRIORITY,
            ),
        )
    }

    @Test
    fun startReply_copiesVisibleTagsAndCleansOnlyAutoCopiedTagsOnCancelSwitchOrDelete() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        // User manually selects alexWorkTag before replying
        vm.toggleSelectedTag(alexWorkTag.id)
        val hiddenAutoTag = EntryTag(
            entryId = "parent-1",
            tagId = "tag-hidden-auto",
            tag = Tag(
                id = "tag-hidden-auto",
                fullPath = "auto/hidden",
                leafName = "hidden",
                rootCategory = TagRootCategory.AUTO,
                colorToken = "auto",
            ),
            state = TagState.AUTO_HIDDEN,
            updatedAtMs = 1L,
        )
        val parent1 = sampleEntry("parent-1", "First parent").copy(
            tags = sampleEntry("parent-1", "First parent").tags + hiddenAutoTag,
        )
        vm.startReply(parent1)
        // Copies lexTag (USER) but ignores hiddenAutoTag; keeps manual alexWorkTag
        assertThat(vm.uiState.value.selectedTagIds).containsExactly(alexWorkTag.id, lexTag.id).inOrder()

        // Switching to a second reply target replaces lexTag with journalProjectTag
        val parent2 = sampleEntry("parent-2", "Second parent").copy(
            tags = listOf(
                EntryTag(
                    entryId = "parent-2",
                    tagId = journalProjectTag.id,
                    tag = journalProjectTag,
                    state = TagState.AUTO_PROMOTED,
                    updatedAtMs = 1L,
                ),
            ),
        )
        vm.startReply(parent2)
        assertThat(vm.uiState.value.selectedTagIds).containsExactly(alexWorkTag.id, journalProjectTag.id).inOrder()

        // Canceling reply removes only auto-copied journalProjectTag, preserving manual alexWorkTag
        vm.cancelReply()
        assertThat(vm.uiState.value.selectedTagIds).containsExactly(alexWorkTag.id)

        // Deleting the active reply target also strips its auto-copied tags
        vm.startReply(parent1)
        assertThat(vm.uiState.value.selectedTagIds).containsExactly(alexWorkTag.id, lexTag.id).inOrder()
        vm.deleteEntry("parent-1")
        advanceUntilIdle()
        assertThat(vm.uiState.value.replyTarget).isNull()
        assertThat(vm.uiState.value.selectedTagIds).containsExactly(alexWorkTag.id)
    }

    @Test
    fun inlineTagSelectionDuringEdit_stripsTokenAndImmediatelyAttachesTagToEditedEntry() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        val existing = sampleEntry("e-edit-tag", "Draft")
        vm.startEdit(existing)
        vm.onComposerTextChanged("Draft with @le", cursor = 14)
        vm.onSelectTagSuggestion(lexTag)
        advanceUntilIdle()

        assertThat(vm.uiState.value.composerText).isEqualTo("Draft with ")
        assertThat(tagRepo.setUserTagCalls).containsExactly(Triple("e-edit-tag", lexTag.id, true))
    }

    @Test
    fun archiveAndUndo_andUnarchive_delegateToRepository() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.archiveEntry("e-arch")
        advanceUntilIdle()
        assertThat(entryRepo.archivedUpdates).containsExactly("e-arch" to true)
        assertThat(vm.uiState.value.undoArchivedEntryId).isEqualTo("e-arch")

        vm.undoArchive()
        advanceUntilIdle()
        assertThat(entryRepo.archivedUpdates).containsExactly("e-arch" to true, "e-arch" to false).inOrder()
        assertThat(vm.uiState.value.undoArchivedEntryId).isNull()

        vm.unarchiveEntry("e-arch-2")
        advanceUntilIdle()
        assertThat(entryRepo.archivedUpdates.last()).isEqualTo("e-arch-2" to false)

        vm.setFeedFilter(ChatFeedFilter.Archived)
        assertThat(vm.uiState.value.activeFilter).isEqualTo(ChatFeedFilter.Archived)
        assertThat(vm.uiState.value.composerIsTask).isFalse()
    }

    @Test
    fun toggleHighlighted_andUpdateEntryMetadata_delegateWithTaskOnlyPriorityRule() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        val note = sampleEntry("e-hl", "Highlight me").entry
        vm.toggleHighlighted(note)
        advanceUntilIdle()
        assertThat(entryRepo.highlightUpdates).containsExactly("e-hl" to true)

        // Updating metadata with TaskStatus.NONE forces TaskPriority.NONE
        vm.updateEntryMetadata(
            entryId = "e-hl",
            createdAtMs = 1_700_000_000_000L,
            zoneId = "Pacific/Honolulu",
            taskStatus = TaskStatus.NONE,
            taskPriority = TaskPriority.HIGH,
            isHighlighted = true,
            archivedAtMs = 1_700_000_500_000L,
        )
        advanceUntilIdle()
        val call = entryRepo.metadataUpdates.single()
        assertThat(call.entryId).isEqualTo("e-hl")
        assertThat(call.zoneId).isEqualTo("Pacific/Honolulu")
        assertThat(call.taskStatus).isEqualTo(TaskStatus.NONE)
        assertThat(call.taskPriority).isEqualTo(TaskPriority.NONE)
        assertThat(call.isHighlighted).isTrue()
        assertThat(call.archivedAtMs).isEqualTo(1_700_000_500_000L)
    }

    @Test
    fun showHiddenPlaceholderToggle_updatesSettingsAndOnlyIncludesHiddenInAllNotes() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        assertThat(vm.uiState.value.showHiddenPlaceholderMessages).isFalse()
        assertThat(entryRepo.observedTimelineQueries.last()).isEqualTo(ChatFeedFilter.All to false)

        vm.toggleShowHiddenPlaceholderMessages()
        advanceUntilIdle()
        assertThat(vm.uiState.value.showHiddenPlaceholderMessages).isTrue()
        assertThat(settingsRepo.getSettings().showHiddenPlaceholderMessages).isTrue()
        assertThat(entryRepo.observedTimelineQueries.last()).isEqualTo(ChatFeedFilter.All to true)

        // Switching to TODOs must pass includeHiddenPlaceholders = false even when setting is true
        vm.setFeedFilter(ChatFeedFilter.Todos())
        advanceUntilIdle()
        assertThat(entryRepo.observedTimelineQueries.last()).isEqualTo(ChatFeedFilter.Todos() to false)

        // Timeline buckets populate timelineIndex & fallback period meta
        entryRepo.timelineBucketsFlow.value = listOf(
            TimelineDateBucket("2026-11-24", 2),
            TimelineDateBucket("2022-08-13", 3),
        )
        advanceUntilIdle()
        assertThat(vm.uiState.value.timelineIndex.totalCount).isEqualTo(5)
        assertThat(vm.timelinePeriodMeta(3)?.monthLabel).isEqualTo("August 2022")
    }

    private data class CreatedCall(
        val createdId: String,
        val bodyRaw: String,
        val userTagIds: List<String>,
        val replyToId: String?,
        val attachments: List<PendingAttachment>,
        val taskStatus: TaskStatus = TaskStatus.NONE,
        val taskPriority: TaskPriority = TaskPriority.NONE,
        val nowMs: Long = 0L,
        val zoneId: String? = null,
    )

    private data class DetailedEditCall(
        val entryId: String,
        val newBodyRaw: String,
        val addedAttachments: List<PendingAttachment>,
        val removedAttachmentIds: Set<String>,
    )

    private data class MetadataUpdateCall(
        val entryId: String,
        val createdAtMs: Long,
        val zoneId: String,
        val taskStatus: TaskStatus,
        val taskPriority: TaskPriority,
        val isHighlighted: Boolean,
        val archivedAtMs: Long?,
    )

    private class FakeEntryRepository : EntryRepository {
        private var nextSeq = 1
        val createdCalls = mutableListOf<CreatedCall>()
        val editedCalls = mutableListOf<Pair<String, String>>()
        val detailedEditCalls = mutableListOf<DetailedEditCall>()
        val taskStatusUpdates = mutableListOf<Pair<String, TaskStatus>>()
        val taskPriorityUpdates = mutableListOf<Pair<String, TaskPriority>>()
        val highlightUpdates = mutableListOf<Pair<String, Boolean>>()
        val archivedUpdates = mutableListOf<Pair<String, Boolean>>()
        val metadataUpdates = mutableListOf<MetadataUpdateCall>()
        val reactionToggles = mutableListOf<Pair<String, String>>()
        val softDeletedIds = mutableListOf<String>()
        val restoredIds = mutableListOf<String>()
        val observedTimelineQueries = mutableListOf<Pair<ChatFeedFilter, Boolean>>()
        val entryOffsets = mutableMapOf<Pair<String, ChatFeedFilter>, Int>()
        val dateOffsets = mutableMapOf<Pair<String, ChatFeedFilter>, Int>()
        val pendingAutoTagFlow = MutableStateFlow<List<EntryWithDetails>>(emptyList())
        val timelineBucketsFlow = MutableStateFlow<List<TimelineDateBucket>>(emptyList())

        override fun observePagedEntries(): Flow<PagingData<EntryWithDetails>> =
            flowOf(PagingData.empty())

        override fun observePagedEntries(
            filter: ChatFeedFilter,
            includeHiddenPlaceholders: Boolean,
        ): Flow<PagingData<EntryWithDetails>> = flowOf(PagingData.empty())

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
        ): EntryWithDetails = createEntry(
            bodyRaw = bodyRaw,
            userTagIds = userTagIds,
            replyToId = replyToId,
            pendingAttachments = pendingAttachments,
            nowMs = nowMs,
            zoneId = zoneId,
            taskStatus = taskStatus,
            taskPriority = TaskPriority.NONE,
        )

        override suspend fun createEntry(
            bodyRaw: String,
            userTagIds: List<String>,
            replyToId: String?,
            pendingAttachments: List<PendingAttachment>,
            nowMs: Long,
            zoneId: String?,
            taskStatus: TaskStatus,
            taskPriority: TaskPriority,
        ): EntryWithDetails {
            val id = "created-${nextSeq++}"
            createdCalls += CreatedCall(
                createdId = id,
                bodyRaw = bodyRaw,
                userTagIds = userTagIds,
                replyToId = replyToId,
                attachments = pendingAttachments,
                taskStatus = taskStatus,
                taskPriority = taskPriority,
                nowMs = nowMs,
                zoneId = zoneId,
            )
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
                    taskStatus = taskStatus,
                    taskPriority = taskPriority,
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

        override suspend fun setTaskStatus(
            entryId: String,
            taskStatus: TaskStatus,
            nowMs: Long,
        ) {
            taskStatusUpdates += entryId to taskStatus
        }

        override suspend fun setTaskPriority(
            entryId: String,
            taskPriority: TaskPriority,
            nowMs: Long,
        ) {
            taskPriorityUpdates += entryId to taskPriority
        }

        override suspend fun setHighlighted(
            entryId: String,
            highlighted: Boolean,
        ) {
            highlightUpdates += entryId to highlighted
        }

        override suspend fun setArchived(
            entryId: String,
            archived: Boolean,
            nowMs: Long,
        ) {
            archivedUpdates += entryId to archived
        }

        override suspend fun updateEntryMetadata(
            entryId: String,
            createdAtMs: Long,
            zoneId: String,
            taskStatus: TaskStatus,
            taskPriority: TaskPriority,
            isHighlighted: Boolean,
            archivedAtMs: Long?,
            nowMs: Long,
        ) {
            metadataUpdates += MetadataUpdateCall(
                entryId = entryId,
                createdAtMs = createdAtMs,
                zoneId = zoneId,
                taskStatus = taskStatus,
                taskPriority = taskPriority,
                isHighlighted = isHighlighted,
                archivedAtMs = archivedAtMs,
            )
        }

        override suspend fun toggleReaction(
            entryId: String,
            emoji: String,
            nowMs: Long,
        ) {
            reactionToggles += entryId to emoji
        }

        override suspend fun editEntry(
            entryId: String,
            newBodyRaw: String,
            nowMs: Long,
        ): EntryWithDetails = editEntry(
            entryId = entryId,
            newBodyRaw = newBodyRaw,
            nowMs = nowMs,
            addedAttachments = emptyList(),
            removedAttachmentIds = emptySet(),
        )

        override suspend fun editEntry(
            entryId: String,
            newBodyRaw: String,
            nowMs: Long,
            addedAttachments: List<PendingAttachment>,
            removedAttachmentIds: Set<String>,
        ): EntryWithDetails {
            editedCalls += entryId to newBodyRaw
            detailedEditCalls += DetailedEditCall(
                entryId = entryId,
                newBodyRaw = newBodyRaw,
                addedAttachments = addedAttachments,
                removedAttachmentIds = removedAttachmentIds,
            )
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

        override suspend fun findEntryOffset(
            entryId: String,
            filter: ChatFeedFilter,
            includeHiddenPlaceholders: Boolean,
        ): Int? = entryOffsets[entryId to filter]

        override suspend fun findDateOffset(
            localDate: String,
            filter: ChatFeedFilter,
            includeHiddenPlaceholders: Boolean,
        ): Int? = dateOffsets[localDate to filter]

        override fun observeTimelineDateBuckets(
            filter: ChatFeedFilter,
            includeHiddenPlaceholders: Boolean,
        ): Flow<List<TimelineDateBucket>> {
            observedTimelineQueries += filter to includeHiddenPlaceholders
            return timelineBucketsFlow
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
        val deletedTagIds = mutableListOf<String>()

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
        override suspend fun deleteTag(tagId: String) {
            deletedTagIds += tagId
            tagsFlow.value = tagsFlow.value.filterNot { it.id == tagId }
        }

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

        override suspend fun setShowHiddenPlaceholderMessages(show: Boolean) {
            state.value = state.value.copy(showHiddenPlaceholderMessages = show)
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
