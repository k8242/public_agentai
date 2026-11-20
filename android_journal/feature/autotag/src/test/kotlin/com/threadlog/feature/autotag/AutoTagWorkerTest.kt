package com.threadlog.feature.autotag

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.google.common.truth.Truth.assertThat
import com.threadlog.core.model.AutoTagSuggestion
import com.threadlog.core.model.TagState
import com.threadlog.core.model.contract.AutoTagProvider
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AutoTagWorkerTest {

    private lateinit var context: Context
    private lateinit var entryRepository: FakeEntryRepository
    private lateinit var tagRepository: FakeTagRepository
    private lateinit var ruleRepository: FakeAutoTagRuleRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        entryRepository = FakeEntryRepository()
        tagRepository = FakeTagRepository()
        ruleRepository = FakeAutoTagRuleRepository()
    }

    @Test
    fun missingEntryId_returnsFailure() = runTest {
        val worker = buildWorker(
            entryId = null,
            provider = FakeAutoTagProvider { _, _, _, _ -> emptyList() },
        )

        val result = worker.doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.failure())
        assertThat(tagRepository.appliedAutoTags).isEmpty()
    }

    @Test
    fun blankEntryId_returnsFailure() = runTest {
        val worker = buildWorker(
            entryId = "   ",
            provider = FakeAutoTagProvider { _, _, _, _ -> emptyList() },
        )

        val result = worker.doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.failure())
    }

    @Test
    fun missingEntryInRepository_returnsSuccessNoOp() = runTest {
        val worker = buildWorker(
            entryId = "does-not-exist",
            provider = FakeAutoTagProvider { _, _, _, _ ->
                listOf(AutoTagSuggestion("topics/should-not-run", 0.9f, "test"))
            },
        )

        val result = worker.doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        assertThat(tagRepository.appliedAutoTags).isEmpty()
    }

    @Test
    fun deletedEntry_returnsSuccessNoOp() = runTest {
        val deletedEntry = testEntry(id = "e-del", deletedAtMs = 1_763_655_000_000L)
        entryRepository.setEntries(listOf(testEntryWithDetails(entry = deletedEntry)))

        val worker = buildWorker(
            entryId = "e-del",
            provider = FakeAutoTagProvider { _, _, _, _ ->
                listOf(AutoTagSuggestion("topics/should-not-run", 0.9f, "test"))
            },
        )

        val result = worker.doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        assertThat(tagRepository.appliedAutoTags).isEmpty()
    }

    @Test
    fun validEntry_appliesSuggestionsAndSkipsUserPromotedAndRejectedTags() = runTest {
        val userTag = testTag("t-user", "people/friends/Lex")
        val promotedTag = testTag("t-promoted", "projects/journal")
        val rejectedTag = testTag("t-rejected", "topics/noise")
        val hiddenTag = testTag("t-hidden", "topics/existing-hidden")

        val entry = testEntry(id = "e-1", bodyPlain = "Met Lex on journal noise")
        entryRepository.setEntries(
            listOf(
                testEntryWithDetails(
                    entry = entry,
                    tags = listOf(
                        testEntryTag("e-1", userTag, TagState.USER),
                        testEntryTag("e-1", promotedTag, TagState.AUTO_PROMOTED),
                        testEntryTag("e-1", rejectedTag, TagState.AUTO_REJECTED),
                        testEntryTag("e-1", hiddenTag, TagState.AUTO_HIDDEN),
                    ),
                ),
            ),
        )

        val provider = FakeAutoTagProvider { _, _, _, _ ->
            listOf(
                AutoTagSuggestion("people/friends/lex", 0.95f, "already USER"),
                AutoTagSuggestion("/projects/journal/", 0.92f, "already AUTO_PROMOTED"),
                AutoTagSuggestion("topics/noise", 0.90f, "already AUTO_REJECTED"),
                AutoTagSuggestion("topics/existing-hidden", 0.88f, "still AUTO_HIDDEN"),
                AutoTagSuggestion("topics/new-suggestion", 0.84f, "brand new"),
            )
        }

        val worker = buildWorker(entryId = "e-1", provider = provider)
        val result = worker.doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        assertThat(tagRepository.appliedAutoTags).hasSize(1)
        val (appliedEntryId, appliedPairs) = tagRepository.appliedAutoTags.single()
        assertThat(appliedEntryId).isEqualTo("e-1")
        assertThat(appliedPairs).containsExactly(
            "topics/existing-hidden" to 0.88f,
            "topics/new-suggestion" to 0.84f,
        ).inOrder()
    }

    @Test
    fun allSuggestionsFilteredOut_doesNotCallApplyAutoTags() = runTest {
        val rejectedTag = testTag("t-rejected", "topics/noise")
        val entry = testEntry(id = "e-2", bodyPlain = "noise")
        entryRepository.setEntries(
            listOf(
                testEntryWithDetails(
                    entry = entry,
                    tags = listOf(testEntryTag("e-2", rejectedTag, TagState.AUTO_REJECTED)),
                ),
            ),
        )

        val worker = buildWorker(
            entryId = "e-2",
            provider = FakeAutoTagProvider { _, _, _, _ ->
                listOf(AutoTagSuggestion("topics/noise", 0.90f, "rejected"))
            },
        )

        val result = worker.doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        assertThat(tagRepository.appliedAutoTags).isEmpty()
    }

    @Test
    fun transientException_retriesUntilMaxAttemptsThenFails() = runTest {
        val entry = testEntry(id = "e-err")
        entryRepository.setEntries(listOf(testEntryWithDetails(entry = entry)))
        tagRepository.throwOnApply = IllegalStateException("SQLite busy")

        val provider = FakeAutoTagProvider { _, _, _, _ ->
            listOf(AutoTagSuggestion("topics/work", 0.85f, "match"))
        }

        val firstAttemptWorker = buildWorker(
            entryId = "e-err",
            provider = provider,
            runAttemptCount = 0,
        )
        assertThat(firstAttemptWorker.doWork()).isEqualTo(ListenableWorker.Result.retry())

        val exhaustedAttemptWorker = buildWorker(
            entryId = "e-err",
            provider = provider,
            runAttemptCount = AutoTagWorker.MAX_RETRY_ATTEMPTS,
        )
        assertThat(exhaustedAttemptWorker.doWork()).isEqualTo(ListenableWorker.Result.failure())
    }

    private fun buildWorker(
        entryId: String?,
        provider: AutoTagProvider,
        runAttemptCount: Int = 0,
    ): AutoTagWorker {
        val factory = object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters,
            ): ListenableWorker = AutoTagWorker(
                appContext = appContext,
                params = workerParameters,
                entryRepository = entryRepository,
                tagRepository = tagRepository,
                autoTagRuleRepository = ruleRepository,
                autoTagProvider = provider,
            )
        }

        val builder = TestListenableWorkerBuilder<AutoTagWorker>(context)
            .setWorkerFactory(factory)
            .setRunAttemptCount(runAttemptCount)

        if (entryId != null) {
            builder.setInputData(workDataOf(AutoTagWorker.KEY_ENTRY_ID to entryId))
        }

        return builder.build()
    }
}
