// Copyright (c) 2026 k8242
// SPDX-License-Identifier: MIT

package io.github.k8242.threadlog.core.parser

import com.google.common.truth.Truth.assertThat
import io.github.k8242.threadlog.core.model.parser.ActiveTagTrigger
import io.github.k8242.threadlog.core.model.parser.ExtractedUrl
import io.github.k8242.threadlog.core.model.parser.InlineSpan
import io.github.k8242.threadlog.core.model.parser.MessageBlock
import io.github.k8242.threadlog.core.model.parser.TagTriggerKind
import org.junit.Test

class DefaultMessageParserTest {

    private val parser = DefaultMessageParser()

    @Test
    fun parse_emptyAndBlankInput_returnsEmptyParsedMessage() {
        val empty = parser.parse("")
        assertThat(empty.blocks).isEmpty()
        assertThat(empty.bodyPlain).isEmpty()
        assertThat(empty.extractedUrls).isEmpty()

        val blank = parser.parse("   \n\t\n  ")
        assertThat(blank.blocks).isEmpty()
        assertThat(blank.bodyPlain).isEmpty()
        assertThat(blank.extractedUrls).isEmpty()
    }

    @Test
    fun parse_whatsappFormatting_boldItalicStrikeAndNesting() {
        val parsed = parser.parse("*bold* _italic_ ~strike~ *_bold italic_* *~_all three_~*")

        assertThat(parsed.blocks).containsExactly(
            MessageBlock.Paragraph(
                listOf(
                    InlineSpan.Bold(listOf(InlineSpan.Text("bold"))),
                    InlineSpan.Text(" "),
                    InlineSpan.Italic(listOf(InlineSpan.Text("italic"))),
                    InlineSpan.Text(" "),
                    InlineSpan.Strike(listOf(InlineSpan.Text("strike"))),
                    InlineSpan.Text(" "),
                    InlineSpan.Bold(listOf(InlineSpan.Italic(listOf(InlineSpan.Text("bold italic"))))),
                    InlineSpan.Text(" "),
                    InlineSpan.Bold(
                        listOf(
                            InlineSpan.Strike(
                                listOf(InlineSpan.Italic(listOf(InlineSpan.Text("all three")))),
                            ),
                        ),
                    ),
                ),
            ),
        )
        assertThat(parsed.bodyPlain).isEqualTo("bold italic strike bold italic all three")
    }

    @Test
    fun parse_whatsappBoundaryRules_preservesSnakeCaseArithmeticAndUnmatchedMarkers() {
        val parsed = parser.parse(
            "snake_case_identifier and 2*3*4 and foo~bar~baz and *unclosed and * spaced * and **",
        )

        assertThat(parsed.blocks).containsExactly(
            MessageBlock.Paragraph(
                listOf(
                    InlineSpan.Text(
                        "snake_case_identifier and 2*3*4 and foo~bar~baz and *unclosed and * spaced * and **",
                    ),
                ),
            ),
        )
        assertThat(parsed.bodyPlain).isEqualTo(
            "snake_case_identifier and 2*3*4 and foo~bar~baz and *unclosed and * spaced * and **",
        )
    }

    @Test
    fun parse_formattingWithSurroundingPunctuation() {
        val parsed = parser.parse("(*bold*), _italic_! ~done~.")

        assertThat(parsed.blocks).containsExactly(
            MessageBlock.Paragraph(
                listOf(
                    InlineSpan.Text("("),
                    InlineSpan.Bold(listOf(InlineSpan.Text("bold"))),
                    InlineSpan.Text("), "),
                    InlineSpan.Italic(listOf(InlineSpan.Text("italic"))),
                    InlineSpan.Text("! "),
                    InlineSpan.Strike(listOf(InlineSpan.Text("done"))),
                    InlineSpan.Text("."),
                ),
            ),
        )
        assertThat(parsed.bodyPlain).isEqualTo("(bold), italic! done.")
    }

