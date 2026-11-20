package com.threadlog.feature.autotag.ui

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.threadlog.core.model.Entry
import com.threadlog.core.model.EntryTag
import com.threadlog.core.model.EntryWithDetails
import com.threadlog.core.model.TagRootCategory
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Full-screen Auto-Tag Review destination backed by [AutoTagReviewViewModel].
 */
@Composable
fun AutoTagReviewScreen(
    onNavigateBack: (() -> Unit)? = null,
    onEntryClick: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
    viewModel: AutoTagReviewViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    AutoTagReviewScreen(
        uiState = uiState,
        onPromoteTag = viewModel::promoteTag,
        onRejectTag = viewModel::rejectTag,
        onPromoteAllForEntry = viewModel::promoteAllForEntry,
        onRejectAllForEntry = viewModel::rejectAllForEntry,
        onDismissStatusMessage = viewModel::clearStatusMessage,
        onNavigateBack = onNavigateBack,
        onEntryClick = onEntryClick,
        modifier = modifier,
    )
}

/**
 * Stateless overload of [AutoTagReviewScreen] for previews and isolated UI testing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutoTagReviewScreen(
    uiState: AutoTagReviewUiState,
    onPromoteTag: (entryId: String, tagId: String) -> Unit,
    onRejectTag: (entryId: String, tagId: String) -> Unit,
    onPromoteAllForEntry: (entryId: String) -> Unit,
    onRejectAllForEntry: (entryId: String) -> Unit,
    onDismissStatusMessage: () -> Unit,
    onNavigateBack: (() -> Unit)? = null,
    onEntryClick: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.statusMessage) {
        val message = uiState.statusMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        onDismissStatusMessage()
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Auto-Tag Review",
                            style = MaterialTheme.typography.titleLarge,
                        )
                        if (!uiState.isLoading) {
                            Text(
                                text = pendingSubtitle(uiState),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    if (onNavigateBack != null) {
                        IconButton(onClick = onNavigateBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = "Back",
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { innerPadding ->
        AutoTagReviewBody(
            uiState = uiState,
            onPromoteTag = onPromoteTag,
            onRejectTag = onRejectTag,
            onPromoteAllForEntry = onPromoteAllForEntry,
            onRejectAllForEntry = onRejectAllForEntry,
            onEntryClick = onEntryClick,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        )
    }
}

/**
 * Drawer / sheet-friendly Auto-Tag Review content backed by [AutoTagReviewViewModel].
 */
@Composable
fun AutoTagReviewDrawerContent(
    onEntryClick: ((String) -> Unit)? = null,
    onClose: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    viewModel: AutoTagReviewViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    AutoTagReviewDrawerContent(
        uiState = uiState,
        onPromoteTag = viewModel::promoteTag,
        onRejectTag = viewModel::rejectTag,
        onPromoteAllForEntry = viewModel::promoteAllForEntry,
        onRejectAllForEntry = viewModel::rejectAllForEntry,
        onEntryClick = onEntryClick,
        onClose = onClose,
        modifier = modifier,
    )
}

/**
 * Stateless overload of [AutoTagReviewDrawerContent] suitable for embedding inside a
 * `ModalDrawerSheet` or bottom sheet.
 */
@Composable
fun AutoTagReviewDrawerContent(
    uiState: AutoTagReviewUiState,
    onPromoteTag: (entryId: String, tagId: String) -> Unit,
    onRejectTag: (entryId: String, tagId: String) -> Unit,
    onPromoteAllForEntry: (entryId: String) -> Unit,
    onRejectAllForEntry: (entryId: String) -> Unit,
    onEntryClick: ((String) -> Unit)? = null,
    onClose: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Pending Auto-Tags",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (!uiState.isLoading) {
                    Text(
                        text = pendingSubtitle(uiState),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (uiState.pendingTagCount > 0) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Text(
                        text = uiState.pendingTagCount.toString(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }

            if (onClose != null) {
                Spacer(modifier = Modifier.width(4.dp))
                IconButton(onClick = onClose) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Close auto-tag review",
                    )
                }
            }
        }

        HorizontalDivider()

        AutoTagReviewBody(
            uiState = uiState,
            onPromoteTag = onPromoteTag,
            onRejectTag = onRejectTag,
            onPromoteAllForEntry = onPromoteAllForEntry,
            onRejectAllForEntry = onRejectAllForEntry,
            onEntryClick = onEntryClick,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )
    }
}

