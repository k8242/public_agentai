// Copyright (c) 2026 k8242
// SPDX-License-Identifier: MIT

package io.github.k8242.threadlog.feature.chat.ui

import com.google.common.truth.Truth.assertThat
import io.github.k8242.threadlog.core.model.WeekStart
import io.github.k8242.threadlog.core.parser.DefaultDateWeekCalculator
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class ChatDividerLogicTest {

    private val calculator = DefaultDateWeekCalculator()
    private val zone = "America/Los_Angeles"

    private fun epochMs(year: Int, month: Int, day: Int, hour: Int = 10, minute: Int = 0): Long =
        LocalDateTime.of(year, month, day, hour, minute)
            .atZone(ZoneId.of(zone))
            .toInstant()
            .toEpochMilli()

    @Test
    fun `oldest item with no older neighbor shows all four divider tiers`() {
        val (_, vis) = ChatDividerLogic.computeDividerVisibility(
            calculator = calculator,
            currentEpochMs = epochMs(2026, 11, 19, 8, 15),
            currentZoneId = zone,
            olderEpochMs = null,
            olderZoneId = null,
            weekStart = WeekStart.MONDAY,
        )

        assertThat(vis).isEqualTo(
            DividerVisibility(showYear = true, showMonth = true, showWeek = true, showDay = true),
        )
        assertThat(vis.hasAny).isTrue()
    }

    @Test
    fun `two entries on the same day show no dividers`() {
        val (_, vis) = ChatDividerLogic.computeDividerVisibility(
            calculator = calculator,
            currentEpochMs = epochMs(2026, 11, 19, 17, 30),
            currentZoneId = zone,
            olderEpochMs = epochMs(2026, 11, 19, 8, 15),
            olderZoneId = zone,
            weekStart = WeekStart.MONDAY,
        )

        assertThat(vis).isEqualTo(
            DividerVisibility(showYear = false, showMonth = false, showWeek = false, showDay = false),
        )
        assertThat(vis.hasAny).isFalse()
    }

    @Test
    fun `next day within same week shows only day divider`() {
        // Thu Nov 19 -> Fri Nov 20, 2026 (same week for both Monday and Sunday start)
        val (_, vis) = ChatDividerLogic.computeDividerVisibility(
            calculator = calculator,
            currentEpochMs = epochMs(2026, 11, 20, 9, 0),
            currentZoneId = zone,
            olderEpochMs = epochMs(2026, 11, 19, 18, 0),
            olderZoneId = zone,
            weekStart = WeekStart.MONDAY,
        )

        assertThat(vis).isEqualTo(
            DividerVisibility(showYear = false, showMonth = false, showWeek = false, showDay = true),
        )
    }

    @Test
    fun `saturday to sunday transition differs between Monday and Sunday week start`() {
        // Sat Nov 21, 2026 -> Sun Nov 22, 2026
        val satMs = epochMs(2026, 11, 21, 15, 0)
        val sunMs = epochMs(2026, 11, 22, 11, 0)

        val (_, mondayStartVis) = ChatDividerLogic.computeDividerVisibility(
            calculator = calculator,
            currentEpochMs = sunMs,
            currentZoneId = zone,
            olderEpochMs = satMs,
            olderZoneId = zone,
            weekStart = WeekStart.MONDAY,
        )
        // Under Monday-start, Sat and Sun are in the SAME week
        assertThat(mondayStartVis).isEqualTo(
            DividerVisibility(showYear = false, showMonth = false, showWeek = false, showDay = true),
        )

        val (_, sundayStartVis) = ChatDividerLogic.computeDividerVisibility(
            calculator = calculator,
            currentEpochMs = sunMs,
            currentZoneId = zone,
            olderEpochMs = satMs,
            olderZoneId = zone,
            weekStart = WeekStart.SUNDAY,
        )
        // Under Sunday-start, Sunday starts a NEW week
        assertThat(sundayStartVis).isEqualTo(
            DividerVisibility(showYear = false, showMonth = false, showWeek = true, showDay = true),
        )
    }

    @Test
    fun `sunday to monday transition differs between Monday and Sunday week start`() {
        // Sun Nov 22, 2026 -> Mon Nov 23, 2026
        val sunMs = epochMs(2026, 11, 22, 20, 0)
        val monMs = epochMs(2026, 11, 23, 9, 0)

        val (_, mondayStartVis) = ChatDividerLogic.computeDividerVisibility(
            calculator = calculator,
            currentEpochMs = monMs,
            currentZoneId = zone,
            olderEpochMs = sunMs,
            olderZoneId = zone,
            weekStart = WeekStart.MONDAY,
        )
        // Under Monday-start, Monday starts a NEW week
        assertThat(mondayStartVis).isEqualTo(
            DividerVisibility(showYear = false, showMonth = false, showWeek = true, showDay = true),
        )

        val (_, sundayStartVis) = ChatDividerLogic.computeDividerVisibility(
            calculator = calculator,
            currentEpochMs = monMs,
            currentZoneId = zone,
            olderEpochMs = sunMs,
            olderZoneId = zone,
            weekStart = WeekStart.SUNDAY,
        )
        // Under Sunday-start, Sun and Mon are in the SAME week
        assertThat(sundayStartVis).isEqualTo(
            DividerVisibility(showYear = false, showMonth = false, showWeek = false, showDay = true),
        )
    }

    @Test
    fun `month change within same year shows month week and day dividers`() {
        // Mon Nov 30, 2026 -> Tue Dec 1, 2026 (same ISO week, different month!)
        val (_, vis) = ChatDividerLogic.computeDividerVisibility(
            calculator = calculator,
            currentEpochMs = epochMs(2026, 12, 1, 8, 0),
            currentZoneId = zone,
            olderEpochMs = epochMs(2026, 11, 30, 22, 0),
            olderZoneId = zone,
            weekStart = WeekStart.MONDAY,
        )

        assertThat(vis).isEqualTo(
            DividerVisibility(showYear = false, showMonth = true, showWeek = true, showDay = true),
        )
    }

    @Test
    fun `year change shows all four divider tiers`() {
        val (_, vis) = ChatDividerLogic.computeDividerVisibility(
            calculator = calculator,
            currentEpochMs = epochMs(2027, 1, 1, 0, 5),
            currentZoneId = zone,
            olderEpochMs = epochMs(2026, 12, 31, 23, 55),
            olderZoneId = zone,
            weekStart = WeekStart.MONDAY,
        )

        assertThat(vis).isEqualTo(
            DividerVisibility(showYear = true, showMonth = true, showWeek = true, showDay = true),
        )
    }
}
