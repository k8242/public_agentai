// Copyright (c) 2026 k8242
// SPDX-License-Identifier: MIT

package io.github.k8242.threadlog.core.parser

import io.github.k8242.threadlog.core.model.contract.MessageParser
import io.github.k8242.threadlog.core.model.parser.ActiveTagTrigger
import io.github.k8242.threadlog.core.model.parser.ExtractedUrl
import io.github.k8242.threadlog.core.model.parser.InlineSpan
import io.github.k8242.threadlog.core.model.parser.InlineTagToken
import io.github.k8242.threadlog.core.model.parser.MessageBlock
import io.github.k8242.threadlog.core.model.parser.ParsedMessage
import io.github.k8242.threadlog.core.model.parser.TagTriggerKind
import java.util.Locale

/**
 * Deterministic, single-pass WhatsApp-style message parser and inline tag trigger detector.
 *
 * Supports fenced code blocks, quote blocks, paragraphs, inline code, URL extraction with
 * trailing punctuation / balanced-paren handling, inline `@person` / `#topic` / `+project` tag
 * extraction, and nested `*bold*`, `_italic_`, `~strike~` formatting following WhatsApp boundary rules.
 */
class DefaultMessageParser : MessageParser {

    override fun parse(bodyRaw: String): ParsedMessage {
        if (bodyRaw.isEmpty()) {
            return ParsedMessage(
                blocks = emptyList(),
                bodyPlain = "",
                extractedUrls = emptyList(),
                inlineTags = emptyList(),
            )
        }

        val normalized = normalizeLineEndings(bodyRaw)
        val lines = normalized.split('\n')
        val blocks = mutableListOf<MessageBlock>()
        val extractedUrls = LinkedHashMap<String, ExtractedUrl>()

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmedStart = line.trimStart()

            when {
                // Blank separator line outside code/quote blocks
                line.isBlank() -> {
                    i++
                }

                // Fenced code block (multi-line or single-line ```code```)
                trimmedStart.startsWith(FENCE) -> {
                    val trimmed = line.trim()
                    if (trimmed.length >= FENCE.length * 2 && trimmed.endsWith(FENCE)) {
                        val singleLineCode = trimmed.substring(FENCE.length, trimmed.length - FENCE.length)
                        blocks += MessageBlock.CodeBlock(language = null, code = singleLineCode)
                        i++
                    } else {
                        val langRaw = trimmedStart.substring(FENCE.length).trim()
                        val language = langRaw.ifEmpty { null }
                        val codeLines = mutableListOf<String>()
                        i++
                        while (i < lines.size) {
                            if (lines[i].trim() == FENCE) {
                                i++
                                break
                            }
                            codeLines += lines[i]
                            i++
                        }
                        blocks += MessageBlock.CodeBlock(
                            language = language,
                            code = codeLines.joinToString("\n"),
                        )
                    }
                }

                // Consecutive quote lines starting with '>'
                line.startsWith(">") -> {
                    val quoteLines = mutableListOf<List<InlineSpan>>()
                    while (i < lines.size && lines[i].startsWith(">")) {
                        val stripped = stripQuotePrefix(lines[i])
                        quoteLines += parseInlineLine(stripped, extractedUrls)
                        i++
                    }
                    blocks += MessageBlock.QuoteBlock(quoteLines)
                }

                // Paragraph: consecutive non-blank, non-fence, non-quote lines
                else -> {
                    val paragraphSpans = mutableListOf<InlineSpan>()
                    var firstLine = true
                    while (i < lines.size) {
                        val current = lines[i]
                        if (current.isBlank() ||
                            current.startsWith(">") ||
                            current.trimStart().startsWith(FENCE)
                        ) {
                            break
                        }
                        if (!firstLine) {
                            appendSpan(paragraphSpans, InlineSpan.Text("\n"))
                        }
                        val lineSpans = parseInlineLine(current, extractedUrls)
                        for (span in lineSpans) {
                            appendSpan(paragraphSpans, span)
                        }
                        firstLine = false
                        i++
                    }
                    if (paragraphSpans.isNotEmpty()) {
                        blocks += MessageBlock.Paragraph(paragraphSpans)
                    }
                }
            }
        }

