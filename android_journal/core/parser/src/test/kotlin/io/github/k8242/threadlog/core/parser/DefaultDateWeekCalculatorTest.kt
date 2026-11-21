package io.github.k8242.threadlog.core.parser

import com.google.common.truth.Truth.assertThat
import io.github.k8242.threadlog.core.model.WeekStart
import io.github.k8242.threadlog.core.model.parser.DateDividerMeta
import org.junit.Test
import java.time.Instant
import java.time.ZonedDateTime

class DefaultDateWeekCalculatorTest {

    private val calculator = DefaultDateWeekCalculator()

    @Test
    fun localDateAndIsoWeek_anchorsInWriteTimeTimezoneAcrossMidnight() {
        // 2026-11-20T04:15:00Z is still 2026-11-19 8:15 PM in Los Angeles,
        // and 2026-11-20 1:15 PM in Tokyo.
        val epochMs = Instant.parse("2026-11-20T04:15:00Z").toEpochMilli()

        val la = calculator.localDateAndIsoWeek(epochMs, "America/Los_Angeles")
        val tokyo = calculator.localDateAndIsoWeek(epochMs, "Asia/Tokyo")

        assertThat(la).isEqualTo("2026-11-19" to "2026-W47")
        assertThat(tokyo).isEqualTo("2026-11-20" to "2026-W47")
    }

    @Test
    fun localDateAndIsoWeek_isoYearBoundaryWeeks() {
        // Mon Dec 29, 2025 belongs to ISO week 2026-W01 (Jan 1, 2026 is Thursday)
        val dec29 = ZonedDateTime.parse("2025-12-29T12:00:00Z").toInstant().toEpochMilli()
        assertThat(calculator.localDateAndIsoWeek(dec29, "UTC"))
            .isEqualTo("2025-12-29" to "2026-W01")

        // Fri Jan 1, 2021 belongs to ISO week 2020-W53
        val jan1 = ZonedDateTime.parse("2021-01-01T12:00:00Z").toInstant().toEpochMilli()
        assertThat(calculator.localDateAndIsoWeek(jan1, "UTC"))
            .isEqualTo("2021-01-01" to "2020-W53")
    }

    @Test
    fun weekKeyAndLabel_mondayVsSundayWeekStart() {
        // Sun Nov 22, 2026:
        // - MONDAY start: last day of Mon Nov 16–Sun Nov 22 (2026-W47)
        // - SUNDAY start: first day of Sun Nov 22–Sat Nov 28 (2026-S47)
        assertThat(calculator.weekKeyForLocalDate("2026-11-22", WeekStart.MONDAY))
            .isEqualTo("2026-W47")
        assertThat(calculator.weekLabelForLocalDate("2026-11-22", WeekStart.MONDAY))
            .isEqualTo("Week 47 \u00B7 Nov 16\u201322")

        assertThat(calculator.weekKeyForLocalDate("2026-11-22", WeekStart.SUNDAY))
            .isEqualTo("2026-S47")
        assertThat(calculator.weekLabelForLocalDate("2026-11-22", WeekStart.SUNDAY))
            .isEqualTo("Week 47 \u00B7 Nov 22\u201328")

        // Sat Nov 21, 2026 under SUNDAY start is end of Sun Nov 15–Sat Nov 21 (2026-S46)
        assertThat(calculator.weekKeyForLocalDate("2026-11-21", WeekStart.SUNDAY))
            .isEqualTo("2026-S46")
        assertThat(calculator.weekLabelForLocalDate("2026-11-21", WeekStart.SUNDAY))
            .isEqualTo("Week 46 \u00B7 Nov 15\u201321")
    }

    @Test
    fun weekLabelForLocalDate_crossMonthAndCrossYearRanges() {
        // Mon Nov 30 – Sun Dec 6, 2026 -> Week 49? Let's verify exact ISO week:
        // Nov 16 = W47, Nov 23 = W48, Nov 30 = W49!
        // Let's also check cross-month in 2025: Mon Nov 24–30 is W48, Mon Dec 1–7 is W49;
        // in 2020: Mon Nov 30 – Sun Dec 6 is W49; in 2026: Mon Nov 30 – Sun Dec 6 is W49.
        assertThat(calculator.weekLabelForLocalDate("2026-11-30", WeekStart.MONDAY))
            .isEqualTo("Week 49 \u00B7 Nov 30\u2013Dec 6")

        // Cross-year: Mon Dec 29, 2025 – Sun Jan 4, 2026 (2026-W01)
        assertThat(calculator.weekLabelForLocalDate("2025-12-31", WeekStart.MONDAY))
            .isEqualTo("Week 1 \u00B7 Dec 29\u2013Jan 4")

        // Sunday-start cross-month: Sun Nov 29 – Sat Dec 5, 2026 (2026-S48)
        assertThat(calculator.weekKeyForLocalDate("2026-11-29", WeekStart.SUNDAY))
            .isEqualTo("2026-S48")
        assertThat(calculator.weekLabelForLocalDate("2026-11-29", WeekStart.SUNDAY))
            .isEqualTo("Week 48 \u00B7 Nov 29\u2013Dec 5")
    }

    @Test
    fun dividerMeta_populatesAllFieldsExactFormat() {
        // 2026-11-19 08:15 AM in America/Los_Angeles (PST = UTC-8)
        val epochMs = ZonedDateTime.parse("2026-11-19T08:15:00-08:00").toInstant().toEpochMilli()

        val meta = calculator.dividerMeta(
            epochMs = epochMs,
            zoneId = "America/Los_Angeles",
            weekStart = WeekStart.MONDAY,
        )

        assertThat(meta).isEqualTo(
            DateDividerMeta(
                year = 2026,
                yearLabel = "2026",
                monthKey = "2026-11",
                monthLabel = "November 2026",
                weekKey = "2026-W47",
                weekLabel = "Week 47 \u00B7 Nov 16\u201322",
                dayKey = "2026-11-19",
                dayLabel = "Thu, Nov 19",
                timeLabel = "8:15 AM",
            ),
        )
    }

    @Test
    fun invalidZoneId_fallsBackSafelyToUtc() {
        val epochMs = Instant.parse("2026-11-19T16:15:00Z").toEpochMilli()

        val (localDate, isoWeek) = calculator.localDateAndIsoWeek(epochMs, "Not/A_Valid_Zone")
        assertThat(localDate).isEqualTo("2026-11-19")
        assertThat(isoWeek).isEqualTo("2026-W47")

        val meta = calculator.dividerMeta(epochMs, "", WeekStart.MONDAY)
        assertThat(meta.dayKey).isEqualTo("2026-11-19")
        assertThat(meta.timeLabel).isEqualTo("4:15 PM")
    }
}