@Composable
private fun AutoTagReviewBody(
    uiState: AutoTagReviewUiState,
    onPromoteTag: (entryId: String, tagId: String) -> Unit,
    onRejectTag: (entryId: String, tagId: String) -> Unit,
    onPromoteAllForEntry: (entryId: String) -> Unit,
    onRejectAllForEntry: (entryId: String) -> Unit,
    onEntryClick: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    when {
        uiState.isLoading -> {
            Box(
                modifier = modifier,
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        }

        uiState.isEmpty -> {
            AutoTagReviewEmptyState(modifier = modifier)
        }

        else -> {
            LazyColumn(
                modifier = modifier,
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(
                    items = uiState.pendingEntries,
                    key = { it.entry.id },
                ) { entryWithDetails ->
                    PendingAutoTagEntryCard(
                        entryWithDetails = entryWithDetails,
                        displayLabelFor = uiState::displayLabelFor,
                        onPromoteTag = { tagId ->
                            onPromoteTag(entryWithDetails.entry.id, tagId)
                        },
                        onRejectTag = { tagId ->
                            onRejectTag(entryWithDetails.entry.id, tagId)
                        },
                        onPromoteAll = {
                            onPromoteAllForEntry(entryWithDetails.entry.id)
                        },
                        onRejectAll = {
                            onRejectAllForEntry(entryWithDetails.entry.id)
                        },
                        onEntryClick = onEntryClick?.let { callback ->
                            { callback(entryWithDetails.entry.id) }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun AutoTagReviewEmptyState(
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.AutoAwesome,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(48.dp),
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "No pending auto-tags",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Suggested tags detected from your rules and existing tags will appear here so you can promote or dismiss them.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PendingAutoTagEntryCard(
    entryWithDetails: EntryWithDetails,
    displayLabelFor: (com.threadlog.core.model.Tag) -> String,
    onPromoteTag: (tagId: String) -> Unit,
    onRejectTag: (tagId: String) -> Unit,
    onPromoteAll: () -> Unit,
    onRejectAll: () -> Unit,
    onEntryClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val entry = entryWithDetails.entry
    val hiddenTags = entryWithDetails.hiddenAutoTags

    ElevatedCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = formatEntryTimestamp(entry),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (hiddenTags.size > 1) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = onPromoteAll) {
                            Text(text = "Promote all")
                        }
                        TextButton(onClick = onRejectAll) {
                            Text(text = "Dismiss all")
                        }
                    }
                }
            }

            val previewModifier = if (onEntryClick != null) {
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onEntryClick)
            } else {
                Modifier.fillMaxWidth()
            }

            Text(
                text = entry.bodyPlain.ifBlank { "(No text)" },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = previewModifier,
            )

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (entryTag in hiddenTags) {
                    PendingAutoTagChip(
                        entryTag = entryTag,
                        label = displayLabelFor(entryTag.tag),
                        onPromote = { onPromoteTag(entryTag.tagId) },
                        onReject = { onRejectTag(entryTag.tagId) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PendingAutoTagChip(
    entryTag: EntryTag,
    label: String,
    onPromote: () -> Unit,
    onReject: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val confidencePercent = (entryTag.confidence.coerceIn(0f, 1f) * 100f).roundToInt()
    val containerColor = when (entryTag.tag.rootCategory) {
        TagRootCategory.PEOPLE -> MaterialTheme.colorScheme.primaryContainer
        TagRootCategory.PROJECTS -> MaterialTheme.colorScheme.tertiaryContainer
        TagRootCategory.TOPICS -> MaterialTheme.colorScheme.secondaryContainer
        TagRootCategory.AUTO,
        TagRootCategory.OTHER,
        -> MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = when (entryTag.tag.rootCategory) {
        TagRootCategory.PEOPLE -> MaterialTheme.colorScheme.onPrimaryContainer
        TagRootCategory.PROJECTS -> MaterialTheme.colorScheme.onTertiaryContainer
        TagRootCategory.TOPICS -> MaterialTheme.colorScheme.onSecondaryContainer
        TagRootCategory.AUTO,
        TagRootCategory.OTHER,
        -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = containerColor,
        contentColor = contentColor,
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "#$label",
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "$confidencePercent%",
                style = MaterialTheme.typography.labelSmall,
                color = contentColor.copy(alpha = 0.8f),
            )
            Spacer(modifier = Modifier.width(4.dp))
            IconButton(
                onClick = onPromote,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = "Promote $label",
                    modifier = Modifier.size(18.dp),
                )
            }
            IconButton(
                onClick = onReject,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "Reject $label",
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

private fun pendingSubtitle(uiState: AutoTagReviewUiState): String {
    val count = uiState.pendingTagCount
    val entryCount = uiState.pendingEntries.size
    return when {
        count == 0 -> "All caught up"
        count == 1 -> "1 pending suggestion"
        entryCount == 1 -> "$count pending suggestions on 1 entry"
        else -> "$count pending suggestions across $entryCount entries"
    }
}

private val TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm", Locale.US)

private fun formatEntryTimestamp(entry: Entry): String {
    val zone = try {
        ZoneId.of(entry.zoneId)
    } catch (_: Exception) {
        ZoneId.systemDefault()
    }
    val timeText = Instant.ofEpochMilli(entry.createdAtMs)
        .atZone(zone)
        .format(TIME_FORMATTER)
    return "${entry.localDate} · $timeText"
}
