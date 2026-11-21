// Copyright (c) 2026 k8242
// SPDX-License-Identifier: MIT

package io.github.k8242.threadlog.feature.search.ui

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.k8242.threadlog.core.model.SearchHit
import io.github.k8242.threadlog.core.model.Tag

/**
 * Stateful entry point for two-tier hybrid search:
 * - Tier A (`Recent · ≤ 5 weeks`): interactive stepper with `"Jump in Chat"` action.
 * - Tier B (`Older · > 5 weeks`): compact rows opening the interactive `±12h` Context Window.
 */
@Composable
fun SearchScreen(
    initialQuery: String? = null,
    onJumpToEntryInChat: (entryId: String, localDate: String) -> Unit = { _, _ -> },
    onOpenContextWindow: (anchorEntryId: String, highlightQuery: String?) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    LaunchedEffect(initialQuery) {
        viewModel.initQuery(initialQuery)
    }

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    SearchScreen(
        uiState = uiState,
        onQueryChanged = viewModel::onQueryChanged,
        onClearQuery = viewModel::clearQuery,
        onToggleIncludeHiddenAutoTags = viewModel::toggleIncludeHiddenAutoTags,
        onStepPreviousTierA = viewModel::stepPreviousTierA,
        onStepNextTierA = viewModel::stepNextTierA,
        onSelectTierAIndex = viewModel::selectTierAIndex,
        onJumpToEntryInChat = onJumpToEntryInChat,
        onOpenContextWindow = onOpenContextWindow,
        formatDateTimeLabel = viewModel::formatDateTimeLabel,
        modifier = modifier,
    )
}

/**
 * Stateless overload of [SearchScreen] suitable for previews and UI tests.
 */
@Composable
fun SearchScreen(
    uiState: SearchUiState,
    onQueryChanged: (String) -> Unit,
    onClearQuery: () -> Unit,
    onToggleIncludeHiddenAutoTags: () -> Unit,
    onStepPreviousTierA: () -> Unit,
    onStepNextTierA: () -> Unit,
    onSelectTierAIndex: (Int) -> Unit,
    onJumpToEntryInChat: (entryId: String, localDate: String) -> Unit,
    onOpenContextWindow: (anchorEntryId: String, highlightQuery: String?) -> Unit,
    formatDateTimeLabel: (SearchHit) -> String,
    modifier: Modifier = Modifier,
) {
    Scaffold(modifier = modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            SearchHeader(
                query = uiState.query,
                includeHiddenAutoTags = uiState.includeHiddenAutoTags,
                totalCount = if (uiState.result != null) uiState.totalCount else null,
                onQueryChanged = onQueryChanged,
                onClearQuery = onClearQuery,
                onToggleIncludeHiddenAutoTags = onToggleIncludeHiddenAutoTags,
            )

            if (uiState.isSearching) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            if (uiState.errorMessage != null) {
                SearchErrorBanner(message = uiState.errorMessage)
            }

            when {
                uiState.query.isBlank() -> {
                    SearchIdleState(modifier = Modifier.weight(1f))
                }
                !uiState.isSearching && uiState.result != null && uiState.totalCount == 0 -> {
                    SearchEmptyState(
                        query = uiState.query,
                        includeHiddenAutoTags = uiState.includeHiddenAutoTags,
                        onEnableHiddenAutoTags = onToggleIncludeHiddenAutoTags,
                        modifier = Modifier.weight(1f),
                    )
                }
                else -> {
                    SearchResultsList(
                        uiState = uiState,
                        onStepPreviousTierA = onStepPreviousTierA,
                        onStepNextTierA = onStepNextTierA,
                        onSelectTierAIndex = onSelectTierAIndex,
                        onJumpToEntryInChat = onJumpToEntryInChat,
                        onOpenContextWindow = onOpenContextWindow,
                        formatDateTimeLabel = formatDateTimeLabel,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchHeader(
    query: String,
    includeHiddenAutoTags: Boolean,
    totalCount: Int?,
    onQueryChanged: (String) -> Unit,
    onClearQuery: () -> Unit,
    onToggleIncludeHiddenAutoTags: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChanged,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(24.dp),
            placeholder = { Text(text = "Search entries, tags, or links") },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Rounded.Search,
                    contentDescription = "Search",
                )
            },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = onClearQuery) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Clear search query",
                        )
                    }
                }
            },
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            FilterChip(
                selected = includeHiddenAutoTags,
                onClick = onToggleIncludeHiddenAutoTags,
                label = { Text(text = "Include hidden auto-tags") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.AutoAwesome,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
            )

            if (totalCount != null && query.isNotBlank()) {
                Text(
                    text = if (totalCount == 1) "1 match" else "$totalCount matches",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    HorizontalDivider()
}

@Composable
private fun SearchErrorBanner(message: String) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(12.dp),
    ) {
        Text(
            text = message,
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
private fun SearchIdleState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(40.dp),
            )
            Text(
                text = "Search your ThreadLog",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "Recent matches (\u2264 5 weeks) jump directly in chat. Older matches (> 5 weeks) open in a \u00B112h context window.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SearchEmptyState(
    query: String,
    includeHiddenAutoTags: Boolean,
    onEnableHiddenAutoTags: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "No matches for \"$query\"",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = if (includeHiddenAutoTags) {
                    "Try a shorter keyword or different tag name."
                } else {
                    "Hidden auto-tags are currently excluded from results."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!includeHiddenAutoTags) {
                FilledTonalButton(onClick = onEnableHiddenAutoTags) {
                    Icon(
                        imageVector = Icons.Rounded.AutoAwesome,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = "Include hidden auto-tags")
                }
            }
        }
    }
}

