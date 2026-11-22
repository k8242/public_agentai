// Copyright (c) 2026 k8242
// SPDX-License-Identifier: MIT

package io.github.k8242.threadlog.feature.chat.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.content.MediaType
import androidx.compose.foundation.content.ReceiveContentListener
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.content.hasMediaType
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Label
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import io.github.k8242.threadlog.core.model.Tag
import io.github.k8242.threadlog.core.model.TagRootCategory
import io.github.k8242.threadlog.core.model.contract.PendingAttachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatComposer(
    uiState: ChatUiState,
    resolveAttachmentPath: (String) -> String,
    onTextChanged: (text: String, cursor: Int) -> Unit,
    onSelectTagSuggestion: (Tag) -> Unit,
    onCreateTriggeredTag: () -> Unit,
    onToggleSelectedTag: (String) -> Unit,
    onToggleComposerTask: () -> Unit,
    onImageBytesReceived: (ByteArray) -> Unit,
    onRemovePendingAttachment: (String) -> Unit,
    onCancelReply: () -> Unit,
    onCancelEdit: () -> Unit,
    onOpenQuickTagPicker: () -> Unit,
    onSendOrSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val bytes = readUriBytes(context, uri)
                if (bytes != null) {
                    onImageBytesReceived(bytes)
                }
            }
        }
    }

    val receiveContentListener = remember(context, scope, onImageBytesReceived) {
        ReceiveContentListener { transferableContent ->
            if (!transferableContent.hasMediaType(MediaType.Image)) {
                return@ReceiveContentListener transferableContent
            }
            transferableContent.consume { item ->
                val uri = item.uri
                if (uri != null) {
                    scope.launch {
                        val bytes = readUriBytes(context, uri)
                        if (bytes != null) {
                            onImageBytesReceived(bytes)
                        }
                    }
                    true
                } else {
                    false
                }
            }
        }
    }

    // Keep local TextFieldValue synchronized with ViewModel's text & cursor so suggestion
    // replacement updates cursor cleanly without breaking IME composition.
    var fieldValue by remember {
        mutableStateOf(
            TextFieldValue(
                text = uiState.composerText,
                selection = TextRange(uiState.composerCursor.coerceIn(0, uiState.composerText.length)),
            ),
        )
    }
    if (fieldValue.text != uiState.composerText) {
        val safeCursor = uiState.composerCursor.coerceIn(0, uiState.composerText.length)
        fieldValue = TextFieldValue(
            text = uiState.composerText,
            selection = TextRange(safeCursor),
        )
    }

    val tagsById = remember(uiState.allTags) { uiState.allTags.associateBy { it.id } }

    Surface(
        modifier = modifier.fillMaxWidth(),
        tonalElevation = 3.dp,
        shadowElevation = 4.dp,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
        ) {
            // Live @/#/+ autocomplete suggestions overlay
            val activeTrigger = uiState.activeTagTrigger
            val trimmedTriggerQuery = activeTrigger?.query?.trim()?.trim('/').orEmpty()
            if (activeTrigger != null && (uiState.tagSuggestions.isNotEmpty() || trimmedTriggerQuery.isNotEmpty())) {
                TagSuggestionList(
                    activeTrigger = activeTrigger,
                    suggestions = uiState.tagSuggestions,
                    tagDisplayLabels = uiState.tagDisplayLabels,
                    onSelect = onSelectTagSuggestion,
                    onCreateTriggeredTag = onCreateTriggeredTag,
                )
                HorizontalDivider()
            }

            // Reply or Edit context banner
            when {
                uiState.editingEntry != null -> {
                    ComposerContextBar(
                        icon = Icons.Outlined.Edit,
                        title = "Editing entry",
                        subtitle = uiState.editingEntry.entry.bodyPlain.ifBlank { "Image entry" },
                        onCancel = onCancelEdit,
                    )
                }

                uiState.replyTarget != null -> {
                    ComposerContextBar(
                        icon = Icons.AutoMirrored.Outlined.Reply,
                        title = "Replying to",
                        subtitle = uiState.replyTarget.entry.bodyPlain.ifBlank { "Image entry" },
                        onCancel = onCancelReply,
                    )
                }
            }

            // Pending attachments strip
            if (uiState.pendingAttachments.isNotEmpty() || uiState.isCompressingImage) {
                PendingAttachmentStrip(
                    attachments = uiState.pendingAttachments,
                    isCompressing = uiState.isCompressingImage,
                    resolveAttachmentPath = resolveAttachmentPath,
                    onRemove = onRemovePendingAttachment,
                )
            }

            // Selected tags & TODO mode strip
            if ((uiState.selectedTagIds.isNotEmpty() || uiState.composerIsTask) && uiState.editingEntry == null) {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (uiState.composerIsTask) {
                        item(key = "composer_todo_chip") {
                            InputChip(
                                selected = true,
                                onClick = onToggleComposerTask,
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Filled.CheckBox,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                    )
                                },
                                label = { Text(text = "TODO", style = MaterialTheme.typography.labelSmall) },
                                trailingIcon = {
                                    Icon(
                                        imageVector = Icons.Outlined.Close,
                                        contentDescription = "Unmark TODO",
                                        modifier = Modifier.size(14.dp),
                                    )
                                },
                            )
                        }
                    }
                    items(uiState.selectedTagIds, key = { it }) { tagId ->
                        val tag = tagsById[tagId]
                        val label = uiState.tagDisplayLabels[tagId] ?: tag?.leafName ?: tagId
                        InputChip(
                            selected = true,
                            onClick = { onToggleSelectedTag(tagId) },
                            label = { Text(text = label, style = MaterialTheme.typography.labelSmall) },
                            trailingIcon = {
                                Icon(
                                    imageVector = Icons.Outlined.Close,
                                    contentDescription = "Remove tag $label",
                                    modifier = Modifier.size(14.dp),
                                )
                            },
                        )
                    }
                }
            }

            // Main input bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (uiState.editingEntry == null) {
                    IconButton(
                        onClick = {
                            photoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                            )
                        },
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Image,
                            contentDescription = "Attach photo",
                        )
                    }

                    IconButton(
                        onClick = onOpenQuickTagPicker,
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Label,
                            contentDescription = "Pick tags",
                        )
                    }

                    IconButton(
                        onClick = onToggleComposerTask,
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            imageVector = if (uiState.composerIsTask) {
                                Icons.Filled.CheckBox
                            } else {
                                Icons.Outlined.CheckBoxOutlineBlank
                            },
                            contentDescription = if (uiState.composerIsTask) {
                                "Creating TODO (tap to switch to regular note)"
                            } else {
                                "Create as TODO"
                            },
                            tint = if (uiState.composerIsTask) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }

                OutlinedTextField(
                    value = fieldValue,
                    onValueChange = { next ->
                        fieldValue = next
                        onTextChanged(next.text, next.selection.end)
                    },
                    placeholder = {
                        Text(
                            text = when {
                                uiState.editingEntry != null -> "Edit journal entry..."
                                uiState.composerIsTask -> "Add a TODO... (@people, #topics, +projects)"
                                else -> "Write a note... (@people, #topics, +projects)"
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    maxLines = 5,
                    shape = RoundedCornerShape(22.dp),
                    modifier = Modifier
                        .weight(1f)
                        .contentReceiver(receiveContentListener),
                )

                val canSend = if (uiState.editingEntry != null) {
                    uiState.composerText.isNotBlank() || uiState.editingEntry.attachments.isNotEmpty()
                } else {
                    uiState.composerText.isNotBlank() || uiState.pendingAttachments.isNotEmpty()
                }

                FilledIconButton(
                    onClick = onSendOrSave,
                    enabled = canSend && !uiState.isCompressingImage,
                ) {
                    Icon(
                        imageVector = if (uiState.editingEntry != null) {
                            Icons.Outlined.Check
                        } else {
                            Icons.AutoMirrored.Outlined.Send
                        },
                        contentDescription = if (uiState.editingEntry != null) "Save edit" else "Send entry",
                    )
                }
            }
        }
    }
}

