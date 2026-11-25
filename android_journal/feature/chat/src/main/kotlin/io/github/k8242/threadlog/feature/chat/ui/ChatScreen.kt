// Copyright (c) 2026 k8242
// SPDX-License-Identifier: MIT

package io.github.k8242.threadlog.feature.chat.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Highlight
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material.icons.outlined.VerticalAlignTop
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import io.github.k8242.threadlog.core.model.ChatFeedFilter
import io.github.k8242.threadlog.core.model.EntryWithDetails
import io.github.k8242.threadlog.core.model.Tag
import io.github.k8242.threadlog.core.model.TagColorPreset
import io.github.k8242.threadlog.core.model.TagSortOrder
import io.github.k8242.threadlog.core.model.TaskPriority
import io.github.k8242.threadlog.core.model.TaskStatus
import io.github.k8242.threadlog.core.model.TodoFilterMode
import io.github.k8242.threadlog.core.model.TodoSortOrder
import kotlinx.coroutines.launch
import kotlin.math.abs

private sealed interface TagPickerTarget {
    data object Composer : TagPickerTarget
    data class ExistingEntry(val entry: EntryWithDetails) : TagPickerTarget
    data object BatchSelected : TagPickerTarget
}

/**
 * Primary WhatsApp-style reverse-anchored journal chat screen for ThreadLog.
 *
 * Uses a single status-bar-inset compact header (48dp) with top/bottom navigation consolidated
 * into the hidable left panel so the note stream maximizes vertical space.
 */
