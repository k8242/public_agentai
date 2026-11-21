package io.github.k8242.threadlog.feature.backup.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.k8242.threadlog.core.model.BackupCadence
import io.github.k8242.threadlog.core.model.BackupFormat
import io.github.k8242.threadlog.core.model.ConflictResolution
import io.github.k8242.threadlog.core.model.EntryDiffItem
import io.github.k8242.threadlog.core.model.SyncDiffPreview
import io.github.k8242.threadlog.core.model.contract.GoogleAuthState

@Composable
fun BackupScreen(
    modifier: Modifier = Modifier,
    viewModel: BackupViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val contentResolver = context.contentResolver

    var pendingImportFormat by remember { mutableStateOf<BackupFormat?>(null) }

    val exportCsvLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(MIME_CSV),
    ) { uri ->
        if (uri != null) {
            viewModel.exportToUri(BackupFormat.CSV, uri, contentResolver)
        }
    }

    val exportXlsxLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(MIME_XLSX),
    ) { uri ->
        if (uri != null) {
            viewModel.exportToUri(BackupFormat.XLSX, uri, contentResolver)
        }
    }

    val exportZipLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(MIME_ZIP),
    ) { uri ->
        if (uri != null) {
            viewModel.exportToUri(BackupFormat.ZIP_JSON_MEDIA, uri, contentResolver)
        }
    }

    val importDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val format = pendingImportFormat
        pendingImportFormat = null
        if (uri != null && format != null) {
            viewModel.importPreviewFromUri(format, uri, contentResolver)
        }
    }

    BackupScreen(
        uiState = uiState,
        onExportFormat = { format ->
            when (format) {
                BackupFormat.CSV -> exportCsvLauncher.launch("threadlog-backup.csv")
                BackupFormat.XLSX -> exportXlsxLauncher.launch("threadlog-backup.xlsx")
                BackupFormat.ZIP_JSON_MEDIA -> exportZipLauncher.launch("threadlog-backup.zip")
                BackupFormat.GOOGLE_SHEETS -> viewModel.pushToGoogleSheets()
            }
        },
        onImportFormat = { format ->
            pendingImportFormat = format
            val mimeTypes = when (format) {
                BackupFormat.CSV -> arrayOf(MIME_CSV, "text/comma-separated-values", "text/plain", "*/*")
                BackupFormat.XLSX -> arrayOf(MIME_XLSX, "application/octet-stream", "*/*")
                BackupFormat.ZIP_JSON_MEDIA -> arrayOf(MIME_ZIP, "application/octet-stream", "*/*")
                BackupFormat.GOOGLE_SHEETS -> return@BackupScreen
            }
            importDocumentLauncher.launch(mimeTypes)
        },
        onPushCloudBackup = viewModel::pushToGoogleSheets,
        onPullCloudPreview = viewModel::previewGoogleSheetsPull,
        onUpdateCadence = viewModel::updateCadence,
        onSignOut = viewModel::signOut,
        onSetConflictResolution = viewModel::setConflictResolution,
        onConfirmApplyRestore = viewModel::confirmApplyRestore,
        onDismissDiffPreview = viewModel::dismissDiffPreview,
        onClearMessages = viewModel::clearMessages,
        modifier = modifier,
    )
}

@Composable
fun BackupScreen(
    uiState: BackupUiState,
    onExportFormat: (BackupFormat) -> Unit,
    onImportFormat: (BackupFormat) -> Unit,
    onPushCloudBackup: () -> Unit,
    onPullCloudPreview: () -> Unit,
    onUpdateCadence: (BackupCadence) -> Unit,
    onSignOut: () -> Unit,
    onSetConflictResolution: (String, ConflictResolution) -> Unit,
    onConfirmApplyRestore: () -> Unit,
    onDismissDiffPreview: () -> Unit,
    onClearMessages: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Backup & Restore",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )

        if (uiState.isBusy) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        uiState.errorMessage?.let { error ->
            StatusBanner(
                message = error,
                isError = true,
                onDismiss = onClearMessages,
            )
        }

        uiState.statusMessage?.let { status ->
            StatusBanner(
                message = status,
                isError = false,
                onDismiss = onClearMessages,
            )
        }

        // Mandatory Dry-Run Diff & Conflict Resolution Preview section whenever a pull/import is staged.
        uiState.pendingDiff?.let { diff ->
            DryRunDiffPreviewCard(
                diff = diff,
                conflictResolutions = uiState.conflictResolutions,
                isBusy = uiState.isBusy,
                onSetConflictResolution = onSetConflictResolution,
                onConfirmApply = onConfirmApplyRestore,
                onCancel = onDismissDiffPreview,
            )
        }

        OfflineBackupCard(
            isBusy = uiState.isBusy,
            lastExportIso = uiState.settings.lastExportIso,
            onExportFormat = onExportFormat,
            onImportFormat = onImportFormat,
        )

        CloudSyncCard(
            uiState = uiState,
            onPushCloudBackup = onPushCloudBackup,
            onPullCloudPreview = onPullCloudPreview,
            onUpdateCadence = onUpdateCadence,
            onSignOut = onSignOut,
        )
    }
}

