// Copyright (c) 2026 k8242
// SPDX-License-Identifier: MIT

package io.github.k8242.threadlog.core.data

import androidx.paging.testing.asSnapshot
import androidx.sqlite.execSQL
import com.google.common.truth.Truth.assertThat
import io.github.k8242.threadlog.core.model.ChatFeedFilter
import io.github.k8242.threadlog.core.model.TaskStatus
import io.github.k8242.threadlog.core.model.TodoFilterMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

class TaskAndDrawerRepositoryTest {

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
    fun taskStatusLifecycle_updatesEntryAndContentHash_andFiltersTodosFeed() = runTest {
        val regular = graph.entryRepository.createEntry(
            bodyRaw = "Regular journal note",
            userTagIds = emptyList(),
            nowMs = 1_763_600_000_000L,
            zoneId = "UTC",
        )
        val initialHash = regular.entry.contentHash
        assertThat(regular.entry.taskStatus).isEqualTo(TaskStatus.NONE)
        assertThat(regular.entry.isTask).isFalse()

        val createdTodo = graph.entryRepository.createEntry(
            bodyRaw = "Ship feature update",
            userTagIds = emptyList(),
            nowMs = 1_763_600_100_000L,
            zoneId = "UTC",
            taskStatus = TaskStatus.TODO,
        )
        assertThat(createdTodo.entry.taskStatus).isEqualTo(TaskStatus.TODO)
        assertThat(createdTodo.entry.isTask).isTrue()
        assertThat(createdTodo.entry.isTaskDone).isFalse()

        // Toggle regular note -> TODO -> DONE -> NONE
        graph.entryRepository.setTaskStatus(regular.entry.id, TaskStatus.TODO, nowMs = 1_763_600_200_000L)
        val promoted = graph.entryRepository.getEntry(regular.entry.id)!!
        assertThat(promoted.entry.taskStatus).isEqualTo(TaskStatus.TODO)
        assertThat(promoted.entry.contentHash).isNotEqualTo(initialHash)

        graph.entryRepository.setTaskStatus(regular.entry.id, TaskStatus.DONE, nowMs = 1_763_600_300_000L)
        val completed = graph.entryRepository.getEntry(regular.entry.id)!!
        assertThat(completed.entry.isTaskDone).isTrue()

        val openTodos = graph.entryRepository.observePagedEntries(ChatFeedFilter.Todos(TodoFilterMode.OPEN)).asSnapshot()
        assertThat(openTodos.map { it.entry.id }).containsExactly(createdTodo.entry.id)

        val doneTodos = graph.entryRepository.observePagedEntries(ChatFeedFilter.Todos(TodoFilterMode.DONE)).asSnapshot()
        assertThat(doneTodos.map { it.entry.id }).containsExactly(regular.entry.id)

        val allTodos = graph.entryRepository.observePagedEntries(ChatFeedFilter.Todos(TodoFilterMode.ALL)).asSnapshot()
        assertThat(allTodos.map { it.entry.id }).containsExactly(createdTodo.entry.id, regular.entry.id)

        // Drawer overview reflects open/completed counts and latest open TODO preview
        val overview = graph.entryRepository.observeDrawerOverview().first()
        assertThat(overview.totalEntryCount).isEqualTo(2)
        assertThat(overview.openTodoCount).isEqualTo(1)
        assertThat(overview.completedTodoCount).isEqualTo(1)
        assertThat(overview.latestOpenTodoPreview).contains("Ship feature update")

        // Converting back to regular note removes it from TODOs
        graph.entryRepository.setTaskStatus(regular.entry.id, TaskStatus.NONE, nowMs = 1_763_600_400_000L)
        val allTodosAfter = graph.entryRepository.observePagedEntries(ChatFeedFilter.Todos(TodoFilterMode.ALL)).asSnapshot()
        assertThat(allTodosAfter.map { it.entry.id }).containsExactly(createdTodo.entry.id)
    }

