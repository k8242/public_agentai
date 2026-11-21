package io.github.k8242.threadlog.core.data.repository

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import io.github.k8242.threadlog.core.data.local.ThreadLogDatabase
import io.github.k8242.threadlog.core.data.local.dao.AttachmentDao
import io.github.k8242.threadlog.core.data.local.dao.BookmarkDao
import io.github.k8242.threadlog.core.data.local.dao.EntryDao
import io.github.k8242.threadlog.core.data.local.dao.TagDao
import io.github.k8242.threadlog.core.data.local.entity.AttachmentEntity
import io.github.k8242.threadlog.core.data.local.entity.BookmarkEntity
import io.github.k8242.threadlog.core.data.local.entity.EntryEntity
import io.github.k8242.threadlog.core.data.local.entity.EntryTagEntity
import io.github.k8242.threadlog.core.data.local.fts.FtsIndexManager
import io.github.k8242.threadlog.core.data.local.inReadTransaction
import io.github.k8242.threadlog.core.data.local.inWriteTransaction
import io.github.k8242.threadlog.core.data.mapper.EntryContentHasher
import io.github.k8242.threadlog.core.data.mapper.TagPathNormalizer
import io.github.k8242.threadlog.core.data.mapper.toDomain
import io.github.k8242.threadlog.core.model.CalendarDayCount
import io.github.k8242.threadlog.core.model.CompactEntrySummary
import io.github.k8242.threadlog.core.model.EntryWithDetails
import io.github.k8242.threadlog.core.model.TagState
import io.github.k8242.threadlog.core.model.contract.DateWeekCalculator
import io.github.k8242.threadlog.core.model.contract.EntryRepository
import io.github.k8242.threadlog.core.model.contract.MessageParser
import io.github.k8242.threadlog.core.model.contract.PendingAttachment
import io.github.k8242.threadlog.core.parser.UuidV7
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class DefaultEntryRepository @Inject constructor(
    private val database: ThreadLogDatabase,
    private val entryDao: EntryDao,
    private val tagDao: TagDao,
    private val bookmarkDao: BookmarkDao,
    private val attachmentDao: AttachmentDao,
    private val ftsIndexManager: FtsIndexManager,
    private val hydrator: EntryHydrator,
    private val messageParser: MessageParser,
    private val dateWeekCalculator: DateWeekCalculator,
) : EntryRepository {

    override fun observePagedEntries(): Flow<PagingData<EntryWithDetails>> =
        Pager(
            config = PagingConfig(
                pageSize = DEFAULT_PAGE_SIZE,
                enablePlaceholders = false,
            ),
            pagingSourceFactory = { entryDao.pagingSource() },
        ).flow.map { pagingData ->
            pagingData.map { entity ->
                hydrator.hydrateEntry(entity)
            }
        }

    override fun observeEntry(entryId: String): Flow<EntryWithDetails?> =
        observeHydratedTables()
            .mapLatest {
                database.inReadTransaction {
                    entryDao.getById(entryId)?.let { hydrator.hydrateEntry(it) }
                }
            }
            .distinctUntilChanged()

    override suspend fun getEntry(entryId: String): EntryWithDetails? =
        database.inReadTransaction {
            entryDao.getById(entryId)?.let { hydrator.hydrateEntry(it) }
        }

    override suspend fun createEntry(
        bodyRaw: String,
        userTagIds: List<String>,
        replyToId: String?,
        pendingAttachments: List<PendingAttachment>,
        nowMs: Long,
        zoneId: String?,
    ): EntryWithDetails {
        val resolvedZoneId = zoneId?.takeIf { it.isNotBlank() } ?: ZoneId.systemDefault().id
        val (localDate, isoWeek) = dateWeekCalculator.localDateAndIsoWeek(nowMs, resolvedZoneId)
        val parsed = messageParser.parse(bodyRaw)
        val entryId = UuidV7.generate(nowMs)

        val bookmarks = parsed.extractedUrls.map { url ->
            BookmarkEntity(
                id = UuidV7.generate(nowMs),
                entryId = entryId,
                urlRaw = url.urlRaw,
                domain = url.domain,
                title = null,
                note = null,
                isCollapsed = true,
            )
        }

        val attachments = pendingAttachments.map { pending ->
            AttachmentEntity(
                id = UuidV7.generate(nowMs),
                entryId = entryId,
                localFilename = pending.localFilename,
                mimeType = pending.mimeType,
                byteSize = pending.byteSize,
                width = pending.width,
                height = pending.height,
                sha256 = pending.sha256,
                driveFileId = null,
            )
        }

        return database.inWriteTransaction {
            val distinctTagIds = userTagIds.distinct()
            val validTags = if (distinctTagIds.isEmpty()) {
                emptyList()
            } else {
                tagDao.getByIds(distinctTagIds)
            }

            val entryTags = validTags.map { tag ->
                EntryTagEntity(
                    entryId = entryId,
                    tagId = tag.id,
                    state = TagState.USER.name,
                    confidence = 1.0f,
                    updatedAtMs = nowMs,
                )
            }

            val contentHash = EntryContentHasher.compute(
                bodyRaw = bodyRaw,
                replyToId = replyToId,
                isDeleted = false,
                visibleTagPaths = validTags.map { it.fullPath },
                hiddenTagPaths = emptyList(),
                rejectedTagPaths = emptyList(),
                bookmarkSignatures = bookmarks.map { "${it.urlRaw}|${it.title.orEmpty()}" },
                attachmentSha256s = attachments.map { it.sha256 },
            )

            val entity = EntryEntity(
                id = entryId,
                createdAtMs = nowMs,
                zoneId = resolvedZoneId,
                localDate = localDate,
                isoWeek = isoWeek,
                bodyRaw = bodyRaw,
                bodyPlain = parsed.bodyPlain,
                replyToId = replyToId,
                contentHash = contentHash,
                updatedAtMs = null,
                deletedAtMs = null,
            )

            entryDao.upsert(entity)
            if (entryTags.isNotEmpty()) {
                tagDao.upsertEntryTags(entryTags)
            }
            if (bookmarks.isNotEmpty()) {
                bookmarkDao.upsertAll(bookmarks)
            }
            if (attachments.isNotEmpty()) {
                attachmentDao.upsertAll(attachments)
            }
            ftsIndexManager.reindexEntry(entryId)

            hydrator.hydrateEntry(entity)
        }
    }

    override suspend fun editEntry(
        entryId: String,
        newBodyRaw: String,
        nowMs: Long,
    ): EntryWithDetails =
        database.inWriteTransaction {
            val existing = entryDao.getById(entryId)
                ?: throw NoSuchElementException("Entry not found: $entryId")

            val parsed = messageParser.parse(newBodyRaw)
            val updatedAtMs = maxOf(nowMs, existing.createdAtMs + 1L)

            // Reconcile URL bookmarks: keep existing title/note/collapsed for unchanged URLs,
            // insert new ones collapsed, remove stale URLs.
            val existingBookmarks = bookmarkDao.getForEntry(entryId)
            val existingByUrl = existingBookmarks.associateBy { it.urlRaw }
            val extractedUrls = parsed.extractedUrls
            val extractedUrlSet = extractedUrls.mapTo(HashSet()) { it.urlRaw }

            for (stale in existingBookmarks) {
                if (stale.urlRaw !in extractedUrlSet) {
                    bookmarkDao.deleteById(stale.id)
                }
            }

            val newBookmarks = extractedUrls
                .filter { it.urlRaw !in existingByUrl }
                .map { url ->
                    BookmarkEntity(
                        id = UuidV7.generate(updatedAtMs),
                        entryId = entryId,
                        urlRaw = url.urlRaw,
                        domain = url.domain,
                        title = null,
                        note = null,
                        isCollapsed = true,
                    )
                }
            if (newBookmarks.isNotEmpty()) {
                bookmarkDao.upsertAll(newBookmarks)
            }

            // Preserve original createdAtMs, zoneId, localDate, isoWeek, replyToId, deletedAtMs.
            val updatedEntity = existing.copy(
                bodyRaw = newBodyRaw,
                bodyPlain = parsed.bodyPlain,
                updatedAtMs = updatedAtMs,
            )
            entryDao.update(updatedEntity)

            val hashedEntity = hydrator.recomputeAndPersistContentHash(entryId) ?: updatedEntity
            ftsIndexManager.reindexEntry(entryId)

            hydrator.hydrateEntry(hashedEntity)
        }

    override suspend fun softDeleteEntry(entryId: String, nowMs: Long) {
        database.inWriteTransaction {
            val existing = entryDao.getById(entryId) ?: return@inWriteTransaction
            entryDao.update(existing.copy(deletedAtMs = nowMs))
            hydrator.recomputeAndPersistContentHash(entryId)
            ftsIndexManager.removeEntry(entryId)
        }
    }

    override suspend fun undoSoftDelete(entryId: String) {
        database.inWriteTransaction {
            val existing = entryDao.getById(entryId) ?: return@inWriteTransaction
            entryDao.update(existing.copy(deletedAtMs = null))
            hydrator.recomputeAndPersistContentHash(entryId)
            ftsIndexManager.reindexEntry(entryId)
        }
    }

    override fun observeCompactSummaries(): Flow<List<CompactEntrySummary>> =
        entryDao.observeCompactSummaries()
            .map { rows -> rows.map { it.toDomain() } }
            .distinctUntilChanged()

    override fun observeCalendarDayCounts(
        yearMonth: String,
        filterTagPathPrefix: String?,
    ): Flow<List<CalendarDayCount>> {
        val normalizedPrefix = filterTagPathPrefix
            ?.let { TagPathNormalizer.normalize(it) }
            ?.takeIf { it.isNotEmpty() }

        val sourceFlow = if (normalizedPrefix == null) {
            entryDao.observeCalendarDayCounts(yearMonth)
        } else {
            val subtreeLike = "${TagPathNormalizer.escapeLike(normalizedPrefix)}/%"
            entryDao.observeCalendarDayCountsByTagPrefix(
                yearMonth = yearMonth,
                exactTagPath = normalizedPrefix,
                subtreeLikePattern = subtreeLike,
            )
        }

        return sourceFlow
            .map { rows -> rows.map { it.toDomain() } }
            .distinctUntilChanged()
    }

    override fun observeEntriesForDate(
        localDate: String,
        filterTagPathPrefix: String?,
    ): Flow<List<EntryWithDetails>> {
        val normalizedPrefix = filterTagPathPrefix
            ?.let { TagPathNormalizer.normalize(it) }
            ?.takeIf { it.isNotEmpty() }

        return observeHydratedTables()
            .mapLatest {
                database.inReadTransaction {
                    val entries = if (normalizedPrefix == null) {
                        entryDao.getEntriesForDate(localDate)
                    } else {
                        val subtreeLike = "${TagPathNormalizer.escapeLike(normalizedPrefix)}/%"
                        entryDao.getEntriesForDateByTagPrefix(
                            localDate = localDate,
                            exactTagPath = normalizedPrefix,
                            subtreeLikePattern = subtreeLike,
                        )
                    }
                    hydrator.hydrateEntries(entries)
                }
            }
            .distinctUntilChanged()
    }

    override fun observeContextWindow(startMs: Long, endMs: Long): Flow<List<EntryWithDetails>> =
        observeHydratedTables()
            .mapLatest {
                database.inReadTransaction {
                    val entries = entryDao.getContextWindow(startMs, endMs)
                    hydrator.hydrateEntries(entries)
                }
            }
            .distinctUntilChanged()

    override fun observeEntriesWithPendingAutoTags(): Flow<List<EntryWithDetails>> =
        observeHydratedTables()
            .mapLatest {
                database.inReadTransaction {
                    val entries = entryDao.getEntriesWithPendingAutoTags()
                    hydrator.hydrateEntries(entries)
                }
            }
            .distinctUntilChanged()

    private fun observeHydratedTables(): Flow<Set<String>> =
        database.invalidationTracker.createFlow(
            "entries",
            "tags",
            "entry_tags",
            "bookmarks",
            "attachments",
            emitInitialState = true,
        )

    private companion object {
        const val DEFAULT_PAGE_SIZE = 30
    }
}