@Composable
private fun StatusBanner(
    message: String,
    isError: Boolean,
    onDismiss: () -> Unit,
) {
    val containerColor = if (isError) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }
    val contentColor = if (isError) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer
    }

    Surface(
        color = containerColor,
        contentColor = contentColor,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (isError) Icons.Default.Warning else Icons.Default.Info,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss) {
                Text("Dismiss", color = contentColor)
            }
        }
    }
}

@Composable
private fun OfflineBackupCard(
    isBusy: Boolean,
    lastExportIso: String?,
    onExportFormat: (BackupFormat) -> Unit,
    onImportFormat: (BackupFormat) -> Unit,
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.FolderZip,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Offline Backup & Restore",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Text(
                text = "Works immediately with zero cloud setup. Every import previews changes and conflicts before writing to your journal.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (!lastExportIso.isNullOrBlank()) {
                Text(
                    text = "Last export: $lastExportIso",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider()

            OfflineFormatRow(
                title = "JSON + Media Archive (.zip)",
                subtitle = "Full 3-tab workbook plus compressed image attachments",
                isBusy = isBusy,
                onExport = { onExportFormat(BackupFormat.ZIP_JSON_MEDIA) },
                onImport = { onImportFormat(BackupFormat.ZIP_JSON_MEDIA) },
            )

            OfflineFormatRow(
                title = "Excel Workbook (.xlsx)",
                subtitle = "3-sheet workbook (Entries, TagTree, SyncMeta)",
                isBusy = isBusy,
                onExport = { onExportFormat(BackupFormat.XLSX) },
                onImport = { onImportFormat(BackupFormat.XLSX) },
            )

            OfflineFormatRow(
                title = "Entries CSV (.csv)",
                subtitle = "RFC 4180 spreadsheet table for Entries",
                isBusy = isBusy,
                onExport = { onExportFormat(BackupFormat.CSV) },
                onImport = { onImportFormat(BackupFormat.CSV) },
            )
        }
    }
}

