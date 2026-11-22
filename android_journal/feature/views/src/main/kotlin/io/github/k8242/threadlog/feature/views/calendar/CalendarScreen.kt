// Copyright (c) 2026 k8242
// SPDX-License-Identifier: MIT

package io.github.k8242.threadlog.feature.views.calendar

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.k8242.threadlog.feature.views.common.LeafTagChip

/**
 * Stateful Monthly Calendar View screen wired to [CalendarViewModel].
 */
@Composable
fun CalendarScreen(
    onOpenEntryInChat: (entryId: String, localDate: String) -> Unit = { _, _ -> },
    onOpenContextWindow: (entryId: String) -> Unit = {},
    onOpenDrawer: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: CalendarViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    CalendarScreen(
        uiState = uiState,
        onPreviousMonth = viewModel::previousMonth,
        onNextMonth = viewModel::nextMonth,
        onJumpToToday = viewModel::jumpToToday,
        onSelectDate = viewModel::selectDate,
        onSelectTagFilter = viewModel::selectTagFilter,
        onOpenEntryInChat = onOpenEntryInChat,
        onOpenContextWindow = onOpenContextWindow,
        onOpenDrawer = onOpenDrawer,
        modifier = modifier,
    )
}

/**
 * Stateless Monthly Calendar View screen for testing and Compose previews.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(
    uiState: CalendarUiState,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onJumpToToday: () -> Unit,
    onSelectDate: (localDate: String) -> Unit,
    onSelectTagFilter: (fullPath: String?) -> Unit,
    onOpenEntryInChat: (entryId: String, localDate: String) -> Unit = { _, _ -> },
    onOpenContextWindow: (entryId: String) -> Unit = {},
    onOpenDrawer: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(text = "Calendar") },
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(
                            imageVector = Icons.Outlined.Menu,
                            contentDescription = "Open navigation panel",
                        )
                    }
                },
                actions = {
                    TextButton(onClick = onJumpToToday) {
                        Text(text = "Today")
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item(key = "month_nav", contentType = "month_nav") {
                MonthNavigationHeader(
                    monthLabel = uiState.monthLabel,
                    onPreviousMonth = onPreviousMonth,
                    onNextMonth = onNextMonth,
                )
            }

            if (uiState.allTags.isNotEmpty() || uiState.selectedTagFilterPath != null) {
                item(key = "tag_filter_strip", contentType = "tag_filter_strip") {
                    TagFilterChipStrip(
                        uiState = uiState,
                        onSelectTagFilter = onSelectTagFilter,
                    )
                }
            }

            item(key = "calendar_grid", contentType = "calendar_grid") {
                MonthCalendarGrid(
                    weekdayHeaders = uiState.weekdayHeaders,
                    cells = uiState.cells,
                    onSelectDate = onSelectDate,
                )
            }

            item(key = "grid_divider", contentType = "divider") {
                HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
            }

            item(key = "selected_day_heading", contentType = "day_heading") {
                SelectedDayHeading(
                    selectedDateLabel = uiState.selectedDateLabel,
                    entryCount = uiState.entriesForSelectedDate.size,
                    isLoading = uiState.isLoadingEntries,
                )
            }

            when {
                uiState.isLoadingEntries -> {
                    item(key = "day_loading", contentType = "loading") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(28.dp))
                        }
                    }
                }

                uiState.entriesForSelectedDate.isEmpty() -> {
                    item(key = "day_empty", contentType = "empty") {
                        CalendarEmptyDayState(
                            hasTagFilter = uiState.selectedTagFilterPath != null,
                        )
                    }
                }

                else -> {
                    items(
                        items = uiState.entriesForSelectedDate,
                        key = { item -> "day_entry_${item.details.entry.id}" },
                        contentType = { "day_entry_card" },
                    ) { item ->
                        CalendarDayEntryCard(
                            item = item,
                            tagDisplayLabels = uiState.tagDisplayLabels,
                            onOpenEntryInChat = {
                                onOpenEntryInChat(
                                    item.details.entry.id,
                                    item.details.entry.localDate,
                                )
                            },
                            onOpenContextWindow = {
                                onOpenContextWindow(item.details.entry.id)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MonthNavigationHeader(
    monthLabel: String,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        IconButton(onClick = onPreviousMonth) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                contentDescription = "Previous month",
            )
        }
        Text(
            text = monthLabel,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        IconButton(onClick = onNextMonth) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = "Next month",
            )
        }
    }
}

@Composable
private fun TagFilterChipStrip(
    uiState: CalendarUiState,
    onSelectTagFilter: (fullPath: String?) -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "filter_all") {
            FilterChip(
                selected = uiState.selectedTagFilterPath == null,
                onClick = { onSelectTagFilter(null) },
                label = { Text(text = "All") },
            )
        }
        items(
            items = uiState.allTags,
            key = { tag -> "filter_${tag.id}" },
        ) { tag ->
            val selected = uiState.selectedTagFilterPath.equals(tag.fullPath, ignoreCase = true)
            val label = uiState.tagDisplayLabels[tag.id] ?: tag.leafName
            FilterChip(
                selected = selected,
                onClick = {
                    onSelectTagFilter(if (selected) null else tag.fullPath)
                },
                label = {
                    Text(
                        text = label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
            )
        }
    }
}

@Composable
private fun MonthCalendarGrid(
    weekdayHeaders: List<String>,
    cells: List<CalendarDayCell>,
    onSelectDate: (localDate: String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 4.dp),
        ) {
            weekdayHeaders.forEach { header ->
                Text(
                    text = header,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        cells.chunked(7).forEach { weekRow ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                weekRow.forEach { cell ->
                    CalendarGridCell(
                        cell = cell,
                        onSelectDate = onSelectDate,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun CalendarGridCell(
    cell: CalendarDayCell,
    onSelectDate: (localDate: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dayOfMonth = cell.dayOfMonth
    val localDateKey = cell.localDateKey
    if (dayOfMonth == null || localDateKey == null) {
        Spacer(modifier = modifier.height(46.dp))
        return
    }

    val dotsCount = CalendarGridBuilder.densityDotsCount(cell.entryCount)
    val circleModifier = when {
        cell.isSelected -> Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)

        cell.isToday -> Modifier
            .size(32.dp)
            .clip(CircleShape)
            .border(
                width = 1.5.dp,
                color = MaterialTheme.colorScheme.primary,
                shape = CircleShape,
            )

        else -> Modifier
            .size(32.dp)
            .clip(CircleShape)
    }

    val dayTextColor = when {
        cell.isSelected -> MaterialTheme.colorScheme.onPrimary
        cell.isToday -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurface
    }

    Column(
        modifier = modifier
            .height(46.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable { onSelectDate(localDateKey) }
            .padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = circleModifier,
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = dayOfMonth.toString(),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (cell.isSelected || cell.isToday) {
                    FontWeight.Bold
                } else {
                    FontWeight.Normal
                },
                color = dayTextColor,
            )
        }
        Spacer(modifier = Modifier.height(3.dp))
        Row(
            modifier = Modifier.height(5.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(dotsCount) {
                Box(
                    modifier = Modifier
                        .size(4.dp)
                        .clip(CircleShape)
                        .background(
                            if (cell.isSelected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.secondary
                            },
                        ),
                )
            }
        }
    }
}

@Composable
private fun SelectedDayHeading(
    selectedDateLabel: String,
    entryCount: Int,
    isLoading: Boolean,
) {
    val countSuffix = when {
        isLoading -> ""
        entryCount == 1 -> " \u00B7 1 entry"
        else -> " \u00B7 $entryCount entries"
    }
    Text(
        text = "$selectedDateLabel$countSuffix",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@Composable
private fun CalendarEmptyDayState(
    hasTagFilter: Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.CalendarToday,
            contentDescription = null,
            modifier = Modifier.size(32.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
        )
        Text(
            text = if (hasTagFilter) {
                "No matching entries on this day"
            } else {
                "No entries on this day"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CalendarDayEntryCard(
    item: CalendarDayEntryItem,
    tagDisplayLabels: Map<String, String>,
    onOpenEntryInChat: () -> Unit,
    onOpenContextWindow: () -> Unit,
) {
    val details = item.details
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp)
            .clickable(onClick = onOpenEntryInChat),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        border = BorderStroke(
            width = 0.5.dp,
            color = MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = item.timeLabel,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (details.entry.isEdited) {
                        Text(
                            text = "Edited",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (details.attachments.isNotEmpty()) {
                        Icon(
                            imageVector = Icons.Outlined.AttachFile,
                            contentDescription = "Has attachments",
                            modifier = Modifier.size(15.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (details.bookmarks.isNotEmpty()) {
                        Icon(
                            imageVector = Icons.Outlined.Link,
                            contentDescription = "Has bookmarks",
                            modifier = Modifier.size(15.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (details.entry.bodyPlain.isNotBlank()) {
                Text(
                    text = details.entry.bodyPlain,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }

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
