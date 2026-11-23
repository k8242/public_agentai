// Copyright (c) 2026 k8242
// SPDX-License-Identifier: MIT

package io.github.k8242.threadlog.feature.backup.ui

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.k8242.threadlog.core.model.BackupCadence
import io.github.k8242.threadlog.core.model.BackupFormat
import io.github.k8242.threadlog.core.model.ConflictResolution
import io.github.k8242.threadlog.core.model.SyncDiffPreview
import io.github.k8242.threadlog.core.model.TaskStatus
import io.github.k8242.threadlog.core.model.UserSettings
import io.github.k8242.threadlog.core.model.contract.AutoTagScheduler
import io.github.k8242.threadlog.core.model.contract.BackupDataGateway
import io.github.k8242.threadlog.core.model.contract.BackupScheduler
import io.github.k8242.threadlog.core.model.contract.EntryRepository
import io.github.k8242.threadlog.core.model.contract.GoogleAuthProvider
import io.github.k8242.threadlog.core.model.contract.GoogleAuthState
import io.github.k8242.threadlog.core.model.contract.PendingAttachment
import io.github.k8242.threadlog.core.model.contract.SettingsRepository
import io.github.k8242.threadlog.core.parser.CredentialCheckResult
import io.github.k8242.threadlog.core.parser.SensitiveCredentialGuard
import io.github.k8242.threadlog.core.parser.WhatsAppDateOrder
import io.github.k8242.threadlog.core.parser.WhatsAppExportParser
import io.github.k8242.threadlog.feature.backup.BackupCoordinator
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * UI state for [BackupScreen], managing offline SAF export/import, WhatsApp text/file import
 * with interactive review, optional Google Cloud sync, and mandatory dry-run diff preview.
 */
data class BackupUiState(
    val authState: GoogleAuthState = GoogleAuthState.NotConfigured,
    val settings: UserSettings = UserSettings(),
    val isBusy: Boolean = false,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
    val pendingDiff: SyncDiffPreview? = null,
    val conflictResolutions: Map<String, ConflictResolution> = emptyMap(),
    val stagedMedia: Map<String, PendingAttachment> = emptyMap(),
    val whatsAppDraft: WhatsAppImportDraft? = null,
)

