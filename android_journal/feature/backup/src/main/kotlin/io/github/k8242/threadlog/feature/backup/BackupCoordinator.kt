// Copyright (c) 2026 k8242
// SPDX-License-Identifier: MIT

package io.github.k8242.threadlog.feature.backup

import io.github.k8242.threadlog.core.model.BackupFormat
import io.github.k8242.threadlog.core.model.BackupWorkbook
import io.github.k8242.threadlog.core.model.ConflictResolution
import io.github.k8242.threadlog.core.model.SyncDiffPreview
import io.github.k8242.threadlog.core.model.SyncMetaRecord
import io.github.k8242.threadlog.core.model.contract.BackupDataGateway
import io.github.k8242.threadlog.core.model.contract.GoogleAuthProvider
import io.github.k8242.threadlog.core.model.contract.MediaStorage
import io.github.k8242.threadlog.core.model.contract.PendingAttachment
import io.github.k8242.threadlog.core.model.contract.SettingsRepository
import io.github.k8242.threadlog.core.parser.UuidV7
import io.github.k8242.threadlog.feature.backup.cloud.GoogleDriveClient
import io.github.k8242.threadlog.feature.backup.cloud.GoogleSheetsClient
import io.github.k8242.threadlog.feature.backup.codec.CsvBackupCodec
import io.github.k8242.threadlog.feature.backup.codec.XlsxBackupCodec
import io.github.k8242.threadlog.feature.backup.codec.ZipJsonMediaCodec
import io.github.k8242.threadlog.feature.backup.sync.SyncDiffEngine
import io.github.k8242.threadlog.feature.backup.sync.WorkbookMapper
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Staged dry-run import session holding the mandatory [SyncDiffPreview] and any locally staged
 * media attachments extracted from an offline `.zip` archive.
 */
data class BackupImportSession(
    val diffPreview: SyncDiffPreview,
    val stagedMedia: Map<String, PendingAttachment> = emptyMap(),
)

/**
 * Orchestrates offline SAF exports/imports (CSV, XLSX, JSON+Media `.zip`), optional Google Sheets
 * & Drive sync, mandatory dry-run diff previews, and transactional merge restores.
 */
