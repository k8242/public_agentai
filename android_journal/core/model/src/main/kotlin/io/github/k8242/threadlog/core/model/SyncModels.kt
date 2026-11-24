// Copyright (c) 2026 k8242
// SPDX-License-Identifier: MIT

package io.github.k8242.threadlog.core.model

import kotlinx.serialization.Serializable

@Serializable
data class SheetEntryRow(
    val entryId: String,               // Blank when human-added in Sheets/CSV/XLSX
    val createdAtIso: String,
    val zoneId: String,
    val bodyRaw: String,
    val visibleTags: List<String>,
    val hiddenAutoTags: List<String>,
    val rejectedTags: List<String>,
    val bookmarksPipe: String,         // "url|title;;url2|title2"
    val mediaDriveIds: List<String>,   // "<driveId>:<sha256>:<mime>" or "<filename>:<sha256>"
    val replyToId: String? = null,
    val updatedAtIso: String? = null,
    val deleted: Boolean,
    val taskStatus: String = "NONE",
    val reactions: List<String> = emptyList(),
    val taskPriority: String = "NONE",
    val isHighlighted: Boolean = false,
    val archivedAtIso: String? = null,
)

@Serializable
data class SheetTagRow(
    val tagId: String,
    val fullPath: String,
    val leafName: String,
    val rootCategory: String,
    val colorToken: String,
    val aliases: List<String>,
)

@Serializable
data class SyncMetaRecord(
    val schemaVersion: Int = 1,
    val lastExportIso: String,
    val deviceId: String,
    val entryCount: Int,
    val checksum: String,
)

@Serializable
data class BackupWorkbook(
    val entries: List<SheetEntryRow>,
    val tags: List<SheetTagRow>,
    val meta: SyncMetaRecord,
)

enum class DiffChangeType {
    NEW_FROM_REMOTE,
    EDITED_FROM_REMOTE,
    DELETED_FROM_REMOTE,
    CONFLICT,
}

enum class ConflictResolution {
    KEEP_PHONE,
    KEEP_SHEET,
    KEEP_BOTH,
}

data class EntryDiffItem(
    val diffKey: String,               // entryId or synthetic row index for blank-ID human rows
    val changeType: DiffChangeType,
    val localEntry: EntryWithDetails?,
    val remoteRow: SheetEntryRow?,
    val validationWarning: String? = null,
)

data class SyncDiffPreview(
    val sourceFormat: BackupFormat,
    val newItems: List<EntryDiffItem>,
    val editedItems: List<EntryDiffItem>,
    val deletedItems: List<EntryDiffItem>,
    val conflicts: List<EntryDiffItem>,
    val tagChangesCount: Int,
    val missingMediaIds: List<String>,
    val remoteWorkbook: BackupWorkbook,
) {
    val hasConflicts: Boolean get() = conflicts.isNotEmpty()
    val isEmpty: Boolean get() =
        newItems.isEmpty() && editedItems.isEmpty() && deletedItems.isEmpty() && conflicts.isEmpty() && tagChangesCount == 0
}