@Composable
fun ChatScreen(
    activeFilter: ChatFeedFilter = ChatFeedFilter.All(),
    onFilterChanged: (ChatFeedFilter) -> Unit = {},
    onOpenDrawer: () -> Unit = {},
    jumpToEntryId: String? = null,
    jumpToLocalDate: String? = null,
    jumpNonce: Long = 0L,
    onOpenAutoTagReview: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenSearch: (String?) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val lazyPagingItems = viewModel.pagedEntries.collectAsLazyPagingItems()
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var fullscreenImagePath by remember { mutableStateOf<String?>(null) }
    var tagPickerTarget by remember { mutableStateOf<TagPickerTarget?>(null) }
    var detailsTargetEntry by remember { mutableStateOf<EntryWithDetails?>(null) }
    var showBackdateDialog by remember { mutableStateOf(false) }
    var showTodoTagFilterDialog by remember { mutableStateOf(false) }
    var lastHandledJumpKey by remember { mutableStateOf<String?>(null) }

    BackHandler(enabled = uiState.isSelectionMode) {
        viewModel.clearSelection()
    }

    val copySelectedEntries: () -> Unit = {
        val count = uiState.selectedEntryIds.size
        if (count > 0) {
            val loadedById = lazyPagingItems.itemSnapshotList
                .filterNotNull()
                .associateBy { it.entry.id }
            scope.launch {
                val copied = viewModel.buildCopiedTextForSelected(loadedById)
                if (copied.isNotEmpty()) {
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    clipboard?.setPrimaryClip(ClipData.newPlainText("ThreadLog", copied))
                    snackbarHostState.showSnackbar(
                        message = if (count == 1) "Copied 1 message" else "Copied $count messages",
                        duration = SnackbarDuration.Short,
                    )
                }
            }
        }
    }

    // Sync active feed filter selected in the hidable left panel.
    LaunchedEffect(activeFilter) {
        viewModel.setFeedFilter(activeFilter)
    }

    // Scroll to index 0 whenever a newly created note lands in the paging snapshot so existing
    // notes smoothly scroll upward instead of staying key-anchored over the new item.
    val newestSnapshotEntryId = lazyPagingItems.itemSnapshotList.firstOrNull()?.entry?.id
    LaunchedEffect(uiState.scrollToNewestEntryId, lazyPagingItems.itemCount, newestSnapshotEntryId) {
        val createdId = uiState.scrollToNewestEntryId ?: return@LaunchedEffect
        val snapshot = lazyPagingItems.itemSnapshotList
        val targetIndex = snapshot.indexOfFirst { it?.entry?.id == createdId }
        if (targetIndex >= 0) {
            listState.animateScrollToItem(targetIndex)
            viewModel.consumeScrollToNewest(createdId)
        }
    }

    // Trigger exact DB-offset lookup for external jumps ("Open in Chat" from Weekly, Calendar, Search, etc.).
    LaunchedEffect(jumpToEntryId, jumpToLocalDate, jumpNonce) {
        if (jumpToEntryId == null && jumpToLocalDate == null) return@LaunchedEffect
        val jumpKey = "${jumpToEntryId.orEmpty()}|${jumpToLocalDate.orEmpty()}|$jumpNonce"
        if (jumpKey != lastHandledJumpKey) {
            lastHandledJumpKey = jumpKey
            viewModel.setFeedFilter(activeFilter)
            viewModel.resolveJumpTarget(
                entryId = jumpToEntryId,
                localDate = jumpToLocalDate,
            )
        }
    }

    // Execute pending DB-offset jump once the matching filter's PagingData has loaded enough slots.
    LaunchedEffect(
        uiState.pendingJump,
        uiState.activeFilter,
        lazyPagingItems.itemCount,
        lazyPagingItems.loadState.refresh,
    ) {
        val pending = uiState.pendingJump ?: return@LaunchedEffect
        if (uiState.activeFilter != pending.targetFilter) return@LaunchedEffect
        if (lazyPagingItems.loadState.refresh is LoadState.Loading) return@LaunchedEffect
        if (lazyPagingItems.itemCount <= pending.offset) return@LaunchedEffect

        val distance = abs(pending.offset - listState.firstVisibleItemIndex)
        if (distance <= 30) {
            listState.animateScrollToItem(pending.offset)
        } else {
            listState.scrollToItem(pending.offset)
        }
        viewModel.consumePendingJump(pending.nonce)
    }

    // Undo soft-delete snackbar (supports single and multi-select batch deletes)
    LaunchedEffect(uiState.undoDeletedEntryIds) {
        val deletedIds = uiState.undoDeletedEntryIds
        if (deletedIds.isEmpty()) return@LaunchedEffect
        val message = if (deletedIds.size == 1) "Entry deleted" else "${deletedIds.size} entries deleted"
        val result = snackbarHostState.showSnackbar(
            message = message,
            actionLabel = "Undo",
            duration = SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) {
            viewModel.undoDelete()
        } else {
            if (uiState.undoDeletedEntryIds == deletedIds) {
                viewModel.clearUndoDelete()
            }
        }
    }

    // Undo archive snackbar (supports single and multi-select batch archives)
    LaunchedEffect(uiState.undoArchivedEntryIds) {
        val archivedIds = uiState.undoArchivedEntryIds
        if (archivedIds.isEmpty()) return@LaunchedEffect
        val message = if (archivedIds.size == 1) "Entry archived" else "${archivedIds.size} entries archived"
        val result = snackbarHostState.showSnackbar(
            message = message,
            actionLabel = "Undo",
            duration = SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) {
            viewModel.undoArchive()
        } else {
            if (uiState.undoArchivedEntryIds == archivedIds) {
                viewModel.clearUndoArchive()
            }
        }
    }

    // Error feedback snackbar
    LaunchedEffect(uiState.errorMessage) {
        val error = uiState.errorMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(
            message = error,
            duration = SnackbarDuration.Short,
        )
        viewModel.clearError()
    }

    // Sticky top period pill derived from the visually topmost item (largest index in reverseLayout = true)
    // with ChatTimelineIndex fallback when peeking an unloaded placeholder mid-jump.
    val stickyPeriodMeta by remember(lazyPagingItems, uiState.weekStart, uiState.timelineIndex) {
        derivedStateOf {
            val topVisibleIndex = listState.layoutInfo.visibleItemsInfo
                .maxByOrNull { it.index }
                ?.index
                ?: return@derivedStateOf null
            if (topVisibleIndex !in 0 until lazyPagingItems.itemCount) {
                return@derivedStateOf null
            }
            val topEntry = lazyPagingItems.peek(topVisibleIndex)
            if (topEntry != null) {
                viewModel.dividerMeta(topEntry.entry.createdAtMs, topEntry.entry.zoneId)
            } else {
                viewModel.timelinePeriodMeta(topVisibleIndex)
            }
        }
    }

    val totalItemCount = lazyPagingItems.itemCount
    val isAtOldest by remember(totalItemCount) {
        derivedStateOf {
            if (totalItemCount <= 1) {
                true
            } else {
                val maxVisible = listState.layoutInfo.visibleItemsInfo.maxByOrNull { it.index }?.index ?: 0
                maxVisible >= totalItemCount - 1
            }
        }
    }
    val showJumpToBeginning = totalItemCount > 1 && !isAtOldest
    val showJumpToEnd by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 2 }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (uiState.isSelectionMode) {
                    MultiSelectTopBar(
                        selectedCount = uiState.selectedEntryIds.size,
                        onClearSelection = viewModel::clearSelection,
                        onSelectVisible = {
                            val visibleIds = listState.layoutInfo.visibleItemsInfo.mapNotNull { info ->
                                if (info.index in 0 until lazyPagingItems.itemCount) {
                                    lazyPagingItems.peek(info.index)?.entry?.id
                                } else {
                                    null
                                }
                            }
                            viewModel.selectEntries(visibleIds)
                        },
                        onCopy = copySelectedEntries,
                    )
                } else {
                    CompactChatHeaderBar(
                        activeFilter = uiState.activeFilter,
                        pendingAutoTagCount = uiState.pendingAutoTagCount,
                        showHiddenPlaceholderMessages = uiState.showHiddenPlaceholderMessages,
                        showJumpToBeginning = showJumpToBeginning,
                        onOpenDrawer = onOpenDrawer,
                        onToggleShowHiddenPlaceholders = viewModel::toggleShowHiddenPlaceholderMessages,
                        onJumpToBeginning = {
                            val oldestIdx = (lazyPagingItems.itemCount - 1).coerceAtLeast(0)
                            scope.launch {
                                listState.scrollToItem(oldestIdx)
                            }
                        },
                        onClearFilter = {
                            val allFilter = ChatFeedFilter.All()
                            viewModel.setFeedFilter(allFilter)
                            onFilterChanged(allFilter)
                        },
                    )
                    when (val filter = uiState.activeFilter) {
                        is ChatFeedFilter.All -> {
                            AllNotesFilterStrip(
                                allFilter = filter,
                                onToggleUntaggedOnly = {
                                    viewModel.toggleAllUntaggedOnly()
                                    onFilterChanged(viewModel.uiState.value.activeFilter)
                                },
                                onToggleHideTodos = {
                                    viewModel.toggleAllHideTodos()
                                    onFilterChanged(viewModel.uiState.value.activeFilter)
                                },
                            )
                        }

                        is ChatFeedFilter.Todos -> {
                            val selectedTag = remember(uiState.allTags, filter.tagId) {
                                uiState.allTags.firstOrNull { it.id == filter.tagId }
                            }
                            TodoFilterStrip(
                                todosFilter = filter,
                                openCount = uiState.openTodoCount,
                                completedCount = uiState.completedTodoCount,
                                selectedTag = selectedTag,
                                onSelectMode = { mode ->
                                    viewModel.setTodoFilterMode(mode)
                                    onFilterChanged(viewModel.uiState.value.activeFilter)
                                },
                                onSelectPriority = { priority ->
                                    viewModel.setTodoPriorityFilter(priority)
                                    onFilterChanged(viewModel.uiState.value.activeFilter)
                                },
                                onToggleSortOrder = {
                                    viewModel.toggleTodoSortOrder()
                                    onFilterChanged(viewModel.uiState.value.activeFilter)
                                },
                                onOpenTagFilterPicker = { showTodoTagFilterDialog = true },
                                onClearTagFilter = {
                                    viewModel.setTodoTagFilter(null)
                                    onFilterChanged(viewModel.uiState.value.activeFilter)
                                },
                            )
                        }

                        is ChatFeedFilter.TagSubtree -> {
                            SubTagTabStrip(
                                tagFilter = filter,
                                allTags = uiState.allTags,
                                recentTags = uiState.recentTags,
                                tagSortOrder = uiState.tagSortOrder,
                                onSelectSubTag = { subTag ->
                                    viewModel.selectSubTag(subTag)
                                    onFilterChanged(viewModel.uiState.value.activeFilter)
                                },
                            )
                            TagTodoFilterStrip(
                                tagFilter = filter,
                                openCount = uiState.openTodoCount,
                                completedCount = uiState.completedTodoCount,
                                onSelectMode = { mode ->
                                    viewModel.setTagTodoFilterMode(mode)
                                    onFilterChanged(viewModel.uiState.value.activeFilter)
                                },
                                onSelectPriority = { priority ->
                                    viewModel.setTagTodoPriorityFilter(priority)
                                    onFilterChanged(viewModel.uiState.value.activeFilter)
                                },
                                onToggleSortOrder = {
                                    viewModel.toggleTagTodoSortOrder()
                                    onFilterChanged(viewModel.uiState.value.activeFilter)
                                },
                            )
                        }

                        ChatFeedFilter.Archived -> Unit
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        bottomBar = {
            if (uiState.isSelectionMode) {
                val loadedSelected = lazyPagingItems.itemSnapshotList
                    .filterNotNull()
                    .filter { it.entry.id in uiState.selectedEntryIds }
                val isArchivedSelection = uiState.activeFilter == ChatFeedFilter.Archived ||
                    (loadedSelected.isNotEmpty() && loadedSelected.all { it.entry.isArchived })
                val allLoadedHighlighted = loadedSelected.isNotEmpty() &&
                    loadedSelected.all { it.entry.isHighlighted }

                MultiSelectBottomBar(
                    isArchivedSelection = isArchivedSelection,
                    allSelectedHighlighted = allLoadedHighlighted,
                    onCopy = copySelectedEntries,
                    onOpenTags = { tagPickerTarget = TagPickerTarget.BatchSelected },
                    onSetTaskStatus = viewModel::batchSetTaskStatus,
                    onSetTaskPriority = viewModel::batchSetTaskPriority,
                    onToggleHighlight = { viewModel.batchSetHighlighted(!allLoadedHighlighted) },
                    onArchiveOrUnarchive = {
                        if (isArchivedSelection) {
                            viewModel.batchUnarchive()
                        } else {
                            viewModel.batchArchive()
                        }
                    },
                    onDelete = viewModel::batchDelete,
                )
            } else {
                ChatComposer(
                    uiState = uiState,
                    resolveAttachmentPath = viewModel::resolveAttachmentPath,
                    onTextChanged = viewModel::onComposerTextChanged,
                    onSelectTagSuggestion = viewModel::onSelectTagSuggestion,
                    onCreateTriggeredTag = viewModel::createAndInsertTriggeredTag,
                    onToggleSelectedTag = viewModel::toggleSelectedTag,
                    onToggleComposerTask = viewModel::toggleComposerTask,
                    onSetComposerTaskPriority = viewModel::setComposerTaskPriority,
                    onOpenBackdatePicker = { showBackdateDialog = true },
                    onClearComposerBackdate = viewModel::clearComposerBackdate,
                    onImageBytesReceived = viewModel::onImageBytesReceived,
                    onRemovePendingAttachment = viewModel::removePendingAttachment,
                    onRemoveEditingAttachment = viewModel::removeEditingAttachment,
                    onCancelReply = viewModel::cancelReply,
                    onCancelEdit = viewModel::cancelEdit,
                    onMaskCredentials = viewModel::maskCredentialsInComposer,
                    onDismissCredentialWarning = viewModel::dismissCredentialWarning,
                    onOpenQuickTagPicker = { tagPickerTarget = TagPickerTarget.Composer },
                    onSendOrSave = viewModel::sendOrSave,
                )
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            val isEmpty = lazyPagingItems.itemCount == 0 &&
                lazyPagingItems.loadState.refresh !is LoadState.Loading

            if (isEmpty) {
                EmptyChatState(
                    activeFilter = uiState.activeFilter,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                LazyColumn(
                    state = listState,
                    reverseLayout = true,
                    contentPadding = PaddingValues(top = 40.dp, bottom = 10.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(
                        count = lazyPagingItems.itemCount,
                        key = lazyPagingItems.itemKey { it.entry.id },
                    ) { index ->
                        val item = lazyPagingItems[index]
                        if (item == null) {
                            MessageBubblePlaceholder()
                            return@items
                        }
                        val olderItem = if (index + 1 < lazyPagingItems.itemCount) {
                            lazyPagingItems.peek(index + 1)
                        } else {
                            null
                        }

                        val currentMeta = remember(item.entry.createdAtMs, item.entry.zoneId, uiState.weekStart) {
                            viewModel.dividerMeta(item.entry.createdAtMs, item.entry.zoneId)
                        }
                        val editedLabel = remember(
                            item.entry.createdAtMs,
                            item.entry.updatedAtMs,
                            item.entry.zoneId,
                        ) {
                            ChatDividerLogic.formatEditedLabel(
                                createdAtMs = item.entry.createdAtMs,
                                updatedAtMs = item.entry.updatedAtMs,
                                zoneId = item.entry.zoneId,
                            )
                        }
                        val olderMeta = remember(
                            olderItem?.entry?.createdAtMs,
                            olderItem?.entry?.zoneId,
                            uiState.weekStart,
                        ) {
                            olderItem?.let { viewModel.dividerMeta(it.entry.createdAtMs, it.entry.zoneId) }
                        }
                        val dividerVisibility = remember(currentMeta, olderMeta) {
                            ChatDividerLogic.computeDividerVisibility(currentMeta, olderMeta)
                        }
                        val parsedMessage = remember(item.entry.bodyRaw) {
                            viewModel.parseMessage(item.entry.bodyRaw)
                        }

                        Column(modifier = Modifier.fillMaxWidth()) {
                            ChatDateDividers(
                                meta = currentMeta,
                                visibility = dividerVisibility,
                            )
                            MessageBubbleItem(
                                entryWithDetails = item,
                                parsedMessage = parsedMessage,
                                dateMeta = currentMeta,
                                editedLabel = editedLabel,
                                tagDisplayLabels = uiState.tagDisplayLabels,
                                showHiddenAutoTags = uiState.showHiddenAutoTags,
                                resolveAttachmentPath = viewModel::resolveAttachmentPath,
                                isJumpHighlighted = uiState.highlightedEntryId == item.entry.id,
                                recentTags = uiState.recentTags,
                                onToggleEntryTag = { tagId, currentlyAttached ->
                                    viewModel.setEntryTag(
                                        entryId = item.entry.id,
                                        tagId = tagId,
                                        attached = !currentlyAttached,
                                    )
                                },
                                isSelectionMode = uiState.isSelectionMode,
                                isSelected = item.entry.id in uiState.selectedEntryIds,
                                onEnterSelectionMode = { viewModel.enterSelectionMode(item.entry.id) },
                                onToggleSelection = { viewModel.toggleEntrySelection(item.entry.id) },
                                onReply = { viewModel.startReply(item) },
                                onEdit = { viewModel.startEdit(item) },
                                onManageTags = { tagPickerTarget = TagPickerTarget.ExistingEntry(item) },
                                onToggleTask = { viewModel.toggleNoteTask(item.entry) },
                                onToggleTaskDone = { viewModel.toggleTaskCompleted(item.entry) },
                                onSetTaskPriority = { priority ->
                                    viewModel.setEntryTaskPriority(item.entry.id, priority)
                                },
                                onToggleHighlight = { viewModel.toggleHighlighted(item.entry) },
                                onToggleArchive = {
                                    if (item.entry.isArchived) {
                                        viewModel.unarchiveEntry(item.entry.id)
                                    } else {
                                        viewModel.archiveEntry(item.entry.id)
                                    }
                                },
                                onOpenDetails = { detailsTargetEntry = item },
                                onReplyPreviewClick = { parentEntryId ->
                                    viewModel.jumpToReplyParent(
                                        entryId = parentEntryId,
                                        onFilterChanged = onFilterChanged,
                                    )
                                },
                                onToggleReaction = { emoji -> viewModel.toggleReaction(item.entry.id, emoji) },
                                onDelete = { viewModel.deleteEntry(item.entry.id) },
                                onToggleBookmarkCollapsed = viewModel::toggleBookmarkCollapsed,
                                onPromoteAutoTag = { tagId ->
                                    viewModel.promoteAutoTag(item.entry.id, tagId)
                                },
                                onRejectAutoTag = { tagId ->
                                    viewModel.rejectAutoTag(item.entry.id, tagId)
                                },
                                onImageClick = { filePath -> fullscreenImagePath = filePath },
                                onTagClick = { target ->
                                    val filter = ChatFeedFilter.TagSubtree(
                                        tagId = target.tagId,
                                        fullPath = target.fullPath,
                                        displayLabel = target.displayLabel,
                                    )
                                    viewModel.setFeedFilter(filter)
                                    onFilterChanged(filter)
                                },
                            )
                        }
                    }
                }

                // Floating sticky Month · Week pill at top of the feed
                stickyPeriodMeta?.let { meta ->
                    StickyPeriodPill(
                        monthLabel = meta.monthLabel,
                        weekLabel = meta.weekLabel,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 6.dp),
                    )
                }

                // Google Photos-style right-edge dated side scrollbar
                ChatTimelineScrubber(
                    listState = listState,
                    timelineIndex = uiState.timelineIndex,
                    totalCount = totalItemCount,
                    modifier = Modifier.align(Alignment.CenterEnd),
                )

                // Jump to End (newest, index 0) floating button when scrolled up into history
                if (showJumpToEnd) {
                    SmallFloatingActionButton(
                        onClick = {
                            scope.launch {
                                if (listState.firstVisibleItemIndex <= 30) {
                                    listState.animateScrollToItem(0)
                                } else {
                                    listState.scrollToItem(0)
                                }
                            }
                        },
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 16.dp, bottom = 12.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.KeyboardArrowDown,
                            contentDescription = "Jump to latest",
                        )
                    }
                }
            }

            // Dedicated left-edge swipe strip to open the hidable panel without conflicting with
            // message bubble swipe-to-reply gestures.
            LeftEdgeDrawerSwipeStrip(
                onOpenDrawer = onOpenDrawer,
                modifier = Modifier.align(Alignment.CenterStart),
            )
        }
    }

    // Fullscreen attachment preview dialog
    fullscreenImagePath?.let { path ->
        FullscreenImageDialog(
            filePath = path,
            onDismiss = { fullscreenImagePath = null },
        )
    }

    // Backdate composer dialog (date, time, timezone)
    if (showBackdateDialog) {
        BackdateComposerDialog(
            initialTimestampMs = uiState.composerCustomTimestampMs,
            initialZoneId = uiState.composerCustomZoneId,
            initialKeepAfterSend = uiState.keepBackdateAfterSend,
            onApply = { epochMs, zoneId, keepAfterSend ->
                viewModel.setComposerBackdate(epochMs, zoneId, keepAfterSend)
                showBackdateDialog = false
            },
            onResetToNow = {
                viewModel.clearComposerBackdate()
                showBackdateDialog = false
            },
            onDismiss = { showBackdateDialog = false },
        )
    }

    // Tag filter picker dialog for TODOs view
    val activeTodosFilter = uiState.activeFilter as? ChatFeedFilter.Todos
    if (showTodoTagFilterDialog && activeTodosFilter != null) {
        TodoTagFilterDialog(
            allTags = uiState.allTags,
            recentTags = uiState.recentTags,
            tagDisplayLabels = uiState.tagDisplayLabels,
            tagSortOrder = uiState.tagSortOrder,
            selectedTagId = activeTodosFilter.tagId,
            onSelectTag = { tag ->
                viewModel.setTodoTagFilter(tag)
                onFilterChanged(viewModel.uiState.value.activeFilter)
                showTodoTagFilterDialog = false
            },
            onDismiss = { showTodoTagFilterDialog = false },
        )
    }

    // Extra Message Details sheet (view & edit metadata including timezone)
    detailsTargetEntry?.let { target ->
        val liveEntry = lazyPagingItems.itemSnapshotList
            .firstOrNull { it?.entry?.id == target.entry.id }
            ?: target
        MessageDetailsSheet(
            entryWithDetails = liveEntry,
            onSave = { createdAtMs, zoneId, taskStatus, taskPriority, isHighlighted, archivedAtMs ->
                viewModel.updateEntryMetadata(
                    entryId = liveEntry.entry.id,
                    createdAtMs = createdAtMs,
                    zoneId = zoneId,
                    taskStatus = taskStatus,
                    taskPriority = taskPriority,
                    isHighlighted = isHighlighted,
                    archivedAtMs = archivedAtMs,
                )
                detailsTargetEntry = null
            },
            onDismiss = { detailsTargetEntry = null },
        )
    }

    // Quick-Tag Picker modal bottom sheet (for composer, existing entry, or multi-select batch)
    when (val target = tagPickerTarget) {
        null -> Unit

        TagPickerTarget.Composer -> {
            QuickTagPickerSheet(
                title = "Tags for new entry",
                allTags = uiState.allTags,
                tagDisplayLabels = uiState.tagDisplayLabels,
                selectedTagIds = uiState.selectedTagIds.toSet(),
                recentTags = uiState.recentTags,
                tagSortOrder = uiState.tagSortOrder,
                onToggleTag = { tagId, _ -> viewModel.toggleSelectedTag(tagId) },
                onCreateTagPath = viewModel::createAndSelectTag,
                onDeleteTag = { tagId ->
                    viewModel.deleteTag(
                        tagId = tagId,
                        onActiveFilterUpdated = onFilterChanged,
                    )
                },
                onUpdateTagColor = viewModel::updateTagColor,
                onDismiss = { tagPickerTarget = null },
            )
        }

        is TagPickerTarget.ExistingEntry -> {
            // Re-read the latest visible tag IDs from the paging snapshot if available
            val liveEntry = lazyPagingItems.itemSnapshotList
                .firstOrNull { it?.entry?.id == target.entry.entry.id }
                ?: target.entry
            val attachedIds = liveEntry.visibleTags.map { it.tagId }.toSet()

            QuickTagPickerSheet(
                title = "Edit entry tags",
                allTags = uiState.allTags,
                tagDisplayLabels = uiState.tagDisplayLabels,
                selectedTagIds = attachedIds,
                recentTags = uiState.recentTags,
                tagSortOrder = uiState.tagSortOrder,
                onToggleTag = { tagId, currentlySelected ->
                    viewModel.setEntryTag(
                        entryId = liveEntry.entry.id,
                        tagId = tagId,
                        attached = !currentlySelected,
                    )
                },
                onCreateTagPath = { fullPath ->
                    viewModel.createAndAttachEntryTag(liveEntry.entry.id, fullPath)
                },
                onDeleteTag = { tagId ->
                    viewModel.deleteTag(
                        tagId = tagId,
                        onActiveFilterUpdated = onFilterChanged,
                    )
                },
                onUpdateTagColor = viewModel::updateTagColor,
                onDismiss = { tagPickerTarget = null },
            )
        }

        TagPickerTarget.BatchSelected -> {
            val loadedSelected = lazyPagingItems.itemSnapshotList
                .filterNotNull()
                .filter { it.entry.id in uiState.selectedEntryIds }
            val commonTagIds = if (loadedSelected.isEmpty()) {
                emptySet()
            } else {
                loadedSelected
                    .map { entry -> entry.visibleTags.map { it.tagId }.toSet() }
                    .reduce { acc, ids -> acc intersect ids }
            }

            QuickTagPickerSheet(
                title = "Tags for ${uiState.selectedEntryIds.size} selected",
                allTags = uiState.allTags,
                tagDisplayLabels = uiState.tagDisplayLabels,
                selectedTagIds = commonTagIds,
                recentTags = uiState.recentTags,
                tagSortOrder = uiState.tagSortOrder,
                onToggleTag = { tagId, currentlySelected ->
                    viewModel.batchSetTag(
                        tagId = tagId,
                        attached = !currentlySelected,
                    )
                },
                onCreateTagPath = viewModel::createAndAttachBatchTag,
                onDeleteTag = { tagId ->
                    viewModel.deleteTag(
                        tagId = tagId,
                        onActiveFilterUpdated = onFilterChanged,
                    )
                },
                onUpdateTagColor = viewModel::updateTagColor,
                onDismiss = { tagPickerTarget = null },
            )
        }
    }
}

@Composable
private fun CompactChatHeaderBar(
    activeFilter: ChatFeedFilter,
    pendingAutoTagCount: Int,
    showHiddenPlaceholderMessages: Boolean,
    showJumpToBeginning: Boolean,
    onOpenDrawer: () -> Unit,
    onToggleShowHiddenPlaceholders: () -> Unit,
    onJumpToBeginning: () -> Unit,
    onClearFilter: () -> Unit,
) {
    val title = when (activeFilter) {
        is ChatFeedFilter.All -> "ThreadLog"
        is ChatFeedFilter.Todos -> "TODOs"
        ChatFeedFilter.Archived -> "Archived"
        is ChatFeedFilter.TagSubtree -> when {
            activeFilter.fullPath.startsWith("people/", ignoreCase = true) ||
                activeFilter.fullPath.equals("people", ignoreCase = true) -> "@${activeFilter.displayLabel}"
            activeFilter.fullPath.startsWith("projects/", ignoreCase = true) ||
                activeFilter.fullPath.equals("projects", ignoreCase = true) -> "+${activeFilter.displayLabel}"
            else -> "#${activeFilter.displayLabel}"
        }
    }
    val subtitle = when (activeFilter) {
        is ChatFeedFilter.All -> null
        is ChatFeedFilter.Todos -> activeFilter.tagFullPath ?: "Task notes"
        ChatFeedFilter.Archived -> "Archived notes"
        is ChatFeedFilter.TagSubtree -> activeFilter.effectiveFullPath
    }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                IconButton(onClick = onOpenDrawer) {
                    if (pendingAutoTagCount > 0) {
                        BadgedBox(badge = { Badge() }) {
                            Icon(
                                imageVector = Icons.Outlined.Menu,
                                contentDescription = "Open navigation panel",
                            )
                        }
                    } else {
                        Icon(
                            imageVector = Icons.Outlined.Menu,
                            contentDescription = "Open navigation panel",
                        )
                    }
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (subtitle != null) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                if (activeFilter is ChatFeedFilter.All) {
                    IconButton(onClick = onToggleShowHiddenPlaceholders) {
                        Icon(
                            imageVector = if (showHiddenPlaceholderMessages) {
                                Icons.Outlined.Visibility
                            } else {
                                Icons.Outlined.VisibilityOff
                            },
                            contentDescription = if (showHiddenPlaceholderMessages) {
                                "Hide omitted/deleted messages"
                            } else {
                                "Show hidden messages"
                            },
                            tint = if (showHiddenPlaceholderMessages) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }

                if (showJumpToBeginning) {
                    IconButton(onClick = onJumpToBeginning) {
                        Icon(
                            imageVector = Icons.Outlined.VerticalAlignTop,
                            contentDescription = "Jump to oldest",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }

                if (activeFilter !is ChatFeedFilter.All) {
                    TextButton(onClick = onClearFilter) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = " All",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun MultiSelectTopBar(
    selectedCount: Int,
    onClearSelection: () -> Unit,
    onSelectVisible: () -> Unit,
    onCopy: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        tonalElevation = 3.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                IconButton(onClick = onClearSelection) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = "Exit selection mode",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                Text(
                    text = "$selectedCount selected",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onSelectVisible) {
                    Text(
                        text = "Select visible",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                TextButton(onClick = onCopy) {
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = " Copy",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun MultiSelectBottomBar(
    isArchivedSelection: Boolean,
    allSelectedHighlighted: Boolean,
    onCopy: () -> Unit,
    onOpenTags: () -> Unit,
    onSetTaskStatus: (TaskStatus) -> Unit,
    onSetTaskPriority: (TaskPriority) -> Unit,
    onToggleHighlight: () -> Unit,
    onArchiveOrUnarchive: () -> Unit,
    onDelete: () -> Unit,
) {
    var todoMenuExpanded by remember { mutableStateOf(false) }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp,
        shadowElevation = 4.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars),
        ) {
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BatchActionButton(
                    icon = Icons.Outlined.ContentCopy,
                    label = "Copy",
                    onClick = onCopy,
                )
                BatchActionButton(
                    icon = Icons.AutoMirrored.Outlined.Label,
                    label = "Tags",
                    onClick = onOpenTags,
                )
                Box {
                    BatchActionButton(
                        icon = Icons.Outlined.TaskAlt,
                        label = "TODO",
                        onClick = { todoMenuExpanded = true },
                    )
                    DropdownMenu(
                        expanded = todoMenuExpanded,
                        onDismissRequest = { todoMenuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Mark as TODO") },
                            onClick = {
                                todoMenuExpanded = false
                                onSetTaskStatus(TaskStatus.TODO)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Mark Done") },
                            onClick = {
                                todoMenuExpanded = false
                                onSetTaskStatus(TaskStatus.DONE)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Remove TODO") },
                            onClick = {
                                todoMenuExpanded = false
                                onSetTaskStatus(TaskStatus.NONE)
                            },
                        )
                        HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
                        listOf(
                            TaskPriority.HIGH to "High priority",
                            TaskPriority.MEDIUM to "Medium priority",
                            TaskPriority.LOW to "Low priority",
                            TaskPriority.NONE to "No priority",
                        ).forEach { (priority, label) ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = label,
                                        color = if (priority == TaskPriority.NONE) {
                                            MaterialTheme.colorScheme.onSurface
                                        } else {
                                            taskPriorityColor(priority)
                                        },
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Outlined.Flag,
                                        contentDescription = null,
                                        tint = if (priority == TaskPriority.NONE) {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        } else {
                                            taskPriorityColor(priority)
                                        },
                                        modifier = Modifier.size(18.dp),
                                    )
                                },
                                onClick = {
                                    todoMenuExpanded = false
                                    onSetTaskPriority(priority)
                                },
                            )
                        }
                    }
                }
                BatchActionButton(
                    icon = Icons.Outlined.Highlight,
                    label = if (allSelectedHighlighted) "Unhighlight" else "Highlight",
                    onClick = onToggleHighlight,
                )
                BatchActionButton(
                    icon = if (isArchivedSelection) Icons.Outlined.Unarchive else Icons.Outlined.Archive,
                    label = if (isArchivedSelection) "Unarchive" else "Archive",
                    onClick = onArchiveOrUnarchive,
                )
                BatchActionButton(
                    icon = Icons.Outlined.Delete,
                    label = "Delete",
                    tint = MaterialTheme.colorScheme.error,
                    onClick = onDelete,
                )
            }
        }
    }
}

@Composable
private fun BatchActionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.onSurface,
) {
    Column(
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            maxLines = 1,
        )
    }
}

@Composable
private fun AllNotesFilterStrip(
    allFilter: ChatFeedFilter.All,
    onToggleUntaggedOnly: () -> Unit,
    onToggleHideTodos: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = allFilter.untaggedOnly,
                    onClick = onToggleUntaggedOnly,
                    label = {
                        Text(
                            text = "Untagged only",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                )
                FilterChip(
                    selected = allFilter.hideTodos,
                    onClick = onToggleHideTodos,
                    label = {
                        Text(
                            text = "Hide TODOs",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                )
            }
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubTagTabStrip(
    tagFilter: ChatFeedFilter.TagSubtree,
    allTags: List<Tag>,
    recentTags: List<Tag>,
    tagSortOrder: TagSortOrder,
    onSelectSubTag: (Tag?) -> Unit,
) {
    val recentTagIds = remember(recentTags) { recentTags.map { it.id } }
    val directChildren = remember(allTags, tagFilter.tagId, tagSortOrder, recentTagIds) {
        sortTagsForDisplay(
            tags = allTags.filter { it.parentId == tagFilter.tagId },
            sortOrder = tagSortOrder,
            recentTagIds = recentTagIds,
        )
    }
    if (directChildren.isEmpty()) return

    val activeDirectChild = remember(directChildren, tagFilter.selectedSubTagId, tagFilter.selectedSubTagPath) {
        val selectedSubPath = tagFilter.selectedSubTagPath
        directChildren.firstOrNull { child ->
            child.id == tagFilter.selectedSubTagId ||
                (
                    selectedSubPath != null &&
                        selectedSubPath.startsWith("${child.fullPath}/", ignoreCase = true)
                    )
        }
    }
    val selectedTabIndex = if (activeDirectChild == null) {
        0
    } else {
        directChildren.indexOfFirst { it.id == activeDirectChild.id } + 1
    }

    val nestedChildren = remember(allTags, activeDirectChild?.id, tagSortOrder, recentTagIds) {
        val parentId = activeDirectChild?.id ?: return@remember emptyList()
        sortTagsForDisplay(
            tags = allTags.filter { it.parentId == parentId },
            sortOrder = tagSortOrder,
            recentTagIds = recentTagIds,
        )
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        SecondaryScrollableTabRow(
            selectedTabIndex = selectedTabIndex,
            edgePadding = 12.dp,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            Tab(
                selected = selectedTabIndex == 0,
                onClick = { onSelectSubTag(null) },
                text = {
                    Text(
                        text = "All",
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                    )
                },
            )
            directChildren.forEachIndexed { index, child ->
                val tabIdx = index + 1
                Tab(
                    selected = selectedTabIndex == tabIdx,
                    onClick = { onSelectSubTag(child) },
                    text = {
                        Text(
                            text = child.leafName,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                )
            }
        }

        if (activeDirectChild != null && nestedChildren.isNotEmpty()) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilterChip(
                        selected = tagFilter.selectedSubTagId == activeDirectChild.id,
                        onClick = { onSelectSubTag(activeDirectChild) },
                        label = {
                            Text(
                                text = "All ${activeDirectChild.leafName}",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        },
                    )
                    nestedChildren.forEach { grandchild ->
                        val isSelected = tagFilter.selectedSubTagId == grandchild.id
                        val (chipBg, chipFg) = tagColorPair(grandchild.colorToken, grandchild.rootCategory)
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                onSelectSubTag(if (isSelected) activeDirectChild else grandchild)
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = chipBg,
                                selectedLabelColor = chipFg,
                            ),
                            label = {
                                Text(
                                    text = grandchild.leafName,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TagTodoFilterStrip(
    tagFilter: ChatFeedFilter.TagSubtree,
    openCount: Int,
    completedCount: Int,
    onSelectMode: (TodoFilterMode?) -> Unit,
    onSelectPriority: (TaskPriority?) -> Unit,
    onToggleSortOrder: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = tagFilter.todoFilterMode == null,
                    onClick = { onSelectMode(null) },
                    label = { Text("All notes", style = MaterialTheme.typography.labelSmall) },
                )
                FilterChip(
                    selected = tagFilter.todoFilterMode == TodoFilterMode.OPEN,
                    onClick = { onSelectMode(TodoFilterMode.OPEN) },
                    label = { Text("Open ($openCount)", style = MaterialTheme.typography.labelSmall) },
                )
                FilterChip(
                    selected = tagFilter.todoFilterMode == TodoFilterMode.DONE,
                    onClick = { onSelectMode(TodoFilterMode.DONE) },
                    label = { Text("Done ($completedCount)", style = MaterialTheme.typography.labelSmall) },
                )
                FilterChip(
                    selected = tagFilter.todoFilterMode == TodoFilterMode.ALL,
                    onClick = { onSelectMode(TodoFilterMode.ALL) },
                    label = { Text("All TODOs (${openCount + completedCount})", style = MaterialTheme.typography.labelSmall) },
                )
                if (tagFilter.todoFilterMode != null) {
                    FilterChip(
                        selected = tagFilter.todoSortOrder == TodoSortOrder.PRIORITY,
                        onClick = onToggleSortOrder,
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.Sort,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                            )
                        },
                        label = {
                            Text(
                                text = if (tagFilter.todoSortOrder == TodoSortOrder.PRIORITY) {
                                    "Sort: Priority"
                                } else {
                                    "Sort: Newest"
                                },
                                style = MaterialTheme.typography.labelSmall,
                            )
                        },
                    )
                }
            }

            if (tagFilter.todoFilterMode != null) {
                PriorityFilterChipsRow(
                    selectedPriority = tagFilter.priorityFilter,
                    onSelectPriority = onSelectPriority,
                )
            }
        }
    }
}

@Composable
private fun TodoFilterStrip(
    todosFilter: ChatFeedFilter.Todos,
    openCount: Int,
    completedCount: Int,
    selectedTag: Tag?,
    onSelectMode: (TodoFilterMode) -> Unit,
    onSelectPriority: (TaskPriority?) -> Unit,
    onToggleSortOrder: () -> Unit,
    onOpenTagFilterPicker: () -> Unit,
    onClearTagFilter: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = todosFilter.mode == TodoFilterMode.OPEN,
                    onClick = { onSelectMode(TodoFilterMode.OPEN) },
                    label = { Text("Open ($openCount)", style = MaterialTheme.typography.labelSmall) },
                )
                FilterChip(
                    selected = todosFilter.mode == TodoFilterMode.DONE,
                    onClick = { onSelectMode(TodoFilterMode.DONE) },
                    label = { Text("Done ($completedCount)", style = MaterialTheme.typography.labelSmall) },
                )
                FilterChip(
                    selected = todosFilter.mode == TodoFilterMode.ALL,
                    onClick = { onSelectMode(TodoFilterMode.ALL) },
                    label = { Text("All (${openCount + completedCount})", style = MaterialTheme.typography.labelSmall) },
                )
                FilterChip(
                    selected = todosFilter.sortOrder == TodoSortOrder.PRIORITY,
                    onClick = onToggleSortOrder,
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.Sort,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                        )
                    },
                    label = {
                        Text(
                            text = if (todosFilter.sortOrder == TodoSortOrder.PRIORITY) {
                                "Sort: Priority"
                            } else {
                                "Sort: Newest"
                            },
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                )
                if (todosFilter.tagId == null) {
                    FilterChip(
                        selected = false,
                        onClick = onOpenTagFilterPicker,
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.Label,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                            )
                        },
                        label = {
                            Text(
                                text = "Tag: All",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        },
                    )
                } else {
                    val (chipBg, chipFg) = tagColorPair(
                        colorToken = selectedTag?.colorToken ?: TagColorPreset.DEFAULT.token,
                        rootCategory = selectedTag?.rootCategory,
                    )
                    val label = todosFilter.tagDisplayLabel ?: selectedTag?.leafName ?: "Tag"
                    FilterChip(
                        selected = true,
                        onClick = onOpenTagFilterPicker,
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = chipBg,
                            selectedLabelColor = chipFg,
                            selectedTrailingIconColor = chipFg,
                        ),
                        label = {
                            Text(
                                text = "#$label",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                        },
                        trailingIcon = {
                            Icon(
                                imageVector = Icons.Outlined.Close,
                                contentDescription = "Clear tag filter",
                                modifier = Modifier
                                    .size(14.dp)
                                    .clickable(onClick = onClearTagFilter),
                            )
                        },
                    )
                }
            }

            PriorityFilterChipsRow(
                selectedPriority = todosFilter.priorityFilter,
                onSelectPriority = onSelectPriority,
            )
        }
    }
}

@Composable
private fun PriorityFilterChipsRow(
    selectedPriority: TaskPriority?,
    onSelectPriority: (TaskPriority?) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf<Pair<TaskPriority?, String>>(
            null to "All",
            TaskPriority.HIGH to "High",
            TaskPriority.MEDIUM to "Med",
            TaskPriority.LOW to "Low",
            TaskPriority.NONE to "None",
        ).forEach { (priority, label) ->
            val isSelected = selectedPriority == priority
            val accentColor = priority?.takeIf { it != TaskPriority.NONE }?.let { taskPriorityColor(it) }
            FilterChip(
                selected = isSelected,
                onClick = { onSelectPriority(priority) },
                label = {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        color = accentColor ?: MaterialTheme.colorScheme.onSurface,
                    )
                },
            )
        }
    }
}

@Composable
private fun TodoTagFilterDialog(
    allTags: List<Tag>,
    recentTags: List<Tag>,
    tagDisplayLabels: Map<String, String>,
    tagSortOrder: TagSortOrder,
    selectedTagId: String?,
    onSelectTag: (Tag?) -> Unit,
    onDismiss: () -> Unit,
) {
    val recentTagIds = remember(recentTags) { recentTags.map { it.id } }
    val sortedTags = remember(allTags, tagSortOrder, recentTagIds) {
        sortTagsForDisplay(allTags, tagSortOrder, recentTagIds)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Filter TODOs by tag") },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                item(key = "all_tags") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectTag(null) }
                            .padding(horizontal = 8.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "All tags",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (selectedTagId == null) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.weight(1f),
                        )
                        if (selectedTagId == null) {
                            Icon(
                                imageVector = Icons.Outlined.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
                items(sortedTags, key = { it.id }) { tag ->
                    val isSelected = tag.id == selectedTagId
                    val label = tagDisplayLabels[tag.id] ?: tag.leafName
                    val (chipBg, chipFg) = tagColorPair(tag.colorToken, tag.rootCategory)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectTag(tag) }
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Surface(
                            color = chipBg,
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelMedium,
                                color = chipFg,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                        Text(
                            text = tag.fullPath,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Outlined.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
    )
}

@Composable
private fun LeftEdgeDrawerSwipeStrip(
    onOpenDrawer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val openThresholdPx = remember(density) { with(density) { 36.dp.toPx() } }
    var accumulatedDrag by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(20.dp)
            .pointerInput(onOpenDrawer) {
                detectHorizontalDragGestures(
                    onDragStart = { accumulatedDrag = 0f },
                    onDragEnd = { accumulatedDrag = 0f },
                    onDragCancel = { accumulatedDrag = 0f },
                    onHorizontalDrag = { _, dragAmount ->
                        if (dragAmount > 0f) {
                            accumulatedDrag += dragAmount
                            if (accumulatedDrag >= openThresholdPx) {
                                accumulatedDrag = 0f
                                onOpenDrawer()
                            }
                        }
                    },
                )
            },
    )
}

@Composable
private fun EmptyChatState(
    activeFilter: ChatFeedFilter,
    modifier: Modifier = Modifier,
) {
    val (title, subtitle) = when (activeFilter) {
        is ChatFeedFilter.All -> when {
            activeFilter.untaggedOnly && activeFilter.hideTodos -> {
                "No untagged non-TODO notes" to
                    "Turn off \"Untagged only\" or \"Hide TODOs\" above to view more notes."
            }

            activeFilter.untaggedOnly -> {
                "No untagged notes" to
                    "Every note currently has at least one tag. Turn off \"Untagged only\" above to view all notes."
            }

            activeFilter.hideTodos -> {
                "No non-TODO notes" to
                    "All matching notes are TODOs. Turn off \"Hide TODOs\" above to include them."
            }

            else -> {
                "Your ThreadLog is ready" to
                    "Capture thoughts, links, and photos like messaging yourself. Use @people and #tags anytime."
            }
        }

        ChatFeedFilter.Archived -> {
            "No archived notes" to
                "Archive notes from any message's menu to tuck them away here."
        }

        is ChatFeedFilter.Todos -> {
            val tagSuffix = activeFilter.tagDisplayLabel?.let { " in #$it" }.orEmpty()
            when (activeFilter.mode) {
                TodoFilterMode.OPEN -> {
                    "No open TODOs$tagSuffix" to
                        "Write a note below or use any note's menu to mark it as a TODO."
                }

                TodoFilterMode.DONE -> {
                    "No completed TODOs$tagSuffix" to
                        "Check off open TODOs and they will appear here."
                }

                TodoFilterMode.ALL -> {
                    "No TODOs$tagSuffix" to
                        "Toggle the checkbox in the composer or mark any note as a TODO."
                }
            }
        }

        is ChatFeedFilter.TagSubtree -> {
            val activeLabel = activeFilter.selectedSubTagPath?.substringAfterLast('/')
                ?: activeFilter.displayLabel
            when (activeFilter.todoFilterMode) {
                TodoFilterMode.OPEN -> {
                    "No open TODOs in $activeLabel" to
                        "Write a TODO below — it will automatically be tagged with ${activeFilter.effectiveFullPath}."
                }

                TodoFilterMode.DONE -> {
                    "No completed TODOs in $activeLabel" to
                        "Check off open TODOs in ${activeFilter.effectiveFullPath} and they will appear here."
                }

                TodoFilterMode.ALL -> {
                    "No TODOs in $activeLabel" to
                        "Write a TODO below or switch back to \"All notes\" above."
                }

                null -> {
                    "No notes in $activeLabel" to
                        "Write a note below — it will automatically be tagged with ${activeFilter.effectiveFullPath}."
                }
            }
        }
    }

    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
