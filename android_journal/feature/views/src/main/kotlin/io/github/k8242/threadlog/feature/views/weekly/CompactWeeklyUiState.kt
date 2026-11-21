// Copyright (c) 2026 k8242
// SPDX-License-Identifier: MIT

package io.github.k8242.threadlog.feature.views.weekly

import io.github.k8242.threadlog.core.model.CompactEntrySummary
import io.github.k8242.threadlog.core.model.EntryWithDetails
import io.github.k8242.threadlog.core.model.WeekStart

/**
 * Grouped week section for the Compact Weekly View, ordered newest week first.
 */
data class CompactWeekGroup(
    val weekKey: String,
    val weekLabel: String,
    val entryCount: Int,
    val days: List<CompactDayGroup>,
)

/**
 * Day sub-group inside a [CompactWeekGroup], ordered newest day first.
 */
data class CompactDayGroup(
    val dayKey: String,
    val dayLabel: String,
    val items: List<CompactEntryRowItem>,
)

/**
 * Single row item inside a [CompactDayGroup], supporting inline expansion to hydrated [EntryWithDetails].
 */
data class CompactEntryRowItem(
    val summary: CompactEntrySummary,
    val timeLabel: String,
    val isExpanded: Boolean,
    val expandedDetails: EntryWithDetails?,
)

/**
 * Immutable UI state for [CompactWeeklyScreen].
 */
data class CompactWeeklyUiState(
    val isLoading: Boolean = true,
    val weekStart: WeekStart = WeekStart.MONDAY,
    val weeks: List<CompactWeekGroup> = emptyList(),
    val tagDisplayLabels: Map<String, String> = emptyMap(),
) {
    val hasExpandedItems: Boolean
        get() = weeks.any { week ->
            week.days.any { day ->
                day.items.any { it.isExpanded }
            }
        }
}
