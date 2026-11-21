package io.github.k8242.threadlog.core.data

import androidx.paging.testing.asSnapshot
import com.google.common.truth.Truth.assertThat
import io.github.k8242.threadlog.core.model.AutoTagRule
import io.github.k8242.threadlog.core.model.ContextWindowState
import io.github.k8242.threadlog.core.model.RuleMatchType
import io.github.k8242.threadlog.core.model.TagRootCategory
import io.github.k8242.threadlog.core.model.TagState
import io.github.k8242.threadlog.core.model.contract.PendingAttachment
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

class EntryAndTagRepositoryTest {

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
    fun createAndEditEntry_preservesCreatedAtAndZoneId_andReconcilesBookmarks() = runTest {
        val lexTag = graph.tagRepository.createOrGetTagByPath("people/friends/Lex")
        val t0 = Instant.parse("2026-11-19T16:15:00Z").toEpochMilli() // 08:15 AM America/Los_Angeles

        val created = graph.entryRepository.createEntry(
            bodyRaw = "*Morning* notes with Lex: https://example.com/keep and https://old.example.org/drop",
            userTagIds = listOf(lexTag.id),
            pendingAttachments = listOf(
                PendingAttachment(
                    localFilename = "media/pic1.webp",
                    mimeType = "image/webp",
                    byteSize = 120_000L,
                    width = 1280,
                    height = 720,
                    sha256 = "sha-pic1",
                ),
            ),
            nowMs = t0,
            zoneId = "America/Los_Angeles",
        )

        assertThat(created.entry.createdAtMs).isEqualTo(t0)
        assertThat(created.entry.zoneId).isEqualTo("America/Los_Angeles")
        assertThat(created.entry.localDate).isEqualTo("2026-11-19")
        assertThat(created.entry.isoWeek).isEqualTo("2026-W47")
        assertThat(created.entry.bodyPlain).isEqualTo(
            "Morning notes with Lex: https://example.com/keep and https://old.example.org/drop",
        )
        assertThat(created.entry.isEdited).isFalse()
        assertThat(created.entry.contentHash).hasLength(64)
        assertThat(created.visibleTags.map { it.tag.fullPath }).containsExactly("people/friends/Lex")
        assertThat(created.attachments).hasSize(1)
        assertThat(created.bookmarks).hasSize(2)
        assertThat(created.bookmarks.all { it.isCollapsed }).isTrue()

        // Enrich kept bookmark with title and expanded state before editing.
        val keptBookmark = created.bookmarks.first { it.urlRaw == "https://example.com/keep" }
        graph.bookmarkRepository.updateBookmarkTitle(keptBookmark.id, "Kept Page Title")
        graph.bookmarkRepository.setCollapsed(keptBookmark.id, collapsed = false)

        val missingBefore = graph.bookmarkRepository.getBookmarksMissingTitle(created.entry.id)
        assertThat(missingBefore.map { it.urlRaw }).containsExactly("https://old.example.org/drop")

        // Edit entry even with an earlier/equal clock to verify monotonic updatedAtMs >= createdAtMs + 1.
        val edited = graph.entryRepository.editEntry(
            entryId = created.entry.id,
            newBodyRaw = "Updated notes: https://example.com/keep plus https://new.example.net/added",
            nowMs = t0,
        )

        assertThat(edited.entry.createdAtMs).isEqualTo(t0)
        assertThat(edited.entry.zoneId).isEqualTo("America/Los_Angeles")
        assertThat(edited.entry.localDate).isEqualTo("2026-11-19")
        assertThat(edited.entry.isoWeek).isEqualTo("2026-W47")
        assertThat(edited.entry.updatedAtMs).isEqualTo(t0 + 1L)
        assertThat(edited.entry.isEdited).isTrue()
        assertThat(edited.entry.contentHash).isNotEqualTo(created.entry.contentHash)

        // Kept bookmark preserved its id, title, and collapsed=false; old dropped; new added collapsed=true.
        val byUrl = edited.bookmarks.associateBy { it.urlRaw }
        assertThat(byUrl.keys).containsExactly(
            "https://example.com/keep",
            "https://new.example.net/added",
        )
        assertThat(byUrl.getValue("https://example.com/keep").id).isEqualTo(keptBookmark.id)
        assertThat(byUrl.getValue("https://example.com/keep").title).isEqualTo("Kept Page Title")
        assertThat(byUrl.getValue("https://example.com/keep").isCollapsed).isFalse()
        assertThat(byUrl.getValue("https://new.example.net/added").title).isNull()
        assertThat(byUrl.getValue("https://new.example.net/added").isCollapsed).isTrue()
    }

