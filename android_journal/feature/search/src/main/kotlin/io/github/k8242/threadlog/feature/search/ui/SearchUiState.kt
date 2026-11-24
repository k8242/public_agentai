// Copyright (c) 2026 k8242
// SPDX-License-Identifier: MIT

package io.github.k8242.threadlog.feature.search.ui

import io.github.k8242.threadlog.core.model.HybridSearchResult
import io.github.k8242.threadlog.core.model.SearchHit

/**
 * UI state for the two-tier hybrid search screen.
 *
 * - Tier A (`<= 5 weeks`) exposes an interactive match stepper indexed by [tierAIndex].
 * - Tier B (`> 5 weeks`) renders compact rows that open the `±12h` Context Window.
 */
data class SearchUiState(
    val query: String = "",
    val includeHiddenAutoTags: Boolean = true,
    val includeArchived: Boolean = false,
    val isSearching: Boolean = false,
    val result: HybridSearchResult? = null,
    val tierAIndex: Int = 0,
    val tagDisplayLabels: Map<String, String> = emptyMap(),
    val errorMessage: String? = null,
) {
    /** Recent hits within the last 5 weeks, newest first. */
    val tierAHits: List<SearchHit>
        get() = result?.tierARecent.orEmpty()

    /** Older hits beyond 5 weeks, newest first. */
    val tierBHits: List<SearchHit>
        get() = result?.tierBOlder.orEmpty()

    /** Currently active Tier A hit focused in the recent-match stepper. */
    val activeTierAHit: SearchHit?
        get() = tierAHits.getOrNull(tierAIndex)

    val tierACount: Int
        get() = tierAHits.size

    val tierBCount: Int
        get() = tierBHits.size

    val totalCount: Int
        get() = tierACount + tierBCount
}
