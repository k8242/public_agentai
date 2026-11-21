package com.threadlog.feature.chat.ui

import com.threadlog.core.model.WeekStart
import com.threadlog.core.model.contract.DateWeekCalculator
import com.threadlog.core.model.parser.DateDividerMeta

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
 * between a message and its immediately older neighbor in the chat feed.
 */
object ChatDividerLogic {

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