    @Test
    fun softDeleteUndo_andReplyPreviewReflectsActiveAndDeletedParent() = runTest {
        val t1 = 1_763_568_000_000L
        val t2 = t1 + 60_000L

        val parent = graph.entryRepository.createEntry(
            bodyRaw = "Original parent message",
            userTagIds = emptyList(),
            nowMs = t1,
            zoneId = "UTC",
        )
        val child = graph.entryRepository.createEntry(
            bodyRaw = "Follow-up reply",
            userTagIds = emptyList(),
            replyToId = parent.entry.id,
            nowMs = t2,
            zoneId = "UTC",
        )

        assertThat(child.replyPreview).isNotNull()
        assertThat(child.replyPreview!!.entryId).isEqualTo(parent.entry.id)
        assertThat(child.replyPreview!!.snippet).isEqualTo("Original parent message")
        assertThat(child.replyPreview!!.isDeleted).isFalse()

        // Verify paged feed has both entries newest-first.
        val pagedBefore = graph.entryRepository.observePagedEntries().asSnapshot()
        assertThat(pagedBefore.map { it.entry.id }).containsExactly(child.entry.id, parent.entry.id).inOrder()

        // Soft-delete parent -> disappears from paged feed and child's replyPreview marks isDeleted = true.
        graph.entryRepository.softDeleteEntry(parent.entry.id, nowMs = t2 + 1_000L)

        val pagedAfterDelete = graph.entryRepository.observePagedEntries().asSnapshot()
        assertThat(pagedAfterDelete.map { it.entry.id }).containsExactly(child.entry.id)

        val childAfterParentDelete = graph.entryRepository.getEntry(child.entry.id)!!
        assertThat(childAfterParentDelete.replyPreview).isNotNull()
        assertThat(childAfterParentDelete.replyPreview!!.isDeleted).isTrue()

        // Undo soft-delete -> parent returns and replyPreview is active again.
        graph.entryRepository.undoSoftDelete(parent.entry.id)

        val pagedAfterUndo = graph.entryRepository.observePagedEntries().asSnapshot()
        assertThat(pagedAfterUndo.map { it.entry.id }).containsExactly(child.entry.id, parent.entry.id).inOrder()

        val childAfterUndo = graph.entryRepository.getEntry(child.entry.id)!!
        assertThat(childAfterUndo.replyPreview!!.isDeleted).isFalse()
        assertThat(childAfterUndo.replyPreview!!.snippet).isEqualTo("Original parent message")
    }

