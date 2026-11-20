package com.threadlog.feature.autotag.ui

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.threadlog.core.model.TagState
import com.threadlog.feature.autotag.FakeEntryRepository
import com.threadlog.feature.autotag.FakeLeafDisambiguator
import com.threadlog.feature.autotag.FakeTagRepository
import com.threadlog.feature.autotag.testEntry
import com.threadlog.feature.autotag.testEntryTag
import com.threadlog.feature.autotag.testEntryWithDetails
import com.threadlog.feature.autotag.testTag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AutoTagReviewViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun uiState_streamsPendingEntriesAndDisambiguatedLabels() = runTest(testDispatcher) {
        val workAlex = testTag("t-work-alex", "people/work/Alex")
        val familyAlex = testTag("t-family-alex", "people/family/Alex")
        val sprintTag = testTag("t-sprint", "topics/sprint")

        val pendingEntry = testEntryWithDetails(
            entry = testEntry(id = "e1", bodyPlain = "Met Alex about sprint"),
            tags = listOf(
                testEntryTag("e1", workAlex, TagState.AUTO_HIDDEN, confidence = 0.85f),
                testEntryTag("e1", sprintTag, TagState.AUTO_HIDDEN, confidence = 0.90f),
            ),
        )
        val alreadyCleanEntry = testEntryWithDetails(
            entry = testEntry(id = "e2", bodyPlain = "No pending tags"),
            tags = listOf(testEntryTag("e2", familyAlex, TagState.USER)),
        )
        val deletedPendingEntry = testEntryWithDetails(
            entry = testEntry(id = "e3", deletedAtMs = 1_763_655_000_000L),
            tags = listOf(testEntryTag("e3", sprintTag, TagState.AUTO_HIDDEN)),
        )

        val entryRepo = FakeEntryRepository(
            listOf(pendingEntry, alreadyCleanEntry, deletedPendingEntry),
        )
        val tagRepo = FakeTagRepository(
            initialTags = listOf(workAlex, familyAlex, sprintTag),
            entryRepository = entryRepo,
        )
        val viewModel = AutoTagReviewViewModel(
            entryRepository = entryRepo,
            tagRepository = tagRepo,
            leafDisambiguator = FakeLeafDisambiguator(),
        )

        viewModel.uiState.test {
            val initial = awaitItem()
            assertThat(initial.isLoading).isTrue()

            val loaded = awaitItem()
            assertThat(loaded.isLoading).isFalse()
            assertThat(loaded.isEmpty).isFalse()
            assertThat(loaded.pendingEntries.map { it.entry.id }).containsExactly("e1")
            assertThat(loaded.pendingTagCount).isEqualTo(2)
            assertThat(loaded.displayLabelFor(workAlex)).isEqualTo("Alex (work)")
            assertThat(loaded.displayLabelFor(familyAlex)).isEqualTo("Alex (family)")
            assertThat(loaded.displayLabelFor(sprintTag)).isEqualTo("sprint")

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun promoteTag_delegatesToRepositoryAndUpdatesState() = runTest(testDispatcher) {
        val tagA = testTag("t-a", "topics/kotlin")
        val entryRepo = FakeEntryRepository(
            listOf(
                testEntryWithDetails(
                    entry = testEntry(id = "e1"),
                    tags = listOf(testEntryTag("e1", tagA, TagState.AUTO_HIDDEN)),
                ),
            ),
        )
        val tagRepo = FakeTagRepository(
            initialTags = listOf(tagA),
            entryRepository = entryRepo,
        )
        val viewModel = AutoTagReviewViewModel(entryRepo, tagRepo, FakeLeafDisambiguator())

        viewModel.uiState.test {
            awaitItem() // initial loading
            val loaded = awaitItem()
            assertThat(loaded.pendingTagCount).isEqualTo(1)

            viewModel.promoteTag("e1", "t-a")
            advanceUntilIdle()

            val updated = expectMostRecentItem()
            assertThat(tagRepo.promotedCalls).containsExactly("e1" to "t-a")
            assertThat(updated.pendingEntries).isEmpty()
            assertThat(updated.isEmpty).isTrue()
            assertThat(updated.statusMessage).isEqualTo("Auto-tag promoted")

            viewModel.clearStatusMessage()
            advanceUntilIdle()
            assertThat(expectMostRecentItem().statusMessage).isNull()

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun rejectTag_delegatesToRepositoryAndUpdatesState() = runTest(testDispatcher) {
        val tagA = testTag("t-a", "topics/kotlin")
        val tagB = testTag("t-b", "topics/android")
        val entryRepo = FakeEntryRepository(
            listOf(
                testEntryWithDetails(
                    entry = testEntry(id = "e1"),
                    tags = listOf(
                        testEntryTag("e1", tagA, TagState.AUTO_HIDDEN),
                        testEntryTag("e1", tagB, TagState.AUTO_HIDDEN),
                    ),
                ),
            ),
        )
        val tagRepo = FakeTagRepository(
            initialTags = listOf(tagA, tagB),
            entryRepository = entryRepo,
        )
        val viewModel = AutoTagReviewViewModel(entryRepo, tagRepo, FakeLeafDisambiguator())

        viewModel.uiState.test {
            awaitItem() // loading
            awaitItem() // loaded

            viewModel.rejectTag("e1", "t-a")
            advanceUntilIdle()

            val updated = expectMostRecentItem()
            assertThat(tagRepo.rejectedCalls).containsExactly("e1" to "t-a")
            assertThat(updated.pendingTagCount).isEqualTo(1)
            assertThat(updated.pendingEntries.single().hiddenAutoTags.map { it.tagId })
                .containsExactly("t-b")
            assertThat(updated.statusMessage).isEqualTo("Auto-tag dismissed")

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun promoteAllForEntry_promotesOnlyHiddenAutoTagsOnThatEntry() = runTest(testDispatcher) {
        val userTag = testTag("t-user", "people/Lex")
        val hidden1 = testTag("t-h1", "topics/kotlin")
        val hidden2 = testTag("t-h2", "topics/compose")
        val entryRepo = FakeEntryRepository(
            listOf(
                testEntryWithDetails(
                    entry = testEntry(id = "e1"),
                    tags = listOf(
                        testEntryTag("e1", userTag, TagState.USER),
                        testEntryTag("e1", hidden1, TagState.AUTO_HIDDEN),
                        testEntryTag("e1", hidden2, TagState.AUTO_HIDDEN),
                    ),
                ),
            ),
        )
        val tagRepo = FakeTagRepository(
            initialTags = listOf(userTag, hidden1, hidden2),
            entryRepository = entryRepo,
        )
        val viewModel = AutoTagReviewViewModel(entryRepo, tagRepo, FakeLeafDisambiguator())

        viewModel.uiState.test {
            awaitItem() // loading
            awaitItem() // loaded

            viewModel.promoteAllForEntry("e1")
            advanceUntilIdle()

            val updated = expectMostRecentItem()
            assertThat(tagRepo.promotedCalls).containsExactly(
                "e1" to "t-h1",
                "e1" to "t-h2",
            ).inOrder()
            assertThat(updated.isEmpty).isTrue()
            assertThat(updated.statusMessage).isEqualTo("2 auto-tags promoted")

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun rejectAllForEntry_rejectsOnlyHiddenAutoTagsOnThatEntry() = runTest(testDispatcher) {
        val hidden1 = testTag("t-h1", "topics/kotlin")
        val hidden2 = testTag("t-h2", "topics/compose")
        val otherEntryTag = testTag("t-other", "projects/other")
        val entryRepo = FakeEntryRepository(
            listOf(
                testEntryWithDetails(
                    entry = testEntry(id = "e1"),
                    tags = listOf(
                        testEntryTag("e1", hidden1, TagState.AUTO_HIDDEN),
                        testEntryTag("e1", hidden2, TagState.AUTO_HIDDEN),
                    ),
                ),
                testEntryWithDetails(
                    entry = testEntry(id = "e2"),
                    tags = listOf(
                        testEntryTag("e2", otherEntryTag, TagState.AUTO_HIDDEN),
                    ),
                ),
            ),
        )
        val tagRepo = FakeTagRepository(
            initialTags = listOf(hidden1, hidden2, otherEntryTag),
            entryRepository = entryRepo,
        )
        val viewModel = AutoTagReviewViewModel(entryRepo, tagRepo, FakeLeafDisambiguator())

        viewModel.uiState.test {
            awaitItem() // loading
            awaitItem() // loaded

            viewModel.rejectAllForEntry("e1")
            advanceUntilIdle()

            val updated = expectMostRecentItem()
            assertThat(tagRepo.rejectedCalls).containsExactly(
                "e1" to "t-h1",
                "e1" to "t-h2",
            ).inOrder()
            assertThat(updated.pendingEntries.map { it.entry.id }).containsExactly("e2")
            assertThat(updated.pendingTagCount).isEqualTo(1)
            assertThat(updated.statusMessage).isEqualTo("2 auto-tags dismissed")

            cancelAndIgnoreRemainingEvents()
        }
    }
}
