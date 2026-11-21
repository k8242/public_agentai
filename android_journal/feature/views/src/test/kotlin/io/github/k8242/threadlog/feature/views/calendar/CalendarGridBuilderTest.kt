package io.github.k8242.threadlog.feature.views.calendar

import com.google.common.truth.Truth.assertThat
import io.github.k8242.threadlog.core.model.WeekStart
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Test

class CalendarGridBuilderTest {

    @Test
    fun `month starting on Sunday aligns under Monday vs Sunday week starts`() {
        // 2026-11-01 is a Sunday (30 days)
        val nov2026 = YearMonth.of(2026, 11)
        val today = LocalDate.of(2026, 11, 19)

        val mondayGrid = CalendarGridBuilder.buildMonthGrid(
            yearMonth = nov2026,
            weekStart = WeekStart.MONDAY,
            countsByDate = mapOf("2026-11-01" to 2, "2026-11-19" to 4),
            today = today,
            selectedDate = "2026-11-01",
        )
        assertThat(mondayGrid.size % 7).isEqualTo(0)
        assertThat(mondayGrid).hasSize(42) // 6 leading + 30 days + 6 trailing
        assertThat(mondayGrid.take(6).all { it.dayOfMonth == null }).isTrue()
        assertThat(mondayGrid[6].dayOfMonth).isEqualTo(1)
        assertThat(mondayGrid[6].localDateKey).isEqualTo("2026-11-01")
        assertThat(mondayGrid[6].entryCount).isEqualTo(2)
        assertThat(mondayGrid[6].isSelected).isTrue()
        assertThat(mondayGrid[6].isToday).isFalse()

        val day19Cell = mondayGrid.first { it.dayOfMonth == 19 }
        assertThat(day19Cell.isToday).isTrue()
        assertThat(day19Cell.entryCount).isEqualTo(4)

        val sundayGrid = CalendarGridBuilder.buildMonthGrid(
            yearMonth = nov2026,
            weekStart = WeekStart.SUNDAY,
            countsByDate = mapOf("2026-11-01" to 2),
            today = today,
            selectedDate = "2026-11-19",
        )
        assertThat(sundayGrid.size % 7).isEqualTo(0)
        assertThat(sundayGrid).hasSize(35) // 0 leading + 30 days + 5 trailing
        assertThat(sundayGrid[0].dayOfMonth).isEqualTo(1)
        assertThat(sundayGrid[0].localDateKey).isEqualTo("2026-11-01")
        assertThat(sundayGrid[29].dayOfMonth).isEqualTo(30)
        assertThat(sundayGrid.drop(30).all { it.dayOfMonth == null }).isTrue()
    }

    @Test
    fun `month starting on Monday has zero leading blanks for Monday start and one for Sunday start`() {
        // 2026-06-01 is a Monday (30 days)
        val june2026 = YearMonth.of(2026, 6)
        val today = LocalDate.of(2026, 6, 15)

        val mondayGrid = buildMonthGrid(
            yearMonth = june2026,
            weekStart = WeekStart.MONDAY,
            countsByDate = emptyMap(),
            today = today,
            selectedDate = null,
        )
        assertThat(mondayGrid).hasSize(35) // 0 leading + 30 days + 5 trailing
        assertThat(mondayGrid[0].dayOfMonth).isEqualTo(1)
        assertThat(mondayGrid[29].dayOfMonth).isEqualTo(30)

        val sundayGrid = buildMonthGrid(
            yearMonth = june2026,
            weekStart = WeekStart.SUNDAY,
            countsByDate = emptyMap(),
            today = today,
            selectedDate = null,
        )
        assertThat(sundayGrid).hasSize(35) // 1 leading + 30 days + 4 trailing
        assertThat(sundayGrid[0].dayOfMonth).isNull()
        assertThat(sundayGrid[1].dayOfMonth).isEqualTo(1)
        assertThat(sundayGrid[30].dayOfMonth).isEqualTo(30)
    }

