// Copyright (c) 2026 k8242
// SPDX-License-Identifier: MIT

package io.github.k8242.threadlog.feature.chat.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Label
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import io.github.k8242.threadlog.core.model.Attachment
import io.github.k8242.threadlog.core.model.Bookmark
import io.github.k8242.threadlog.core.model.EntryTag
import io.github.k8242.threadlog.core.model.EntryWithDetails
import io.github.k8242.threadlog.core.model.ReplyPreview
import io.github.k8242.threadlog.core.model.parser.DateDividerMeta
import io.github.k8242.threadlog.core.model.parser.ParsedMessage
import java.io.File
import kotlin.math.roundToInt

/**
 * 4-tier hierarchical date dividers (Year / Month / Week / Day) rendered above a message when
 * any tier transitions relative to its older neighbor.
 */
@Composable
fun ChatDateDividers(
    meta: DateDividerMeta,
    visibility: DividerVisibility,
    modifier: Modifier = Modifier,
) {
    if (!visibility.hasAny) return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (visibility.showYear) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                HorizontalDivider(modifier = Modifier.weight(1f))
                Text(
                    text = meta.yearLabel,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                HorizontalDivider(modifier = Modifier.weight(1f))
            }
        }

        if (visibility.showMonth) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(16.dp),
            ) {
                Text(
                    text = meta.monthLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                )
            }
        }

        if (visibility.showWeek) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(
                    text = meta.weekLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 3.dp),
                )
            }
        }

        if (visibility.showDay) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(10.dp),
            ) {
                Text(
                    text = meta.dayLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
                )
            }
        }
    }
}

/**
 * Floating translucent period indicator pinned at the top of the chat feed showing the currently
 * visible message's `monthLabel · weekLabel`.
 */
