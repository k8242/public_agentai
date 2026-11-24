// Copyright (c) 2026 k8242
// SPDX-License-Identifier: MIT

package io.github.k8242.threadlog.feature.views.calendar

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.github.k8242.threadlog.core.model.UserSettings
import io.github.k8242.threadlog.core.model.WeekStart
import io.github.k8242.threadlog.core.parser.DefaultDateWeekCalculator
import io.github.k8242.threadlog.core.parser.DefaultLeafDisambiguator
import io.github.k8242.threadlog.feature.views.CalendarCountQuery
import io.github.k8242.threadlog.feature.views.DateEntriesQuery
import io.github.k8242.threadlog.feature.views.FakeEntryRepository
import io.github.k8242.threadlog.feature.views.FakeSettingsRepository
import io.github.k8242.threadlog.feature.views.FakeTagRepository
import io.github.k8242.threadlog.feature.views.MainDispatcherRule
import io.github.k8242.threadlog.feature.views.ViewsTestData
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CalendarViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val fixedToday = LocalDate.of(2026, 11, 19)
    private val dateWeekCalculator = DefaultDateWeekCalculator()
    private val leafDisambiguator = DefaultLeafDisambiguator()

    private fun createViewModel(
        entryRepo: FakeEntryRepository,
        tagRepo: FakeTagRepository = FakeTagRepository(),
        settingsRepo: FakeSettingsRepository = FakeSettingsRepository(),
    ): CalendarViewModel = CalendarViewModel.createForTest(
        entryRepository = entryRepo,
        tagRepository = tagRepo,
        settingsRepository = settingsRepo,
        dateWeekCalculator = dateWeekCalculator,
        leafDisambiguator = leafDisambiguator,
        todayProvider = { fixedToday },
    )

    @Test
    fun `month navigation updates displayedMonth, monthLabel, and jumpToToday resets month and selectedDate`() = runTest {
        val entryRepo = FakeEntryRepository()
        val viewModel = createViewModel(entryRepo = entryRepo)

        viewModel.uiState.test {
            advanceUntilIdle()
            val initial = expectMostRecentItem()
            assertThat(initial.displayedMonth).isEqualTo(YearMonth.of(2026, 11))
            assertThat(initial.monthLabel).isEqualTo("November 2026")
            assertThat(initial.selectedDate).isEqualTo("2026-11-19")
            assertThat(initial.selectedDateLabel).isEqualTo("Thu, Nov 19")
            assertThat(initial.isLoadingEntries).isFalse()

            viewModel.previousMonth()
            advanceUntilIdle()
            val oct = expectMostRecentItem()
            assertThat(oct.displayedMonth).isEqualTo(YearMonth.of(2026, 10))
            assertThat(oct.monthLabel).isEqualTo("October 2026")

            viewModel.nextMonth()
            viewModel.nextMonth()
            advanceUntilIdle()
            val dec = expectMostRecentItem()
            assertThat(dec.displayedMonth).isEqualTo(YearMonth.of(2026, 12))
            assertThat(dec.monthLabel).isEqualTo("December 2026")

            viewModel.selectDate("2026-12-05")
            advanceUntilIdle()
            val dec5 = expectMostRecentItem()
            assertThat(dec5.selectedDate).isEqualTo("2026-12-05")
            assertThat(dec5.selectedDateLabel).isEqualTo("Sat, Dec 5")

            viewModel.jumpToToday()
            advanceUntilIdle()
            val reset = expectMostRecentItem()
            assertThat(reset.displayedMonth).isEqualTo(YearMonth.of(2026, 11))
            assertThat(reset.selectedDate).isEqualTo("2026-11-19")
            assertThat(reset.cells.first { it.localDateKey == "2026-11-19" }.isSelected).isTrue()

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `selectDate switches observed entries for selected day`() = runTest {
        val entryRepo = FakeEntryRepository()
        val nov19Entry = ViewsTestData.entryDetails(
            id = "e-19",
            localDate = "2026-11-19",
            hour = 8,
            minute = 15,
            bodyPlain = "Morning standup",
        )
        val nov10EntryA = ViewsTestData.entryDetails(
            id = "e-10a",
            localDate = "2026-11-10",
            hour = 10,
            minute = 0,
            bodyPlain = "Design review",
        )
        val nov10EntryB = ViewsTestData.entryDetails(
            id = "e-10b",
            localDate = "2026-11-10",
            hour = 14,
            minute = 30,
            bodyPlain = "Ship build",
        )
        entryRepo.setEntries(listOf(nov19Entry, nov10EntryA, nov10EntryB))

        val viewModel = createViewModel(entryRepo = entryRepo)

        viewModel.uiState.test {
            advanceUntilIdle()
            val initial = expectMostRecentItem()
            assertThat(initial.entriesForSelectedDate.map { it.details.entry.id })
                .containsExactly("e-19")
            assertThat(initial.entriesForSelectedDate.single().timeLabel).isEqualTo("8:15 AM")
            assertThat(initial.cells.first { it.localDateKey == "2026-11-10" }.entryCount)
                .isEqualTo(2)

            viewModel.selectDate("2026-11-10")
            advanceUntilIdle()
            val nov10State = expectMostRecentItem()
            assertThat(nov10State.selectedDate).isEqualTo("2026-11-10")
            assertThat(nov10State.selectedDateLabel).isEqualTo("Tue, Nov 10")
            assertThat(nov10State.entriesForSelectedDate.map { it.details.entry.id })
                .containsExactly("e-10a", "e-10b")
                .inOrder()
            assertThat(nov10State.entriesForSelectedDate[1].timeLabel).isEqualTo("2:30 PM")

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `selectTagFilter passes tag subtree prefix to both day counts and day entries`() = runTest {
        val peopleTag = ViewsTestData.tag(id = "t-people", fullPath = "people")
        val lexTag = ViewsTestData.tag(id = "t-lex", fullPath = "people/friends/Lex")
        val workTag = ViewsTestData.tag(id = "t-work", fullPath = "projects/journal-app")

        val entryWithLex = ViewsTestData.entryDetails(
            id = "e-lex",
            localDate = "2026-11-19",
            hour = 9,
            bodyPlain = "Coffee with Lex",
            tags = listOf(lexTag),
        )
        val entryWithProject = ViewsTestData.entryDetails(
            id = "e-proj",
            localDate = "2026-11-19",
            hour = 11,
            bodyPlain = "Working on calendar UI",
            tags = listOf(workTag),
        )
        val entryOtherDayProject = ViewsTestData.entryDetails(
            id = "e-proj-2",
            localDate = "2026-11-20",
            hour = 15,
            bodyPlain = "Unit tests",
            tags = listOf(workTag),
        )

        val entryRepo = FakeEntryRepository().apply {
            setEntries(listOf(entryWithLex, entryWithProject, entryOtherDayProject))
        }
        val tagRepo = FakeTagRepository(listOf(peopleTag, lexTag, workTag))
        val viewModel = createViewModel(entryRepo = entryRepo, tagRepo = tagRepo)

        viewModel.uiState.test {
            advanceUntilIdle()
            val unfiltered = expectMostRecentItem()
            assertThat(unfiltered.selectedTagFilterPath).isNull()
            assertThat(unfiltered.entriesForSelectedDate.map { it.details.entry.id })
                .containsExactly("e-lex", "e-proj")
            assertThat(unfiltered.cells.first { it.localDateKey == "2026-11-19" }.entryCount)
                .isEqualTo(2)
            assertThat(unfiltered.cells.first { it.localDateKey == "2026-11-20" }.entryCount)
                .isEqualTo(1)

            // Filter by parent subtree "people" -> matches "people/friends/Lex"
            viewModel.selectTagFilter("people")
            advanceUntilIdle()
            val peopleFiltered = expectMostRecentItem()
            assertThat(peopleFiltered.selectedTagFilterPath).isEqualTo("people")
            assertThat(peopleFiltered.entriesForSelectedDate.map { it.details.entry.id })
                .containsExactly("e-lex")
            assertThat(peopleFiltered.cells.first { it.localDateKey == "2026-11-19" }.entryCount)
                .isEqualTo(1)
            assertThat(peopleFiltered.cells.first { it.localDateKey == "2026-11-20" }.entryCount)
                .isEqualTo(0)
            assertThat(entryRepo.calendarCountQueries)
                .contains(CalendarCountQuery(yearMonth = "2026-11", filterTagPathPrefix = "people"))
            assertThat(entryRepo.dateEntryQueries)
                .contains(DateEntriesQuery(localDate = "2026-11-19", filterTagPathPrefix = "people"))

            // Clear filter
            viewModel.selectTagFilter(null)
            advanceUntilIdle()
            val cleared = expectMostRecentItem()
            assertThat(cleared.selectedTagFilterPath).isNull()
            assertThat(cleared.entriesForSelectedDate).hasSize(2)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `reacts live to WeekStart changes in weekdayHeaders and grid alignment`() = runTest {
        val entryRepo = FakeEntryRepository()
        val settingsRepo = FakeSettingsRepository(UserSettings(weekStart = WeekStart.MONDAY))
        val viewModel = createViewModel(entryRepo = entryRepo, settingsRepo = settingsRepo)

        viewModel.uiState.test {
            advanceUntilIdle()
            val mondayState = expectMostRecentItem()
            assertThat(mondayState.weekStart).isEqualTo(WeekStart.MONDAY)
            assertThat(mondayState.weekdayHeaders)
                .containsExactly("M", "T", "W", "T", "F", "S", "S")
                .inOrder()
            // Nov 1, 2026 is Sunday -> index 6 under Monday start
            assertThat(mondayState.cells[6].dayOfMonth).isEqualTo(1)

            settingsRepo.setWeekStart(WeekStart.SUNDAY)
            advanceUntilIdle()
            val sundayState = expectMostRecentItem()
            assertThat(sundayState.weekStart).isEqualTo(WeekStart.SUNDAY)
            assertThat(sundayState.weekdayHeaders)
                .containsExactly("S", "M", "T", "W", "T", "F", "S")
                .inOrder()
            // Nov 1, 2026 is Sunday -> index 0 under Sunday start
            assertThat(sundayState.cells[0].dayOfMonth).isEqualTo(1)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `exposes openTodoCount and completedTodoCount on calendar cells and task metadata on day entries`() = runTest {
        val openHighTodo = ViewsTestData.entryDetails(
            id = "e-todo-high",
            localDate = "2026-11-19",
            hour = 9,
            bodyPlain = "Prepare release notes",
            taskStatus = io.github.k8242.threadlog.core.model.TaskStatus.TODO,
            taskPriority = io.github.k8242.threadlog.core.model.TaskPriority.HIGH,
            isHighlighted = true,
        )
        val doneMedTodo = ViewsTestData.entryDetails(
            id = "e-done-med",
            localDate = "2026-11-19",
            hour = 11,
            bodyPlain = "Fix migration test",
            taskStatus = io.github.k8242.threadlog.core.model.TaskStatus.DONE,
            taskPriority = io.github.k8242.threadlog.core.model.TaskPriority.MEDIUM,
        )
        val regularNote = ViewsTestData.entryDetails(
            id = "e-note",
            localDate = "2026-11-20",
            hour = 14,
            bodyPlain = "Coffee walk",
        )

        val entryRepo = FakeEntryRepository().apply {
            setEntries(listOf(openHighTodo, doneMedTodo, regularNote))
        }
        val viewModel = createViewModel(entryRepo = entryRepo)

        viewModel.uiState.test {
            advanceUntilIdle()
            val state = expectMostRecentItem()
            val nov19Cell = state.cells.first { it.localDateKey == "2026-11-19" }
            assertThat(nov19Cell.entryCount).isEqualTo(2)
            assertThat(nov19Cell.openTodoCount).isEqualTo(1)
            assertThat(nov19Cell.completedTodoCount).isEqualTo(1)

            val nov20Cell = state.cells.first { it.localDateKey == "2026-11-20" }
            assertThat(nov20Cell.entryCount).isEqualTo(1)
            assertThat(nov20Cell.openTodoCount).isEqualTo(0)
            assertThat(nov20Cell.completedTodoCount).isEqualTo(0)

            val firstEntry = state.entriesForSelectedDate.first { it.details.entry.id == "e-todo-high" }.details.entry
            assertThat(firstEntry.taskStatus).isEqualTo(io.github.k8242.threadlog.core.model.TaskStatus.TODO)
            assertThat(firstEntry.taskPriority).isEqualTo(io.github.k8242.threadlog.core.model.TaskPriority.HIGH)
            assertThat(firstEntry.isHighlighted).isTrue()

            val secondEntry = state.entriesForSelectedDate.first { it.details.entry.id == "e-done-med" }.details.entry
            assertThat(secondEntry.isTaskDone).isTrue()
            assertThat(secondEntry.taskPriority).isEqualTo(io.github.k8242.threadlog.core.model.TaskPriority.MEDIUM)

            cancelAndIgnoreRemainingEvents()
        }
    }
}
