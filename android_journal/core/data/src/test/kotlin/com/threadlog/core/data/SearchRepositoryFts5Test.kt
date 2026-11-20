package com.threadlog.core.data

import com.google.common.truth.Truth.assertThat
import com.threadlog.core.model.HybridSearchResult
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

class SearchRepositoryFts5Test {

    private lateinit var graph: TestDataGraph

    @Before
    fun setUp() {
        graph = TestDatabaseFactory.createGraph()
    }

    @After
    fun tearDown() {
        graph.close()
    }

    @Test
    fun fullTextSearch_returnsMarkedSnippet_andSplitsTierAAndTierBAt35DayBoundary() = runTest {
        val nowMs = 1_763_654_400_000L // 2026-11-20T16:00:00Z
        val cutoffMs = nowMs - HybridSearchResult.TIER_A_WINDOW_MS

        val recentNewest = graph.entryRepository.createEntry(
            bodyRaw = "Brewed fresh *espresso* before standup",
            userTagIds = emptyList(),
            nowMs = nowMs - 10_000L,
            zoneId = "UTC",
        )
        val recentAtExactBoundary = graph.entryRepository.createEntry(
            bodyRaw = "Boundary espresso tasting note",
            userTagIds = emptyList(),
            nowMs = cutoffMs,
            zoneId = "UTC",
        )
        val olderJustPastBoundary = graph.entryRepository.createEntry(
            bodyRaw = "Older espresso beans from roast batch 4",
            userTagIds = emptyList(),
            nowMs = cutoffMs - 1L,
            zoneId = "UTC",
        )
        val olderMuchEarlier = graph.entryRepository.createEntry(
            bodyRaw = "Summer espresso tonic experiment",
            userTagIds = emptyList(),
            nowMs = cutoffMs - (20L * 24L * 3600L * 1000L),
            zoneId = "UTC",
        )

        val result = graph.searchRepository.search(
            query = "espresso",
            includeHiddenAutoTags = true,
            nowMs = nowMs,
        )

        assertThat(result.cutoffEpochMs).isEqualTo(cutoffMs)
        assertThat(result.tierARecent.map { it.entryId })
            .containsExactly(recentNewest.entry.id, recentAtExactBoundary.entry.id)
            .inOrder()
        assertThat(result.tierBOlder.map { it.entryId })
            .containsExactly(olderJustPastBoundary.entry.id, olderMuchEarlier.entry.id)
            .inOrder()

        val topHit = result.tierARecent.first()
        assertThat(topHit.snippetMarked).contains("<mark>espresso</mark>")
        assertThat(topHit.matchedViaHiddenAutoTag).isFalse()
        assertThat(topHit.hiddenMatchedTags).isEmpty()
    }

    @Test
    fun hiddenAutoTagSearch_respectsIncludeHiddenFlag_andMatchesAliases() = runTest {
        val nowMs = 1_763_654_400_000L
        graph.tagRepository.createOrGetTagByPath(
            fullPath = "projects/journal-app",
            aliases = listOf("threadlog", "diarycore"),
        )
        val visibleTag = graph.tagRepository.createOrGetTagByPath("topics/engineering")

        val entryWithHiddenOnly = graph.entryRepository.createEntry(
            bodyRaw = "Shipped room database migration tests",
            userTagIds = listOf(visibleTag.id),
            nowMs = nowMs - 5_000L,
            zoneId = "UTC",
        )
        graph.tagRepository.applyAutoTags(
            entryId = entryWithHiddenOnly.entry.id,
            suggestions = listOf("projects/journal-app" to 0.92f),
            nowMs = nowMs - 4_000L,
        )

        // Query matching hidden auto-tag alias "diarycore" with includeHiddenAutoTags = true.
        val included = graph.searchRepository.search(
            query = "diarycore",
            includeHiddenAutoTags = true,
            nowMs = nowMs,
        )
        assertThat(included.tierARecent).hasSize(1)
        val hit = included.tierARecent.first()
        assertThat(hit.entryId).isEqualTo(entryWithHiddenOnly.entry.id)
        assertThat(hit.matchedViaHiddenAutoTag).isTrue()
        assertThat(hit.hiddenMatchedTags.map { it.fullPath }).containsExactly("projects/journal-app")
        assertThat(hit.visibleTags.map { it.fullPath }).containsExactly("topics/engineering")

        // Same query with includeHiddenAutoTags = false must NOT match the hidden tag.
        val excluded = graph.searchRepository.search(
            query = "diarycore",
            includeHiddenAutoTags = false,
            nowMs = nowMs,
        )
        assertThat(excluded.tierARecent).isEmpty()
        assertThat(excluded.tierBOlder).isEmpty()

        // Rejecting the auto-tag removes it from FTS5 hidden_tags immediately.
        val hiddenTagId = hit.hiddenMatchedTags.first().id
        graph.tagRepository.rejectAutoTag(entryWithHiddenOnly.entry.id, hiddenTagId, nowMs = nowMs)

        val afterReject = graph.searchRepository.search(
            query = "diarycore",
            includeHiddenAutoTags = true,
            nowMs = nowMs,
        )
        assertThat(afterReject.tierARecent).isEmpty()
    }

    @Test
    fun softDelete_excludesFromFts5_andUndoRestoresSearchability() = runTest {
        val nowMs = 1_763_654_400_000L
        val entry = graph.entryRepository.createEntry(
            bodyRaw = "Secret sourdough fermentation notes",
            userTagIds = emptyList(),
            nowMs = nowMs - 1_000L,
            zoneId = "UTC",
        )

        assertThat(graph.searchRepository.search("sourdough", nowMs = nowMs).tierARecent)
            .hasSize(1)

        graph.entryRepository.softDeleteEntry(entry.entry.id, nowMs = nowMs)
        assertThat(graph.searchRepository.search("sourdough", nowMs = nowMs).tierARecent)
            .isEmpty()

        graph.entryRepository.undoSoftDelete(entry.entry.id)
        assertThat(graph.searchRepository.search("sourdough", nowMs = nowMs).tierARecent.map { it.entryId })
            .containsExactly(entry.entry.id)
    }

    @Test
    fun fts5SpecialCharactersAndBlankQueries_neverThrowSyntaxErrors() = runTest {
        val nowMs = 1_763_654_400_000L
        val entry = graph.entryRepository.createEntry(
            bodyRaw = "Debugging OR NOT AND operators with foo-bar:baz (v2)",
            userTagIds = emptyList(),
            nowMs = nowMs - 1_000L,
            zoneId = "UTC",
        )

        val blank = graph.searchRepository.search("   ", nowMs = nowMs)
        assertThat(blank.tierARecent).isEmpty()
        assertThat(blank.tierBOlder).isEmpty()

        val punctuationOnly = graph.searchRepository.search("\"***---:::(())!!!", nowMs = nowMs)
        assertThat(punctuationOnly.tierARecent).isEmpty()
        assertThat(punctuationOnly.tierBOlder).isEmpty()

        val specialChars = graph.searchRepository.search("\"NOT\" (OR) -foo:bar*", nowMs = nowMs)
        assertThat(specialChars.tierARecent.map { it.entryId }).containsExactly(entry.entry.id)
    }
}
