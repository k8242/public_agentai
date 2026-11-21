// Copyright (c) 2026 k8242
// SPDX-License-Identifier: MIT

package io.github.k8242.threadlog.feature.chat.bookmark

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import com.google.common.truth.Truth.assertThat
import io.github.k8242.threadlog.core.model.BackupCadence
import io.github.k8242.threadlog.core.model.Bookmark
import io.github.k8242.threadlog.core.model.UserSettings
import io.github.k8242.threadlog.core.model.WeekStart
import io.github.k8242.threadlog.core.model.contract.BookmarkRepository
import io.github.k8242.threadlog.core.model.contract.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BookmarkTitleWorkerTest {

    private lateinit var context: Context
    private lateinit var server: MockWebServer
    private lateinit var fetcher: BookmarkTitleFetcher
    private lateinit var bookmarkRepo: FakeBookmarkRepository
    private lateinit var settingsRepo: FakeSettingsRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        server = MockWebServer()
        server.start()
        val client = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .build()
        fetcher = BookmarkTitleFetcher(client)
        bookmarkRepo = FakeBookmarkRepository()
        settingsRepo = FakeSettingsRepository()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `fails when entryId is missing or blank`() = runTest {
        val missingWorker = buildWorker(entryId = null)
        assertThat(missingWorker.doWork()).isEqualTo(ListenableWorker.Result.failure())

        val blankWorker = buildWorker(entryId = "   ")
        assertThat(blankWorker.doWork()).isEqualTo(ListenableWorker.Result.failure())
    }

    @Test
    fun `skips network completely when offlineOnlyLinks is enabled`() = runTest {
        settingsRepo.setOfflineOnlyLinks(true)
        bookmarkRepo.bookmarksByEntry["entry-1"] = listOf(
            Bookmark(
                id = "bm-1",
                entryId = "entry-1",
                urlRaw = server.url("/offline").toString(),
                domain = "localhost",
            ),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/html")
                .setBody("<html><head><title>Should Not Fetch</title></head></html>"),
        )

        val worker = buildWorker(entryId = "entry-1")
        val result = worker.doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        assertThat(server.requestCount).isEqualTo(0)
        assertThat(bookmarkRepo.updatedTitles).isEmpty()
    }

    @Test
    fun `fetches and updates missing bookmark titles when online mode enabled`() = runTest {
        settingsRepo.setOfflineOnlyLinks(false)
        bookmarkRepo.bookmarksByEntry["entry-2"] = listOf(
            Bookmark(
                id = "bm-1",
                entryId = "entry-2",
                urlRaw = server.url("/one").toString(),
                domain = "localhost",
            ),
            Bookmark(
                id = "bm-2",
                entryId = "entry-2",
                urlRaw = server.url("/two").toString(),
                domain = "localhost",
            ),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/html")
                .setBody("<html><head><title>First Title</title></head></html>"),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/html")
                .setBody("<html><head><title>Second &amp; Final</title></head></html>"),
        )

        val worker = buildWorker(entryId = "entry-2")
        val result = worker.doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        assertThat(server.requestCount).isEqualTo(2)
        assertThat(bookmarkRepo.updatedTitles).containsExactly(
            "bm-1", "First Title",
            "bm-2", "Second & Final",
        )
    }

    @Test
    fun `retries on IOException below max attempts and succeeds once attempts exhausted`() = runTest {
        settingsRepo.setOfflineOnlyLinks(false)
        bookmarkRepo.bookmarksByEntry["entry-3"] = listOf(
            Bookmark(
                id = "bm-err",
                entryId = "entry-3",
                urlRaw = server.url("/drop").toString(),
                domain = "localhost",
            ),
        )
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        val firstAttempt = buildWorker(entryId = "entry-3", runAttemptCount = 0)
        assertThat(firstAttempt.doWork()).isEqualTo(ListenableWorker.Result.retry())

        val exhaustedAttempt = buildWorker(
            entryId = "entry-3",
            runAttemptCount = BookmarkTitleWorker.MAX_RETRY_ATTEMPTS,
        )
        assertThat(exhaustedAttempt.doWork()).isEqualTo(ListenableWorker.Result.success())
    }

    @Test
    fun `scheduler enqueues unique work with connected network constraint`() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val workManager = WorkManager.getInstance(context)
        val scheduler = WorkManagerBookmarkTitleScheduler(workManager)

        scheduler.enqueueTitleFetch("entry-42")

        val infos = workManager
            .getWorkInfosForUniqueWork(WorkManagerBookmarkTitleScheduler.uniqueWorkName("entry-42"))
            .get()

        assertThat(infos).hasSize(1)
        assertThat(infos.first().state).isEqualTo(WorkInfo.State.ENQUEUED)
        assertThat(infos.first().constraints.requiredNetworkType).isEqualTo(NetworkType.CONNECTED)
        assertThat(infos.first().tags).contains(WorkManagerBookmarkTitleScheduler.WORK_TAG)
    }

    private fun buildWorker(
        entryId: String?,
        runAttemptCount: Int = 0,
    ): BookmarkTitleWorker {
        val factory = object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters,
            ): ListenableWorker = BookmarkTitleWorker(
                appContext = appContext,
                workerParams = workerParameters,
                bookmarkRepository = bookmarkRepo,
                settingsRepository = settingsRepo,
                titleFetcher = fetcher,
            )
        }

        val builder = TestListenableWorkerBuilder<BookmarkTitleWorker>(context)
            .setWorkerFactory(factory)
            .setRunAttemptCount(runAttemptCount)
        if (entryId != null) {
            builder.setInputData(workDataOf(BookmarkTitleWorker.KEY_ENTRY_ID to entryId))
        }
        return builder.build()
    }

    private class FakeBookmarkRepository : BookmarkRepository {
        val bookmarksByEntry = mutableMapOf<String, List<Bookmark>>()
        val updatedTitles = linkedMapOf<String, String>()

        override suspend fun updateBookmarkTitle(bookmarkId: String, title: String) {
            updatedTitles[bookmarkId] = title
        }

        override suspend fun setCollapsed(bookmarkId: String, collapsed: Boolean) = Unit

        override suspend fun getBookmarksMissingTitle(entryId: String): List<Bookmark> =
            bookmarksByEntry[entryId].orEmpty()
    }

    private class FakeSettingsRepository : SettingsRepository {
        private val state = MutableStateFlow(UserSettings())
        override val settings: Flow<UserSettings> = state

        override suspend fun getSettings(): UserSettings = state.value

        override suspend fun setWeekStart(weekStart: WeekStart) {
            state.value = state.value.copy(weekStart = weekStart)
        }

        override suspend fun setOfflineOnlyLinks(enabled: Boolean) {
            state.value = state.value.copy(offlineOnlyLinks = enabled)
        }

        override suspend fun setShowHiddenAutoTagsInChat(show: Boolean) {
            state.value = state.value.copy(showHiddenAutoTagsInChat = show)
        }

        override suspend fun setScheduledBackupCadence(cadence: BackupCadence) {
            state.value = state.value.copy(scheduledBackupCadence = cadence)
        }

        override suspend fun updateSyncLinks(
            spreadsheetId: String?,
            mediaFolderId: String?,
            lastExportIso: String?,
        ) = Unit
    }
}
