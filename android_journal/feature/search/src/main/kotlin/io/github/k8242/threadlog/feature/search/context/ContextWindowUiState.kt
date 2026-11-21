// Copyright (c) 2026 k8242
// SPDX-License-Identifier: MIT

package io.github.k8242.threadlog.feature.search.context

import io.github.k8242.threadlog.core.model.ContextWindowState
import io.github.k8242.threadlog.core.model.EntryWithDetails

/**
 * UI state for the interactive `±12h` Context Window screen.
 */
data class ContextWindowUiState(
    val isLoading: Boolean = true,
    val anchorEntryId: String = "",
    val highlightQuery: String? = null,
    val contextState: ContextWindowState? = null,
    val tagDisplayLabels: Map<String, String> = emptyMap(),
    val showHiddenAutoTags: Boolean = false,
    val notFound: Boolean = false,
    val errorMessage: String? = null,
) {
    /** Anchor entry within the loaded context window, if present. */
    val anchorEntry: EntryWithDetails?
        get() = contextState?.entries?.firstOrNull { it.entry.id == anchorEntryId }

    /** Hours currently included prior to the anchor entry's timestamp. */
    val earlierHours: Long
        get() {
            val state = contextState ?: return DEFAULT_HALF_WINDOW_HOURS
            return ((state.anchorCreatedAtMs - state.windowStartMs).coerceAtLeast(0L)) / MILLIS_PER_HOUR
        }

    /** Hours currently included after the anchor entry's timestamp. */
    val laterHours: Long
        get() {
            val state = contextState ?: return DEFAULT_HALF_WINDOW_HOURS
            return ((state.windowEndMs - state.anchorCreatedAtMs).coerceAtLeast(0L)) / MILLIS_PER_HOUR
        }

    /** True when the user has expanded the window beyond the default `±12h` span. */
    val isExpanded: Boolean
        get() = earlierHours > DEFAULT_HALF_WINDOW_HOURS || laterHours > DEFAULT_HALF_WINDOW_HOURS

    /** Human-readable description of the active time span (e.g. `"±12h"` or `"-24h / +12h"`). */
    val windowSpanLabel: String
        get() = if (earlierHours == laterHours) {
            "\u00B1${earlierHours}h"
        } else {
            "-${earlierHours}h / +${laterHours}h"
        }

    companion object {
        private const val MILLIS_PER_HOUR: Long = 60L * 60L * 1000L
        private const val DEFAULT_HALF_WINDOW_HOURS: Long = 12L
    }
}
