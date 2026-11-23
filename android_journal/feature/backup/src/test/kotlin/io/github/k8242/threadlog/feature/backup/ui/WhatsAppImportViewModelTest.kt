// Copyright (c) 2026 k8242
// SPDX-License-Identifier: MIT

package io.github.k8242.threadlog.feature.backup.ui

import androidx.paging.PagingData
import com.google.common.truth.Truth.assertThat
import io.github.k8242.threadlog.core.model.AppColorScheme
import io.github.k8242.threadlog.core.model.BackupCadence
import io.github.k8242.threadlog.core.model.CalendarDayCount
import io.github.k8242.threadlog.core.model.CompactEntrySummary
import io.github.k8242.threadlog.core.model.ConflictResolution
import io.github.k8242.threadlog.core.model.Entry
import io.github.k8242.threadlog.core.model.EntryWithDetails
import io.github.k8242.threadlog.core.model.SyncDiffPreview
import io.github.k8242.threadlog.core.model.Tag
import io.github.k8242.threadlog.core.model.TaskStatus
import io.github.k8242.threadlog.core.model.ThemeMode
import io.github.k8242.threadlog.core.model.UserSettings
import io.github.k8242.threadlog.core.model.WeekStart
import io.github.k8242.threadlog.core.model.contract.AutoTagScheduler
import io.github.k8242.threadlog.core.model.contract.BackupDataGateway
import io.github.k8242.threadlog.core.model.contract.BackupScheduler
import io.github.k8242.threadlog.core.model.contract.EntryRepository
import io.github.k8242.threadlog.core.model.contract.GoogleAuthProvider
import io.github.k8242.threadlog.core.model.contract.GoogleAuthState
import io.github.k8242.threadlog.core.model.contract.MediaStorage
import io.github.k8242.threadlog.core.model.contract.PendingAttachment
import io.github.k8242.threadlog.core.model.contract.SettingsRepository
import io.github.k8242.threadlog.core.parser.WhatsAppDateOrder
import io.github.k8242.threadlog.feature.backup.BackupCoordinator
import io.github.k8242.threadlog.feature.backup.cloud.GoogleDriveClient
import io.github.k8242.threadlog.feature.backup.cloud.GoogleSheetsClient
import io.github.k8242.threadlog.feature.backup.codec.CsvBackupCodec
import io.github.k8242.threadlog.feature.backup.codec.XlsxBackupCodec
import io.github.k8242.threadlog.feature.backup.codec.ZipJsonMediaCodec
import io.github.k8242.threadlog.feature.backup.sync.SyncDiffEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WhatsAppImportViewModelTest {

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
    fun parseReviewToggleMaskAndImport_respectsTimezoneSenderMediaAndCredentials() = runTest(testDispatcher) {
        val sample = """
            8/13/22, 2:11${'\u202F'}PM - Yutong: Beach kickoff
            8/19/22, 6:26${'\u202F'}AM - Yutong: Wifi pw: Summer2026!
            8/22/22, 3:43${'\u202F'}AM - Yutong: <Media omitted>
        """.trimIndent()

        val createdEntries = mutableListOf<Triple<String, Long, String?>>()
        val createdTasks = mutableListOf<TaskStatus>()
        val scheduledAutoTagIds = mutableListOf<String>()

        val fakeEntryRepo = object : EntryRepository {
            private var seq = 1
            override fun observePagedEntries(): Flow<PagingData<EntryWithDetails>> = flowOf(PagingData.empty())
            override fun observeEntry(entryId: String): Flow<EntryWithDetails?> = flowOf(null)
            override suspend fun getEntry(entryId: String): EntryWithDetails? = null
            override suspend fun createEntry(
                bodyRaw: String,
                userTagIds: List<String>,
                replyToId: String?,
                pendingAttachments: List<PendingAttachment>,
                nowMs: Long,
                zoneId: String?,
                taskStatus: TaskStatus,
            ): EntryWithDetails {
                val id = "wa-${seq++}"
                createdEntries += Triple(bodyRaw, nowMs, zoneId)
                createdTasks += taskStatus
                return EntryWithDetails(
                    entry = Entry(
                        id = id,
                        createdAtMs = nowMs,
                        zoneId = zoneId ?: "UTC",
                        localDate = "2022-08-13",
                        isoWeek = "2022-W32",
                        bodyRaw = bodyRaw,
                        bodyPlain = bodyRaw,
                        contentHash = "hash-$id",
                        taskStatus = taskStatus,
                    ),
                    tags = emptyList(),
                    bookmarks = emptyList(),
                    attachments = emptyList(),
                )
            }

            override suspend fun editEntry(entryId: String, newBodyRaw: String, nowMs: Long): EntryWithDetails =
                error("unused")

            override suspend fun softDeleteEntry(entryId: String, nowMs: Long) = Unit
            override suspend fun undoSoftDelete(entryId: String) = Unit
            override fun observeCompactSummaries(): Flow<List<CompactEntrySummary>> = flowOf(emptyList())
            override fun observeCalendarDayCounts(
                yearMonth: String,
                filterTagPathPrefix: String?,
            ): Flow<List<CalendarDayCount>> = flowOf(emptyList())

            override fun observeEntriesForDate(
                localDate: String,
                filterTagPathPrefix: String?,
            ): Flow<List<EntryWithDetails>> = flowOf(emptyList())

            override fun observeContextWindow(startMs: Long, endMs: Long): Flow<List<EntryWithDetails>> =
                flowOf(emptyList())

            override fun observeEntriesWithPendingAutoTags(): Flow<List<EntryWithDetails>> = flowOf(emptyList())
        }

        val fakeGateway = object : BackupDataGateway {
            override suspend fun exportSnapshot(): Pair<List<EntryWithDetails>, List<Tag>> =
                emptyList<EntryWithDetails>() to emptyList()

            override suspend fun getLastSyncedContentHashes(): Map<String, String> = emptyMap()
            override suspend fun markSynced(
                entryHashes: Map<String, String>,
                driveFileIdsByAttachmentId: Map<String, String>,
            ) = Unit

            override suspend fun applyMergeRestore(
                diff: SyncDiffPreview,
                resolutions: Map<String, ConflictResolution>,
                downloadedMedia: Map<String, PendingAttachment>,
            ) = Unit
        }

        val fakeSettings = object : SettingsRepository {
            override val settings: Flow<UserSettings> = flowOf(UserSettings())
            override suspend fun getSettings(): UserSettings = UserSettings()
            override suspend fun setWeekStart(weekStart: WeekStart) = Unit
            override suspend fun setOfflineOnlyLinks(enabled: Boolean) = Unit
            override suspend fun setShowHiddenAutoTagsInChat(show: Boolean) = Unit
            override suspend fun setScheduledBackupCadence(cadence: BackupCadence) = Unit
            override suspend fun updateSyncLinks(
                spreadsheetId: String?,
                mediaFolderId: String?,
                lastExportIso: String?,
            ) = Unit

            override suspend fun setColorScheme(colorScheme: AppColorScheme) = Unit
            override suspend fun setThemeMode(themeMode: ThemeMode) = Unit
        }

        val fakeMedia = object : MediaStorage {
            override suspend fun writeVerifiedBytes(
                uuid: String,
                mimeType: String,
                bytes: ByteArray,
            ): PendingAttachment = PendingAttachment(
                localFilename = "media/$uuid.bin",
                mimeType = mimeType,
                byteSize = bytes.size.toLong(),
                width = 0,
                height = 0,
                sha256 = "sha-$uuid",
            )

            override suspend fun readBytes(localFilename: String): ByteArray? = null
            override fun resolveFilePath(localFilename: String): String = "/tmp/$localFilename"
            override suspend fun findBySha256(sha256: String): String? = null
        }

        val fakeAuth = object : GoogleAuthProvider {
            override fun isConfigured(): Boolean = false
            override fun observeAuthState(): Flow<GoogleAuthState> = flowOf(GoogleAuthState.NotConfigured)
            override suspend fun getAccessToken(): Result<String> = Result.failure(IllegalStateException())
            override suspend fun signOut() = Unit
        }

        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val coordinator = BackupCoordinator(
            backupDataGateway = fakeGateway,
            settingsRepository = fakeSettings,
            mediaStorage = fakeMedia,
            syncDiffEngine = SyncDiffEngine(),
            csvBackupCodec = CsvBackupCodec(),
            xlsxBackupCodec = XlsxBackupCodec(),
            zipJsonMediaCodec = ZipJsonMediaCodec(),
            googleAuthProvider = fakeAuth,
            googleSheetsClient = GoogleSheetsClient(OkHttpClient(), json),
            googleDriveClient = GoogleDriveClient(OkHttpClient(), json),
        )

        val vm = BackupViewModel(
            backupCoordinator = coordinator,
            entryRepository = fakeEntryRepo,
            backupDataGateway = fakeGateway,
            autoTagScheduler = object : AutoTagScheduler {
                override fun enqueueAutoTag(entryId: String) {
                    scheduledAutoTagIds += entryId
                }
            },
            googleAuthProvider = fakeAuth,
            settingsRepository = fakeSettings,
            backupScheduler = object : BackupScheduler {
                override fun updateSchedule(cadence: BackupCadence) = Unit
            },
        )

        vm.startWhatsAppImportFromText(sample)
        advanceUntilIdle()

        val initialDraft = vm.uiState.value.whatsAppDraft!!
        // Defaults: US M/D/YY, keep <Media omitted>, strip sender prefix.
        assertThat(initialDraft.dateOrder).isEqualTo(WhatsAppDateOrder.MDY)
        assertThat(initialDraft.skipMediaOmitted).isFalse()
        assertThat(initialDraft.includeSenderName).isFalse()
        assertThat(initialDraft.visibleItems).hasSize(3)
        assertThat(initialDraft.blockedSelectedCount).isEqualTo(1)

        // Switching timezone recalculates timestamps.
        vm.updateWhatsAppZoneId("Pacific/Honolulu")
        assertThat(vm.uiState.value.whatsAppDraft!!.zoneId).isEqualTo("Pacific/Honolulu")

        // Toggling sender name includes `Yutong: ` prefix.
        vm.toggleWhatsAppIncludeSender(true)
        assertThat(vm.uiState.value.whatsAppDraft!!.items[0].resolvedBody(true))
            .isEqualTo("Yutong: Beach kickoff")

        // Toggling skip <Media omitted> filters out the media item.
        vm.toggleWhatsAppSkipMediaOmitted(true)
        assertThat(vm.uiState.value.whatsAppDraft!!.visibleItems).hasSize(2)
        // Toggle back to keep <Media omitted>.
        vm.toggleWhatsAppSkipMediaOmitted(false)
        assertThat(vm.uiState.value.whatsAppDraft!!.visibleItems).hasSize(3)

        // Mark first message as TODO during review.
        vm.toggleWhatsAppItemTask(0)

        // Attempt to import while credential is still unmasked -> blocked!
        vm.confirmWhatsAppImport()
        advanceUntilIdle()
        assertThat(createdEntries).isEmpty()
        assertThat(vm.uiState.value.errorMessage).isNotNull()

        // Mask all credentials -> now import succeeds!
        vm.maskAllWhatsAppCredentials()
        assertThat(vm.uiState.value.whatsAppDraft!!.blockedSelectedCount).isEqualTo(0)

        vm.confirmWhatsAppImport()
        advanceUntilIdle()

        assertThat(createdEntries).hasSize(3)
        assertThat(createdEntries[0].first).isEqualTo("Yutong: Beach kickoff")
        assertThat(createdEntries[0].third).isEqualTo("Pacific/Honolulu")
        assertThat(createdEntries[1].first).isEqualTo("Yutong: Wifi pw: xxxx!")
        assertThat(createdEntries[2].first).isEqualTo("Yutong: <Media omitted>")
        assertThat(createdTasks).containsExactly(TaskStatus.TODO, TaskStatus.NONE, TaskStatus.NONE).inOrder()
        assertThat(scheduledAutoTagIds).hasSize(3)
        assertThat(vm.uiState.value.whatsAppDraft).isNull()
        assertThat(vm.uiState.value.statusMessage).contains("Imported 3 WhatsApp notes (Pacific/Honolulu)")
    }
}
