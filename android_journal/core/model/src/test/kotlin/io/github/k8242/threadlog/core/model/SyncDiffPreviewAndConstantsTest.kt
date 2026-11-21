package io.github.k8242.threadlog.core.model

import com.google.common.truth.Truth.assertThat
import io.github.k8242.threadlog.core.model.contract.MediaCompressor
import org.junit.Test

class SyncDiffPreviewAndConstantsTest {

    private val emptyWorkbook = BackupWorkbook(
        entries = emptyList(),
        tags = emptyList(),
        meta = SyncMetaRecord(
            lastExportIso = "2026-11-19T08:15:00Z",
            deviceId = "dev-1",
            entryCount = 0,
            checksum = "sha256-empty",
        ),
    )

    private fun sampleDiffItem(type: DiffChangeType): EntryDiffItem = EntryDiffItem(
        diffKey = "item-${type.name}",
        changeType = type,
        localEntry = null,
        remoteRow = SheetEntryRow(
            entryId = "e1",
            createdAtIso = "2026-11-19T08:15:00Z",
            zoneId = "UTC",
            bodyRaw = "Sample",
            visibleTags = emptyList(),
            hiddenAutoTags = emptyList(),
            rejectedTags = emptyList(),
            bookmarksPipe = "",
            mediaDriveIds = emptyList(),
            deleted = false,
        ),
    )

    private fun basePreview(
        newItems: List<EntryDiffItem> = emptyList(),
        editedItems: List<EntryDiffItem> = emptyList(),
        deletedItems: List<EntryDiffItem> = emptyList(),
        conflicts: List<EntryDiffItem> = emptyList(),
        tagChangesCount: Int = 0,
        missingMediaIds: List<String> = emptyList(),
    ): SyncDiffPreview = SyncDiffPreview(
        sourceFormat = BackupFormat.GOOGLE_SHEETS,
        newItems = newItems,
        editedItems = editedItems,
        deletedItems = deletedItems,
        conflicts = conflicts,
        tagChangesCount = tagChangesCount,
        missingMediaIds = missingMediaIds,
        remoteWorkbook = emptyWorkbook,
    )

    @Test
    fun syncDiffPreview_emptyWhenAllListsEmptyAndZeroTagChanges() {
        val empty = basePreview()
        assertThat(empty.isEmpty).isTrue()
        assertThat(empty.hasConflicts).isFalse()

        // Intentionally does NOT count missingMediaIds toward isEmpty per frozen spec
        val emptyWithMissingMedia = basePreview(missingMediaIds = listOf("drive-id-1"))
        assertThat(emptyWithMissingMedia.isEmpty).isTrue()
        assertThat(emptyWithMissingMedia.hasConflicts).isFalse()
    }

    @Test
    fun syncDiffPreview_notEmptyForEachIndividualDiffBucket() {
        val onlyNew = basePreview(newItems = listOf(sampleDiffItem(DiffChangeType.NEW_FROM_REMOTE)))
        assertThat(onlyNew.isEmpty).isFalse()
        assertThat(onlyNew.hasConflicts).isFalse()

        val onlyEdited = basePreview(editedItems = listOf(sampleDiffItem(DiffChangeType.EDITED_FROM_REMOTE)))
        assertThat(onlyEdited.isEmpty).isFalse()
        assertThat(onlyEdited.hasConflicts).isFalse()

        val onlyDeleted = basePreview(deletedItems = listOf(sampleDiffItem(DiffChangeType.DELETED_FROM_REMOTE)))
        assertThat(onlyDeleted.isEmpty).isFalse()
        assertThat(onlyDeleted.hasConflicts).isFalse()

        val onlyTagChanges = basePreview(tagChangesCount = 1)
        assertThat(onlyTagChanges.isEmpty).isFalse()
        assertThat(onlyTagChanges.hasConflicts).isFalse()
    }

    @Test
    fun syncDiffPreview_hasConflictsAndNotEmptyWhenConflictsPresent() {
        val onlyConflicts = basePreview(conflicts = listOf(sampleDiffItem(DiffChangeType.CONFLICT)))
        assertThat(onlyConflicts.isEmpty).isFalse()
        assertThat(onlyConflicts.hasConflicts).isTrue()

        val allBuckets = basePreview(
            newItems = listOf(sampleDiffItem(DiffChangeType.NEW_FROM_REMOTE)),
            editedItems = listOf(sampleDiffItem(DiffChangeType.EDITED_FROM_REMOTE)),
            deletedItems = listOf(sampleDiffItem(DiffChangeType.DELETED_FROM_REMOTE)),
            conflicts = listOf(sampleDiffItem(DiffChangeType.CONFLICT)),
            tagChangesCount = 2,
        )
        assertThat(allBuckets.isEmpty).isFalse()
        assertThat(allBuckets.hasConflicts).isTrue()
    }

    @Test
    fun constants_matchLockedSpecBoundaries() {
        assertThat(MediaCompressor.MAX_ATTACHMENT_BYTES).isEqualTo(655_360L)
        assertThat(MediaCompressor.MAX_ATTACHMENT_BYTES).isEqualTo(640L * 1024L)
        assertThat(HybridSearchResult.TIER_A_WINDOW_MS).isEqualTo(3_024_000_000L)
        assertThat(ContextWindowState.DEFAULT_HALF_WINDOW_MS).isEqualTo(43_200_000L)
    }

    @Test
    fun domainDefaults_matchLockedDecisions() {
        val settings = UserSettings()
        assertThat(settings.weekStart).isEqualTo(WeekStart.MONDAY)
        assertThat(settings.offlineOnlyLinks).isFalse()
        assertThat(settings.showHiddenAutoTagsInChat).isFalse()
        assertThat(settings.scheduledBackupCadence).isEqualTo(BackupCadence.OFF)
        assertThat(settings.linkedSpreadsheetId).isNull()
        assertThat(settings.linkedMediaFolderId).isNull()
        assertThat(settings.lastExportIso).isNull()
        assertThat(settings.deviceId).isEmpty()

        val meta = SyncMetaRecord(
            lastExportIso = "2026-11-19T00:00:00Z",
            deviceId = "d1",
            entryCount = 0,
            checksum = "0",
        )
        assertThat(meta.schemaVersion).isEqualTo(1)

        val bookmark = Bookmark(
            id = "b1",
            entryId = "e1",
            urlRaw = "https://example.com/a/b/c",
            domain = "example.com",
        )
        assertThat(bookmark.isCollapsed).isTrue()
        assertThat(bookmark.title).isNull()
        assertThat(bookmark.note).isNull()

        val tag = Tag(
            id = "t1",
            fullPath = "people/friends/Lex",
            leafName = "Lex",
            rootCategory = TagRootCategory.PEOPLE,
            colorToken = "people",
        )
        assertThat(tag.parentId).isNull()
        assertThat(tag.aliases).isEmpty()

        val entryTag = EntryTag(
            entryId = "e1",
            tagId = "t1",
            tag = tag,
            state = TagState.USER,
            updatedAtMs = 1L,
        )
        assertThat(entryTag.confidence).isEqualTo(1.0f)

        val rule = AutoTagRule(
            id = "r1",
            pattern = "github.com",
            matchType = RuleMatchType.DOMAIN,
            targetTagPath = "projects/journal-app",
        )
        assertThat(rule.confidence).isEqualTo(0.85f)
        assertThat(rule.enabled).isTrue()
    }
}
