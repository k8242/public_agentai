// Copyright (c) 2026 k8242
// SPDX-License-Identifier: MIT

package io.github.k8242.threadlog.feature.backup.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.k8242.threadlog.core.model.TaskStatus
import io.github.k8242.threadlog.core.parser.WhatsAppDateOrder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val WHATSAPP_PREVIEW_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMM d, yyyy · h:mm a", Locale.US)

private val COMMON_WHATSAPP_ZONES = listOf(
    "America/Los_Angeles",
    "Pacific/Honolulu",
    "America/Denver",
    "America/Chicago",
    "America/New_York",
    "UTC",
    "Europe/London",
    "Asia/Tokyo",
)

@Composable
fun WhatsAppImportEntryCard(
    isBusy: Boolean,
    onOpenPasteDialog: () -> Unit,
    onImportTxtFile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Import from WhatsApp",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Paste exported WhatsApp chat text or pick a .txt export. Specify the timezone and review, edit, or filter every message before importing.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onOpenPasteDialog,
                    enabled = !isBusy,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ContentPaste,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Paste Text", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                OutlinedButton(
                    onClick = onImportTxtFile,
                    enabled = !isBusy,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Description,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Import .txt", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
fun WhatsAppPasteDialog(
    onParseText: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var pastedText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Paste WhatsApp Export") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Paste messages in WhatsApp export format (e.g. 8/13/22, 2:11 PM - Name: message).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = pastedText,
                    onValueChange = { pastedText = it },
                    placeholder = { Text("8/13/22, 2:11 PM - Yutong: ...") },
                    minLines = 6,
                    maxLines = 12,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onParseText(pastedText) },
                enabled = pastedText.isNotBlank(),
            ) {
                Text("Parse & Review")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WhatsAppReviewCard(
    draft: WhatsAppImportDraft,
    isBusy: Boolean,
    onUpdateZoneId: (String) -> Unit,
    onUpdateDateOrder: (WhatsAppDateOrder) -> Unit,
    onToggleIncludeSender: (Boolean) -> Unit,
    onToggleSkipMediaOmitted: (Boolean) -> Unit,
    onToggleItemSelected: (Int) -> Unit,
    onSetAllSelected: (Boolean) -> Unit,
    onToggleItemTask: (Int) -> Unit,
    onUpdateItemBody: (Int, String) -> Unit,
    onMaskItemCredentials: (Int) -> Unit,
    onMaskAllCredentials: () -> Unit,
    onConfirmImport: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showZonePicker by remember { mutableStateOf(false) }
    var editingItem by remember { mutableStateOf<WhatsAppReviewItem?>(null) }

    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Review WhatsApp Import (${draft.sourceLabel})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )

            // Summary line
            val summaryText = buildString {
                append("${draft.visibleItems.size} shown · ${draft.selectedCount} selected")
                if (draft.skipMediaOmitted && draft.mediaOmittedCount > 0) {
                    append(" · ${draft.mediaOmittedCount} media skipped")
                }
                if (draft.duplicateCount > 0) {
                    append(" · ${draft.duplicateCount} already in journal")
                }
            }
            Text(
                text = summaryText,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )

            // Timezone selector button
            OutlinedButton(
                onClick = { showZonePicker = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Public,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Timezone: ${draft.zoneId}",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Date order + sender + <Media omitted> toggles
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                WhatsAppDateOrder.entries.forEach { order ->
                    FilterChip(
                        selected = draft.dateOrder == order,
                        onClick = { onUpdateDateOrder(order) },
                        label = { Text(order.shortLabel, style = MaterialTheme.typography.labelSmall) },
                    )
                }
                FilterChip(
                    selected = draft.includeSenderName,
                    onClick = { onToggleIncludeSender(!draft.includeSenderName) },
                    label = {
                        Text(
                            text = if (draft.includeSenderName) "Sender: Kept" else "Sender: Stripped",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                )
                FilterChip(
                    selected = draft.skipMediaOmitted,
                    onClick = { onToggleSkipMediaOmitted(!draft.skipMediaOmitted) },
                    label = {
                        Text(
                            text = if (draft.skipMediaOmitted) "<Media omitted>: Skipped" else "<Media omitted>: Kept",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                )
            }

            if (draft.dateOrderMismatchCount > 0) {
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "${draft.dateOrderMismatchCount} line(s) didn't match ${draft.dateOrder.shortLabel}. Try switching date format above.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.padding(10.dp),
                    )
                }
            }

            if (draft.blockedAnyCount > 0) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.WarningAmber,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = "${draft.blockedAnyCount} note(s) look like a password/PIN. Mask with xxxx or uncheck to import.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onMaskAllCredentials) {
                            Text(
                                text = "Mask all",
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }
            }

            // Bulk selection controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = { onSetAllSelected(true) }) {
                    Text("Select all")
                }
                TextButton(onClick = { onSetAllSelected(false) }) {
                    Text("Deselect all")
                }
            }

            if (draft.visibleItems.isEmpty()) {
                Text(
                    text = "No messages matched with the current settings. Check date format or paste text.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            } else {
                Surface(
                    tonalElevation = 1.dp,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp),
                ) {
                    LazyColumn(modifier = Modifier.fillMaxWidth()) {
                        items(draft.visibleItems, key = { it.index }) { item ->
                            WhatsAppReviewRow(
                                item = item,
                                includeSenderName = draft.includeSenderName,
                                onToggleSelected = { onToggleItemSelected(item.index) },
                                onToggleTask = { onToggleItemTask(item.index) },
                                onEdit = { editingItem = item },
                                onMaskCredentials = { onMaskItemCredentials(item.index) },
                            )
                            HorizontalDivider(thickness = 0.5.dp)
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onCancel,
                    enabled = !isBusy,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Cancel")
                }
                Button(
                    onClick = onConfirmImport,
                    enabled = !isBusy && draft.selectedCount > 0 && draft.blockedSelectedCount == 0,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Import ${draft.selectedCount} note${if (draft.selectedCount == 1) "" else "s"}")
                }
            }
        }
    }

    if (showZonePicker) {
        WhatsAppTimezonePickerDialog(
            currentZoneId = draft.zoneId,
            onSelectZone = { zone ->
                onUpdateZoneId(zone)
                showZonePicker = false
            },
            onDismiss = { showZonePicker = false },
        )
    }

    editingItem?.let { item ->
        val initialText = item.resolvedBody(draft.includeSenderName)
        var draftBody by remember(item.index, initialText) { mutableStateOf(initialText) }
        AlertDialog(
            onDismissRequest = { editingItem = null },
            title = { Text("Edit message before import") },
            text = {
                OutlinedTextField(
                    value = draftBody,
                    onValueChange = { draftBody = it },
                    minLines = 3,
                    maxLines = 10,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onUpdateItemBody(item.index, draftBody)
                        editingItem = null
                    },
                    enabled = draftBody.isNotBlank(),
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { editingItem = null }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WhatsAppReviewRow(
    item: WhatsAppReviewItem,
    includeSenderName: Boolean,
    onToggleSelected: () -> Unit,
    onToggleTask: () -> Unit,
    onEdit: () -> Unit,
    onMaskCredentials: () -> Unit,
) {
    val formattedTime = remember(item.createdAtMs, item.zoneId) {
        val zone = runCatching { ZoneId.of(item.zoneId) }.getOrElse { ZoneId.systemDefault() }
        Instant.ofEpochMilli(item.createdAtMs).atZone(zone).format(WHATSAPP_PREVIEW_FORMATTER)
    }
    val bodyText = item.resolvedBody(includeSenderName)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggleSelected)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Checkbox(
            checked = item.selected,
            onCheckedChange = { onToggleSelected() },
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = formattedTime,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                item.sender?.let { sender ->
                    Text(
                        text = sender,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (item.isMediaOmitted) {
                    ReviewBadge(
                        text = "Media omitted",
                        container = MaterialTheme.colorScheme.secondaryContainer,
                        content = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                if (item.isSystemMessage) {
                    ReviewBadge(
                        text = "System",
                        container = MaterialTheme.colorScheme.surfaceContainerHighest,
                        content = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (item.isDuplicate) {
                    ReviewBadge(
                        text = "Already in journal",
                        container = MaterialTheme.colorScheme.tertiaryContainer,
                        content = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
                if (item.taskStatus == TaskStatus.TODO) {
                    ReviewBadge(
                        text = "TODO",
                        container = MaterialTheme.colorScheme.primaryContainer,
                        content = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }

            Text(
                text = bodyText,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                color = if (item.selected) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )

            item.credentialWarning?.let { warning ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = warning,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onMaskCredentials) {
                        Text(
                            text = "Mask xxxx",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            IconButton(
                onClick = onEdit,
                modifier = Modifier.size(30.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Edit,
                    contentDescription = "Edit message",
                    modifier = Modifier.size(16.dp),
                )
            }
            IconButton(
                onClick = onToggleTask,
                modifier = Modifier.size(30.dp),
            ) {
                Icon(
                    imageVector = if (item.taskStatus == TaskStatus.TODO) {
                        Icons.Filled.CheckBox
                    } else {
                        Icons.Outlined.CheckBoxOutlineBlank
                    },
                    contentDescription = "Toggle TODO",
                    tint = if (item.taskStatus == TaskStatus.TODO) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun ReviewBadge(
    text: String,
    container: androidx.compose.ui.graphics.Color,
    content: androidx.compose.ui.graphics.Color,
) {
    Surface(
        color = container,
        shape = MaterialTheme.shapes.extraSmall,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = content,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WhatsAppTimezonePickerDialog(
    currentZoneId: String,
    onSelectZone: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val defaultZone = remember { ZoneId.systemDefault().id }
    val presets = remember(defaultZone) { (listOf(defaultZone) + COMMON_WHATSAPP_ZONES).distinct() }
    val allZones = remember { ZoneId.getAvailableZoneIds().sorted() }
    var query by remember { mutableStateOf("") }
    val filtered = remember(query, allZones) {
        val q = query.trim().lowercase(Locale.ROOT)
        if (q.isEmpty()) {
            allZones.take(50)
        } else {
            allZones.filter { it.lowercase(Locale.ROOT).contains(q) }.take(80)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Import Timezone") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    presets.forEach { zone ->
                        FilterChip(
                            selected = currentZoneId == zone,
                            onClick = { onSelectZone(zone) },
                            label = {
                                Text(
                                    text = if (zone == defaultZone) "Device ($zone)" else zone,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            },
                        )
                    }
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search IANA timezone (e.g. Pacific/Honolulu)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(2.dp))
                Surface(
                    tonalElevation = 1.dp,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 200.dp),
                ) {
                    LazyColumn {
                        items(filtered, key = { it }) { zone ->
                            Text(
                                text = zone,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = if (zone == currentZoneId) FontWeight.Bold else FontWeight.Normal,
                                color = if (zone == currentZoneId) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelectZone(zone) }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                            )
                            HorizontalDivider(thickness = 0.5.dp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
    )
}
