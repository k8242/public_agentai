package com.threadlog.feature.search

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.threadlog.core.model.HybridSearchResult
import com.threadlog.core.parser.DefaultDateWeekCalculator
import com.threadlog.core.parser.DefaultLeafDisambiguator
import com.threadlog.feature.search.ui.SearchViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val disambiguator = DefaultLeafDisambiguator()
    private val dateCalculator = DefaultDateWeekCalculator()

    @Test
    fun searchSeparatesTierAAndTierBAndComputesCounts() = runTest {
        val recent1 = sampleSearchHit("r1", 1_795_000_000_000L, "2026-11-18", "Recent <mark>coffee</mark> #1")
        val recent2 = sampleSearchHit("r2", 1_794_900_000_000L, "2026-11-17", "Recent <mark>coffee</mark> #2")
        val older1 = sampleSearchHit("o1", 1_780_000_000_000L, "2026-05-29", "Older <mark>coffee</mark> #1")

        val searchRepo = FakeSearchRepository { query, includeHidden ->
            HybridSearchResult(
                query = query,
                includeHiddenAutoTags = includeHidden,
                cutoffEpochMs = 1_790_000_000_000L,
                tierARecent = listOf(recent1, recent2),
                tierBOlder = listOf(older1),
            )
        }
        val tagRepo = FakeTagRepository()
        val viewModel = SearchViewModel(searchRepo, tagRepo, disambiguator, dateCalculator)

        viewModel.uiState.test {
            assertThat(awaitItem().query).isEmpty()

            viewModel.onQueryChanged("coffee")
            advanceUntilIdle()

            val state = expectMostRecentItem()
            assertThat(state.isSearching).isFalse()
            assertThat(state.tierACount).isEqualTo(2)
            assertThat(state.tierBCount).isEqualTo(1)
            assertThat(state.totalCount).isEqualTo(3)
            assertThat(state.tierAIndex).isEqualTo(0)
            assertThat(state.activeTierAHit).isEqualTo(recent1)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun tierAStepperWrapsForwardBackwardAndSelectClamps() = runTest {
        val recent1 = sampleSearchHit("r1", 3000L, "2026-11-18", "<mark>plan</mark> 1")
        val recent2 = sampleSearchHit("r2", 2000L, "2026-11-17", "<mark>plan</mark> 2")
        val recent3 = sampleSearchHit("r3", 1000L, "2026-11-16", "<mark>plan</mark> 3")

        val searchRepo = FakeSearchRepository { query, includeHidden ->
            HybridSearchResult(
                query = query,
                includeHiddenAutoTags = includeHidden,
                cutoffEpochMs = 500L,
                tierARecent = listOf(recent1, recent2, recent3),
                tierBOlder = emptyList(),
            )
        }
        val viewModel = SearchViewModel(searchRepo, FakeTagRepository(), disambiguator, dateCalculator)

        viewModel.initQuery("plan")
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.tierAIndex).isEqualTo(0)
        assertThat(viewModel.uiState.value.activeTierAHit).isEqualTo(recent1)

        // Wrap backward from 0 -> 2
        viewModel.stepPreviousTierA()
        assertThat(viewModel.uiState.value.tierAIndex).isEqualTo(2)
        assertThat(viewModel.uiState.value.activeTierAHit).isEqualTo(recent3)

        // Wrap forward from 2 -> 0
        viewModel.stepNextTierA()
        assertThat(viewModel.uiState.value.tierAIndex).isEqualTo(0)
        assertThat(viewModel.uiState.value.activeTierAHit).isEqualTo(recent1)

        // Step forward to 1
        viewModel.stepNextTierA()
        assertThat(viewModel.uiState.value.tierAIndex).isEqualTo(1)
        assertThat(viewModel.uiState.value.activeTierAHit).isEqualTo(recent2)

        // Direct selection with out-of-bounds clamping
        viewModel.selectTierAIndex(99)
        assertThat(viewModel.uiState.value.tierAIndex).isEqualTo(2)
        viewModel.selectTierAIndex(-5)
        assertThat(viewModel.uiState.value.tierAIndex).isEqualTo(0)
    }

    @Test
    fun toggleIncludeHiddenAutoTagsRequeriesWithUpdatedFlag() = runTest {
        val hiddenTag = sampleTag("t-auto", "auto/travel")
        val hitWithHidden = sampleSearchHit(
            entryId = "e1",
            createdAtMs = 5000L,
            localDate = "2026-11-18",
            snippetMarked = "Flight booked",
            hiddenMatchedTags = listOf(hiddenTag),
            matchedViaHiddenAutoTag = true,
        )

        val searchRepo = FakeSearchRepository { query, includeHidden ->
            HybridSearchResult(
                query = query,
                includeHiddenAutoTags = includeHidden,
                cutoffEpochMs = 1000L,
                tierARecent = if (includeHidden) listOf(hitWithHidden) else emptyList(),
                tierBOlder = emptyList(),
            )
        }
        val viewModel = SearchViewModel(searchRepo, FakeTagRepository(), disambiguator, dateCalculator)

        viewModel.initQuery("travel")
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.includeHiddenAutoTags).isTrue()
        assertThat(viewModel.uiState.value.tierACount).isEqualTo(1)

        viewModel.toggleIncludeHiddenAutoTags()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.includeHiddenAutoTags).isFalse()
        assertThat(viewModel.uiState.value.tierACount).isEqualTo(0)
        assertThat(searchRepo.recordedCalls).containsExactly(
            RecordedSearchCall(query = "travel", includeHiddenAutoTags = true),
            RecordedSearchCall(query = "travel", includeHiddenAutoTags = false),
        ).inOrder()
    }

    @Test
    fun initQueryRunsOncePerDistinctQueryAndClearQueryResetsState() = runTest {
        val hit = sampleSearchHit("e1", 5000L, "2026-11-18", "<mark>journal</mark>")
        val searchRepo = FakeSearchRepository { query, includeHidden ->
            HybridSearchResult(
                query = query,
                includeHiddenAutoTags = includeHidden,
                cutoffEpochMs = 1000L,
                tierARecent = listOf(hit),
                tierBOlder = emptyList(),
            )
        }
        val viewModel = SearchViewModel(searchRepo, FakeTagRepository(), disambiguator, dateCalculator)

        viewModel.initQuery("journal")
        viewModel.initQuery("journal") // Duplicate init should be ignored
        advanceUntilIdle()

        assertThat(searchRepo.recordedCalls).hasSize(1)
        assertThat(viewModel.uiState.value.query).isEqualTo("journal")
        assertThat(viewModel.uiState.value.totalCount).isEqualTo(1)

        viewModel.clearQuery()
        assertThat(viewModel.uiState.value.query).isEmpty()
        assertThat(viewModel.uiState.value.result).isNull()
        assertThat(viewModel.uiState.value.isSearching).isFalse()
        assertThat(viewModel.uiState.value.tierAIndex).isEqualTo(0)
    }

    @Test
    fun rapidTypingCancelsPreviousDebouncedSearch() = runTest {
        val searchRepo = FakeSearchRepository { query, includeHidden ->
            delay(50L)
            HybridSearchResult(
                query = query,
                includeHiddenAutoTags = includeHidden,
                cutoffEpochMs = 0L,
                tierARecent = emptyList(),
                tierBOlder = emptyList(),
            )
        }
        val viewModel = SearchViewModel(searchRepo, FakeTagRepository(), disambiguator, dateCalculator)

        viewModel.onQueryChanged("k")
        advanceTimeBy(100L)
        viewModel.onQueryChanged("ko")
        advanceTimeBy(100L)
        viewModel.onQueryChanged("kotlin")
        advanceUntilIdle()

        assertThat(searchRepo.recordedCalls).containsExactly(
            RecordedSearchCall(query = "kotlin", includeHiddenAutoTags = true),
        )
    }

    @Test
    fun tagDisplayLabelsReflectLeafDisambiguationAndFormatDateTimeUsesZone() = runTest {
        val alexWork = sampleTag("t1", "people/work/Alex")
        val alexFriends = sampleTag("t2", "people/friends/Alex")
        val lex = sampleTag("t3", "people/friends/Lex")

        val tagRepo = FakeTagRepository(listOf(alexWork, alexFriends, lex))
        val viewModel = SearchViewModel(FakeSearchRepository(), tagRepo, disambiguator, dateCalculator)
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.tagDisplayLabels).containsExactly(
            "t1", "Alex (work)",
            "t2", "Alex (friends)",
            "t3", "Lex",
        )

        // 2026-11-19T16:15:00Z -> 8:15 AM in America/Los_Angeles (PST = UTC-8)
        val hit = sampleSearchHit(
            entryId = "e-tz",
            createdAtMs = 1_795_104_900_000L,
            localDate = "2026-11-19",
            snippetMarked = "Check timezone",
            zoneId = "America/Los_Angeles",
        )
        assertThat(viewModel.formatDateTimeLabel(hit)).isEqualTo("Thu, Nov 19 \u00B7 8:15 AM")
    }
}