@Singleton
class BackupCoordinator @Inject constructor(
    private val backupDataGateway: BackupDataGateway,
    private val settingsRepository: SettingsRepository,
    private val mediaStorage: MediaStorage,
    private val syncDiffEngine: SyncDiffEngine,
    private val csvBackupCodec: CsvBackupCodec,
    private val xlsxBackupCodec: XlsxBackupCodec,
    private val zipJsonMediaCodec: ZipJsonMediaCodec,
    private val googleAuthProvider: GoogleAuthProvider,
    private val googleSheetsClient: GoogleSheetsClient,
    private val googleDriveClient: GoogleDriveClient,
) {

    // ---- Offline Exports (Zero Cloud Setup) ----

    suspend fun exportCsv(outputStream: OutputStream): Result<SyncMetaRecord> = runCatching {
        val settings = settingsRepository.getSettings()
        val (entries, tags) = backupDataGateway.exportSnapshot()
        val workbook = WorkbookMapper.toWorkbook(
            entries = entries,
            tags = tags,
            deviceId = settings.deviceId,
        )
        withContext(Dispatchers.IO) {
            csvBackupCodec.write(workbook, outputStream)
        }
        settingsRepository.updateSyncLinks(
            spreadsheetId = settings.linkedSpreadsheetId,
            mediaFolderId = settings.linkedMediaFolderId,
            lastExportIso = workbook.meta.lastExportIso,
        )
        workbook.meta
    }

    suspend fun exportXlsx(outputStream: OutputStream): Result<SyncMetaRecord> = runCatching {
        val settings = settingsRepository.getSettings()
        val (entries, tags) = backupDataGateway.exportSnapshot()
        val workbook = WorkbookMapper.toWorkbook(
            entries = entries,
            tags = tags,
            deviceId = settings.deviceId,
        )
        withContext(Dispatchers.IO) {
            xlsxBackupCodec.write(workbook, outputStream)
        }
        settingsRepository.updateSyncLinks(
            spreadsheetId = settings.linkedSpreadsheetId,
            mediaFolderId = settings.linkedMediaFolderId,
            lastExportIso = workbook.meta.lastExportIso,
        )
        workbook.meta
    }

    suspend fun exportZip(outputStream: OutputStream): Result<SyncMetaRecord> = runCatching {
        val settings = settingsRepository.getSettings()
        val (entries, tags) = backupDataGateway.exportSnapshot()
        val workbook = WorkbookMapper.toWorkbook(
            entries = entries,
            tags = tags,
            deviceId = settings.deviceId,
        )
        withContext(Dispatchers.IO) {
            zipJsonMediaCodec.write(
                workbook = workbook,
                entries = entries,
                mediaStorage = mediaStorage,
                outputStream = outputStream,
            )
        }
        settingsRepository.updateSyncLinks(
            spreadsheetId = settings.linkedSpreadsheetId,
            mediaFolderId = settings.linkedMediaFolderId,
            lastExportIso = workbook.meta.lastExportIso,
        )
        workbook.meta
    }

    // ---- Offline Dry-Run Import Previews ----

    suspend fun previewCsvImport(inputStream: InputStream): Result<BackupImportSession> = runCatching {
        val remoteWorkbook = withContext(Dispatchers.IO) {
            csvBackupCodec.read(inputStream)
        }
        val diff = buildDiffPreview(
            sourceFormat = BackupFormat.CSV,
            remoteWorkbook = remoteWorkbook,
            extraPresentMediaSha256 = emptySet(),
        )
        BackupImportSession(diffPreview = diff)
    }

    suspend fun previewXlsxImport(inputStream: InputStream): Result<BackupImportSession> = runCatching {
        val remoteWorkbook = withContext(Dispatchers.IO) {
            xlsxBackupCodec.read(inputStream)
        }
        val diff = buildDiffPreview(
            sourceFormat = BackupFormat.XLSX,
            remoteWorkbook = remoteWorkbook,
            extraPresentMediaSha256 = emptySet(),
        )
        BackupImportSession(diffPreview = diff)
    }

    suspend fun previewZipImport(inputStream: InputStream): Result<BackupImportSession> = runCatching {
        val payload = withContext(Dispatchers.IO) {
            zipJsonMediaCodec.read(inputStream = inputStream, mediaStorage = mediaStorage)
        }
        val stagedSha256s = payload.extractedMedia.values
            .map { it.sha256.lowercase(Locale.ROOT) }
            .toSet()
        val diff = buildDiffPreview(
            sourceFormat = BackupFormat.ZIP_JSON_MEDIA,
            remoteWorkbook = payload.workbook,
            extraPresentMediaSha256 = stagedSha256s,
        )
        BackupImportSession(
            diffPreview = diff,
            stagedMedia = payload.extractedMedia,
        )
    }

    // ---- Optional Cloud Sync (Google Sheets + Drive) ----

    suspend fun pushToGoogleSheets(): Result<SyncMetaRecord> = runCatching {
        val accessToken = googleAuthProvider.getAccessToken().getOrThrow()
        val settings = settingsRepository.getSettings()
        val (entries, tags) = backupDataGateway.exportSnapshot()

        val spreadsheetId = settings.linkedSpreadsheetId?.takeIf { it.isNotBlank() }
            ?: googleSheetsClient.createWorkbookSpreadsheet(accessToken).getOrThrow()

        val unuploadedAttachments = entries
            .flatMap { it.attachments }
            .filter { it.driveFileId.isNullOrBlank() }

        var mediaFolderId = settings.linkedMediaFolderId?.takeIf { it.isNotBlank() }
        val uploadedDriveIdsByAttachmentId = LinkedHashMap<String, String>()

        if (unuploadedAttachments.isNotEmpty()) {
            mediaFolderId = googleDriveClient.ensureMediaFolder(
                accessToken = accessToken,
                existingFolderId = mediaFolderId,
            ).getOrThrow()

            val driveIdBySha256 = LinkedHashMap<String, String>()
            for (attachment in unuploadedAttachments) {
                val existingDriveIdForSha = driveIdBySha256[attachment.sha256]
                if (existingDriveIdForSha != null) {
                    uploadedDriveIdsByAttachmentId[attachment.id] = existingDriveIdForSha
                    continue
                }
                val bytes = mediaStorage.readBytes(attachment.localFilename) ?: continue
                val filename = attachment.localFilename.substringAfterLast('/')
                val driveFileId = googleDriveClient.uploadMediaFile(
                    accessToken = accessToken,
                    folderId = mediaFolderId,
                    filename = filename,
                    mimeType = attachment.mimeType,
                    bytes = bytes,
                ).getOrThrow()
                driveIdBySha256[attachment.sha256] = driveFileId
                uploadedDriveIdsByAttachmentId[attachment.id] = driveFileId
            }
        }

        val entriesWithDriveIds = if (uploadedDriveIdsByAttachmentId.isEmpty()) {
            entries
        } else {
            entries.map { details ->
                val updatedAttachments = details.attachments.map { att ->
                    val newDriveId = uploadedDriveIdsByAttachmentId[att.id]
                    if (newDriveId != null) att.copy(driveFileId = newDriveId) else att
                }
                details.copy(attachments = updatedAttachments)
            }
        }

        val workbook = WorkbookMapper.toWorkbook(
            entries = entriesWithDriveIds,
            tags = tags,
            deviceId = settings.deviceId,
        )

        googleSheetsClient.writeWorkbook(
            accessToken = accessToken,
            spreadsheetId = spreadsheetId,
            workbook = workbook,
        ).getOrThrow()

        val entryHashes = entriesWithDriveIds.associate { details ->
            details.entry.id to WorkbookMapper.computeLocalEntrySyncHash(details)
        }
        backupDataGateway.markSynced(
            entryHashes = entryHashes,
            driveFileIdsByAttachmentId = uploadedDriveIdsByAttachmentId,
        )
        settingsRepository.updateSyncLinks(
            spreadsheetId = spreadsheetId,
            mediaFolderId = mediaFolderId,
            lastExportIso = workbook.meta.lastExportIso,
        )

        workbook.meta
    }

    suspend fun previewGoogleSheetsPull(): Result<SyncDiffPreview> = runCatching {
        val accessToken = googleAuthProvider.getAccessToken().getOrThrow()
        val settings = settingsRepository.getSettings()
        val spreadsheetId = settings.linkedSpreadsheetId?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("No linked Google Sheet found. Push a backup first.")

        val remoteWorkbook = googleSheetsClient.readWorkbook(
            accessToken = accessToken,
            spreadsheetId = spreadsheetId,
        ).getOrThrow()

        buildDiffPreview(
            sourceFormat = BackupFormat.GOOGLE_SHEETS,
            remoteWorkbook = remoteWorkbook,
            extraPresentMediaSha256 = emptySet(),
        )
    }

    // ---- Transactional User-Approved Merge Restore ----

    suspend fun applyApprovedRestore(
        diff: SyncDiffPreview,
        resolutions: Map<String, ConflictResolution>,
        extractedOrDownloadedMedia: Map<String, PendingAttachment> = emptyMap(),
    ): Result<Unit> = runCatching {
        val mergedMedia = LinkedHashMap<String, PendingAttachment>(extractedOrDownloadedMedia)

        // For Google Sheets restores, fetch any missing media attachments from Drive before merging.
        if (diff.sourceFormat == BackupFormat.GOOGLE_SHEETS && diff.missingMediaIds.isNotEmpty()) {
            val tokenResult = googleAuthProvider.getAccessToken()
            val accessToken = tokenResult.getOrNull()
            if (accessToken != null) {
                for (rawToken in diff.missingMediaIds) {
                    val tokenRef = WorkbookMapper.parseMediaToken(rawToken) ?: continue
                    if (tokenRef.isLocalPath || tokenRef.primaryId.isBlank()) continue
                    if (tokenRef.sha256.isNotEmpty() && tokenRef.sha256 in mergedMedia) continue

                    val bytes = googleDriveClient.downloadMediaFile(
                        accessToken = accessToken,
                        driveFileId = tokenRef.primaryId,
                    ).getOrNull() ?: continue

                    val mimeType = normalizeSupportedMimeType(tokenRef.mimeType)
                    val pending = runCatching {
                        mediaStorage.writeVerifiedBytes(
                            uuid = UuidV7.generate(),
                            mimeType = mimeType,
                            bytes = bytes,
                        )
                    }.getOrNull() ?: continue

                    mergedMedia[tokenRef.rawToken] = pending
                    mergedMedia[tokenRef.primaryId] = pending
                    mergedMedia[pending.sha256] = pending
                    if (tokenRef.sha256.isNotEmpty()) {
                        mergedMedia[tokenRef.sha256] = pending
                    }
                }
            }
        }

        backupDataGateway.applyMergeRestore(
            diff = diff,
            resolutions = resolutions,
            downloadedMedia = mergedMedia,
        )
    }

    private suspend fun buildDiffPreview(
        sourceFormat: BackupFormat,
        remoteWorkbook: BackupWorkbook,
        extraPresentMediaSha256: Set<String>,
    ): SyncDiffPreview {
        val (localEntries, localTags) = backupDataGateway.exportSnapshot()
        val lastSyncedHashes = backupDataGateway.getLastSyncedContentHashes()

        val existingMediaSha256s = HashSet<String>(extraPresentMediaSha256)
        val remoteSha256s = remoteWorkbook.entries
            .asSequence()
            .filter { !it.deleted }
            .flatMap { it.mediaDriveIds.asSequence() }
            .mapNotNull { WorkbookMapper.parseMediaToken(it)?.sha256 }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()

        for (sha in remoteSha256s) {
            if (sha !in existingMediaSha256s && mediaStorage.findBySha256(sha) != null) {
                existingMediaSha256s += sha
            }
        }

        return syncDiffEngine.computeDiff(
            sourceFormat = sourceFormat,
            localEntries = localEntries,
            localTags = localTags,
            lastSyncedHashes = lastSyncedHashes,
            remoteWorkbook = remoteWorkbook,
            localMediaExistsBySha256 = existingMediaSha256s,
        )
    }

    private fun normalizeSupportedMimeType(mimeType: String?): String =
        when (mimeType?.trim()?.lowercase(Locale.ROOT)) {
            "image/jpeg", "image/jpg" -> "image/jpeg"
            else -> "image/webp"
        }
}