@Composable
fun StickyPeriodPill(
    monthLabel: String,
    weekLabel: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.92f),
        tonalElevation = 3.dp,
        shadowElevation = 2.dp,
        shape = RoundedCornerShape(20.dp),
    ) {
        Text(
            text = "$monthLabel \u00B7 $weekLabel",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun MessageBubbleItem(
    entryWithDetails: EntryWithDetails,
    parsedMessage: ParsedMessage,
    dateMeta: DateDividerMeta,
    tagDisplayLabels: Map<String, String>,
    showHiddenAutoTags: Boolean,
    resolveAttachmentPath: (String) -> String,
    onReply: () -> Unit,
    onEdit: () -> Unit,
    onManageTags: () -> Unit,
    onToggleTask: () -> Unit,
    onToggleTaskDone: () -> Unit,
    onDelete: () -> Unit,
    onToggleBookmarkCollapsed: (bookmarkId: String, currentlyCollapsed: Boolean) -> Unit,
    onPromoteAutoTag: (tagId: String) -> Unit,
    onRejectAutoTag: (tagId: String) -> Unit,
    onImageClick: (String) -> Unit,
    onTagClick: (TagChipTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val maxDragPx = remember(density) { with(density) { 64.dp.toPx() } }
    val triggerDragPx = remember(density) { with(density) { 44.dp.toPx() } }

    var dragOffsetPx by remember(entryWithDetails.entry.id) { mutableFloatStateOf(0f) }
    var triggeredReplyOnDrag by remember(entryWithDetails.entry.id) { mutableStateOf(false) }
    val animatedOffsetPx by animateFloatAsState(targetValue = dragOffsetPx, label = "swipeReplyOffset")
    var menuExpanded by remember { mutableStateOf(false) }

    val entry = entryWithDetails.entry
    val isTask = entry.isTask
    val isTaskDone = entry.isTaskDone

    val bubbleContainerColor = when {
        isTask && !isTaskDone -> MaterialTheme.colorScheme.surfaceContainerLow
        isTaskDone -> MaterialTheme.colorScheme.surfaceContainerLowest
        else -> MaterialTheme.colorScheme.surfaceContainerLow
    }
    val bubbleBorderColor = when {
        isTask && !isTaskDone -> MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
        else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
    }
    val bubbleBorderWidth = if (isTask && !isTaskDone) 1.dp else 0.5.dp

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 3.dp)
            .pointerInput(entry.id) {
                detectHorizontalDragGestures(
                    onHorizontalDrag = { _, dragAmount ->
                        val next = (dragOffsetPx + dragAmount).coerceIn(0f, maxDragPx)
                        dragOffsetPx = next
                        if (next >= triggerDragPx && !triggeredReplyOnDrag) {
                            triggeredReplyOnDrag = true
                        }
                    },
                    onDragEnd = {
                        if (triggeredReplyOnDrag) {
                            onReply()
                        }
                        dragOffsetPx = 0f
                        triggeredReplyOnDrag = false
                    },
                    onDragCancel = {
                        dragOffsetPx = 0f
                        triggeredReplyOnDrag = false
                    },
                )
            },
    ) {
        if (animatedOffsetPx > 4f) {
            val iconAlpha = (animatedOffsetPx / triggerDragPx).coerceIn(0f, 1f)
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.Reply,
                contentDescription = "Swipe to quote",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 8.dp)
                    .alpha(iconAlpha),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(animatedOffsetPx.roundToInt(), 0) },
            horizontalArrangement = Arrangement.End,
        ) {
            Surface(
                color = bubbleContainerColor,
                tonalElevation = if (isTaskDone) 0.dp else 1.dp,
                shape = RoundedCornerShape(
                    topStart = 16.dp,
                    topEnd = 4.dp,
                    bottomStart = 16.dp,
                    bottomEnd = 16.dp,
                ),
                border = BorderStroke(
                    width = bubbleBorderWidth,
                    color = bubbleBorderColor,
                ),
                modifier = Modifier
                    .fillMaxWidth(0.80f)
                    .combinedClickable(
                        onClick = {},
                        onLongClick = { menuExpanded = true },
                    ),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // Header row: Reply preview / TODO badge (if any) + overflow action menu
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            if (isTask) {
                                Surface(
                                    color = if (isTaskDone) {
                                        MaterialTheme.colorScheme.surfaceContainerHigh
                                    } else {
                                        MaterialTheme.colorScheme.primaryContainer
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.clickable(onClick = onToggleTaskDone),
                                ) {
                                    Text(
                                        text = if (isTaskDone) "DONE" else "TODO",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (isTaskDone) {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        } else {
                                            MaterialTheme.colorScheme.onPrimaryContainer
                                        },
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    )
                                }
                            }
                            entryWithDetails.replyPreview?.let { preview ->
                                QuotedReplyBar(replyPreview = preview)
                            }
                        }

                        Box {
                            IconButton(
                                onClick = { menuExpanded = true },
                                modifier = Modifier.size(24.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.MoreVert,
                                    contentDescription = "Message actions",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            DropdownMenu(
                                expanded = menuExpanded,
                                onDismissRequest = { menuExpanded = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text(if (isTask) "Remove TODO" else "Mark as TODO") },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = if (isTask) Icons.Filled.CheckBox else Icons.Outlined.CheckBoxOutlineBlank,
                                            contentDescription = null,
                                        )
                                    },
                                    onClick = {
                                        menuExpanded = false
                                        onToggleTask()
                                    },
                                )
                                if (isTask) {
                                    DropdownMenuItem(
                                        text = { Text(if (isTaskDone) "Reopen TODO" else "Mark Done") },
                                        leadingIcon = {
                                            Icon(Icons.Outlined.TaskAlt, contentDescription = null)
                                        },
                                        onClick = {
                                            menuExpanded = false
                                            onToggleTaskDone()
                                        },
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("Reply") },
                                    leadingIcon = {
                                        Icon(Icons.AutoMirrored.Outlined.Reply, contentDescription = null)
                                    },
                                    onClick = {
                                        menuExpanded = false
                                        onReply()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Edit") },
                                    leadingIcon = {
                                        Icon(Icons.Outlined.Edit, contentDescription = null)
                                    },
                                    onClick = {
                                        menuExpanded = false
                                        onEdit()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Tags") },
                                    leadingIcon = {
                                        Icon(Icons.Outlined.Label, contentDescription = null)
                                    },
                                    onClick = {
                                        menuExpanded = false
                                        onManageTags()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Delete") },
                                    leadingIcon = {
                                        Icon(Icons.Outlined.Delete, contentDescription = null)
                                    },
                                    onClick = {
                                        menuExpanded = false
                                        onDelete()
                                    },
                                )
                            }
                        }
                    }

                    // Body text (with 1-tap inline task checkbox when this note is a TODO)
                    if (entry.bodyRaw.isNotBlank() || isTask) {
                        if (isTask) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.Top,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                IconButton(
                                    onClick = onToggleTaskDone,
                                    modifier = Modifier.size(24.dp),
                                ) {
                                    Icon(
                                        imageVector = if (isTaskDone) {
                                            Icons.Filled.CheckBox
                                        } else {
                                            Icons.Outlined.CheckBoxOutlineBlank
                                        },
                                        contentDescription = if (isTaskDone) "Reopen TODO" else "Complete TODO",
                                        tint = if (isTaskDone) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.primary
                                        },
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                                if (entry.bodyRaw.isNotBlank()) {
                                    FormattedMessageBody(
                                        parsedMessage = parsedMessage,
                                        isCompletedTask = isTaskDone,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        } else {
                            FormattedMessageBody(parsedMessage = parsedMessage)
                        }
                    }

                    // Attachments grid
                    if (entryWithDetails.attachments.isNotEmpty()) {
                        AttachmentGrid(
                            attachments = entryWithDetails.attachments,
                            resolveAttachmentPath = resolveAttachmentPath,
                            onImageClick = onImageClick,
                        )
                    }

                    // Collapsible bookmarks
                    if (entryWithDetails.bookmarks.isNotEmpty()) {
                        BookmarkList(
                            bookmarks = entryWithDetails.bookmarks,
                            onToggleCollapsed = onToggleBookmarkCollapsed,
                        )
                    }

                    // Visible leaf-only tags
                    val visibleTags = entryWithDetails.visibleTags
                    if (visibleTags.isNotEmpty()) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            visibleTags.forEach { entryTag ->
                                val label = tagDisplayLabels[entryTag.tagId] ?: entryTag.tag.leafName
                                TagPillChip(
                                    label = label,
                                    colorToken = entryTag.tag.colorToken,
                                    onClick = {
                                        onTagClick(
                                            TagChipTarget(
                                                tagId = entryTag.tagId,
                                                fullPath = entryTag.tag.fullPath,
                                                displayLabel = label,
                                            ),
                                        )
                                    },
                                )
                            }
                        }
                    }

                    // Optional hidden auto-tags row with inline Promote / Reject controls
                    val hiddenAutoTags = entryWithDetails.hiddenAutoTags
                    if (showHiddenAutoTags && hiddenAutoTags.isNotEmpty()) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            hiddenAutoTags.forEach { entryTag ->
                                val label = tagDisplayLabels[entryTag.tagId] ?: entryTag.tag.leafName
                                HiddenAutoTagChip(
                                    label = label,
                                    onPromote = { onPromoteAutoTag(entryTag.tagId) },
                                    onReject = { onRejectAutoTag(entryTag.tagId) },
                                )
                            }
                        }
                    }

                    // Footer: write-time timestamp + optional Edited badge
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (entry.isEdited) {
                            Text(
                                text = "Edited",
                                style = MaterialTheme.typography.labelSmall,
                                fontStyle = FontStyle.Italic,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = " \u00B7 ",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            text = dateMeta.timeLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

data class TagChipTarget(
    val tagId: String,
    val fullPath: String,
    val displayLabel: String,
)

@Composable
private fun QuotedReplyBar(replyPreview: ReplyPreview) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.7f))
            .padding(end = 8.dp),
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.tertiary),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = if (replyPreview.isDeleted) "Original message deleted" else replyPreview.snippet,
            style = MaterialTheme.typography.bodySmall,
            fontStyle = if (replyPreview.isDeleted) FontStyle.Italic else FontStyle.Normal,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(vertical = 5.dp),
        )
    }
}

