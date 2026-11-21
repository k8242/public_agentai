// Copyright (c) 2026 k8242
// SPDX-License-Identifier: MIT

package io.github.k8242.threadlog.feature.views.weekly

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.k8242.threadlog.core.model.CompactEntrySummary
import io.github.k8242.threadlog.core.model.EntryWithDetails
import io.github.k8242.threadlog.core.model.WeekStart
import io.github.k8242.threadlog.core.model.contract.DateWeekCalculator
import io.github.k8242.threadlog.core.model.contract.EntryRepository
import io.github.k8242.threadlog.core.model.contract.LeafDisambiguator
import io.github.k8242.threadlog.core.model.contract.SettingsRepository
import io.github.k8242.threadlog.core.model.contract.TagRepository
import io.github.k8242.threadlog.feature.views.common.formatDayLabel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel for the Compact Weekly View.
 *
 * Groups [CompactEntrySummary] rows dynamically by the user's live [WeekStart] setting
 * (newest weeks first), computes minimal disambiguated tag display labels, and lazily
 * loads and caches full [EntryWithDetails] on inline row expansion.
 */
@HiltViewModel
class CompactWeeklyViewModel @Inject constructor(
    private val entryRepository: EntryRepository,
    private val tagRepository: TagRepository,
    private val settingsRepository: SettingsRepository,
    private val dateWeekCalculator: DateWeekCalculator,
    private val leafDisambiguator: LeafDisambiguator,
) : ViewModel() {

    private val expandedEntryIds = MutableStateFlow<Set<String>>(emptySet())
    private val expandedDetailsById = MutableStateFlow<Map<String, EntryWithDetails>>(emptyMap())
    private val inFlightDetailIds = MutableStateFlow<Set<String>>(emptySet())

    val uiState: StateFlow<CompactWeeklyUiState> = combine(
        entryRepository.observeCompactSummaries(),
        tagRepository.observeAllTags(),
        settingsRepository.settings,
        expandedEntryIds,
        expandedDetailsById,
    ) { summaries, allTags, settings, expandedIds, detailsById ->
        val weekStart = settings.weekStart
        val tagDisplayLabels = leafDisambiguator.computeDisplayLabels(allTags)
        val weeks = buildWeekGroups(
            summaries = summaries,
            weekStart = weekStart,
            expandedIds = expandedIds,
            detailsById = detailsById,
        )
        CompactWeeklyUiState(
            isLoading = false,
            weekStart = weekStart,
            weeks = weeks,
            tagDisplayLabels = tagDisplayLabels,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = CompactWeeklyUiState(isLoading = true),
    )

    /**
     * Toggles inline expansion for [entryId]. When expanding for the first time,
     * asynchronously fetches and caches [EntryWithDetails] via [EntryRepository.getEntry].
     */
    fun toggleExpand(entryId: String) {
        var expanding = false
        expandedEntryIds.update { current ->
            if (entryId in current) {
                current - entryId
            } else {
                expanding = true
                current + entryId
            }
        }
        if (expanding && entryId !in expandedDetailsById.value) {
            fetchDetailsIfNeeded(entryId)
        }
    }

    /**
     * Collapses all currently expanded rows while preserving the loaded details cache.
     */
    fun collapseAll() {
        expandedEntryIds.value = emptySet()
    }

    private fun fetchDetailsIfNeeded(entryId: String) {
        var shouldFetch = false
        inFlightDetailIds.update { current ->
            if (entryId in current) {
                current
            } else {
                shouldFetch = true
                current + entryId
            }
        }
        if (!shouldFetch) return

        viewModelScope.launch {
            try {
                val details = entryRepository.getEntry(entryId)
                if (details != null) {
                    expandedDetailsById.update { it + (entryId to details) }
                }
            } finally {
                inFlightDetailIds.update { it - entryId }
            }
        }
    }

    private fun buildWeekGroups(
        summaries: List<CompactEntrySummary>,
        weekStart: WeekStart,
        expandedIds: Set<String>,
        detailsById: Map<String, EntryWithDetails>,
    ): List<CompactWeekGroup> {
        if (summaries.isEmpty()) return emptyList()

        data class AnnotatedSummary(
            val summary: CompactEntrySummary,
            val weekKey: String,
            val weekLabel: String,
            val timeLabel: String,
        )

        val annotated = summaries.map { summary ->
            val weekKey = dateWeekCalculator.weekKeyForLocalDate(summary.localDate, weekStart)
            val weekLabel = dateWeekCalculator.weekLabelForLocalDate(summary.localDate, weekStart)
            val timeLabel = dateWeekCalculator.dividerMeta(
                epochMs = summary.createdAtMs,
                zoneId = summary.zoneId,
                weekStart = weekStart,
            ).timeLabel
            AnnotatedSummary(
                summary = summary,
                weekKey = weekKey,
                weekLabel = weekLabel,
                timeLabel = timeLabel,
            )
        }

        return annotated
            .groupBy { it.weekKey }
            .entries
            .sortedByDescending { (weekKey, _) -> weekKey }
            .map { (weekKey, weekItems) ->
                val weekLabel = weekItems.first().weekLabel
                val days = weekItems
                    .groupBy { it.summary.localDate }
                    .entries
                    .sortedByDescending { (dayKey, _) -> dayKey }
                    .map { (dayKey, dayItems) ->
                        val sortedRows = dayItems
                            .sortedWith(
                                compareByDescending<AnnotatedSummary> { it.summary.createdAtMs }
                                    .thenByDescending { it.summary.id },
                            )
                            .map { item ->
                                val id = item.summary.id
                                CompactEntryRowItem(
                                    summary = item.summary,
                                    timeLabel = item.timeLabel,
                                    isExpanded = id in expandedIds,
                                    expandedDetails = detailsById[id],
                                )
                            }
                        CompactDayGroup(
                            dayKey = dayKey,
                            dayLabel = formatDayLabel(dayKey),
                            items = sortedRows,
                        )
                    }

                CompactWeekGroup(
                    weekKey = weekKey,
                    weekLabel = weekLabel,
                    entryCount = weekItems.size,
                    days = days,
                )
            }
    }
}