@Composable
private fun SearchResultsList(
    uiState: SearchUiState,
    onStepPreviousTierA: () -> Unit,
    onStepNextTierA: () -> Unit,
    onSelectTierAIndex: (Int) -> Unit,
    onJumpToEntryInChat: (entryId: String, localDate: String) -> Unit,
    onOpenContextWindow: (anchorEntryId: String, highlightQuery: String?) -> Unit,
    formatDateTimeLabel: (SearchHit) -> String,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (uiState.tierACount > 0) {
            item(key = "tier_a_header") {
                SectionHeader(
                    title = "Recent \u00B7 \u2264 5 weeks",
                    count = uiState.tierACount,
                )
            }

            uiState.activeTierAHit?.let { activeHit ->
                item(key = "tier_a_stepper") {
                    TierAStepperCard(
                        hit = activeHit,
                        activeIndex = uiState.tierAIndex,
                        totalCount = uiState.tierACount,
                        dateTimeLabel = formatDateTimeLabel(activeHit),
                        tagDisplayLabels = uiState.tagDisplayLabels,
                        onPrevious = onStepPreviousTierA,
                        onNext = onStepNextTierA,
                        onJumpInChat = { onJumpToEntryInChat(activeHit.entryId, activeHit.localDate) },
                    )
                }
            }

            itemsIndexed(
                items = uiState.tierAHits,
                key = { _, hit -> "tier_a_${hit.entryId}" },
            ) { index, hit ->
                TierACompactRow(
                    hit = hit,
                    dateTimeLabel = formatDateTimeLabel(hit),
                    tagDisplayLabels = uiState.tagDisplayLabels,
                    isSelected = index == uiState.tierAIndex,
                    onClick = { onSelectTierAIndex(index) },
                )
            }
        }

        if (uiState.tierBCount > 0) {
            item(key = "tier_b_header") {
                if (uiState.tierACount > 0) {
                    Spacer(modifier = Modifier.height(8.dp))
                }
                SectionHeader(
                    title = "Older \u00B7 > 5 weeks",
                    count = uiState.tierBCount,
                )
            }

            items(
                items = uiState.tierBHits,
                key = { hit -> "tier_b_${hit.entryId}" },
            ) { hit ->
                TierBResultRow(
                    hit = hit,
                    dateTimeLabel = formatDateTimeLabel(hit),
                    tagDisplayLabels = uiState.tagDisplayLabels,
                    onClick = {
                        onOpenContextWindow(hit.entryId, uiState.query.trim().ifEmpty { null })
                    },
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    count: Int,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = RoundedCornerShape(12.dp),
        ) {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun TierAStepperCard(
    hit: SearchHit,
    activeIndex: Int,
    totalCount: Int,
    dateTimeLabel: String,
    tagDisplayLabels: Map<String, String>,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onJumpInChat: () -> Unit,
) {
    val highlightBg = MaterialTheme.colorScheme.tertiaryContainer
    val highlightFg = MaterialTheme.colorScheme.onTertiaryContainer

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Match ${activeIndex + 1} of $totalCount",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onPrevious,
                        enabled = totalCount > 1,
                        modifier = Modifier.semantics { contentDescription = "Previous recent match" },
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.KeyboardArrowUp,
                            contentDescription = "Previous match",
                        )
                    }
                    IconButton(
                        onClick = onNext,
                        enabled = totalCount > 1,
                        modifier = Modifier.semantics { contentDescription = "Next recent match" },
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.KeyboardArrowDown,
                            contentDescription = "Next match",
                        )
                    }
                }
            }

            Text(
                text = dateTimeLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(
                text = MarkedSnippetFormatter.formatMarkedSnippet(
                    snippetMarked = hit.snippetMarked,
                    highlightBackground = highlightBg,
                    highlightTextColor = highlightFg,
                ),
                style = MaterialTheme.typography.bodyMedium,
            )

            TagAndAutoMatchBadges(
                visibleTags = hit.visibleTags,
                hiddenMatchedTags = hit.hiddenMatchedTags,
                matchedViaHiddenAutoTag = hit.matchedViaHiddenAutoTag,
                tagDisplayLabels = tagDisplayLabels,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                FilledTonalButton(onClick = onJumpInChat) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = "Jump in Chat")
                }
            }
        }
    }
}

