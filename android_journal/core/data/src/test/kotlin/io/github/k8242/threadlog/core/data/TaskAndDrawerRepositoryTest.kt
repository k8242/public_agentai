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
        } finally {
            conn.close()
        }
    }
}
