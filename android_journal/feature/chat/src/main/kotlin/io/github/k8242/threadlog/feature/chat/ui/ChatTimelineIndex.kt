// Copyright (c) 2026 k8242
// SPDX-License-Identifier: MIT

package io.github.k8242.threadlog.feature.chat.ui

import io.github.k8242.threadlog.core.model.TimelineDateBucket
import io.github.k8242.threadlog.core.model.WeekStart
import io.github.k8242.threadlog.core.model.contract.DateWeekCalculator
import io.github.k8242.threadlog.core.model.parser.DateDividerMeta
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Cumulative index range for a single `localDate` bucket in a reverse-layout feed
 * (`index 0 = newest`, `totalCount - 1 = oldest`).
 */
data class TimelineBucketRange(
    val localDate: String,
    val startIndex: Int,
    val endIndexExclusive: Int,
)

/**
 * Lightweight, immutable timeline index built from [TimelineDateBucket] rows for O(log D)
 * index-to-date lookups even while scrubbing over unloaded Paging 3 placeholders.
 */
data class ChatTimelineIndex(
    val ranges: List<TimelineBucketRange>,
    val totalCount: Int,
) {
    /** Returns the `"YYYY-MM-DD"` localDate covering [index], or `null` if out of bounds. */
    fun dateForIndex(index: Int): String? {
        if (ranges.isEmpty() || index !in 0 until totalCount) return null
        var low = 0
        var high = ranges.lastIndex
        while (low <= high) {
            val mid = (low + high) ushr 1
            val range = ranges[mid]
            when {
                index < range.startIndex -> high = mid - 1
                index >= range.endIndexExclusive -> low = mid + 1
                else -> return range.localDate
            }
        }
        return null
    }

    /**
     * Computes [DateDividerMeta] (month & week labels) for [index] so the sticky period pill
     * remains populated even when `lazyPagingItems.peek(index)` is `null` mid-jump.
     */
    fun monthWeekForIndex(
        index: Int,
        dateWeekCalculator: DateWeekCalculator,
        weekStart: WeekStart,
    ): DateDividerMeta? {
        val localDateStr = dateForIndex(index) ?: return null
        val localDate = runCatching { LocalDate.parse(localDateStr) }.getOrNull() ?: return null
        val epochMs = localDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        return dateWeekCalculator.dividerMeta(epochMs, "UTC", weekStart)
    }

    companion object {
        val EMPTY = ChatTimelineIndex(ranges = emptyList(), totalCount = 0)

        private val SCRUBBER_PILL_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("MMM yyyy \u00B7 MMM d", Locale.US)

        fun fromBuckets(buckets: List<TimelineDateBucket>): ChatTimelineIndex {
            if (buckets.isEmpty()) return EMPTY
            val built = ArrayList<TimelineBucketRange>(buckets.size)
            var cursor = 0
            for (bucket in buckets) {
                if (bucket.entryCount <= 0) continue
                val end = cursor + bucket.entryCount
                built += TimelineBucketRange(
                    localDate = bucket.localDate,
                    startIndex = cursor,
                    endIndexExclusive = end,
                )
                cursor = end
            }
            return if (built.isEmpty()) EMPTY else ChatTimelineIndex(ranges = built, totalCount = cursor)
        }

        /**
         * Maps a visual vertical fraction (`0f` = top / oldest, `1f` = bottom / newest) to
         * a `LazyColumn(reverseLayout = true)` index.
         */
        fun indexForVisualFraction(fraction: Float, totalCount: Int): Int {
            if (totalCount <= 1) return 0
            val clamped = fraction.coerceIn(0f, 1f)
            return ((1f - clamped) * (totalCount - 1)).roundToInt().coerceIn(0, totalCount - 1)
        }

        /**
         * Maps a `LazyColumn(reverseLayout = true)` item index (`0` = newest / bottom,
         * `totalCount - 1` = oldest / top) to a visual vertical fraction in `[0f, 1f]`.
         */
        fun visualFractionForIndex(index: Int, totalCount: Int): Float {
            if (totalCount <= 1) return 1f
            val clamped = index.coerceIn(0, totalCount - 1)
            return (1f - clamped.toFloat() / (totalCount - 1).toFloat()).coerceIn(0f, 1f)
        }

        /** Formats `"YYYY-MM-DD"` as a compact scrubber pill label like `"Aug 2022 · Aug 13"`. */
        fun formatScrubberDateLabel(localDate: String): String {
            val parsed = runCatching { LocalDate.parse(localDate) }.getOrNull() ?: return localDate
            return parsed.format(SCRUBBER_PILL_FORMATTER)
        }
    }
}
