// Copyright (c) 2026 k8242
// SPDX-License-Identifier: MIT

package io.github.k8242.threadlog.core.data.repository

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.PagingState
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
import io.github.k8242.threadlog.core.data.mapper.ReactionsJsonCodec
import io.github.k8242.threadlog.core.data.mapper.TagPathNormalizer
import io.github.k8242.threadlog.core.data.mapper.toDomain
import io.github.k8242.threadlog.core.model.CalendarDayCount
import io.github.k8242.threadlog.core.model.ChatFeedFilter
import io.github.k8242.threadlog.core.model.CompactEntrySummary
import io.github.k8242.threadlog.core.model.DrawerOverviewSummary
import io.github.k8242.threadlog.core.model.DrawerTagSummary
import io.github.k8242.threadlog.core.model.EntryWithDetails
import io.github.k8242.threadlog.core.model.TagRootCategory
import io.github.k8242.threadlog.core.model.TagState
import io.github.k8242.threadlog.core.model.TaskPriority
import io.github.k8242.threadlog.core.model.TaskStatus
import io.github.k8242.threadlog.core.model.TimelineDateBucket
import io.github.k8242.threadlog.core.model.TodoCounts
import io.github.k8242.threadlog.core.model.TodoFilterMode
import io.github.k8242.threadlog.core.model.TodoSortOrder
import io.github.k8242.threadlog.core.model.contract.DateWeekCalculator
import io.github.k8242.threadlog.core.model.contract.EntryRepository
import io.github.k8242.threadlog.core.model.contract.MessageParser
import io.github.k8242.threadlog.core.model.contract.PendingAttachment
import io.github.k8242.threadlog.core.model.parser.InlineTagToken
import io.github.k8242.threadlog.core.model.parser.TagTriggerKind
import io.github.k8242.threadlog.core.parser.DefaultMessageParser
import io.github.k8242.threadlog.core.parser.HiddenPlaceholderMatcher
import io.github.k8242.threadlog.core.parser.SensitiveCredentialGuard
import io.github.k8242.threadlog.core.parser.UuidV7
import java.time.ZoneId
import java.util.Locale
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
        observePagedEntries(filter = ChatFeedFilter.All(), includeHiddenPlaceholders = false)

    override fun observePagedEntries(filter: ChatFeedFilter): Flow<PagingData<EntryWithDetails>> =
        observePagedEntries(filter = filter, includeHiddenPlaceholders = false)

    override fun observePagedEntries(
        filter: ChatFeedFilter,
        includeHiddenPlaceholders: Boolean,
    ): Flow<PagingData<EntryWithDetails>> =
        Pager(
            config = PagingConfig(
                pageSize = DEFAULT_PAGE_SIZE,
                enablePlaceholders = true,
                jumpThreshold = JUMP_THRESHOLD,
                maxSize = MAX_PAGING_SIZE,
            ),
            pagingSourceFactory = {
                val delegate = when (filter) {
                    is ChatFeedFilter.All -> entryDao.pagingSourceAll(
                        includeHidden = includeHiddenPlaceholders,
                        untaggedOnly = filter.untaggedOnly,
                        hideTodos = filter.hideTodos,
                    )

                    is ChatFeedFilter.Todos -> {
                        val statuses = todoStatusesForMode(filter.mode)
                        val priority = filter.priorityFilter?.name
                        val (exactTagPath, subtreeLike) = normalizeTagPrefixFilter(filter.tagFullPath)
                        when (filter.sortOrder) {
                            TodoSortOrder.NEWEST -> entryDao.pagingSourceTodosByNewest(
                                statuses = statuses,
                                priorityFilter = priority,
                                exactTagPath = exactTagPath,
                                subtreeLikePattern = subtreeLike,
                            )

                            TodoSortOrder.PRIORITY -> entryDao.pagingSourceTodosByPriority(
                                statuses = statuses,
                                priorityFilter = priority,
                                exactTagPath = exactTagPath,
                                subtreeLikePattern = subtreeLike,
                            )
                        }
                    }

                    is ChatFeedFilter.TagSubtree -> {
                        val (exactTagPath, subtreeLike) = normalizeTagPrefixFilter(filter.effectiveFullPath)
                        val todoMode = filter.todoFilterMode
                        if (todoMode != null) {
                            val statuses = todoStatusesForMode(todoMode)
                            val priority = filter.priorityFilter?.name
                            when (filter.todoSortOrder) {
                                TodoSortOrder.NEWEST -> entryDao.pagingSourceTodosByNewest(
                                    statuses = statuses,
                                    priorityFilter = priority,
                                    exactTagPath = exactTagPath,
                                    subtreeLikePattern = subtreeLike,
                                )

                                TodoSortOrder.PRIORITY -> entryDao.pagingSourceTodosByPriority(
                                    statuses = statuses,
                                    priorityFilter = priority,
                                    exactTagPath = exactTagPath,
                                    subtreeLikePattern = subtreeLike,
                                )
                            }
                        } else if (exactTagPath == null || subtreeLike == null) {
                            entryDao.pagingSourceAll(includeHidden = false)
                        } else {
                            entryDao.pagingSourceByTagPrefix(
                                exactTagPath = exactTagPath,
                                subtreeLikePattern = subtreeLike,
                            )
                        }
                    }

                    ChatFeedFilter.Archived -> entryDao.pagingSourceArchived()
                }
                BatchHydratingPagingSource(
                    delegate = delegate,
                    database = database,
                    hydrator = hydrator,
                )
            },
        ).flow

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
        taskStatus: TaskStatus,
    ): EntryWithDetails = createEntry(
        bodyRaw = bodyRaw,
        userTagIds = userTagIds,
        replyToId = replyToId,
        pendingAttachments = pendingAttachments,
        nowMs = nowMs,
        zoneId = zoneId,
        taskStatus = taskStatus,
        taskPriority = TaskPriority.NONE,
    )

    override suspend fun createEntry(
        bodyRaw: String,
        userTagIds: List<String>,
        replyToId: String?,
        pendingAttachments: List<PendingAttachment>,
        nowMs: Long,
        zoneId: String?,
        taskStatus: TaskStatus,
        taskPriority: TaskPriority,
    ): EntryWithDetails {
        SensitiveCredentialGuard.requireAllowed(bodyRaw)
        val resolvedZoneId = zoneId?.takeIf { it.isNotBlank() } ?: ZoneId.systemDefault().id
        val (localDate, isoWeek) = dateWeekCalculator.localDateAndIsoWeek(nowMs, resolvedZoneId)
        val (cleanedBodyRaw, inlineTags) = DefaultMessageParser.stripInlineTagsAndUnescape(bodyRaw)
        val parsed = messageParser.parse(cleanedBodyRaw)
        val entryId = UuidV7.generate(nowMs)
        val effectivePriority = if (taskStatus == TaskStatus.NONE) TaskPriority.NONE else taskPriority

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
            val resolvedInlineTagIds = resolveOrCreateInlineTags(inlineTags)
            val distinctTagIds = (userTagIds + resolvedInlineTagIds).distinct()
            val validTags = if (distinctTagIds.isEmpty()) {
                emptyList()
            } else {
                tagDao.getByIds(distinctTagIds)
            }

            val tagAttachMs = System.currentTimeMillis()
            val entryTags = validTags.map { tag ->
                EntryTagEntity(
                    entryId = entryId,
                    tagId = tag.id,
                    state = TagState.USER.name,
                    confidence = 1.0f,
                    updatedAtMs = tagAttachMs,
                )
            }

            val contentHash = EntryContentHasher.compute(
                bodyRaw = cleanedBodyRaw,
                replyToId = replyToId,
                isDeleted = false,
                visibleTagPaths = validTags.map { it.fullPath },
                hiddenTagPaths = emptyList(),
                rejectedTagPaths = emptyList(),
                bookmarkSignatures = bookmarks.map { "${it.urlRaw}|${it.title.orEmpty()}" },
                attachmentSha256s = attachments.map { it.sha256 },
                taskStatus = taskStatus,
                taskPriority = effectivePriority,
                isHighlighted = false,
                isArchived = false,
            )

            val entity = EntryEntity(
                id = entryId,
                createdAtMs = nowMs,
                zoneId = resolvedZoneId,
                localDate = localDate,
                isoWeek = isoWeek,
                bodyRaw = cleanedBodyRaw,
                bodyPlain = parsed.bodyPlain,
                replyToId = replyToId,
                contentHash = contentHash,
                updatedAtMs = null,
                deletedAtMs = null,
                taskStatus = taskStatus.name,
                reactionsJson = "[]",
                taskPriority = effectivePriority.name,
                archivedAtMs = null,
                isHighlighted = false,
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
    ): EntryWithDetails = editEntry(
        entryId = entryId,
        newBodyRaw = newBodyRaw,
        nowMs = nowMs,
        addedAttachments = emptyList(),
        removedAttachmentIds = emptySet(),
    )

    override suspend fun editEntry(
        entryId: String,
        newBodyRaw: String,
        nowMs: Long,
        addedAttachments: List<PendingAttachment>,
        removedAttachmentIds: Set<String>,
    ): EntryWithDetails {
        SensitiveCredentialGuard.requireAllowed(newBodyRaw)
        return database.inWriteTransaction {
            val existing = entryDao.getById(entryId)
                ?: throw NoSuchElementException("Entry not found: $entryId")

            // Preserve any literal @/#/+ tokens that were already in existing.bodyRaw (e.g. from prior \@foo).
            val preserveLiteralTokens = DefaultMessageParser.extractLiteralTagTokenKeys(existing.bodyRaw)
            val (cleanedBodyRaw, inlineTags) = DefaultMessageParser.stripInlineTagsAndUnescape(
                text = newBodyRaw,
                preserveLiteralTokens = preserveLiteralTokens,
            )
            val parsed = messageParser.parse(cleanedBodyRaw)
            val updatedAtMs = maxOf(nowMs, existing.createdAtMs + 1L)

            // Attach any newly typed inline @person / #topic / +project tags as USER tags.
            val inlineTagIds = resolveOrCreateInlineTags(inlineTags)
            if (inlineTagIds.isNotEmpty()) {
                val inlineEntryTags = inlineTagIds.map { tagId ->
                    EntryTagEntity(
                        entryId = entryId,
                        tagId = tagId,
                        state = TagState.USER.name,
                        confidence = 1.0f,
                        updatedAtMs = updatedAtMs,
                    )
                }
                tagDao.upsertEntryTags(inlineEntryTags)
            }

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

            // Reconcile image attachments: delete removed IDs, keep existing IDs/driveFileIds,
            // and insert newly added attachments deduped by sha256 per entry.
            if (removedAttachmentIds.isNotEmpty()) {
                attachmentDao.deleteByIds(entryId, removedAttachmentIds.toList())
            }
            if (addedAttachments.isNotEmpty()) {
                val remainingSha256s = attachmentDao.getForEntry(entryId)
                    .mapTo(HashSet()) { it.sha256 }
                val newAttachments = mutableListOf<AttachmentEntity>()
                for (pending in addedAttachments) {
                    if (remainingSha256s.add(pending.sha256)) {
                        newAttachments += AttachmentEntity(
                            id = UuidV7.generate(updatedAtMs),
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
                }
                if (newAttachments.isNotEmpty()) {
                    attachmentDao.upsertAll(newAttachments)
                }
            }

            // Preserve original createdAtMs, zoneId, localDate, isoWeek, replyToId, deletedAtMs,
            // taskStatus, taskPriority, isHighlighted, archivedAtMs, reactionsJson.
            val updatedEntity = existing.copy(
                bodyRaw = cleanedBodyRaw,
                bodyPlain = parsed.bodyPlain,
                updatedAtMs = updatedAtMs,
            )
            entryDao.update(updatedEntity)

            val hashedEntity = hydrator.recomputeAndPersistContentHash(entryId) ?: updatedEntity
            ftsIndexManager.reindexEntry(entryId)

            hydrator.hydrateEntry(hashedEntity)
        }
    }

    override suspend fun setTaskStatus(
        entryId: String,
        taskStatus: TaskStatus,
        nowMs: Long,
    ) {
        database.inWriteTransaction {
            val existing = entryDao.getById(entryId) ?: return@inWriteTransaction
            val nextPriority = if (taskStatus == TaskStatus.NONE) {
                TaskPriority.NONE.name
            } else {
                existing.taskPriority
            }
            if (existing.taskStatus == taskStatus.name && existing.taskPriority == nextPriority) {
                return@inWriteTransaction
            }
            val updatedAtMs = maxOf(nowMs, existing.createdAtMs + 1L)
            entryDao.update(
                existing.copy(
                    taskStatus = taskStatus.name,
                    taskPriority = nextPriority,
                    updatedAtMs = updatedAtMs,
                ),
            )
            hydrator.recomputeAndPersistContentHash(entryId)
        }
    }

    override suspend fun setTaskPriority(
        entryId: String,
        taskPriority: TaskPriority,
        nowMs: Long,
    ) {
        database.inWriteTransaction {
            val existing = entryDao.getById(entryId) ?: return@inWriteTransaction
            val nextStatus = if (taskPriority != TaskPriority.NONE && existing.taskStatus == TaskStatus.NONE.name) {
                TaskStatus.TODO.name
            } else {
                existing.taskStatus
            }
            if (existing.taskPriority == taskPriority.name && existing.taskStatus == nextStatus) {
                return@inWriteTransaction
            }
            val updatedAtMs = maxOf(nowMs, existing.createdAtMs + 1L)
            entryDao.update(
                existing.copy(
                    taskStatus = nextStatus,
                    taskPriority = taskPriority.name,
                    updatedAtMs = updatedAtMs,
                ),
            )
            hydrator.recomputeAndPersistContentHash(entryId)
        }
    }

    override suspend fun setHighlighted(
        entryId: String,
        highlighted: Boolean,
    ) {
        database.inWriteTransaction {
            val existing = entryDao.getById(entryId) ?: return@inWriteTransaction
            if (existing.isHighlighted == highlighted) return@inWriteTransaction
            // Yellow highlight intentionally preserves existing.updatedAtMs so it never marks a note as Edited.
            entryDao.update(existing.copy(isHighlighted = highlighted))
            hydrator.recomputeAndPersistContentHash(entryId)
        }
    }

    override suspend fun setArchived(
        entryId: String,
        archived: Boolean,
        nowMs: Long,
    ) {
        database.inWriteTransaction {
            val existing = entryDao.getById(entryId) ?: return@inWriteTransaction
            if ((existing.archivedAtMs != null) == archived) return@inWriteTransaction
            // Archiving/unarchiving intentionally preserves existing.updatedAtMs so it never marks a note as Edited.
            entryDao.update(existing.copy(archivedAtMs = if (archived) nowMs else null))
            hydrator.recomputeAndPersistContentHash(entryId)
        }
    }

    override suspend fun updateEntryMetadata(
        entryId: String,
        createdAtMs: Long,
        zoneId: String,
        taskStatus: TaskStatus,
        taskPriority: TaskPriority,
        isHighlighted: Boolean,
        archivedAtMs: Long?,
        nowMs: Long,
    ) {
        val validatedZoneId = ZoneId.of(zoneId.trim()).id
        val (localDate, isoWeek) = dateWeekCalculator.localDateAndIsoWeek(createdAtMs, validatedZoneId)
        val effectivePriority = if (taskStatus == TaskStatus.NONE) TaskPriority.NONE else taskPriority

        database.inWriteTransaction {
            val existing = entryDao.getById(entryId) ?: return@inWriteTransaction
            val taskChanged = existing.taskStatus != taskStatus.name ||
                existing.taskPriority != effectivePriority.name
            // Preserve existing.updatedAtMs when only timestamp, timezone, highlight, or archive status changed.
            val nextUpdatedAtMs = if (taskChanged) {
                maxOf(nowMs, createdAtMs + 1L)
            } else {
                existing.updatedAtMs
            }

            entryDao.update(
                existing.copy(
                    createdAtMs = createdAtMs,
                    zoneId = validatedZoneId,
                    localDate = localDate,
                    isoWeek = isoWeek,
                    taskStatus = taskStatus.name,
                    taskPriority = effectivePriority.name,
                    isHighlighted = isHighlighted,
                    archivedAtMs = archivedAtMs,
                    updatedAtMs = nextUpdatedAtMs,
                ),
            )
            hydrator.recomputeAndPersistContentHash(entryId)
        }
    }

    override suspend fun toggleReaction(
        entryId: String,
        emoji: String,
        nowMs: Long,
    ) {
        val cleaned = emoji.trim()
        if (cleaned.isEmpty()) return
        database.inWriteTransaction {
            val existing = entryDao.getById(entryId) ?: return@inWriteTransaction
            val current = ReactionsJsonCodec.decode(existing.reactionsJson)
            val updated = if (cleaned in current) {
                current.filterNot { it == cleaned }
            } else {
                current + cleaned
            }
            // Emoji reactions intentionally preserve existing.updatedAtMs so they never mark
            // a note as Edited or alter its edited timestamp.
            entryDao.update(
                existing.copy(
                    reactionsJson = ReactionsJsonCodec.encode(updated),
                ),
            )
            hydrator.recomputeAndPersistContentHash(entryId)
        }
    }

    override suspend fun findEntryOffset(
        entryId: String,
        filter: ChatFeedFilter,
        includeHiddenPlaceholders: Boolean,
    ): Int? = database.inReadTransaction {
        val entry = entryDao.getById(entryId) ?: return@inReadTransaction null
        if (entry.deletedAtMs != null) return@inReadTransaction null

        val isHidden = HiddenPlaceholderMatcher.isHiddenPlaceholder(entry.bodyPlain) &&
            attachmentDao.getForEntry(entryId).isEmpty()

        when (filter) {
            is ChatFeedFilter.All -> {
                if (entry.archivedAtMs != null) return@inReadTransaction null
                if (!includeHiddenPlaceholders && isHidden) return@inReadTransaction null
                if (filter.hideTodos && entry.taskStatus != TaskStatus.NONE.name) {
                    return@inReadTransaction null
                }
                if (filter.untaggedOnly && entryDao.entryHasVisibleTag(entry.id)) {
                    return@inReadTransaction null
                }
                entryDao.countAheadAll(
                    targetCreatedAtMs = entry.createdAtMs,
                    targetId = entry.id,
                    includeHidden = includeHiddenPlaceholders,
                    untaggedOnly = filter.untaggedOnly,
                    hideTodos = filter.hideTodos,
                )
            }

            ChatFeedFilter.Archived -> {
                if (entry.archivedAtMs == null || isHidden) return@inReadTransaction null
                entryDao.countAheadArchived(
                    targetCreatedAtMs = entry.createdAtMs,
                    targetId = entry.id,
                )
            }

            is ChatFeedFilter.Todos -> {
                if (entry.archivedAtMs != null || isHidden) return@inReadTransaction null
                val statuses = todoStatusesForMode(filter.mode)
                if (entry.taskStatus !in statuses) return@inReadTransaction null
                val priorityFilter = filter.priorityFilter?.name
                if (priorityFilter != null && entry.taskPriority != priorityFilter) {
                    return@inReadTransaction null
                }
                val (exactTagPath, subtreeLike) = normalizeTagPrefixFilter(filter.tagFullPath)
                if (exactTagPath != null && subtreeLike != null) {
                    val matches = entryDao.entryMatchesTagPrefix(
                        entryId = entry.id,
                        exactTagPath = exactTagPath,
                        subtreeLikePattern = subtreeLike,
                    )
                    if (!matches) return@inReadTransaction null
                }
                countAheadTodos(
                    entry = entry,
                    statuses = statuses,
                    priorityFilter = priorityFilter,
                    sortOrder = filter.sortOrder,
                    exactTagPath = exactTagPath,
                    subtreeLikePattern = subtreeLike,
                )
            }

            is ChatFeedFilter.TagSubtree -> {
                if (entry.archivedAtMs != null || isHidden) return@inReadTransaction null
                val (exactTagPath, subtreeLike) = normalizeTagPrefixFilter(filter.effectiveFullPath)
                if (exactTagPath != null && subtreeLike != null) {
                    val matches = entryDao.entryMatchesTagPrefix(
                        entryId = entry.id,
                        exactTagPath = exactTagPath,
                        subtreeLikePattern = subtreeLike,
                    )
                    if (!matches) return@inReadTransaction null
                }
                val todoMode = filter.todoFilterMode
                if (todoMode != null) {
                    val statuses = todoStatusesForMode(todoMode)
                    if (entry.taskStatus !in statuses) return@inReadTransaction null
                    val priorityFilter = filter.priorityFilter?.name
                    if (priorityFilter != null && entry.taskPriority != priorityFilter) {
                        return@inReadTransaction null
                    }
                    countAheadTodos(
                        entry = entry,
                        statuses = statuses,
                        priorityFilter = priorityFilter,
                        sortOrder = filter.todoSortOrder,
                        exactTagPath = exactTagPath,
                        subtreeLikePattern = subtreeLike,
                    )
                } else if (exactTagPath == null || subtreeLike == null) {
                    entryDao.countAheadAll(
                        targetCreatedAtMs = entry.createdAtMs,
                        targetId = entry.id,
                        includeHidden = false,
                    )
                } else {
                    entryDao.countAheadTagPrefix(
                        exactTagPath = exactTagPath,
                        subtreeLikePattern = subtreeLike,
                        targetCreatedAtMs = entry.createdAtMs,
                        targetId = entry.id,
                    )
                }
            }
        }
    }

    override suspend fun findDateOffset(
        localDate: String,
        filter: ChatFeedFilter,
        includeHiddenPlaceholders: Boolean,
    ): Int? = database.inReadTransaction {
        val target = when (filter) {
            is ChatFeedFilter.All -> entryDao.getNewestEntryForDateOnOrBeforeAll(
                localDate = localDate,
                includeHidden = includeHiddenPlaceholders,
                untaggedOnly = filter.untaggedOnly,
                hideTodos = filter.hideTodos,
            )

            ChatFeedFilter.Archived -> entryDao.getNewestEntryForDateOnOrBeforeArchived(
                localDate = localDate,
            )

            is ChatFeedFilter.Todos -> {
                val (exactTagPath, subtreeLike) = normalizeTagPrefixFilter(filter.tagFullPath)
                entryDao.getNewestEntryForDateOnOrBeforeTodos(
                    localDate = localDate,
                    statuses = todoStatusesForMode(filter.mode),
                    priorityFilter = filter.priorityFilter?.name,
                    exactTagPath = exactTagPath,
                    subtreeLikePattern = subtreeLike,
                )
            }

            is ChatFeedFilter.TagSubtree -> {
                val (exactTagPath, subtreeLike) = normalizeTagPrefixFilter(filter.effectiveFullPath)
                val todoMode = filter.todoFilterMode
                if (todoMode != null) {
                    entryDao.getNewestEntryForDateOnOrBeforeTodos(
                        localDate = localDate,
                        statuses = todoStatusesForMode(todoMode),
                        priorityFilter = filter.priorityFilter?.name,
                        exactTagPath = exactTagPath,
                        subtreeLikePattern = subtreeLike,
                    )
                } else if (exactTagPath == null || subtreeLike == null) {
                    entryDao.getNewestEntryForDateOnOrBeforeAll(
                        localDate = localDate,
                        includeHidden = false,
                    )
                } else {
                    entryDao.getNewestEntryForDateOnOrBeforeTagPrefix(
                        localDate = localDate,
                        exactTagPath = exactTagPath,
                        subtreeLikePattern = subtreeLike,
                    )
                }
            }
        } ?: return@inReadTransaction null

        findEntryOffset(
            entryId = target.id,
            filter = filter,
            includeHiddenPlaceholders = includeHiddenPlaceholders,
        )
    }

    override fun observeTimelineDateBuckets(
        filter: ChatFeedFilter,
        includeHiddenPlaceholders: Boolean,
    ): Flow<List<TimelineDateBucket>> {
        val sourceFlow = when (filter) {
            is ChatFeedFilter.All -> entryDao.observeTimelineDateBucketsAll(
                includeHidden = includeHiddenPlaceholders,
                untaggedOnly = filter.untaggedOnly,
                hideTodos = filter.hideTodos,
            )

            ChatFeedFilter.Archived -> entryDao.observeTimelineDateBucketsArchived()

            is ChatFeedFilter.Todos -> {
                val (exactTagPath, subtreeLike) = normalizeTagPrefixFilter(filter.tagFullPath)
                entryDao.observeTimelineDateBucketsTodos(
                    statuses = todoStatusesForMode(filter.mode),
                    priorityFilter = filter.priorityFilter?.name,
                    exactTagPath = exactTagPath,
                    subtreeLikePattern = subtreeLike,
                )
            }

            is ChatFeedFilter.TagSubtree -> {
                val (exactTagPath, subtreeLike) = normalizeTagPrefixFilter(filter.effectiveFullPath)
                val todoMode = filter.todoFilterMode
                if (todoMode != null) {
                    entryDao.observeTimelineDateBucketsTodos(
                        statuses = todoStatusesForMode(todoMode),
                        priorityFilter = filter.priorityFilter?.name,
                        exactTagPath = exactTagPath,
                        subtreeLikePattern = subtreeLike,
                    )
                } else if (exactTagPath == null || subtreeLike == null) {
                    entryDao.observeTimelineDateBucketsAll(includeHidden = false)
                } else {
                    entryDao.observeTimelineDateBucketsTagPrefix(
                        exactTagPath = exactTagPath,
                        subtreeLikePattern = subtreeLike,
                    )
                }
            }
        }
        return sourceFlow
            .map { rows -> rows.map { TimelineDateBucket(localDate = it.localDate, entryCount = it.entryCount) } }
            .distinctUntilChanged()
    }

    override fun observeTodoCounts(tagFullPath: String?): Flow<TodoCounts> {
        val (exactTagPath, subtreeLike) = normalizeTagPrefixFilter(tagFullPath)
        return entryDao.observeTodoCounts(
            exactTagPath = exactTagPath,
            subtreeLikePattern = subtreeLike,
        )
            .map { projection ->
                TodoCounts(
                    openCount = projection.openCount,
                    completedCount = projection.completedCount,
                )
            }
            .distinctUntilChanged()
    }

    private suspend fun countAheadTodos(
        entry: EntryEntity,
        statuses: List<String>,
        priorityFilter: String?,
        sortOrder: TodoSortOrder,
        exactTagPath: String?,
        subtreeLikePattern: String?,
    ): Int = when (sortOrder) {
        TodoSortOrder.NEWEST -> entryDao.countAheadTodosByNewest(
            statuses = statuses,
            priorityFilter = priorityFilter,
            targetCreatedAtMs = entry.createdAtMs,
            targetId = entry.id,
            exactTagPath = exactTagPath,
            subtreeLikePattern = subtreeLikePattern,
        )

        TodoSortOrder.PRIORITY -> {
            val rank = runCatching { TaskPriority.valueOf(entry.taskPriority) }
                .getOrDefault(TaskPriority.NONE)
                .sortRank
            entryDao.countAheadTodosByPriority(
                statuses = statuses,
                priorityFilter = priorityFilter,
                targetPriorityRank = rank,
                targetCreatedAtMs = entry.createdAtMs,
                targetId = entry.id,
                exactTagPath = exactTagPath,
                subtreeLikePattern = subtreeLikePattern,
            )
        }
    }

    private fun normalizeTagPrefixFilter(rawPath: String?): Pair<String?, String?> {
        val normalized = rawPath
            ?.let { TagPathNormalizer.normalize(it) }
            ?.takeIf { it.isNotEmpty() }
            ?: return null to null
        return normalized to "${TagPathNormalizer.escapeLike(normalized)}/%"
    }

    private suspend fun resolveOrCreateInlineTags(
        inlineTags: List<InlineTagToken>,
    ): List<String> {
        if (inlineTags.isEmpty()) return emptyList()

        val existingTags = tagDao.getAll().map { it.toDomain() }
        val resolvedIds = LinkedHashSet<String>()

        for (token in inlineTags) {
            val normalizedRaw = TagPathNormalizer.normalize(token.rawValue)
            if (normalizedRaw.isEmpty()) continue
            val rawLower = normalizedRaw.lowercase(Locale.ROOT)

            when (token.kind) {
                TagTriggerKind.PROJECT_PLUS -> {
                    val match = if (normalizedRaw.contains('/')) {
                        val targetPath = if (rawLower.startsWith("projects/")) normalizedRaw else "projects/$normalizedRaw"
                        existingTags.firstOrNull { it.fullPath.equals(targetPath, ignoreCase = true) }
                    } else {
                        existingTags
                            .filter { it.rootCategory == TagRootCategory.PROJECTS }
                            .firstOrNull {
                                it.leafName.equals(normalizedRaw, ignoreCase = true) ||
                                    it.aliases.any { alias -> alias.equals(normalizedRaw, ignoreCase = true) }
                            }
                    }
                    if (match != null) {
                        resolvedIds += match.id
                    } else {
                        val targetPath = if (rawLower.startsWith("projects/")) normalizedRaw else "projects/$normalizedRaw"
                        resolvedIds += hydrator.ensureTagChain(targetPath).first.id
                    }
                }

                TagTriggerKind.PERSON_AT -> {
                    val match = if (normalizedRaw.contains('/')) {
                        val targetPath = if (rawLower.startsWith("people/")) normalizedRaw else "people/$normalizedRaw"
                        existingTags.firstOrNull { it.fullPath.equals(targetPath, ignoreCase = true) }
                    } else {
                        existingTags
                            .filter { it.rootCategory == TagRootCategory.PEOPLE }
                            .firstOrNull {
                                it.leafName.equals(normalizedRaw, ignoreCase = true) ||
                                    it.aliases.any { alias -> alias.equals(normalizedRaw, ignoreCase = true) }
                            }
                    }
                    if (match != null) {
                        resolvedIds += match.id
                    } else {
                        val targetPath = if (rawLower.startsWith("people/")) normalizedRaw else "people/$normalizedRaw"
                        resolvedIds += hydrator.ensureTagChain(targetPath).first.id
                    }
                }

                TagTriggerKind.ALL_HASH -> {
                    val match = if (normalizedRaw.contains('/')) {
                        existingTags.firstOrNull { it.fullPath.equals(normalizedRaw, ignoreCase = true) }
                    } else {
                        existingTags.firstOrNull {
                            it.fullPath.equals(normalizedRaw, ignoreCase = true) ||
                                it.leafName.equals(normalizedRaw, ignoreCase = true) ||
                                it.aliases.any { alias -> alias.equals(normalizedRaw, ignoreCase = true) }
                        }
                    }
                    if (match != null) {
                        resolvedIds += match.id
                    } else {
                        val targetPath = if (normalizedRaw.contains('/')) normalizedRaw else "topics/$normalizedRaw"
                        resolvedIds += hydrator.ensureTagChain(targetPath).first.id
                    }
                }
            }
        }

        return resolvedIds.toList()
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

    override fun observeDrawerOverview(): Flow<DrawerOverviewSummary> =
        database.invalidationTracker.createFlow("entries", "attachments", emitInitialState = true)
            .mapLatest {
                database.inReadTransaction {
                    val projection = entryDao.getDrawerOverview()
                    DrawerOverviewSummary(
                        totalEntryCount = projection.totalEntryCount,
                        latestEntryPreview = projection.latestEntryPreview?.trim()?.takeIf { it.isNotEmpty() },
                        openTodoCount = projection.openTodoCount ?: 0,
                        completedTodoCount = projection.completedTodoCount ?: 0,
                        latestOpenTodoPreview = projection.latestOpenTodoPreview?.trim()?.takeIf { it.isNotEmpty() },
                        archivedCount = projection.archivedCount ?: 0,
                    )
                }
            }
            .distinctUntilChanged()

    override fun observeDrawerTagSummaries(): Flow<List<DrawerTagSummary>> =
        database.invalidationTracker.createFlow(
            "entries",
            "tags",
            "entry_tags",
            "attachments",
            emitInitialState = true,
        )
            .mapLatest {
                database.inReadTransaction {
                    val allTags = tagDao.getAll().map { it.toDomain() }
                    if (allTags.isEmpty()) return@inReadTransaction emptyList()

                    val links = entryDao.getActiveVisibleEntryTagLinks()

                    class TagAccumulator {
                        val entryIds = HashSet<String>()
                        val openTodoIds = HashSet<String>()
                        var latestPreview: String? = null
                        var latestCreatedAtMs: Long? = null
                    }

                    val statsByPathLower = HashMap<String, TagAccumulator>()

                    for (link in links) {
                        val normalized = TagPathNormalizer.normalize(link.tagFullPath)
                        if (normalized.isEmpty()) continue
                        val segments = normalized.split('/')
                        val prefixBuilder = StringBuilder()
                        for (i in segments.indices) {
                            if (i > 0) prefixBuilder.append('/')
                            prefixBuilder.append(segments[i])
                            val key = prefixBuilder.toString().lowercase(Locale.ROOT)
                            val acc = statsByPathLower.getOrPut(key) { TagAccumulator() }
                            acc.entryIds += link.entryId
                            if (link.taskStatus == TaskStatus.TODO.name) {
                                acc.openTodoIds += link.entryId
                            }
                            if (acc.latestCreatedAtMs == null) {
                                acc.latestCreatedAtMs = link.createdAtMs
                                acc.latestPreview = link.preview.trim().takeIf { it.isNotEmpty() }
                            }
                        }
                    }

                    allTags
                        .map { tag ->
                            val key = TagPathNormalizer.normalize(tag.fullPath).lowercase(Locale.ROOT)
                            val acc = statsByPathLower[key]
                            DrawerTagSummary(
                                tag = tag,
                                entryCount = acc?.entryIds?.size ?: 0,
                                openTodoCount = acc?.openTodoIds?.size ?: 0,
                                latestPreview = acc?.latestPreview,
                                latestCreatedAtMs = acc?.latestCreatedAtMs,
                            )
                        }
                        .sortedWith(
                            compareByDescending<DrawerTagSummary> { it.latestCreatedAtMs ?: Long.MIN_VALUE }
                                .thenBy { it.tag.leafName.lowercase(Locale.ROOT) }
                                .thenBy { it.tag.fullPath.lowercase(Locale.ROOT) },
                        )
                }
            }
            .distinctUntilChanged()

    private fun todoStatusesForMode(mode: TodoFilterMode): List<String> = when (mode) {
        TodoFilterMode.OPEN -> listOf(TaskStatus.TODO.name)
        TodoFilterMode.DONE -> listOf(TaskStatus.DONE.name)
        TodoFilterMode.ALL -> listOf(TaskStatus.TODO.name, TaskStatus.DONE.name)
    }

    private fun observeHydratedTables(): Flow<Set<String>> =
        database.invalidationTracker.createFlow(
            "entries",
            "tags",
            "entry_tags",
            "bookmarks",
            "attachments",
            emitInitialState = true,
        )

    private class BatchHydratingPagingSource(
        private val delegate: PagingSource<Int, EntryEntity>,
        private val database: ThreadLogDatabase,
        private val hydrator: EntryHydrator,
    ) : PagingSource<Int, EntryWithDetails>() {

        init {
            delegate.registerInvalidatedCallback {
                invalidate()
            }
            registerInvalidatedCallback {
                delegate.invalidate()
            }
            if (delegate.invalid) {
                invalidate()
            }
        }

        override val jumpingSupported: Boolean
            get() = delegate.jumpingSupported

        override val keyReuseSupported: Boolean
            get() = delegate.keyReuseSupported

        override suspend fun load(params: LoadParams<Int>): LoadResult<Int, EntryWithDetails> {
            return when (val result = delegate.load(params)) {
                is LoadResult.Page -> {
                    val hydrated = if (result.data.isEmpty()) {
                        emptyList()
                    } else {
                        database.inReadTransaction {
                            hydrator.hydrateEntries(result.data)
                        }
                    }
                    LoadResult.Page(
                        data = hydrated,
                        prevKey = result.prevKey,
                        nextKey = result.nextKey,
                        itemsBefore = result.itemsBefore,
                        itemsAfter = result.itemsAfter,
                    )
                }

                is LoadResult.Error -> LoadResult.Error(result.throwable)
                is LoadResult.Invalid -> LoadResult.Invalid()
            }
        }

        override fun getRefreshKey(state: PagingState<Int, EntryWithDetails>): Int? {
            val anchorPosition = state.anchorPosition ?: return null
            val initialLoadSize = state.config.initialLoadSize
            return maxOf(0, anchorPosition - (initialLoadSize / 2))
        }
    }

    private companion object {
        const val DEFAULT_PAGE_SIZE = 30
        const val JUMP_THRESHOLD = 60
        const val MAX_PAGING_SIZE = 300
    }
}