    @Test
    fun hierarchicalTags_createParentChain_renameCascadesDescendants_andDeleteCleansUp() = runTest {
        val lex = graph.tagRepository.createOrGetTagByPath(
            fullPath = " /people//friends/Lex/ ",
            aliases = listOf("Alexis", "Lexi"),
        )
        val sam = graph.tagRepository.createOrGetTagByPath("people/friends/Sam")

        val allTags = graph.tagRepository.getAllTags().associateBy { it.fullPath }
        assertThat(allTags.keys).containsExactly(
            "people",
            "people/friends",
            "people/friends/Lex",
            "people/friends/Sam",
        )
        val peopleTag = allTags.getValue("people")
        val friendsTag = allTags.getValue("people/friends")
        assertThat(peopleTag.parentId).isNull()
        assertThat(friendsTag.parentId).isEqualTo(peopleTag.id)
        assertThat(lex.parentId).isEqualTo(friendsTag.id)
        assertThat(lex.leafName).isEqualTo("Lex")
        assertThat(lex.rootCategory).isEqualTo(TagRootCategory.PEOPLE)
        assertThat(lex.colorToken).isEqualTo("people")
        assertThat(lex.aliases).containsExactly("Alexis", "Lexi")

        // Attach Lex to an entry so we can verify FTS5 reindex after rename.
        val entry = graph.entryRepository.createEntry(
            bodyRaw = "Dinner downtown",
            userTagIds = listOf(lex.id),
            nowMs = 1_763_568_000_000L,
            zoneId = "UTC",
        )

        // Move "people/friends" -> "projects/crew" and verify rootCategory/colorToken cascade.
        graph.tagRepository.renameOrMoveTag(friendsTag.id, "projects/crew")

        val afterMove = graph.tagRepository.getAllTags().associateBy { it.id }
        val movedFriends = afterMove.getValue(friendsTag.id)
        val movedLex = afterMove.getValue(lex.id)
        val movedSam = afterMove.getValue(sam.id)

        assertThat(movedFriends.fullPath).isEqualTo("projects/crew")
        assertThat(movedFriends.leafName).isEqualTo("crew")
        assertThat(movedFriends.rootCategory).isEqualTo(TagRootCategory.PROJECTS)
        assertThat(movedFriends.colorToken).isEqualTo("projects")

        assertThat(movedLex.fullPath).isEqualTo("projects/crew/Lex")
        assertThat(movedLex.parentId).isEqualTo(friendsTag.id)
        assertThat(movedLex.rootCategory).isEqualTo(TagRootCategory.PROJECTS)
        assertThat(movedLex.colorToken).isEqualTo("projects")

        assertThat(movedSam.fullPath).isEqualTo("projects/crew/Sam")
        assertThat(movedSam.parentId).isEqualTo(friendsTag.id)

        // Entry now searchable by new path "crew" in FTS5 and retains its tag link.
        val searchAfterRename = graph.searchRepository.search("crew", nowMs = 1_763_568_000_000L)
        assertThat(searchAfterRename.tierARecent.map { it.entryId }).containsExactly(entry.entry.id)

        // Updating aliases preserves entry_tags and reindexes FTS5 immediately.
        graph.tagRepository.updateAliases(lex.id, listOf("SuperLex"))
        val searchByAlias = graph.searchRepository.search("SuperLex", nowMs = 1_763_568_000_000L)
        assertThat(searchByAlias.tierARecent.map { it.entryId }).containsExactly(entry.entry.id)

        // Delete subtree and verify entry no longer has the deleted tag.
        graph.tagRepository.deleteTag(friendsTag.id)
        val remainingPaths = graph.tagRepository.getAllTags().map { it.fullPath }
        assertThat(remainingPaths).containsExactly("people", "projects")
        assertThat(graph.entryRepository.getEntry(entry.entry.id)!!.tags).isEmpty()
    }

    @Test
    fun autoTagLifecycle_respectsRejectedUserAndPromotedTombstones() = runTest {
        val nowMs = 1_763_568_000_000L
        val userTag = graph.tagRepository.createOrGetTagByPath("topics/kotlin")
        val entry = graph.entryRepository.createEntry(
            bodyRaw = "Working on Kotlin coroutines and SQLite FTS5",
            userTagIds = listOf(userTag.id),
            nowMs = nowMs,
            zoneId = "UTC",
        )

        // Apply auto-tags: kotlin (already USER), sqlite (new AUTO_HIDDEN), coroutines (new AUTO_HIDDEN).
        graph.tagRepository.applyAutoTags(
            entryId = entry.entry.id,
            suggestions = listOf(
                "topics/kotlin" to 0.7f,
                "topics/sqlite" to 0.9f,
                "topics/coroutines" to 0.85f,
            ),
            nowMs = nowMs + 100L,
        )

        val afterFirstAuto = graph.entryRepository.getEntry(entry.entry.id)!!
        val statesByPath1 = afterFirstAuto.tags.associate { it.tag.fullPath to it.state }
        assertThat(statesByPath1).containsExactly(
            "topics/kotlin", TagState.USER,
            "topics/sqlite", TagState.AUTO_HIDDEN,
            "topics/coroutines", TagState.AUTO_HIDDEN,
        )

        // Pending auto-tags flow should contain this entry.
        val pending1 = graph.entryRepository.observeEntriesWithPendingAutoTags().first()
        assertThat(pending1.map { it.entry.id }).containsExactly(entry.entry.id)

        // Promote sqlite, reject coroutines.
        val sqliteTagId = afterFirstAuto.tags.first { it.tag.fullPath == "topics/sqlite" }.tagId
        val coroutinesTagId = afterFirstAuto.tags.first { it.tag.fullPath == "topics/coroutines" }.tagId

        graph.tagRepository.promoteAutoTag(entry.entry.id, sqliteTagId, nowMs = nowMs + 200L)
        graph.tagRepository.rejectAutoTag(entry.entry.id, coroutinesTagId, nowMs = nowMs + 300L)

        // No more pending AUTO_HIDDEN tags on this entry.
        val pending2 = graph.entryRepository.observeEntriesWithPendingAutoTags().first()
        assertThat(pending2).isEmpty()

        // Re-running auto-tagger MUST NOT overwrite USER, AUTO_PROMOTED, or AUTO_REJECTED!
        graph.tagRepository.applyAutoTags(
            entryId = entry.entry.id,
            suggestions = listOf(
                "topics/kotlin" to 0.5f,
                "topics/sqlite" to 0.6f,
                "topics/coroutines" to 0.99f,
                "projects/threadlog" to 0.88f,
            ),
            nowMs = nowMs + 400L,
        )

        val afterSecondAuto = graph.entryRepository.getEntry(entry.entry.id)!!
        val statesByPath2 = afterSecondAuto.tags.associate { it.tag.fullPath to it.state }
        assertThat(statesByPath2).containsExactly(
            "topics/kotlin", TagState.USER,
            "topics/sqlite", TagState.AUTO_PROMOTED,
            "topics/coroutines", TagState.AUTO_REJECTED,
            "projects/threadlog", TagState.AUTO_HIDDEN,
        )
    }

