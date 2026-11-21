package com.threadlog.feature.search.context

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.threadlog.core.model.ContextWindowState
import com.threadlog.core.model.Entry
import com.threadlog.core.model.WeekStart
import com.threadlog.core.model.contract.DateWeekCalculator
import com.threadlog.core.model.contract.EntryRepository
import com.threadlog.core.model.contract.LeafDisambiguator
import com.threadlog.core.model.contract.SettingsRepository
import com.threadlog.core.model.contract.TagRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel powering the interactive `±12h` Context Window around a matched older entry.
 *
 * Supports expanding the observed time range earlier/later in 12-hour increments, resetting back
 * to `±12h`, toggling hidden auto-tag visibility, and promoting/rejecting auto-tags inline.
 */
@HiltViewModel
class ContextWindowViewModel @Inject constructor(
    private val entryRepository: EntryRepository,
    private val tagRepository: TagRepository,
    private val settingsRepository: SettingsRepository,
    private val leafDisambiguator: LeafDisambiguator,
    private val dateWeekCalculator: DateWeekCalculator,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ContextWindowUiState())
    val uiState: StateFlow<ContextWindowUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null
    private var windowObservationJob: Job? = null
    private var currentWeekStart: WeekStart = WeekStart.MONDAY
    private var hasLocalHiddenTagOverride: Boolean = false
    private var loadedAnchorEntryId: String? = null
    private var anchorCreatedAtMs: Long? = null
    private var anchorLocalDate: String? = null

    init {
        viewModelScope.launch {
            tagRepository.observeAllTags().collect { tags ->
                val labels = leafDisambiguator.computeDisplayLabels(tags)
                _uiState.update { it.copy(tagDisplayLabels = labels) }
            }
        }

        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                currentWeekStart = settings.weekStart
                if (!hasLocalHiddenTagOverride) {
                    _uiState.update {
                        it.copy(showHiddenAutoTags = settings.showHiddenAutoTagsInChat)
                    }
                }
            }
        }
    }

    /**
     * Loads the anchor entry and starts observing the `±12h` window around it.
     */
    fun load(anchorEntryId: String, highlightQuery: String?) {
        val normalizedQuery = highlightQuery?.trim()?.takeIf { it.isNotEmpty() }

        // If already loaded for the same anchor, simply refresh the highlight query.
        if (loadedAnchorEntryId == anchorEntryId && !_uiState.value.notFound && _uiState.value.errorMessage == null) {
            _uiState.update { it.copy(highlightQuery = normalizedQuery) }
            return
        }

        loadedAnchorEntryId = anchorEntryId
        anchorCreatedAtMs = null
        anchorLocalDate = null
        loadJob?.cancel()
        windowObservationJob?.cancel()

        _uiState.update {
            it.copy(
                isLoading = true,
                anchorEntryId = anchorEntryId,
                highlightQuery = normalizedQuery,
                contextState = null,
                notFound = false,
                errorMessage = null,
            )
        }

        loadJob = viewModelScope.launch {
            try {
                val anchor = entryRepository.getEntry(anchorEntryId)
                if (anchor == null) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            notFound = true,
                            contextState = null,
                            errorMessage = null,
                        )
                    }
                    return@launch
                }

                val createdAt = anchor.entry.createdAtMs
                anchorCreatedAtMs = createdAt
                anchorLocalDate = anchor.entry.localDate

                val startMs = createdAt - ContextWindowState.DEFAULT_HALF_WINDOW_MS
                val endMs = createdAt + ContextWindowState.DEFAULT_HALF_WINDOW_MS

                observeWindow(
                    anchorEntryId = anchorEntryId,
                    anchorCreatedAtMs = createdAt,
                    startMs = startMs,
                    endMs = endMs,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = error.message ?: "Unable to load context window.",
                    )
                }
            }
        }
    }

    /** Extends the observed window 12 hours earlier (`windowStartMs -= 12h`). */
    fun expandEarlier() {
        val state = _uiState.value.contextState ?: return
        val newStartMs = state.windowStartMs - ContextWindowState.DEFAULT_HALF_WINDOW_MS
        observeWindow(
            anchorEntryId = state.anchorEntryId,
            anchorCreatedAtMs = state.anchorCreatedAtMs,
            startMs = newStartMs,
            endMs = state.windowEndMs,
        )
    }

    /** Extends the observed window 12 hours later (`windowEndMs += 12h`). */
    fun expandLater() {
        val state = _uiState.value.contextState ?: return
        val newEndMs = state.windowEndMs + ContextWindowState.DEFAULT_HALF_WINDOW_MS
        observeWindow(
            anchorEntryId = state.anchorEntryId,
            anchorCreatedAtMs = state.anchorCreatedAtMs,
            startMs = state.windowStartMs,
            endMs = newEndMs,
        )
    }

    /** Resets the observed window back to the default `anchor ± 12h` span. */
    fun resetWindow() {
        val state = _uiState.value.contextState ?: return
        val anchorMs = anchorCreatedAtMs ?: state.anchorCreatedAtMs
        observeWindow(
            anchorEntryId = state.anchorEntryId,
            anchorCreatedAtMs = anchorMs,
            startMs = anchorMs - ContextWindowState.DEFAULT_HALF_WINDOW_MS,
            endMs = anchorMs + ContextWindowState.DEFAULT_HALF_WINDOW_MS,
        )
    }

    /** Promotes a hidden auto-tag on an entry inside the context window to a visible tag. */
    fun promoteAutoTag(entryId: String, tagId: String) {
        viewModelScope.launch {
            try {
                tagRepository.promoteAutoTag(entryId = entryId, tagId = tagId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(errorMessage = error.message ?: "Unable to promote auto-tag.")
                }
            }
        }
    }

    /** Rejects a hidden auto-tag on an entry inside the context window. */
    fun rejectAutoTag(entryId: String, tagId: String) {
        viewModelScope.launch {
            try {
                tagRepository.rejectAutoTag(entryId = entryId, tagId = tagId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(errorMessage = error.message ?: "Unable to reject auto-tag.")
                }
            }
        }
    }

    /** Toggles visibility of hidden auto-tags inline within the context window. */
    fun toggleShowHiddenAutoTags() {
        hasLocalHiddenTagOverride = true
        _uiState.update { it.copy(showHiddenAutoTags = !it.showHiddenAutoTags) }
    }

    /** Returns the anchor entry's local date (`YYYY-MM-DD`) for chat jump navigation, if known. */
    fun resolveAnchorLocalDate(): String? =
        _uiState.value.anchorEntry?.entry?.localDate ?: anchorLocalDate

    /** Formats a human-readable date and time label anchored in the entry's write-time zone. */
    fun formatEntryDateTime(entry: Entry): String {
        val meta = dateWeekCalculator.dividerMeta(
            epochMs = entry.createdAtMs,
            zoneId = entry.zoneId,
            weekStart = currentWeekStart,
        )
        return "${meta.dayLabel} \u00B7 ${meta.timeLabel}"
    }

    private fun observeWindow(
        anchorEntryId: String,
        anchorCreatedAtMs: Long,
        startMs: Long,
        endMs: Long,
    ) {
        windowObservationJob?.cancel()
        _uiState.update { current ->
            current.copy(
                contextState = current.contextState?.copy(
                    windowStartMs = startMs,
                    windowEndMs = endMs,
                ) ?: ContextWindowState(
                    anchorEntryId = anchorEntryId,
                    anchorCreatedAtMs = anchorCreatedAtMs,
                    windowStartMs = startMs,
                    windowEndMs = endMs,
                    entries = emptyList(),
                ),
                errorMessage = null,
            )
        }

        windowObservationJob = viewModelScope.launch {
            entryRepository.observeContextWindow(startMs = startMs, endMs = endMs)
                .catch { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = error.message ?: "Unable to observe context window.",
                        )
                    }
                }
                .collect { entries ->
                    val chronological = entries.sortedBy { it.entry.createdAtMs }
                    _uiState.update { state ->
                        state.copy(
                            isLoading = false,
                            notFound = false,
                            errorMessage = null,
                            contextState = ContextWindowState(
                                anchorEntryId = anchorEntryId,
                                anchorCreatedAtMs = anchorCreatedAtMs,
                                windowStartMs = startMs,
                                windowEndMs = endMs,
                                entries = chronological,
                            ),
                        )
                    }
                }
        }
    }
}
