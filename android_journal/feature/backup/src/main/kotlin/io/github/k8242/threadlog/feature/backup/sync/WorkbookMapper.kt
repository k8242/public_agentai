// Copyright (c) 2026 k8242
// SPDX-License-Identifier: MIT

package io.github.k8242.threadlog.feature.backup.sync

import io.github.k8242.threadlog.core.model.Attachment
import io.github.k8242.threadlog.core.model.BackupWorkbook
import io.github.k8242.threadlog.core.model.Bookmark
import io.github.k8242.threadlog.core.model.EntryWithDetails
import io.github.k8242.threadlog.core.model.SheetEntryRow
import io.github.k8242.threadlog.core.model.SheetTagRow
import io.github.k8242.threadlog.core.model.SyncMetaRecord
import io.github.k8242.threadlog.core.model.Tag
import io.github.k8242.threadlog.core.model.TagRootCategory
import io.github.k8242.threadlog.core.parser.ContentHasher
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Parsed reference from a `media_drive_ids` token:
 * either `"<driveId>:<sha256>:<mime>"` or `"<localFilename>:<sha256>:<mime>"` (or 2-part `"<id>:<sha256>"`).
 */
data class MediaTokenRef(
    val rawToken: String,
    val primaryId: String,
    val sha256: String,
    val mimeType: String?,
) {
    val isLocalPath: Boolean
        get() = primaryId.startsWith("media/") || primaryId.contains('/')
}

/**
 * Bidirectional mapper between ThreadLog domain snapshots and the 3-tab [BackupWorkbook]
 * (`Entries`, `TagTree`, `SyncMeta`) plus canonical 3-way sync content hashing.
 */
object WorkbookMapper {

    const val SCHEMA_VERSION: Int = 1
    const val PROVENANCE_APP_NAME: String = "ThreadLog"
    const val PROVENANCE_SCHEMA_ORIGIN: String = "io.github.k8242.threadlog"
    const val PROVENANCE_GENERATOR: String = "$PROVENANCE_APP_NAME ($PROVENANCE_SCHEMA_ORIGIN)"
    const val PROVENANCE_UPSTREAM_URL: String = "https://github.com/k8242/public_agentai"
    const val PROVENANCE_LICENSE: String = "MIT"

    const val TAB_ENTRIES: String = "Entries"
    const val TAB_TAG_TREE: String = "TagTree"
    const val TAB_SYNC_META: String = "SyncMeta"

    val ENTRIES_HEADERS: List<String> = listOf(
        "entry_id",
        "created_at_iso",
        "zone_id",
        "body_raw",
        "visible_tags",
        "hidden_auto_tags",
        "rejected_tags",
        "bookmarks_pipe",
        "media_drive_ids",
        "reply_to_id",
        "updated_at_iso",
        "deleted",
        "task_status",
        "reactions",
    )

    val TAG_TREE_HEADERS: List<String> = listOf(
        "tag_id",
        "full_path",
        "leaf_name",
        "root_category",
        "color_token",
        "aliases",
    )

    val SYNC_META_HEADERS: List<String> = listOf(
        "schema_version",
        "last_export_iso",
        "device_id",
        "entry_count",
        "checksum",
        "generator",
        "upstream",
    )

    /**
     * Converts a local database snapshot into a deterministic 3-tab [BackupWorkbook].
     */
    fun toWorkbook(
        entries: List<EntryWithDetails>,
        tags: List<Tag>,
        deviceId: String,
        exportIso: String = OffsetDateTime.now(ZoneOffset.UTC)
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
    ): BackupWorkbook {
        val sheetEntries = entries
            .sortedWith(compareBy<EntryWithDetails>({ it.entry.createdAtMs }, { it.entry.id }))
            .map(::toSheetEntryRow)

        val sheetTags = tags
            .sortedWith(compareBy<Tag>({ normalizeTagPath(it.fullPath).lowercase(Locale.ROOT) }, { it.id }))
            .map(::toSheetTagRow)

        val checksum = computeWorkbookChecksum(sheetEntries, sheetTags)
        val meta = SyncMetaRecord(
            schemaVersion = SCHEMA_VERSION,
            lastExportIso = exportIso,
            deviceId = deviceId,
            entryCount = sheetEntries.size,
            checksum = checksum,
        )

        return BackupWorkbook(
            entries = sheetEntries,
            tags = sheetTags,
            meta = meta,
        )
    }

