// Copyright (c) 2026 k8242
// SPDX-License-Identifier: MIT

package io.github.k8242.threadlog.core.model.parser

sealed interface MessageBlock {
    data class Paragraph(val spans: List<InlineSpan>) : MessageBlock
    data class QuoteBlock(val lines: List<List<InlineSpan>>) : MessageBlock
    data class CodeBlock(val language: String?, val code: String) : MessageBlock
}

sealed interface InlineSpan {
    data class Text(val text: String) : InlineSpan
    data class Bold(val children: List<InlineSpan>) : InlineSpan
    data class Italic(val children: List<InlineSpan>) : InlineSpan
    data class Strike(val children: List<InlineSpan>) : InlineSpan
    data class InlineCode(val code: String) : InlineSpan
    data class Url(val rawUrl: String, val domain: String, val isLong: Boolean) : InlineSpan
}

data class InlineTagToken(
    val kind: TagTriggerKind,
    val rawValue: String,
)

data class ParsedMessage(
    val blocks: List<MessageBlock>,
    val bodyPlain: String,
    val extractedUrls: List<ExtractedUrl>,
    val inlineTags: List<InlineTagToken> = emptyList(),
    val cleanedBodyRaw: String = "",
)

data class ExtractedUrl(
    val urlRaw: String,
    val domain: String,
    val isLong: Boolean,               // true when raw length > 40 or contains query/path > 2 segments
)

enum class TagTriggerKind {
    PERSON_AT,
    ALL_HASH,
    PROJECT_PLUS,
}

data class ActiveTagTrigger(
    val kind: TagTriggerKind,
    val query: String,
    val startIndex: Int,               // Index of '@', '#', or '+' in composer text
    val endIndex: Int,                 // Cursor index
)

data class DateDividerMeta(
    val year: Int,
    val yearLabel: String,             // "2026"
    val monthKey: String,              // "2026-11"
    val monthLabel: String,            // "November 2026"
    val weekKey: String,               // Computed dynamically using WeekStart ("2026-W47" or "2026-S47")
    val weekLabel: String,             // "Week 47 · Nov 16–22"
    val dayKey: String,                // "2026-11-19"
    val dayLabel: String,              // "Thu, Nov 19"
    val timeLabel: String,             // "8:15 AM" (anchored in entry's zoneId)
)
