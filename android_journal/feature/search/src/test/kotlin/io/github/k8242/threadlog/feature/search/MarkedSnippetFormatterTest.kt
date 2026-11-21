// Copyright (c) 2026 k8242
// SPDX-License-Identifier: MIT

package io.github.k8242.threadlog.feature.search

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import com.google.common.truth.Truth.assertThat
import io.github.k8242.threadlog.feature.search.ui.MarkedSegment
import io.github.k8242.threadlog.feature.search.ui.MarkedSnippetFormatter
import org.junit.Test

class MarkedSnippetFormatterTest {

    @Test
    fun parseMarkedSnippet_emptyStringReturnsEmptyList() {
        assertThat(MarkedSnippetFormatter.parseMarkedSnippet("")).isEmpty()
    }

    @Test
    fun parseMarkedSnippet_plainTextWithoutTagsReturnsSingleUnhighlightedSegment() {
        val segments = MarkedSnippetFormatter.parseMarkedSnippet("Morning standup notes")
        assertThat(segments).containsExactly(
            MarkedSegment(text = "Morning standup notes", highlighted = false),
        )
    }

    @Test
    fun parseMarkedSnippet_parsesSingleAndMultipleMarks() {
        val segments = MarkedSnippetFormatter.parseMarkedSnippet(
            "Met <mark>Lex</mark> for <mark>coffee</mark> at noon",
        )
        assertThat(segments).containsExactly(
            MarkedSegment("Met ", highlighted = false),
            MarkedSegment("Lex", highlighted = true),
            MarkedSegment(" for ", highlighted = false),
            MarkedSegment("coffee", highlighted = true),
            MarkedSegment(" at noon", highlighted = false),
        ).inOrder()
    }

    @Test
    fun parseMarkedSnippet_isCaseInsensitiveOnTagNames() {
        val segments = MarkedSnippetFormatter.parseMarkedSnippet(
            "Check <MARK>SQLite</Mark> FTS5",
        )
        assertThat(segments).containsExactly(
            MarkedSegment("Check ", highlighted = false),
            MarkedSegment("SQLite", highlighted = true),
            MarkedSegment(" FTS5", highlighted = false),
        ).inOrder()
    }

    @Test
    fun parseMarkedSnippet_handlesUnclosedMarkWithoutLeakingRawTag() {
        val segments = MarkedSnippetFormatter.parseMarkedSnippet(
            "Truncated snippet with <mark>important keyword...",
        )
        assertThat(segments).containsExactly(
            MarkedSegment("Truncated snippet with ", highlighted = false),
            MarkedSegment("important keyword...", highlighted = true),
        ).inOrder()
    }

    @Test
    fun parseMarkedSnippet_stripsStrayClosingTags() {
        val segments = MarkedSnippetFormatter.parseMarkedSnippet(
            "Before </mark> middle <mark>hit</mark> after </mark>",
        )
        assertThat(segments).containsExactly(
            MarkedSegment("Before  middle ", highlighted = false),
            MarkedSegment("hit", highlighted = true),
            MarkedSegment(" after ", highlighted = false),
        ).inOrder()
    }

    @Test
    fun parseQueryHighlights_highlightsCaseInsensitiveMultiTermMatches() {
        val segments = MarkedSnippetFormatter.parseQueryHighlights(
            text = "Discussed Kotlin coroutines and KOTLIN compose UI",
            query = "kotlin compose",
        )
        assertThat(segments).containsExactly(
            MarkedSegment("Discussed ", highlighted = false),
            MarkedSegment("Kotlin", highlighted = true),
            MarkedSegment(" coroutines and ", highlighted = false),
            MarkedSegment("KOTLIN", highlighted = true),
            MarkedSegment(" ", highlighted = false),
            MarkedSegment("compose", highlighted = true),
            MarkedSegment(" UI", highlighted = false),
        ).inOrder()
    }

    @Test
    fun parseQueryHighlights_mergesOverlappingMatchesAndHandlesWildcardQuery() {
        val segments = MarkedSnippetFormatter.parseQueryHighlights(
            text = "android_journal app",
            query = "\"android*\" journal",
        )
        assertThat(segments).containsExactly(
            MarkedSegment("android", highlighted = true),
            MarkedSegment("_", highlighted = false),
            MarkedSegment("journal", highlighted = true),
            MarkedSegment(" app", highlighted = false),
        ).inOrder()
    }

    @Test
    fun parseQueryHighlights_blankQueryReturnsUnhighlightedText() {
        val segments = MarkedSnippetFormatter.parseQueryHighlights(
            text = "Plain entry body",
            query = "   ",
        )
        assertThat(segments).containsExactly(
            MarkedSegment("Plain entry body", highlighted = false),
        )
    }

    @Test
    fun formatMarkedSnippet_appliesBoldAndBackgroundSpanStyle() {
        val annotated = MarkedSnippetFormatter.formatMarkedSnippet(
            snippetMarked = "Hello <mark>world</mark>!",
            highlightBackground = Color.Yellow,
            highlightTextColor = Color.Black,
        )

        assertThat(annotated.text).isEqualTo("Hello world!")
        assertThat(annotated.spanStyles).hasSize(1)
        val span = annotated.spanStyles.first()
        assertThat(span.start).isEqualTo(6)
        assertThat(span.end).isEqualTo(11)
        assertThat(span.item.background).isEqualTo(Color.Yellow)
        assertThat(span.item.color).isEqualTo(Color.Black)
        assertThat(span.item.fontWeight).isEqualTo(FontWeight.Bold)
    }
}
