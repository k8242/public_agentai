// Copyright (c) 2026 k8242
// SPDX-License-Identifier: MIT

package io.github.k8242.threadlog.feature.chat.ui

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

private enum class BackdateTab(val label: String) {
    DATE("Date"),
    TIME("Time"),
    ZONE("Timezone"),
}

private val COMMON_ZONES = listOf(
    "America/Los_Angeles",
    "Pacific/Honolulu",
    "America/Denver",
    "America/Chicago",
    "America/New_York",
    "UTC",
    "Europe/London",
    "Asia/Tokyo",
)

private val BACKDATE_CHIP_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMM d, yyyy · h:mm a", Locale.US)

internal fun formatBackdateChipLabel(epochMs: Long, zoneId: String?): String {
    val zone = runCatching { zoneId?.let(ZoneId::of) }.getOrNull() ?: ZoneId.systemDefault()
    val zdt = Instant.ofEpochMilli(epochMs).atZone(zone)
    val shortZone = zone.id.substringAfterLast('/').replace('_', ' ')
    return "${zdt.format(BACKDATE_CHIP_FORMATTER)} ($shortZone)"
}

/**
 * Dialog allowing the user to backdate a new note with a specific date, time, and IANA timezone.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BackdateComposerDialog(
    initialTimestampMs: Long?,
    initialZoneId: String?,
    initialKeepAfterSend: Boolean,
    onApply: (epochMs: Long, zoneId: String, keepAfterSend: Boolean) -> Unit,
    onResetToNow: () -> Unit,
    onDismiss: () -> Unit,
) {
    val defaultZoneId = remember { ZoneId.systemDefault().id }
    var selectedZoneId by remember { mutableStateOf(initialZoneId ?: defaultZoneId) }
    var keepAfterSend by remember { mutableStateOf(initialKeepAfterSend) }
    var activeTab by remember { mutableStateOf(BackdateTab.DATE) }
    var zoneQuery by remember { mutableStateOf("") }

    val initialZdt = remember(initialTimestampMs, initialZoneId) {
        val zone = runCatching { (initialZoneId ?: defaultZoneId).let(ZoneId::of) }
            .getOrElse { ZoneId.systemDefault() }
        Instant.ofEpochMilli(initialTimestampMs ?: System.currentTimeMillis()).atZone(zone)
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

    val allZoneIds = remember { ZoneId.getAvailableZoneIds().sorted() }
    val filteredZones = remember(zoneQuery, allZoneIds) {
        val q = zoneQuery.trim().lowercase(Locale.ROOT)
        if (q.isEmpty()) {
            allZoneIds.take(40)
        } else {
            allZoneIds.filter { it.lowercase(Locale.ROOT).contains(q) }.take(60)
        }
    }

    val selectedDate: LocalDate = datePickerState.selectedDateMillis?.let { millis ->
        Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
    } ?: initialZdt.toLocalDate()
    val previewZone = runCatching { ZoneId.of(selectedZoneId) }.getOrElse { ZoneId.systemDefault() }
    val previewEpochMs = LocalDateTime.of(
        selectedDate,
        LocalTime.of(timePickerState.hour, timePickerState.minute),
    ).atZone(previewZone).toInstant().toEpochMilli()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "Backdate note",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = formatBackdateChipLabel(previewEpochMs, selectedZoneId),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    BackdateTab.entries.forEach { tab ->
                        FilterChip(
                            selected = activeTab == tab,
                            onClick = { activeTab = tab },
                            label = { Text(tab.label, style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }

                when (activeTab) {
                    BackdateTab.DATE -> {
                        DatePicker(
                            state = datePickerState,
                            title = null,
                            headline = null,
                            showModeToggle = true,
                        )
                    }

                    BackdateTab.TIME -> {
                        TimePicker(
                            state = timePickerState,
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                        )
                    }

                    BackdateTab.ZONE -> {
                        val presets = remember(defaultZoneId) {
                            (listOf(defaultZoneId) + COMMON_ZONES).distinct()
                        }
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            presets.forEach { zone ->
                                FilterChip(
                                    selected = selectedZoneId == zone,
                                    onClick = { selectedZoneId = zone },
                                    label = {
                                        Text(
                                            text = if (zone == defaultZoneId) "Device ($zone)" else zone,
                                            style = MaterialTheme.typography.labelSmall,
                                        )
                                    },
                                )
                            }
                        }

                        OutlinedTextField(
                            value = zoneQuery,
                            onValueChange = { zoneQuery = it },
                            placeholder = { Text("Search IANA timezone (e.g. America/New_York)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )

                        Surface(
                            tonalElevation = 1.dp,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 180.dp),
                        ) {
                            LazyColumn {
                                items(filteredZones, key = { it }) { zone ->
                                    Text(
                                        text = zone,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = if (zone == selectedZoneId) FontWeight.Bold else FontWeight.Normal,
                                        color = if (zone == selectedZoneId) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { selectedZoneId = zone }
                                            .padding(horizontal = 12.dp, vertical = 8.dp),
                                    )
                                    HorizontalDivider(thickness = 0.5.dp)
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { keepAfterSend = !keepAfterSend },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = keepAfterSend,
                        onCheckedChange = { keepAfterSend = it },
                    )
                    Text(
                        text = "Keep date for next note (+1 min each)",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onApply(previewEpochMs, selectedZoneId, keepAfterSend) },
            ) {
                Text("Apply")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (initialTimestampMs != null) {
                    TextButton(onClick = onResetToNow) {
                        Text("Reset to Now")
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        },
    )
}
