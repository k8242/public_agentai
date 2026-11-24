// Copyright (c) 2026 k8242
// SPDX-License-Identifier: MIT

package io.github.k8242.threadlog.feature.chat.ui

import com.google.common.truth.Truth.assertThat
import io.github.k8242.threadlog.core.model.TimelineDateBucket
import io.github.k8242.threadlog.core.model.WeekStart
import io.github.k8242.threadlog.core.parser.DefaultDateWeekCalculator
import org.junit.Test

class ChatTimelineIndexTest {

    private val calculator = DefaultDateWeekCalculator()

    @Test
    fun emptyBuckets_producesEmptyIndex() {
        val index = ChatTimelineIndex.fromBuckets(emptyList())
        assertThat(index.totalCount).isEqualTo(0)
        assertThat(index.ranges).isEmpty()
        assertThat(index.dateForIndex(0)).isNull()
        assertThat(index.monthWeekForIndex(0, calculator, WeekStart.MONDAY)).isNull()
    }

    @Test
    fun fromBuckets_ignoresZeroCountAndResolvesExactBoundaries() {
        val index = ChatTimelineIndex.fromBuckets(
            listOf(
                TimelineDateBucket(localDate = "2026-11-24", entryCount = 3), // 0, 1, 2
                TimelineDateBucket(localDate = "2026-11-23", entryCount = 0), // skipped
                TimelineDateBucket(localDate = "2026-11-20", entryCount = 2), // 3, 4
                TimelineDateBucket(localDate = "2022-08-13", entryCount = 5), // 5, 6, 7, 8, 9
            ),
        )

        assertThat(index.totalCount).isEqualTo(10)
        assertThat(index.dateForIndex(-1)).isNull()
        assertThat(index.dateForIndex(0)).isEqualTo("2026-11-24")
        assertThat(index.dateForIndex(2)).isEqualTo("2026-11-24")
        assertThat(index.dateForIndex(3)).isEqualTo("2026-11-20")
        assertThat(index.dateForIndex(4)).isEqualTo("2026-11-20")
        assertThat(index.dateForIndex(5)).isEqualTo("2022-08-13")
        assertThat(index.dateForIndex(9)).isEqualTo("2022-08-13")
        assertThat(index.dateForIndex(10)).isNull()
    }

    @Test
    fun monthWeekForIndex_computesFallbackDividerMeta() {
        val index = ChatTimelineIndex.fromBuckets(
            listOf(TimelineDateBucket(localDate = "2022-08-13", entryCount = 4)),
        )

        val meta = index.monthWeekForIndex(2, calculator, WeekStart.MONDAY)
        assertThat(meta).isNotNull()
        assertThat(meta?.monthLabel).isEqualTo("August 2022")
        assertThat(meta?.dayKey).isEqualTo("2022-08-13")
    }

    @Test
    fun visualFractionMapping_invertsForReverseLayout() {
        // 0f (top) = oldest (totalCount - 1), 1f (bottom) = newest (0)
        assertThat(ChatTimelineIndex.indexForVisualFraction(0f, 100)).isEqualTo(99)
        assertThat(ChatTimelineIndex.indexForVisualFraction(1f, 100)).isEqualTo(0)
        assertThat(ChatTimelineIndex.indexForVisualFraction(0.5f, 101)).isEqualTo(50)

        assertThat(ChatTimelineIndex.visualFractionForIndex(0, 100)).isWithin(0.001f).of(1f)
        assertThat(ChatTimelineIndex.visualFractionForIndex(99, 100)).isWithin(0.001f).of(0f)
        assertThat(ChatTimelineIndex.visualFractionForIndex(50, 101)).isWithin(0.001f).of(0.5f)

        // Single-item edge case
        assertThat(ChatTimelineIndex.indexForVisualFraction(0.3f, 1)).isEqualTo(0)
        assertThat(ChatTimelineIndex.visualFractionForIndex(0, 1)).isWithin(0.001f).of(1f)
    }

    @Test
    fun formatScrubberDateLabel_formatsIsoDateCleanly() {
        assertThat(ChatTimelineIndex.formatScrubberDateLabel("2022-08-13"))
            .isEqualTo("Aug 2022 \u00B7 Aug 13")
        assertThat(ChatTimelineIndex.formatScrubberDateLabel("invalid-date"))
            .isEqualTo("invalid-date")
    }

    @Test
    fun removeInlineTriggerRange_cleansUpSpacesAcrossAllPositions() {
        // End of sentence preserves trailing space for continued typing
        assertThat(removeInlineTriggerRange("Lunch with @le", 11, 14))
            .isEqualTo("Lunch with " to 11)

        // Middle of sentence collapses double spaces
        assertThat(removeInlineTriggerRange("Lunch with @le tomorrow", 11, 14))
            .isEqualTo("Lunch with tomorrow" to 11)

        // Start of text removes leading space
        assertThat(removeInlineTriggerRange("@le hello", 0, 3))
            .isEqualTo("hello" to 0)

        // Entire text is the trigger
        assertThat(removeInlineTriggerRange("#topic", 0, 6))
            .isEqualTo("" to 0)

        // Start of new line trims leading space on that line
        assertThat(removeInlineTriggerRange("Line 1\n+proj rest", 7, 12))
            .isEqualTo("Line 1\nrest" to 7)

        // End of line before newline trims trailing space
        assertThat(removeInlineTriggerRange("Note #top\nNext", 5, 9))
            .isEqualTo("Note\nNext" to 4)
    }
}
