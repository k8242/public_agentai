package com.threadlog.feature.autotag.ui

import com.threadlog.core.model.EntryWithDetails
import com.threadlog.core.model.Tag

/**
 * UI state for the Auto-Tag Review screen and drawer.
 */
data class AutoTagReviewUiState(
    val pendingEntries: List<EntryWithDetails> = emptyList(),
    val displayLabels: Map<String, String> = emptyMap(),
    val isLoading: Boolean = true,
    val statusMessage: String? = null,
) {
    /** Total number of hidden auto-tag suggestions currently awaiting review. */
    val pendingTagCount: Int
        get() = pendingEntries.sumOf { it.hiddenAutoTags.size }

    /** True once initial data has loaded and there are no pending auto-tags to review. */
    val isEmpty: Boolean
        get() = !isLoading && pendingEntries.isEmpty()

    /** Resolves the disambiguated label for [tag], falling back to its [Tag.leafName]. */
    fun displayLabelFor(tag: Tag): String =
        displayLabels[tag.id] ?: tag.leafName
}