@Composable
private fun TagSuggestionList(
    activeTrigger: io.github.k8242.threadlog.core.model.parser.ActiveTagTrigger,
    suggestions: List<Tag>,
    tagDisplayLabels: Map<String, String>,
    onSelect: (Tag) -> Unit,
    onCreateTriggeredTag: () -> Unit,
) {
    val trimmedQuery = activeTrigger.query.trim().trim('/')
    val hasExactMatch = trimmedQuery.isNotEmpty() && suggestions.any {
        it.leafName.equals(trimmedQuery, ignoreCase = true) ||
            it.fullPath.equals(trimmedQuery, ignoreCase = true)
    }
    val triggerPrefix = when (activeTrigger.kind) {
        io.github.k8242.threadlog.core.model.parser.TagTriggerKind.PERSON_AT -> "@"
        io.github.k8242.threadlog.core.model.parser.TagTriggerKind.PROJECT_PLUS -> "+"
        io.github.k8242.threadlog.core.model.parser.TagTriggerKind.ALL_HASH -> "#"
    }
    val createTargetPath = when (activeTrigger.kind) {
        io.github.k8242.threadlog.core.model.parser.TagTriggerKind.PERSON_AT ->
            if (trimmedQuery.startsWith("people/", ignoreCase = true)) trimmedQuery else "people/$trimmedQuery"
        io.github.k8242.threadlog.core.model.parser.TagTriggerKind.PROJECT_PLUS ->
            if (trimmedQuery.startsWith("projects/", ignoreCase = true)) trimmedQuery else "projects/$trimmedQuery"
        io.github.k8242.threadlog.core.model.parser.TagTriggerKind.ALL_HASH ->
            if (trimmedQuery.contains('/')) trimmedQuery else "topics/$trimmedQuery"
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 180.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        if (trimmedQuery.isNotEmpty() && !hasExactMatch) {
            item(key = "create_triggered_tag") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onCreateTriggeredTag)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Add,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = "Create $triggerPrefix$trimmedQuery",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = createTargetPath,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        items(suggestions, key = { it.id }) { tag ->
            val label = tagDisplayLabels[tag.id] ?: tag.leafName
            val (badgeBg, badgeFg) = tagColorPair(tag.colorToken)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(tag) }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Surface(
                    color = badgeBg,
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        color = badgeFg,
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
            }
        }
    }
}

