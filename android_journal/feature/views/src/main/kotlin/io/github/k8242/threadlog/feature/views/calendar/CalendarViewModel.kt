// Copyright (c) 2026 k8242
// SPDX-License-Identifier: MIT

package io.github.k8242.threadlog.feature.views.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.k8242.threadlog.core.model.EntryWithDetails
import io.github.k8242.threadlog.core.model.WeekStart
import io.github.k8242.threadlog.core.model.contract.DateWeekCalculator
import io.github.k8242.threadlog.core.model.contract.EntryRepository
import io.github.k8242.threadlog.core.model.contract.LeafDisambiguator
import io.github.k8242.threadlog.core.model.contract.SettingsRepository
import io.github.k8242.threadlog.core.model.contract.TagRepository
import io.github.k8242.threadlog.feature.views.common.formatDayLabel
import io.github.k8242.threadlog.feature.views.common.formatMonthLabel
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/**
 * ViewModel for the Monthly Calendar View.
 *
 * Reactively observes per-day entry counts for the displayed month and hydrated entries
 * for the selected date, applying optional hierarchical tag-subtree prefix filtering and
 * live [WeekStart] updates.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CalendarViewModel private constructor(
    private val entryRepository: EntryRepository,
    private val tagRepository: TagRepository,
    private val settingsRepository: SettingsRepository,
    private val dateWeekCalculator: DateWeekCalculator,
    private val leafDisambiguator: LeafDisambiguator,
    private val todayProvider: () -> LocalDate,
) : ViewModel() {

    @Inject
    constructor(
        entryRepository: EntryRepository,
        tagRepository: TagRepository,
        settingsRepository: SettingsRepository,
        dateWeekCalculator: DateWeekCalculator,
        leafDisambiguator: LeafDisambiguator,
    ) : this(
        entryRepository = entryRepository,
        tagRepository = tagRepository,
        settingsRepository = settingsRepository,
        dateWeekCalculator = dateWeekCalculator,
        leafDisambiguator = leafDisambiguator,
        todayProvider = { LocalDate.now() },
    )

    private data class CalendarSelection(
        val displayedMonth: YearMonth,
        val selectedDate: String,
        val selectedTagFilterPath: String?,
    )

    private data class DayEntriesState(
        val entries: List<EntryWithDetails> = emptyList(),
        val isLoading: Boolean = true,
    )

    private val initialToday: LocalDate = todayProvider()
    private val displayedMonthFlow = MutableStateFlow(YearMonth.from(initialToday))
    private val selectedDateFlow = MutableStateFlow(
        initialToday.format(DateTimeFormatter.ISO_LOCAL_DATE),
    )
    private val selectedTagFilterPathFlow = MutableStateFlow<String?>(null)

    private val selectionFlow: Flow<CalendarSelection> = combine(
        displayedMonthFlow,
        selectedDateFlow,
        selectedTagFilterPathFlow,
        ::CalendarSelection,
    )

    private val dayCountsFlow: Flow<Map<String, Int>> = combine(
        displayedMonthFlow,
        selectedTagFilterPathFlow,
    ) { month, filterPath ->
        month.format(YEAR_MONTH_FORMATTER) to filterPath
    }.flatMapLatest { (yearMonthKey, filterPath) ->
        entryRepository
            .observeCalendarDayCounts(
                yearMonth = yearMonthKey,
                filterTagPathPrefix = filterPath,
            )
            .map { counts ->
                counts.associate { it.localDate to it.entryCount }
            }
    }

    private val selectedDayEntriesFlow: Flow<DayEntriesState> = combine(
        selectedDateFlow,
        selectedTagFilterPathFlow,
    ) { localDate, filterPath ->
        localDate to filterPath
    }.flatMapLatest { (localDate, filterPath) ->
        entryRepository
            .observeEntriesForDate(
                localDate = localDate,
                filterTagPathPrefix = filterPath,
            )
            .map { entries ->
                DayEntriesState(entries = entries, isLoading = false)
            }
            .onStart {
                emit(DayEntriesState(entries = emptyList(), isLoading = true))
            }
    }

    val uiState: StateFlow<CalendarUiState> = combine(
        selectionFlow,
        settingsRepository.settings,
        tagRepository.observeAllTags(),
        dayCountsFlow,
        selectedDayEntriesFlow,
    ) { selection, settings, allTags, countsByDate, dayEntriesState ->
        val today = todayProvider()
        val weekStart = settings.weekStart
        val tagDisplayLabels = leafDisambiguator.computeDisplayLabels(allTags)
        val cells = CalendarGridBuilder.buildMonthGrid(
            yearMonth = selection.displayedMonth,
            weekStart = weekStart,
            countsByDate = countsByDate,
            today = today,
            selectedDate = selection.selectedDate,
        )
        val entryItems = dayEntriesState.entries.map { details ->
            val timeLabel = dateWeekCalculator.dividerMeta(
                epochMs = details.entry.createdAtMs,
                zoneId = details.entry.zoneId,
                weekStart = weekStart,
            ).timeLabel
            CalendarDayEntryItem(
                details = details,
                timeLabel = timeLabel,
            )
        }

        CalendarUiState(
            displayedMonth = selection.displayedMonth,
            monthLabel = formatMonthLabel(selection.displayedMonth),
            weekStart = weekStart,
            weekdayHeaders = CalendarGridBuilder.weekdayHeaders(weekStart),
            cells = cells,
            selectedDate = selection.selectedDate,
            selectedDateLabel = formatDayLabel(selection.selectedDate),
            allTags = allTags,
            tagDisplayLabels = tagDisplayLabels,
            selectedTagFilterPath = selection.selectedTagFilterPath,
            entriesForSelectedDate = entryItems,
            isLoadingEntries = dayEntriesState.isLoading,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = buildInitialState(initialToday),
    )

    fun previousMonth() {
        displayedMonthFlow.update { it.minusMonths(1) }
    }

    fun nextMonth() {
        displayedMonthFlow.update { it.plusMonths(1) }
    }

    fun jumpToToday() {
        val today = todayProvider()
        displayedMonthFlow.value = YearMonth.from(today)
        selectedDateFlow.value = today.format(DateTimeFormatter.ISO_LOCAL_DATE)
    }

    fun selectDate(localDate: String) {
        selectedDateFlow.value = localDate
        runCatching { LocalDate.parse(localDate, DateTimeFormatter.ISO_LOCAL_DATE) }
            .getOrNull()
            ?.let { parsed ->
                val targetMonth = YearMonth.from(parsed)
                if (displayedMonthFlow.value != targetMonth) {
                    displayedMonthFlow.value = targetMonth
                }
            }
    }

    fun selectTagFilter(fullPath: String?) {
        val normalized = fullPath?.trim()?.trim('/')?.takeIf { it.isNotEmpty() }
        selectedTagFilterPathFlow.value = normalized
    }

    private fun buildInitialState(today: LocalDate): CalendarUiState {
        val month = YearMonth.from(today)
        val selectedDate = today.format(DateTimeFormatter.ISO_LOCAL_DATE)
        return CalendarUiState(
            displayedMonth = month,
            monthLabel = formatMonthLabel(month),
            weekStart = WeekStart.MONDAY,
            weekdayHeaders = CalendarGridBuilder.weekdayHeaders(WeekStart.MONDAY),
            cells = CalendarGridBuilder.buildMonthGrid(
                yearMonth = month,
                weekStart = WeekStart.MONDAY,
                countsByDate = emptyMap(),
                today = today,
                selectedDate = selectedDate,
            ),
            selectedDate = selectedDate,
            selectedDateLabel = formatDayLabel(selectedDate),
            isLoadingEntries = true,
        )
    }

    internal companion object {
        private val YEAR_MONTH_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM", Locale.US)

        fun createForTest(
            entryRepository: EntryRepository,
            tagRepository: TagRepository,
            settingsRepository: SettingsRepository,
            dateWeekCalculator: DateWeekCalculator,
            leafDisambiguator: LeafDisambiguator,
            todayProvider: () -> LocalDate = { LocalDate.now() },
        ): CalendarViewModel = CalendarViewModel(
            entryRepository = entryRepository,
            tagRepository = tagRepository,
            settingsRepository = settingsRepository,
            dateWeekCalculator = dateWeekCalculator,
            leafDisambiguator = leafDisambiguator,
            todayProvider = todayProvider,
        )
    }
}