    fun toSheetEntryRow(details: EntryWithDetails): SheetEntryRow {
        val entry = details.entry
        return SheetEntryRow(
            entryId = entry.id,
            createdAtIso = formatEpochMsWithZone(entry.createdAtMs, entry.zoneId),
            zoneId = entry.zoneId,
            bodyRaw = entry.bodyRaw,
            visibleTags = details.visibleTags
                .map { normalizeTagPath(it.tag.fullPath) }
                .filter { it.isNotEmpty() }
                .distinct()
                .sorted(),
            hiddenAutoTags = details.hiddenAutoTags
                .map { normalizeTagPath(it.tag.fullPath) }
                .filter { it.isNotEmpty() }
                .distinct()
                .sorted(),
            rejectedTags = details.rejectedTags
                .map { normalizeTagPath(it.tag.fullPath) }
                .filter { it.isNotEmpty() }
                .distinct()
                .sorted(),
            bookmarksPipe = encodeBookmarksPipe(details.bookmarks),
            mediaDriveIds = details.attachments
                .sortedWith(compareBy<Attachment>({ it.sha256 }, { it.localFilename }))
                .map(::encodeMediaToken),
            replyToId = entry.replyToId?.takeIf { it.isNotBlank() },
            updatedAtIso = entry.updatedAtMs?.let { formatEpochMsWithZone(it, entry.zoneId) },
            deleted = entry.isDeleted,
            taskStatus = entry.taskStatus.name,
            reactions = entry.reactions,
        )
    }

    fun toSheetTagRow(tag: Tag): SheetTagRow {
        val normalizedPath = normalizeTagPath(tag.fullPath)
        return SheetTagRow(
            tagId = tag.id,
            fullPath = normalizedPath,
            leafName = tag.leafName,
            rootCategory = tag.rootCategory.key,
            colorToken = tag.colorToken,
            aliases = tag.aliases.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
        )
    }

    /**
     * Formats [epochMs] in its write-time IANA [zoneId] as `ISO_OFFSET_DATE_TIME`.
     */
    fun formatEpochMsWithZone(epochMs: Long, zoneId: String): String {
        val zone = safeZoneId(zoneId)
        return Instant.ofEpochMilli(epochMs)
            .atZone(zone)
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
    }

    /**
     * Parses an ISO-8601 date/time string into epoch milliseconds, returning `null` if invalid.
     */
    fun parseIsoToEpochMs(iso: String, zoneId: String = "UTC"): Long? {
        val trimmed = iso.trim()
        if (trimmed.isEmpty()) return null

        runCatching { return OffsetDateTime.parse(trimmed).toInstant().toEpochMilli() }
        runCatching { return Instant.parse(trimmed).toEpochMilli() }
        runCatching { return ZonedDateTime.parse(trimmed).toInstant().toEpochMilli() }

        val zone = safeZoneId(zoneId)
        runCatching { return LocalDateTime.parse(trimmed).atZone(zone).toInstant().toEpochMilli() }
        runCatching { return LocalDate.parse(trimmed).atStartOfDay(zone).toInstant().toEpochMilli() }
        return null
    }

    /**
     * Encodes bookmarks into `"url|title;;url2|title2"` format.
     */
    fun encodeBookmarksPipe(bookmarks: List<Bookmark>): String {
        if (bookmarks.isEmpty()) return ""
        return bookmarks
            .sortedBy { it.urlRaw }
            .joinToString(BOOKMARK_RECORD_DELIMITER) { bookmark ->
                val url = bookmark.urlRaw.trim()
                val title = bookmark.title
                    ?.replace(BOOKMARK_RECORD_DELIMITER, "; ")
                    ?.replace(BOOKMARK_FIELD_DELIMITER, " ")
                    ?.trim()
                    .orEmpty()
                "$url$BOOKMARK_FIELD_DELIMITER$title"
            }
    }

