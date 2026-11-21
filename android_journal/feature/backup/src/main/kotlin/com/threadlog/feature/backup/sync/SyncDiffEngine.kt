package com.threadlog.feature.backup.sync

import com.threadlog.core.model.BackupFormat
import com.threadlog.core.model.BackupWorkbook
import com.threadlog.core.model.DiffChangeType
import com.threadlog.core.model.EntryDiffItem
import com.threadlog.core.model.EntryWithDetails
import com.threadlog.core.model.SheetEntryRow
import com.threadlog.core.model.SheetTagRow
import com.threadlog.core.model.SyncDiffPreview
import com.threadlog.core.model.Tag
import java.time.DateTimeException
import java.time.ZoneId
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pure 3-way sync diff engine comparing local journal state, last-synced base hashes, and an
 * incoming [BackupWorkbook] to produce a mandatory dry-run [SyncDiffPreview].
 */
@Singleton
class SyncDiffEngine @Inject constructor() {

    fun computeDiff(
        sourceFormat: BackupFormat,
        localEntries: List<EntryWithDetails>,
        localTags: List<Tag>,
        lastSyncedHashes: Map<String, String>,
        remoteWorkbook: BackupWorkbook,
        localMediaExistsBySha256: Set<String> = emptySet(),
    ): SyncDiffPreview {
        val localById = localEntries.associateBy { it.entry.id }
        val normalizedLocalMediaSha256 = localMediaExistsBySha256
            .map { it.trim().lowercase(Locale.ROOT) }
            .filter { it.isNotEmpty() }
            .toHashSet()

        val newItems = mutableListOf<EntryDiffItem>()
        val editedItems = mutableListOf<EntryDiffItem>()
        val deletedItems = mutableListOf<EntryDiffItem>()
        val conflicts = mutableListOf<EntryDiffItem>()
        val missingMediaTokens = LinkedHashSet<String>()

        for ((index, remoteRow) in remoteWorkbook.entries.withIndex()) {
            val remoteEntryId = remoteRow.entryId.trim()

            // Collect missing media references for active (non-deleted) remote rows.
            if (!remoteRow.deleted) {
                for (mediaToken in remoteRow.mediaDriveIds) {
                    val parsedToken = WorkbookMapper.parseMediaToken(mediaToken) ?: continue
                    val sha256 = parsedToken.sha256
                    if (sha256.isNotEmpty() && sha256 !in normalizedLocalMediaSha256) {
                        missingMediaTokens += parsedToken.rawToken
                    }
                }
            }

            // 1. Blank entry_id: human-added row in Google Sheets / CSV / XLSX.
            if (remoteEntryId.isEmpty()) {
                if (remoteRow.deleted) continue
                newItems += EntryDiffItem(
                    diffKey = "new_row_$index",
                    changeType = DiffChangeType.NEW_FROM_REMOTE,
                    localEntry = null,
                    remoteRow = remoteRow,
                    validationWarning = buildValidationWarning(remoteRow),
                )
                continue
            }

            val local = localById[remoteEntryId]

            // 2. Non-blank entry_id not present locally.
            if (local == null) {
                if (remoteRow.deleted) continue
                newItems += EntryDiffItem(
                    diffKey = remoteEntryId,
                    changeType = DiffChangeType.NEW_FROM_REMOTE,
                    localEntry = null,
                    remoteRow = remoteRow,
                    validationWarning = buildValidationWarning(remoteRow),
                )
                continue
            }

            // 3. Present both locally and remotely: run 3-way hash comparison.
            val localHash = WorkbookMapper.computeLocalEntrySyncHash(local)
            val remoteHash = WorkbookMapper.computeRemoteRowContentHash(remoteRow)
            val baseHash = lastSyncedHashes[remoteEntryId]

            if (localHash == remoteHash) {
                // Identical content -> unchanged, skip.
                continue
            }

            if (remoteRow.deleted && !local.entry.isDeleted) {
                if (baseHash != null && localHash != baseHash) {
                    // Local was edited after last sync AND remote deleted it -> conflict.
                    conflicts += EntryDiffItem(
                        diffKey = remoteEntryId,
                        changeType = DiffChangeType.CONFLICT,
                        localEntry = local,
                        remoteRow = remoteRow,
                    )
                } else {
                    deletedItems += EntryDiffItem(
                        diffKey = remoteEntryId,
                        changeType = DiffChangeType.DELETED_FROM_REMOTE,
                        localEntry = local,
                        remoteRow = remoteRow,
                    )
                }
                continue
            }

            if (baseHash != null && localHash == baseHash && remoteHash != baseHash) {
                editedItems += EntryDiffItem(
                    diffKey = remoteEntryId,
                    changeType = DiffChangeType.EDITED_FROM_REMOTE,
                    localEntry = local,
                    remoteRow = remoteRow,
                    validationWarning = buildValidationWarning(remoteRow),
                )
                continue
            }

            if (baseHash != null && localHash != baseHash && remoteHash == baseHash) {
                // Local-only edit since last sync; no remote change to pull.
                continue
            }

            // Both sides diverged from baseHash, or no baseHash exists and contents differ.
            conflicts += EntryDiffItem(
                diffKey = remoteEntryId,
                changeType = DiffChangeType.CONFLICT,
                localEntry = local,
                remoteRow = remoteRow,
                validationWarning = buildValidationWarning(remoteRow),
            )
        }

        val tagChangesCount = countTagChanges(localTags = localTags, remoteTags = remoteWorkbook.tags)

        return SyncDiffPreview(
            sourceFormat = sourceFormat,
            newItems = newItems,
            editedItems = editedItems,
            deletedItems = deletedItems,
            conflicts = conflicts,
            tagChangesCount = tagChangesCount,
            missingMediaIds = missingMediaTokens.toList(),
            remoteWorkbook = remoteWorkbook,
        )
    }

