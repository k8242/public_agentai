// Copyright (c) 2026 k8242
// SPDX-License-Identifier: MIT

package io.github.k8242.threadlog.feature.chat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.k8242.threadlog.core.model.parser.InlineSpan
import io.github.k8242.threadlog.core.model.parser.MessageBlock
import io.github.k8242.threadlog.core.model.parser.ParsedMessage

/**
 * Renders a [ParsedMessage] into structured Material 3 blocks: paragraphs with inline WhatsApp
 * formatting, quote blocks with a left accent bar, and scrollable monospace fenced code blocks.
 */
@Composable
fun FormattedMessageBody(
    parsedMessage: ParsedMessage,
    modifier: Modifier = Modifier,
) {
    if (parsedMessage.blocks.isEmpty()) return

    val uriHandler = LocalUriHandler.current
    val linkColor = MaterialTheme.colorScheme.primary
    val inlineCodeBg = MaterialTheme.colorScheme.surfaceContainerHighest
    val inlineCodeFg = MaterialTheme.colorScheme.onSurface
    val quoteAccent = MaterialTheme.colorScheme.primary
    val quoteBg = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.65f)
    val quoteTextColor = MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        parsedMessage.blocks.forEach { block ->
            when (block) {
                is MessageBlock.Paragraph -> {
                    val annotated = remember(block, linkColor, inlineCodeBg, inlineCodeFg) {
                        buildInlineAnnotatedString(
                            spans = block.spans,
                            linkColor = linkColor,
                            inlineCodeBg = inlineCodeBg,
                            inlineCodeFg = inlineCodeFg,
                            onUrlClick = { url -> runCatching { uriHandler.openUri(url) } },
                        )
                    }
                    Text(
                        text = annotated,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                is MessageBlock.QuoteBlock -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Min)
                            .clip(RoundedCornerShape(8.dp))
                            .background(quoteBg)
                            .padding(end = 10.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .fillMaxHeight()
                                .background(quoteAccent),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(
                            modifier = Modifier.padding(vertical = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            block.lines.forEach { lineSpans ->
                                val annotated = remember(lineSpans, linkColor, inlineCodeBg, inlineCodeFg) {
                                    buildInlineAnnotatedString(
                                        spans = lineSpans,
                                        linkColor = linkColor,
                                        inlineCodeBg = inlineCodeBg,
                                        inlineCodeFg = inlineCodeFg,
                                        onUrlClick = { url -> runCatching { uriHandler.openUri(url) } },
                                    )
                                }
                                Text(
                                    text = annotated,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = quoteTextColor,
                                )
                            }
                        }
                    }
                }

                is MessageBlock.CodeBlock -> {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                            if (!block.language.isNullOrBlank()) {
                                Text(
                                    text = block.language.orEmpty(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(bottom = 4.dp),
                                )
                            }
                            Text(
                                text = block.code,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    lineHeight = 18.sp,
                                ),
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun buildInlineAnnotatedString(
    spans: List<InlineSpan>,
    linkColor: Color,
    inlineCodeBg: Color,
    inlineCodeFg: Color,
    onUrlClick: (String) -> Unit,
): AnnotatedString = buildAnnotatedString {
    appendInlineSpans(
        spans = spans,
        linkColor = linkColor,
        inlineCodeBg = inlineCodeBg,
        inlineCodeFg = inlineCodeFg,
        onUrlClick = onUrlClick,
    )
}

private fun AnnotatedString.Builder.appendInlineSpans(
    spans: List<InlineSpan>,
    linkColor: Color,
    inlineCodeBg: Color,
    inlineCodeFg: Color,
    onUrlClick: (String) -> Unit,
) {
    for (span in spans) {
        when (span) {
            is InlineSpan.Text -> append(span.text)

            is InlineSpan.Bold -> {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    appendInlineSpans(span.children, linkColor, inlineCodeBg, inlineCodeFg, onUrlClick)
                }
            }

            is InlineSpan.Italic -> {
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    appendInlineSpans(span.children, linkColor, inlineCodeBg, inlineCodeFg, onUrlClick)
                }
            }

            is InlineSpan.Strike -> {
                withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                    appendInlineSpans(span.children, linkColor, inlineCodeBg, inlineCodeFg, onUrlClick)
                }
            }

            is InlineSpan.InlineCode -> {
                withStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = inlineCodeBg,
                        color = inlineCodeFg,
                    ),
                ) {
                    append(" ${span.code} ")
                }
            }

            is InlineSpan.Url -> {
                val displayText = if (span.isLong) span.domain else span.rawUrl
                withLink(
                    LinkAnnotation.Url(
                        url = span.rawUrl,
                        styles = TextLinkStyles(
                            style = SpanStyle(
                                color = linkColor,
                                textDecoration = TextDecoration.Underline,
                            ),
                        ),
                        linkInteractionListener = { onUrlClick(span.rawUrl) },
                    ),
                ) {
                    append(displayText)
                }
            }
        }
    }
}
