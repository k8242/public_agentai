// Copyright (c) 2026 k8242
// SPDX-License-Identifier: MIT

package io.github.k8242.threadlog.feature.chat.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.k8242.threadlog.core.model.Attachment
import io.github.k8242.threadlog.core.model.ChatFeedFilter
import io.github.k8242.threadlog.core.model.Entry
import io.github.k8242.threadlog.core.model.EntryWithDetails
import io.github.k8242.threadlog.core.model.Tag
import io.github.k8242.threadlog.core.model.TagColorPreset
import io.github.k8242.threadlog.core.model.TagRootCategory
import io.github.k8242.threadlog.core.model.TagSortOrder
import io.github.k8242.threadlog.core.model.TaskPriority
import io.github.k8242.threadlog.core.model.TaskStatus
import io.github.k8242.threadlog.core.model.TodoFilterMode
import io.github.k8242.threadlog.core.model.TodoSortOrder
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
import io.github.k8242.threadlog.core.parser.CredentialCheckResult
import io.github.k8242.threadlog.core.parser.SensitiveCredentialException
import io.github.k8242.threadlog.core.parser.SensitiveCredentialGuard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PendingJumpRequest(
    val offset: Int,
    val targetEntryId: String?,
    val targetFilter: ChatFeedFilter,
    val nonce: Long,
)

