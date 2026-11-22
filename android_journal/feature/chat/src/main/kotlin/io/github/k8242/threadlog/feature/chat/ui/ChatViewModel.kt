// Copyright (c) 2026 k8242
// SPDX-License-Identifier: MIT

package io.github.k8242.threadlog.feature.chat.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import io.github.k8242.threadlog.core.model.ChatFeedFilter
import io.github.k8242.threadlog.core.model.Entry
import io.github.k8242.threadlog.core.model.EntryWithDetails
import io.github.k8242.threadlog.core.model.Tag
import io.github.k8242.threadlog.core.model.TagRootCategory
import io.github.k8242.threadlog.core.model.TaskStatus
import io.github.k8242.threadlog.core.model.TodoFilterMode
import io.github.k8242.threadlog.core.model.WeekStart
import io.github.k8242.threadlog.core.model.contract.AutoTagScheduler
import io.github.k8242.threadlog.core.model.contract.BookmarkRepository
import io.github.k8242.threadlog.core.model.contract.BookmarkTitleScheduler
import io.github.k8242.threadlog.core.model.contract.DateWeekCalculator
import io.github.k8242.threadlog.core.model.contract.EntryRepository
import io.github.k8242.threadlog.core.model.contract.LeafDisambiguator
import io.github.k8242.threadlog.core.model.contract.MediaCompressor
import io.github.k8242.threadlog.core.model.contract.MediaStorage
import io.github.k8242.threadlog.core.model.contract.MessageParser
import io.github.k8242.threadlog.core.model.contract.PendingAttachment
import io.github.k8242.threadlog.core.model.contract.SettingsRepository
import io.github.k8242.threadlog.core.model.contract.TagRepository
import io.github.k8242.threadlog.core.model.parser.ActiveTagTrigger
import io.github.k8242.threadlog.core.model.parser.DateDividerMeta
import io.github.k8242.threadlog.core.model.parser.ParsedMessage
import io.github.k8242.threadlog.core.model.parser.TagTriggerKind
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ChatUiState(
    val activeFilter: ChatFeedFilter = ChatFeedFilter.All,
    val openTodoCount: Int = 0,
    val completedTodoCount: Int = 0,
    val composerText: String = "",
    val composerCursor: Int = 0,
    val composerIsTask: Boolean = false,
    val activeTagTrigger: ActiveTagTrigger? = null,
    val tagSuggestions: List<Tag> = emptyList(),
    val selectedTagIds: List<String> = emptyList(),
    val pendingAttachments: List<PendingAttachment> = emptyList(),
    val isCompressingImage: Boolean = false,
    val replyTarget: EntryWithDetails? = null,
    val editingEntry: EntryWithDetails? = null,
    val allTags: List<Tag> = emptyList(),
    val tagDisplayLabels: Map<String, String> = emptyMap(),
    val weekStart: WeekStart = WeekStart.MONDAY,
    val showHiddenAutoTags: Boolean = false,
    val offlineOnlyLinks: Boolean = false,
    val pendingAutoTagCount: Int = 0,
    val undoDeletedEntryId: String? = null,
    val scrollToNewestEntryId: String? = null,
    val errorMessage: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val entryRepository: EntryRepository,
    private val tagRepository: TagRepository,
    private val bookmarkRepository: BookmarkRepository,
    private val settingsRepository: SettingsRepository,
    private val messageParser: MessageParser,
    private val leafDisambiguator: LeafDisambiguator,
    private val dateWeekCalculator: DateWeekCalculator,
    private val mediaCompressor: MediaCompressor,
    private val mediaStorage: MediaStorage,
    private val autoTagScheduler: AutoTagScheduler,
    private val bookmarkTitleScheduler: BookmarkTitleScheduler,
) : ViewModel() {

    private val activeFilterFlow = MutableStateFlow<ChatFeedFilter>(ChatFeedFilter.All)

    val pagedEntries: Flow<PagingData<EntryWithDetails>> =
        activeFilterFlow
            .flatMapLatest { filter -> entryRepository.observePagedEntries(filter) }
            .cachedIn(viewModelScope)

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var autoAttachedFilterTagId: String? = null

    init {
        observeTags()
        observeSettings()
        observePendingAutoTags()
        observeDrawerOverview()
    }

    private fun observeTags() {
        viewModelScope.launch {
            tagRepository.observeAllTags().collect { tags ->
                val displayLabels = leafDisambiguator.computeDisplayLabels(tags)
                _uiState.update { state ->
                    state.copy(
                        allTags = tags,
                        tagDisplayLabels = displayLabels,
                        tagSuggestions = computeTagSuggestions(state.activeTagTrigger, tags),
                    )
                }
            }
        }
    }

    private fun observeSettings() {
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                _uiState.update { state ->
                    state.copy(
                        weekStart = settings.weekStart,
                        showHiddenAutoTags = settings.showHiddenAutoTagsInChat,
                        offlineOnlyLinks = settings.offlineOnlyLinks,
                    )
                }
            }
        }
    }

    private fun observePendingAutoTags() {
        viewModelScope.launch {
            entryRepository.observeEntriesWithPendingAutoTags().collect { pendingEntries ->
                _uiState.update { state ->
                    state.copy(pendingAutoTagCount = pendingEntries.size)
                }
            }
        }
    }

    private fun observeDrawerOverview() {
        viewModelScope.launch {
            entryRepository.observeDrawerOverview().collect { overview ->
                _uiState.update { state ->
                    state.copy(
                        openTodoCount = overview.openTodoCount,
                        completedTodoCount = overview.completedTodoCount,
                    )
                }
            }
        }
    }

    fun setFeedFilter(filter: ChatFeedFilter) {
        if (activeFilterFlow.value == filter) return
        activeFilterFlow.value = filter
        _uiState.update { state ->
            val previousAutoTag = autoAttachedFilterTagId
            val cleanedTags = if (previousAutoTag != null) {
                state.selectedTagIds.filterNot { it == previousAutoTag }
            } else {
                state.selectedTagIds
            }

            when (filter) {
                ChatFeedFilter.All -> {
                    autoAttachedFilterTagId = null
                    state.copy(
                        activeFilter = filter,
                        selectedTagIds = cleanedTags,
                        composerIsTask = false,
                    )
                }

                is ChatFeedFilter.Todos -> {
                    autoAttachedFilterTagId = null
                    state.copy(
                        activeFilter = filter,
                        selectedTagIds = cleanedTags,
                        composerIsTask = true,
                    )
                }

                is ChatFeedFilter.TagSubtree -> {
                    autoAttachedFilterTagId = filter.tagId
                    val nextTags = if (filter.tagId in cleanedTags) {
                        cleanedTags
                    } else {
                        cleanedTags + filter.tagId
                    }
                    state.copy(
                        activeFilter = filter,
                        selectedTagIds = nextTags,
                        composerIsTask = false,
                    )
                }
            }
        }
    }

    fun setTodoFilterMode(mode: TodoFilterMode) {
        val current = activeFilterFlow.value
        if (current is ChatFeedFilter.Todos && current.mode == mode) return
        val next = ChatFeedFilter.Todos(mode = mode)
        activeFilterFlow.value = next
        _uiState.update { it.copy(activeFilter = next) }
    }

    fun toggleComposerTask() {
        _uiState.update { it.copy(composerIsTask = !it.composerIsTask) }
    }

    fun consumeScrollToNewest(entryId: String) {
        _uiState.update { state ->
            if (state.scrollToNewestEntryId == entryId) {
                state.copy(scrollToNewestEntryId = null)
            } else {
                state
            }
        }
    }

    fun toggleNoteTask(entry: Entry) {
        val nextStatus = if (entry.taskStatus == TaskStatus.NONE) {
            TaskStatus.TODO
        } else {
            TaskStatus.NONE
        }
        setEntryTaskStatus(entry.id, nextStatus)
    }

    fun toggleTaskCompleted(entry: Entry) {
        val nextStatus = if (entry.taskStatus == TaskStatus.DONE) {
            TaskStatus.TODO
        } else {
            TaskStatus.DONE
        }
        setEntryTaskStatus(entry.id, nextStatus)
    }

    private fun setEntryTaskStatus(entryId: String, taskStatus: TaskStatus) {
        viewModelScope.launch {
            try {
                entryRepository.setTaskStatus(entryId = entryId, taskStatus = taskStatus)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = e.message ?: "Failed to update task status") }
            }
        }
    }

    fun toggleReaction(entryId: String, emoji: String) {
        val cleaned = emoji.trim()
        if (cleaned.isEmpty()) return
        viewModelScope.launch {
            try {
                entryRepository.toggleReaction(entryId = entryId, emoji = cleaned)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = e.message ?: "Failed to update reaction") }
            }
        }
    }

    fun createAndInsertTriggeredTag() {
        val trigger = _uiState.value.activeTagTrigger ?: return
        val raw = trigger.query.trim().trim('/')
        if (raw.isEmpty()) return
        val fullPath = when (trigger.kind) {
            TagTriggerKind.PERSON_AT -> if (raw.startsWith("people/", ignoreCase = true)) raw else "people/$raw"
            TagTriggerKind.PROJECT_PLUS -> if (raw.startsWith("projects/", ignoreCase = true)) raw else "projects/$raw"
            TagTriggerKind.ALL_HASH -> if (raw.contains('/')) raw else "topics/$raw"
        }
        viewModelScope.launch {
            try {
                val created = tagRepository.createOrGetTagByPath(fullPath)
                onSelectTagSuggestion(created)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = e.message ?: "Failed to create tag") }
            }
        }
    }

    fun onComposerTextChanged(text: String, cursor: Int) {
        val safeCursor = cursor.coerceIn(0, text.length)
        val trigger = messageParser.detectTagTrigger(text, safeCursor)
        _uiState.update { state ->
            state.copy(
                composerText = text,
                composerCursor = safeCursor,
                activeTagTrigger = trigger,
                tagSuggestions = computeTagSuggestions(trigger, state.allTags),
            )
        }
    }

    fun onSelectTagSuggestion(tag: Tag) {
        _uiState.update { state ->
            val trigger = state.activeTagTrigger
            val updatedSelected = if (tag.id in state.selectedTagIds) {
                state.selectedTagIds
            } else {
                state.selectedTagIds + tag.id
            }

            if (trigger == null) {
                return@update state.copy(
                    selectedTagIds = updatedSelected,
                    activeTagTrigger = null,
                    tagSuggestions = emptyList(),
                )
            }

            val text = state.composerText
            val start = trigger.startIndex.coerceIn(0, text.length)
            val end = trigger.endIndex.coerceIn(start, text.length)
            val before = text.substring(0, start)
            val after = text.substring(end)
            val prefix = when (trigger.kind) {
                TagTriggerKind.PERSON_AT -> "@"
                TagTriggerKind.PROJECT_PLUS -> "+"
                TagTriggerKind.ALL_HASH -> "#"
            }
            val mention = "$prefix${tag.leafName}"
            val needsSpace = after.isEmpty() || !after.first().isWhitespace()
            val replacement = if (needsSpace) "$mention " else mention
            val newText = before + replacement + after
            val newCursor = before.length + replacement.length

            state.copy(
                composerText = newText,
                composerCursor = newCursor,
                activeTagTrigger = null,
                tagSuggestions = emptyList(),
                selectedTagIds = updatedSelected,
            )
        }
    }

    fun toggleSelectedTag(tagId: String) {
        _uiState.update { state ->
            val updated = if (tagId in state.selectedTagIds) {
                state.selectedTagIds - tagId
            } else {
                state.selectedTagIds + tagId
            }
            state.copy(selectedTagIds = updated)
        }
    }

    fun createAndSelectTag(fullPath: String) {
        val normalized = fullPath.trim()
        if (normalized.isEmpty()) return

        viewModelScope.launch {
            try {
                val created = tagRepository.createOrGetTagByPath(normalized)
                _uiState.update { state ->
                    val updatedSelected = if (created.id in state.selectedTagIds) {
                        state.selectedTagIds
                    } else {
                        state.selectedTagIds + created.id
                    }
                    state.copy(selectedTagIds = updatedSelected, errorMessage = null)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = e.message ?: "Failed to create tag") }
            }
        }
    }

    fun createAndAttachEntryTag(entryId: String, fullPath: String) {
        val normalized = fullPath.trim()
        if (normalized.isEmpty()) return

        viewModelScope.launch {
            try {
                val created = tagRepository.createOrGetTagByPath(normalized)
                tagRepository.setUserTag(entryId = entryId, tagId = created.id, attached = true)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = e.message ?: "Failed to attach tag") }
            }
        }
    }

    fun onImageBytesReceived(rawBytes: ByteArray) {
        viewModelScope.launch {
            _uiState.update { it.copy(isCompressingImage = true, errorMessage = null) }
            try {
                val result = mediaCompressor.compressAndStore(rawBytes)
                result.fold(
                    onSuccess = { attachment ->
                        _uiState.update { state ->
                            val alreadyPending = state.pendingAttachments.any { it.sha256 == attachment.sha256 }
                            val updated = if (alreadyPending) {
                                state.pendingAttachments
                            } else {
                                state.pendingAttachments + attachment
                            }
                            state.copy(
                                pendingAttachments = updated,
                                isCompressingImage = false,
                            )
                        }
                    },
                    onFailure = { error ->
                        _uiState.update { state ->
                            state.copy(
                                isCompressingImage = false,
                                errorMessage = error.message ?: "Failed to compress image",
                            )
                        }
                    },
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                _uiState.update { state ->
                    state.copy(
                        isCompressingImage = false,
                        errorMessage = e.message ?: "Failed to compress image",
                    )
                }
            }
        }
    }

    fun removePendingAttachment(localFilename: String) {
        _uiState.update { state ->
            state.copy(
                pendingAttachments = state.pendingAttachments.filterNot { it.localFilename == localFilename },
            )
        }
    }

    fun startReply(entry: EntryWithDetails) {
        _uiState.update { state ->
            state.copy(
                replyTarget = entry,
                editingEntry = null,
            )
        }
    }

    fun cancelReply() {
        _uiState.update { it.copy(replyTarget = null) }
    }

    fun startEdit(entry: EntryWithDetails) {
        val raw = entry.entry.bodyRaw
        _uiState.update { state ->
            state.copy(
                editingEntry = entry,
                replyTarget = null,
                composerText = raw,
                composerCursor = raw.length,
                activeTagTrigger = null,
                tagSuggestions = emptyList(),
            )
        }
    }

    fun cancelEdit() {
        _uiState.update { state ->
            state.copy(
                editingEntry = null,
                composerText = "",
                composerCursor = 0,
                activeTagTrigger = null,
                tagSuggestions = emptyList(),
            )
        }
    }

    fun sendOrSave() {
        val snapshot = _uiState.value
        val editing = snapshot.editingEntry

        if (editing != null) {
            if (snapshot.composerText.isBlank() && editing.attachments.isEmpty()) {
                return
            }
            viewModelScope.launch {
                try {
                    val updated = entryRepository.editEntry(
                        entryId = editing.entry.id,
                        newBodyRaw = snapshot.composerText,
                    )
                    autoTagScheduler.enqueueAutoTag(updated.entry.id)
                    if (updated.bookmarks.isNotEmpty() && !snapshot.offlineOnlyLinks) {
                        bookmarkTitleScheduler.enqueueTitleFetch(updated.entry.id)
                    }
                    _uiState.update { state ->
                        state.copy(
                            editingEntry = null,
                            composerText = "",
                            composerCursor = 0,
                            activeTagTrigger = null,
                            tagSuggestions = emptyList(),
                            errorMessage = null,
                        )
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    _uiState.update { it.copy(errorMessage = e.message ?: "Failed to update entry") }
                }
            }
            return
        }

        if (snapshot.composerText.isBlank() && snapshot.pendingAttachments.isEmpty()) {
            return
        }

        viewModelScope.launch {
            try {
                val taskStatus = if (snapshot.composerIsTask) TaskStatus.TODO else TaskStatus.NONE
                val created = entryRepository.createEntry(
                    bodyRaw = snapshot.composerText,
                    userTagIds = snapshot.selectedTagIds,
                    replyToId = snapshot.replyTarget?.entry?.id,
                    pendingAttachments = snapshot.pendingAttachments,
                    taskStatus = taskStatus,
                )
                autoTagScheduler.enqueueAutoTag(created.entry.id)
                if (created.bookmarks.isNotEmpty() && !snapshot.offlineOnlyLinks) {
                    bookmarkTitleScheduler.enqueueTitleFetch(created.entry.id)
                }
                _uiState.update { state ->
                    val defaultTagsForFilter = when (val filter = state.activeFilter) {
                        is ChatFeedFilter.TagSubtree -> listOf(filter.tagId)
                        else -> emptyList()
                    }
                    val defaultIsTaskForFilter = state.activeFilter is ChatFeedFilter.Todos
                    state.copy(
                        composerText = "",
                        composerCursor = 0,
                        composerIsTask = defaultIsTaskForFilter,
                        activeTagTrigger = null,
                        tagSuggestions = emptyList(),
                        selectedTagIds = defaultTagsForFilter,
                        pendingAttachments = emptyList(),
                        replyTarget = null,
                        scrollToNewestEntryId = created.entry.id,
                        errorMessage = null,
                    )
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = e.message ?: "Failed to save entry") }
            }
        }
    }

    fun deleteEntry(entryId: String) {
        viewModelScope.launch {
            try {
                entryRepository.softDeleteEntry(entryId)
                _uiState.update { state ->
                    state.copy(
                        undoDeletedEntryId = entryId,
                        replyTarget = state.replyTarget?.takeUnless { it.entry.id == entryId },
                        editingEntry = state.editingEntry?.takeUnless { it.entry.id == entryId },
                        errorMessage = null,
                    )
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = e.message ?: "Failed to delete entry") }
            }
        }
    }

    fun undoDelete() {
        val deletedId = _uiState.value.undoDeletedEntryId ?: return
        _uiState.update { it.copy(undoDeletedEntryId = null) }
        viewModelScope.launch {
            try {
                entryRepository.undoSoftDelete(deletedId)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = e.message ?: "Failed to restore entry") }
            }
        }
    }

    fun clearUndoDelete() {
        _uiState.update { it.copy(undoDeletedEntryId = null) }
    }

    fun toggleShowHiddenAutoTags() {
        val nextValue = !_uiState.value.showHiddenAutoTags
        _uiState.update { it.copy(showHiddenAutoTags = nextValue) }
        viewModelScope.launch {
            try {
                settingsRepository.setShowHiddenAutoTagsInChat(nextValue)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = e.message ?: "Failed to update setting") }
            }
        }
    }

    fun promoteAutoTag(entryId: String, tagId: String) {
        viewModelScope.launch {
            try {
                tagRepository.promoteAutoTag(entryId = entryId, tagId = tagId)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = e.message ?: "Failed to promote auto-tag") }
            }
        }
    }

    fun rejectAutoTag(entryId: String, tagId: String) {
        viewModelScope.launch {
            try {
                tagRepository.rejectAutoTag(entryId = entryId, tagId = tagId)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = e.message ?: "Failed to reject auto-tag") }
            }
        }
    }

    fun toggleBookmarkCollapsed(bookmarkId: String, currentlyCollapsed: Boolean) {
        viewModelScope.launch {
            try {
                bookmarkRepository.setCollapsed(bookmarkId = bookmarkId, collapsed = !currentlyCollapsed)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = e.message ?: "Failed to toggle bookmark") }
            }
        }
    }

    fun setEntryTag(entryId: String, tagId: String, attached: Boolean) {
        viewModelScope.launch {
            try {
                tagRepository.setUserTag(entryId = entryId, tagId = tagId, attached = attached)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = e.message ?: "Failed to update tag") }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun parseMessage(bodyRaw: String): ParsedMessage = messageParser.parse(bodyRaw)

    fun dividerMeta(epochMs: Long, zoneId: String): DateDividerMeta =
        dateWeekCalculator.dividerMeta(epochMs, zoneId, _uiState.value.weekStart)

    fun resolveAttachmentPath(localFilename: String): String =
        mediaStorage.resolveFilePath(localFilename)

    private fun computeTagSuggestions(
        trigger: ActiveTagTrigger?,
        allTags: List<Tag>,
    ): List<Tag> {
        if (trigger == null) return emptyList()

        val pool = when (trigger.kind) {
            TagTriggerKind.PERSON_AT -> allTags.filter { it.rootCategory == TagRootCategory.PEOPLE }
            TagTriggerKind.PROJECT_PLUS -> allTags.filter { it.rootCategory == TagRootCategory.PROJECTS }
            TagTriggerKind.ALL_HASH -> allTags
        }

        val query = trigger.query.trim().lowercase()
        if (query.isEmpty()) {
            return pool
                .sortedWith(compareBy<Tag> { it.leafName.lowercase() }.thenBy { it.fullPath.lowercase() })
                .take(MAX_TAG_SUGGESTIONS)
        }

        return pool
            .asSequence()
            .filter { tag ->
                tag.leafName.lowercase().contains(query) ||
                    tag.fullPath.lowercase().contains(query) ||
                    tag.aliases.any { alias -> alias.lowercase().contains(query) }
            }
            .sortedWith(
                compareBy<Tag> { relevanceRank(it, query) }
                    .thenBy { it.leafName.lowercase() }
                    .thenBy { it.fullPath.lowercase() },
            )
            .take(MAX_TAG_SUGGESTIONS)
            .toList()
    }

    private fun relevanceRank(tag: Tag, query: String): Int {
        val leafLower = tag.leafName.lowercase()
        val aliasesLower = tag.aliases.map { it.lowercase() }
        return when {
            leafLower == query || aliasesLower.any { it == query } -> 0
            leafLower.startsWith(query) -> 1
            aliasesLower.any { it.startsWith(query) } -> 2
            tag.fullPath.lowercase().startsWith(query) -> 3
            else -> 4
        }
    }

    companion object {
        private const val MAX_TAG_SUGGESTIONS = 20
    }
}
