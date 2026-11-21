// Copyright (c) 2026 k8242
// SPDX-License-Identifier: MIT

package io.github.k8242.threadlog.feature.views.calendar

import io.github.k8242.threadlog.core.model.EntryWithDetails
import io.github.k8242.threadlog.core.model.Tag
import io.github.k8242.threadlog.core.model.WeekStart
import java.time.LocalDate
import java.time.YearMonth

/**
 * Hydrated entry item displayed for the currently selected day in [CalendarScreen].
 */
data class CalendarDayEntryItem(
    val details: EntryWithDetails,
    val timeLabel: String,
)

/**
 * Immutable UI state for [CalendarScreen].
 */
data class CalendarUiState(
    val displayedMonth: YearMonth = YearMonth.now(),
    val monthLabel: String = "",
    val weekStart: WeekStart = WeekStart.MONDAY,
    val weekdayHeaders: List<String> = CalendarGridBuilder.weekdayHeaders(WeekStart.MONDAY),
    val cells: List<CalendarDayCell> = emptyList(),
    val selectedDate: String = LocalDate.now().toString(),
    val selectedDateLabel: String = "",
    val allTags: List<Tag> = emptyList(),
    val tagDisplayLabels: Map<String, String> = emptyMap(),
    val selectedTagFilterPath: String? = null,
    val entriesForSelectedDate: List<CalendarDayEntryItem> = emptyList(),
    val isLoadingEntries: Boolean = true,
)
