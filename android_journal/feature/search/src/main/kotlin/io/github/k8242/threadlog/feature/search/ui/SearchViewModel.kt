// Copyright (c) 2026 k8242
// SPDX-License-Identifier: MIT

package io.github.k8242.threadlog.feature.search.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.k8242.threadlog.core.model.SearchHit
import io.github.k8242.threadlog.core.model.WeekStart
import io.github.k8242.threadlog.core.model.contract.DateWeekCalculator
import io.github.k8242.threadlog.core.model.contract.LeafDisambiguator
import io.github.k8242.threadlog.core.model.contract.SearchRepository
import io.github.k8242.threadlog.core.model.contract.TagRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel driving two-tier hybrid FTS5 search:
 * - Tier A (`<= 5 weeks`) with an interactive wrapping stepper and jump-in-chat navigation.
 * - Tier B (`> 5 weeks`) opening the interactive `±12h` Context Window.
 */
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val searchRepository: SearchRepository,
    private val tagRepository: TagRepository,
    private val leafDisambiguator: LeafDisambiguator,
    private val dateWeekCalculator: DateWeekCalculator,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null
    private var lastInitializedQuery: String? = null

    init {
        viewModelScope.launch {
            tagRepository.observeAllTags().collect { tags ->
                val labels = leafDisambiguator.computeDisplayLabels(tags)
                _uiState.update { it.copy(tagDisplayLabels = labels) }
            }
        }
    }

    /**
     * Applies an optional route-provided initial query once per distinct non-blank value and
     * immediately executes the search.
     */
    fun initQuery(initialQuery: String?) {
        val normalized = initialQuery?.trim().orEmpty()
        if (normalized.isEmpty() || normalized == lastInitializedQuery) return
        lastInitializedQuery = normalized
        _uiState.update { it.copy(query = normalized, errorMessage = null) }
        scheduleSearch(
            query = normalized,
            includeHiddenAutoTags = _uiState.value.includeHiddenAutoTags,
            debounceMs = 0L,
        )
    }

    /** Updates the search query and schedules a debounced search (cancelling any in-flight query). */
    fun onQueryChanged(newQuery: String) {
        _uiState.update { it.copy(query = newQuery, errorMessage = null) }
        val trimmed = newQuery.trim()
        if (trimmed.isEmpty()) {
            searchJob?.cancel()
            searchJob = null
            _uiState.update {
                it.copy(
                    isSearching = false,
                    result = null,
                    tierAIndex = 0,
                    errorMessage = null,
                )
            }
            return
        }

        scheduleSearch(
            query = trimmed,
            includeHiddenAutoTags = _uiState.value.includeHiddenAutoTags,
            debounceMs = SEARCH_DEBOUNCE_MS,
        )
    }

    /** Toggles inclusion of hidden auto-tags in search matches and re-queries immediately if active. */
    fun toggleIncludeHiddenAutoTags() {
        var nextIncludeHidden = true
        var activeQuery = ""
        _uiState.update { state ->
            nextIncludeHidden = !state.includeHiddenAutoTags
            activeQuery = state.query.trim()
            state.copy(
                includeHiddenAutoTags = nextIncludeHidden,
                errorMessage = null,
            )
        }

        if (activeQuery.isNotEmpty()) {
            scheduleSearch(
                query = activeQuery,
                includeHiddenAutoTags = nextIncludeHidden,
                debounceMs = 0L,
            )
        }
    }

    /** Clears the current query, cancels any pending search, and resets results. */
    fun clearQuery() {
        searchJob?.cancel()
        searchJob = null
        lastInitializedQuery = null
        _uiState.update {
            it.copy(
                query = "",
                isSearching = false,
                result = null,
                tierAIndex = 0,
                errorMessage = null,
            )
        }
    }

    /** Steps backward through Tier A (`<= 5 weeks`) matches, wrapping from `0` to `size - 1`. */
    fun stepPreviousTierA() {
        _uiState.update { state ->
            val count = state.tierACount
            if (count <= 0) {
                state
            } else {
                val nextIndex = (state.tierAIndex - 1 + count) % count
                state.copy(tierAIndex = nextIndex)
            }
        }
    }

    /** Steps forward through Tier A (`<= 5 weeks`) matches, wrapping from `size - 1` to `0`. */
    fun stepNextTierA() {
        _uiState.update { state ->
            val count = state.tierACount
            if (count <= 0) {
                state
            } else {
                val nextIndex = (state.tierAIndex + 1) % count
                state.copy(tierAIndex = nextIndex)
            }
        }
    }

    /** Jumps the Tier A stepper directly to [index], clamped safely into `0 until tierACount`. */
    fun selectTierAIndex(index: Int) {
        _uiState.update { state ->
            val count = state.tierACount
            if (count <= 0) {
                state
            } else {
                state.copy(tierAIndex = index.coerceIn(0, count - 1))
            }
        }
    }

    /** Formats a human-readable date and time label anchored in the hit's original [SearchHit.zoneId]. */
    fun formatDateTimeLabel(hit: SearchHit): String {
        val meta = dateWeekCalculator.dividerMeta(
            epochMs = hit.createdAtMs,
            zoneId = hit.zoneId,
            weekStart = WeekStart.MONDAY,
        )
        return "${meta.dayLabel} \u00B7 ${meta.timeLabel}"
    }

    private fun scheduleSearch(
        query: String,
        includeHiddenAutoTags: Boolean,
        debounceMs: Long,
    ) {
        searchJob?.cancel()
        _uiState.update { it.copy(isSearching = true, errorMessage = null) }
        searchJob = viewModelScope.launch {
            if (debounceMs > 0L) {
                delay(debounceMs)
            }
            try {
                val result = searchRepository.search(
                    query = query,
                    includeHiddenAutoTags = includeHiddenAutoTags,
                )
                _uiState.update {
                    it.copy(
                        isSearching = false,
                        result = result,
                        tierAIndex = 0,
                        errorMessage = null,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(
                        isSearching = false,
                        errorMessage = error.message ?: "Unable to complete search.",
                    )
                }
            }
        }
    }

    companion object {
        const val SEARCH_DEBOUNCE_MS: Long = 200L
    }
}
