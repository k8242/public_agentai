package com.threadlog.feature.chat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.threadlog.core.model.EntryWithDetails

private sealed interface TagPickerTarget {
    data object Composer : TagPickerTarget
    data class ExistingEntry(val entry: EntryWithDetails) : TagPickerTarget
}

/**
 * Primary WhatsApp-style reverse-anchored journal chat screen for ThreadLog.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    jumpToEntryId: String? = null,
    jumpToLocalDate: String? = null,
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

    var fullscreenImagePath by remember { mutableStateOf<String?>(null) }
    var tagPickerTarget by remember { mutableStateOf<TagPickerTarget?>(null) }
    var lastHandledJumpKey by remember { mutableStateOf<String?>(null) }

    // Scroll to target entry or localDate once loaded in the paging snapshot.
    LaunchedEffect(jumpToEntryId, jumpToLocalDate, lazyPagingItems.itemCount) {
        val jumpKey = "${jumpToEntryId.orEmpty()}|${jumpToLocalDate.orEmpty()}"
        if ((jumpToEntryId != null || jumpToLocalDate != null) && jumpKey != lastHandledJumpKey) {
            val snapshot = lazyPagingItems.itemSnapshotList.items
            val targetIndex = when {
                jumpToEntryId != null -> snapshot.indexOfFirst { it.entry.id == jumpToEntryId }
                jumpToLocalDate != null -> snapshot.indexOfFirst { it.entry.localDate == jumpToLocalDate }
                else -> -1
            }
            if (targetIndex >= 0) {
                lastHandledJumpKey = jumpKey
                listState.animateScrollToItem(targetIndex)
            }
        }
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
    val stickyPeriodMeta by remember(lazyPagingItems, uiState.weekStart) {
        derivedStateOf {
            val topVisibleIndex = listState.layoutInfo.visibleItemsInfo
                .maxByOrNull { it.index }
                ?.index
                ?: return@derivedStateOf null
            if (topVisibleIndex !in 0 until lazyPagingItems.itemCount) {
                return@derivedStateOf null
            }
            val topEntry = lazyPagingItems.peek(topVisibleIndex) ?: return@derivedStateOf null
            viewModel.dividerMeta(topEntry.entry.createdAtMs, topEntry.entry.zoneId)
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .imePadding(),
        topBar = {
            TopAppBar(
                title = { Text("ThreadLog") },
                actions = {
                    IconButton(onClick = viewModel::toggleShowHiddenAutoTags) {
                        Icon(
                            imageVector = if (uiState.showHiddenAutoTags) {
                                Icons.Outlined.Visibility
                            } else {
                                Icons.Outlined.VisibilityOff
                            },
                            contentDescription = if (uiState.showHiddenAutoTags) {
                                "Hide pending auto-tags in chat"
                            } else {
                                "Show pending auto-tags in chat"
                            },
                        )
                    }

                    IconButton(onClick = onOpenAutoTagReview) {
                        if (uiState.pendingAutoTagCount > 0) {
                            BadgedBox(
                                badge = {
                                    Badge {
                                        Text(
                                            text = if (uiState.pendingAutoTagCount > 99) {
                                                "99+"
                                            } else {
                                                uiState.pendingAutoTagCount.toString()
                                            },
                                        )
                                    }
                                },
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.AutoAwesome,
                                    contentDescription = "Auto-Tag Review (${uiState.pendingAutoTagCount} pending)",
                                )
                            }
                        } else {
                            Icon(
                                imageVector = Icons.Outlined.AutoAwesome,
                                contentDescription = "Auto-Tag Review",
                            )
                        }
                    }

                    IconButton(onClick = { onOpenSearch(null) }) {
                        Icon(
                            imageVector = Icons.Outlined.Search,
                            contentDescription = "Search journal",
                        )
                    }

                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Outlined.Settings,
                            contentDescription = "Settings",
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        bottomBar = {
            ChatComposer(
                uiState = uiState,
                resolveAttachmentPath = viewModel::resolveAttachmentPath,
                onTextChanged = viewModel::onComposerTextChanged,
                onSelectTagSuggestion = viewModel::onSelectTagSuggestion,
                onToggleSelectedTag = viewModel::toggleSelectedTag,
                onImageBytesReceived = viewModel::onImageBytesReceived,
                onRemovePendingAttachment = viewModel::removePendingAttachment,
                onCancelReply = viewModel::cancelReply,
                onCancelEdit = viewModel::cancelEdit,
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
                EmptyChatState(modifier = Modifier.align(Alignment.Center))
            } else {
                LazyColumn(
                    state = listState,
                    reverseLayout = true,
                    contentPadding = PaddingValues(top = 44.dp, bottom = 12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(
                        count = lazyPagingItems.itemCount,
                        key = lazyPagingItems.itemKey { it.entry.id },
                    ) { index ->
                        val item = lazyPagingItems[index] ?: return@items
                        val olderItem = if (index + 1 < lazyPagingItems.itemCount) {
                            lazyPagingItems.peek(index + 1)
                        } else {
                            null
                        }

                        val currentMeta = remember(item.entry.createdAtMs, item.entry.zoneId, uiState.weekStart) {
                            viewModel.dividerMeta(item.entry.createdAtMs, item.entry.zoneId)
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
                                tagDisplayLabels = uiState.tagDisplayLabels,
                                showHiddenAutoTags = uiState.showHiddenAutoTags,
                                resolveAttachmentPath = viewModel::resolveAttachmentPath,
                                onReply = { viewModel.startReply(item) },
                                onEdit = { viewModel.startEdit(item) },
                                onManageTags = { tagPickerTarget = TagPickerTarget.ExistingEntry(item) },
                                onDelete = { viewModel.deleteEntry(item.entry.id) },
                                onToggleBookmarkCollapsed = viewModel::toggleBookmarkCollapsed,
                                onPromoteAutoTag = { tagId ->
                                    viewModel.promoteAutoTag(item.entry.id, tagId)
                                },
                                onRejectAutoTag = { tagId ->
                                    viewModel.rejectAutoTag(item.entry.id, tagId)
                                },
                                onImageClick = { filePath -> fullscreenImagePath = filePath },
                                onTagClick = { target -> onOpenSearch("#${target.fullPath}") },
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
                            .padding(top = 8.dp),
                    )
                }
            }
        }
    }

    // Fullscreen attachment preview dialog
    fullscreenImagePath?.let { path ->
        FullscreenImageDialog(
            filePath = path,
            onDismiss = { fullscreenImagePath = null },
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
                onDismiss = { tagPickerTarget = null },
            )
        }

        is TagPickerTarget.ExistingEntry -> {
            // Re-read the latest visible tag IDs from the paging snapshot if available
            val liveEntry = lazyPagingItems.itemSnapshotList.items
                .firstOrNull { it.entry.id == target.entry.entry.id }
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
                onDismiss = { tagPickerTarget = null },
            )
        }
    }
}

@Composable
private fun EmptyChatState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Your ThreadLog is ready",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "Capture thoughts, links, and photos like messaging yourself. Use @people and #tags anytime.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
