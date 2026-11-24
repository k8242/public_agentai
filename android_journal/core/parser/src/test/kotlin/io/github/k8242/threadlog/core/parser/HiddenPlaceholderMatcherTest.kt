// Copyright (c) 2026 k8242
// SPDX-License-Identifier: MIT

package io.github.k8242.threadlog.core.parser

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HiddenPlaceholderMatcherTest {

    @Test
    fun matchesStandardOmittedMediaAndDeletedPlaceholders_caseInsensitive() {
        val positives = listOf(
            "<Media omitted>",
            "<media omitted>",
            "Media omitted",
            "image omitted",
            "video omitted",
            "audio omitted",
            "sticker omitted",
            "gif omitted",
            "document omitted",
            "<image omitted>",
            "<attached: 00000042-PHOTO-2026-11-20.jpg>",
            "You deleted this message",
            "You deleted this message.",
            "This message was deleted",
            "This message was deleted.",
            "  THIS MESSAGE WAS DELETED.  ",
        )
        for (sample in positives) {
            assertThat(HiddenPlaceholderMatcher.isHiddenPlaceholder(sample)).isTrue()
        }
    }

    @Test
    fun stripsDirectionalMarksBomAndOptionalSenderPrefix() {
        val positives = listOf(
            "\u200E<Media omitted>",
            "\uFEFF\u200EYou deleted this message\u200F",
            "\u202AThis message was deleted.\u202C",
            "Alice: <Media omitted>",
            "Alice: \u200E<Media omitted>",
            "Bob Smith: You deleted this message.",
            "Mom: \u200Eimage omitted",
            "Dad: \u200E<attached: 00001-IMG.jpg>",
        )
        for (sample in positives) {
            assertThat(HiddenPlaceholderMatcher.isHiddenPlaceholder(sample)).isTrue()
        }
    }

    @Test
    fun doesNotMatchNormalNotesOrMultilineNotesContainingPlaceholderText() {
        val negatives = listOf(
            "",
            "   ",
            "Meeting notes from today",
            "I wrote <Media omitted> in a real sentence",
            "Remember: You deleted this message yesterday?",
            "Line 1\n<Media omitted>",
            "<attached: foo.jpg>\nCaption text",
            "Sender: regular message text",
        )
        for (sample in negatives) {
            assertThat(HiddenPlaceholderMatcher.isHiddenPlaceholder(sample)).isFalse()
        }
    }
}
