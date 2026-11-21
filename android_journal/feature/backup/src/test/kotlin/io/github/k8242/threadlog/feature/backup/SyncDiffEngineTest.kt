// Copyright (c) 2026 k8242
// SPDX-License-Identifier: MIT

package io.github.k8242.threadlog.feature.backup

import com.google.common.truth.Truth.assertThat
import io.github.k8242.threadlog.core.model.Attachment
import io.github.k8242.threadlog.core.model.BackupFormat
import io.github.k8242.threadlog.core.model.BackupWorkbook
import io.github.k8242.threadlog.core.model.Bookmark
import io.github.k8242.threadlog.core.model.DiffChangeType
import io.github.k8242.threadlog.core.model.Entry
import io.github.k8242.threadlog.core.model.EntryTag
import io.github.k8242.threadlog.core.model.EntryWithDetails
import io.github.k8242.threadlog.core.model.SheetEntryRow
import io.github.k8242.threadlog.core.model.SheetTagRow
import io.github.k8242.threadlog.core.model.SyncMetaRecord
import io.github.k8242.threadlog.core.model.Tag
import io.github.k8242.threadlog.core.model.TagRootCategory
import io.github.k8242.threadlog.core.model.TagState
import io.github.k8242.threadlog.feature.backup.sync.SyncDiffEngine
import io.github.k8242.threadlog.feature.backup.sync.WorkbookMapper
import org.junit.Test

class SyncDiffEngineTest {

    private val diffEngine = SyncDiffEngine()

    @Test
    fun workbookMapper_localAndRemoteContentHashesMatchExactly() {
        val local = sampleEntry(
            id = "e-1",
            bodyRaw = "Met #Lex about https://example.com\nLine 2",
            visibleTagPaths = listOf("people/friends/Lex", "projects/journal"),
            hiddenTagPaths = listOf("auto/meeting"),
            rejectedTagPaths = listOf("topics/noise"),
            bookmarks = listOf(
                Bookmark(
                    id = "b-1",
                    entryId = "e-1",
                    urlRaw = "https://example.com",
                    domain = "example.com",
                    title = "Example Site",
                ),
            ),
            attachments = listOf(
                Attachment(
                    id = "a-1",
                    entryId = "e-1",
                    localFilename = "media/pic1.webp",
                    mimeType = "image/webp",
                    byteSize = 1024,
                    width = 800,
                    height = 600,
                    sha256 = "abcd1234",
                    driveFileId = "drv-123",
                ),
            ),
        )

        val sheetRow = WorkbookMapper.toSheetEntryRow(local)
        val localHash = WorkbookMapper.computeLocalEntrySyncHash(local)
        val remoteHash = WorkbookMapper.computeRemoteRowContentHash(sheetRow)

        assertThat(sheetRow.createdAtIso).contains("-08:00")
        assertThat(sheetRow.bookmarksPipe).isEqualTo("https://example.com|Example Site")
        assertThat(sheetRow.mediaDriveIds).containsExactly("drv-123:abcd1234:image/webp")
        assertThat(remoteHash).isEqualTo(localHash)
    }

    @Test
    fun computeDiff_blankIdHumanAddedRow_createsNewItemWithValidationWarning() {
        val remoteRow = SheetEntryRow(
            entryId = "",
            createdAtIso = "not-a-timestamp",
            zoneId = "Invalid/Zone",
            bodyRaw = "",
            visibleTags = listOf("people/Lex"),
            hiddenAutoTags = emptyList(),
            rejectedTags = emptyList(),
            bookmarksPipe = "",
            mediaDriveIds = emptyList(),
            replyToId = null,
            updatedAtIso = null,
            deleted = false,
        )

        val preview = diffEngine.computeDiff(
            sourceFormat = BackupFormat.GOOGLE_SHEETS,
            localEntries = emptyList(),
            localTags = emptyList(),
            lastSyncedHashes = emptyMap(),
            remoteWorkbook = workbookWith(entries = listOf(remoteRow)),
        )

        assertThat(preview.newItems).hasSize(1)
        val item = preview.newItems.single()
        assertThat(item.diffKey).isEqualTo("new_row_0")
        assertThat(item.changeType).isEqualTo(DiffChangeType.NEW_FROM_REMOTE)
        assertThat(item.validationWarning).contains("Empty body text")
        assertThat(item.validationWarning).contains("Unparseable timestamp")
        assertThat(item.validationWarning).contains("Unknown timezone")
    }