@Composable
private fun ComposerContextBar(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onCancel: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(
            onClick = onCancel,
            modifier = Modifier.size(28.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = "Cancel",
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun PendingAttachmentStrip(
    attachments: List<PendingAttachment>,
    isCompressing: Boolean,
    resolveAttachmentPath: (String) -> String,
    onRemove: (String) -> Unit,
) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(attachments, key = { it.localFilename }) { att ->
            Box(modifier = Modifier.size(64.dp)) {
                AsyncImage(
                    model = File(resolveAttachmentPath(att.localFilename)),
                    contentDescription = "Pending attachment",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(10.dp)),
                )
                IconButton(
                    onClick = { onRemove(att.localFilename) },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(2.dp)
                        .size(20.dp)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), CircleShape),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = "Remove attachment",
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }

        if (isCompressing) {
            item(key = "compressing_indicator") {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.5.dp,
                    )
                }
            }
        }
    }
}

/**
 * Modal bottom sheet for browsing all hierarchical tags grouped by [TagRootCategory],
 * toggling tags for either the composer or an existing entry, and creating new tag paths.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun QuickTagPickerSheet(
    title: String,
    allTags: List<Tag>,
    tagDisplayLabels: Map<String, String>,
    selectedTagIds: Set<String>,
    onToggleTag: (tagId: String, currentlySelected: Boolean) -> Unit,
    onCreateTagPath: (fullPath: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var newTagPath by remember { mutableStateOf("") }

    val groupedTags = remember(allTags) {
        allTags
            .groupBy { it.rootCategory }
            .toSortedMap(compareBy { it.ordinal })
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )

            // Create new hierarchical tag path
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = newTagPath,
                    onValueChange = { newTagPath = it },
                    placeholder = { Text("New tag path (e.g. projects/new-idea)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                FilledIconButton(
                    onClick = {
                        val trimmed = newTagPath.trim()
                        if (trimmed.isNotEmpty()) {
                            onCreateTagPath(trimmed)
                            newTagPath = ""
                        }
                    },
                    enabled = newTagPath.isNotBlank(),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Add,
                        contentDescription = "Create and select tag",
                    )
                }
            }

            if (allTags.isEmpty()) {
                Text(
                    text = "No tags yet. Create one above like people/Lex or topics/ideas.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    groupedTags.forEach { (category, tags) ->
                        item(key = "category_${category.name}") {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = categoryDisplayName(category),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    tags.sortedBy { it.leafName.lowercase() }.forEach { tag ->
                                        val isSelected = tag.id in selectedTagIds
                                        val label = tagDisplayLabels[tag.id] ?: tag.leafName
                                        FilterChip(
                                            selected = isSelected,
                                            onClick = { onToggleTag(tag.id, isSelected) },
                                            label = { Text(label) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}

private fun categoryDisplayName(category: TagRootCategory): String = when (category) {
    TagRootCategory.PEOPLE -> "People"
    TagRootCategory.PROJECTS -> "Projects"
    TagRootCategory.TOPICS -> "Topics"
    TagRootCategory.AUTO -> "Auto"
    TagRootCategory.OTHER -> "Other"
}

private suspend fun readUriBytes(context: Context, uri: Uri): ByteArray? =
    withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                stream.readBytes()
            }
        }.getOrNull()
    }
