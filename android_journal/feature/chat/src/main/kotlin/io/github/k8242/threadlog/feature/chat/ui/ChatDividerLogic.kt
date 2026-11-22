// Copyright (c) 2026 k8242
// SPDX-License-Identifier: MIT

package io.github.k8242.threadlog.feature.chat.ui

import io.github.k8242.threadlog.core.model.WeekStart
import io.github.k8242.threadlog.core.model.contract.DateWeekCalculator
import io.github.k8242.threadlog.core.model.parser.DateDividerMeta
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Flags indicating which of the 4 hierarchical date divider tiers should be rendered above
 * a chat message in a reverse-layout feed (`index 0 = newest`, `index + 1 = older neighbor`).
 */
data class DividerVisibility(
    val showYear: Boolean,
    val showMonth: Boolean,
    val showWeek: Boolean,
    val showDay: Boolean,
) {
    val hasAny: Boolean get() = showYear || showMonth || showWeek || showDay
}

/**
 * Pure helper computing hierarchical Year / Month / Week / Day divider transitions
 * between a message and its immediately older neighbor in the chat feed, as well as
 * compact edited-timestamp labels.
 */
object ChatDividerLogic {

    private val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
    private val MONTH_DAY_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d", Locale.US)
    private val MONTH_DAY_YEAR_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)

    /**
     * Formats an entry's edited timestamp relative to its creation timestamp in [zoneId]:
     * - Same local day: `"Edited 8:05 PM"`
     * - Different day, same year: `"Edited Nov 22, 9:12 AM"`
     * - Different year: `"Edited Jan 3, 2027, 9:12 AM"`
     * Returns `null` when the entry has not been edited (`updatedAtMs == null` or `<= createdAtMs`).
     */
    fun formatEditedLabel(
        createdAtMs: Long,
        updatedAtMs: Long?,
        zoneId: String,
    ): String? {
        if (updatedAtMs == null || updatedAtMs <= createdAtMs) return null
        val zone = runCatching { ZoneId.of(zoneId) }.getOrElse { ZoneId.systemDefault() }
        val createdZdt = Instant.ofEpochMilli(createdAtMs).atZone(zone)
        val updatedZdt = Instant.ofEpochMilli(updatedAtMs).atZone(zone)
        val timeText = updatedZdt.format(TIME_FORMATTER)
        return when {
            createdZdt.toLocalDate() == updatedZdt.toLocalDate() -> "Edited $timeText"
            createdZdt.year == updatedZdt.year -> "Edited ${updatedZdt.format(MONTH_DAY_FORMATTER)}, $timeText"
            else -> "Edited ${updatedZdt.format(MONTH_DAY_YEAR_FORMATTER)}, $timeText"
        }
    }

    /**
     * Computes which divider tiers appear above the item represented by [currentMeta],
     * comparing against its immediately older neighbor [olderMeta] (null for the oldest item).
     */
    fun computeDividerVisibility(
        currentMeta: DateDividerMeta,
        olderMeta: DateDividerMeta?,
    ): DividerVisibility {
        val showYear = olderMeta == null || currentMeta.year != olderMeta.year
        val showMonth = showYear || currentMeta.monthKey != olderMeta.monthKey
        val showWeek = showMonth || currentMeta.weekKey != olderMeta.weekKey
        val showDay = showWeek || currentMeta.dayKey != olderMeta.dayKey
        return DividerVisibility(
            showYear = showYear,
            showMonth = showMonth,
            showWeek = showWeek,
            showDay = showDay,
        )
    }

    /**
     * Convenience overload that computes [DateDividerMeta] via [calculator] anchored in each
     * entry's write-time zone and the user's selected [weekStart].
     */
    fun computeDividerVisibility(
        calculator: DateWeekCalculator,
        currentEpochMs: Long,
        currentZoneId: String,
        olderEpochMs: Long?,
        olderZoneId: String?,
        weekStart: WeekStart,
    ): Pair<DateDividerMeta, DividerVisibility> {
        val currentMeta = calculator.dividerMeta(currentEpochMs, currentZoneId, weekStart)
        val olderMeta = if (olderEpochMs != null && olderZoneId != null) {
            calculator.dividerMeta(olderEpochMs, olderZoneId, weekStart)
        } else {
            null
        }
        return currentMeta to computeDividerVisibility(currentMeta, olderMeta)
    }
}