    @Test
    fun computeDiff_nonBlankNewRemoteEntry_addsActiveAndSkipsDeleted() {
        val activeRemote = SheetEntryRow(
            entryId = "e-new",
            createdAtIso = "2026-11-20T09:15:00-08:00",
            zoneId = "America/Los_Angeles",
            bodyRaw = "Brand new note from another device",
            visibleTags = emptyList(),
            hiddenAutoTags = emptyList(),
            rejectedTags = emptyList(),
            bookmarksPipe = "",
            mediaDriveIds = emptyList(),
            replyToId = null,
            updatedAtIso = null,
            deleted = false,
        )
        val deletedRemote = activeRemote.copy(entryId = "e-already-deleted", deleted = true)

        val preview = diffEngine.computeDiff(
            sourceFormat = BackupFormat.CSV,
            localEntries = emptyList(),
            localTags = emptyList(),
            lastSyncedHashes = emptyMap(),
            remoteWorkbook = workbookWith(entries = listOf(activeRemote, deletedRemote)),
        )

        assertThat(preview.newItems.map { it.diffKey }).containsExactly("e-new")
        assertThat(preview.deletedItems).isEmpty()
        assertThat(preview.conflicts).isEmpty()
    }

    @Test
    fun computeDiff_threeWayRules_unchangedEditedLocalOnlyDeletedAndConflicts() {
        // 1. Unchanged entry (localHash == remoteHash) -> skipped
        val unchangedLocal = sampleEntry(id = "e-unchanged", bodyRaw = "Same text")
        val unchangedRemote = WorkbookMapper.toSheetEntryRow(unchangedLocal)

        // 2. Remote-only edit (localHash == baseHash, remoteHash != baseHash) -> EDITED_FROM_REMOTE
        val editedLocal = sampleEntry(id = "e-edited", bodyRaw = "Original text")
        val editedBaseHash = WorkbookMapper.computeLocalEntrySyncHash(editedLocal)
        val editedRemote = WorkbookMapper.toSheetEntryRow(editedLocal).copy(bodyRaw = "Edited in Sheets")

        // 3. Local-only edit (localHash != baseHash, remoteHash == baseHash) -> skipped
        val localOnlyBase = sampleEntry(id = "e-local-only", bodyRaw = "Base text")
        val localOnlyBaseHash = WorkbookMapper.computeLocalEntrySyncHash(localOnlyBase)
        val localOnlyCurrent = sampleEntry(id = "e-local-only", bodyRaw = "Edited on phone")
        val localOnlyRemote = WorkbookMapper.toSheetEntryRow(localOnlyBase)

        // 4. Remote delete while local untouched -> DELETED_FROM_REMOTE
        val deletedLocal = sampleEntry(id = "e-deleted", bodyRaw = "Will be deleted remotely")
        val deletedBaseHash = WorkbookMapper.computeLocalEntrySyncHash(deletedLocal)
        val deletedRemote = WorkbookMapper.toSheetEntryRow(deletedLocal).copy(deleted = true)

        // 5. Conflict: both edited since baseHash -> CONFLICT
        val bothBase = sampleEntry(id = "e-conflict-both", bodyRaw = "Common ancestor")
        val bothBaseHash = WorkbookMapper.computeLocalEntrySyncHash(bothBase)
        val bothLocal = sampleEntry(id = "e-conflict-both", bodyRaw = "Phone version")
        val bothRemote = WorkbookMapper.toSheetEntryRow(bothBase).copy(bodyRaw = "Sheet version")

        // 6. Conflict: local edited AND remote deleted -> CONFLICT
        val editDelBase = sampleEntry(id = "e-conflict-del", bodyRaw = "Ancestor before edit/delete")
        val editDelBaseHash = WorkbookMapper.computeLocalEntrySyncHash(editDelBase)
        val editDelLocal = sampleEntry(id = "e-conflict-del", bodyRaw = "Phone edited this!")
        val editDelRemote = WorkbookMapper.toSheetEntryRow(editDelBase).copy(deleted = true)

        // 7. Conflict: no baseHash and local != remote -> CONFLICT
        val noBaseLocal = sampleEntry(id = "e-no-base", bodyRaw = "Phone without base")
        val noBaseRemote = WorkbookMapper.toSheetEntryRow(noBaseLocal).copy(bodyRaw = "CSV without base")

        val preview = diffEngine.computeDiff(
            sourceFormat = BackupFormat.GOOGLE_SHEETS,
            localEntries = listOf(
                unchangedLocal,
                editedLocal,
                localOnlyCurrent,
                deletedLocal,
                bothLocal,
                editDelLocal,
                noBaseLocal,
            ),
            localTags = emptyList(),
            lastSyncedHashes = mapOf(
                "e-unchanged" to WorkbookMapper.computeLocalEntrySyncHash(unchangedLocal),
                "e-edited" to editedBaseHash,
                "e-local-only" to localOnlyBaseHash,
                "e-deleted" to deletedBaseHash,
                "e-conflict-both" to bothBaseHash,
                "e-conflict-del" to editDelBaseHash,
            ),
            remoteWorkbook = workbookWith(
                entries = listOf(
                    unchangedRemote,
                    editedRemote,
                    localOnlyRemote,
                    deletedRemote,
                    bothRemote,
                    editDelRemote,
                    noBaseRemote,
                ),
            ),
        )

        assertThat(preview.newItems).isEmpty()
        assertThat(preview.editedItems.map { it.diffKey }).containsExactly("e-edited")
        assertThat(preview.deletedItems.map { it.diffKey }).containsExactly("e-deleted")
        assertThat(preview.conflicts.map { it.diffKey }).containsExactly(
            "e-conflict-both",
            "e-conflict-del",
            "e-no-base",
        )
        assertThat(preview.hasConflicts).isTrue()
    }