@Composable
private fun OfflineFormatRow(
    title: String,
    subtitle: String,
    isBusy: Boolean,
    onExport: () -> Unit,
    onImport: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            OutlinedButton(
                onClick = onExport,
                enabled = !isBusy,
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    imageVector = Icons.Default.Upload,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Export")
            }
            OutlinedButton(
                onClick = onImport,
                enabled = !isBusy,
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    imageVector = Icons.Default.Download,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Import")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CloudSyncCard(
    uiState: BackupUiState,
    onPushCloudBackup: () -> Unit,
    onPullCloudPreview: () -> Unit,
    onUpdateCadence: (BackupCadence) -> Unit,
    onSignOut: () -> Unit,
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = when (uiState.authState) {
                        is GoogleAuthState.Connected -> Icons.Default.CloudDone
                        GoogleAuthState.SignedOut -> Icons.Default.CloudSync
                        GoogleAuthState.NotConfigured -> Icons.Default.CloudOff
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Google Sheets & Drive Sync (Optional)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            when (val auth = uiState.authState) {
                GoogleAuthState.NotConfigured -> {
                    Text(
                        text = "Cloud sync is not configured in this build. Offline CSV, XLSX, and ZIP+Media backups above work immediately with no account required.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = "To enable optional Google Sheets & Drive sync (scoped strictly to drive.file), set GOOGLE_WEB_CLIENT_ID in your build configuration.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                GoogleAuthState.SignedOut -> {
                    Text(
                        text = "Signed out. Connect a Google account with drive.file permission to push a 3-tab Sheet and media folder, or use offline exports above.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                is GoogleAuthState.Connected -> {
                    Text(
                        text = "Connected as ${auth.email ?: "Google account"}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    uiState.settings.linkedSpreadsheetId?.takeIf { it.isNotBlank() }?.let { sheetId ->
                        Text(
                            text = "Linked Sheet ID: $sheetId",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Button(
                            onClick = onPushCloudBackup,
                            enabled = !uiState.isBusy,
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(
                                imageVector = Icons.Default.TableChart,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Push Backup")
                        }
                        OutlinedButton(
                            onClick = onPullCloudPreview,
                            enabled = !uiState.isBusy,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Pull & Preview")
                        }
                    }

                    HorizontalDivider()

                    Text(
                        text = "Scheduled Cloud Backup",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        BackupCadence.entries.forEach { cadence ->
                            FilterChip(
                                selected = uiState.settings.scheduledBackupCadence == cadence,
                                onClick = { onUpdateCadence(cadence) },
                                enabled = !uiState.isBusy,
                                label = {
                                    Text(
                                        when (cadence) {
                                            BackupCadence.OFF -> "Off"
                                            BackupCadence.DAILY -> "Daily"
                                            BackupCadence.WEEKLY -> "Weekly"
                                        },
                                    )
                                },
                            )
                        }
                    }

                    TextButton(
                        onClick = onSignOut,
                        enabled = !uiState.isBusy,
                    ) {
                        Text("Sign Out")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DryRunDiffPreviewCard(
    diff: SyncDiffPreview,
    conflictResolutions: Map<String, ConflictResolution>,
    isBusy: Boolean,
    onSetConflictResolution: (String, ConflictResolution) -> Unit,
    onConfirmApply: () -> Unit,
    onCancel: () -> Unit,
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Dry-Run Restore Preview (${diff.sourceFormat.name})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                DiffCountPill("New", diff.newItems.size)
                DiffCountPill("Edited", diff.editedItems.size)
                DiffCountPill("Deleted", diff.deletedItems.size)
                DiffCountPill("Conflicts", diff.conflicts.size, highlight = diff.hasConflicts)
                DiffCountPill("Tags", diff.tagChangesCount)
                if (diff.missingMediaIds.isNotEmpty()) {
                    DiffCountPill("Missing Media", diff.missingMediaIds.size, highlight = true)
                }
            }

            if (diff.isEmpty) {
                Text(
                    text = "Already up to date — no remote changes to apply.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            val warnedItems = (diff.newItems + diff.editedItems + diff.conflicts)
                .filter { !it.validationWarning.isNullOrBlank() }
            if (warnedItems.isNotEmpty()) {
                HorizontalDivider()
                Text(
                    text = "Validation Warnings (${warnedItems.size})",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.error,
                )
                warnedItems.take(5).forEach { item ->
                    Text(
                        text = "• ${item.diffKey}: ${item.validationWarning}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            if (diff.hasConflicts) {
                HorizontalDivider()
                Text(
                    text = "Resolve Conflicts (${diff.conflicts.size})",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                diff.conflicts.forEach { conflict ->
                    ConflictResolutionItem(
                        conflict = conflict,
                        selectedResolution = conflictResolutions[conflict.diffKey]
                            ?: ConflictResolution.KEEP_PHONE,
                        enabled = !isBusy,
                        onSelect = { resolution ->
                            onSetConflictResolution(conflict.diffKey, resolution)
                        },
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedButton(
                    onClick = onCancel,
                    enabled = !isBusy,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Cancel")
                }
                Button(
                    onClick = onConfirmApply,
                    enabled = !isBusy && !diff.isEmpty,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Apply Restore")
                }
            }
        }
    }
}

@Composable
private fun DiffCountPill(
    label: String,
    count: Int,
    highlight: Boolean = false,
) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (highlight) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        contentColor = if (highlight) {
            MaterialTheme.colorScheme.onErrorContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    ) {
        Text(
            text = "$label: $count",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConflictResolutionItem(
    conflict: EntryDiffItem,
    selectedResolution: ConflictResolution,
    enabled: Boolean,
    onSelect: (ConflictResolution) -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "Entry ${conflict.diffKey}",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Phone: ${conflict.localEntry?.entry?.bodyRaw?.take(120) ?: "(deleted)"}",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val remoteSummary = when {
                conflict.remoteRow == null -> "(missing)"
                conflict.remoteRow!!.deleted -> "(deleted remotely)"
                else -> conflict.remoteRow!!.bodyRaw.take(120)
            }
            Text(
                text = "Sheet: $remoteSummary",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ResolutionChip(
                    label = "Keep Phone",
                    selected = selectedResolution == ConflictResolution.KEEP_PHONE,
                    enabled = enabled,
                    onClick = { onSelect(ConflictResolution.KEEP_PHONE) },
                )
                ResolutionChip(
                    label = "Keep Sheet",
                    selected = selectedResolution == ConflictResolution.KEEP_SHEET,
                    enabled = enabled,
                    onClick = { onSelect(ConflictResolution.KEEP_SHEET) },
                )
                ResolutionChip(
                    label = "Keep Both",
                    selected = selectedResolution == ConflictResolution.KEEP_BOTH,
                    enabled = enabled,
                    onClick = { onSelect(ConflictResolution.KEEP_BOTH) },
                )
            }
        }
    }
}

@Composable
private fun ResolutionChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        label = { Text(label) },
    )
}

private const val MIME_CSV = "text/csv"
private const val MIME_XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
private const val MIME_ZIP = "application/zip"