    @Test
    fun parse_inlineCode_suppressesNestedFormattingAndUrlExtraction() {
        val parsed = parser.parse("Run `*not bold* https://ignored.example.com` then *real* and `unclosed")

        assertThat(parsed.blocks).containsExactly(
            MessageBlock.Paragraph(
                listOf(
                    InlineSpan.Text("Run "),
                    InlineSpan.InlineCode("*not bold* https://ignored.example.com"),
                    InlineSpan.Text(" then "),
                    InlineSpan.Bold(listOf(InlineSpan.Text("real"))),
                    InlineSpan.Text(" and `unclosed"),
                ),
            ),
        )
        assertThat(parsed.extractedUrls).isEmpty()
        assertThat(parsed.bodyPlain)
            .isEqualTo("Run *not bold* https://ignored.example.com then real and `unclosed")
    }

    @Test
    fun parse_boldContainingInlineCodeWithAsterisk_parsesCleanly() {
        val parsed = parser.parse("*before `a*b` after*")

        assertThat(parsed.blocks).containsExactly(
            MessageBlock.Paragraph(
                listOf(
                    InlineSpan.Bold(
                        listOf(
                            InlineSpan.Text("before "),
                            InlineSpan.InlineCode("a*b"),
                            InlineSpan.Text(" after"),
                        ),
                    ),
                ),
            ),
        )
        assertThat(parsed.bodyPlain).isEqualTo("before a*b after")
    }

    @Test
    fun parse_fencedCodeBlocks_preservesVerbatimAndSuppressesUrlsAndFormatting() {
        val raw = """
            Intro *bold*
            ```kotlin
            val url = "https://code.example.com/path"
            val expr = *not bold*

            println(url)
            ```
            Outro
        """.trimIndent()

        val parsed = parser.parse(raw)

        assertThat(parsed.blocks).containsExactly(
            MessageBlock.Paragraph(
                listOf(
                    InlineSpan.Text("Intro "),
                    InlineSpan.Bold(listOf(InlineSpan.Text("bold"))),
                ),
            ),
            MessageBlock.CodeBlock(
                language = "kotlin",
                code = "val url = \"https://code.example.com/path\"\nval expr = *not bold*\n\nprintln(url)",
            ),
            MessageBlock.Paragraph(listOf(InlineSpan.Text("Outro"))),
        ).inOrder()
        assertThat(parsed.extractedUrls).isEmpty()
        assertThat(parsed.bodyPlain).isEqualTo(
            "Intro bold\nval url = \"https://code.example.com/path\"\nval expr = *not bold*\n\nprintln(url)\nOutro",
        )
    }

    @Test
    fun parse_fencedCodeBlock_blankLanguageAndUnclosedFenceHandledGracefully() {
        val blankLang = parser.parse("```   \nline 1\n```")
        assertThat(blankLang.blocks).containsExactly(
            MessageBlock.CodeBlock(language = null, code = "line 1"),
        )

        val unclosed = parser.parse("```json\n{\"ok\": true}\nsecond line")
        assertThat(unclosed.blocks).containsExactly(
            MessageBlock.CodeBlock(language = "json", code = "{\"ok\": true}\nsecond line"),
        )
        assertThat(unclosed.bodyPlain).isEqualTo("{\"ok\": true}\nsecond line")

        val singleLine = parser.parse("```inline fence```")
        assertThat(singleLine.blocks).containsExactly(
            MessageBlock.CodeBlock(language = null, code = "inline fence"),
        )
    }

    @Test
    fun parse_quoteBlocks_stripsPrefixParsesInlineAndExtractsUrls() {
        val raw = """
            > *Quoted* line with https://www.Example.com/a/b
            >second line without space
            >  indented line
            Regular reply
        """.trimIndent()

        val parsed = parser.parse(raw)

        assertThat(parsed.blocks).containsExactly(
            MessageBlock.QuoteBlock(
                lines = listOf(
                    listOf(
                        InlineSpan.Bold(listOf(InlineSpan.Text("Quoted"))),
                        InlineSpan.Text(" line with "),
                        InlineSpan.Url(
                            rawUrl = "https://www.Example.com/a/b",
                            domain = "example.com",
                            isLong = false,
                        ),
                    ),
                    listOf(InlineSpan.Text("second line without space")),
                    listOf(InlineSpan.Text(" indented line")),
                ),
            ),
            MessageBlock.Paragraph(listOf(InlineSpan.Text("Regular reply"))),
        ).inOrder()

        assertThat(parsed.extractedUrls).containsExactly(
            ExtractedUrl(
                urlRaw = "https://www.Example.com/a/b",
                domain = "example.com",
                isLong = false,
            ),
        )
        assertThat(parsed.bodyPlain).isEqualTo(
            "Quoted line with https://www.Example.com/a/b\nsecond line without space\n indented line\nRegular reply",
        )
    }