@Composable
private fun TierACompactRow(
    hit: SearchHit,
    dateTimeLabel: String,
    tagDisplayLabels: Map<String, String>,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val highlightBg = MaterialTheme.colorScheme.tertiaryContainer
    val highlightFg = MaterialTheme.colorScheme.onTertiaryContainer

    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        border = if (isSelected) {
            BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
        } else {
            CardDefaults.outlinedCardBorder()
        },
        colors = CardDefaults.outlinedCardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f)
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = dateTimeLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = MarkedSnippetFormatter.formatMarkedSnippet(
                    snippetMarked = hit.snippetMarked,
                    highlightBackground = highlightBg,
                    highlightTextColor = highlightFg,
                ),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            TagAndAutoMatchBadges(
                visibleTags = hit.visibleTags,
                hiddenMatchedTags = hit.hiddenMatchedTags,
                matchedViaHiddenAutoTag = hit.matchedViaHiddenAutoTag,
                tagDisplayLabels = tagDisplayLabels,
            )
        }
    }
}

@Composable
private fun TierBResultRow(
    hit: SearchHit,
    dateTimeLabel: String,
    tagDisplayLabels: Map<String, String>,
    onClick: () -> Unit,
) {
    val highlightBg = MaterialTheme.colorScheme.tertiaryContainer
    val highlightFg = MaterialTheme.colorScheme.onTertiaryContainer

    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "${hit.localDate} \u00B7 $dateTimeLabel",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.History,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = "\u00B112h",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            Text(
                text = MarkedSnippetFormatter.formatMarkedSnippet(
                    snippetMarked = hit.snippetMarked,
                    highlightBackground = highlightBg,
                    highlightTextColor = highlightFg,
                ),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )

            TagAndAutoMatchBadges(
                visibleTags = hit.visibleTags,
                hiddenMatchedTags = hit.hiddenMatchedTags,
                matchedViaHiddenAutoTag = hit.matchedViaHiddenAutoTag,
                tagDisplayLabels = tagDisplayLabels,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagAndAutoMatchBadges(
    visibleTags: List<Tag>,
    hiddenMatchedTags: List<Tag>,
    matchedViaHiddenAutoTag: Boolean,
    tagDisplayLabels: Map<String, String>,
) {
    if (visibleTags.isEmpty() && !matchedViaHiddenAutoTag) return

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (tag in visibleTags) {
            val label = tagDisplayLabels[tag.id] ?: tag.leafName
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(8.dp),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }

        if (matchedViaHiddenAutoTag) {
            val hiddenLabels = hiddenMatchedTags
                .map { tagDisplayLabels[it.id] ?: it.leafName }
                .distinct()
            val badgeText = if (hiddenLabels.isNotEmpty()) {
                "Auto: ${hiddenLabels.joinToString(", ")}"
            } else {
                "Matched via hidden auto-tag"
            }

            Surface(
                color = MaterialTheme.colorScheme.tertiaryContainer,
                shape = RoundedCornerShape(8.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AutoAwesome,
                        contentDescription = "Hidden auto-tag match",
                        tint = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        text = badgeText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }
        }
    }
}
