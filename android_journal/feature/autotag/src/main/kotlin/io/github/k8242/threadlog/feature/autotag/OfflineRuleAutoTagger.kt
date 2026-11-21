// Copyright (c) 2026 k8242
// SPDX-License-Identifier: MIT

package io.github.k8242.threadlog.feature.autotag

import io.github.k8242.threadlog.core.model.AutoTagRule
import io.github.k8242.threadlog.core.model.AutoTagSuggestion
import io.github.k8242.threadlog.core.model.Bookmark
import io.github.k8242.threadlog.core.model.Entry
import io.github.k8242.threadlog.core.model.RuleMatchType
import io.github.k8242.threadlog.core.model.Tag
import io.github.k8242.threadlog.core.model.TagRootCategory
import io.github.k8242.threadlog.core.model.contract.AutoTagProvider
import java.util.Locale
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Offline deterministic [AutoTagProvider] powered by user-configured [AutoTagRule]s
 * and existing [Tag] leaf names / aliases.
 */
@Singleton
class OfflineRuleAutoTagger @Inject constructor() : AutoTagProvider {

    override val providerId: String = PROVIDER_ID

    override suspend fun suggestTags(
        entry: Entry,
        bookmarks: List<Bookmark>,
        allTags: List<Tag>,
        rules: List<AutoTagRule>,
    ): List<AutoTagSuggestion> {
        if (entry.isDeleted) return emptyList()

        val candidates = mutableListOf<AutoTagSuggestion>()

        val keywordSearchCorpus = buildKeywordCorpus(entry, bookmarks)
        val normalizedBookmarkDomains = bookmarks
            .map { TagPathNormalizer.normalizeDomain(it.domain) }
            .filter { it.isNotEmpty() }

        for (rule in rules) {
            if (!rule.enabled) continue

            val normalizedTarget = TagPathNormalizer.normalizeTagPath(rule.targetTagPath)
            if (normalizedTarget.isEmpty()) continue

            val trimmedPattern = rule.pattern.trim()
            if (trimmedPattern.isEmpty()) continue

            val clampedConfidence = rule.confidence.coerceIn(0f, 1f)

            when (rule.matchType) {
                RuleMatchType.KEYWORD -> {
                    if (keywordSearchCorpus.any { containsWholeToken(it, trimmedPattern) }) {
                        candidates += AutoTagSuggestion(
                            tagPath = normalizedTarget,
                            confidence = clampedConfidence,
                            reason = "Matched keyword '$trimmedPattern'",
                        )
                    }
                }

                RuleMatchType.DOMAIN -> {
                    val ruleDomain = TagPathNormalizer.normalizeDomain(trimmedPattern)
                    if (ruleDomain.isNotEmpty() &&
                        normalizedBookmarkDomains.any { matchesDomain(it, ruleDomain) }
                    ) {
                        candidates += AutoTagSuggestion(
                            tagPath = normalizedTarget,
                            confidence = clampedConfidence,
                            reason = "Matched domain '$ruleDomain'",
                        )
                    }
                }

                RuleMatchType.REGEX -> {
                    if (matchesRegexSafely(entry.bodyPlain, rule.pattern)) {
                        candidates += AutoTagSuggestion(
                            tagPath = normalizedTarget,
                            confidence = clampedConfidence,
                            reason = "Matched regex '${rule.pattern}'",
                        )
                    }
                }
            }
        }

        if (entry.bodyPlain.isNotBlank()) {
            for (tag in allTags) {
                if (tag.rootCategory == TagRootCategory.AUTO) continue

                val normalizedTagPath = TagPathNormalizer.normalizeTagPath(tag.fullPath)
                if (normalizedTagPath.isEmpty()) continue

                val leaf = tag.leafName.trim()
                if (leaf.length >= MIN_IMPLICIT_TOKEN_LENGTH &&
                    containsWholeToken(entry.bodyPlain, leaf)
                ) {
                    candidates += AutoTagSuggestion(
                        tagPath = normalizedTagPath,
                        confidence = LEAF_MATCH_CONFIDENCE,
                        reason = "Matched tag '$leaf'",
                    )
                }

                for (rawAlias in tag.aliases) {
                    val alias = rawAlias.trim()
                    if (alias.length >= MIN_IMPLICIT_TOKEN_LENGTH &&
                        containsWholeToken(entry.bodyPlain, alias)
                    ) {
                        candidates += AutoTagSuggestion(
                            tagPath = normalizedTagPath,
                            confidence = ALIAS_MATCH_CONFIDENCE,
                            reason = "Matched alias '$alias'",
                        )
                    }
                }
            }
        }

        val bestByCanonicalPath = LinkedHashMap<String, AutoTagSuggestion>()
        for (suggestion in candidates) {
            val key = suggestion.tagPath.lowercase(Locale.ROOT)
            val existing = bestByCanonicalPath[key]
            if (existing == null || suggestion.confidence > existing.confidence) {
                bestByCanonicalPath[key] = suggestion
            }
        }

        return bestByCanonicalPath.values.sortedWith(
            compareByDescending<AutoTagSuggestion> { it.confidence }
                .thenBy { it.tagPath },
        )
    }

    private fun buildKeywordCorpus(entry: Entry, bookmarks: List<Bookmark>): List<String> =
        buildList {
            if (entry.bodyPlain.isNotBlank()) {
                add(entry.bodyPlain)
            }
            for (bookmark in bookmarks) {
                bookmark.title?.takeIf { it.isNotBlank() }?.let(::add)
                bookmark.note?.takeIf { it.isNotBlank() }?.let(::add)
            }
        }

    private fun containsWholeToken(text: String, token: String): Boolean {
        if (text.isBlank() || token.isBlank()) return false
        val pattern = Pattern.compile(
            "(?<![\\p{L}\\p{N}_])${Pattern.quote(token)}(?![\\p{L}\\p{N}_])",
            Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE,
        )
        return pattern.matcher(text).find()
    }

    private fun matchesDomain(bookmarkDomain: String, ruleDomain: String): Boolean =
        bookmarkDomain == ruleDomain || bookmarkDomain.endsWith(".$ruleDomain")

    private fun matchesRegexSafely(text: String, patternText: String): Boolean {
        if (text.isBlank() || patternText.isBlank()) return false
        return try {
            Pattern.compile(
                patternText,
                Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE,
            ).matcher(text).find()
        } catch (_: PatternSyntaxException) {
            false
        }
    }

    companion object {
        const val PROVIDER_ID: String = "offline_rule_v1"
        const val LEAF_MATCH_CONFIDENCE: Float = 0.75f
        const val ALIAS_MATCH_CONFIDENCE: Float = 0.80f
        const val MIN_IMPLICIT_TOKEN_LENGTH: Int = 2
    }
}
