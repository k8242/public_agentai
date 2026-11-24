// Copyright (c) 2026 k8242
// SPDX-License-Identifier: MIT

package io.github.k8242.threadlog.core.parser

import java.util.Locale

/**
 * Canonical matcher for WhatsApp-style placeholder messages (omitted media / deleted message
 * tombstones) that should be hidden across views unless explicitly toggled visible in All Notes.
 *
 * Callers in `:core:data` combine this predicate with a zero-attachments check so attaching a
 * real photo to a `<Media omitted>` note makes it visible everywhere.
 */
object HiddenPlaceholderMatcher {

    private val DIRECTIONAL_AND_BOM_CHARS = setOf(
        '\uFEFF', // BOM / ZWNBSP
        '\u200E', // Left-to-right mark
        '\u200F', // Right-to-left mark
        '\u202A', // LRE
        '\u202B', // RLE
        '\u202C', // PDF
        '\u202D', // LRO
        '\u202E', // RLO
    )

    private val EXACT_PLACEHOLDERS = setOf(
        "<media omitted>",
        "media omitted",
        "image omitted",
        "video omitted",
        "audio omitted",
        "sticker omitted",
        "gif omitted",
        "document omitted",
        "<image omitted>",
        "<video omitted>",
        "<audio omitted>",
        "<sticker omitted>",
        "<gif omitted>",
        "<document omitted>",
        "you deleted this message",
        "you deleted this message.",
        "this message was deleted",
        "this message was deleted.",
    )

    fun isHiddenPlaceholder(text: String): Boolean {
        val cleaned = normalize(text)
        if (cleaned.isEmpty()) return false
        if (matchesNormalized(cleaned)) return true

        // Optional single-line "Sender: <placeholder>" prefix from WhatsApp chat imports.
        if (!cleaned.contains('\n') && !cleaned.startsWith("<attached:")) {
            val colonIdx = cleaned.indexOf(':')
            if (colonIdx > 0) {
                val afterColon = cleaned.substring(colonIdx + 1).trim()
                if (afterColon.isNotEmpty() && matchesNormalized(afterColon)) {
                    return true
                }
            }
        }
        return false
    }

    private fun normalize(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text) {
            if (ch !in DIRECTIONAL_AND_BOM_CHARS) {
                sb.append(ch)
            }
        }
        return sb.toString().trim().lowercase(Locale.ROOT)
    }

    private fun matchesNormalized(normalized: String): Boolean {
        if (normalized in EXACT_PLACEHOLDERS) return true
        if (!normalized.contains('\n') &&
            normalized.startsWith("<attached:") &&
            normalized.endsWith(">")
        ) {
            return true
        }
        return false
    }
}
