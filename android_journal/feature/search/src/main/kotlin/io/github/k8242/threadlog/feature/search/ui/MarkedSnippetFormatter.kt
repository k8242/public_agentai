package io.github.k8242.threadlog.feature.search.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

/**
 * A contiguous span of snippet or entry text together with whether it should be rendered
 * with highlight emphasis.
 */
data class MarkedSegment(
    val text: String,
    val highlighted: Boolean,
)

/**
 * Pure helper for converting FTS5 `<mark>...</mark>` snippets and free-text Context Window
 * queries into structured [MarkedSegment]s and Compose [AnnotatedString]s.
 */
object MarkedSnippetFormatter {

    private const val OPEN_TAG = "<mark>"
    private const val CLOSE_TAG = "</mark>"

    /**
     * Parses an FTS5 snippet containing `<mark>...</mark>` delimiters into [MarkedSegment]s.
     *
     * Tag matching is case-insensitive. Unclosed `<mark>` tags highlight the remainder of the
     * snippet without leaking raw markup, and stray `</mark>` closing tags are stripped.
     */
    fun parseMarkedSnippet(snippetMarked: String): List<MarkedSegment> {
        if (snippetMarked.isEmpty()) return emptyList()

        val rawSegments = mutableListOf<MarkedSegment>()
        var cursor = 0

        while (cursor < snippetMarked.length) {
            val openIndex = snippetMarked.indexOf(OPEN_TAG, startIndex = cursor, ignoreCase = true)
            if (openIndex == -1) {
                val trailing = stripCloseTags(snippetMarked.substring(cursor))
                if (trailing.isNotEmpty()) {
                    rawSegments += MarkedSegment(text = trailing, highlighted = false)
                }
                break
            }

            if (openIndex > cursor) {
                val plain = stripCloseTags(snippetMarked.substring(cursor, openIndex))
                if (plain.isNotEmpty()) {
                    rawSegments += MarkedSegment(text = plain, highlighted = false)
                }
            }

            val contentStart = openIndex + OPEN_TAG.length
            val closeIndex = snippetMarked.indexOf(CLOSE_TAG, startIndex = contentStart, ignoreCase = true)
            if (closeIndex == -1) {
                // Unclosed <mark>: highlight remaining text without leaking the raw tag.
                val remaining = stripOpenTags(snippetMarked.substring(contentStart))
                if (remaining.isNotEmpty()) {
                    rawSegments += MarkedSegment(text = remaining, highlighted = true)
                }
                break
            }

            val markedText = stripOpenTags(snippetMarked.substring(contentStart, closeIndex))
            if (markedText.isNotEmpty()) {
                rawSegments += MarkedSegment(text = markedText, highlighted = true)
            }
            cursor = closeIndex + CLOSE_TAG.length
        }

        return mergeAdjacentSegments(rawSegments)
    }

    /**
     * Splits [text] into [MarkedSegment]s highlighting all case-insensitive occurrences of the
     * search terms in [query]. Overlapping or contiguous matches are merged cleanly.
     */
    fun parseQueryHighlights(text: String, query: String?): List<MarkedSegment> {
        if (text.isEmpty()) return emptyList()

        val terms = extractQueryTerms(query)
        if (terms.isEmpty()) {
            return listOf(MarkedSegment(text = text, highlighted = false))
        }

        val ranges = mutableListOf<IntRange>()
        for (term in terms) {
            var searchIndex = 0
            while (searchIndex <= text.length - term.length) {
                val matchIndex = text.indexOf(term, startIndex = searchIndex, ignoreCase = true)
                if (matchIndex == -1) break
                ranges += matchIndex until (matchIndex + term.length)
                searchIndex = matchIndex + 1
            }
        }

        if (ranges.isEmpty()) {
            return listOf(MarkedSegment(text = text, highlighted = false))
        }

        val mergedRanges = mergeRanges(ranges)
        val segments = mutableListOf<MarkedSegment>()
        var cursor = 0

        for (range in mergedRanges) {
            if (range.first > cursor) {
                segments += MarkedSegment(
                    text = text.substring(cursor, range.first),
                    highlighted = false,
                )
            }
            segments += MarkedSegment(
                text = text.substring(range.first, range.last + 1),
                highlighted = true,
            )
            cursor = range.last + 1
        }

        if (cursor < text.length) {
            segments += MarkedSegment(
                text = text.substring(cursor),
                highlighted = false,
            )
        }

        return segments
    }

