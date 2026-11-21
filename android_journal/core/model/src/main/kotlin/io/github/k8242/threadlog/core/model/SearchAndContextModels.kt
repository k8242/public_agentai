// Copyright (c) 2026 k8242
// SPDX-License-Identifier: MIT

package io.github.k8242.threadlog.core.model

data class SearchHit(
    val entryId: String,
    val createdAtMs: Long,
    val zoneId: String,
    val localDate: String,
    val snippetMarked: String,         // FTS5 snippet with <mark>...</mark> delimiters
    val visibleTags: List<Tag>,
    val hiddenMatchedTags: List<Tag>,  // Non-empty => render auto-tag badge
    val matchedViaHiddenAutoTag: Boolean,
)

data class HybridSearchResult(
    val query: String,
    val includeHiddenAutoTags: Boolean,
    val cutoffEpochMs: Long,           // nowMs - 35 days (3_024_000_000L ms)
    val tierARecent: List<SearchHit>,  // createdAtMs >= cutoffEpochMs (<= 5 weeks), newest-first
    val tierBOlder: List<SearchHit>,   // createdAtMs < cutoffEpochMs (> 5 weeks), newest-first
) {
    companion object {
        const val TIER_A_WINDOW_MS: Long = 35L * 24L * 60L * 60L * 1000L // 3_024_000_000L
    }
}

data class ContextWindowState(
    val anchorEntryId: String,
    val anchorCreatedAtMs: Long,
    val windowStartMs: Long,           // Default: anchorCreatedAtMs - 43_200_000L (-12h)
    val windowEndMs: Long,             // Default: anchorCreatedAtMs + 43_200_000L (+12h)
    val entries: List<EntryWithDetails>,
) {
    companion object {
        const val DEFAULT_HALF_WINDOW_MS: Long = 12L * 60L * 60L * 1000L // 43_200_000L
    }
}