    /**
     * Decodes `"url|title;;url2|title2"` into `(url, nullableTitle)` pairs.
     */
    fun decodeBookmarksPipe(bookmarksPipe: String): List<Pair<String, String?>> {
        if (bookmarksPipe.isBlank()) return emptyList()
        return bookmarksPipe
            .split(BOOKMARK_RECORD_DELIMITER)
            .mapNotNull { segment ->
                val trimmed = segment.trim()
                if (trimmed.isEmpty()) return@mapNotNull null
                val pipeIdx = trimmed.indexOf(BOOKMARK_FIELD_DELIMITER)
                val url = if (pipeIdx == -1) trimmed else trimmed.substring(0, pipeIdx).trim()
                if (url.isEmpty()) return@mapNotNull null
                val title = if (pipeIdx == -1) {
                    null
                } else {
                    trimmed.substring(pipeIdx + 1).trim().takeIf { it.isNotEmpty() }
                }
                url to title
            }
    }

    /**
     * Encodes an [Attachment] into `"<driveId>:<sha256>:<mime>"` (when synced to Drive) or
     * `"<localFilename>:<sha256>:<mime>"` (for offline/un-uploaded exports).
     */
    fun encodeMediaToken(attachment: Attachment): String {
        val primaryId = attachment.driveFileId?.takeIf { it.isNotBlank() } ?: attachment.localFilename
        return "$primaryId:${attachment.sha256}:${attachment.mimeType}"
    }

    /**
     * Parses a media token from `media_drive_ids`.
     */
    fun parseMediaToken(token: String): MediaTokenRef? {
        val trimmed = token.trim()
        if (trimmed.isEmpty()) return null
        val parts = trimmed.split(':')
        val primaryId = parts.getOrNull(0)?.trim().orEmpty()
        val sha256 = parts.getOrNull(1)?.trim()?.lowercase(Locale.ROOT).orEmpty()
        val mimeType = parts.getOrNull(2)?.trim()?.takeIf { it.isNotEmpty() }
        if (primaryId.isEmpty() && sha256.isEmpty()) return null
        return MediaTokenRef(
            rawToken = trimmed,
            primaryId = primaryId,
            sha256 = sha256,
            mimeType = mimeType,
        )
    }

    /**
     * Computes the canonical SHA-256 sync hash for a local [EntryWithDetails].
     * Matches `:core:data`'s `EntryContentHasher` so `lastSyncedContentHashes` aligns across
     * local edits, pushes, and transactional restores.
     */
    fun computeLocalEntrySyncHash(entry: EntryWithDetails): String {
        val visiblePaths = entry.visibleTags.map { normalizeTagPath(it.tag.fullPath) }.filter { it.isNotEmpty() }
        val hiddenPaths = entry.hiddenAutoTags.map { normalizeTagPath(it.tag.fullPath) }.filter { it.isNotEmpty() }
        val rejectedPaths = entry.rejectedTags.map { normalizeTagPath(it.tag.fullPath) }.filter { it.isNotEmpty() }
        val bookmarkSignatures = entry.bookmarks.map { "${it.urlRaw.trim()}|${it.title.orEmpty().trim()}" }
        val attachmentSha256s = entry.attachments.map { it.sha256.trim().lowercase(Locale.ROOT) }

        return computeCanonicalEntryHash(
            bodyRaw = entry.entry.bodyRaw,
            replyToId = entry.entry.replyToId?.takeIf { it.isNotBlank() },
            isDeleted = entry.entry.isDeleted,
            visibleTagPaths = visiblePaths,
            hiddenTagPaths = hiddenPaths,
            rejectedTagPaths = rejectedPaths,
            bookmarkSignatures = bookmarkSignatures,
            attachmentSha256s = attachmentSha256s,
            taskStatus = entry.entry.taskStatus.name,
            reactions = entry.entry.reactions,
        )
    }