    @Test
    fun compactSummaries_truncatesAt140Chars_andComputesFlags() = runTest {
        val longBody = "A".repeat(200)
        val t1 = 1_763_568_000_000L
        val t2 = t1 + 10_000L

        val entry1 = graph.entryRepository.createEntry(
            bodyRaw = longBody,
            userTagIds = emptyList(),
            pendingAttachments = listOf(
                PendingAttachment("media/a.webp", "image/webp", 5000L, 100, 100, "sha-a"),
            ),
            nowMs = t1,
            zoneId = "UTC",
        )
        val entry2 = graph.entryRepository.createEntry(
            bodyRaw = "Check https://kotlinlang.org\n```kotlin\nprintln(1)\n```",
            userTagIds = emptyList(),
            nowMs = t2,
            zoneId = "UTC",
        )

        val summaries = graph.entryRepository.observeCompactSummaries().first()
        assertThat(summaries.map { it.id }).containsExactly(entry2.entry.id, entry1.entry.id).inOrder()

        val s2 = summaries[0]
        assertThat(s2.hasAttachments).isFalse()
        assertThat(s2.hasBookmarks).isTrue()
        assertThat(s2.hasCodeBlock).isTrue()

        val s1 = summaries[1]
        assertThat(s1.preview140).hasLength(140)
        assertThat(s1.preview140).isEqualTo("A".repeat(140))
        assertThat(s1.hasAttachments).isTrue()
        assertThat(s1.hasBookmarks).isFalse()
        assertThat(s1.hasCodeBlock).isFalse()
    }

    @Test
    fun calendarDayCounts_andEntriesForDate_supportTagSubtreePrefixFilter() = runTest {
        val lex = graph.tagRepository.createOrGetTagByPath("people/friends/Lex")
        val alex = graph.tagRepository.createOrGetTagByPath("people/work/Alex")
        val al = graph.tagRepository.createOrGetTagByPath("people/work/Al")
        val journal = graph.tagRepository.createOrGetTagByPath("projects/journal")

        val nov19Morning = Instant.parse("2026-11-19T10:00:00Z").toEpochMilli()
        val nov19Noon = Instant.parse("2026-11-19T12:00:00Z").toEpochMilli()
        val nov20Morning = Instant.parse("2026-11-20T09:00:00Z").toEpochMilli()

        // Entry with TWO tags under people/ should still only count ONCE for "people" filter!
        val e1 = graph.entryRepository.createEntry(
            bodyRaw = "Lunch with Lex and Alex",
            userTagIds = listOf(lex.id, alex.id),
            nowMs = nov19Morning,
            zoneId = "UTC",
        )
        val e2 = graph.entryRepository.createEntry(
            bodyRaw = "Standalone journal work",
            userTagIds = listOf(journal.id),
            nowMs = nov19Noon,
            zoneId = "UTC",
        )
        val e3 = graph.entryRepository.createEntry(
            bodyRaw = "Chat with Al",
            userTagIds = listOf(al.id),
            nowMs = nov20Morning,
            zoneId = "UTC",
        )

        val allCounts = graph.entryRepository.observeCalendarDayCounts("2026-11").first()
        assertThat(allCounts.associate { it.localDate to it.entryCount }).containsExactly(
            "2026-11-19", 2,
            "2026-11-20", 1,
        )

        val peopleCounts = graph.entryRepository.observeCalendarDayCounts("2026-11", "people").first()
        assertThat(peopleCounts.associate { it.localDate to it.entryCount }).containsExactly(
            "2026-11-19", 1,
            "2026-11-20", 1,
        )

        // Subtree boundary check: "people/work/Al" must NOT match "people/work/Alex".
        val alCounts = graph.entryRepository.observeCalendarDayCounts("2026-11", "people/work/Al").first()
        assertThat(alCounts.associate { it.localDate to it.entryCount }).containsExactly(
            "2026-11-20", 1,
        )

        val nov19All = graph.entryRepository.observeEntriesForDate("2026-11-19").first()
        assertThat(nov19All.map { it.entry.id }).containsExactly(e1.entry.id, e2.entry.id).inOrder()

        val nov19Projects = graph.entryRepository.observeEntriesForDate("2026-11-19", "projects").first()
        assertThat(nov19Projects.map { it.entry.id }).containsExactly(e2.entry.id)
        assertThat(e3.entry.localDate).isEqualTo("2026-11-20")
    }

