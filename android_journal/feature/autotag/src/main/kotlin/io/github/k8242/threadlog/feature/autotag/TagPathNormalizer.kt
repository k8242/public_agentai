package io.github.k8242.threadlog.feature.autotag

import java.util.Locale

/**
 * Shared normalization helpers for tag paths and bookmark domains within `:feature:autotag`.
 */
internal object TagPathNormalizer {

    /**
     * Normalizes a hierarchical tag path by trimming whitespace and leading/trailing slashes,
     * and collapsing empty segments (e.g. `"/projects/journal-app/"` -> `"projects/journal-app"`).
     */
    fun normalizeTagPath(rawPath: String): String =
        rawPath
            .trim()
            .trim('/')
            .split('/')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("/")

    /**
     * Canonical lowercase key for case-insensitive tag path comparison and deduplication.
     */
    fun canonicalKey(rawPath: String): String =
        normalizeTagPath(rawPath).lowercase(Locale.ROOT)

    /**
     * Normalizes a host/domain string by lowercasing, removing optional URL scheme/path/port,
     * stripping any leading `www.`, and trimming trailing dots.
     */
    fun normalizeDomain(rawDomain: String): String =
        rawDomain
            .trim()
            .lowercase(Locale.ROOT)
            .removePrefix("https://")
            .removePrefix("http://")
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
            .substringBefore(':')
            .removePrefix("www.")
            .trimEnd('.')
}