    /**
     * Computes the canonical SHA-256 sync hash for a remote [SheetEntryRow].
     * Guaranteed to match [computeLocalEntrySyncHash] when the remote row represents identical content.
     */
    fun computeRemoteRowContentHash(row: SheetEntryRow): String {
        val visiblePaths = row.visibleTags.map(::normalizeTagPath).filter { it.isNotEmpty() }
        val hiddenPaths = row.hiddenAutoTags.map(::normalizeTagPath).filter { it.isNotEmpty() }
        val rejectedPaths = row.rejectedTags.map(::normalizeTagPath).filter { it.isNotEmpty() }
        val bookmarkSignatures = decodeBookmarksPipe(row.bookmarksPipe)
            .map { (url, title) -> "${url.trim()}|${title.orEmpty().trim()}" }
        val attachmentSha256s = row.mediaDriveIds
            .mapNotNull { parseMediaToken(it)?.sha256?.takeIf { sha -> sha.isNotEmpty() } }

        return computeCanonicalEntryHash(
            bodyRaw = row.bodyRaw,
            replyToId = row.replyToId?.takeIf { it.isNotBlank() },
            isDeleted = row.deleted,
            visibleTagPaths = visiblePaths,
            hiddenTagPaths = hiddenPaths,
            rejectedTagPaths = rejectedPaths,
            bookmarkSignatures = bookmarkSignatures,
            attachmentSha256s = attachmentSha256s,
            taskStatus = row.taskStatus,
            reactions = row.reactions,
        )
    }

    /**
     * Computes a deterministic canonical SHA-256 checksum over the entire workbook contents
     * (`Entries` + `TagTree`).
     */
    fun computeWorkbookChecksum(
        entries: List<SheetEntryRow>,
        tags: List<SheetTagRow>,
    ): String {
        val canonical = buildString {
            append("workbook:v").append(SCHEMA_VERSION).append('\n')
            val sortedEntries = entries.sortedWith(
                compareBy<SheetEntryRow>({ it.entryId }, { it.createdAtIso }, { it.bodyRaw }),
            )
            for (row in sortedEntries) {
                append("E|")
                    .append(row.entryId).append('|')
                    .append(row.createdAtIso).append('|')
                    .append(row.zoneId).append('|')
                    .append(computeRemoteRowContentHash(row)).append('|')
                    .append(row.updatedAtIso.orEmpty()).append('\n')
            }
            val sortedTags = tags.sortedWith(
                compareBy<SheetTagRow>({ normalizeTagPath(it.fullPath).lowercase(Locale.ROOT) }, { it.tagId }),
            )
            for (tag in sortedTags) {
                append("T|")
                    .append(tag.tagId).append('|')
                    .append(normalizeTagPath(tag.fullPath)).append('|')
                    .append(tag.leafName).append('|')
                    .append(tag.rootCategory).append('|')
                    .append(tag.colorToken).append('|')
                    .append(tag.aliases.map { it.trim() }.filter { it.isNotEmpty() }.sorted().joinToString(","))
                    .append('\n')
            }
        }
        return ContentHasher.sha256Hex(canonical)
    }

    /**
     * Synthesizes a hierarchical `TagTree` from all tag paths referenced in [entries]
     * (used when importing standalone CSV files or workbooks without a `TagTree` tab).
     */
    fun synthesizeTagTreeFromEntries(entries: List<SheetEntryRow>): List<SheetTagRow> {
        val allPaths = LinkedHashSet<String>()
        for (entry in entries) {
            val rawPaths = entry.visibleTags + entry.hiddenAutoTags + entry.rejectedTags
            for (raw in rawPaths) {
                val normalized = normalizeTagPath(raw)
                if (normalized.isEmpty()) continue
                val segments = normalized.split('/')
                val prefix = StringBuilder()
                for (i in segments.indices) {
                    if (i > 0) prefix.append('/')
                    prefix.append(segments[i])
                    allPaths += prefix.toString()
                }
            }
        }

        return allPaths
            .sortedBy { it.lowercase(Locale.ROOT) }
            .map { fullPath ->
                val category = TagRootCategory.fromPath(fullPath)
                SheetTagRow(
                    tagId = "",
                    fullPath = fullPath,
                    leafName = fullPath.substringAfterLast('/'),
                    rootCategory = category.key,
                    colorToken = category.key,
                    aliases = emptyList(),
                )
            }
    }

