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

            io.github.k8242.threadlog.core.data.local.ThreadLogDatabase.MIGRATION_3_4.migrate(conn)

            conn.prepare(
                "SELECT task_priority, archived_at_ms, is_highlighted FROM entries WHERE id = 'e-legacy'",
            ).use { stmt ->
                assertThat(stmt.step()).isTrue()
                assertThat(stmt.getText(0)).isEqualTo("NONE")
                assertThat(stmt.isNull(1)).isTrue()
                assertThat(stmt.getLong(2)).isEqualTo(0L)
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
        assertThat(afterAdd.entry.updatedAtMs).isNull()
        assertThat(afterAdd.entry.isEdited).isFalse()

        // Toggling an existing reaction removes it and still leaves updatedAtMs untouched.
        graph.entryRepository.toggleReaction(created.entry.id, "\uD83D\uDD25", nowMs = 1_763_600_300_000L)
        val afterRemove = graph.entryRepository.getEntry(created.entry.id)!!
        assertThat(afterRemove.entry.reactions).containsExactly("\uD83D\uDC4D")
        assertThat(afterRemove.entry.updatedAtMs).isNull()

        // After a TODO toggle sets updatedAtMs, adding a reaction must preserve that exact timestamp.
        graph.entryRepository.setTaskStatus(created.entry.id, TaskStatus.TODO, nowMs = 1_763_600_400_000L)
        val afterTaskToggle = graph.entryRepository.getEntry(created.entry.id)!!
        assertThat(afterTaskToggle.entry.updatedAtMs).isEqualTo(1_763_600_400_000L)
        graph.entryRepository.toggleReaction(created.entry.id, "\u2764\uFE0F", nowMs = 1_763_600_500_000L)
        val afterSecondReaction = graph.entryRepository.getEntry(created.entry.id)!!
        assertThat(afterSecondReaction.entry.updatedAtMs).isEqualTo(1_763_600_400_000L)
    }

    @Test
    fun createAndEditEntry_rejectUnmaskedPasswordsAndPins_allowXxxxAndPwd() = runTest {
        var createBlocked = false
        try {
            graph.entryRepository.createEntry(
                bodyRaw = "Wifi pw: Summer2026!",
                userTagIds = emptyList(),
                nowMs = 1_763_600_000_000L,
                zoneId = "UTC",
            )
        } catch (_: io.github.k8242.threadlog.core.parser.SensitiveCredentialException) {
            createBlocked = true
        }
        assertThat(createBlocked).isTrue()

        val allowed = graph.entryRepository.createEntry(
            bodyRaw = "Wifi pw: xxxx, ran pwd /home/user",
            userTagIds = emptyList(),
            nowMs = 1_763_600_000_000L,
            zoneId = "UTC",
        )
        assertThat(allowed.entry.bodyRaw).contains("xxxx")

        var editBlocked = false
        try {
            graph.entryRepository.editEntry(
                entryId = allowed.entry.id,
                newBodyRaw = "ATM pin is 4829",
                nowMs = 1_763_600_100_000L,
            )
        } catch (_: io.github.k8242.threadlog.core.parser.SensitiveCredentialException) {
            editBlocked = true
        }
        assertThat(editBlocked).isTrue()
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
    fun createEntry_autoResolvesInlineProjectPersonAndTopicTags_stripsTokensAndSupportsEscaping() = runTest {
        val created = graph.entryRepository.createEntry(
            bodyRaw = "Synced with @Lex and \\@literal on +journal-app about #ideas",
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
        assertThat(created.entry.bodyRaw).isEqualTo("Synced with and @literal on about")

        // Editing preserves literal @literal without turning it into a tag, while stripping newly added #followup.
        val edited = graph.entryRepository.editEntry(
            entryId = created.entry.id,
            newBodyRaw = "Synced with and @literal on about the release #followup",
            nowMs = 1_763_600_100_000L,
        )
        assertThat(edited.entry.bodyRaw).isEqualTo("Synced with and @literal on about the release")
        assertThat(edited.visibleTags.map { it.tag.fullPath }).containsExactly(
            "people/Lex",
            "projects/journal-app",
            "topics/ideas",
            "topics/followup",
        )
    }

    @Test
    fun taskPriority_autoPromotesNoteToTodo_resetsOnNone_andSortsAndFiltersTodos() = runTest {
        val base = 1_795_132_800_000L // 2026-11-20T00:00:00Z
        // Creating a regular note with HIGH priority enforces task-only priority -> forced to NONE.
        val plain = graph.entryRepository.createEntry(
            bodyRaw = "Plain note",
            userTagIds = emptyList(),
            nowMs = base + 1_000L,
            zoneId = "UTC",
            taskStatus = TaskStatus.NONE,
            taskPriority = io.github.k8242.threadlog.core.model.TaskPriority.HIGH,
        )
        assertThat(plain.entry.taskStatus).isEqualTo(TaskStatus.NONE)
        assertThat(plain.entry.taskPriority).isEqualTo(io.github.k8242.threadlog.core.model.TaskPriority.NONE)

        // Setting priority on a regular note auto-promotes it to TODO.
        graph.entryRepository.setTaskPriority(
            entryId = plain.entry.id,
            taskPriority = io.github.k8242.threadlog.core.model.TaskPriority.LOW,
            nowMs = base + 1_500L,
        )
        val promoted = graph.entryRepository.getEntry(plain.entry.id)!!
        assertThat(promoted.entry.taskStatus).isEqualTo(TaskStatus.TODO)
        assertThat(promoted.entry.taskPriority).isEqualTo(io.github.k8242.threadlog.core.model.TaskPriority.LOW)

        val highTask = graph.entryRepository.createEntry(
            bodyRaw = "Urgent fix",
            userTagIds = emptyList(),
            nowMs = base + 2_000L,
            zoneId = "UTC",
            taskStatus = TaskStatus.TODO,
            taskPriority = io.github.k8242.threadlog.core.model.TaskPriority.HIGH,
        )
        val medTask = graph.entryRepository.createEntry(
            bodyRaw = "Medium refactor",
            userTagIds = emptyList(),
            nowMs = base + 3_000L,
            zoneId = "UTC",
            taskStatus = TaskStatus.TODO,
            taskPriority = io.github.k8242.threadlog.core.model.TaskPriority.MEDIUM,
        )
        val noneTask = graph.entryRepository.createEntry(
            bodyRaw = "No priority newest",
            userTagIds = emptyList(),
            nowMs = base + 4_000L,
            zoneId = "UTC",
            taskStatus = TaskStatus.TODO,
            taskPriority = io.github.k8242.threadlog.core.model.TaskPriority.NONE,
        )

        val byNewest = graph.entryRepository.observePagedEntries(
            ChatFeedFilter.Todos(
                mode = TodoFilterMode.OPEN,
                sortOrder = io.github.k8242.threadlog.core.model.TodoSortOrder.NEWEST,
            ),
        ).asSnapshot()
        assertThat(byNewest.map { it.entry.id }).containsExactly(
            noneTask.entry.id,
            medTask.entry.id,
            highTask.entry.id,
            plain.entry.id,
        ).inOrder()

        val byPriorityFilter = ChatFeedFilter.Todos(
            mode = TodoFilterMode.OPEN,
            sortOrder = io.github.k8242.threadlog.core.model.TodoSortOrder.PRIORITY,
        )
        val byPriority = graph.entryRepository.observePagedEntries(byPriorityFilter).asSnapshot()
        assertThat(byPriority.map { it.entry.id }).containsExactly(
            highTask.entry.id,
            medTask.entry.id,
            plain.entry.id,
            noneTask.entry.id,
        ).inOrder()

        // Verify findEntryOffset matches priority sort order exactly.
        assertThat(graph.entryRepository.findEntryOffset(highTask.entry.id, byPriorityFilter)).isEqualTo(0)
        assertThat(graph.entryRepository.findEntryOffset(medTask.entry.id, byPriorityFilter)).isEqualTo(1)
        assertThat(graph.entryRepository.findEntryOffset(plain.entry.id, byPriorityFilter)).isEqualTo(2)
        assertThat(graph.entryRepository.findEntryOffset(noneTask.entry.id, byPriorityFilter)).isEqualTo(3)

        // Filter by HIGH priority only.
        val highOnly = graph.entryRepository.observePagedEntries(
            ChatFeedFilter.Todos(
                mode = TodoFilterMode.OPEN,
                priorityFilter = io.github.k8242.threadlog.core.model.TaskPriority.HIGH,
            ),
        ).asSnapshot()
        assertThat(highOnly.map { it.entry.id }).containsExactly(highTask.entry.id)

        // Removing TODO status resets priority back to NONE.
        graph.entryRepository.setTaskStatus(highTask.entry.id, TaskStatus.NONE, nowMs = base + 5_000L)
        val demoted = graph.entryRepository.getEntry(highTask.entry.id)!!
        assertThat(demoted.entry.taskStatus).isEqualTo(TaskStatus.NONE)
        assertThat(demoted.entry.taskPriority).isEqualTo(io.github.k8242.threadlog.core.model.TaskPriority.NONE)

        // Compact summaries & calendar day counts surface task status & priority.
        val summaries = graph.entryRepository.observeCompactSummaries().first()
        val medSummary = summaries.first { it.id == medTask.entry.id }
        assertThat(medSummary.taskStatus).isEqualTo(TaskStatus.TODO)
        assertThat(medSummary.taskPriority).isEqualTo(io.github.k8242.threadlog.core.model.TaskPriority.MEDIUM)
        assertThat(medSummary.isTask).isTrue()

        val dayCounts = graph.entryRepository.observeCalendarDayCounts("2026-11").first()
        assertThat(dayCounts.single().openTodoCount).isEqualTo(3)
        assertThat(dayCounts.single().completedTodoCount).isEqualTo(0)
    }

    @Test
    fun highlightAndArchive_preserveEditedStatus_andFilterAllViewsAndSearch() = runTest {
        val base = 1_795_132_800_000L
        val tag = graph.tagRepository.createOrGetTagByPath("topics/reading")
        val note = graph.entryRepository.createEntry(
            bodyRaw = "Fascinating quantum paper",
            userTagIds = listOf(tag.id),
            nowMs = base + 1_000L,
            zoneId = "UTC",
            taskStatus = TaskStatus.TODO,
        )
        val initialHash = note.entry.contentHash

        // Highlighting preserves updatedAtMs (not Edited) and updates contentHash.
        graph.entryRepository.setHighlighted(note.entry.id, true)
        val highlighted = graph.entryRepository.getEntry(note.entry.id)!!
        assertThat(highlighted.entry.isHighlighted).isTrue()
        assertThat(highlighted.entry.updatedAtMs).isNull()
        assertThat(highlighted.entry.isEdited).isFalse()
        assertThat(highlighted.entry.contentHash).isNotEqualTo(initialHash)
        assertThat(graph.entryRepository.observeCompactSummaries().first().single().isHighlighted).isTrue()

        // Archiving hides from All, Todos, TagSubtree, CompactSummaries, Calendar, Search (default), Drawer active count.
        graph.entryRepository.setArchived(note.entry.id, true, nowMs = base + 2_000L)
        val archived = graph.entryRepository.getEntry(note.entry.id)!!
        assertThat(archived.entry.isArchived).isTrue()
        assertThat(archived.entry.updatedAtMs).isNull()

        assertThat(graph.entryRepository.observePagedEntries(ChatFeedFilter.All).asSnapshot()).isEmpty()
        assertThat(graph.entryRepository.observePagedEntries(ChatFeedFilter.Todos(TodoFilterMode.ALL)).asSnapshot()).isEmpty()
        assertThat(
            graph.entryRepository.observePagedEntries(
                ChatFeedFilter.TagSubtree(tag.id, tag.fullPath, "reading"),
            ).asSnapshot(),
        ).isEmpty()
        assertThat(graph.entryRepository.observeCompactSummaries().first()).isEmpty()
        assertThat(graph.entryRepository.observeCalendarDayCounts("2026-11").first()).isEmpty()
        assertThat(graph.searchRepository.search("quantum", nowMs = base + 3_000L).tierARecent).isEmpty()

        // Visible in Archived feed, drawer archivedCount, and search with includeArchived = true.
        val archivedFeed = graph.entryRepository.observePagedEntries(ChatFeedFilter.Archived).asSnapshot()
        assertThat(archivedFeed.map { it.entry.id }).containsExactly(note.entry.id)
        val overview = graph.entryRepository.observeDrawerOverview().first()
        assertThat(overview.totalEntryCount).isEqualTo(0)
        assertThat(overview.archivedCount).isEqualTo(1)

        val archivedSearch = graph.searchRepository.search(
            query = "quantum",
            includeHiddenAutoTags = true,
            includeArchived = true,
            nowMs = base + 3_000L,
        )
        assertThat(archivedSearch.tierARecent.map { it.entryId }).containsExactly(note.entry.id)
        assertThat(graph.entryRepository.findEntryOffset(note.entry.id, ChatFeedFilter.All)).isNull()
        assertThat(graph.entryRepository.findEntryOffset(note.entry.id, ChatFeedFilter.Archived)).isEqualTo(0)

        // Unarchiving restores it everywhere.
        graph.entryRepository.setArchived(note.entry.id, false, nowMs = base + 4_000L)
        assertThat(graph.entryRepository.observePagedEntries(ChatFeedFilter.All).asSnapshot().map { it.entry.id })
            .containsExactly(note.entry.id)
        assertThat(graph.entryRepository.observeDrawerOverview().first().archivedCount).isEqualTo(0)
    }

    @Test
    fun hiddenPlaceholderMessages_hiddenByDefault_shownWhenToggled_andUnhiddenWhenPhotoAttached() = runTest {
        val base = 1_795_132_800_000L
        val normal = graph.entryRepository.createEntry(
            bodyRaw = "Real journal note",
            userTagIds = emptyList(),
            nowMs = base + 1_000L,
            zoneId = "UTC",
        )
        val omitted = graph.entryRepository.createEntry(
            bodyRaw = "Alice: \u200E<Media omitted>",
            userTagIds = emptyList(),
            nowMs = base + 2_000L,
            zoneId = "UTC",
        )
        val deletedPlaceholder = graph.entryRepository.createEntry(
            bodyRaw = "You deleted this message.",
            userTagIds = emptyList(),
            nowMs = base + 3_000L,
            zoneId = "UTC",
        )

        // Hidden by default in All, CompactSummaries, Search, and DrawerOverview.
        val defaultAll = graph.entryRepository.observePagedEntries(ChatFeedFilter.All, includeHiddenPlaceholders = false)
            .asSnapshot()
        assertThat(defaultAll.map { it.entry.id }).containsExactly(normal.entry.id)
        assertThat(graph.entryRepository.observeCompactSummaries().first().map { it.id })
            .containsExactly(normal.entry.id)
        assertThat(graph.searchRepository.search("omitted", nowMs = base + 4_000L).tierARecent).isEmpty()
        assertThat(graph.entryRepository.observeDrawerOverview().first().totalEntryCount).isEqualTo(1)
        assertThat(graph.entryRepository.findEntryOffset(omitted.entry.id, ChatFeedFilter.All, false)).isNull()

        // Visible when includeHiddenPlaceholders = true in All Notes.
        val withHidden = graph.entryRepository.observePagedEntries(ChatFeedFilter.All, includeHiddenPlaceholders = true)
            .asSnapshot()
        assertThat(withHidden.map { it.entry.id }).containsExactly(
            deletedPlaceholder.entry.id,
            omitted.entry.id,
            normal.entry.id,
        ).inOrder()
        assertThat(graph.entryRepository.findEntryOffset(omitted.entry.id, ChatFeedFilter.All, true)).isEqualTo(1)

        // Attaching a real photo to <Media omitted> via editEntry makes it visible everywhere!
        val att1 = io.github.k8242.threadlog.core.model.contract.PendingAttachment(
            localFilename = "media/restored.webp",
            mimeType = "image/webp",
            byteSize = 12_000L,
            width = 800,
            height = 600,
            sha256 = "sha-restored-1",
        )
        val editedWithPhoto = graph.entryRepository.editEntry(
            entryId = omitted.entry.id,
            newBodyRaw = omitted.entry.bodyRaw,
            nowMs = base + 5_000L,
            addedAttachments = listOf(att1),
        )
        assertThat(editedWithPhoto.attachments).hasSize(1)
        val afterAttachAll = graph.entryRepository.observePagedEntries(ChatFeedFilter.All, includeHiddenPlaceholders = false)
            .asSnapshot()
        assertThat(afterAttachAll.map { it.entry.id }).containsExactly(omitted.entry.id, normal.entry.id).inOrder()
        assertThat(graph.searchRepository.search("omitted", nowMs = base + 6_000L).tierARecent.map { it.entryId })
            .containsExactly(omitted.entry.id)

        // Removing the attachment and adding a second one reconciles attachments cleanly.
        val att2 = io.github.k8242.threadlog.core.model.contract.PendingAttachment(
            localFilename = "media/second.webp",
            mimeType = "image/webp",
            byteSize = 8_000L,
            width = 640,
            height = 480,
            sha256 = "sha-second-2",
        )
        val swapped = graph.entryRepository.editEntry(
            entryId = omitted.entry.id,
            newBodyRaw = "Restored photo caption",
            nowMs = base + 7_000L,
            addedAttachments = listOf(att2, att2), // duplicate sha256 deduped
            removedAttachmentIds = setOf(editedWithPhoto.attachments.single().id),
        )
        assertThat(swapped.attachments).hasSize(1)
        assertThat(swapped.attachments.single().sha256).isEqualTo("sha-second-2")
    }

    @Test
    fun updateEntryMetadata_recalculatesLocalDateAndPreservesEditedStatus_andTimelineBucketsMatchOffsets() = runTest {
        val day1 = java.time.Instant.parse("2026-11-18T10:00:00Z").toEpochMilli()
        val day2a = java.time.Instant.parse("2026-11-20T09:00:00Z").toEpochMilli()
        val day2b = java.time.Instant.parse("2026-11-20T18:00:00Z").toEpochMilli()

        val e1 = graph.entryRepository.createEntry("Day 1 note", emptyList(), nowMs = day1, zoneId = "UTC")
        val e2 = graph.entryRepository.createEntry("Day 2 morning", emptyList(), nowMs = day2a, zoneId = "UTC")
        val e3 = graph.entryRepository.createEntry("Day 2 evening", emptyList(), nowMs = day2b, zoneId = "UTC")

        val buckets = graph.entryRepository.observeTimelineDateBuckets(ChatFeedFilter.All).first()
        assertThat(buckets).containsExactly(
            io.github.k8242.threadlog.core.model.TimelineDateBucket("2026-11-20", 2),
            io.github.k8242.threadlog.core.model.TimelineDateBucket("2026-11-18", 1),
        ).inOrder()

        assertThat(graph.entryRepository.findDateOffset("2026-11-20", ChatFeedFilter.All)).isEqualTo(0)
        assertThat(graph.entryRepository.findDateOffset("2026-11-19", ChatFeedFilter.All)).isEqualTo(2)
        assertThat(graph.entryRepository.findDateOffset("2026-11-18", ChatFeedFilter.All)).isEqualTo(2)
        assertThat(graph.entryRepository.findDateOffset("2026-11-10", ChatFeedFilter.All)).isNull()

        // Change timezone of e3 to Pacific/Kiritimati (+14:00) so 2026-11-20T18:00:00Z becomes 2026-11-21!
        // Because task status/priority did not change, updatedAtMs MUST remain null (not marked Edited).
        graph.entryRepository.updateEntryMetadata(
            entryId = e3.entry.id,
            createdAtMs = day2b,
            zoneId = "Pacific/Kiritimati",
            taskStatus = TaskStatus.NONE,
            taskPriority = io.github.k8242.threadlog.core.model.TaskPriority.HIGH, // forced to NONE for non-task
            isHighlighted = true,
            archivedAtMs = null,
            nowMs = day2b + 5_000L,
        )
        val updated = graph.entryRepository.getEntry(e3.entry.id)!!
        assertThat(updated.entry.zoneId).isEqualTo("Pacific/Kiritimati")
        assertThat(updated.entry.localDate).isEqualTo("2026-11-21")
        assertThat(updated.entry.taskPriority).isEqualTo(io.github.k8242.threadlog.core.model.TaskPriority.NONE)
        assertThat(updated.entry.isHighlighted).isTrue()
        assertThat(updated.entry.updatedAtMs).isNull()
        assertThat(updated.entry.isEdited).isFalse()

        val updatedBuckets = graph.entryRepository.observeTimelineDateBuckets(ChatFeedFilter.All).first()
        assertThat(updatedBuckets.map { it.localDate to it.entryCount }).containsExactly(
            "2026-11-21" to 1,
            "2026-11-20" to 1,
            "2026-11-18" to 1,
        ).inOrder()
        assertThat(e1.entry.id).isNotEmpty()
        assertThat(e2.entry.id).isNotEmpty()
    }
}