@HiltViewModel
class BackupViewModel @Inject constructor(
    private val backupCoordinator: BackupCoordinator,
    private val entryRepository: EntryRepository,
    private val backupDataGateway: BackupDataGateway,
    private val autoTagScheduler: AutoTagScheduler,
    private val googleAuthProvider: GoogleAuthProvider,
    private val settingsRepository: SettingsRepository,
    private val backupScheduler: BackupScheduler,
) : ViewModel() {

    private val _uiState = MutableStateFlow(BackupUiState())
    val uiState: StateFlow<BackupUiState> = _uiState.asStateFlow()

    /** Cached `(createdAtMs, normalizedBodyRaw)` keys of existing active entries for WhatsApp deduplication. */
    private var existingEntryKeys: Set<Pair<Long, String>> = emptySet()

    init {
        viewModelScope.launch {
            combine(
                googleAuthProvider.observeAuthState(),
                settingsRepository.settings,
            ) { authState, settings ->
                authState to settings
            }.collect { (authState, settings) ->
                _uiState.update { current ->
                    current.copy(
                        authState = authState,
                        settings = settings,
                    )
                }
            }
        }
    }

    fun exportToUri(
        format: BackupFormat,
        uri: Uri,
        contentResolver: ContentResolver,
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, statusMessage = null, errorMessage = null) }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val outputStream = contentResolver.openOutputStream(uri)
                        ?: throw IOException("Unable to open destination file for writing")
                    outputStream.use { stream ->
                        when (format) {
                            BackupFormat.CSV -> backupCoordinator.exportCsv(stream).getOrThrow()
                            BackupFormat.XLSX -> backupCoordinator.exportXlsx(stream).getOrThrow()
                            BackupFormat.ZIP_JSON_MEDIA -> backupCoordinator.exportZip(stream).getOrThrow()
                            BackupFormat.GOOGLE_SHEETS ->
                                throw IllegalArgumentException("Use pushToGoogleSheets() for cloud export")
                        }
                    }
                }
            }

            result.fold(
                onSuccess = { meta ->
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            statusMessage = "Exported ${meta.entryCount} entries (${formatLabel(format)}).",
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            errorMessage = error.message ?: "Export failed.",
                        )
                    }
                },
            )
        }
    }

    fun importPreviewFromUri(
        format: BackupFormat,
        uri: Uri,
        contentResolver: ContentResolver,
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, statusMessage = null, errorMessage = null) }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val inputStream = contentResolver.openInputStream(uri)
                        ?: throw IOException("Unable to open selected backup file")
                    inputStream.use { stream ->
                        when (format) {
                            BackupFormat.CSV -> backupCoordinator.previewCsvImport(stream).getOrThrow()
                            BackupFormat.XLSX -> backupCoordinator.previewXlsxImport(stream).getOrThrow()
                            BackupFormat.ZIP_JSON_MEDIA -> backupCoordinator.previewZipImport(stream).getOrThrow()
                            BackupFormat.GOOGLE_SHEETS ->
                                throw IllegalArgumentException("Use previewGoogleSheetsPull() for cloud pull")
                        }
                    }
                }
            }

            result.fold(
                onSuccess = { session ->
                    val defaultResolutions = session.diffPreview.conflicts
                        .associate { it.diffKey to ConflictResolution.KEEP_PHONE }
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            pendingDiff = session.diffPreview,
                            conflictResolutions = defaultResolutions,
                            stagedMedia = session.stagedMedia,
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            errorMessage = error.message ?: "Failed to read backup file.",
                        )
                    }
                },
            )
        }
    }

    fun pushToGoogleSheets() {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, statusMessage = null, errorMessage = null) }
            backupCoordinator.pushToGoogleSheets().fold(
                onSuccess = { meta ->
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            statusMessage = "Backed up ${meta.entryCount} entries to Google Sheets.",
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            errorMessage = error.message ?: "Google Sheets backup failed.",
                        )
                    }
                },
            )
        }
    }

    fun previewGoogleSheetsPull() {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, statusMessage = null, errorMessage = null) }
            backupCoordinator.previewGoogleSheetsPull().fold(
                onSuccess = { diff ->
                    val defaultResolutions = diff.conflicts
                        .associate { it.diffKey to ConflictResolution.KEEP_PHONE }
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            pendingDiff = diff,
                            conflictResolutions = defaultResolutions,
                            stagedMedia = emptyMap(),
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            errorMessage = error.message ?: "Failed to fetch Google Sheet diff.",
                        )
                    }
                },
            )
        }
    }

    fun setConflictResolution(diffKey: String, resolution: ConflictResolution) {
        _uiState.update { current ->
            current.copy(
                conflictResolutions = current.conflictResolutions + (diffKey to resolution),
            )
        }
    }

    fun confirmApplyRestore() {
        val currentState = _uiState.value
        val diff = currentState.pendingDiff ?: return

        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, statusMessage = null, errorMessage = null) }
            backupCoordinator.applyApprovedRestore(
                diff = diff,
                resolutions = currentState.conflictResolutions,
                extractedOrDownloadedMedia = currentState.stagedMedia,
            ).fold(
                onSuccess = {
                    val totalApplied = diff.newItems.size +
                        diff.editedItems.size +
                        diff.deletedItems.size +
                        diff.conflicts.size
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            pendingDiff = null,
                            conflictResolutions = emptyMap(),
                            stagedMedia = emptyMap(),
                            statusMessage = "Restore applied ($totalApplied entry changes, ${diff.tagChangesCount} tag updates).",
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            errorMessage = error.message ?: "Restore failed.",
                        )
                    }
                },
            )
        }
    }

    fun dismissDiffPreview() {
        _uiState.update {
            it.copy(
                pendingDiff = null,
                conflictResolutions = emptyMap(),
                stagedMedia = emptyMap(),
            )
        }
    }

    fun updateCadence(cadence: BackupCadence) {
        viewModelScope.launch {
            settingsRepository.setScheduledBackupCadence(cadence)
            backupScheduler.updateSchedule(cadence)
            _uiState.update {
                it.copy(
                    statusMessage = when (cadence) {
                        BackupCadence.OFF -> "Scheduled cloud backup disabled."
                        BackupCadence.DAILY -> "Daily scheduled cloud backup enabled."
                        BackupCadence.WEEKLY -> "Weekly scheduled cloud backup enabled."
                    },
                    errorMessage = null,
                )
            }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, statusMessage = null, errorMessage = null) }
            googleAuthProvider.signOut()
            _uiState.update {
                it.copy(
                    isBusy = false,
                    statusMessage = "Signed out of Google account.",
                )
            }
        }
    }

    fun clearMessages() {
        _uiState.update { it.copy(statusMessage = null, errorMessage = null) }
    }

    // ---- WhatsApp Export Import (Paste Text or .txt File with Interactive Review) ----

    fun startWhatsAppImportFromText(
        rawText: String,
        sourceLabel: String = "Pasted text",
    ) {
        if (rawText.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Please paste WhatsApp chat text first.") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, statusMessage = null, errorMessage = null) }
            try {
                existingEntryKeys = loadExistingEntryKeys()
                val draft = buildWhatsAppDraft(
                    rawText = rawText,
                    sourceLabel = sourceLabel,
                    zoneId = ZoneId.systemDefault().id,
                    dateOrder = WhatsAppDateOrder.MDY,
                    includeSenderName = false,
                    skipMediaOmitted = false,
                )
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        whatsAppDraft = draft,
                        errorMessage = if (draft.items.isEmpty() && draft.dateOrderMismatchCount == 0) {
                            "No WhatsApp messages found. Expected lines like: 8/13/22, 2:11 PM - Name: message"
                        } else {
                            null
                        },
                    )
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        errorMessage = e.message ?: "Failed to parse WhatsApp export.",
                    )
                }
            }
        }
    }

    fun startWhatsAppImportFromUri(
        uri: Uri,
        contentResolver: ContentResolver,
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, statusMessage = null, errorMessage = null) }
            val textResult = withContext(Dispatchers.IO) {
                runCatching {
                    val stream = contentResolver.openInputStream(uri)
                        ?: throw IOException("Unable to open selected text file")
                    stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                }
            }
            textResult.fold(
                onSuccess = { text ->
                    startWhatsAppImportFromText(rawText = text, sourceLabel = "WhatsApp .txt")
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            errorMessage = error.message ?: "Failed to read WhatsApp text file.",
                        )
                    }
                },
            )
        }
    }

    fun updateWhatsAppZoneId(zoneId: String) {
        val current = _uiState.value.whatsAppDraft ?: return
        if (current.zoneId == zoneId) return
        val rebuilt = buildWhatsAppDraft(
            rawText = current.rawText,
            sourceLabel = current.sourceLabel,
            zoneId = zoneId,
            dateOrder = current.dateOrder,
            includeSenderName = current.includeSenderName,
            skipMediaOmitted = current.skipMediaOmitted,
            previousItemsByIndex = current.items.associateBy { it.index },
        )
        _uiState.update { it.copy(whatsAppDraft = rebuilt) }
    }

    fun updateWhatsAppDateOrder(dateOrder: WhatsAppDateOrder) {
        val current = _uiState.value.whatsAppDraft ?: return
        if (current.dateOrder == dateOrder) return
        val rebuilt = buildWhatsAppDraft(
            rawText = current.rawText,
            sourceLabel = current.sourceLabel,
            zoneId = current.zoneId,
            dateOrder = dateOrder,
            includeSenderName = current.includeSenderName,
            skipMediaOmitted = current.skipMediaOmitted,
        )
        _uiState.update { it.copy(whatsAppDraft = rebuilt) }
    }

    fun toggleWhatsAppIncludeSender(includeSenderName: Boolean) {
        val current = _uiState.value.whatsAppDraft ?: return
        val updatedItems = current.items.map { item ->
            val effectiveBody = item.resolvedBody(includeSenderName)
            val isDup = (item.createdAtMs to effectiveBody.trim()) in existingEntryKeys
            val warning = credentialWarningFor(effectiveBody)
            item.copy(
                isDuplicate = isDup,
                credentialWarning = warning,
            )
        }
        _uiState.update {
            it.copy(
                whatsAppDraft = current.copy(
                    includeSenderName = includeSenderName,
                    items = updatedItems,
                ),
            )
        }
    }

    fun toggleWhatsAppSkipMediaOmitted(skipMediaOmitted: Boolean) {
        val current = _uiState.value.whatsAppDraft ?: return
        _uiState.update {
            it.copy(whatsAppDraft = current.copy(skipMediaOmitted = skipMediaOmitted))
        }
    }

    fun toggleWhatsAppItemSelected(index: Int) {
        val current = _uiState.value.whatsAppDraft ?: return
        val updated = current.items.map { item ->
            if (item.index == index) item.copy(selected = !item.selected) else item
        }
        _uiState.update { it.copy(whatsAppDraft = current.copy(items = updated)) }
    }

    fun setAllWhatsAppItemsSelected(selected: Boolean) {
        val current = _uiState.value.whatsAppDraft ?: return
        val updated = current.items.map { item ->
            if (current.skipMediaOmitted && item.isMediaOmitted) item else item.copy(selected = selected)
        }
        _uiState.update { it.copy(whatsAppDraft = current.copy(items = updated)) }
    }

    fun toggleWhatsAppItemTask(index: Int) {
        val current = _uiState.value.whatsAppDraft ?: return
        val updated = current.items.map { item ->
            if (item.index == index) {
                val nextTask = if (item.taskStatus == TaskStatus.TODO) TaskStatus.NONE else TaskStatus.TODO
                item.copy(taskStatus = nextTask)
            } else {
                item
            }
        }
        _uiState.update { it.copy(whatsAppDraft = current.copy(items = updated)) }
    }

    fun updateWhatsAppItemBody(index: Int, newBody: String) {
        val current = _uiState.value.whatsAppDraft ?: return
        val updated = current.items.map { item ->
            if (item.index == index) {
                val warning = credentialWarningFor(newBody)
                val isDup = (item.createdAtMs to newBody.trim()) in existingEntryKeys
                item.copy(
                    editedBody = newBody,
                    isDuplicate = isDup,
                    credentialWarning = warning,
                )
            } else {
                item
            }
        }
        _uiState.update { it.copy(whatsAppDraft = current.copy(items = updated)) }
    }

    fun maskWhatsAppItemCredentials(index: Int) {
        val current = _uiState.value.whatsAppDraft ?: return
        val target = current.items.firstOrNull { it.index == index } ?: return
        val masked = SensitiveCredentialGuard.maskDetectedWithXxxx(target.resolvedBody(current.includeSenderName))
        updateWhatsAppItemBody(index, masked)
    }

    fun maskAllWhatsAppCredentials() {
        val current = _uiState.value.whatsAppDraft ?: return
        val updated = current.items.map { item ->
            if (item.credentialWarning != null) {
                val masked = SensitiveCredentialGuard.maskDetectedWithXxxx(
                    item.resolvedBody(current.includeSenderName),
                )
                item.copy(
                    editedBody = masked,
                    isDuplicate = (item.createdAtMs to masked.trim()) in existingEntryKeys,
                    credentialWarning = credentialWarningFor(masked),
                )
            } else {
                item
            }
        }
        _uiState.update { it.copy(whatsAppDraft = current.copy(items = updated)) }
    }

    fun dismissWhatsAppImport() {
        _uiState.update { it.copy(whatsAppDraft = null) }
    }

    fun confirmWhatsAppImport() {
        val draft = _uiState.value.whatsAppDraft ?: return
        val toImport = draft.selectedItems
        if (toImport.isEmpty()) return
        if (draft.blockedSelectedCount > 0) {
            _uiState.update {
                it.copy(
                    errorMessage = "Mask or uncheck blocked password/PIN entries before importing.",
                )
            }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, statusMessage = null, errorMessage = null) }
            try {
                var importedCount = 0
                for (item in toImport.sortedBy { it.createdAtMs }) {
                    val body = item.resolvedBody(draft.includeSenderName)
                    if (body.isBlank()) continue
                    val created = entryRepository.createEntry(
                        bodyRaw = body,
                        userTagIds = emptyList(),
                        replyToId = null,
                        pendingAttachments = emptyList(),
                        nowMs = item.createdAtMs,
                        zoneId = item.zoneId,
                        taskStatus = item.taskStatus,
                    )
                    autoTagScheduler.enqueueAutoTag(created.entry.id)
                    importedCount++
                }
                existingEntryKeys = emptySet()
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        whatsAppDraft = null,
                        statusMessage = "Imported $importedCount WhatsApp note${if (importedCount == 1) "" else "s"} (${draft.zoneId}).",
                    )
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        errorMessage = e.message ?: "WhatsApp import failed.",
                    )
                }
            }
        }
    }

    private suspend fun loadExistingEntryKeys(): Set<Pair<Long, String>> =
        runCatching {
            val (entries, _) = backupDataGateway.exportSnapshot()
            entries
                .filter { !it.entry.isDeleted }
                .map { it.entry.createdAtMs to it.entry.bodyRaw.trim() }
                .toSet()
        }.getOrDefault(emptySet())

    private fun buildWhatsAppDraft(
        rawText: String,
        sourceLabel: String,
        zoneId: String,
        dateOrder: WhatsAppDateOrder,
        includeSenderName: Boolean,
        skipMediaOmitted: Boolean,
        previousItemsByIndex: Map<Int, WhatsAppReviewItem> = emptyMap(),
    ): WhatsAppImportDraft {
        val parsed = WhatsAppExportParser.parse(
            rawText = rawText,
            zoneId = zoneId,
            dateOrder = dateOrder,
        )
        val reviewItems = parsed.messages.map { msg ->
            val previous = previousItemsByIndex[msg.index]
            val editedBody = previous?.editedBody
            val effectiveBody = editedBody ?: msg.effectiveBody(includeSenderName)
            val isDup = (msg.createdAtMs to effectiveBody.trim()) in existingEntryKeys
            val defaultSelected = !msg.isSystemMessage && !isDup
            WhatsAppReviewItem(
                index = msg.index,
                createdAtMs = msg.createdAtMs,
                zoneId = msg.zoneId,
                sender = msg.sender,
                rawMessageBody = msg.messageBody,
                editedBody = editedBody,
                selected = previous?.selected ?: defaultSelected,
                isMediaOmitted = msg.isMediaOmitted,
                isSystemMessage = msg.isSystemMessage,
                isDuplicate = isDup,
                taskStatus = previous?.taskStatus ?: TaskStatus.NONE,
                credentialWarning = credentialWarningFor(effectiveBody),
            )
        }
        return WhatsAppImportDraft(
            rawText = rawText,
            sourceLabel = sourceLabel,
            zoneId = parsed.zoneId,
            dateOrder = parsed.dateOrder,
            includeSenderName = includeSenderName,
            skipMediaOmitted = skipMediaOmitted,
            dateOrderMismatchCount = parsed.dateOrderMismatchCount,
            items = reviewItems,
        )
    }

    private fun credentialWarningFor(text: String): String? =
        when (val result = SensitiveCredentialGuard.validate(text)) {
            CredentialCheckResult.Allowed -> null
            is CredentialCheckResult.Rejected -> result.message
        }

    private fun formatLabel(format: BackupFormat): String = when (format) {
        BackupFormat.CSV -> "CSV"
        BackupFormat.XLSX -> "XLSX"
        BackupFormat.ZIP_JSON_MEDIA -> "ZIP + Media"
        BackupFormat.GOOGLE_SHEETS -> "Google Sheets"
    }
}