    private fun countTagChanges(
        localTags: List<Tag>,
        remoteTags: List<SheetTagRow>,
    ): Int {
        val localByPath = localTags.associateBy {
            WorkbookMapper.normalizeTagPath(it.fullPath).lowercase(Locale.ROOT)
        }

        var changes = 0
        val seenRemotePaths = HashSet<String>()

        for (remoteTag in remoteTags) {
            val normalizedPath = WorkbookMapper.normalizeTagPath(remoteTag.fullPath)
            if (normalizedPath.isEmpty()) continue
            val key = normalizedPath.lowercase(Locale.ROOT)
            if (!seenRemotePaths.add(key)) continue

            val local = localByPath[key]
            if (local == null) {
                changes++
            } else {
                val localAliases = local.aliases
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .toSet()
                val remoteAliases = remoteTag.aliases
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .toSet()

                val leafChanged = remoteTag.leafName.isNotBlank() && local.leafName != remoteTag.leafName.trim()
                val colorChanged = remoteTag.colorToken.isNotBlank() && local.colorToken != remoteTag.colorToken.trim()
                val aliasesChanged = localAliases != remoteAliases

                if (leafChanged || colorChanged || aliasesChanged) {
                    changes++
                }
            }
        }

        return changes
    }

    private fun buildValidationWarning(row: SheetEntryRow): String? {
        val warnings = mutableListOf<String>()

        if (row.bodyRaw.isBlank()) {
            warnings += "Empty body text"
        }

        val createdIso = row.createdAtIso.trim()
        if (createdIso.isEmpty()) {
            warnings += "Missing created_at_iso; current time will be used"
        } else if (WorkbookMapper.parseIsoToEpochMs(createdIso, row.zoneId) == null) {
            warnings += "Unparseable timestamp '$createdIso'; current time will be used"
        }

        val zoneId = row.zoneId.trim()
        if (zoneId.isNotEmpty()) {
            try {
                ZoneId.of(zoneId)
            } catch (_: DateTimeException) {
                warnings += "Unknown timezone '$zoneId'; device timezone will be used"
            }
        }

        return warnings.takeIf { it.isNotEmpty() }?.joinToString("; ")
    }
}
