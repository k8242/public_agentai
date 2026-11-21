package io.github.k8242.threadlog.feature.views.calendar

import io.github.k8242.threadlog.core.model.WeekStart
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/**
 * Single cell in a 7-column monthly calendar grid.
 *
 * Leading/trailing padding cells outside the displayed month have `null` [date],
 * [localDateKey], and [dayOfMonth].
 */
data class CalendarDayCell(
    val date: LocalDate?,
    val localDateKey: String?,
    val dayOfMonth: Int?,
    val entryCount: Int,
    val isToday: Boolean,
    val isSelected: Boolean,
)

/**
 * Pure calendar grid computation helpers supporting [WeekStart.MONDAY] and [WeekStart.SUNDAY].
 */
object CalendarGridBuilder {

    private val EMPTY_CELL = CalendarDayCell(
        date = null,
        localDateKey = null,
        dayOfMonth = null,
        entryCount = 0,
        isToday = false,
        isSelected = false,
    )

    /**
     * Builds a complete list of 7-column rows covering [yearMonth], aligned to [weekStart].
     *
     * The returned list size is always a multiple of 7 (28, 35, or 42).
     */
    fun buildMonthGrid(
        yearMonth: YearMonth,
        weekStart: WeekStart,
        countsByDate: Map<String, Int>,
        today: LocalDate,
        selectedDate: String?,
    ): List<CalendarDayCell> {
        val firstOfMonth = yearMonth.atDay(1)
        val daysInMonth = yearMonth.lengthOfMonth()

        val leadingBlanks = when (weekStart) {
            WeekStart.MONDAY -> firstOfMonth.dayOfWeek.value - 1
            WeekStart.SUNDAY -> firstOfMonth.dayOfWeek.value % 7
        }

        val totalUsed = leadingBlanks + daysInMonth
        val remainder = totalUsed % 7
        val trailingBlanks = if (remainder == 0) 0 else 7 - remainder

        val cells = ArrayList<CalendarDayCell>(totalUsed + trailingBlanks)

        repeat(leadingBlanks) {
            cells.add(EMPTY_CELL)
        }

        for (day in 1..daysInMonth) {
            val date = yearMonth.atDay(day)
            val key = date.format(DateTimeFormatter.ISO_LOCAL_DATE)
            val count = (countsByDate[key] ?: 0).coerceAtLeast(0)
            cells.add(
                CalendarDayCell(
                    date = date,
                    localDateKey = key,
                    dayOfMonth = day,
                    entryCount = count,
                    isToday = date == today,
                    isSelected = key == selectedDate,
                ),
            )
        }

        repeat(trailingBlanks) {
            cells.add(EMPTY_CELL)
        }

        return cells
    }

    /**
     * Maps an entry count to a 0..3 density dot tier:
     * - `0` (or negative) -> `0`
     * - `1` -> `1`
     * - `2..3` -> `2`
     * - `4+` -> `3`
     */
    fun densityDotsCount(entryCount: Int): Int = when {
        entryCount <= 0 -> 0
        entryCount == 1 -> 1
        entryCount in 2..3 -> 2
        else -> 3
    }

    /**
     * Returns single-letter weekday headers aligned to [weekStart].
     */
    fun weekdayHeaders(weekStart: WeekStart): List<String> = when (weekStart) {
        WeekStart.MONDAY -> listOf("M", "T", "W", "T", "F", "S", "S")
        WeekStart.SUNDAY -> listOf("S", "M", "T", "W", "T", "F", "S")
    }
}

/** Convenience top-level wrapper for [CalendarGridBuilder.buildMonthGrid]. */
fun buildMonthGrid(
    yearMonth: YearMonth,
    weekStart: WeekStart,
    countsByDate: Map<String, Int>,
    today: LocalDate,
    selectedDate: String?,
): List<CalendarDayCell> = CalendarGridBuilder.buildMonthGrid(
    yearMonth = yearMonth,
    weekStart = weekStart,
    countsByDate = countsByDate,
    today = today,
    selectedDate = selectedDate,
)

/** Convenience top-level wrapper for [CalendarGridBuilder.densityDotsCount]. */
fun densityDotsCount(entryCount: Int): Int = CalendarGridBuilder.densityDotsCount(entryCount)

/** Convenience top-level wrapper for [CalendarGridBuilder.weekdayHeaders]. */
fun weekdayHeaders(weekStart: WeekStart): List<String> = CalendarGridBuilder.weekdayHeaders(weekStart)