data class ChatUiState(
    val activeFilter: ChatFeedFilter = ChatFeedFilter.All(),
    val openTodoCount: Int = 0,
    val completedTodoCount: Int = 0,
    val composerText: String = "",
    val composerCursor: Int = 0,
    val composerIsTask: Boolean = false,
    val composerTaskPriority: TaskPriority = TaskPriority.NONE,
    val composerCustomTimestampMs: Long? = null,
    val composerCustomZoneId: String? = null,
    val keepBackdateAfterSend: Boolean = false,
    val activeTagTrigger: ActiveTagTrigger? = null,
    val tagSuggestions: List<Tag> = emptyList(),
    val selectedTagIds: List<String> = emptyList(),
    val pendingAttachments: List<PendingAttachment> = emptyList(),
    val editingKeptAttachments: List<Attachment> = emptyList(),
    val removedEditingAttachmentIds: Set<String> = emptySet(),
    val isCompressingImage: Boolean = false,
    val replyTarget: EntryWithDetails? = null,
    val editingEntry: EntryWithDetails? = null,
    val allTags: List<Tag> = emptyList(),
    val recentTags: List<Tag> = emptyList(),
    val tagDisplayLabels: Map<String, String> = emptyMap(),
    val tagSortOrder: TagSortOrder = TagSortOrder.RECENT,
    val weekStart: WeekStart = WeekStart.MONDAY,
    val showHiddenAutoTags: Boolean = false,
    val showHiddenPlaceholderMessages: Boolean = false,
    val offlineOnlyLinks: Boolean = false,
    val pendingAutoTagCount: Int = 0,
    val selectedEntryIds: Set<String> = emptySet(),
    val undoDeletedEntryIds: List<String> = emptyList(),
    val undoArchivedEntryIds: List<String> = emptyList(),
    val scrollToNewestEntryId: String? = null,
    val pendingJump: PendingJumpRequest? = null,
    val highlightedEntryId: String? = null,
    val timelineIndex: ChatTimelineIndex = ChatTimelineIndex.EMPTY,
    val credentialWarning: String? = null,
    val errorMessage: String? = null,
) {
    val isSelectionMode: Boolean get() = selectedEntryIds.isNotEmpty()
    val undoDeletedEntryId: String? get() = undoDeletedEntryIds.singleOrNull()
    val undoArchivedEntryId: String? get() = undoArchivedEntryIds.singleOrNull()
}

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

    private val activeFilterFlow = MutableStateFlow<ChatFeedFilter>(ChatFeedFilter.All())
    private val showHiddenPlaceholdersFlow = MutableStateFlow(false)

    private val feedQueryFlow = combine(activeFilterFlow, showHiddenPlaceholdersFlow) { filter, showHidden ->
        filter to (filter is ChatFeedFilter.All && showHidden)
    }.distinctUntilChanged()

    val pagedEntries: Flow<PagingData<EntryWithDetails>> =
        feedQueryFlow
            .flatMapLatest { (filter, includeHidden) ->
                entryRepository.observePagedEntries(
                    filter = filter,
                    includeHiddenPlaceholders = includeHidden,
                )
            }
            .cachedIn(viewModelScope)

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var autoAttachedFilterTagId: String? = null
    private var autoAttachedReplyTagIds: Set<String> = emptySet()
    private var jumpRequestSeq: Long = 0L
    private var highlightClearJob: Job? = null

    init {
        observeTags()
        observeRecentTags()
        observeSettings()
        observePendingAutoTags()
        observeTodoCounts()
        observeTimelineDateBuckets()
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

    private fun observeRecentTags() {
        viewModelScope.launch {
            tagRepository.observeRecentTags(limit = RECENT_TAGS_LIMIT).collect { recent ->
                _uiState.update { state ->
                    state.copy(recentTags = recent)
                }
            }
        }
    }

    private fun observeSettings() {
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                showHiddenPlaceholdersFlow.value = settings.showHiddenPlaceholderMessages
                _uiState.update { state ->
                    state.copy(
                        weekStart = settings.weekStart,
                        showHiddenAutoTags = settings.showHiddenAutoTagsInChat,
                        showHiddenPlaceholderMessages = settings.showHiddenPlaceholderMessages,
                        offlineOnlyLinks = settings.offlineOnlyLinks,
                        tagSortOrder = settings.tagSortOrder,
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

    private fun observeTodoCounts() {
        viewModelScope.launch {
            activeFilterFlow
                .map { filter ->
                    when (filter) {
                        is ChatFeedFilter.Todos -> filter.tagFullPath
                        is ChatFeedFilter.TagSubtree -> filter.effectiveFullPath
                        is ChatFeedFilter.All, ChatFeedFilter.Archived -> null
                    }
                }
                .distinctUntilChanged()
                .flatMapLatest { tagFullPath ->
                    entryRepository.observeTodoCounts(tagFullPath)
                }
                .collect { counts ->
                    _uiState.update { state ->
                        state.copy(
                            openTodoCount = counts.openCount,
                            completedTodoCount = counts.completedCount,
                        )
                    }
                }
        }
    }

    private fun observeTimelineDateBuckets() {
        viewModelScope.launch {
            feedQueryFlow
                .flatMapLatest { (filter, includeHidden) ->
                    entryRepository.observeTimelineDateBuckets(
                        filter = filter,
                        includeHiddenPlaceholders = includeHidden,
                    )
                }
                .collect { buckets ->
                    val index = ChatTimelineIndex.fromBuckets(buckets)
                    _uiState.update { it.copy(timelineIndex = index) }
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
                is ChatFeedFilter.All -> {
                    autoAttachedFilterTagId = null
                    state.copy(
                        activeFilter = filter,
                        selectedTagIds = cleanedTags,
                        composerIsTask = false,
                        composerTaskPriority = TaskPriority.NONE,
                        selectedEntryIds = emptySet(),
                    )
                }

                is ChatFeedFilter.Todos -> {
                    val targetTagId = filter.tagId
                    autoAttachedFilterTagId = targetTagId
                    if (targetTagId != null) {
                        autoAttachedReplyTagIds = autoAttachedReplyTagIds - targetTagId
                    }
                    val nextTags = if (targetTagId != null && targetTagId !in cleanedTags) {
                        cleanedTags + targetTagId
                    } else {
                        cleanedTags
                    }
                    state.copy(
                        activeFilter = filter,
                        selectedTagIds = nextTags,
                        composerIsTask = true,
                        selectedEntryIds = emptySet(),
                    )
                }

                is ChatFeedFilter.TagSubtree -> {
                    val effectiveTagId = filter.effectiveTagId
                    autoAttachedFilterTagId = effectiveTagId
                    autoAttachedReplyTagIds = autoAttachedReplyTagIds - effectiveTagId
                    val nextTags = if (effectiveTagId in cleanedTags) {
                        cleanedTags
                    } else {
                        cleanedTags + effectiveTagId
                    }
                    val isTaskMode = filter.todoFilterMode != null
                    state.copy(
                        activeFilter = filter,
                        selectedTagIds = nextTags,
                        composerIsTask = isTaskMode,
                        composerTaskPriority = if (isTaskMode) state.composerTaskPriority else TaskPriority.NONE,
                        selectedEntryIds = emptySet(),
                    )
                }

                ChatFeedFilter.Archived -> {
                    autoAttachedFilterTagId = null
                    state.copy(
                        activeFilter = filter,
                        selectedTagIds = cleanedTags,
                        composerIsTask = false,
                        composerTaskPriority = TaskPriority.NONE,
                        selectedEntryIds = emptySet(),
                    )
                }
            }
        }
    }

    fun toggleAllUntaggedOnly() {
        val current = activeFilterFlow.value as? ChatFeedFilter.All ?: ChatFeedFilter.All()
        setFeedFilter(current.copy(untaggedOnly = !current.untaggedOnly))
    }

    fun toggleAllHideTodos() {
        val current = activeFilterFlow.value as? ChatFeedFilter.All ?: ChatFeedFilter.All()
        setFeedFilter(current.copy(hideTodos = !current.hideTodos))
    }

    fun setTodoFilterMode(mode: TodoFilterMode) {
        val current = activeFilterFlow.value
        val next = if (current is ChatFeedFilter.Todos) {
            if (current.mode == mode) return
            current.copy(mode = mode)
        } else {
            ChatFeedFilter.Todos(mode = mode)
        }
        activeFilterFlow.value = next
        _uiState.update { it.copy(activeFilter = next, composerIsTask = true, selectedEntryIds = emptySet()) }
    }

    fun setTodoPriorityFilter(priority: TaskPriority?) {
        val current = activeFilterFlow.value as? ChatFeedFilter.Todos ?: ChatFeedFilter.Todos()
        if (current.priorityFilter == priority) return
        val next = current.copy(priorityFilter = priority)
        activeFilterFlow.value = next
        _uiState.update { it.copy(activeFilter = next, composerIsTask = true, selectedEntryIds = emptySet()) }
    }

    fun toggleTodoSortOrder() {
        val current = activeFilterFlow.value as? ChatFeedFilter.Todos ?: ChatFeedFilter.Todos()
        val nextSort = when (current.sortOrder) {
            TodoSortOrder.NEWEST -> TodoSortOrder.PRIORITY
            TodoSortOrder.PRIORITY -> TodoSortOrder.NEWEST
        }
        val next = current.copy(sortOrder = nextSort)
        activeFilterFlow.value = next
        _uiState.update { it.copy(activeFilter = next, composerIsTask = true, selectedEntryIds = emptySet()) }
    }

    fun setTodoTagFilter(tag: Tag?) {
        val current = activeFilterFlow.value as? ChatFeedFilter.Todos ?: ChatFeedFilter.Todos()
        val label = tag?.let { _uiState.value.tagDisplayLabels[it.id] ?: it.leafName }
        val next = current.copy(
            tagId = tag?.id,
            tagFullPath = tag?.fullPath,
            tagDisplayLabel = label,
        )
        setFeedFilter(next)
    }

    fun selectSubTag(subTag: Tag?) {
        val current = activeFilterFlow.value as? ChatFeedFilter.TagSubtree ?: return
        val next = current.copy(
            selectedSubTagId = subTag?.id,
            selectedSubTagPath = subTag?.fullPath,
        )
        setFeedFilter(next)
    }

    fun setTagTodoFilterMode(mode: TodoFilterMode?) {
        val current = activeFilterFlow.value as? ChatFeedFilter.TagSubtree ?: return
        if (current.todoFilterMode == mode) return
        val next = current.copy(
            todoFilterMode = mode,
            priorityFilter = if (mode == null) null else current.priorityFilter,
            todoSortOrder = if (mode == null) TodoSortOrder.NEWEST else current.todoSortOrder,
        )
        setFeedFilter(next)
    }

    fun setTagTodoPriorityFilter(priority: TaskPriority?) {
        val current = activeFilterFlow.value as? ChatFeedFilter.TagSubtree ?: return
        if (current.todoFilterMode == null || current.priorityFilter == priority) return
        setFeedFilter(current.copy(priorityFilter = priority))
    }

    fun toggleTagTodoSortOrder() {
        val current = activeFilterFlow.value as? ChatFeedFilter.TagSubtree ?: return
        if (current.todoFilterMode == null) return
        val nextSort = when (current.todoSortOrder) {
            TodoSortOrder.NEWEST -> TodoSortOrder.PRIORITY
            TodoSortOrder.PRIORITY -> TodoSortOrder.NEWEST
        }
        setFeedFilter(current.copy(todoSortOrder = nextSort))
    }

    fun updateTagColor(tagId: String, colorToken: String) {
        viewModelScope.launch {
            try {
                tagRepository.updateTagColor(tagId = tagId, colorToken = colorToken)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = e.message ?: "Failed to update tag color") }
            }
        }
    }

    fun enterSelectionMode(entryId: String) {
        _uiState.update { state ->
            state.copy(selectedEntryIds = state.selectedEntryIds + entryId)
        }
    }

    fun toggleEntrySelection(entryId: String) {
        _uiState.update { state ->
            val next = if (entryId in state.selectedEntryIds) {
                state.selectedEntryIds - entryId
            } else {
                state.selectedEntryIds + entryId
            }
            state.copy(selectedEntryIds = next)
        }
    }

    fun selectEntries(entryIds: Collection<String>) {
        if (entryIds.isEmpty()) return
        _uiState.update { state ->
            state.copy(selectedEntryIds = state.selectedEntryIds + entryIds)
        }
    }

    fun clearSelection() {
        _uiState.update { it.copy(selectedEntryIds = emptySet()) }
    }

    suspend fun buildCopiedTextForSelected(
        loadedById: Map<String, EntryWithDetails>,
    ): String {
        val selectedIds = _uiState.value.selectedEntryIds
        if (selectedIds.isEmpty()) return ""

        val tagLabels = _uiState.value.tagDisplayLabels
        val resolved = selectedIds.mapNotNull { id ->
            loadedById[id] ?: entryRepository.getEntry(id)
        }.sortedWith(
            compareBy<EntryWithDetails> { it.entry.createdAtMs }.thenBy { it.entry.id },
        )

        return resolved.mapIndexed { index, item ->
            val entry = item.entry
            val prefixParts = buildList {
                when (entry.taskStatus) {
                    TaskStatus.TODO -> add("[TODO]")
                    TaskStatus.DONE -> add("[DONE]")
                    TaskStatus.NONE -> Unit
                }
                if (entry.taskPriority != TaskPriority.NONE) {
                    add("[${taskPriorityShortLabel(entry.taskPriority)}]")
                }
                item.visibleTags.forEach { entryTag ->
                    val label = tagLabels[entryTag.tagId] ?: entryTag.tag.leafName
                    add("#$label")
                }
            }
            val body = entry.bodyPlain.trim()
                .ifBlank { entry.bodyRaw.trim() }
                .ifBlank {
                    val attCount = item.attachments.size
                    if (attCount > 0) "[$attCount attachment${if (attCount == 1) "" else "s"}]" else ""
                }
            val prefix = prefixParts.joinToString(" ")
            val content = when {
                prefix.isNotEmpty() && body.isNotEmpty() -> "$prefix $body"
                body.isNotEmpty() -> body
                else -> prefix
            }
            "${index + 1}. $content"
        }.joinToString(separator = "\n")
    }

    fun batchSetTag(tagId: String, attached: Boolean) {
        val ids = _uiState.value.selectedEntryIds.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            try {
                ids.forEach { id ->
                    tagRepository.setUserTag(entryId = id, tagId = tagId, attached = attached)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = e.message ?: "Failed to update tags") }
            }
        }
    }

    fun createAndAttachBatchTag(fullPath: String) {
        val normalized = fullPath.trim()
        val ids = _uiState.value.selectedEntryIds.toList()
        if (normalized.isEmpty() || ids.isEmpty()) return
        viewModelScope.launch {
            try {
                val created = tagRepository.createOrGetTagByPath(normalized)
                ids.forEach { id ->
                    tagRepository.setUserTag(entryId = id, tagId = created.id, attached = true)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = e.message ?: "Failed to attach tag") }
            }
        }
    }

    fun batchSetTaskStatus(status: TaskStatus) {
        val ids = _uiState.value.selectedEntryIds.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            try {
                ids.forEach { id ->
                    entryRepository.setTaskStatus(entryId = id, taskStatus = status)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = e.message ?: "Failed to update task status") }
            }
        }
    }

    fun batchSetTaskPriority(priority: TaskPriority) {
        val ids = _uiState.value.selectedEntryIds.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            try {
                ids.forEach { id ->
                    entryRepository.setTaskPriority(entryId = id, taskPriority = priority)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = e.message ?: "Failed to update task priority") }
            }
        }
    }

    fun batchSetHighlighted(highlighted: Boolean) {
        val ids = _uiState.value.selectedEntryIds.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            try {
                ids.forEach { id ->
                    entryRepository.setHighlighted(entryId = id, highlighted = highlighted)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = e.message ?: "Failed to update highlight") }
            }
        }
    }

    fun batchArchive() {
        val ids = _uiState.value.selectedEntryIds.toList()
        if (ids.isEmpty()) return
        val idSet = ids.toSet()
        viewModelScope.launch {
            try {
                ids.forEach { id ->
                    entryRepository.setArchived(entryId = id, archived = true)
                }
                _uiState.update { state ->
                    val clearingReply = state.replyTarget?.entry?.id in idSet
                    val clearingEdit = state.editingEntry?.entry?.id in idSet
                    val nextSelectedTags = if (clearingReply) {
                        val cleaned = state.selectedTagIds.filterNot { it in autoAttachedReplyTagIds }
                        autoAttachedReplyTagIds = emptySet()
                        cleaned
                    } else {
                        state.selectedTagIds
                    }
                    state.copy(
                        selectedEntryIds = emptySet(),
                        undoArchivedEntryIds = ids,
                        replyTarget = if (clearingReply) null else state.replyTarget,
                        editingEntry = if (clearingEdit) null else state.editingEntry,
                        editingKeptAttachments = if (clearingEdit) emptyList() else state.editingKeptAttachments,
                        removedEditingAttachmentIds = if (clearingEdit) emptySet() else state.removedEditingAttachmentIds,
                        pendingAttachments = if (clearingEdit) emptyList() else state.pendingAttachments,
                        selectedTagIds = nextSelectedTags,
                        errorMessage = null,
                    )
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = e.message ?: "Failed to archive entries") }
            }
        }
    }

    fun batchUnarchive() {
        val ids = _uiState.value.selectedEntryIds.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            try {
                ids.forEach { id ->
                    entryRepository.setArchived(entryId = id, archived = false)
                }
                _uiState.update { state ->
                    state.copy(
                        selectedEntryIds = emptySet(),
                        errorMessage = null,
                    )
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = e.message ?: "Failed to unarchive entries") }
            }
        }
    }

    fun batchDelete() {
        val ids = _uiState.value.selectedEntryIds.toList()
        if (ids.isEmpty()) return
        val idSet = ids.toSet()
        viewModelScope.launch {
            try {
                ids.forEach { id ->
                    entryRepository.softDeleteEntry(id)
                }
                _uiState.update { state ->
                    val clearingReply = state.replyTarget?.entry?.id in idSet
                    val clearingEdit = state.editingEntry?.entry?.id in idSet
                    val nextSelectedTags = if (clearingReply) {
                        val cleaned = state.selectedTagIds.filterNot { it in autoAttachedReplyTagIds }
                        autoAttachedReplyTagIds = emptySet()
                        cleaned
                    } else {
                        state.selectedTagIds
                    }
                    state.copy(
                        selectedEntryIds = emptySet(),
                        undoDeletedEntryIds = ids,
                        replyTarget = if (clearingReply) null else state.replyTarget,
                        editingEntry = if (clearingEdit) null else state.editingEntry,
                        editingKeptAttachments = if (clearingEdit) emptyList() else state.editingKeptAttachments,
                        removedEditingAttachmentIds = if (clearingEdit) emptySet() else state.removedEditingAttachmentIds,
                        pendingAttachments = if (clearingEdit) emptyList() else state.pendingAttachments,
                        selectedTagIds = nextSelectedTags,
                        errorMessage = null,
                    )
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = e.message ?: "Failed to delete entries") }
            }
        }
    }

    fun toggleComposerTask() {
        _uiState.update { state ->
            val nextIsTask = !state.composerIsTask
            state.copy(
                composerIsTask = nextIsTask,
                composerTaskPriority = if (nextIsTask) state.composerTaskPriority else TaskPriority.NONE,
            )
        }
    }

    fun setComposerTaskPriority(priority: TaskPriority) {
        _uiState.update { state ->
            state.copy(
                composerTaskPriority = priority,
                composerIsTask = if (priority != TaskPriority.NONE) true else state.composerIsTask,
            )
        }
    }

    fun setComposerBackdate(
        epochMs: Long,
        zoneId: String,
        keepAfterSend: Boolean,
    ) {
        _uiState.update {
            it.copy(
                composerCustomTimestampMs = epochMs,
                composerCustomZoneId = zoneId,
                keepBackdateAfterSend = keepAfterSend,
            )
        }
    }

    fun clearComposerBackdate() {
        _uiState.update {
            it.copy(
                composerCustomTimestampMs = null,
                composerCustomZoneId = null,
                keepBackdateAfterSend = false,
            )
        }
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

    /**
     * Resolves an exact DB offset for jumping to [entryId] (or falling back to [localDate])
     * in the active feed filter.
     */
    fun resolveJumpTarget(entryId: String?, localDate: String?) {
        if (entryId == null && localDate == null) return
        viewModelScope.launch {
            try {
                val filter = activeFilterFlow.value
                val includeHidden = filter is ChatFeedFilter.All && showHiddenPlaceholdersFlow.value
                var resolvedEntryId: String? = null
                var offset: Int? = null

                if (entryId != null) {
                    val foundOffset = entryRepository.findEntryOffset(
                        entryId = entryId,
                        filter = filter,
                        includeHiddenPlaceholders = includeHidden,
                    )
                    if (foundOffset != null) {
                        offset = foundOffset
                        resolvedEntryId = entryId
                    }
                }
                if (offset == null && localDate != null) {
                    offset = entryRepository.findDateOffset(
                        localDate = localDate,
                        filter = filter,
                        includeHiddenPlaceholders = includeHidden,
                    )
                }

                if (offset != null) {
                    val request = PendingJumpRequest(
                        offset = offset,
                        targetEntryId = resolvedEntryId,
                        targetFilter = filter,
                        nonce = ++jumpRequestSeq,
                    )
                    _uiState.update { it.copy(pendingJump = request) }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = e.message ?: "Failed to jump to entry") }
            }
        }
    }

    /**
     * Navigates directly to a quoted reply parent. Checks the current filter first, then falls back
     * to [ChatFeedFilter.All] (switching filter if the parent lives outside the current filter).
     */
    fun jumpToReplyParent(
        entryId: String,
        onFilterChanged: (ChatFeedFilter) -> Unit = {},
    ) {
        viewModelScope.launch {
            try {
                val currentFilter = activeFilterFlow.value
                val includeHiddenCurrent = currentFilter is ChatFeedFilter.All && showHiddenPlaceholdersFlow.value
                val currentOffset = entryRepository.findEntryOffset(
                    entryId = entryId,
                    filter = currentFilter,
                    includeHiddenPlaceholders = includeHiddenCurrent,
                )
                if (currentOffset != null) {
                    val request = PendingJumpRequest(
                        offset = currentOffset,
                        targetEntryId = entryId,
                        targetFilter = currentFilter,
                        nonce = ++jumpRequestSeq,
                    )
                    _uiState.update { it.copy(pendingJump = request) }
                    return@launch
                }

                val unfilteredAll = ChatFeedFilter.All()
                if (currentFilter != unfilteredAll) {
                    val allOffset = entryRepository.findEntryOffset(
                        entryId = entryId,
                        filter = unfilteredAll,
                        includeHiddenPlaceholders = showHiddenPlaceholdersFlow.value,
                    )
                    if (allOffset != null) {
                        setFeedFilter(unfilteredAll)
                        onFilterChanged(unfilteredAll)
                        val request = PendingJumpRequest(
                            offset = allOffset,
                            targetEntryId = entryId,
                            targetFilter = unfilteredAll,
                            nonce = ++jumpRequestSeq,
                        )
                        _uiState.update { it.copy(pendingJump = request) }
                        return@launch
                    }
                }

                _uiState.update { it.copy(errorMessage = "Original message was deleted") }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = e.message ?: "Failed to jump to original message") }
            }
        }
    }

    fun consumePendingJump(nonce: Long) {
        val pending = _uiState.value.pendingJump ?: return
        if (pending.nonce != nonce) return
        _uiState.update { it.copy(pendingJump = null) }
        val targetId = pending.targetEntryId ?: return
        triggerJumpHighlight(targetId)
    }

    private fun triggerJumpHighlight(entryId: String) {
        highlightClearJob?.cancel()
        _uiState.update { it.copy(highlightedEntryId = entryId) }
        highlightClearJob = viewModelScope.launch {
            delay(JUMP_HIGHLIGHT_DURATION_MS)
            _uiState.update { state ->
                if (state.highlightedEntryId == entryId) {
                    state.copy(highlightedEntryId = null)
                } else {
                    state
                }
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

    fun setEntryTaskPriority(entryId: String, priority: TaskPriority) {
        viewModelScope.launch {
            try {
                entryRepository.setTaskPriority(entryId = entryId, taskPriority = priority)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = e.message ?: "Failed to update task priority") }
            }
        }
    }

    fun toggleHighlighted(entry: Entry) {
        viewModelScope.launch {
            try {
                entryRepository.setHighlighted(
                    entryId = entry.id,
                    highlighted = !entry.isHighlighted,
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = e.message ?: "Failed to update highlight") }
            }
        }
    }

    fun archiveEntry(entryId: String) {
        viewModelScope.launch {
            try {
                entryRepository.setArchived(entryId = entryId, archived = true)
                _uiState.update { state ->
                    val clearingReply = state.replyTarget?.entry?.id == entryId
                    val clearingEdit = state.editingEntry?.entry?.id == entryId
                    val nextSelectedTags = if (clearingReply) {
                        val cleaned = state.selectedTagIds.filterNot { it in autoAttachedReplyTagIds }
                        autoAttachedReplyTagIds = emptySet()
                        cleaned
                    } else {
                        state.selectedTagIds
                    }
                    state.copy(
                        selectedEntryIds = state.selectedEntryIds - entryId,
                        undoArchivedEntryIds = listOf(entryId),
                        replyTarget = if (clearingReply) null else state.replyTarget,
                        editingEntry = if (clearingEdit) null else state.editingEntry,
                        editingKeptAttachments = if (clearingEdit) emptyList() else state.editingKeptAttachments,
                        removedEditingAttachmentIds = if (clearingEdit) emptySet() else state.removedEditingAttachmentIds,
                        pendingAttachments = if (clearingEdit) emptyList() else state.pendingAttachments,
                        selectedTagIds = nextSelectedTags,
                        errorMessage = null,
                    )
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = e.message ?: "Failed to archive entry") }
            }
        }
    }

    fun undoArchive() {
        val archivedIds = _uiState.value.undoArchivedEntryIds
        if (archivedIds.isEmpty()) return
        _uiState.update { it.copy(undoArchivedEntryIds = emptyList()) }
        viewModelScope.launch {
            try {
                archivedIds.forEach { archivedId ->
                    entryRepository.setArchived(entryId = archivedId, archived = false)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = e.message ?: "Failed to unarchive entry") }
            }
        }
    }

    fun clearUndoArchive() {
        _uiState.update { it.copy(undoArchivedEntryIds = emptyList()) }
    }

    fun unarchiveEntry(entryId: String) {
        viewModelScope.launch {
            try {
                entryRepository.setArchived(entryId = entryId, archived = false)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = e.message ?: "Failed to unarchive entry") }
            }
        }
    }

    fun updateEntryMetadata(
        entryId: String,
        createdAtMs: Long,
        zoneId: String,
        taskStatus: TaskStatus,
        taskPriority: TaskPriority,
        isHighlighted: Boolean,
        archivedAtMs: Long?,
    ) {
        val effectivePriority = if (taskStatus == TaskStatus.NONE) TaskPriority.NONE else taskPriority
        viewModelScope.launch {
            try {
                entryRepository.updateEntryMetadata(
                    entryId = entryId,
                    createdAtMs = createdAtMs,
                    zoneId = zoneId,
                    taskStatus = taskStatus,
                    taskPriority = effectivePriority,
                    isHighlighted = isHighlighted,
                    archivedAtMs = archivedAtMs,
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = e.message ?: "Failed to update entry details") }
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
            val nextWarning = if (state.credentialWarning != null) {
                when (val check = SensitiveCredentialGuard.validate(text)) {
                    CredentialCheckResult.Allowed -> null
                    is CredentialCheckResult.Rejected -> check.message
                }
            } else {
                null
            }
            state.copy(
                composerText = text,
                composerCursor = safeCursor,
                activeTagTrigger = trigger,
                tagSuggestions = computeTagSuggestions(trigger, state.allTags),
                credentialWarning = nextWarning,
            )
        }
    }

    fun maskCredentialsInComposer() {
        _uiState.update { state ->
            val masked = SensitiveCredentialGuard.maskDetectedWithXxxx(state.composerText)
            val safeCursor = masked.length
            val trigger = messageParser.detectTagTrigger(masked, safeCursor)
            state.copy(
                composerText = masked,
                composerCursor = safeCursor,
                activeTagTrigger = trigger,
                tagSuggestions = computeTagSuggestions(trigger, state.allTags),
                credentialWarning = null,
            )
        }
    }

    fun dismissCredentialWarning() {
        _uiState.update { it.copy(credentialWarning = null) }
    }

    fun onSelectTagSuggestion(tag: Tag) {
        var editingEntryIdToAttach: String? = null
        _uiState.update { state ->
            val trigger = state.activeTagTrigger
            val editing = state.editingEntry
            if (editing != null) {
                editingEntryIdToAttach = editing.entry.id
            }

            val updatedSelected = if (editing != null) {
                state.selectedTagIds
            } else if (tag.id in state.selectedTagIds) {
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

            val (newText, newCursor) = removeInlineTriggerRange(
                text = state.composerText,
                startIndex = trigger.startIndex,
                endIndex = trigger.endIndex,
            )

            state.copy(
                composerText = newText,
                composerCursor = newCursor,
                activeTagTrigger = null,
                tagSuggestions = emptyList(),
                selectedTagIds = updatedSelected,
            )
        }
        editingEntryIdToAttach?.let { entryId ->
            setEntryTag(entryId = entryId, tagId = tag.id, attached = true)
        }
    }

    fun toggleSelectedTag(tagId: String) {
        autoAttachedReplyTagIds = autoAttachedReplyTagIds - tagId
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
                            val alreadyAttached =
                                state.pendingAttachments.any { it.sha256 == attachment.sha256 } ||
                                    state.editingKeptAttachments.any { it.sha256 == attachment.sha256 }
                            val updated = if (alreadyAttached) {
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

    fun removeEditingAttachment(attachmentId: String) {
        _uiState.update { state ->
            if (state.editingKeptAttachments.none { it.id == attachmentId }) {
                return@update state
            }
            state.copy(
                editingKeptAttachments = state.editingKeptAttachments.filterNot { it.id == attachmentId },
                removedEditingAttachmentIds = state.removedEditingAttachmentIds + attachmentId,
            )
        }
    }

    fun startReply(entry: EntryWithDetails) {
        _uiState.update { state ->
            val baseTags = state.selectedTagIds.filterNot { it in autoAttachedReplyTagIds }
            val replyTagIds = entry.visibleTags.map { it.tagId }.distinct()
            val newlyAdded = replyTagIds.filterNot { it in baseTags }
            autoAttachedReplyTagIds = newlyAdded.toSet()
            state.copy(
                replyTarget = entry,
                editingEntry = null,
                editingKeptAttachments = emptyList(),
                removedEditingAttachmentIds = emptySet(),
                selectedTagIds = baseTags + newlyAdded,
            )
        }
    }

    fun cancelReply() {
        _uiState.update { state ->
            val cleaned = state.selectedTagIds.filterNot { it in autoAttachedReplyTagIds }
            autoAttachedReplyTagIds = emptySet()
            state.copy(
                replyTarget = null,
                selectedTagIds = cleaned,
            )
        }
    }

    fun startEdit(entry: EntryWithDetails) {
        val raw = entry.entry.bodyRaw
        _uiState.update { state ->
            val cleaned = state.selectedTagIds.filterNot { it in autoAttachedReplyTagIds }
            autoAttachedReplyTagIds = emptySet()
            state.copy(
                editingEntry = entry,
                replyTarget = null,
                selectedTagIds = cleaned,
                editingKeptAttachments = entry.attachments,
                removedEditingAttachmentIds = emptySet(),
                pendingAttachments = emptyList(),
                composerText = raw,
                composerCursor = raw.length,
                activeTagTrigger = null,
                tagSuggestions = emptyList(),
                credentialWarning = null,
            )
        }
    }

    fun cancelEdit() {
        _uiState.update { state ->
            state.copy(
                editingEntry = null,
                editingKeptAttachments = emptyList(),
                removedEditingAttachmentIds = emptySet(),
                pendingAttachments = emptyList(),
                composerText = "",
                composerCursor = 0,
                activeTagTrigger = null,
                tagSuggestions = emptyList(),
                credentialWarning = null,
            )
        }
    }

    fun sendOrSave() {
        val snapshot = _uiState.value
        val editing = snapshot.editingEntry

        if (editing != null) {
            if (
                snapshot.composerText.isBlank() &&
                snapshot.editingKeptAttachments.isEmpty() &&
                snapshot.pendingAttachments.isEmpty()
            ) {
                return
            }
            val check = SensitiveCredentialGuard.validate(snapshot.composerText)
            if (check is CredentialCheckResult.Rejected) {
                _uiState.update { it.copy(credentialWarning = check.message) }
                return
            }
            viewModelScope.launch {
                try {
                    val updated = entryRepository.editEntry(
                        entryId = editing.entry.id,
                        newBodyRaw = snapshot.composerText,
                        addedAttachments = snapshot.pendingAttachments,
                        removedAttachmentIds = snapshot.removedEditingAttachmentIds,
                    )
                    autoTagScheduler.enqueueAutoTag(updated.entry.id)
                    if (updated.bookmarks.isNotEmpty() && !snapshot.offlineOnlyLinks) {
                        bookmarkTitleScheduler.enqueueTitleFetch(updated.entry.id)
                    }
                    _uiState.update { state ->
                        state.copy(
                            editingEntry = null,
                            editingKeptAttachments = emptyList(),
                            removedEditingAttachmentIds = emptySet(),
                            pendingAttachments = emptyList(),
                            composerText = "",
                            composerCursor = 0,
                            activeTagTrigger = null,
                            tagSuggestions = emptyList(),
                            credentialWarning = null,
                            errorMessage = null,
                        )
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: SensitiveCredentialException) {
                    _uiState.update { it.copy(credentialWarning = e.message) }
                } catch (e: Exception) {
                    _uiState.update { it.copy(errorMessage = e.message ?: "Failed to update entry") }
                }
            }
            return
        }

        if (snapshot.composerText.isBlank() && snapshot.pendingAttachments.isEmpty()) {
            return
        }

        val check = SensitiveCredentialGuard.validate(snapshot.composerText)
        if (check is CredentialCheckResult.Rejected) {
            _uiState.update { it.copy(credentialWarning = check.message) }
            return
        }

        viewModelScope.launch {
            try {
                val taskStatus = if (snapshot.composerIsTask) TaskStatus.TODO else TaskStatus.NONE
                val taskPriority = if (snapshot.composerIsTask) snapshot.composerTaskPriority else TaskPriority.NONE
                val entryTimestampMs = snapshot.composerCustomTimestampMs ?: System.currentTimeMillis()
                val created = entryRepository.createEntry(
                    bodyRaw = snapshot.composerText,
                    userTagIds = snapshot.selectedTagIds,
                    replyToId = snapshot.replyTarget?.entry?.id,
                    pendingAttachments = snapshot.pendingAttachments,
                    nowMs = entryTimestampMs,
                    zoneId = snapshot.composerCustomZoneId,
                    taskStatus = taskStatus,
                    taskPriority = taskPriority,
                )
                autoTagScheduler.enqueueAutoTag(created.entry.id)
                if (created.bookmarks.isNotEmpty() && !snapshot.offlineOnlyLinks) {
                    bookmarkTitleScheduler.enqueueTitleFetch(created.entry.id)
                }
                autoAttachedReplyTagIds = emptySet()
                _uiState.update { state ->
                    val defaultTagsForFilter = when (val filter = state.activeFilter) {
                        is ChatFeedFilter.TagSubtree -> listOf(filter.effectiveTagId)
                        is ChatFeedFilter.Todos -> listOfNotNull(filter.tagId)
                        else -> emptyList()
                    }
                    val defaultIsTaskForFilter = when (val filter = state.activeFilter) {
                        is ChatFeedFilter.Todos -> true
                        is ChatFeedFilter.TagSubtree -> filter.todoFilterMode != null
                        else -> false
                    }
                    val nextCustomTs = if (state.keepBackdateAfterSend && state.composerCustomTimestampMs != null) {
                        state.composerCustomTimestampMs + 60_000L
                    } else {
                        null
                    }
                    val nextCustomZone = if (nextCustomTs != null) state.composerCustomZoneId else null
                    state.copy(
                        composerText = "",
                        composerCursor = 0,
                        composerIsTask = defaultIsTaskForFilter,
                        composerTaskPriority = TaskPriority.NONE,
                        composerCustomTimestampMs = nextCustomTs,
                        composerCustomZoneId = nextCustomZone,
                        activeTagTrigger = null,
                        tagSuggestions = emptyList(),
                        selectedTagIds = defaultTagsForFilter,
                        pendingAttachments = emptyList(),
                        replyTarget = null,
                        scrollToNewestEntryId = created.entry.id,
                        credentialWarning = null,
                        errorMessage = null,
                    )
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: SensitiveCredentialException) {
                _uiState.update { it.copy(credentialWarning = e.message) }
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = e.message ?: "Failed to save entry") }
            }
        }
    }

    fun deleteTag(
        tagId: String,
        onActiveFilterUpdated: ((ChatFeedFilter) -> Unit)? = null,
        onActiveFilterCleared: () -> Unit = {},
    ) {
        val currentState = _uiState.value
        val targetTag = currentState.allTags.firstOrNull { it.id == tagId }
        val deletedIds = if (targetTag != null) {
            val prefix = "${targetTag.fullPath}/"
            currentState.allTags
                .filter { it.id == tagId || it.fullPath.startsWith(prefix, ignoreCase = true) }
                .map { it.id }
                .toSet()
        } else {
            setOf(tagId)
        }

        viewModelScope.launch {
            try {
                tagRepository.deleteTag(tagId)
                val currentFilter = activeFilterFlow.value
                var updatedFilter: ChatFeedFilter? = null
                var resetToAll = false
                var reattachTagId: String? = null

                when (currentFilter) {
                    is ChatFeedFilter.TagSubtree -> {
                        val rootWasDeleted = currentFilter.tagId in deletedIds ||
                            (targetTag != null && currentFilter.fullPath.startsWith("${targetTag.fullPath}/", ignoreCase = true))
                        val subTagWasDeleted = !rootWasDeleted &&
                            currentFilter.selectedSubTagId != null &&
                            (
                                currentFilter.selectedSubTagId in deletedIds ||
                                    (
                                        targetTag != null &&
                                            currentFilter.selectedSubTagPath?.startsWith("${targetTag.fullPath}/", ignoreCase = true) == true
                                        )
                                )
                        if (rootWasDeleted) {
                            val allFilter = ChatFeedFilter.All()
                            autoAttachedFilterTagId = null
                            activeFilterFlow.value = allFilter
                            updatedFilter = allFilter
                            resetToAll = true
                        } else if (subTagWasDeleted) {
                            val fallback = currentFilter.copy(
                                selectedSubTagId = null,
                                selectedSubTagPath = null,
                            )
                            autoAttachedFilterTagId = fallback.effectiveTagId
                            reattachTagId = fallback.effectiveTagId
                            activeFilterFlow.value = fallback
                            updatedFilter = fallback
                        }
                    }

                    is ChatFeedFilter.Todos -> {
                        val todoTagWasDeleted = currentFilter.tagId != null &&
                            (
                                currentFilter.tagId in deletedIds ||
                                    (
                                        targetTag != null &&
                                            currentFilter.tagFullPath?.startsWith("${targetTag.fullPath}/", ignoreCase = true) == true
                                        )
                                )
                        if (todoTagWasDeleted) {
                            val fallback = currentFilter.copy(
                                tagId = null,
                                tagFullPath = null,
                                tagDisplayLabel = null,
                            )
                            autoAttachedFilterTagId = null
                            activeFilterFlow.value = fallback
                            updatedFilter = fallback
                        }
                    }

                    is ChatFeedFilter.All, ChatFeedFilter.Archived -> Unit
                }

                autoAttachedReplyTagIds = autoAttachedReplyTagIds - deletedIds
                _uiState.update { state ->
                    val cleanedTags = state.selectedTagIds.filterNot { it in deletedIds }
                    val nextSelectedTags = if (reattachTagId != null && reattachTagId !in cleanedTags) {
                        cleanedTags + reattachTagId
                    } else {
                        cleanedTags
                    }
                    state.copy(
                        activeFilter = updatedFilter ?: state.activeFilter,
                        selectedTagIds = nextSelectedTags,
                        errorMessage = null,
                    )
                }
                if (updatedFilter != null) {
                    onActiveFilterUpdated?.invoke(updatedFilter)
                }
                if (resetToAll) {
                    onActiveFilterCleared()
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = e.message ?: "Failed to delete tag") }
            }
        }
    }

    fun deleteEntry(entryId: String) {
        viewModelScope.launch {
            try {
                entryRepository.softDeleteEntry(entryId)
                _uiState.update { state ->
                    val clearingReply = state.replyTarget?.entry?.id == entryId
                    val clearingEdit = state.editingEntry?.entry?.id == entryId
                    val nextSelectedTags = if (clearingReply) {
                        val cleaned = state.selectedTagIds.filterNot { it in autoAttachedReplyTagIds }
                        autoAttachedReplyTagIds = emptySet()
                        cleaned
                    } else {
                        state.selectedTagIds
                    }
                    state.copy(
                        selectedEntryIds = state.selectedEntryIds - entryId,
                        undoDeletedEntryIds = listOf(entryId),
                        replyTarget = if (clearingReply) null else state.replyTarget,
                        editingEntry = if (clearingEdit) null else state.editingEntry,
                        editingKeptAttachments = if (clearingEdit) emptyList() else state.editingKeptAttachments,
                        removedEditingAttachmentIds = if (clearingEdit) emptySet() else state.removedEditingAttachmentIds,
                        pendingAttachments = if (clearingEdit) emptyList() else state.pendingAttachments,
                        selectedTagIds = nextSelectedTags,
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
        val deletedIds = _uiState.value.undoDeletedEntryIds
        if (deletedIds.isEmpty()) return
        _uiState.update { it.copy(undoDeletedEntryIds = emptyList()) }
        viewModelScope.launch {
            try {
                deletedIds.forEach { deletedId ->
                    entryRepository.undoSoftDelete(deletedId)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = e.message ?: "Failed to restore entry") }
            }
        }
    }

    fun clearUndoDelete() {
        _uiState.update { it.copy(undoDeletedEntryIds = emptyList()) }
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

    fun toggleShowHiddenPlaceholderMessages() {
        val nextValue = !_uiState.value.showHiddenPlaceholderMessages
        showHiddenPlaceholdersFlow.value = nextValue
        _uiState.update { it.copy(showHiddenPlaceholderMessages = nextValue) }
        viewModelScope.launch {
            try {
                settingsRepository.setShowHiddenPlaceholderMessages(nextValue)
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

    fun timelinePeriodMeta(index: Int): DateDividerMeta? =
        _uiState.value.timelineIndex.monthWeekForIndex(
            index = index,
            dateWeekCalculator = dateWeekCalculator,
            weekStart = _uiState.value.weekStart,
        )

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
        private const val RECENT_TAGS_LIMIT = 6
        private const val JUMP_HIGHLIGHT_DURATION_MS = 1_500L
    }
}

/**
 * Sorts [tags] according to the persisted [TagSortOrder] using [recentTagIds] rank for
 * [TagSortOrder.RECENT], alphabetical order for [TagSortOrder.NAME_ASC], and [TagColorPreset]
 * palette order for [TagSortOrder.COLOR].
 */
internal fun sortTagsForDisplay(
    tags: List<Tag>,
    sortOrder: TagSortOrder,
    recentTagIds: List<String> = emptyList(),
): List<Tag> {
    if (tags.size <= 1) return tags
    val recentRank = recentTagIds.withIndex().associate { (index, id) -> id to index }
    return when (sortOrder) {
        TagSortOrder.RECENT, TagSortOrder.MOST_USED -> tags.sortedWith(
            compareBy<Tag> { recentRank[it.id] ?: Int.MAX_VALUE }
                .thenBy { it.leafName.lowercase() }
                .thenBy { it.fullPath.lowercase() },
        )

        TagSortOrder.NAME_ASC -> tags.sortedWith(
            compareBy<Tag> { it.leafName.lowercase() }
                .thenBy { it.fullPath.lowercase() },
        )

        TagSortOrder.COLOR -> tags.sortedWith(
            compareBy<Tag> { TagColorPreset.fromToken(it.colorToken)?.ordinal ?: Int.MAX_VALUE }
                .thenBy { it.leafName.lowercase() }
                .thenBy { it.fullPath.lowercase() },
        )
    }
}

/**
 * Removes an inline tag trigger token `[startIndex, endIndex)` from [text], collapsing any
 * redundant double spaces or leading horizontal whitespace cleanly while returning `(newText, newCursor)`.
 */
internal fun removeInlineTriggerRange(
    text: String,
    startIndex: Int,
    endIndex: Int,
): Pair<String, Int> {
    val start = startIndex.coerceIn(0, text.length)
    val end = endIndex.coerceIn(start, text.length)
    val before = text.substring(0, start)
    val after = text.substring(end)

    val beforeEndsWithNewlineOrEmpty = before.isEmpty() || before.endsWith('\n')
    val beforeEndsWithSpace = before.lastOrNull()?.let { it == ' ' || it == '\t' } == true
    val afterStartsWithSpace = after.firstOrNull()?.let { it == ' ' || it == '\t' } == true
    val afterStartsWithNewline = after.startsWith('\n')

    return when {
        beforeEndsWithNewlineOrEmpty && afterStartsWithSpace -> {
            val trimmedAfter = after.dropWhile { it == ' ' || it == '\t' }
            (before + trimmedAfter) to before.length
        }

        beforeEndsWithSpace && afterStartsWithSpace -> {
            val trimmedBefore = before.dropLastWhile { it == ' ' || it == '\t' }
            val trimmedAfter = after.dropWhile { it == ' ' || it == '\t' }
            if (trimmedBefore.isEmpty() || trimmedBefore.endsWith('\n')) {
                (trimmedBefore + trimmedAfter) to trimmedBefore.length
            } else {
                "$trimmedBefore $trimmedAfter" to (trimmedBefore.length + 1)
            }
        }

        beforeEndsWithSpace && afterStartsWithNewline -> {
            val trimmedBefore = before.dropLastWhile { it == ' ' || it == '\t' }
            (trimmedBefore + after) to trimmedBefore.length
        }

        else -> {
            (before + after) to before.length
        }
    }
}
