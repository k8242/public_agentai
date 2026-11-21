// Copyright (c) 2026 k8242
// SPDX-License-Identifier: MIT

package io.github.k8242.threadlog.core.parser

import io.github.k8242.threadlog.core.model.WeekStart
import io.github.k8242.threadlog.core.model.contract.DateWeekCalculator
import io.github.k8242.threadlog.core.model.parser.DateDividerMeta
import java.time.DateTimeException
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.IsoFields
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * Pure `java.time` date, week, and hierarchical divider calculator anchored in an entry's
 * write-time IANA [ZoneId] (falling back to [ZoneOffset.UTC] if invalid).
 */
class DefaultDateWeekCalculator : DateWeekCalculator {

    override fun localDateAndIsoWeek(epochMs: Long, zoneId: String): Pair<String, String> {
        val date = Instant.ofEpochMilli(epochMs).atZone(safeZoneId(zoneId)).toLocalDate()
        val localDate = date.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val isoWeek = formatIsoMondayWeekKey(date)
        return localDate to isoWeek
    }

    override fun dividerMeta(epochMs: Long, zoneId: String, weekStart: WeekStart): DateDividerMeta {
        val zdt = Instant.ofEpochMilli(epochMs).atZone(safeZoneId(zoneId))
        val date = zdt.toLocalDate()
        val dayKey = date.format(DateTimeFormatter.ISO_LOCAL_DATE)

        return DateDividerMeta(
            year = date.year,
            yearLabel = date.year.toString(),
            monthKey = date.format(MONTH_KEY_FORMATTER),
            monthLabel = date.format(MONTH_LABEL_FORMATTER),
            weekKey = weekKeyForDate(date, weekStart),
            weekLabel = weekLabelForDate(date, weekStart),
            dayKey = dayKey,
            dayLabel = date.format(DAY_LABEL_FORMATTER),
            timeLabel = zdt.format(TIME_LABEL_FORMATTER),
        )
    }

    override fun weekKeyForLocalDate(localDate: String, weekStart: WeekStart): String {
        val date = LocalDate.parse(localDate, DateTimeFormatter.ISO_LOCAL_DATE)
        return weekKeyForDate(date, weekStart)
    }

    override fun weekLabelForLocalDate(localDate: String, weekStart: WeekStart): String {
        val date = LocalDate.parse(localDate, DateTimeFormatter.ISO_LOCAL_DATE)
        return weekLabelForDate(date, weekStart)
    }

    private fun weekKeyForDate(date: LocalDate, weekStart: WeekStart): String =
        when (weekStart) {
            WeekStart.MONDAY -> formatIsoMondayWeekKey(date)
            WeekStart.SUNDAY -> {
                val weekYear = date.get(SUNDAY_WEEK_FIELDS.weekBasedYear())
                val weekNum = date.get(SUNDAY_WEEK_FIELDS.weekOfWeekBasedYear())
                "%04d-S%02d".format(Locale.US, weekYear, weekNum)
            }
        }

    private fun formatIsoMondayWeekKey(date: LocalDate): String {
        val weekYear = date.get(IsoFields.WEEK_BASED_YEAR)
        val weekNum = date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
        return "%04d-W%02d".format(Locale.US, weekYear, weekNum)
    }

    private fun weekLabelForDate(date: LocalDate, weekStart: WeekStart): String {
        val (weekNum, startDayOfWeek) = when (weekStart) {
            WeekStart.MONDAY -> date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR) to DayOfWeek.MONDAY
            WeekStart.SUNDAY -> date.get(SUNDAY_WEEK_FIELDS.weekOfWeekBasedYear()) to DayOfWeek.SUNDAY
        }

        val start = date.with(TemporalAdjusters.previousOrSame(startDayOfWeek))
        val end = start.plusDays(6)

        val startMonth = start.format(SHORT_MONTH_FORMATTER)
        val range = if (start.month == end.month) {
            "$startMonth ${start.dayOfMonth}$EN_DASH${end.dayOfMonth}"
        } else {
            val endMonth = end.format(SHORT_MONTH_FORMATTER)
            "$startMonth ${start.dayOfMonth}$EN_DASH$endMonth ${end.dayOfMonth}"
        }
        return "Week $weekNum $MIDDLE_DOT $range"
    }

    private fun safeZoneId(zoneId: String): ZoneId =
        try {
            ZoneId.of(zoneId)
        } catch (_: DateTimeException) {
            ZoneOffset.UTC
        }

    private companion object {
        const val MIDDLE_DOT = "\u00B7"
        const val EN_DASH = "\u2013"

        val SUNDAY_WEEK_FIELDS: WeekFields = WeekFields.of(DayOfWeek.SUNDAY, 4)

        val MONTH_KEY_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM", Locale.US)
        val MONTH_LABEL_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US)
        val SHORT_MONTH_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("MMM", Locale.US)
        val DAY_LABEL_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("EEE, MMM d", Locale.US)
        val TIME_LABEL_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("h:mm a", Locale.US)
    }
}
