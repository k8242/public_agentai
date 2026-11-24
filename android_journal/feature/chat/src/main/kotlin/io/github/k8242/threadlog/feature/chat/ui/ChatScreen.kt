// Copyright (c) 2026 k8242
// SPDX-License-Identifier: MIT

package io.github.k8242.threadlog.feature.chat.ui

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.VerticalAlignTop
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
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
import androidx.compose.ui.input.pointer.pointerInput
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
import io.github.k8242.threadlog.core.model.TaskPriority
import io.github.k8242.threadlog.core.model.TodoFilterMode
import io.github.k8242.threadlog.core.model.TodoSortOrder
import kotlinx.coroutines.launch
import kotlin.math.abs

private sealed interface TagPickerTarget {
    data object Composer : TagPickerTarget
    data class ExistingEntry(val entry: EntryWithDetails) : TagPickerTarget
}

/**
 * Primary WhatsApp-style reverse-anchored journal chat screen for ThreadLog.
 *
 * Uses a single status-bar-inset compact header (48dp) with top/bottom navigation consolidated
 * into the hidable left panel so the note stream maximizes vertical space.
 */
@Composable
fun ChatScreen(
    activeFilter: ChatFeedFilter = ChatFeedFilter.All,
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

    var fullscreenImagePath by remember { mutableStateOf<String?>(null) }
    var tagPickerTarget by remember { mutableStateOf<TagPickerTarget?>(null) }
    var detailsTargetEntry by remember { mutableStateOf<EntryWithDetails?>(null) }
    var showBackdateDialog by remember { mutableStateOf(false) }
    var lastHandledJumpKey by remember { mutableStateOf<String?>(null) }

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

    // Undo soft-delete snackbar
    LaunchedEffect(uiState.undoDeletedEntryId) {
        val deletedId = uiState.undoDeletedEntryId ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = "Entry deleted",
            actionLabel = "Undo",
            duration = SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) {
            viewModel.undoDelete()
        } else {
            if (uiState.undoDeletedEntryId == deletedId) {
                viewModel.clearUndoDelete()
            }
        }
    }

    // Undo archive snackbar
    LaunchedEffect(uiState.undoArchivedEntryId) {
        val archivedId = uiState.undoArchivedEntryId ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = "Entry archived",
            actionLabel = "Undo",
            duration = SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) {
            viewModel.undoArchive()
        } else {
            if (uiState.undoArchivedEntryId == archivedId) {
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
                        viewModel.setFeedFilter(ChatFeedFilter.All)
                        onFilterChanged(ChatFeedFilter.All)
                    },
                )
                val todosFilter = uiState.activeFilter as? ChatFeedFilter.Todos
                if (todosFilter != null) {
                    TodoFilterStrip(
                        todosFilter = todosFilter,
                        openCount = uiState.openTodoCount,
                        completedCount = uiState.completedTodoCount,
                        onSelectMode = { mode ->
                            viewModel.setTodoFilterMode(mode)
                            onFilterChanged(todosFilter.copy(mode = mode))
                        },
                        onSelectPriority = { priority ->
                            viewModel.setTodoPriorityFilter(priority)
                            onFilterChanged(todosFilter.copy(priorityFilter = priority))
                        },
                        onToggleSortOrder = {
                            val nextSort = when (todosFilter.sortOrder) {
                                TodoSortOrder.NEWEST -> TodoSortOrder.PRIORITY
                                TodoSortOrder.PRIORITY -> TodoSortOrder.NEWEST
                            }
                            viewModel.toggleTodoSortOrder()
                            onFilterChanged(todosFilter.copy(sortOrder = nextSort))
                        },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        bottomBar = {
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

    // Quick-Tag Picker modal bottom sheet (for composer or existing entry)
    when (val target = tagPickerTarget) {
        null -> Unit

        TagPickerTarget.Composer -> {
            QuickTagPickerSheet(
                title = "Tags for new entry",
                allTags = uiState.allTags,
                tagDisplayLabels = uiState.tagDisplayLabels,
                selectedTagIds = uiState.selectedTagIds.toSet(),
                onToggleTag = { tagId, _ -> viewModel.toggleSelectedTag(tagId) },
                onCreateTagPath = viewModel::createAndSelectTag,
                onDeleteTag = { tagId ->
                    viewModel.deleteTag(tagId) { onFilterChanged(ChatFeedFilter.All) }
                },
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
                    viewModel.deleteTag(tagId) { onFilterChanged(ChatFeedFilter.All) }
                },
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
        ChatFeedFilter.All -> "ThreadLog"
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
        ChatFeedFilter.All -> null
        is ChatFeedFilter.Todos -> "Task notes"
        ChatFeedFilter.Archived -> "Archived notes"
        is ChatFeedFilter.TagSubtree -> activeFilter.fullPath
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

                if (activeFilter == ChatFeedFilter.All) {
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

                if (activeFilter != ChatFeedFilter.All) {
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
private fun TodoFilterStrip(
    todosFilter: ChatFeedFilter.Todos,
    openCount: Int,
    completedCount: Int,
    onSelectMode: (TodoFilterMode) -> Unit,
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
            }

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
                    val isSelected = todosFilter.priorityFilter == priority
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
    }
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
        ChatFeedFilter.All -> {
            "Your ThreadLog is ready" to
                "Capture thoughts, links, and photos like messaging yourself. Use @people and #tags anytime."
        }

        ChatFeedFilter.Archived -> {
            "No archived notes" to
                "Archive notes from any message's menu to tuck them away here."
        }

        is ChatFeedFilter.Todos -> {
            when (activeFilter.mode) {
                TodoFilterMode.OPEN -> {
                    "No open TODOs" to
                        "Write a note below or use any note's menu to mark it as a TODO."
                }

                TodoFilterMode.DONE -> {
                    "No completed TODOs yet" to
                        "Check off open TODOs and they will appear here."
                }

                TodoFilterMode.ALL -> {
                    "No TODOs yet" to
                        "Toggle the checkbox in the composer or mark any note as a TODO."
                }
            }
        }

        is ChatFeedFilter.TagSubtree -> {
            "No notes in ${activeFilter.displayLabel}" to
                "Write a note below — it will automatically be tagged with ${activeFilter.fullPath}."
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