    @Test
    fun computeDiff_countsTagChangesAndDetectsMissingMedia() {
        val localTags = listOf(
            Tag(
                id = "t-1",
                fullPath = "people/friends/Lex",
                leafName = "Lex",
                rootCategory = TagRootCategory.PEOPLE,
                colorToken = "people",
                aliases = listOf("Alexis"),
            ),
            Tag(
                id = "t-2",
                fullPath = "projects/alpha",
                leafName = "alpha",
                rootCategory = TagRootCategory.PROJECTS,
                colorToken = "projects",
                aliases = emptyList(),
            ),
        )

        val remoteTags = listOf(
            // Unchanged tag
            SheetTagRow(
                tagId = "t-1",
                fullPath = "people/friends/Lex",
                leafName = "Lex",
                rootCategory = "people",
                colorToken = "people",
                aliases = listOf("Alexis"),
            ),
            // Changed aliases
            SheetTagRow(
                tagId = "t-2",
                fullPath = "projects/alpha",
                leafName = "alpha",
                rootCategory = "projects",
                colorToken = "projects",
                aliases = listOf("Project A"),
            ),
            // Brand new tag
            SheetTagRow(
                tagId = "t-3",
                fullPath = "topics/reading",
                leafName = "reading",
                rootCategory = "topics",
                colorToken = "topics",
                aliases = emptyList(),
            ),
        )

        val remoteEntry = SheetEntryRow(
            entryId = "e-media",
            createdAtIso = "2026-11-20T12:00:00Z",
            zoneId = "UTC",
            bodyRaw = "Photo note",
            visibleTags = emptyList(),
            hiddenAutoTags = emptyList(),
            rejectedTags = emptyList(),
            bookmarksPipe = "",
            mediaDriveIds = listOf(
                "drv-present:aaaa1111:image/webp",
                "drv-missing:bbbb2222:image/jpeg",
            ),
            replyToId = null,
            updatedAtIso = null,
            deleted = false,
        )

        val preview = diffEngine.computeDiff(
            sourceFormat = BackupFormat.GOOGLE_SHEETS,
            localEntries = emptyList(),
            localTags = localTags,
            lastSyncedHashes = emptyMap(),
            remoteWorkbook = workbookWith(entries = listOf(remoteEntry), tags = remoteTags),
            localMediaExistsBySha256 = setOf("AAAA1111"),
        )

        assertThat(preview.tagChangesCount).isEqualTo(2)
        assertThat(preview.missingMediaIds).containsExactly("drv-missing:bbbb2222:image/jpeg")
    }