    // ---- 2D Table Serialization Helpers (shared by CSV, XLSX, and Google Sheets) ----

    fun entriesToTableRows(entries: List<SheetEntryRow>): List<List<String>> {
        val rows = ArrayList<List<String>>(entries.size + 1)
        rows += ENTRIES_HEADERS
        for (row in entries) {
            rows += listOf(
                row.entryId,
                row.createdAtIso,
                row.zoneId,
                row.bodyRaw,
                joinListCell(row.visibleTags),
                joinListCell(row.hiddenAutoTags),
                joinListCell(row.rejectedTags),
                row.bookmarksPipe,
                joinListCell(row.mediaDriveIds),
                row.replyToId.orEmpty(),
                row.updatedAtIso.orEmpty(),
                row.deleted.toString(),
                normalizeTaskStatus(row.taskStatus),
                joinListCell(row.reactions.distinct()),
            )
        }
        return rows
    }

    fun tableRowsToEntries(rows: List<List<String>>): List<SheetEntryRow> {
        if (rows.isEmpty()) return emptyList()
        val headerRow = rows.first()
        val hasHeader = isEntriesHeaderRow(headerRow)
        val columnIndex = if (hasHeader) {
            headerRow.withIndex().associate { (idx, name) -> name.trim().lowercase(Locale.ROOT) to idx }
        } else {
            ENTRIES_HEADERS.withIndex().associate { (idx, name) -> name to idx }
        }
        val dataRows = if (hasHeader) rows.drop(1) else rows

        fun List<String>.cell(colName: String, fallbackIndex: Int): String {
            val idx = columnIndex[colName] ?: fallbackIndex
            return getOrNull(idx).orEmpty()
        }

        return dataRows
            .filter { row -> row.any { it.isNotBlank() } }
            .map { row ->
                val entryId = row.cell("entry_id", 0).trim()
                val createdAtIso = row.cell("created_at_iso", 1).trim()
                val zoneId = row.cell("zone_id", 2).trim()
                val bodyRaw = row.cell("body_raw", 3)
                val visibleTags = splitListCell(row.cell("visible_tags", 4))
                val hiddenAutoTags = splitListCell(row.cell("hidden_auto_tags", 5))
                val rejectedTags = splitListCell(row.cell("rejected_tags", 6))
                val bookmarksPipe = row.cell("bookmarks_pipe", 7).trim()
                val mediaDriveIds = splitListCell(row.cell("media_drive_ids", 8))
                val replyToId = row.cell("reply_to_id", 9).trim().takeIf { it.isNotEmpty() }
                val updatedAtIso = row.cell("updated_at_iso", 10).trim().takeIf { it.isNotEmpty() }
                val deleted = parseBooleanCell(row.cell("deleted", 11))
                val taskStatus = normalizeTaskStatus(row.cell("task_status", 12))
                val reactions = splitListCell(row.cell("reactions", 13)).distinct()

                SheetEntryRow(
                    entryId = entryId,
                    createdAtIso = createdAtIso,
                    zoneId = zoneId,
                    bodyRaw = bodyRaw,
                    visibleTags = visibleTags,
                    hiddenAutoTags = hiddenAutoTags,
                    rejectedTags = rejectedTags,
                    bookmarksPipe = bookmarksPipe,
                    mediaDriveIds = mediaDriveIds,
                    replyToId = replyToId,
                    updatedAtIso = updatedAtIso,
                    deleted = deleted,
                    taskStatus = taskStatus,
                    reactions = reactions,
                )
            }
    }