    @Test
    fun tagSubtreeFilterAndDrawerTagSummaries_aggregateCountsAndPreviews() = runTest {
        val lexTag = graph.tagRepository.createOrGetTagByPath("people/friends/Lex")
        val ideasTag = graph.tagRepository.createOrGetTagByPath("topics/ideas")

        val note1 = graph.entryRepository.createEntry(
            bodyRaw = "Coffee with Lex",
            userTagIds = listOf(lexTag.id),
            nowMs = 1_763_600_000_000L,
            zoneId = "UTC",
            taskStatus = TaskStatus.TODO,
        )
        val note2 = graph.entryRepository.createEntry(
            bodyRaw = "Build left panel",
            userTagIds = listOf(ideasTag.id),
            nowMs = 1_763_600_100_000L,
            zoneId = "UTC",
        )

        val peopleTags = graph.tagRepository.getAllTags()
        val peopleRoot = peopleTags.first { it.fullPath == "people" }

        val lexFeed = graph.entryRepository.observePagedEntries(
            ChatFeedFilter.TagSubtree(tagId = lexTag.id, fullPath = lexTag.fullPath, displayLabel = "Lex"),
        ).asSnapshot()
        assertThat(lexFeed.map { it.entry.id }).containsExactly(note1.entry.id)

        val peopleRootFeed = graph.entryRepository.observePagedEntries(
            ChatFeedFilter.TagSubtree(tagId = peopleRoot.id, fullPath = peopleRoot.fullPath, displayLabel = "people"),
        ).asSnapshot()
        assertThat(peopleRootFeed.map { it.entry.id }).containsExactly(note1.entry.id)

        val drawerSummaries = graph.entryRepository.observeDrawerTagSummaries().first()
        val lexSummary = drawerSummaries.first { it.tag.id == lexTag.id }
        assertThat(lexSummary.entryCount).isEqualTo(1)
        assertThat(lexSummary.openTodoCount).isEqualTo(1)
        assertThat(lexSummary.latestPreview).contains("Coffee with Lex")

        val ideasSummary = drawerSummaries.first { it.tag.id == ideasTag.id }
        assertThat(ideasSummary.entryCount).isEqualTo(1)
        assertThat(ideasSummary.openTodoCount).isEqualTo(0)
        assertThat(ideasSummary.latestPreview).contains("Build left panel")
        assertThat(note2.entry.id).isNotEmpty()
    }

    @Test
    fun migration1To2_addsTaskStatusColumnWithDefaultNone() {
        val driver = androidx.sqlite.driver.bundled.BundledSQLiteDriver()
        val conn = driver.open(":memory:")
        try {
            conn.execSQL(
                """
                CREATE TABLE entries (
                    id TEXT NOT NULL PRIMARY KEY,
                    created_at_ms INTEGER NOT NULL,
                    zone_id TEXT NOT NULL,
                    local_date TEXT NOT NULL,
                    iso_week TEXT NOT NULL,
                    body_raw TEXT NOT NULL,
                    body_plain TEXT NOT NULL,
                    reply_to_id TEXT,
                    content_hash TEXT NOT NULL,
                    updated_at_ms INTEGER,
                    deleted_at_ms INTEGER
                )
                """.trimIndent(),
            )
            conn.execSQL(
                """
                INSERT INTO entries (id, created_at_ms, zone_id, local_date, iso_week, body_raw, body_plain, content_hash)
                VALUES ('e-legacy', 1763600000000, 'UTC', '2026-11-20', '2026-W47', 'legacy note', 'legacy note', 'abc')
                """.trimIndent(),
            )

            io.github.k8242.threadlog.core.data.local.ThreadLogDatabase.MIGRATION_1_2.migrate(conn)

            conn.prepare("SELECT task_status FROM entries WHERE id = 'e-legacy'").use { stmt ->
                assertThat(stmt.step()).isTrue()
                assertThat(stmt.getText(0)).isEqualTo("NONE")
            }

            io.github.k8242.threadlog.core.data.local.ThreadLogDatabase.MIGRATION_2_3.migrate(conn)

            conn.prepare("SELECT reactions_json FROM entries WHERE id = 'e-legacy'").use { stmt ->
                assertThat(stmt.step()).isTrue()
                assertThat(stmt.getText(0)).isEqualTo("[]")
            }
        } finally {
            conn.close()
        }
    }