    private fun workbookWith(
        entries: List<SheetEntryRow> = emptyList(),
        tags: List<SheetTagRow> = emptyList(),
    ): BackupWorkbook = BackupWorkbook(
        entries = entries,
        tags = tags,
        meta = SyncMetaRecord(
            schemaVersion = 1,
            lastExportIso = "2026-11-20T12:00:00Z",
            deviceId = "test-device",
            entryCount = entries.size,
            checksum = WorkbookMapper.computeWorkbookChecksum(entries, tags),
        ),
    )

    private fun sampleEntry(
        id: String,
        bodyRaw: String,
        visibleTagPaths: List<String> = emptyList(),
        hiddenTagPaths: List<String> = emptyList(),
        rejectedTagPaths: List<String> = emptyList(),
        bookmarks: List<Bookmark> = emptyList(),
        attachments: List<Attachment> = emptyList(),
        deletedAtMs: Long? = null,
    ): EntryWithDetails {
        val createdAtMs = 1_763_654_400_000L // 2025-11-20T16:00:00Z -> 08:00-08:00 in America/Los_Angeles
        val allEntryTags = buildList {
            visibleTagPaths.forEachIndexed { idx, path ->
                add(makeEntryTag(id, "vis-$idx", path, TagState.USER, createdAtMs))
            }
            hiddenTagPaths.forEachIndexed { idx, path ->
                add(makeEntryTag(id, "hid-$idx", path, TagState.AUTO_HIDDEN, createdAtMs))
            }
            rejectedTagPaths.forEachIndexed { idx, path ->
                add(makeEntryTag(id, "rej-$idx", path, TagState.AUTO_REJECTED, createdAtMs))
            }
        }
        return EntryWithDetails(
            entry = Entry(
                id = id,
                createdAtMs = createdAtMs,
                zoneId = "America/Los_Angeles",
                localDate = "2025-11-20",
                isoWeek = "2025-W47",
                bodyRaw = bodyRaw,
                bodyPlain = bodyRaw,
                replyToId = null,
                contentHash = "",
                updatedAtMs = null,
                deletedAtMs = deletedAtMs,
            ),
            tags = allEntryTags,
            bookmarks = bookmarks,
            attachments = attachments,
        )
    }

    private fun makeEntryTag(
        entryId: String,
        tagId: String,
        fullPath: String,
        state: TagState,
        updatedAtMs: Long,
    ): EntryTag {
        val category = TagRootCategory.fromPath(fullPath)
        return EntryTag(
            entryId = entryId,
            tagId = tagId,
            tag = Tag(
                id = tagId,
                fullPath = fullPath,
                leafName = fullPath.substringAfterLast('/'),
                rootCategory = category,
                colorToken = category.key,
            ),
            state = state,
            updatedAtMs = updatedAtMs,
        )
    }
}