@Composable
private fun AttachmentGrid(
    attachments: List<Attachment>,
    resolveAttachmentPath: (String) -> String,
    onImageClick: (String) -> Unit,
) {
    if (attachments.size == 1) {
        val path = resolveAttachmentPath(attachments[0].localFilename)
        AsyncImage(
            model = File(path),
            contentDescription = "Attached image",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 120.dp, max = 220.dp)
                .clip(RoundedCornerShape(10.dp))
                .clickable { onImageClick(path) },
        )
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        attachments.chunked(2).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                rowItems.forEach { att ->
                    val path = resolveAttachmentPath(att.localFilename)
                    AsyncImage(
                        model = File(path),
                        contentDescription = "Attached image",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1.25f)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onImageClick(path) },
                    )
                }
                if (rowItems.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BookmarkList(
    bookmarks: List<Bookmark>,
    onToggleCollapsed: (bookmarkId: String, currentlyCollapsed: Boolean) -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val collapsed = bookmarks.filter { it.isCollapsed }
        val expanded = bookmarks.filterNot { it.isCollapsed }

        if (collapsed.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                collapsed.forEach { bookmark ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.clickable {
                            onToggleCollapsed(bookmark.id, true)
                        },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Link,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(14.dp),
                            )
                            Text(
                                text = bookmark.domain,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Icon(
                                imageVector = Icons.Outlined.ExpandMore,
                                contentDescription = "Expand bookmark",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }
            }
        }

        expanded.forEach { bookmark ->
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggleCollapsed(bookmark.id, false) }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Link,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = bookmark.title?.takeIf { it.isNotBlank() } ?: bookmark.domain,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = bookmark.urlRaw,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(
                        onClick = { runCatching { uriHandler.openUri(bookmark.urlRaw) } },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                            contentDescription = "Open link in browser",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    Icon(
                        imageVector = Icons.Outlined.ExpandLess,
                        contentDescription = "Collapse bookmark",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
internal fun tagColorPair(colorToken: String): Pair<Color, Color> = when (colorToken.lowercase()) {
    "people" -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
    "projects" -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
    "topics" -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
    "auto" -> MaterialTheme.colorScheme.surfaceContainerHighest to MaterialTheme.colorScheme.onSurfaceVariant
    else -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
fun TagPillChip(
    label: String,
    colorToken: String,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val (bg, fg) = tagColorPair(colorToken)
    Surface(
        color = bg,
        shape = RoundedCornerShape(12.dp),
        modifier = if (onClick != null) modifier.clickable(onClick = onClick) else modifier,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun HiddenAutoTagChip(
    label: String,
    onPromote: () -> Unit,
    onReject: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)),
    ) {
        Row(
            modifier = Modifier.padding(start = 6.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(12.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            IconButton(
                onClick = onPromote,
                modifier = Modifier.size(22.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Check,
                    contentDescription = "Promote auto-tag $label",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(14.dp),
                )
            }
            IconButton(
                onClick = onReject,
                modifier = Modifier.size(22.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = "Reject auto-tag $label",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

/**
 * Fullscreen pinch-to-zoom image preview dialog for compressed message attachments.
 */
@Composable
fun FullscreenImageDialog(
    filePath: String,
    onDismiss: () -> Unit,
) {
    var scale by remember(filePath) { mutableFloatStateOf(1f) }
    var offset by remember(filePath) { mutableStateOf(Offset.Zero) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.94f))
                .pointerInput(filePath) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val nextScale = (scale * zoom).coerceIn(1f, 4f)
                        scale = nextScale
                        offset = if (nextScale > 1f) offset + pan else Offset.Zero
                    }
                },
        ) {
            AsyncImage(
                model = File(filePath),
                contentDescription = "Fullscreen attachment preview",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y,
                    ),
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = "Close preview",
                    tint = Color.White,
                )
            }
        }
    }
}
