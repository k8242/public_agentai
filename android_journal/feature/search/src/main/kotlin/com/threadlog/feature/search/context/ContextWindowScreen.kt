package com.threadlog.feature.search.context

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.threadlog.core.model.Entry
import com.threadlog.core.model.EntryTag
import com.threadlog.core.model.EntryWithDetails
import com.threadlog.feature.search.ui.MarkedSnippetFormatter

/**
 * Stateful entry point for the interactive `±12h` Context Window screen.
 */
@Composable
fun ContextWindowScreen(
    anchorEntryId: String,
    highlightQuery: String? = null,
    onNavigateBack: () -> Unit = {},
    onOpenInChat: (entryId: String, localDate: String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
    viewModel: ContextWindowViewModel = hiltViewModel(),
) {
    LaunchedEffect(anchorEntryId, highlightQuery) {
        viewModel.load(anchorEntryId = anchorEntryId, highlightQuery = highlightQuery)
    }

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    ContextWindowScreen(
        uiState = uiState,
        onNavigateBack = onNavigateBack,
        onOpenInChat = { entryId, localDate ->
            val resolvedDate = localDate.ifBlank { viewModel.resolveAnchorLocalDate().orEmpty() }
            if (resolvedDate.isNotEmpty()) {
                onOpenInChat(entryId, resolvedDate)
            }
        },
        onExpandEarlier = viewModel::expandEarlier,
        onExpandLater = viewModel::expandLater,
        onResetWindow = viewModel::resetWindow,
        onToggleShowHiddenAutoTags = viewModel::toggleShowHiddenAutoTags,
        onPromoteAutoTag = viewModel::promoteAutoTag,
        onRejectAutoTag = viewModel::rejectAutoTag,
        formatEntryDateTime = viewModel::formatEntryDateTime,
        modifier = modifier,
    )
}

/**
 * Stateless overload of [ContextWindowScreen] suitable for previews and UI tests.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContextWindowScreen(
    uiState: ContextWindowUiState,
    onNavigateBack: () -> Unit,
    onOpenInChat: (entryId: String, localDate: String) -> Unit,
    onExpandEarlier: () -> Unit,
    onExpandLater: () -> Unit,
    onResetWindow: () -> Unit,
    onToggleShowHiddenAutoTags: () -> Unit,
    onPromoteAutoTag: (entryId: String, tagId: String) -> Unit,
    onRejectAutoTag: (entryId: String, tagId: String) -> Unit,
    formatEntryDateTime: (Entry) -> String,
    modifier: Modifier = Modifier,
) {
    val anchorEntry = uiState.anchorEntry

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(text = "Context Window") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Navigate back",
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onToggleShowHiddenAutoTags) {
                        Icon(
                            imageVector = Icons.Rounded.AutoAwesome,
                            contentDescription = if (uiState.showHiddenAutoTags) {
                                "Hide auto-tags"
                            } else {
                                "Show hidden auto-tags"
                            },
                            tint = if (uiState.showHiddenAutoTags) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    TextButton(
                        onClick = {
                            if (anchorEntry != null) {
                                onOpenInChat(anchorEntry.entry.id, anchorEntry.entry.localDate)
                            }
                        },
                        enabled = anchorEntry != null,
                    ) {
                        Text(text = "Open in Chat")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            if (uiState.errorMessage != null) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(
                        text = uiState.errorMessage,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }

            when {
                uiState.isLoading && uiState.contextState == null -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
                uiState.notFound -> {
                    ContextNotFoundState(
                        onNavigateBack = onNavigateBack,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                else -> {
                    val entries = uiState.contextState?.entries.orEmpty()
                    ContextWindowBanner(
                        spanLabel = uiState.windowSpanLabel,
                        entryCount = entries.size,
                        isExpanded = uiState.isExpanded,
                        onResetWindow = onResetWindow,
                    )
                    HorizontalDivider()
                    ContextWindowTimeline(
                        entries = entries,
                        anchorEntryId = uiState.anchorEntryId,
                        highlightQuery = uiState.highlightQuery,
                        tagDisplayLabels = uiState.tagDisplayLabels,
                        showHiddenAutoTags = uiState.showHiddenAutoTags,
                        onExpandEarlier = onExpandEarlier,
                        onExpandLater = onExpandLater,
                        onPromoteAutoTag = onPromoteAutoTag,
                        onRejectAutoTag = onRejectAutoTag,
                        formatEntryDateTime = formatEntryDateTime,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun ContextNotFoundState(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Entry not found",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "This entry may have been deleted or is no longer available.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FilledTonalButton(onClick = onNavigateBack) {
                Text(text = "Go Back")
            }
        }
    }
}

@Composable
private fun ContextWindowBanner(
    spanLabel: String,
    entryCount: Int,
    isExpanded: Boolean,
    onResetWindow: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(
                    text = "Window $spanLabel",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = if (entryCount == 1) "1 entry in range" else "$entryCount entries in range",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (isExpanded) {
                TextButton(onClick = onResetWindow) {
                    Text(text = "Reset \u00B112h")
                }
            }
        }
    }
}

@Composable
private fun ContextWindowTimeline(
    entries: List<EntryWithDetails>,
    anchorEntryId: String,
    highlightQuery: String?,
    tagDisplayLabels: Map<String, String>,
    showHiddenAutoTags: Boolean,
    onExpandEarlier: () -> Unit,
    onExpandLater: () -> Unit,
    onPromoteAutoTag: (entryId: String, tagId: String) -> Unit,
    onRejectAutoTag: (entryId: String, tagId: String) -> Unit,
    formatEntryDateTime: (Entry) -> String,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    var hasAutoScrolled by remember(anchorEntryId) { mutableStateOf(false) }

    LaunchedEffect(anchorEntryId, entries.size) {
        if (!hasAutoScrolled && entries.isNotEmpty()) {
            val anchorIdx = entries.indexOfFirst { it.entry.id == anchorEntryId }
            if (anchorIdx >= 0) {
                // +1 accounts for the "Load -12h" header item.
                listState.scrollToItem(anchorIdx + 1)
                hasAutoScrolled = true
            }
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "load_earlier") {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                OutlinedButton(onClick = onExpandEarlier) {
                    Icon(
                        imageVector = Icons.Rounded.ExpandLess,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = "Load -12h")
                }
            }
        }

        items(
            items = entries,
            key = { item -> item.entry.id },
        ) { item ->
            val isAnchor = item.entry.id == anchorEntryId
            ContextEntryCard(
                item = item,
                isAnchor = isAnchor,
                highlightQuery = highlightQuery,
                dateTimeLabel = formatEntryDateTime(item.entry),
                tagDisplayLabels = tagDisplayLabels,
                showHiddenAutoTags = showHiddenAutoTags,
                onPromoteAutoTag = { tagId -> onPromoteAutoTag(item.entry.id, tagId) },
                onRejectAutoTag = { tagId -> onRejectAutoTag(item.entry.id, tagId) },
            )
        }

        item(key = "load_later") {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                OutlinedButton(onClick = onExpandLater) {
                    Icon(
                        imageVector = Icons.Rounded.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = "Load +12h")
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ContextEntryCard(
    item: EntryWithDetails,
    isAnchor: Boolean,
    highlightQuery: String?,
    dateTimeLabel: String,
    tagDisplayLabels: Map<String, String>,
    showHiddenAutoTags: Boolean,
    onPromoteAutoTag: (tagId: String) -> Unit,
    onRejectAutoTag: (tagId: String) -> Unit,
) {
    val highlightBg = MaterialTheme.colorScheme.tertiaryContainer
    val highlightFg = MaterialTheme.colorScheme.onTertiaryContainer

    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        border = if (isAnchor) {
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else {
            CardDefaults.outlinedCardBorder()
        },
        colors = CardDefaults.outlinedCardColors(
            containerColor = if (isAnchor) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
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
                        text = dateTimeLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (item.entry.isEdited) {
                        Text(
                            text = "(edited)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (isAnchor) {
                    Surface(
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Text(
                            text = "Matched Entry",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
            }

            val bodyText = item.entry.bodyPlain.ifBlank { item.entry.bodyRaw }
            Text(
                text = MarkedSnippetFormatter.formatQueryHighlights(
                    text = bodyText,
                    query = highlightQuery,
                    highlightBackground = highlightBg,
                    highlightTextColor = highlightFg,
                ),
                style = MaterialTheme.typography.bodyMedium,
            )

            if (item.visibleTags.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (entryTag in item.visibleTags) {
                        val label = tagDisplayLabels[entryTag.tagId] ?: entryTag.tag.leafName
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
                }
            }

            if (showHiddenAutoTags && item.hiddenAutoTags.isNotEmpty()) {
                HiddenAutoTagControls(
                    hiddenAutoTags = item.hiddenAutoTags,
                    tagDisplayLabels = tagDisplayLabels,
                    onPromoteAutoTag = onPromoteAutoTag,
                    onRejectAutoTag = onRejectAutoTag,
                )
            }
        }
    }
}

@Composable
private fun HiddenAutoTagControls(
    hiddenAutoTags: List<EntryTag>,
    tagDisplayLabels: Map<String, String>,
    onPromoteAutoTag: (tagId: String) -> Unit,
    onRejectAutoTag: (tagId: String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (entryTag in hiddenAutoTags) {
            val label = tagDisplayLabels[entryTag.tagId] ?: entryTag.tag.leafName
            Surface(
                color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.65f),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 10.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.AutoAwesome,
                            contentDescription = "Suggested auto-tag",
                            tint = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { onPromoteAutoTag(entryTag.tagId) }) {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = "Promote $label",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        IconButton(onClick = { onRejectAutoTag(entryTag.tagId) }) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "Reject $label",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