        val bodyPlain = buildBodyPlain(blocks)
        val (cleanedBodyRaw, inlineTags) = stripInlineTagsAndUnescape(normalized)
        return ParsedMessage(
            blocks = blocks,
            bodyPlain = bodyPlain,
            extractedUrls = extractedUrls.values.toList(),
            inlineTags = inlineTags,
            cleanedBodyRaw = cleanedBodyRaw,
        )
    }

    /**
     * Strips unescaped inline `@person`, `#topic`, and `+project` tag tokens from [text] (skipping
     * fenced code blocks, inline code, and URLs) and unescapes `\@`, `\#`, and `\+` to literal
     * `@`, `#`, `+`.
     *
     * Any token whose lowercase prefixed form (e.g. `"@alice"`, `"#123"`, `"+foo"`) is in
     * [preserveLiteralTokens] is kept verbatim and excluded from the returned [InlineTagToken] list.
     */
    fun stripInlineTagsAndUnescape(
        text: String,
        preserveLiteralTokens: Set<String> = emptySet(),
    ): Pair<String, List<InlineTagToken>> =
        Companion.stripInlineTagsAndUnescape(text, preserveLiteralTokens)

    fun extractLiteralTagTokenKeys(text: String): Set<String> =
        Companion.extractLiteralTagTokenKeys(text)

    override fun detectTagTrigger(text: String, cursorPosition: Int): ActiveTagTrigger? {
        if (cursorPosition <= 0 || cursorPosition > text.length) {
            return null
        }

        var idx = cursorPosition - 1
        while (idx >= 0) {
            val ch = text[idx]
            if (ch == '@' || ch == '#' || ch == '+') {
                val isValidStart = idx == 0 || isTriggerPrecedingChar(text[idx - 1])
                if (!isValidStart) {
                    return null
                }
                val kind = when (ch) {
                    '@' -> TagTriggerKind.PERSON_AT
                    '+' -> TagTriggerKind.PROJECT_PLUS
                    else -> TagTriggerKind.ALL_HASH
                }
                val query = text.substring(idx + 1, cursorPosition)
                return ActiveTagTrigger(
                    kind = kind,
                    query = query,
                    startIndex = idx,
                    endIndex = cursorPosition,
                )
            }
            if (!isValidTagQueryChar(ch)) {
                return null
            }
            idx--
        }

        return null
    }

    private fun parseInlineLine(
        text: String,
        extractedUrls: LinkedHashMap<String, ExtractedUrl>,
        activeMarkers: Set<Char> = emptySet(),
    ): List<InlineSpan> {
        if (text.isEmpty()) return emptyList()

        val result = mutableListOf<InlineSpan>()
        val textBuf = StringBuilder()

        fun flushText() {
            if (textBuf.isNotEmpty()) {
                appendSpan(result, InlineSpan.Text(textBuf.toString()))
                textBuf.setLength(0)
            }
        }

        var i = 0
        while (i < text.length) {
            val ch = text[i]

            // 1. Inline code: `code` (verbatim, no nested formatting or URL extraction)
            if (ch == '`') {
                val closeIdx = text.indexOf('`', startIndex = i + 1)
                if (closeIdx > i + 1) {
                    flushText()
                    result += InlineSpan.InlineCode(text.substring(i + 1, closeIdx))
                    i = closeIdx + 1
                    continue
                }
                textBuf.append(ch)
                i++
                continue
            }

            // 2. URL: http://... or https://...
            if ((ch == 'h' || ch == 'H') &&
                isValidUrlStartBoundary(text, i) &&
                startsWithHttpScheme(text, i)
            ) {
                val urlMatch = matchUrl(text, i, activeMarkers)
                if (urlMatch != null) {
                    flushText()
                    val urlSpan = InlineSpan.Url(
                        rawUrl = urlMatch.rawUrl,
                        domain = urlMatch.domain,
                        isLong = urlMatch.isLong,
                    )
                    result += urlSpan
                    extractedUrls.putIfAbsent(
                        urlMatch.rawUrl,
                        ExtractedUrl(
                            urlRaw = urlMatch.rawUrl,
                            domain = urlMatch.domain,
                            isLong = urlMatch.isLong,
                        ),
                    )
                    i = urlMatch.endExclusive
                    continue
                }
            }

            // 2b. Backslash-escaped tag prefixes: \@ -> @, \# -> #, \+ -> +
            if (ch == '\\' && i + 1 < text.length && isTagPrefix(text[i + 1])) {
                textBuf.append(text[i + 1])
                i += 2
                continue
            }

            // 3. WhatsApp formatting markers: *bold*, _italic_, ~strike~
            if (isFormattingMarker(ch) &&
                ch !in activeMarkers &&
                isValidOpeningMarker(text, i, ch)
            ) {
                val closeIdx = findClosingMarker(text, i, ch, activeMarkers)
                if (closeIdx != -1) {
                    flushText()
                    val innerText = text.substring(i + 1, closeIdx)
                    val children = parseInlineLine(innerText, extractedUrls, activeMarkers + ch)
                    result += wrapMarkerSpan(ch, children)
                    i = closeIdx + 1
                    continue
                }
            }

            textBuf.append(ch)
            i++
        }

        flushText()
        return result
    }

    private fun findClosingMarker(
        text: String,
        openIdx: Int,
        marker: Char,
        activeMarkers: Set<Char>,
    ): Int {
        val searchMarkers = activeMarkers + marker
        var i = openIdx + 1
        while (i < text.length) {
            val ch = text[i]

            // Skip inline code spans verbatim so markers inside `...` cannot close formatting
            if (ch == '`') {
                val closeCode = text.indexOf('`', startIndex = i + 1)
                if (closeCode > i + 1) {
                    i = closeCode + 1
                    continue
                }
            }

            // Skip URLs so markers inside URL paths/queries (e.g. underscores) don't falsely close
            if ((ch == 'h' || ch == 'H') &&
                isValidUrlStartBoundary(text, i) &&
                startsWithHttpScheme(text, i)
            ) {
                val urlMatch = matchUrl(text, i, searchMarkers)
                if (urlMatch != null) {
                    i = urlMatch.endExclusive
                    continue
                }
            }

            if (ch == marker && i > openIdx + 1 && isValidClosingMarker(text, i, marker)) {
                return i
            }
            i++
        }
        return -1
    }

    private fun isValidOpeningMarker(text: String, index: Int, marker: Char): Boolean {
        if (index + 1 >= text.length) return false
        if (index > 0 && text[index - 1].isLetterOrDigit()) return false
        val next = text[index + 1]
        return !next.isWhitespace() && next != marker
    }

    private fun wrapMarkerSpan(marker: Char, children: List<InlineSpan>): InlineSpan =
        when (marker) {
            '*' -> InlineSpan.Bold(children)
            '_' -> InlineSpan.Italic(children)
            '~' -> InlineSpan.Strike(children)
            else -> error("Unsupported formatting marker: $marker")
        }

    private fun buildBodyPlain(blocks: List<MessageBlock>): String {
        val parts = mutableListOf<String>()
        for (block in blocks) {
            val text = when (block) {
                is MessageBlock.Paragraph -> spansToPlain(block.spans)
                is MessageBlock.QuoteBlock -> block.lines.joinToString("\n") { spansToPlain(it) }
                is MessageBlock.CodeBlock -> block.code
            }
            if (text.isNotEmpty()) {
                parts += text
            }
        }
        return parts.joinToString("\n")
    }

    private fun spansToPlain(spans: List<InlineSpan>): String {
        val sb = StringBuilder()
        fun appendAll(list: List<InlineSpan>) {
            for (span in list) {
                when (span) {
                    is InlineSpan.Text -> sb.append(span.text)
                    is InlineSpan.Bold -> appendAll(span.children)
                    is InlineSpan.Italic -> appendAll(span.children)
                    is InlineSpan.Strike -> appendAll(span.children)
                    is InlineSpan.InlineCode -> sb.append(span.code)
                    is InlineSpan.Url -> sb.append(span.rawUrl)
                }
            }
        }
        appendAll(spans)
        return sb.toString()
    }

    private fun appendSpan(target: MutableList<InlineSpan>, span: InlineSpan) {
        if (span is InlineSpan.Text && target.lastOrNull() is InlineSpan.Text) {
            val prev = target.removeAt(target.lastIndex) as InlineSpan.Text
            target += InlineSpan.Text(prev.text + span.text)
        } else {
            target += span
        }
    }

    private fun stripQuotePrefix(line: String): String {
        val afterCaret = line.substring(1)
        return if (afterCaret.startsWith(' ')) afterCaret.substring(1) else afterCaret
    }

    private fun isFormattingMarker(ch: Char): Boolean =
        ch == '*' || ch == '_' || ch == '~'

    companion object {
        private const val FENCE = "```"
        private const val LONG_URL_THRESHOLD = 40
        private const val MAX_SHORT_PATH_SEGMENTS = 2
        private val TRAILING_PUNCTUATION = setOf('.', ',', ';', ':', '!', '?')
        private val PUNCTUATION_NO_LEADING_SPACE = setOf('.', ',', ';', ':', '!', '?', ')', ']', '}', '*', '_', '~')

        private data class UrlMatch(
            val rawUrl: String,
            val domain: String,
            val isLong: Boolean,
            val endExclusive: Int,
        )

        private data class LineStripResult(
            val cleaned: String,
            val hadStrippedTag: Boolean,
        )

        /**
         * Extracts lowercase literal tag keys (e.g. `"@alice"`, `"#123"`, `"+foo"`) from unescaped
         * tag-like tokens in [text]. Used by edit flows to preserve pre-existing literal tokens.
         */
        fun extractLiteralTagTokenKeys(text: String): Set<String> {
            val (_, tokens) = stripInlineTagsAndUnescape(text)
            return tokens.mapTo(HashSet()) { token ->
                val prefix = when (token.kind) {
                    TagTriggerKind.PERSON_AT -> '@'
                    TagTriggerKind.PROJECT_PLUS -> '+'
                    TagTriggerKind.ALL_HASH -> '#'
                }
                "$prefix${token.rawValue.lowercase(Locale.ROOT)}"
            }
        }

        /**
         * Strips unescaped inline `@person`, `#topic`, and `+project` tag tokens from [text]
         * (skipping fenced code blocks, inline code, and URLs) and unescapes `\@`, `\#`, `\+`
         * into literal `@`, `#`, `+`.
         */
        fun stripInlineTagsAndUnescape(
            text: String,
            preserveLiteralTokens: Set<String> = emptySet(),
        ): Pair<String, List<InlineTagToken>> {
            if (text.isEmpty()) return "" to emptyList()

            val normalized = normalizeLineEndings(text)
            val preserved = if (preserveLiteralTokens.isEmpty()) {
                emptySet()
            } else {
                preserveLiteralTokens.mapTo(HashSet()) { it.trim().lowercase(Locale.ROOT) }
            }

            val lines = normalized.split('\n')
            val outputLines = ArrayList<String>(lines.size)
            val seenTags = LinkedHashMap<String, InlineTagToken>()
            var inFencedCodeBlock = false

            for (line in lines) {
                val trimmedStart = line.trimStart()
                if (inFencedCodeBlock) {
                    outputLines += line
                    if (line.trim() == FENCE) {
                        inFencedCodeBlock = false
                    }
                    continue
                }
                if (trimmedStart.startsWith(FENCE)) {
                    outputLines += line
                    val trimmed = line.trim()
                    if (!(trimmed.length >= FENCE.length * 2 && trimmed.endsWith(FENCE))) {
                        inFencedCodeBlock = true
                    }
                    continue
                }

                if (line.startsWith(">")) {
                    val quotePrefix = if (line.length >= 2 && line[1] == ' ') "> " else ">"
                    val rest = line.substring(quotePrefix.length)
                    val stripped = stripLineInlineTagsAndUnescape(rest, preserved, seenTags)
                    if (stripped.hadStrippedTag && stripped.cleaned.isBlank()) {
                        continue
                    }
                    outputLines += if (stripped.cleaned.isEmpty() && !stripped.hadStrippedTag) {
                        line
                    } else {
                        quotePrefix + stripped.cleaned
                    }
                } else {
                    val stripped = stripLineInlineTagsAndUnescape(line, preserved, seenTags)
                    if (stripped.hadStrippedTag && stripped.cleaned.isBlank()) {
                        continue
                    }
                    outputLines += stripped.cleaned
                }
            }

            return outputLines.joinToString("\n") to seenTags.values.toList()
        }

        private fun stripLineInlineTagsAndUnescape(
            text: String,
            preserved: Set<String>,
            seenTags: LinkedHashMap<String, InlineTagToken>,
        ): LineStripResult {
            if (text.isEmpty()) return LineStripResult("", hadStrippedTag = false)

            val out = StringBuilder(text.length)
            var hadStrippedTag = false
            var i = 0

            while (i < text.length) {
                val ch = text[i]

                // 1. Inline code: keep verbatim
                if (ch == '`') {
                    val closeIdx = text.indexOf('`', startIndex = i + 1)
                    if (closeIdx > i + 1) {
                        out.append(text, i, closeIdx + 1)
                        i = closeIdx + 1
                        continue
                    }
                    out.append(ch)
                    i++
                    continue
                }

                // 2. URL: keep verbatim
                if ((ch == 'h' || ch == 'H') &&
                    isValidUrlStartBoundary(text, i) &&
                    startsWithHttpScheme(text, i)
                ) {
                    val urlMatch = matchUrl(text, i, emptySet())
                    if (urlMatch != null) {
                        out.append(text, i, urlMatch.endExclusive)
                        i = urlMatch.endExclusive
                        continue
                    }
                }

                // 3. Backslash-escaped tag prefix: \@ -> @, \# -> #, \+ -> + (never tagged)
                if (ch == '\\' && i + 1 < text.length && isTagPrefix(text[i + 1])) {
                    out.append(text[i + 1])
                    i += 2
                    continue
                }

                // 4. Unescaped inline tag token at valid boundary
                if (isTagPrefix(ch) && (i == 0 || isTriggerPrecedingChar(text[i - 1]))) {
                    var end = i + 1
                    while (end < text.length && isValidTagQueryChar(text[end])) {
                        end++
                    }
                    val raw = text.substring(i + 1, end).trimEnd('.', '/', '-', '_')
                    if (raw.isNotEmpty() && raw.any { it.isLetterOrDigit() }) {
                        val tokenEnd = i + 1 + raw.length
                        val literalKey = "$ch${raw.lowercase(Locale.ROOT)}"
                        if (literalKey in preserved) {
                            out.append(text, i, tokenEnd)
                            i = tokenEnd
                            continue
                        }

                        val kind = when (ch) {
                            '@' -> TagTriggerKind.PERSON_AT
                            '+' -> TagTriggerKind.PROJECT_PLUS
                            else -> TagTriggerKind.ALL_HASH
                        }
                        val dedupeKey = "${kind.name}:${raw.lowercase(Locale.ROOT)}"
                        seenTags.putIfAbsent(dedupeKey, InlineTagToken(kind = kind, rawValue = raw))
                        hadStrippedTag = true

                        var nextIdx = tokenEnd
                        val nextChar = text.getOrNull(nextIdx)
                        val outEndsWithSpace = out.isNotEmpty() && (out.last() == ' ' || out.last() == '\t')

                        when {
                            out.isEmpty() && (nextChar == ' ' || nextChar == '\t') -> {
                                while (nextIdx < text.length && (text[nextIdx] == ' ' || text[nextIdx] == '\t')) {
                                    nextIdx++
                                }
                            }

                            outEndsWithSpace && (nextChar == ' ' || nextChar == '\t') -> {
                                while (nextIdx < text.length && (text[nextIdx] == ' ' || text[nextIdx] == '\t')) {
                                    nextIdx++
                                }
                            }

                            outEndsWithSpace && (nextChar == null || nextChar in PUNCTUATION_NO_LEADING_SPACE) -> {
                                while (out.isNotEmpty() && (out.last() == ' ' || out.last() == '\t')) {
                                    out.setLength(out.length - 1)
                                }
                            }
                        }

                        i = nextIdx
                        continue
                    }
                }

                out.append(ch)
                i++
            }

            return LineStripResult(
                cleaned = out.toString(),
                hadStrippedTag = hadStrippedTag,
            )
        }

        private fun normalizeLineEndings(input: String): String =
            input.replace("\r\n", "\n").replace('\r', '\n')

        private fun startsWithHttpScheme(text: String, index: Int): Boolean =
            text.regionMatches(index, "https://", 0, 8, ignoreCase = true) ||
                text.regionMatches(index, "http://", 0, 7, ignoreCase = true)

        private fun isValidUrlStartBoundary(text: String, index: Int): Boolean =
            index == 0 || !text[index - 1].isLetterOrDigit()

        private fun isTagPrefix(ch: Char): Boolean =
            ch == '@' || ch == '#' || ch == '+'

        private fun isTriggerPrecedingChar(ch: Char): Boolean =
            ch.isWhitespace() || ch == '(' || ch == '[' || ch == '{'

        private fun isValidTagQueryChar(ch: Char): Boolean =
            ch.isLetterOrDigit() || ch == '/' || ch == '-' || ch == '_' || ch == '.'

        private fun isValidClosingMarker(text: String, index: Int, marker: Char): Boolean {
            if (index <= 0) return false
            val prev = text[index - 1]
            if (prev.isWhitespace() || prev == marker) return false
            if (index + 1 < text.length && text[index + 1].isLetterOrDigit()) return false
            return true
        }

        private fun matchUrl(
            text: String,
            start: Int,
            activeMarkers: Set<Char>,
        ): UrlMatch? {
            var end = start
            while (end < text.length) {
                val c = text[end]
                if (c.isWhitespace() || c == '<' || c == '>' || c == '"' || c == '`') {
                    break
                }
                end++
            }

            // Trim trailing sentence punctuation, unmatched closing brackets/parens, quotes,
            // and any active formatting delimiter sitting at a valid close boundary.
            while (end > start) {
                val last = text[end - 1]
                val candidate = text.substring(start, end)
                val shouldTrim = when {
                    last in TRAILING_PUNCTUATION -> true
                    last == '\'' -> true
                    last in activeMarkers && isValidClosingMarker(text, end - 1, last) -> true
                    last == ')' -> candidate.count { it == ')' } > candidate.count { it == '(' }
                    last == ']' -> candidate.count { it == ']' } > candidate.count { it == '[' }
                    last == '}' -> candidate.count { it == '}' } > candidate.count { it == '{' }
                    else -> false
                }
                if (shouldTrim) {
                    end--
                } else {
                    break
                }
            }

            val rawUrl = text.substring(start, end)
            val schemeEnd = when {
                rawUrl.startsWith("https://", ignoreCase = true) -> 8
                rawUrl.startsWith("http://", ignoreCase = true) -> 7
                else -> return null
            }

            val afterScheme = rawUrl.substring(schemeEnd)
            if (afterScheme.isEmpty()) return null

            val authorityEnd = afterScheme.indexOfAny(charArrayOf('/', '?', '#'))
            val authority = if (authorityEnd == -1) afterScheme else afterScheme.substring(0, authorityEnd)
            val remainder = if (authorityEnd == -1) "" else afterScheme.substring(authorityEnd)

            val domain = extractDomain(authority) ?: return null
            val isLong = computeIsLongUrl(rawUrl, remainder)

            return UrlMatch(
                rawUrl = rawUrl,
                domain = domain,
                isLong = isLong,
                endExclusive = end,
            )
        }

        private fun extractDomain(authority: String): String? {
            if (authority.isEmpty()) return null
            val hostPort = authority.substringAfterLast('@')
            if (hostPort.isEmpty()) return null

            val host = if (hostPort.startsWith('[')) {
                val closing = hostPort.indexOf(']')
                if (closing <= 1) return null
                hostPort.substring(0, closing + 1)
            } else {
                hostPort.substringBefore(':')
            }

            if (host.isEmpty() || host.startsWith('.') || host.endsWith('.')) return null
            if (!host.any { it.isLetterOrDigit() }) return null

            val normalized = host.lowercase(Locale.ROOT).removePrefix("www.")
            return normalized.ifEmpty { null }
        }

        private fun computeIsLongUrl(rawUrl: String, remainder: String): Boolean {
            if (rawUrl.length > LONG_URL_THRESHOLD) return true

            val beforeFragment = remainder.substringBefore('#')
            val queryIndex = beforeFragment.indexOf('?')
            if (queryIndex != -1) return true

            val path = beforeFragment
            val nonEmptySegments = path.split('/').count { it.isNotEmpty() }
            return nonEmptySegments > MAX_SHORT_PATH_SEGMENTS
        }
    }
}
