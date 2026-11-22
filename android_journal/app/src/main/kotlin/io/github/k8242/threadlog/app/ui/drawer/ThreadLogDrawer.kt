// Copyright (c) 2026 k8242
// SPDX-License-Identifier: MIT

package io.github.k8242.threadlog.app.ui.drawer

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.BrightnessAuto
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Label
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.k8242.threadlog.app.ui.theme.AvailableColorSchemes
import io.github.k8242.threadlog.core.model.AppColorScheme
import io.github.k8242.threadlog.core.model.ChatFeedFilter
import io.github.k8242.threadlog.core.model.DrawerTagSummary
import io.github.k8242.threadlog.core.model.TagRootCategory
import io.github.k8242.threadlog.core.model.ThemeMode
import io.github.k8242.threadlog.core.model.navigation.ThreadLogRoute

data class DrawerNavDestination(
    val label: String,
    val icon: ImageVector,
    val route: ThreadLogRoute,
    val selected: Boolean,
)

/**
 * WhatsApp-inspired hidable left panel containing:
 * - Top utility actions (moved from the old Chat top bar)
 * - Compact view navigation strip (moved from the old bottom NavigationBar)
 * - Filterable Topics, TODOs, Projects, People, and Tags list
 * - One-tap Color Scheme & Light/Dark mode quick switcher
 */
