// Copyright (c) 2026 k8242
// SPDX-License-Identifier: MIT

package io.github.k8242.threadlog.core.data.local.fts

import io.github.k8242.threadlog.core.model.Tag
import java.util.Locale

/**
 * Tokenizes and sanitizes arbitrary user input into safe SQLite FTS5 `MATCH` expressions
 * compatible with the `unicode61` tokenizer, preventing syntax errors from FTS5 operators
 * and punctuation (`"`, `*`, `-`, `:`, `(`, `)`, `NOT`, `AND`, `OR`, `NEAR`, etc.).
 */
object FtsQueryBuilder {

    /**
     * Extracts normalized lowercase alphanumeric tokens matching SQLite `unicode61` word boundaries.
     */
    fun extractTokens(rawQuery: String): List<String> {
        if (rawQuery.isBlank()) return emptyList()
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        for (ch in rawQuery) {
            if (ch.isLetterOrDigit()) {
                current.append(ch.lowercaseChar())
            } else if (current.isNotEmpty()) {
                tokens += current.toString()
                current.setLength(0)
            }
        }
        if (current.isNotEmpty()) {
            tokens += current.toString()
        }
        return tokens.distinct()
    }

    /**
     * Builds an FTS5 `MATCH` expression from sanitized [tokens].
     *
     * When [includeHiddenAutoTags] is `true`, searches all indexed columns (`body_plain`,
     * `visible_tags`, `hidden_tags`). When `false`, restricts the query strictly to
     * `{body_plain visible_tags}`.
     */
    fun buildMatchExpression(tokens: List<String>, includeHiddenAutoTags: Boolean): String? {
        if (tokens.isEmpty()) return null
        val terms = tokens.joinToString(separator = " ") { token ->
            val escaped = token.replace("\"", "\"\"")
            "\"$escaped\"*"
        }
        return if (includeHiddenAutoTags) {
            terms
        } else {
            "{body_plain visible_tags} : ($terms)"
        }
    }

    /**
     * Builds the space-separated FTS5 index text for a collection of tags, including each tag's
     * full path, leaf name, and aliases.
     */
    fun buildTagIndexText(tags: Collection<Tag>): String {
        if (tags.isEmpty()) return ""
        val parts = LinkedHashSet<String>()
        for (tag in tags) {
            if (tag.fullPath.isNotBlank()) parts += tag.fullPath
            if (tag.leafName.isNotBlank()) parts += tag.leafName
            for (alias in tag.aliases) {
                val trimmed = alias.trim()
                if (trimmed.isNotEmpty()) parts += trimmed
            }
        }
        return parts.joinToString(separator = " ")
    }

    /**
     * Returns `true` if any searchable token of [tag] (from its full path, leaf name, or aliases)
     * matches any token in [queryTokens] as a case-insensitive prefix (mirroring FTS5 `"token"*`).
     */
    fun tagMatchesQueryTokens(tag: Tag, queryTokens: List<String>): Boolean {
        if (queryTokens.isEmpty()) return false
        val tagTokens = buildSet {
            addAll(extractTokens(tag.fullPath))
            addAll(extractTokens(tag.leafName))
            for (alias in tag.aliases) {
                addAll(extractTokens(alias))
            }
        }
        if (tagTokens.isEmpty()) return false
        return queryTokens.any { qToken ->
            val normalizedQuery = qToken.lowercase(Locale.ROOT)
            tagTokens.any { tagToken -> tagToken.startsWith(normalizedQuery) }
        }
    }
}
