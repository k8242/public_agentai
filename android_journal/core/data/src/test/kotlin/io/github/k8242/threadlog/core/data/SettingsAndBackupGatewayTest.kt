// Copyright (c) 2026 k8242
// SPDX-License-Identifier: MIT

package io.github.k8242.threadlog.core.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.github.k8242.threadlog.core.data.repository.DataStoreSettingsRepository
import io.github.k8242.threadlog.core.model.BackupCadence
import io.github.k8242.threadlog.core.model.BackupFormat
import io.github.k8242.threadlog.core.model.BackupWorkbook
import io.github.k8242.threadlog.core.model.ConflictResolution
import io.github.k8242.threadlog.core.model.DiffChangeType
import io.github.k8242.threadlog.core.model.EntryDiffItem
import io.github.k8242.threadlog.core.model.SheetEntryRow
import io.github.k8242.threadlog.core.model.SheetTagRow
import io.github.k8242.threadlog.core.model.SyncDiffPreview
import io.github.k8242.threadlog.core.model.SyncMetaRecord
import io.github.k8242.threadlog.core.model.WeekStart
import io.github.k8242.threadlog.core.model.contract.PendingAttachment
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SettingsAndBackupGatewayTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

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
    fun settingsRepository_persistsAllFields_andGeneratesStableDeviceId() = runTest {
        val prefsFile = File(tempFolder.newFolder("ds"), "threadlog_settings.preferences_pb")
        val scope1 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dataStore1 = PreferenceDataStoreFactory.create(
            scope = scope1,
            produceFile = { prefsFile },
        )
        val repo1 = DataStoreSettingsRepository(dataStore1)

        val initial = repo1.getSettings()
        assertThat(initial.deviceId).isNotEmpty()
        assertThat(initial.weekStart).isEqualTo(WeekStart.MONDAY)
        assertThat(initial.offlineOnlyLinks).isFalse()
        assertThat(initial.showHiddenAutoTagsInChat).isFalse()
        assertThat(initial.scheduledBackupCadence).isEqualTo(BackupCadence.OFF)

        repo1.settings.test {
            val first = awaitItem()
            assertThat(first.deviceId).isEqualTo(initial.deviceId)

            repo1.setWeekStart(WeekStart.SUNDAY)
            assertThat(awaitItem().weekStart).isEqualTo(WeekStart.SUNDAY)

            repo1.setOfflineOnlyLinks(true)
            assertThat(awaitItem().offlineOnlyLinks).isTrue()

            repo1.setShowHiddenAutoTagsInChat(true)
            assertThat(awaitItem().showHiddenAutoTagsInChat).isTrue()

            repo1.setScheduledBackupCadence(BackupCadence.DAILY)
            assertThat(awaitItem().scheduledBackupCadence).isEqualTo(BackupCadence.DAILY)

            repo1.setColorScheme(io.github.k8242.threadlog.core.model.AppColorScheme.WHATSAPP)
            assertThat(awaitItem().colorScheme)
                .isEqualTo(io.github.k8242.threadlog.core.model.AppColorScheme.WHATSAPP)

            repo1.setThemeMode(io.github.k8242.threadlog.core.model.ThemeMode.DARK)
            assertThat(awaitItem().themeMode)
                .isEqualTo(io.github.k8242.threadlog.core.model.ThemeMode.DARK)

            repo1.updateSyncLinks(
                spreadsheetId = "sheet-123",
                mediaFolderId = "folder-456",
                lastExportIso = "2026-11-20T12:00:00Z",
            )
            val synced = awaitItem()
            assertThat(synced.linkedSpreadsheetId).isEqualTo("sheet-123")
            assertThat(synced.linkedMediaFolderId).isEqualTo("folder-456")
            assertThat(synced.lastExportIso).isEqualTo("2026-11-20T12:00:00Z")

            cancelAndIgnoreRemainingEvents()
        }

        // Re-verify persisted deviceId and theme prefs stay identical on subsequent reads.
        val secondRead = repo1.getSettings()
        assertThat(secondRead.deviceId).isEqualTo(initial.deviceId)
        assertThat(secondRead.linkedSpreadsheetId).isEqualTo("sheet-123")
        assertThat(secondRead.colorScheme)
            .isEqualTo(io.github.k8242.threadlog.core.model.AppColorScheme.WHATSAPP)
        assertThat(secondRead.themeMode)
            .isEqualTo(io.github.k8242.threadlog.core.model.ThemeMode.DARK)

        // Clearing nullable sync links persists nulls properly.
        repo1.updateSyncLinks(spreadsheetId = null, mediaFolderId = null, lastExportIso = null)
        val cleared = repo1.getSettings()
        assertThat(cleared.linkedSpreadsheetId).isNull()
        assertThat(cleared.linkedMediaFolderId).isNull()
        assertThat(cleared.lastExportIso).isNull()

        scope1.cancel()
    }

    @Test
    fun backupGateway_exportIncludesTombstones_andMarkSyncedUpdatesHashesAndDriveIds() = runTest {
        val tag = graph.tagRepository.createOrGetTagByPath("projects/backup")
        val nowMs = 1_763_654_400_000L

        val active = graph.entryRepository.createEntry(
            bodyRaw = "Active entry with media",
            userTagIds = listOf(tag.id),
            pendingAttachments = listOf(
                PendingAttachment("media/m1.webp", "image/webp", 42_000L, 640, 480, "sha-m1"),
            ),
            nowMs = nowMs - 2_000L,
            zoneId = "UTC",
        )
        val deleted = graph.entryRepository.createEntry(
            bodyRaw = "Entry to be soft-deleted",
            userTagIds = emptyList(),
            nowMs = nowMs - 1_000L,
            zoneId = "UTC",
        )
        graph.entryRepository.softDeleteEntry(deleted.entry.id, nowMs = nowMs)

        val (exportedEntries, exportedTags) = graph.backupDataGateway.exportSnapshot()
        assertThat(exportedEntries.map { it.entry.id }).containsExactly(active.entry.id, deleted.entry.id)
        val exportedDeleted = exportedEntries.first { it.entry.id == deleted.entry.id }
        assertThat(exportedDeleted.entry.isDeleted).isTrue()
        assertThat(exportedTags.map { it.fullPath }).containsAtLeast("projects", "projects/backup")

        val attachmentId = active.attachments.single().id
        graph.backupDataGateway.markSynced(
            entryHashes = exportedEntries.associate { it.entry.id to it.entry.contentHash },
            driveFileIdsByAttachmentId = mapOf(attachmentId to "drive-file-999"),
        )

        val storedHashes = graph.backupDataGateway.getLastSyncedContentHashes()
        assertThat(storedHashes).containsExactly(
            active.entry.id, active.entry.contentHash,
            deleted.entry.id, exportedDeleted.entry.contentHash,
        )

        val refreshedActive = graph.entryRepository.getEntry(active.entry.id)!!
        assertThat(refreshedActive.attachments.single().driveFileId).isEqualTo("drive-file-999")
    }

    @Test
    fun backupGateway_applyMergeRestore_handlesNewEditedDeletedAndAllConflictResolutions() = runTest {
        val nowMs = 1_763_654_400_000L

        val toEdit = graph.entryRepository.createEntry(
            bodyRaw = "Local original text",
            userTagIds = emptyList(),
            nowMs = nowMs - 50_000L,
            zoneId = "UTC",
        )
        val toDelete = graph.entryRepository.createEntry(
            bodyRaw = "Will be deleted remotely",
            userTagIds = emptyList(),
            nowMs = nowMs - 40_000L,
            zoneId = "UTC",
        )
        val conflictKeepPhone = graph.entryRepository.createEntry(
            bodyRaw = "Phone wins body",
            userTagIds = emptyList(),
            nowMs = nowMs - 30_000L,
            zoneId = "UTC",
        )
        val conflictKeepSheet = graph.entryRepository.createEntry(
            bodyRaw = "Phone loses body",
            userTagIds = emptyList(),
            nowMs = nowMs - 20_000L,
            zoneId = "UTC",
        )
        val conflictKeepBoth = graph.entryRepository.createEntry(
            bodyRaw = "Phone version kept alongside sheet copy",
            userTagIds = emptyList(),
            nowMs = nowMs - 10_000L,
            zoneId = "UTC",
        )

        val remoteNewRow = SheetEntryRow(
            entryId = "", // Blank ID simulates a human-added row in Google Sheets/CSV.
            createdAtIso = "2026-11-20T15:00:00Z",
            zoneId = "America/Los_Angeles",
            bodyRaw = "Remote added note with https://developer.android.com",
            visibleTags = listOf("topics/android"),
            hiddenAutoTags = listOf("projects/sync"),
            rejectedTags = listOf("topics/noise"),
            bookmarksPipe = "https://developer.android.com|Android Developers",
            mediaDriveIds = listOf("drv-abc:sha-remote-img:image/webp"),
            deleted = false,
        )
        val remoteEditedRow = SheetEntryRow(
            entryId = toEdit.entry.id,
            createdAtIso = "2026-11-20T15:59:10Z",
            zoneId = "UTC",
            bodyRaw = "Remotely edited text with kaleidoscope",
            visibleTags = listOf("topics/edited"),
            hiddenAutoTags = emptyList(),
            rejectedTags = emptyList(),
            bookmarksPipe = "",
            mediaDriveIds = emptyList(),
            updatedAtIso = "2026-11-20T16:00:00Z",
            deleted = false,
        )
        val remoteDeletedRow = SheetEntryRow(
            entryId = toDelete.entry.id,
            createdAtIso = "2026-11-20T15:59:20Z",
            zoneId = "UTC",
            bodyRaw = "Will be deleted remotely",
            visibleTags = emptyList(),
            hiddenAutoTags = emptyList(),
            rejectedTags = emptyList(),
            bookmarksPipe = "",
            mediaDriveIds = emptyList(),
            updatedAtIso = "2026-11-20T16:00:00Z",
            deleted = true,
        )
        val remoteConflictPhoneRow = SheetEntryRow(
            entryId = conflictKeepPhone.entry.id,
            createdAtIso = "2026-11-20T15:59:30Z",
            zoneId = "UTC",
            bodyRaw = "Sheet version ignored",
            visibleTags = emptyList(),
            hiddenAutoTags = emptyList(),
            rejectedTags = emptyList(),
            bookmarksPipe = "",
            mediaDriveIds = emptyList(),
            deleted = false,
        )
        val remoteConflictSheetRow = SheetEntryRow(
            entryId = conflictKeepSheet.entry.id,
            createdAtIso = "2026-11-20T15:59:40Z",
            zoneId = "UTC",
            bodyRaw = "Sheet wins body with nebula",
            visibleTags = listOf("topics/resolved"),
            hiddenAutoTags = emptyList(),
            rejectedTags = emptyList(),
            bookmarksPipe = "",
            mediaDriveIds = emptyList(),
            updatedAtIso = "2026-11-20T16:00:00Z",
            deleted = false,
        )
        val remoteConflictBothRow = SheetEntryRow(
            entryId = conflictKeepBoth.entry.id,
            createdAtIso = "2026-11-20T15:59:50Z",
            zoneId = "UTC",
            bodyRaw = "Sheet duplicate copy with quasar",
            visibleTags = listOf("topics/both"),
            hiddenAutoTags = emptyList(),
            rejectedTags = emptyList(),
            bookmarksPipe = "",
            mediaDriveIds = emptyList(),
            updatedAtIso = "2026-11-20T16:00:00Z",
            deleted = false,
        )

        val remoteWorkbook = BackupWorkbook(
            entries = listOf(
                remoteNewRow,
                remoteEditedRow,
                remoteDeletedRow,
                remoteConflictPhoneRow,
                remoteConflictSheetRow,
                remoteConflictBothRow,
            ),
            tags = listOf(
                SheetTagRow(
                    tagId = "tag-remote-android",
                    fullPath = "topics/android",
                    leafName = "android",
                    rootCategory = "TOPICS",
                    colorToken = "topics",
                    aliases = listOf("droid"),
                ),
            ),
            meta = SyncMetaRecord(
                lastExportIso = "2026-11-20T16:00:00Z",
                deviceId = "remote-device",
                entryCount = 6,
                checksum = "checksum-1",
            ),
        )

        val diff = SyncDiffPreview(
            sourceFormat = BackupFormat.GOOGLE_SHEETS,
            newItems = listOf(
                EntryDiffItem(
                    diffKey = "row-0",
                    changeType = DiffChangeType.NEW_FROM_REMOTE,
                    localEntry = null,
                    remoteRow = remoteNewRow,
                ),
            ),
            editedItems = listOf(
                EntryDiffItem(
                    diffKey = toEdit.entry.id,
                    changeType = DiffChangeType.EDITED_FROM_REMOTE,
                    localEntry = toEdit,
                    remoteRow = remoteEditedRow,
                ),
            ),
            deletedItems = listOf(
                EntryDiffItem(
                    diffKey = toDelete.entry.id,
                    changeType = DiffChangeType.DELETED_FROM_REMOTE,
                    localEntry = toDelete,
                    remoteRow = remoteDeletedRow,
                ),
            ),
            conflicts = listOf(
                EntryDiffItem(
                    diffKey = conflictKeepPhone.entry.id,
                    changeType = DiffChangeType.CONFLICT,
                    localEntry = conflictKeepPhone,
                    remoteRow = remoteConflictPhoneRow,
                ),
                EntryDiffItem(
                    diffKey = conflictKeepSheet.entry.id,
                    changeType = DiffChangeType.CONFLICT,
                    localEntry = conflictKeepSheet,
                    remoteRow = remoteConflictSheetRow,
                ),
                EntryDiffItem(
                    diffKey = conflictKeepBoth.entry.id,
                    changeType = DiffChangeType.CONFLICT,
                    localEntry = conflictKeepBoth,
                    remoteRow = remoteConflictBothRow,
                ),
            ),
            tagChangesCount = 1,
            missingMediaIds = emptyList(),
            remoteWorkbook = remoteWorkbook,
        )

        val downloadedMedia = mapOf(
            "drv-abc" to PendingAttachment(
                localFilename = "media/downloaded.webp",
                mimeType = "image/webp",
                byteSize = 33_000L,
                width = 800,
                height = 600,
                sha256 = "sha-remote-img",
            ),
        )

        graph.backupDataGateway.applyMergeRestore(
            diff = diff,
            resolutions = mapOf(
                conflictKeepPhone.entry.id to ConflictResolution.KEEP_PHONE,
                conflictKeepSheet.entry.id to ConflictResolution.KEEP_SHEET,
                conflictKeepBoth.entry.id to ConflictResolution.KEEP_BOTH,
            ),
            downloadedMedia = downloadedMedia,
        )

        // 1. Remote tag upserted with alias.
        val androidTag = graph.tagRepository.getAllTags().first { it.fullPath == "topics/android" }
        assertThat(androidTag.aliases).contains("droid")

        // 2. Edited entry updated and searchable in FTS5.
        val editedEntry = graph.entryRepository.getEntry(toEdit.entry.id)!!
        assertThat(editedEntry.entry.bodyRaw).isEqualTo("Remotely edited text with kaleidoscope")
        assertThat(graph.searchRepository.search("kaleidoscope", nowMs = nowMs).tierARecent.map { it.entryId })
            .containsExactly(toEdit.entry.id)

        // 3. Deleted entry soft-deleted and removed from FTS5.
        val deletedEntry = graph.entryRepository.getEntry(toDelete.entry.id)!!
        assertThat(deletedEntry.entry.isDeleted).isTrue()
        assertThat(graph.searchRepository.search("deleted remotely", nowMs = nowMs).tierARecent)
            .isEmpty()

        // 4. KEEP_PHONE preserved local body untouched.
        assertThat(graph.entryRepository.getEntry(conflictKeepPhone.entry.id)!!.entry.bodyRaw)
            .isEqualTo("Phone wins body")

        // 5. KEEP_SHEET replaced local body and reindexed FTS5.
        val sheetWon = graph.entryRepository.getEntry(conflictKeepSheet.entry.id)!!
        assertThat(sheetWon.entry.bodyRaw).isEqualTo("Sheet wins body with nebula")
        assertThat(graph.searchRepository.search("nebula", nowMs = nowMs).tierARecent.map { it.entryId })
            .containsExactly(conflictKeepSheet.entry.id)

        // 6. KEEP_BOTH kept phone entry AND created a brand-new UUID v7 copy of the remote entry.
        val phoneKept = graph.entryRepository.getEntry(conflictKeepBoth.entry.id)!!
        assertThat(phoneKept.entry.bodyRaw).isEqualTo("Phone version kept alongside sheet copy")
        val quasarHits = graph.searchRepository.search("quasar", nowMs = nowMs).tierARecent
        assertThat(quasarHits).hasSize(1)
        val duplicateId = quasarHits.single().entryId
        assertThat(duplicateId).isNotEqualTo(conflictKeepBoth.entry.id)

        // 7. Blank-ID NEW_FROM_REMOTE got a generated UUID v7, parsed bookmarksPipe, attached media, and tags.
        val (allExported, _) = graph.backupDataGateway.exportSnapshot()
        val newImported = allExported.first { it.entry.bodyRaw.startsWith("Remote added note") }
        assertThat(newImported.entry.id).isNotEmpty()
        assertThat(newImported.visibleTags.map { it.tag.fullPath }).containsExactly("topics/android")
        assertThat(newImported.hiddenAutoTags.map { it.tag.fullPath }).containsExactly("projects/sync")
        assertThat(newImported.rejectedTags.map { it.tag.fullPath }).containsExactly("topics/noise")
        assertThat(newImported.bookmarks.single().title).isEqualTo("Android Developers")
        assertThat(newImported.attachments.single().driveFileId).isEqualTo("drv-abc")
        assertThat(newImported.attachments.single().sha256).isEqualTo("sha-remote-img")

        // Searchable via remote tag alias "droid" in FTS5!
        val droidHits = graph.searchRepository.search("droid", nowMs = nowMs).tierARecent
        assertThat(droidHits.map { it.entryId }).containsExactly(newImported.entry.id)
    }
}
