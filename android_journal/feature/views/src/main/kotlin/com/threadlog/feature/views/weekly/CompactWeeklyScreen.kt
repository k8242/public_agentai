package com.threadlog.feature.views.weekly

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.EventNote
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.UnfoldLess
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.threadlog.core.model.EntryWithDetails
import com.threadlog.feature.views.common.BookmarkDomainPill
import com.threadlog.feature.views.common.LeafTagChip
import com.threadlog.feature.views.common.sanitizeSingleLinePreview

/**
 * Stateful Compact Weekly View screen wired to [CompactWeeklyViewModel].
 */
@Composable
fun CompactWeeklyScreen(
    onOpenEntryInChat: (entryId: String, localDate: String) -> Unit = { _, _ -> },
    onOpenContextWindow: (entryId: String) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: CompactWeeklyViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    CompactWeeklyScreen(
        uiState = uiState,
        onToggleExpand = viewModel::toggleExpand,
        onCollapseAll = viewModel::collapseAll,
        onOpenEntryInChat = onOpenEntryInChat,
        onOpenContextWindow = onOpenContextWindow,
        modifier = modifier,
    )
}

/**
 * Stateless Compact Weekly View screen for testing and Compose previews.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompactWeeklyScreen(
    uiState: CompactWeeklyUiState,
    onToggleExpand: (entryId: String) -> Unit,
    onCollapseAll: () -> Unit,
    onOpenEntryInChat: (entryId: String, localDate: String) -> Unit = { _, _ -> },
    onOpenContextWindow: (entryId: String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(text = "Weekly") },
                actions = {
                    if (uiState.hasExpandedItems) {
                        IconButton(onClick = onCollapseAll) {
                            Icon(
                                imageVector = Icons.Rounded.UnfoldLess,
                                contentDescription = "Collapse all",
                            )
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        when {
            uiState.isLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            uiState.weeks.isEmpty() -> {
                WeeklyEmptyState(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                )
            }

            else -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentPadding = PaddingValues(bottom = 24.dp),
                ) {
                    uiState.weeks.forEach { week ->
                        item(
                            key = "week_${week.weekKey}",
                            contentType = "week_header",
                        ) {
                            WeekHeaderBand(
                                weekLabel = week.weekLabel,
                                entryCount = week.entryCount,
                            )
                        }

                        week.days.forEach { day ->
                            item(
                                key = "day_${week.weekKey}_${day.dayKey}",
                                contentType = "day_header",
                            ) {
                                DaySubHeader(dayLabel = day.dayLabel)
                            }

                            items(
                                items = day.items,
                                key = { item -> "entry_${item.summary.id}" },
                                contentType = { "entry_row" },
                            ) { item ->
                                CompactEntryRow(
                                    item = item,
                                    tagDisplayLabels = uiState.tagDisplayLabels,
                                    onToggleExpand = { onToggleExpand(item.summary.id) },
                                    onOpenEntryInChat = {
                                        onOpenEntryInChat(item.summary.id, item.summary.localDate)
                                    },
                                    onOpenContextWindow = {
                                        onOpenContextWindow(item.summary.id)
                                    },
                                )
                                HorizontalDivider(
                                    modifier = Modifier.padding(start = 16.dp),
                                    thickness = 0.5.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WeeklyEmptyState(
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.EventNote,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "No entries yet",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Entries you capture in Chat will appear here grouped by week.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun WeekHeaderBand(
    weekLabel: String,
    entryCount: Int,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = weekLabel,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ) {
                Text(
                    text = if (entryCount == 1) "1 entry" else "$entryCount entries",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                )
            }
        }
    }
}

@Composable
private fun DaySubHeader(
    dayLabel: String,
) {
    Text(
        text = dayLabel,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 4.dp),
    )
}

@Composable
private fun CompactEntryRow(
    item: CompactEntryRowItem,
    tagDisplayLabels: Map<String, String>,
    onToggleExpand: () -> Unit,
    onOpenEntryInChat: () -> Unit,
    onOpenContextWindow: () -> Unit,
) {
    val sanitizedPreview = sanitizeSingleLinePreview(item.summary.preview140)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggleExpand),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = item.timeLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.widthIn(min = 56.dp),
            )
            Text(
                text = sanitizedPreview.ifEmpty { "(Attachment)" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (item.summary.hasAttachments) {
                Icon(
                    imageVector = Icons.Outlined.AttachFile,
                    contentDescription = "Has attachment",
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (item.summary.hasBookmarks) {
                Icon(
                    imageVector = Icons.Outlined.Link,
                    contentDescription = "Has bookmark",
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (item.summary.hasCodeBlock) {
                Icon(
                    imageVector = Icons.Outlined.Code,
                    contentDescription = "Has code block",
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = if (item.isExpanded) {
                    Icons.Rounded.ExpandLess
                } else {
                    Icons.Rounded.ExpandMore
                },
                contentDescription = if (item.isExpanded) "Collapse" else "Expand",
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }

        AnimatedVisibility(
            visible = item.isExpanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            ExpandedEntryDetailsCard(
                details = item.expandedDetails,
                tagDisplayLabels = tagDisplayLabels,
                onOpenEntryInChat = onOpenEntryInChat,
                onOpenContextWindow = onOpenContextWindow,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExpandedEntryDetailsCard(
    details: EntryWithDetails?,
    tagDisplayLabels: Map<String, String>,
    onOpenEntryInChat: () -> Unit,
    onOpenContextWindow: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (details == null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                Text(
                    text = details.entry.bodyPlain,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                val visibleTags = details.visibleTags
                if (visibleTags.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        visibleTags.forEach { entryTag ->
                            LeafTagChip(
                                label = tagDisplayLabels[entryTag.tagId] ?: entryTag.tag.leafName,
                                colorToken = entryTag.tag.colorToken,
                            )
                        }
                    }
                }

                if (details.bookmarks.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        details.bookmarks.forEach { bookmark ->
                            BookmarkDomainPill(
                                domain = bookmark.domain,
                                title = bookmark.title,
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onOpenContextWindow) {
                    Icon(
                        imageVector = Icons.Outlined.History,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.size(6.dp))
                    Text(text = "\u00B112h Context")
                }
                TextButton(onClick = onOpenEntryInChat) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.Chat,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.size(6.dp))
                    Text(text = "Open in Chat")
                }
            }
        }
    }
}