    @Test
    fun parse_urls_trimsTrailingPunctuationPreservesBalancedParensAndComputesDomainAndIsLong() {
        val wiki = "https://en.wikipedia.org/wiki/Kotlin_(programming_language)"
        val shortUrl = "https://www.github.com/user/repo"
        val queryUrl = "https://a.co?x=1"
        val deepPathUrl = "https://a.co/one/two/three"
        val raw = "See ($shortUrl), $wiki. Also $queryUrl; $deepPathUrl! Repeat: $shortUrl"

        val parsed = parser.parse(raw)

        assertThat(parsed.extractedUrls).containsExactly(
            ExtractedUrl(urlRaw = shortUrl, domain = "github.com", isLong = false),
            ExtractedUrl(urlRaw = wiki, domain = "en.wikipedia.org", isLong = true),
            ExtractedUrl(urlRaw = queryUrl, domain = "a.co", isLong = true),
            ExtractedUrl(urlRaw = deepPathUrl, domain = "a.co", isLong = true),
        ).inOrder()

        val paragraph = parsed.blocks.single() as MessageBlock.Paragraph
        assertThat(paragraph.spans).containsExactly(
            InlineSpan.Text("See ("),
            InlineSpan.Url(shortUrl, "github.com", false),
            InlineSpan.Text("), "),
            InlineSpan.Url(wiki, "en.wikipedia.org", true),
            InlineSpan.Text(". Also "),
            InlineSpan.Url(queryUrl, "a.co", true),
            InlineSpan.Text("; "),
            InlineSpan.Url(deepPathUrl, "a.co", true),
            InlineSpan.Text("! Repeat: "),
            InlineSpan.Url(shortUrl, "github.com", false),
        ).inOrder()
    }

    @Test
    fun parse_urlInsideFormattedSpan_preservesClosingMarkerAndExtractsUrl() {
        val parsed = parser.parse("*https://example.com* and _see https://en.wikipedia.org/wiki/Foo_bar_")

        assertThat(parsed.blocks).containsExactly(
            MessageBlock.Paragraph(
                listOf(
                    InlineSpan.Bold(
                        listOf(
                            InlineSpan.Url("https://example.com", "example.com", false),
                        ),
                    ),
                    InlineSpan.Text(" and "),
                    InlineSpan.Italic(
                        listOf(
                            InlineSpan.Text("see "),
                            InlineSpan.Url(
                                "https://en.wikipedia.org/wiki/Foo_bar",
                                "en.wikipedia.org",
                                false,
                            ),
                        ),
                    ),
                ),
            ),
        )
        assertThat(parsed.extractedUrls.map { it.urlRaw }).containsExactly(
            "https://example.com",
            "https://en.wikipedia.org/wiki/Foo_bar",
        ).inOrder()
    }

    @Test
    fun parse_multipleParagraphsAndCrlfNormalized() {
        val parsed = parser.parse("Line 1\r\nLine 2\r\n\r\nSecond paragraph")

        assertThat(parsed.blocks).containsExactly(
            MessageBlock.Paragraph(listOf(InlineSpan.Text("Line 1\nLine 2"))),
            MessageBlock.Paragraph(listOf(InlineSpan.Text("Second paragraph"))),
        ).inOrder()
        assertThat(parsed.bodyPlain).isEqualTo("Line 1\nLine 2\nSecond paragraph")
    }