@Composable
fun ThreadLogDrawerSheet(
    uiState: DrawerUiState,
    destinations: List<DrawerNavDestination>,
    activeChatFilter: ChatFeedFilter,
    isChatActive: Boolean,
    onNavigateToDestination: (ThreadLogRoute) -> Unit,
    onSelectChatFilter: (ChatFeedFilter) -> Unit,
    onSearchQueryChanged: (String) -> Unit,
    onSelectCategory: (DrawerCategoryTab) -> Unit,
    onToggleShowHiddenAutoTags: () -> Unit,
    onOpenAutoTagReview: () -> Unit,
    onOpenSettings: () -> Unit,
    onSelectColorScheme: (AppColorScheme) -> Unit,
    onCycleThemeMode: () -> Unit,
    onDeleteTag: (DrawerTagSummary) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var pendingDeleteSummary by remember { mutableStateOf<DrawerTagSummary?>(null) }

    ModalDrawerSheet(
        modifier = modifier
            .width(316.dp)
            .fillMaxHeight(),
        drawerContainerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .statusBarsPadding()
                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime)),
        ) {
            // 1. Header bar (replaces top app bar actions)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 6.dp, top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "ThreadLog",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )

                IconButton(
                    onClick = onToggleShowHiddenAutoTags,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = if (uiState.showHiddenAutoTagsInChat) {
                            Icons.Outlined.Visibility
                        } else {
                            Icons.Outlined.VisibilityOff
                        },
                        contentDescription = if (uiState.showHiddenAutoTagsInChat) {
                            "Hide pending auto-tags in chat"
                        } else {
                            "Show pending auto-tags in chat"
                        },
                        modifier = Modifier.size(20.dp),
                    )
                }

                IconButton(
                    onClick = onOpenAutoTagReview,
                    modifier = Modifier.size(36.dp),
                ) {
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
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    } else {
                        Icon(
                            imageVector = Icons.Outlined.AutoAwesome,
                            contentDescription = "Auto-Tag Review",
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }

                IconButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Settings,
                        contentDescription = "Settings",
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            // 2. Compact Views navigation strip (moved from bottom NavigationBar)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                destinations.forEach { dest ->
                    DrawerNavTile(
                        destination = dest,
                        onClick = { onNavigateToDestination(dest.route) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

            // 3. WhatsApp-style search & category filter pills
            DrawerSearchField(
                query = uiState.searchQuery,
                onQueryChanged = onSearchQueryChanged,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )

            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(DrawerCategoryTab.entries, key = { it.name }) { tab ->
                    FilterChip(
                        selected = uiState.selectedCategory == tab,
                        onClick = { onSelectCategory(tab) },
                        label = {
                            Text(
                                text = tab.label,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        },
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // 4. Scrollable Topics, TODOs, People & Tags list
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                // Pinned: All Notes
                item(key = "pinned_all_notes") {
                    val selected = isChatActive && activeChatFilter == ChatFeedFilter.All
                    DrawerConversationRow(
                        title = "All Notes",
                        subtitle = uiState.overview.latestEntryPreview ?: "Your complete journal stream",
                        badgeCount = uiState.overview.totalEntryCount,
                        badgeAccent = false,
                        selected = selected,
                        avatarBg = MaterialTheme.colorScheme.primaryContainer,
                        avatarFg = MaterialTheme.colorScheme.onPrimaryContainer,
                        avatarIcon = Icons.AutoMirrored.Outlined.Chat,
                        avatarText = null,
                        onClick = { onSelectChatFilter(ChatFeedFilter.All) },
                    )
                }

                // Pinned: TODOs topic
                item(key = "pinned_todos") {
                    val selected = isChatActive && activeChatFilter is ChatFeedFilter.Todos
                    val latestOpenTodo = uiState.overview.latestOpenTodoPreview
                    val todoSubtitle = when {
                        latestOpenTodo != null -> latestOpenTodo
                        uiState.overview.completedTodoCount > 0 -> {
                            "${uiState.overview.completedTodoCount} completed"
                        }
                        else -> "Mark any note as a TODO to track it here"
                    }
                    DrawerConversationRow(
                        title = "TODOs",
                        subtitle = todoSubtitle,
                        badgeCount = uiState.overview.openTodoCount,
                        badgeAccent = uiState.overview.openTodoCount > 0,
                        selected = selected,
                        avatarBg = MaterialTheme.colorScheme.tertiaryContainer,
                        avatarFg = MaterialTheme.colorScheme.onTertiaryContainer,
                        avatarIcon = Icons.Outlined.CheckBox,
                        avatarText = null,
                        onClick = { onSelectChatFilter(ChatFeedFilter.Todos()) },
                    )
                }

                item(key = "pinned_divider") {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }

                val summaries = uiState.filteredTagSummaries
                if (summaries.isEmpty()) {
                    item(key = "empty_topics") {
                        Text(
                            text = if (uiState.searchQuery.isNotBlank()) {
                                "No topics, tags, or people matching \"${uiState.searchQuery}\""
                            } else {
                                "No tags yet. Use @people, #topics, or +projects in notes to populate your lists."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        )
                    }
                } else {
                    items(summaries, key = { it.tag.id }) { summary ->
                        val displayLabel = uiState.tagDisplayLabels[summary.tag.id] ?: summary.tag.leafName
                        val prefix = when (summary.tag.rootCategory) {
                            TagRootCategory.PEOPLE -> "@"
                            TagRootCategory.PROJECTS -> "+"
                            else -> "#"
                        }
                        val selected = isChatActive &&
                            activeChatFilter is ChatFeedFilter.TagSubtree &&
                            activeChatFilter.tagId == summary.tag.id
                        val (avatarBg, avatarFg) = drawerTagAvatarColors(summary.tag.rootCategory)
                        val subtitle = summary.latestPreview ?: summary.tag.fullPath

                        DrawerConversationRow(
                            title = "$prefix$displayLabel",
                            subtitle = subtitle,
                            badgeCount = summary.entryCount,
                            badgeAccent = false,
                            openTodoCount = summary.openTodoCount,
                            selected = selected,
                            avatarBg = avatarBg,
                            avatarFg = avatarFg,
                            avatarIcon = drawerTagCategoryIcon(summary.tag.rootCategory),
                            avatarText = summary.tag.leafName.firstOrNull()?.uppercaseChar()?.toString(),
                            onClick = {
                                onSelectChatFilter(
                                    ChatFeedFilter.TagSubtree(
                                        tagId = summary.tag.id,
                                        fullPath = summary.tag.fullPath,
                                        displayLabel = displayLabel,
                                    ),
                                )
                            },
                            onLongClick = { pendingDeleteSummary = summary },
                            onDeleteClick = { pendingDeleteSummary = summary },
                        )
                    }
                }
            }

            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

            // 5. Compact Color Scheme & Light/Dark Quick Switcher Footer
            DrawerThemeFooter(
                selectedScheme = uiState.colorScheme,
                themeMode = uiState.themeMode,
                onSelectColorScheme = onSelectColorScheme,
                onCycleThemeMode = onCycleThemeMode,
            )
        }
    }

    pendingDeleteSummary?.let { target ->
        val displayLabel = uiState.tagDisplayLabels[target.tag.id] ?: target.tag.leafName
        val childCount = remember(uiState.allTagSummaries, target) {
            val prefix = "${target.tag.fullPath}/"
            uiState.allTagSummaries.count { it.tag.fullPath.startsWith(prefix, ignoreCase = true) }
        }
        val notesPart = if (target.entryCount > 0) {
            " Currently attached to ${target.entryCount} note${if (target.entryCount == 1) "" else "s"}."
        } else {
            ""
        }
        val childPart = if (childCount > 0) {
            " This will also delete $childCount sub-tag${if (childCount == 1) "" else "s"}."
        } else {
            ""
        }
        AlertDialog(
            onDismissRequest = { pendingDeleteSummary = null },
            title = { Text("Delete tag \"$displayLabel\"?") },
            text = {
                Text(
                    "Remove \"${target.tag.fullPath}\" from your tag list?$notesPart$childPart Your journal notes will not be deleted.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteTag(target)
                        pendingDeleteSummary = null
                    },
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteSummary = null }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun DrawerNavTile(
    destination: DrawerNavDestination,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg = if (destination.selected) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        Color.Transparent
    }
    val fg = if (destination.selected) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        color = bg,
        shape = RoundedCornerShape(10.dp),
        modifier = modifier.clickable(onClick = onClick),
    ) {
        Column(
            modifier = Modifier.padding(vertical = 6.dp, horizontal = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Icon(
                imageVector = destination.icon,
                contentDescription = destination.label,
                tint = fg,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = destination.label,
                style = MaterialTheme.typography.labelSmall,
                color = fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DrawerSearchField(
    query: String,
    onQueryChanged: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(20.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Box(modifier = Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(
                        text = "Filter topics, tags, people...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChanged,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (query.isNotEmpty()) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = "Clear filter",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(16.dp)
                        .clickable { onQueryChanged("") },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DrawerConversationRow(
    title: String,
    subtitle: String,
    badgeCount: Int,
    badgeAccent: Boolean,
    selected: Boolean,
    avatarBg: Color,
    avatarFg: Color,
    avatarIcon: ImageVector?,
    avatarText: String?,
    onClick: () -> Unit,
    openTodoCount: Int = 0,
    onLongClick: (() -> Unit)? = null,
    onDeleteClick: (() -> Unit)? = null,
) {
    val rowBg = if (selected) {
        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f)
    } else {
        Color.Transparent
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(rowBg)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(avatarBg),
            contentAlignment = Alignment.Center,
        ) {
            if (avatarIcon != null && avatarText == null) {
                Icon(
                    imageVector = avatarIcon,
                    contentDescription = null,
                    tint = avatarFg,
                    modifier = Modifier.size(20.dp),
                )
            } else {
                Text(
                    text = avatarText ?: "#",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = avatarFg,
                )
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (openTodoCount > 0) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text(
                        text = "$openTodoCount",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
            }
            if (badgeCount > 0 || badgeAccent) {
                Surface(
                    color = if (badgeAccent) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHighest
                    },
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text(
                        text = badgeCount.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (badgeAccent) FontWeight.Bold else FontWeight.Medium,
                        color = if (badgeAccent) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                    )
                }
            }
            if (onDeleteClick != null) {
                IconButton(
                    onClick = onDeleteClick,
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = "Delete $title",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun DrawerThemeFooter(
    selectedScheme: AppColorScheme,
    themeMode: ThemeMode,
    onSelectColorScheme: (AppColorScheme) -> Unit,
    onCycleThemeMode: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        LazyRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items(AvailableColorSchemes, key = { it.scheme.name }) { option ->
                val isSelected = option.scheme == selectedScheme
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(option.previewColor)
                        .then(
                            if (isSelected) {
                                Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                            } else {
                                Modifier.border(
                                    BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                                    CircleShape,
                                )
                            },
                        )
                        .clickable { onSelectColorScheme(option.scheme) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (isSelected) {
                        Icon(
                            imageVector = Icons.Outlined.Check,
                            contentDescription = "${option.label} selected",
                            tint = Color.White,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
        }

        IconButton(
            onClick = onCycleThemeMode,
            modifier = Modifier.size(34.dp),
        ) {
            Icon(
                imageVector = when (themeMode) {
                    ThemeMode.SYSTEM -> Icons.Outlined.BrightnessAuto
                    ThemeMode.LIGHT -> Icons.Outlined.LightMode
                    ThemeMode.DARK -> Icons.Outlined.DarkMode
                },
                contentDescription = when (themeMode) {
                    ThemeMode.SYSTEM -> "Theme: System (tap to switch)"
                    ThemeMode.LIGHT -> "Theme: Light (tap to switch)"
                    ThemeMode.DARK -> "Theme: Dark (tap to switch)"
                },
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun drawerTagAvatarColors(category: TagRootCategory): Pair<Color, Color> = when (category) {
    TagRootCategory.PEOPLE -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
    TagRootCategory.PROJECTS -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
    TagRootCategory.TOPICS -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
    TagRootCategory.AUTO -> MaterialTheme.colorScheme.surfaceContainerHighest to MaterialTheme.colorScheme.onSurfaceVariant
    TagRootCategory.OTHER -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
}

private fun drawerTagCategoryIcon(category: TagRootCategory): ImageVector = when (category) {
    TagRootCategory.PEOPLE -> Icons.Outlined.Person
    TagRootCategory.PROJECTS -> Icons.Outlined.Folder
    TagRootCategory.TOPICS -> Icons.Outlined.Tag
    TagRootCategory.AUTO -> Icons.Outlined.AutoAwesome
    TagRootCategory.OTHER -> Icons.Outlined.Label
}
