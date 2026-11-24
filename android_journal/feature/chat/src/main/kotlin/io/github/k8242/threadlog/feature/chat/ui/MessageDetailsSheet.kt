// Copyright (c) 2026 k8242
// SPDX-License-Identifier: MIT

package io.github.k8242.threadlog.feature.chat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.k8242.threadlog.core.model.EntryWithDetails
import io.github.k8242.threadlog.core.model.TaskPriority
import io.github.k8242.threadlog.core.model.TaskStatus
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

private enum class DetailsTimeEditorTab(val label: String) {
    DATE("Date"),
    TIME("Time"),
    ZONE("Timezone"),
}

/**
 * Extra Message Details modal sheet displaying read-only entry metadata and allowing edits to
 * creation date/time/IANA timezone, task status, task priority, yellow highlight, and archive state.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MessageDetailsSheet(
    entryWithDetails: EntryWithDetails,
    onSave: (
        createdAtMs: Long,
        zoneId: String,
        taskStatus: TaskStatus,
        taskPriority: TaskPriority,
        isHighlighted: Boolean,
        archivedAtMs: Long?,
    ) -> Unit,
    onDismiss: () -> Unit,
) {
    val entry = entryWithDetails.entry
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var selectedZoneId by remember(entry.id) { mutableStateOf(entry.zoneId) }
    var activeTimeTab by remember(entry.id) { mutableStateOf<DetailsTimeEditorTab?>(null) }
    var taskStatus by remember(entry.id) { mutableStateOf(entry.taskStatus) }
    var taskPriority by remember(entry.id) { mutableStateOf(entry.taskPriority) }
    var isHighlighted by remember(entry.id) { mutableStateOf(entry.isHighlighted) }
    var isArchived by remember(entry.id) { mutableStateOf(entry.isArchived) }

    val initialZdt = remember(entry.createdAtMs, entry.zoneId) {
        val zone = runCatching { ZoneId.of(entry.zoneId) }.getOrElse { ZoneId.systemDefault() }
        Instant.ofEpochMilli(entry.createdAtMs).atZone(zone)
    }
    val initialUtcMidnightMs = remember(initialZdt) {
        initialZdt.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    }
    val datePickerState = rememberDatePickerState(initialSelectedDateMillis = initialUtcMidnightMs)
    val timePickerState = rememberTimePickerState(
        initialHour = initialZdt.hour,
        initialMinute = initialZdt.minute,
        is24Hour = false,
    )

    val selectedDate: LocalDate = datePickerState.selectedDateMillis?.let { millis ->
        Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
    } ?: initialZdt.toLocalDate()
    val previewZone = runCatching { ZoneId.of(selectedZoneId) }.getOrElse { ZoneId.systemDefault() }
    val previewEpochMs = LocalDateTime.of(
        selectedDate,
        LocalTime.of(timePickerState.hour, timePickerState.minute),
    ).atZone(previewZone).toInstant().toEpochMilli()

    val hexOrdinal = remember(entryWithDetails.dailyOrdinal) {
        "0x${entryWithDetails.dailyOrdinal.toString(16).uppercase(Locale.ROOT)}"
    }
    val totalAttachmentBytes = remember(entryWithDetails.attachments) {
        entryWithDetails.attachments.sumOf { it.byteSize }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Message details",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )

            // Read-only info summary card
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    DetailInfoRow(label = "Entry ID", value = entry.id, monospace = true)
                    DetailInfoRow(label = "Daily ordinal", value = hexOrdinal, monospace = true)
                    DetailInfoRow(
                        label = "Created",
                        value = formatBackdateChipLabel(entry.createdAtMs, entry.zoneId),
                    )
                    DetailInfoRow(
                        label = "Edited",
                        value = entry.updatedAtMs
                            ?.takeIf { it > entry.createdAtMs }
                            ?.let { formatBackdateChipLabel(it, entry.zoneId) }
                            ?: "Never",
                    )
                    DetailInfoRow(
                        label = "Attachments",
                        value = if (entryWithDetails.attachments.isEmpty()) {
                            "None"
                        } else {
                            "${entryWithDetails.attachments.size} (${formatByteCount(totalAttachmentBytes)})"
                        },
                    )
                    DetailInfoRow(
                        label = "Status",
                        value = if (entry.isArchived) {
                            "Archived (${entry.archivedAtMs?.let { formatBackdateChipLabel(it, entry.zoneId) }.orEmpty()})"
                        } else {
                            "Active"
                        },
                    )
                }
            }

            HorizontalDivider()

            // Editable Timestamp & Timezone
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Date, time & timezone",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "${formatBackdateChipLabel(previewEpochMs, selectedZoneId)} \u00B7 $selectedZoneId",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    DetailsTimeEditorTab.entries.forEach { tab ->
                        FilterChip(
                            selected = activeTimeTab == tab,
                            onClick = {
                                activeTimeTab = if (activeTimeTab == tab) null else tab
                            },
                            label = { Text(tab.label, style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }
                when (activeTimeTab) {
                    DetailsTimeEditorTab.DATE -> {
                        DatePicker(
                            state = datePickerState,
                            title = null,
                            headline = null,
                            showModeToggle = true,
                        )
                    }

                    DetailsTimeEditorTab.TIME -> {
                        TimePicker(
                            state = timePickerState,
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                        )
                    }

                    DetailsTimeEditorTab.ZONE -> {
                        IanaTimezonePickerSection(
                            selectedZoneId = selectedZoneId,
                            onSelectZone = { selectedZoneId = it },
                        )
                    }

                    null -> Unit
                }
            }

            HorizontalDivider()

            // Editable Task Status
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Task status",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    listOf(
                        TaskStatus.NONE to "Regular note",
                        TaskStatus.TODO to "TODO",
                        TaskStatus.DONE to "Done",
                    ).forEach { (status, label) ->
                        FilterChip(
                            selected = taskStatus == status,
                            onClick = {
                                taskStatus = status
                                if (status == TaskStatus.NONE) {
                                    taskPriority = TaskPriority.NONE
                                }
                            },
                            label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }
            }

            // Editable Priority (enforcing task-only priority rule)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Priority",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    listOf(
                        TaskPriority.HIGH to "High",
                        TaskPriority.MEDIUM to "Med",
                        TaskPriority.LOW to "Low",
                        TaskPriority.NONE to "None",
                    ).forEach { (priority, label) ->
                        FilterChip(
                            selected = taskPriority == priority,
                            onClick = {
                                taskPriority = priority
                                if (priority != TaskPriority.NONE && taskStatus == TaskStatus.NONE) {
                                    taskStatus = TaskStatus.TODO
                                }
                            },
                            label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }
            }

            HorizontalDivider()

            // Highlight & Archive switches
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Yellow highlight",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = "Stand out in Chat, Weekly, and Calendar views",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = isHighlighted,
                    onCheckedChange = { isHighlighted = it },
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Archived",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = "Hide from All Notes, TODOs, Weekly, Calendar & default Search",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = isArchived,
                    onCheckedChange = { isArchived = it },
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
                Button(
                    onClick = {
                        val effectivePriority = if (taskStatus == TaskStatus.NONE) {
                            TaskPriority.NONE
                        } else {
                            taskPriority
                        }
                        val nextArchivedAtMs = when {
                            isArchived && entry.archivedAtMs != null -> entry.archivedAtMs
                            isArchived -> System.currentTimeMillis()
                            else -> null
                        }
                        onSave(
                            previewEpochMs,
                            selectedZoneId,
                            taskStatus,
                            effectivePriority,
                            isHighlighted,
                            nextArchivedAtMs,
                        )
                    },
                ) {
                    Text("Save details")
                }
            }
        }
    }
}

@Composable
private fun DetailInfoRow(
    label: String,
    value: String,
    monospace: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.34f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(0.66f),
        )
    }
}

private fun formatByteCount(bytes: Long): String = when {
    bytes >= 1_048_576L -> String.format(Locale.US, "%.1f MB", bytes / 1_048_576.0)
    bytes >= 1_024L -> String.format(Locale.US, "%.1f KB", bytes / 1_024.0)
    else -> "$bytes B"
}