    fun tagsToTableRows(tags: List<SheetTagRow>): List<List<String>> {
        val rows = ArrayList<List<String>>(tags.size + 1)
        rows += TAG_TREE_HEADERS
        for (tag in tags) {
            rows += listOf(
                tag.tagId,
                tag.fullPath,
                tag.leafName,
                tag.rootCategory,
                tag.colorToken,
                joinListCell(tag.aliases),
            )
        }
        return rows
    }

    fun tableRowsToTags(rows: List<List<String>>): List<SheetTagRow> {
        if (rows.isEmpty()) return emptyList()
        val headerRow = rows.first()
        val hasHeader = headerRow.any { it.trim().equals("full_path", ignoreCase = true) }
        val columnIndex = if (hasHeader) {
            headerRow.withIndex().associate { (idx, name) -> name.trim().lowercase(Locale.ROOT) to idx }
        } else {
            TAG_TREE_HEADERS.withIndex().associate { (idx, name) -> name to idx }
        }
        val dataRows = if (hasHeader) rows.drop(1) else rows

        fun List<String>.cell(colName: String, fallbackIndex: Int): String {
            val idx = columnIndex[colName] ?: fallbackIndex
            return getOrNull(idx).orEmpty()
        }

        return dataRows
            .filter { row -> row.any { it.isNotBlank() } }
            .mapNotNull { row ->
                val fullPath = normalizeTagPath(row.cell("full_path", 1))
                if (fullPath.isEmpty()) return@mapNotNull null
                val category = TagRootCategory.fromPath(fullPath)
                val leafName = row.cell("leaf_name", 2).trim().ifEmpty { fullPath.substringAfterLast('/') }
                val rootCategory = row.cell("root_category", 3).trim().ifEmpty { category.key }
                val colorToken = row.cell("color_token", 4).trim().ifEmpty { category.key }
                SheetTagRow(
                    tagId = row.cell("tag_id", 0).trim(),
                    fullPath = fullPath,
                    leafName = leafName,
                    rootCategory = rootCategory,
                    colorToken = colorToken,
                    aliases = splitListCell(row.cell("aliases", 5)),
                )
            }
    }

    fun metaToTableRows(meta: SyncMetaRecord): List<List<String>> = listOf(
        SYNC_META_HEADERS,
        listOf(
            meta.schemaVersion.toString(),
            meta.lastExportIso,
            meta.deviceId,
            meta.entryCount.toString(),
            meta.checksum,
            PROVENANCE_GENERATOR,
            PROVENANCE_UPSTREAM_URL,
        ),
    )

    fun tableRowsToMeta(
        rows: List<List<String>>,
        fallbackEntries: List<SheetEntryRow>,
        fallbackTags: List<SheetTagRow>,
    ): SyncMetaRecord {
        val hasHeader = rows.firstOrNull()?.any {
            it.trim().equals("schema_version", ignoreCase = true) ||
                it.trim().equals("checksum", ignoreCase = true)
        } == true
        val headerRow = if (hasHeader) rows.first() else SYNC_META_HEADERS
        val columnIndex = headerRow.withIndex().associate { (idx, name) -> name.trim().lowercase(Locale.ROOT) to idx }
        val dataRow = (if (hasHeader) rows.drop(1) else rows)
            .firstOrNull { row -> row.any { it.isNotBlank() } }

        val computedChecksum = computeWorkbookChecksum(fallbackEntries, fallbackTags)
        if (dataRow == null) {
            return SyncMetaRecord(
                schemaVersion = SCHEMA_VERSION,
                lastExportIso = OffsetDateTime.now(ZoneOffset.UTC)
                    .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                deviceId = "",
                entryCount = fallbackEntries.size,
                checksum = computedChecksum,
            )
        }

        fun List<String>.cell(colName: String, fallbackIndex: Int): String {
            val idx = columnIndex[colName] ?: fallbackIndex
            return getOrNull(idx).orEmpty().trim()
        }

        val schemaVersion = dataRow.cell("schema_version", 0).toIntOrNull() ?: SCHEMA_VERSION
        val lastExportIso = dataRow.cell("last_export_iso", 1).ifBlank {
            OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        }
        val deviceId = dataRow.cell("device_id", 2)
        val entryCount = dataRow.cell("entry_count", 3).toIntOrNull() ?: fallbackEntries.size
        val checksum = dataRow.cell("checksum", 4).ifBlank { computedChecksum }

        return SyncMetaRecord(
            schemaVersion = schemaVersion,
            lastExportIso = lastExportIso,
            deviceId = deviceId,
            entryCount = entryCount,
            checksum = checksum,
        )
    }