    @Test
    fun contextWindow_returnsChronologicalEntriesWithinPlusMinus12Hours() = runTest {
        val anchorMs = Instant.parse("2026-11-19T12:00:00Z").toEpochMilli()
        val halfWindow = ContextWindowState.DEFAULT_HALF_WINDOW_MS

        val tooOld = graph.entryRepository.createEntry(
            bodyRaw = "Too old",
            userTagIds = emptyList(),
            nowMs = anchorMs - halfWindow - 1_000L,
            zoneId = "UTC",
        )
        val inBefore = graph.entryRepository.createEntry(
            bodyRaw = "Six hours before",
            userTagIds = emptyList(),
            nowMs = anchorMs - (6L * 3600L * 1000L),
            zoneId = "UTC",
        )
        val anchor = graph.entryRepository.createEntry(
            bodyRaw = "Anchor entry",
            userTagIds = emptyList(),
            nowMs = anchorMs,
            zoneId = "UTC",
        )
        val inAfter = graph.entryRepository.createEntry(
            bodyRaw = "Twelve hours after (inclusive boundary)",
            userTagIds = emptyList(),
            nowMs = anchorMs + halfWindow,
            zoneId = "UTC",
        )
        val tooNew = graph.entryRepository.createEntry(
            bodyRaw = "Too new",
            userTagIds = emptyList(),
            nowMs = anchorMs + halfWindow + 1_000L,
            zoneId = "UTC",
        )

        val window = graph.entryRepository.observeContextWindow(
            startMs = anchorMs - halfWindow,
            endMs = anchorMs + halfWindow,
        ).first()

        assertThat(window.map { it.entry.id }).containsExactly(
            inBefore.entry.id,
            anchor.entry.id,
            inAfter.entry.id,
        ).inOrder()
        assertThat(window.map { it.entry.id }).doesNotContain(tooOld.entry.id)
        assertThat(window.map { it.entry.id }).doesNotContain(tooNew.entry.id)
    }

    @Test
    fun autoTagRuleRepository_crudAndEnabledFilter() = runTest {
        val r1 = AutoTagRule(
            id = "rule-1",
            pattern = "github.com",
            matchType = RuleMatchType.DOMAIN,
            targetTagPath = "projects/code",
            confidence = 0.9f,
            enabled = true,
        )
        val r2 = AutoTagRule(
            id = "rule-2",
            pattern = "coffee",
            matchType = RuleMatchType.KEYWORD,
            targetTagPath = "topics/coffee",
            confidence = 0.8f,
            enabled = false,
        )

        graph.autoTagRuleRepository.upsertRule(r1)
        graph.autoTagRuleRepository.upsertRule(r2)

        val observed = graph.autoTagRuleRepository.observeRules().first()
        assertThat(observed).containsExactly(r1, r2)

        val enabled = graph.autoTagRuleRepository.getEnabledRules()
        assertThat(enabled).containsExactly(r1)

        graph.autoTagRuleRepository.deleteRule("rule-1")
        assertThat(graph.autoTagRuleRepository.observeRules().first()).containsExactly(r2)
    }
}