    @Test
    fun `month starting on Saturday aligns correctly`() {
        // 2026-08-01 is a Saturday (31 days)
        val aug2026 = YearMonth.of(2026, 8)
        val today = LocalDate.of(2026, 8, 1)

        val mondayGrid = CalendarGridBuilder.buildMonthGrid(
            yearMonth = aug2026,
            weekStart = WeekStart.MONDAY,
            countsByDate = emptyMap(),
            today = today,
            selectedDate = "2026-08-01",
        )
        assertThat(mondayGrid).hasSize(42) // 5 leading + 31 days + 6 trailing
        assertThat(mondayGrid.take(5).all { it.dayOfMonth == null }).isTrue()
        assertThat(mondayGrid[5].dayOfMonth).isEqualTo(1)
        assertThat(mondayGrid[5].isToday).isTrue()
        assertThat(mondayGrid[5].isSelected).isTrue()

        val sundayGrid = CalendarGridBuilder.buildMonthGrid(
            yearMonth = aug2026,
            weekStart = WeekStart.SUNDAY,
            countsByDate = emptyMap(),
            today = today,
            selectedDate = "2026-08-01",
        )
        assertThat(sundayGrid).hasSize(42) // 6 leading + 31 days + 5 trailing
        assertThat(sundayGrid.take(6).all { it.dayOfMonth == null }).isTrue()
        assertThat(sundayGrid[6].dayOfMonth).isEqualTo(1)
    }

    @Test
    fun `leap year February includes 29 days and exact 4-week February has 28 cells`() {
        // 2024-02-01 is a Thursday (leap year, 29 days)
        val feb2024 = YearMonth.of(2024, 2)
        val leapGrid = CalendarGridBuilder.buildMonthGrid(
            yearMonth = feb2024,
            weekStart = WeekStart.MONDAY,
            countsByDate = mapOf("2024-02-29" to 3),
            today = LocalDate.of(2024, 2, 29),
            selectedDate = "2024-02-29",
        )
        assertThat(leapGrid).hasSize(35) // 3 leading + 29 days + 3 trailing
        assertThat(leapGrid.take(3).all { it.dayOfMonth == null }).isTrue()
        val leapDay = leapGrid.first { it.localDateKey == "2024-02-29" }
        assertThat(leapDay.dayOfMonth).isEqualTo(29)
        assertThat(leapDay.entryCount).isEqualTo(3)
        assertThat(leapDay.isToday).isTrue()
        assertThat(leapDay.isSelected).isTrue()

        // 2026-02-01 is a Sunday (28 days) -> exact 4 rows under WeekStart.SUNDAY
        val feb2026 = YearMonth.of(2026, 2)
        val fourWeekGrid = CalendarGridBuilder.buildMonthGrid(
            yearMonth = feb2026,
            weekStart = WeekStart.SUNDAY,
            countsByDate = emptyMap(),
            today = LocalDate.of(2026, 2, 10),
            selectedDate = null,
        )
        assertThat(fourWeekGrid).hasSize(28)
        assertThat(fourWeekGrid.first().dayOfMonth).isEqualTo(1)
        assertThat(fourWeekGrid.last().dayOfMonth).isEqualTo(28)
    }

    @Test
    fun `densityDotsCount maps entry counts to 0 through 3 dot tiers`() {
        assertThat(densityDotsCount(-2)).isEqualTo(0)
        assertThat(densityDotsCount(0)).isEqualTo(0)
        assertThat(densityDotsCount(1)).isEqualTo(1)
        assertThat(densityDotsCount(2)).isEqualTo(2)
        assertThat(densityDotsCount(3)).isEqualTo(2)
        assertThat(densityDotsCount(4)).isEqualTo(3)
        assertThat(densityDotsCount(10)).isEqualTo(3)
    }

    @Test
    fun `weekdayHeaders match Monday and Sunday week starts`() {
        assertThat(weekdayHeaders(WeekStart.MONDAY))
            .containsExactly("M", "T", "W", "T", "F", "S", "S")
            .inOrder()
        assertThat(weekdayHeaders(WeekStart.SUNDAY))
            .containsExactly("S", "M", "T", "W", "T", "F", "S")
            .inOrder()
    }
}
