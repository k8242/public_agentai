package com.threadlog.feature.chat.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.threadlog.core.model.EntryWithDetails
import com.threadlog.core.model.Tag
import com.threadlog.core.model.TagRootCategory
import com.threadlog.core.model.WeekStart
import com.threadlog.core.model.contract.AutoTagScheduler
import com.threadlog.core.model.contract.BookmarkRepository
import com.threadlog.core.model.contract.BookmarkTitleScheduler
import com.threadlog.core.model.contract.DateWeekCalculator
import com.threadlog.core.model.contract.EntryRepository
import com.threadlog.core.model.contract.LeafDisambiguator
import com.threadlog.core.model.contract.MediaCompressor
import com.threadlog.core.model.contract.MediaStorage
import com.threadlog.core.model.contract.MessageParser
import com.threadlog.core.model.contract.PendingAttachment
import com.threadlog.core.model.contract.SettingsRepository
import com.threadlog.core.model.contract.TagRepository
import com.threadlog.core.model.parser.ActiveTagTrigger
import com.threadlog.core.model.parser.DateDividerMeta
import com.threadlog.core.model.parser.ParsedMessage
import com.threadlog.core.model.parser.TagTriggerKind
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ChatUiState(
    val composerText: String = "",
    val composerCursor: Int = 0,
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
    val errorMessage: String? = null,
)

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

    val pagedEntries: Flow<PagingData<EntryWithDetails>> =
        entryRepository.observePagedEntries().cachedIn(viewModelScope)

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    init {
        observeTags()
        observeSettings()
        observePendingAutoTags()
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
            val prefix = if (trigger.kind == TagTriggerKind.PERSON_AT) "@" else "#"
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
                val created = entryRepository.createEntry(
                    bodyRaw = snapshot.composerText,
                    userTagIds = snapshot.selectedTagIds,
                    replyToId = snapshot.replyTarget?.entry?.id,
                    pendingAttachments = snapshot.pendingAttachments,
                )
                autoTagScheduler.enqueueAutoTag(created.entry.id)
                if (created.bookmarks.isNotEmpty() && !snapshot.offlineOnlyLinks) {
                    bookmarkTitleScheduler.enqueueTitleFetch(created.entry.id)
                }
                _uiState.update { state ->
                    state.copy(
                        composerText = "",
                        composerCursor = 0,
                        activeTagTrigger = null,
                        tagSuggestions = emptyList(),
                        selectedTagIds = emptyList(),
                        pendingAttachments = emptyList(),
                        replyTarget = null,
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
