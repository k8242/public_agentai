// Copyright (c) 2026 k8242
// SPDX-License-Identifier: MIT

package io.github.k8242.threadlog.feature.views.weekly

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.github.k8242.threadlog.core.model.UserSettings
import io.github.k8242.threadlog.core.model.WeekStart
import io.github.k8242.threadlog.core.parser.DefaultDateWeekCalculator
import io.github.k8242.threadlog.core.parser.DefaultLeafDisambiguator
import io.github.k8242.threadlog.feature.views.FakeEntryRepository
import io.github.k8242.threadlog.feature.views.FakeSettingsRepository
import io.github.k8242.threadlog.feature.views.FakeTagRepository
import io.github.k8242.threadlog.feature.views.MainDispatcherRule
import io.github.k8242.threadlog.feature.views.ViewsTestData
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CompactWeeklyViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val dateWeekCalculator = DefaultDateWeekCalculator()
    private val leafDisambiguator = DefaultLeafDisambiguator()

    private fun createViewModel(
        entryRepo: FakeEntryRepository,
        tagRepo: FakeTagRepository = FakeTagRepository(),
        settingsRepo: FakeSettingsRepository = FakeSettingsRepository(),
    ): CompactWeeklyViewModel = CompactWeeklyViewModel(
        entryRepository = entryRepo,
        tagRepository = tagRepo,
        settingsRepository = settingsRepo,
        dateWeekCalculator = dateWeekCalculator,
        leafDisambiguator = leafDisambiguator,
    )

    @Test
    fun `groups summaries dynamically under Monday vs Sunday weekStart and orders newest first`() = runTest {
        val entryRepo = FakeEntryRepository()
        val settingsRepo = FakeSettingsRepository(UserSettings(weekStart = WeekStart.MONDAY))

        // 2026-11-15 is Sunday (ISO Monday week 2026-W46, Sunday-start week 2026-S47)
        // 2026-11-16 is Monday (ISO Monday week 2026-W47, Sunday-start week 2026-S47)
        // 2026-11-19 is Thursday (ISO Monday week 2026-W47, Sunday-start week 2026-S47)
        val sunNov15 = ViewsTestData.compactSummary(id = "e-sun", localDate = "2026-11-15", hour = 18)
        val monNov16 = ViewsTestData.compactSummary(id = "e-mon", localDate = "2026-11-16", hour = 9)
        val thuNov19Morning = ViewsTestData.compactSummary(id = "e-thu-1", localDate = "2026-11-19", hour = 8, minute = 15)
        val thuNov19Evening = ViewsTestData.compactSummary(id = "e-thu-2", localDate = "2026-11-19", hour = 20, minute = 30)

        entryRepo.compactSummariesFlow.value = listOf(
            thuNov19Evening,
            thuNov19Morning,
            monNov16,
            sunNov15,
        )

        val viewModel = createViewModel(entryRepo = entryRepo, settingsRepo = settingsRepo)

        viewModel.uiState.test {
            // Skip initial loading state if emitted first
            var state = awaitItem()
            if (state.isLoading) {
                state = awaitItem()
            }

            assertThat(state.isLoading).isFalse()
            assertThat(state.weekStart).isEqualTo(WeekStart.MONDAY)
            assertThat(state.weeks.map { it.weekKey })
                .containsExactly("2026-W47", "2026-W46")
                .inOrder()

            val week47 = state.weeks[0]
            assertThat(week47.entryCount).isEqualTo(3)
            assertThat(week47.days.map { it.dayKey })
                .containsExactly("2026-11-19", "2026-11-16")
                .inOrder()
            assertThat(week47.days[0].dayLabel).isEqualTo("Thu, Nov 19")
            assertThat(week47.days[0].items.map { it.summary.id })
                .containsExactly("e-thu-2", "e-thu-1")
                .inOrder()
            assertThat(week47.days[0].items[1].timeLabel).isEqualTo("8:15 AM")

            val week46 = state.weeks[1]
            assertThat(week46.entryCount).isEqualTo(1)
            assertThat(week46.days.single().dayKey).isEqualTo("2026-11-15")

            // Switch live setting to WeekStart.SUNDAY -> Sun Nov 15 joins Mon Nov 16 & Thu Nov 19 in 2026-S46
            settingsRepo.setWeekStart(WeekStart.SUNDAY)
            val sundayState = awaitItem()

            assertThat(sundayState.weekStart).isEqualTo(WeekStart.SUNDAY)
            assertThat(sundayState.weeks).hasSize(1)
            assertThat(sundayState.weeks[0].weekKey).isEqualTo("2026-S46")
            assertThat(sundayState.weeks[0].weekLabel).isEqualTo("Week 46 \u00B7 Nov 15\u201321")
            assertThat(sundayState.weeks[0].entryCount).isEqualTo(4)
            assertThat(sundayState.weeks[0].days.map { it.dayKey })
                .containsExactly("2026-11-19", "2026-11-16", "2026-11-15")
                .inOrder()

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `toggleExpand loads full EntryWithDetails once, caches it, and collapseAll collapses all rows`() = runTest {
        val entryRepo = FakeEntryRepository()
        val summary1 = ViewsTestData.compactSummary(id = "e-1", localDate = "2026-11-19", hour = 9)
        val summary2 = ViewsTestData.compactSummary(id = "e-2", localDate = "2026-11-19", hour = 10)
        val details1 = ViewsTestData.entryDetails(id = "e-1", localDate = "2026-11-19", hour = 9, bodyPlain = "Full body for e-1")
        val details2 = ViewsTestData.entryDetails(id = "e-2", localDate = "2026-11-19", hour = 10, bodyPlain = "Full body for e-2")

        entryRepo.compactSummariesFlow.value = listOf(summary2, summary1)
        entryRepo.setEntries(listOf(details1, details2))

        val viewModel = createViewModel(entryRepo = entryRepo)

        viewModel.uiState.test {
            var state = awaitItem()
            if (state.isLoading) state = awaitItem()
            assertThat(state.hasExpandedItems).isFalse()

            // Expand e-1 -> fetches details via getEntry
            viewModel.toggleExpand("e-1")
            advanceUntilIdle()

            // Drain until expandedDetails is hydrated
            state = expectMostRecentItem()
            val row1Expanded = state.weeks.single().days.single().items.first { it.summary.id == "e-1" }
            assertThat(row1Expanded.isExpanded).isTrue()
            assertThat(row1Expanded.expandedDetails?.entry?.bodyPlain).isEqualTo("Full body for e-1")
            assertThat(entryRepo.getEntryCalls).containsExactly("e-1")
            assertThat(state.hasExpandedItems).isTrue()

            // Collapse and re-expand e-1 -> served from cache without calling getEntry again
            viewModel.toggleExpand("e-1")
            advanceUntilIdle()
            state = expectMostRecentItem()
            assertThat(state.hasExpandedItems).isFalse()

            viewModel.toggleExpand("e-1")
            viewModel.toggleExpand("e-2")
            advanceUntilIdle()
            state = expectMostRecentItem()

            val items = state.weeks.single().days.single().items
            assertThat(items.first { it.summary.id == "e-1" }.isExpanded).isTrue()
            assertThat(items.first { it.summary.id == "e-2" }.isExpanded).isTrue()
            assertThat(items.first { it.summary.id == "e-2" }.expandedDetails?.entry?.bodyPlain)
                .isEqualTo("Full body for e-2")
            assertThat(entryRepo.getEntryCalls).containsExactly("e-1", "e-2")

            // collapseAll collapses every expanded row
            viewModel.collapseAll()
            advanceUntilIdle()
            state = expectMostRecentItem()
            assertThat(state.hasExpandedItems).isFalse()
            assertThat(state.weeks.single().days.single().items.none { it.isExpanded }).isTrue()

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `computes disambiguated tag display labels from active tags`() = runTest {
        val entryRepo = FakeEntryRepository()
        val lex = ViewsTestData.tag(id = "t-lex", fullPath = "people/friends/Lex")
        val alexWork = ViewsTestData.tag(id = "t-alex-work", fullPath = "people/work/Alex")
        val alexFriends = ViewsTestData.tag(id = "t-alex-friends", fullPath = "people/friends/Alex")
        val tagRepo = FakeTagRepository(listOf(lex, alexWork, alexFriends))

        val viewModel = createViewModel(entryRepo = entryRepo, tagRepo = tagRepo)

        viewModel.uiState.test {
            var state = awaitItem()
            if (state.isLoading) state = awaitItem()

            assertThat(state.tagDisplayLabels).containsExactly(
                "t-lex", "Lex",
                "t-alex-work", "Alex (work)",
                "t-alex-friends", "Alex (friends)",
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `preserves live taskStatus, taskPriority, and isHighlighted on compact summaries`() = runTest {
        val entryRepo = FakeEntryRepository()
        val todoSummary = ViewsTestData.compactSummary(
            id = "e-task",
            localDate = "2026-11-19",
            hour = 10,
            preview140 = "Ship release",
            taskStatus = io.github.k8242.threadlog.core.model.TaskStatus.TODO,
            taskPriority = io.github.k8242.threadlog.core.model.TaskPriority.HIGH,
            isHighlighted = true,
        )
        val details = ViewsTestData.entryDetails(
            id = "e-task",
            localDate = "2026-11-19",
            hour = 10,
            bodyPlain = "Ship release",
            taskStatus = io.github.k8242.threadlog.core.model.TaskStatus.TODO,
            taskPriority = io.github.k8242.threadlog.core.model.TaskPriority.HIGH,
            isHighlighted = true,
        )
        entryRepo.compactSummariesFlow.value = listOf(todoSummary)
        entryRepo.setEntries(listOf(details))

        val viewModel = createViewModel(entryRepo = entryRepo)

        viewModel.uiState.test {
            advanceUntilIdle()
            val initial = expectMostRecentItem()
            val row = initial.weeks.single().days.single().items.single()
            assertThat(row.summary.taskStatus).isEqualTo(io.github.k8242.threadlog.core.model.TaskStatus.TODO)
            assertThat(row.summary.taskPriority).isEqualTo(io.github.k8242.threadlog.core.model.TaskPriority.HIGH)
            assertThat(row.summary.isHighlighted).isTrue()

            // Expand row (caches details) then update summary live to DONE + LOW + unhighlighted
            viewModel.toggleExpand("e-task")
            advanceUntilIdle()
            entryRepo.compactSummariesFlow.value = listOf(
                todoSummary.copy(
                    taskStatus = io.github.k8242.threadlog.core.model.TaskStatus.DONE,
                    taskPriority = io.github.k8242.threadlog.core.model.TaskPriority.LOW,
                    isHighlighted = false,
                ),
            )
            advanceUntilIdle()

            val updated = expectMostRecentItem()
            val updatedRow = updated.weeks.single().days.single().items.single()
            assertThat(updatedRow.isExpanded).isTrue()
            assertThat(updatedRow.summary.isTaskDone).isTrue()
            assertThat(updatedRow.summary.taskPriority).isEqualTo(io.github.k8242.threadlog.core.model.TaskPriority.LOW)
            assertThat(updatedRow.summary.isHighlighted).isFalse()

            cancelAndIgnoreRemainingEvents()
        }
    }
}
