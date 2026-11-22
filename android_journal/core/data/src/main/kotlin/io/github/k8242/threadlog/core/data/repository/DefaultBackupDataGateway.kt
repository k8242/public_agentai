// Copyright (c) 2026 k8242
// SPDX-License-Identifier: MIT

package io.github.k8242.threadlog.core.data.repository

import io.github.k8242.threadlog.core.data.local.ThreadLogDatabase
import io.github.k8242.threadlog.core.data.local.dao.AttachmentDao
import io.github.k8242.threadlog.core.data.local.dao.BookmarkDao
import io.github.k8242.threadlog.core.data.local.dao.EntryDao
import io.github.k8242.threadlog.core.data.local.dao.SyncStateDao
import io.github.k8242.threadlog.core.data.local.dao.TagDao
import io.github.k8242.threadlog.core.data.local.entity.AttachmentEntity
import io.github.k8242.threadlog.core.data.local.entity.BookmarkEntity
import io.github.k8242.threadlog.core.data.local.entity.EntryEntity
import io.github.k8242.threadlog.core.data.local.entity.EntryTagEntity
import io.github.k8242.threadlog.core.data.local.entity.SyncEntryHashEntity
import io.github.k8242.threadlog.core.data.local.fts.FtsIndexManager
import io.github.k8242.threadlog.core.data.local.inReadTransaction
import io.github.k8242.threadlog.core.data.local.inWriteTransaction
import io.github.k8242.threadlog.core.data.mapper.TagPathNormalizer
import io.github.k8242.threadlog.core.data.mapper.toDomain
import io.github.k8242.threadlog.core.model.ConflictResolution
import io.github.k8242.threadlog.core.model.EntryWithDetails
import io.github.k8242.threadlog.core.model.SheetEntryRow
import io.github.k8242.threadlog.core.model.SyncDiffPreview
import io.github.k8242.threadlog.core.model.Tag
import io.github.k8242.threadlog.core.model.TagState
import io.github.k8242.threadlog.core.model.contract.BackupDataGateway
import io.github.k8242.threadlog.core.model.contract.DateWeekCalculator
import io.github.k8242.threadlog.core.model.contract.MessageParser
import io.github.k8242.threadlog.core.model.contract.PendingAttachment
import io.github.k8242.threadlog.core.parser.UuidV7
import java.net.URI
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultBackupDataGateway @Inject constructor(
    private val database: ThreadLogDatabase,
    private val entryDao: EntryDao,
    private val tagDao: TagDao,
    private val bookmarkDao: BookmarkDao,
    private val attachmentDao: AttachmentDao,
    private val syncStateDao: SyncStateDao,
    private val ftsIndexManager: FtsIndexManager,
    private val hydrator: EntryHydrator,
    private val messageParser: MessageParser,
    private val dateWeekCalculator: DateWeekCalculator,
) : BackupDataGateway {

    override suspend fun exportSnapshot(): Pair<List<EntryWithDetails>, List<Tag>> =
        database.inReadTransaction {
            // Includes soft-deleted tombstones so deletes propagate during sync.
            val allEntries = entryDao.getAll()
            val hydratedEntries = hydrator.hydrateEntries(allEntries)
            val allTags = tagDao.getAll().map { it.toDomain() }
            hydratedEntries to allTags
        }

    override suspend fun getLastSyncedContentHashes(): Map<String, String> =
        syncStateDao.getAll().associate { it.entryId to it.lastSyncedContentHash }

    override suspend fun markSynced(
        entryHashes: Map<String, String>,
        driveFileIdsByAttachmentId: Map<String, String>,
    ) {
        val nowMs = System.currentTimeMillis()
        database.inWriteTransaction {
            if (entryHashes.isNotEmpty()) {
                val entities = entryHashes.map { (entryId, hash) ->
                    SyncEntryHashEntity(
                        entryId = entryId,
                        lastSyncedContentHash = hash,
                        syncedAtMs = nowMs,
                    )
                }
                syncStateDao.upsertAll(entities)
            }
            for ((attachmentId, driveFileId) in driveFileIdsByAttachmentId) {
                attachmentDao.updateDriveFileId(
                    attachmentId = attachmentId,
                    driveFileId = driveFileId,
                )
            }
        }
    }

    override suspend fun applyMergeRestore(
        diff: SyncDiffPreview,
        resolutions: Map<String, ConflictResolution>,
        downloadedMedia: Map<String, PendingAttachment>,
    ) {
        val nowMs = System.currentTimeMillis()
        database.inWriteTransaction {
            val affectedEntryIds = LinkedHashSet<String>()
            val syncedHashes = mutableListOf<SyncEntryHashEntity>()

            // 1. Upsert remote tags shallowest-first so parent hierarchies are always valid.
            val sortedRemoteTags = diff.remoteWorkbook.tags
                .filter { TagPathNormalizer.normalize(it.fullPath).isNotEmpty() }
                .sortedBy { TagPathNormalizer.normalize(it.fullPath).count { ch -> ch == '/' } }

            for (sheetTag in sortedRemoteTags) {
                val (tagEntity, aliasesChanged) = hydrator.ensureTagChain(
                    rawFullPath = sheetTag.fullPath,
                    leafAliases = sheetTag.aliases,
                    preferredLeafId = sheetTag.tagId.takeIf { it.isNotBlank() },
                )
                if (aliasesChanged) {
                    affectedEntryIds += tagDao.getEntryIdsForTag(tagEntity.id)
                }
            }

            // 2. Apply new entries from remote.
            for (item in diff.newItems) {
                val row = item.remoteRow ?: continue
                val resolvedZone = row.zoneId.ifBlank { ZoneId.systemDefault().id }
                val createdMs = parseIsoToEpochMs(row.createdAtIso, resolvedZone, nowMs)
                val targetId = row.entryId.takeIf { it.isNotBlank() } ?: UuidV7.generate(createdMs)
                val hash = applyRemoteEntryRow(
                    row = row,
                    targetEntryId = targetId,
                    downloadedMedia = downloadedMedia,
                    nowMs = nowMs,
                )
                affectedEntryIds += targetId
                syncedHashes += SyncEntryHashEntity(targetId, hash, nowMs)
            }

            // 3. Apply edited entries from remote.
            for (item in diff.editedItems) {
                val row = item.remoteRow ?: continue
                val targetId = row.entryId.takeIf { it.isNotBlank() }
                    ?: item.localEntry?.entry?.id
                    ?: item.diffKey
                val hash = applyRemoteEntryRow(
                    row = row,
                    targetEntryId = targetId,
                    downloadedMedia = downloadedMedia,
                    nowMs = nowMs,
                )
                affectedEntryIds += targetId
                syncedHashes += SyncEntryHashEntity(targetId, hash, nowMs)
            }

            // 4. Apply remote deletions (soft-delete locally).
            for (item in diff.deletedItems) {
                val targetId = item.localEntry?.entry?.id
                    ?: item.remoteRow?.entryId?.takeIf { it.isNotBlank() }
                    ?: item.diffKey
                val existing = entryDao.getById(targetId) ?: continue
                val deletedAtMs = item.remoteRow?.updatedAtIso
                    ?.takeIf { it.isNotBlank() }
                    ?.let { parseIsoToEpochMs(it, existing.zoneId, nowMs) }
                    ?: nowMs
                entryDao.update(existing.copy(deletedAtMs = deletedAtMs))
                val updated = hydrator.recomputeAndPersistContentHash(targetId)
                affectedEntryIds += targetId
                if (updated != null) {
                    syncedHashes += SyncEntryHashEntity(targetId, updated.contentHash, nowMs)
                }
            }

            // 5. Apply conflict resolutions.
            for (item in diff.conflicts) {
                val resolution = resolutions[item.diffKey] ?: ConflictResolution.KEEP_PHONE
                when (resolution) {
                    ConflictResolution.KEEP_PHONE -> {
                        // Local phone entry remains untouched.
                    }

                    ConflictResolution.KEEP_SHEET -> {
                        val row = item.remoteRow ?: continue
                        val targetId = item.localEntry?.entry?.id
                            ?: row.entryId.takeIf { it.isNotBlank() }
                            ?: item.diffKey
                        val hash = applyRemoteEntryRow(
                            row = row,
                            targetEntryId = targetId,
                            downloadedMedia = downloadedMedia,
                            nowMs = nowMs,
                        )
                        affectedEntryIds += targetId
                        syncedHashes += SyncEntryHashEntity(targetId, hash, nowMs)
                    }

                    ConflictResolution.KEEP_BOTH -> {
                        // Keep local phone entry untouched and insert remote row under a fresh UUID v7.
                        val row = item.remoteRow ?: continue
                        val resolvedZone = row.zoneId.ifBlank { ZoneId.systemDefault().id }
                        val createdMs = parseIsoToEpochMs(row.createdAtIso, resolvedZone, nowMs)
                        val copyId = UuidV7.generate(createdMs)
                        val hash = applyRemoteEntryRow(
                            row = row,
                            targetEntryId = copyId,
                            downloadedMedia = downloadedMedia,
                            nowMs = nowMs,
                        )
                        affectedEntryIds += copyId
                        syncedHashes += SyncEntryHashEntity(copyId, hash, nowMs)
                    }
                }
            }

            if (syncedHashes.isNotEmpty()) {
                syncStateDao.upsertAll(syncedHashes)
            }

            ftsIndexManager.reindexEntries(affectedEntryIds)
        }
    }

    private suspend fun applyRemoteEntryRow(
        row: SheetEntryRow,
        targetEntryId: String,
        downloadedMedia: Map<String, PendingAttachment>,
        nowMs: Long,
    ): String {
        val resolvedZone = row.zoneId.ifBlank { ZoneId.systemDefault().id }
        val createdAtMs = parseIsoToEpochMs(row.createdAtIso, resolvedZone, nowMs)
        val (localDate, isoWeek) = dateWeekCalculator.localDateAndIsoWeek(createdAtMs, resolvedZone)
        val parsed = messageParser.parse(row.bodyRaw)
        val updatedAtMs = row.updatedAtIso
            ?.takeIf { it.isNotBlank() }
            ?.let { parseIsoToEpochMs(it, resolvedZone, nowMs) }
        val deletedAtMs = if (row.deleted) (updatedAtMs ?: nowMs) else null

        val normalizedTaskStatus = runCatching {
            io.github.k8242.threadlog.core.model.TaskStatus.valueOf(row.taskStatus.trim().uppercase(Locale.ROOT)).name
        }.getOrDefault(io.github.k8242.threadlog.core.model.TaskStatus.NONE.name)

        // Insert or update entry first so foreign keys on entry_tags / bookmarks / attachments succeed.
        val placeholderEntity = EntryEntity(
            id = targetEntryId,
            createdAtMs = createdAtMs,
            zoneId = resolvedZone,
            localDate = localDate,
            isoWeek = isoWeek,
            bodyRaw = row.bodyRaw,
            bodyPlain = parsed.bodyPlain,
            replyToId = row.replyToId?.takeIf { it.isNotBlank() },
            contentHash = "",
            updatedAtMs = updatedAtMs,
            deletedAtMs = deletedAtMs,
            taskStatus = normalizedTaskStatus,
            reactionsJson = io.github.k8242.threadlog.core.data.mapper.ReactionsJsonCodec.encode(row.reactions),
        )
        entryDao.upsert(placeholderEntity)

        // Replace entry tags according to remote row state buckets.
        tagDao.deleteEntryTagsForEntry(targetEntryId)
        val tagLinksByTagId = LinkedHashMap<String, EntryTagEntity>()
        val tagTimestamp = updatedAtMs ?: createdAtMs

        suspend fun attachBucket(paths: List<String>, state: TagState, confidence: Float) {
            for (rawPath in paths) {
                if (TagPathNormalizer.normalize(rawPath).isEmpty()) continue
                val (tagEntity, _) = hydrator.ensureTagChain(rawPath)
                tagLinksByTagId.putIfAbsent(
                    tagEntity.id,
                    EntryTagEntity(
                        entryId = targetEntryId,
                        tagId = tagEntity.id,
                        state = state.name,
                        confidence = confidence,
                        updatedAtMs = tagTimestamp,
                    ),
                )
            }
        }

        attachBucket(row.visibleTags, TagState.USER, 1.0f)
        attachBucket(row.hiddenAutoTags, TagState.AUTO_HIDDEN, 0.85f)
        attachBucket(row.rejectedTags, TagState.AUTO_REJECTED, 0f)
        if (tagLinksByTagId.isNotEmpty()) {
            tagDao.upsertEntryTags(tagLinksByTagId.values.toList())
        }

        // Reconcile bookmarks from bookmarksPipe + extracted URLs.
        val existingBookmarksByUrl = bookmarkDao.getForEntry(targetEntryId).associateBy { it.urlRaw }
        val pipedBookmarks = parseBookmarksPipe(row.bookmarksPipe)
        val mergedBookmarks = LinkedHashMap<String, BookmarkEntity>()

        for ((urlRaw, title) in pipedBookmarks) {
            val prev = existingBookmarksByUrl[urlRaw]
            mergedBookmarks[urlRaw] = BookmarkEntity(
                id = prev?.id ?: UuidV7.generate(nowMs),
                entryId = targetEntryId,
                urlRaw = urlRaw,
                domain = prev?.domain ?: extractDomain(urlRaw),
                title = title ?: prev?.title,
                note = prev?.note,
                isCollapsed = prev?.isCollapsed ?: true,
            )
        }
        for (extracted in parsed.extractedUrls) {
            if (extracted.urlRaw !in mergedBookmarks) {
                val prev = existingBookmarksByUrl[extracted.urlRaw]
                mergedBookmarks[extracted.urlRaw] = BookmarkEntity(
                    id = prev?.id ?: UuidV7.generate(nowMs),
                    entryId = targetEntryId,
                    urlRaw = extracted.urlRaw,
                    domain = extracted.domain,
                    title = prev?.title,
                    note = prev?.note,
                    isCollapsed = prev?.isCollapsed ?: true,
                )
            }
        }
        bookmarkDao.deleteForEntry(targetEntryId)
        if (mergedBookmarks.isNotEmpty()) {
            bookmarkDao.upsertAll(mergedBookmarks.values.toList())
        }

        // Attach any downloaded media referenced by row.mediaDriveIds.
        if (row.mediaDriveIds.isNotEmpty() && downloadedMedia.isNotEmpty()) {
            val existingSha256s = attachmentDao.getForEntry(targetEntryId)
                .mapTo(HashSet()) { it.sha256 }
            val newAttachments = mutableListOf<AttachmentEntity>()

            for (mediaToken in row.mediaDriveIds) {
                val trimmed = mediaToken.trim()
                if (trimmed.isEmpty()) continue
                val parts = trimmed.split(':')
                val primaryKey = parts.getOrNull(0).orEmpty()
                val sha256Hint = parts.getOrNull(1).orEmpty()

                val pending = downloadedMedia[trimmed]
                    ?: downloadedMedia[primaryKey]
                    ?: downloadedMedia[sha256Hint]
                    ?: continue

                if (pending.sha256 in existingSha256s) continue
                existingSha256s += pending.sha256

                val driveFileId = if (parts.size >= 3 && primaryKey.isNotBlank()) primaryKey else null
                newAttachments += AttachmentEntity(
                    id = UuidV7.generate(nowMs),
                    entryId = targetEntryId,
                    localFilename = pending.localFilename,
                    mimeType = pending.mimeType,
                    byteSize = pending.byteSize,
                    width = pending.width,
                    height = pending.height,
                    sha256 = pending.sha256,
                    driveFileId = driveFileId,
                )
            }

            if (newAttachments.isNotEmpty()) {
                attachmentDao.upsertAll(newAttachments)
            }
        }

        val finalEntity = hydrator.recomputeAndPersistContentHash(targetEntryId)
        return finalEntity?.contentHash.orEmpty()
    }

    private fun parseBookmarksPipe(bookmarksPipe: String): List<Pair<String, String?>> {
        if (bookmarksPipe.isBlank()) return emptyList()
        return bookmarksPipe
            .split(";;")
            .mapNotNull { segment ->
                val trimmed = segment.trim()
                if (trimmed.isEmpty()) return@mapNotNull null
                val pipeIdx = trimmed.indexOf('|')
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

    private fun extractDomain(urlRaw: String): String =
        runCatching {
            val host = URI(urlRaw).host
            host?.lowercase(Locale.ROOT)?.removePrefix("www.")
        }.getOrNull().orEmpty()

    private fun parseIsoToEpochMs(iso: String, zoneId: String, fallbackMs: Long): Long {
        val trimmed = iso.trim()
        if (trimmed.isEmpty()) return fallbackMs

        runCatching { return Instant.parse(trimmed).toEpochMilli() }
        runCatching { return OffsetDateTime.parse(trimmed).toInstant().toEpochMilli() }
        runCatching { return ZonedDateTime.parse(trimmed).toInstant().toEpochMilli() }

        val zone = try {
            ZoneId.of(zoneId)
        } catch (_: DateTimeException) {
            ZoneOffset.UTC
        }
        runCatching { return LocalDateTime.parse(trimmed).atZone(zone).toInstant().toEpochMilli() }
        runCatching { return LocalDate.parse(trimmed).atStartOfDay(zone).toInstant().toEpochMilli() }

        return fallbackMs
    }
}