    /**
     * Renders pre-parsed [segments] into a Compose [AnnotatedString] with high-contrast highlight
     * background and bold text on highlighted segments.
     */
    fun toAnnotatedString(
        segments: List<MarkedSegment>,
        highlightBackground: Color,
        highlightTextColor: Color = Color.Unspecified,
    ): AnnotatedString = buildAnnotatedString {
        val highlightStyle = SpanStyle(
            background = highlightBackground,
            color = highlightTextColor,
            fontWeight = FontWeight.Bold,
        )
        for (segment in segments) {
            if (segment.highlighted) {
                withStyle(highlightStyle) {
                    append(segment.text)
                }
            } else {
                append(segment.text)
            }
        }
    }

    /** Convenience helper combining [parseMarkedSnippet] and [toAnnotatedString]. */
    fun formatMarkedSnippet(
        snippetMarked: String,
        highlightBackground: Color,
        highlightTextColor: Color = Color.Unspecified,
    ): AnnotatedString = toAnnotatedString(
        segments = parseMarkedSnippet(snippetMarked),
        highlightBackground = highlightBackground,
        highlightTextColor = highlightTextColor,
    )

    /** Convenience helper combining [parseQueryHighlights] and [toAnnotatedString]. */
    fun formatQueryHighlights(
        text: String,
        query: String?,
        highlightBackground: Color,
        highlightTextColor: Color = Color.Unspecified,
    ): AnnotatedString = toAnnotatedString(
        segments = parseQueryHighlights(text, query),
        highlightBackground = highlightBackground,
        highlightTextColor = highlightTextColor,
    )

    private fun extractQueryTerms(query: String?): List<String> {
        if (query.isNullOrBlank()) return emptyList()
        val rawTokens = query.trim()
            .split(Regex("\\s+"))
            .map { token ->
                token.trim().trim('"', '\'').trimEnd('*')
            }
            .filter { it.isNotEmpty() }

        val nonOperators = rawTokens.filterNot { it == "AND" || it == "OR" || it == "NOT" }
        val effective = if (nonOperators.isNotEmpty()) nonOperators else rawTokens
        return effective.distinctBy { it.lowercase() }
    }

    private fun mergeRanges(ranges: List<IntRange>): List<IntRange> {
        val sorted = ranges.sortedWith(compareBy<IntRange> { it.first }.thenBy { it.last })
        val merged = mutableListOf<IntRange>()
        for (range in sorted) {
            val previous = merged.lastOrNull()
            if (previous == null || range.first > previous.last + 1) {
                merged += range
            } else if (range.last > previous.last) {
                merged[merged.lastIndex] = previous.first..range.last
            }
        }
        return merged
    }

    private fun mergeAdjacentSegments(segments: List<MarkedSegment>): List<MarkedSegment> {
        if (segments.size <= 1) return segments
        val merged = mutableListOf<MarkedSegment>()
        for (segment in segments) {
            val previous = merged.lastOrNull()
            if (previous != null && previous.highlighted == segment.highlighted) {
                merged[merged.lastIndex] = previous.copy(text = previous.text + segment.text)
            } else {
                merged += segment
            }
        }
        return merged
    }

    private fun stripCloseTags(text: String): String =
        text.replace(Regex(Regex.escape(CLOSE_TAG), RegexOption.IGNORE_CASE), "")

    private fun stripOpenTags(text: String): String =
        text.replace(Regex(Regex.escape(OPEN_TAG), RegexOption.IGNORE_CASE), "")
}