    @Test
    fun toggleReaction_addsRemovesAndUpdatesContentHash() = runTest {
        val created = graph.entryRepository.createEntry(
            bodyRaw = "Celebration note",
            userTagIds = emptyList(),
            nowMs = 1_763_600_000_000L,
            zoneId = "UTC",
        )
        assertThat(created.entry.reactions).isEmpty()
        val initialHash = created.entry.contentHash

        graph.entryRepository.toggleReaction(created.entry.id, "\uD83D\uDD25", nowMs = 1_763_600_100_000L)
        graph.entryRepository.toggleReaction(created.entry.id, "\uD83D\uDC4D", nowMs = 1_763_600_200_000L)
        val afterAdd = graph.entryRepository.getEntry(created.entry.id)!!
        assertThat(afterAdd.entry.reactions).containsExactly("\uD83D\uDD25", "\uD83D\uDC4D").inOrder()
        assertThat(afterAdd.entry.contentHash).isNotEqualTo(initialHash)

        // Toggling an existing reaction removes it.
        graph.entryRepository.toggleReaction(created.entry.id, "\uD83D\uDD25", nowMs = 1_763_600_300_000L)
        val afterRemove = graph.entryRepository.getEntry(created.entry.id)!!
        assertThat(afterRemove.entry.reactions).containsExactly("\uD83D\uDC4D")
    }

    @Test
    fun dailyOrdinal_isZeroBasedPerDay_andResetsNextDay() = runTest {
        // Day 1: 2026-11-20 UTC (3 notes)
        val day1Base = 1_795_132_800_000L // 2026-11-20T00:00:00Z
        val n0 = graph.entryRepository.createEntry("First", emptyList(), nowMs = day1Base + 1_000L, zoneId = "UTC")
        val n1 = graph.entryRepository.createEntry("Second", emptyList(), nowMs = day1Base + 2_000L, zoneId = "UTC")
        val n2 = graph.entryRepository.createEntry("Third", emptyList(), nowMs = day1Base + 3_000L, zoneId = "UTC")

        // Day 2: 2026-11-21 UTC (2 notes)
        val day2Base = day1Base + 86_400_000L
        val m0 = graph.entryRepository.createEntry("Next day first", emptyList(), nowMs = day2Base + 1_000L, zoneId = "UTC")
        val m1 = graph.entryRepository.createEntry("Next day second", emptyList(), nowMs = day2Base + 2_000L, zoneId = "UTC")

        val snapshot = graph.entryRepository.observePagedEntries(ChatFeedFilter.All).asSnapshot()
        val byId = snapshot.associateBy { it.entry.id }
        assertThat(byId[n0.entry.id]!!.dailyOrdinal).isEqualTo(0)
        assertThat(byId[n1.entry.id]!!.dailyOrdinal).isEqualTo(1)
        assertThat(byId[n2.entry.id]!!.dailyOrdinal).isEqualTo(2)
        assertThat(byId[m0.entry.id]!!.dailyOrdinal).isEqualTo(0)
        assertThat(byId[m1.entry.id]!!.dailyOrdinal).isEqualTo(1)
    }

    @Test
    fun createEntry_autoResolvesInlineProjectPersonAndTopicTags() = runTest {
        val created = graph.entryRepository.createEntry(
            bodyRaw = "Synced with @Lex on +journal-app about #ideas",
            userTagIds = emptyList(),
            nowMs = 1_763_600_000_000L,
            zoneId = "UTC",
        )

        val visiblePaths = created.visibleTags.map { it.tag.fullPath }
        assertThat(visiblePaths).containsExactly(
            "people/Lex",
            "projects/journal-app",
            "topics/ideas",
        )
    }
}