    @Test
    fun detectTagTrigger_atAndHashAtStartWhitespaceAndOpeningPunctuation() {
        assertThat(parser.detectTagTrigger("@", 1)).isEqualTo(
            ActiveTagTrigger(TagTriggerKind.PERSON_AT, "", 0, 1),
        )
        assertThat(parser.detectTagTrigger("#", 1)).isEqualTo(
            ActiveTagTrigger(TagTriggerKind.ALL_HASH, "", 0, 1),
        )
        assertThat(parser.detectTagTrigger("Met @Lex", 8)).isEqualTo(
            ActiveTagTrigger(TagTriggerKind.PERSON_AT, "Lex", 4, 8),
        )
        assertThat(parser.detectTagTrigger("Working on #projects/journal-app", 32)).isEqualTo(
            ActiveTagTrigger(TagTriggerKind.ALL_HASH, "projects/journal-app", 11, 32),
        )
        assertThat(parser.detectTagTrigger("(@Alex", 6)).isEqualTo(
            ActiveTagTrigger(TagTriggerKind.PERSON_AT, "Alex", 1, 6),
        )
        assertThat(parser.detectTagTrigger("[#topic_1", 9)).isEqualTo(
            ActiveTagTrigger(TagTriggerKind.ALL_HASH, "topic_1", 1, 9),
        )
        assertThat(parser.detectTagTrigger("{@Sam", 5)).isEqualTo(
            ActiveTagTrigger(TagTriggerKind.PERSON_AT, "Sam", 1, 5),
        )
        assertThat(parser.detectTagTrigger("Line 1\n#notes", 13)).isEqualTo(
            ActiveTagTrigger(TagTriggerKind.ALL_HASH, "notes", 7, 13),
        )
    }

    @Test
    fun detectTagTrigger_midStringCursorPosition() {
        val text = "Talked with @Ale about #proj today"
        assertThat(parser.detectTagTrigger(text, 16)).isEqualTo(
            ActiveTagTrigger(TagTriggerKind.PERSON_AT, "Ale", 12, 16),
        )
        assertThat(parser.detectTagTrigger(text, 28)).isEqualTo(
            ActiveTagTrigger(TagTriggerKind.ALL_HASH, "proj", 23, 28),
        )
        assertThat(parser.detectTagTrigger(text, 29)).isNull()
    }

    @Test
    fun detectTagTrigger_ignoresEmailsUrlFragmentsWhitespaceAndOutOfBounds() {
        assertThat(parser.detectTagTrigger("a@b.com", 7)).isNull()
        assertThat(parser.detectTagTrigger("email user@example.com", 22)).isNull()
        assertThat(parser.detectTagTrigger("https://x.com#top", 17)).isNull()
        assertThat(parser.detectTagTrigger("(@Lex)", 6)).isNull()
        assertThat(parser.detectTagTrigger("##double", 8)).isNull()
        assertThat(parser.detectTagTrigger("@Lex ", 5)).isNull()
        assertThat(parser.detectTagTrigger("@Lex", 0)).isNull()
        assertThat(parser.detectTagTrigger("@Lex", -1)).isNull()
        assertThat(parser.detectTagTrigger("@Lex", 5)).isNull()
    }

    @Test
    fun detectTagTrigger_projectPlus_triggersAtBoundariesAndIgnoresArithmetic() {
        assertThat(parser.detectTagTrigger("+", 1)).isEqualTo(
            ActiveTagTrigger(TagTriggerKind.PROJECT_PLUS, "", 0, 1),
        )
        assertThat(parser.detectTagTrigger("Working on +journal-app", 23)).isEqualTo(
            ActiveTagTrigger(TagTriggerKind.PROJECT_PLUS, "journal-app", 11, 23),
        )
        assertThat(parser.detectTagTrigger("(+alpha", 7)).isEqualTo(
            ActiveTagTrigger(TagTriggerKind.PROJECT_PLUS, "alpha", 1, 7),
        )
        assertThat(parser.detectTagTrigger("2+2", 3)).isNull()
        assertThat(parser.detectTagTrigger("C++", 3)).isNull()
    }

    @Test
    fun parse_extractsInlineTags_ignoringCodeAndUrls() {
        val raw = """
            Met @Lex on +journal-app. Ideas: #ideas/ui
            Check `code +ignored` and https://example.com/#frag?a+b
            ```
            +codeblock @nobody #nope
            ```
        """.trimIndent()
        val parsed = parser.parse(raw)
        assertThat(parsed.inlineTags).containsExactly(
            io.github.k8242.threadlog.core.model.parser.InlineTagToken(TagTriggerKind.PERSON_AT, "Lex"),
            io.github.k8242.threadlog.core.model.parser.InlineTagToken(TagTriggerKind.PROJECT_PLUS, "journal-app"),
            io.github.k8242.threadlog.core.model.parser.InlineTagToken(TagTriggerKind.ALL_HASH, "ideas/ui"),
        ).inOrder()
    }
}
