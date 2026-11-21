package io.github.k8242.threadlog.feature.backup.ui

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.k8242.threadlog.core.model.BackupCadence
import io.github.k8242.threadlog.core.model.BackupFormat
import io.github.k8242.threadlog.core.model.ConflictResolution
import io.github.k8242.threadlog.core.model.SyncDiffPreview
import io.github.k8242.threadlog.core.model.UserSettings
import io.github.k8242.threadlog.core.model.contract.BackupScheduler
import io.github.k8242.threadlog.core.model.contract.GoogleAuthProvider
import io.github.k8242.threadlog.core.model.contract.GoogleAuthState
import io.github.k8242.threadlog.core.model.contract.PendingAttachment
import io.github.k8242.threadlog.core.model.contract.SettingsRepository
import io.github.k8242.threadlog.feature.backup.BackupCoordinator
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * UI state for [BackupScreen], managing offline SAF export/import, optional Google Cloud sync,
 * and mandatory dry-run diff preview with per-conflict resolution choices.
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
)

@HiltViewModel
class BackupViewModel @Inject constructor(
    private val backupCoordinator: BackupCoordinator,
    private val googleAuthProvider: GoogleAuthProvider,
    private val settingsRepository: SettingsRepository,
    private val backupScheduler: BackupScheduler,
) : ViewModel() {

    private val _uiState = MutableStateFlow(BackupUiState())
    val uiState: StateFlow<BackupUiState> = _uiState.asStateFlow()

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

    private fun formatLabel(format: BackupFormat): String = when (format) {
        BackupFormat.CSV -> "CSV"
        BackupFormat.XLSX -> "XLSX"
        BackupFormat.ZIP_JSON_MEDIA -> "ZIP + Media"
        BackupFormat.GOOGLE_SHEETS -> "Google Sheets"
    }
}
