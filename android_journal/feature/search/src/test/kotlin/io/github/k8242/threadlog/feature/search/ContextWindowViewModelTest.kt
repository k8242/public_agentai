// Copyright (c) 2026 k8242
// SPDX-License-Identifier: MIT

package io.github.k8242.threadlog.feature.search

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.github.k8242.threadlog.core.model.ContextWindowState
import io.github.k8242.threadlog.core.model.EntryTag
import io.github.k8242.threadlog.core.model.TagState
import io.github.k8242.threadlog.core.model.UserSettings
import io.github.k8242.threadlog.core.parser.DefaultDateWeekCalculator
import io.github.k8242.threadlog.core.parser.DefaultLeafDisambiguator
import io.github.k8242.threadlog.feature.search.context.ContextWindowViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ContextWindowViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val disambiguator = DefaultLeafDisambiguator()
    private val dateCalculator = DefaultDateWeekCalculator()

    private val hourMs = 60L * 60L * 1000L
    private val anchorMs = 1_780_000_000_000L

    @Test
    fun loadInitializesPlusMinus12hWindowAndSortsChronologically() = runTest {
        val farEarlier = sampleEntryWithDetails("e-early-18h", anchorMs - 18 * hourMs, "2026-05-28", "18h earlier")
        val withinEarlier = sampleEntryWithDetails("e-early-4h", anchorMs - 4 * hourMs, "2026-05-29", "4h earlier")
        val anchor = sampleEntryWithDetails("e-anchor", anchorMs, "2026-05-29", "Anchor entry match")
        val withinLater = sampleEntryWithDetails("e-later-6h", anchorMs + 6 * hourMs, "2026-05-29", "6h later")
        val farLater = sampleEntryWithDetails("e-later-20h", anchorMs + 20 * hourMs, "2026-05-30", "20h later")

        // Deliberately unsorted in repository to verify chronological sorting.
        val entryRepo = FakeEntryRepository(listOf(withinLater, farEarlier, anchor, farLater, withinEarlier))
        val tagRepo = FakeTagRepository()
        val settingsRepo = FakeSettingsRepository()

        val viewModel = ContextWindowViewModel(entryRepo, tagRepo, settingsRepo, disambiguator, dateCalculator)

        viewModel.uiState.test {
            assertThat(awaitItem().isLoading).isTrue()

            viewModel.load(anchorEntryId = "e-anchor", highlightQuery = "match")
            advanceUntilIdle()

            val state = expectMostRecentItem()
            assertThat(state.isLoading).isFalse()
            assertThat(state.notFound).isFalse()
            assertThat(state.highlightQuery).isEqualTo("match")
            assertThat(state.earlierHours).isEqualTo(12L)
            assertThat(state.laterHours).isEqualTo(12L)
            assertThat(state.windowSpanLabel).isEqualTo("\u00B112h")

            val context = state.contextState!!
            assertThat(context.windowStartMs).isEqualTo(anchorMs - ContextWindowState.DEFAULT_HALF_WINDOW_MS)
            assertThat(context.windowEndMs).isEqualTo(anchorMs + ContextWindowState.DEFAULT_HALF_WINDOW_MS)
            assertThat(context.entries.map { it.entry.id }).containsExactly(
                "e-early-4h",
                "e-anchor",
                "e-later-6h",
            ).inOrder()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun expandEarlierExpandLaterAndResetWindowUpdateObservedRange() = runTest {
        val farEarlier = sampleEntryWithDetails("e-early-18h", anchorMs - 18 * hourMs, "2026-05-28", "18h earlier")
        val anchor = sampleEntryWithDetails("e-anchor", anchorMs, "2026-05-29", "Anchor entry")
        val farLater = sampleEntryWithDetails("e-later-20h", anchorMs + 20 * hourMs, "2026-05-30", "20h later")

        val entryRepo = FakeEntryRepository(listOf(farEarlier, anchor, farLater))
        val viewModel = ContextWindowViewModel(
            entryRepo,
            FakeTagRepository(),
            FakeSettingsRepository(),
            disambiguator,
            dateCalculator,
        )

        viewModel.load("e-anchor", null)
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.contextState!!.entries.map { it.entry.id })
            .containsExactly("e-anchor")

        // Expand earlier (-24h / +12h)
        viewModel.expandEarlier()
        advanceUntilIdle()
        val afterEarlier = viewModel.uiState.value
        assertThat(afterEarlier.earlierHours).isEqualTo(24L)
        assertThat(afterEarlier.laterHours).isEqualTo(12L)
        assertThat(afterEarlier.isExpanded).isTrue()
        assertThat(afterEarlier.windowSpanLabel).isEqualTo("-24h / +12h")
        assertThat(afterEarlier.contextState!!.entries.map { it.entry.id })
            .containsExactly("e-early-18h", "e-anchor")
            .inOrder()

        // Expand later (-24h / +24h)
        viewModel.expandLater()
        advanceUntilIdle()
        val afterLater = viewModel.uiState.value
        assertThat(afterLater.earlierHours).isEqualTo(24L)
        assertThat(afterLater.laterHours).isEqualTo(24L)
        assertThat(afterLater.windowSpanLabel).isEqualTo("\u00B124h")
        assertThat(afterLater.contextState!!.entries.map { it.entry.id })
            .containsExactly("e-early-18h", "e-anchor", "e-later-20h")
            .inOrder()

        // Reset back to ±12h
        viewModel.resetWindow()
        advanceUntilIdle()
        val afterReset = viewModel.uiState.value
        assertThat(afterReset.earlierHours).isEqualTo(12L)
        assertThat(afterReset.laterHours).isEqualTo(12L)
        assertThat(afterReset.isExpanded).isFalse()
        assertThat(afterReset.contextState!!.entries.map { it.entry.id })
            .containsExactly("e-anchor")

        assertThat(entryRepo.observedRanges).containsExactly(
            (anchorMs - 12 * hourMs) to (anchorMs + 12 * hourMs),
            (anchorMs - 24 * hourMs) to (anchorMs + 12 * hourMs),
            (anchorMs - 24 * hourMs) to (anchorMs + 24 * hourMs),
            (anchorMs - 12 * hourMs) to (anchorMs + 12 * hourMs),
        ).inOrder()
    }

    @Test
    fun missingAnchorSetsNotFoundTrue() = runTest {
        val entryRepo = FakeEntryRepository(emptyList())
        val viewModel = ContextWindowViewModel(
            entryRepo,
            FakeTagRepository(),
            FakeSettingsRepository(),
            disambiguator,
            dateCalculator,
        )

        viewModel.load("non-existent-id", "query")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.isLoading).isFalse()
        assertThat(state.notFound).isTrue()
        assertThat(state.contextState).isNull()
    }

    @Test
    fun promoteAndRejectAutoTagsUpdateRepositoryAndObservedEntries() = runTest {
        val tagA = sampleTag("t-a", "projects/threadlog")
        val tagB = sampleTag("t-b", "topics/travel")
        val anchor = sampleEntryWithDetails(
            id = "e-anchor",
            createdAtMs = anchorMs,
            localDate = "2026-05-29",
            bodyPlain = "Working on ThreadLog while traveling",
            tags = listOf(
                EntryTag("e-anchor", tagA.id, tagA, TagState.AUTO_HIDDEN, 0.9f, anchorMs),
                EntryTag("e-anchor", tagB.id, tagB, TagState.AUTO_HIDDEN, 0.8f, anchorMs),
            ),
        )

        val entryRepo = FakeEntryRepository(listOf(anchor))
        val tagRepo = FakeTagRepository(
            initialTags = listOf(tagA, tagB),
            onTagStateChanged = { entryId, tagId, state ->
                entryRepo.updateTagState(entryId, tagId, state)
            },
        )
        val settingsRepo = FakeSettingsRepository(UserSettings(showHiddenAutoTagsInChat = false))

        val viewModel = ContextWindowViewModel(
            entryRepo,
            tagRepo,
            settingsRepo,
            disambiguator,
            dateCalculator,
        )

        viewModel.load("e-anchor", null)
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.showHiddenAutoTags).isFalse()
        viewModel.toggleShowHiddenAutoTags()
        assertThat(viewModel.uiState.value.showHiddenAutoTags).isTrue()

        viewModel.promoteAutoTag("e-anchor", tagA.id)
        viewModel.rejectAutoTag("e-anchor", tagB.id)
        advanceUntilIdle()

        assertThat(tagRepo.promotedCalls).containsExactly("e-anchor" to tagA.id)
        assertThat(tagRepo.rejectedCalls).containsExactly("e-anchor" to tagB.id)

        val updatedAnchor = viewModel.uiState.value.anchorEntry!!
        assertThat(updatedAnchor.visibleTags.map { it.tagId }).containsExactly(tagA.id)
        assertThat(updatedAnchor.rejectedTags.map { it.tagId }).containsExactly(tagB.id)
        assertThat(updatedAnchor.hiddenAutoTags).isEmpty()
    }
}