    fun tableRowsToWorkbook(
        entryRows: List<List<String>>,
        tagRows: List<List<String>>,
        metaRows: List<List<String>>,
    ): BackupWorkbook {
        val entries = tableRowsToEntries(entryRows)
        val parsedTags = tableRowsToTags(tagRows)
        val tags = if (parsedTags.isNotEmpty()) parsedTags else synthesizeTagTreeFromEntries(entries)
        val meta = tableRowsToMeta(metaRows, entries, tags)
        return BackupWorkbook(entries = entries, tags = tags, meta = meta)
    }

    fun normalizeTagPath(rawPath: String): String =
        rawPath
            .trim()
            .trim('/')
            .split('/')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("/")

    private fun computeCanonicalEntryHash(
        bodyRaw: String,
        replyToId: String?,
        isDeleted: Boolean,
        visibleTagPaths: Collection<String>,
        hiddenTagPaths: Collection<String>,
        rejectedTagPaths: Collection<String>,
        bookmarkSignatures: Collection<String>,
        attachmentSha256s: Collection<String>,
        taskStatus: String = "NONE",
        reactions: Collection<String> = emptyList(),
    ): String {
        val normalizedTask = normalizeTaskStatus(taskStatus)
        val canonical = buildString {
            append("v1\n")
            append("body=").append(bodyRaw).append('\n')
            append("reply=").append(replyToId.orEmpty()).append('\n')
            append("deleted=").append(isDeleted).append('\n')
            append("visible=").append(visibleTagPaths.sorted().joinToString(",")).append('\n')
            append("hidden=").append(hiddenTagPaths.sorted().joinToString(",")).append('\n')
            append("rejected=").append(rejectedTagPaths.sorted().joinToString(",")).append('\n')
            append("bookmarks=").append(bookmarkSignatures.sorted().joinToString(";;")).append('\n')
            append("media=").append(attachmentSha256s.sorted().joinToString(","))
            if (normalizedTask != "NONE") {
                append("\ntask=").append(normalizedTask)
            }
            val cleanedReactions = reactions.map { it.trim() }.filter { it.isNotEmpty() }.distinct().sorted()
            if (cleanedReactions.isNotEmpty()) {
                append("\nreactions=").append(cleanedReactions.joinToString(","))
            }
        }
        return ContentHasher.sha256Hex(canonical)
    }

    private fun normalizeTaskStatus(raw: String): String =
        when (raw.trim().uppercase(Locale.ROOT)) {
            "TODO" -> "TODO"
            "DONE" -> "DONE"
            else -> "NONE"
        }

    private fun safeZoneId(zoneId: String): ZoneId =
        if (zoneId.isBlank()) {
            ZoneOffset.UTC
        } else {
            try {
                ZoneId.of(zoneId)
            } catch (_: DateTimeException) {
                ZoneOffset.UTC
            }
        }

    private fun isEntriesHeaderRow(row: List<String>): Boolean {
        val normalized = row.map { it.trim().lowercase(Locale.ROOT) }
        return "entry_id" in normalized || "body_raw" in normalized
    }

    private fun joinListCell(values: List<String>): String =
        values.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", ")

    private fun splitListCell(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        return raw.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    private fun parseBooleanCell(raw: String): Boolean {
        val normalized = raw.trim().lowercase(Locale.ROOT)
        return normalized == "true" || normalized == "1" || normalized == "yes"
    }

    private const val BOOKMARK_RECORD_DELIMITER = ";;"
    private const val BOOKMARK_FIELD_DELIMITER = "|"
}
