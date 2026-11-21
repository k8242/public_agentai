package io.github.k8242.threadlog.core.data.repository

import io.github.k8242.threadlog.core.data.local.dao.AttachmentDao
import io.github.k8242.threadlog.core.data.local.dao.BookmarkDao
import io.github.k8242.threadlog.core.data.local.dao.EntryDao
import io.github.k8242.threadlog.core.data.local.dao.TagDao
import io.github.k8242.threadlog.core.data.local.entity.EntryEntity
import io.github.k8242.threadlog.core.data.local.entity.TagEntity
import io.github.k8242.threadlog.core.data.mapper.AliasesJsonCodec
import io.github.k8242.threadlog.core.data.mapper.EntryContentHasher
import io.github.k8242.threadlog.core.data.mapper.TagPathNormalizer
import io.github.k8242.threadlog.core.data.mapper.toDomain
import io.github.k8242.threadlog.core.model.EntryWithDetails
import io.github.k8242.threadlog.core.model.ReplyPreview
import io.github.k8242.threadlog.core.model.TagRootCategory
import io.github.k8242.threadlog.core.model.TagState
import io.github.k8242.threadlog.core.parser.UuidV7
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shared batch hydrator and helper operations used across repositories and backup gateway
 * to avoid N+1 queries and keep `EntryWithDetails`, `contentHash`, and tag hierarchies consistent.
 */
@Singleton
class EntryHydrator @Inject constructor(
    private val entryDao: EntryDao,
    private val tagDao: TagDao,
    private val bookmarkDao: BookmarkDao,
    private val attachmentDao: AttachmentDao,
) {

    suspend fun hydrateEntry(entity: EntryEntity): EntryWithDetails =
        hydrateEntries(listOf(entity)).first()

    suspend fun hydrateEntries(entities: List<EntryEntity>): List<EntryWithDetails> {
        if (entities.isEmpty()) return emptyList()

        val entryIds = entities.map { it.id }

        val entryTagsByEntryId = entryIds
            .chunked(SQLITE_BIND_CHUNK_SIZE)
            .flatMap { tagDao.getEntryTagsForEntries(it) }
            .groupBy { it.entryId }

        val allTagIds = entryTagsByEntryId.values
            .flatten()
            .map { it.tagId }
            .distinct()
        val tagsById = allTagIds
            .chunked(SQLITE_BIND_CHUNK_SIZE)
            .flatMap { tagDao.getByIds(it) }
            .associate { it.id to it.toDomain() }

        val bookmarksByEntryId = entryIds
            .chunked(SQLITE_BIND_CHUNK_SIZE)
            .flatMap { bookmarkDao.getForEntries(it) }
            .groupBy { it.entryId }

        val attachmentsByEntryId = entryIds
            .chunked(SQLITE_BIND_CHUNK_SIZE)
            .flatMap { attachmentDao.getForEntries(it) }
            .groupBy { it.entryId }

        val replyToIds = entities.mapNotNull { it.replyToId }.distinct()
        val replyParentsById = replyToIds
            .chunked(SQLITE_BIND_CHUNK_SIZE)
            .flatMap { entryDao.getByIds(it) }
            .associateBy { it.id }

        return entities.map { entity ->
            val hydratedTags = entryTagsByEntryId[entity.id]
                .orEmpty()
                .mapNotNull { link ->
                    val tag = tagsById[link.tagId] ?: return@mapNotNull null
                    link.toDomain(tag)
                }
                .sortedBy { it.tag.fullPath }

            val hydratedBookmarks = bookmarksByEntryId[entity.id]
                .orEmpty()
                .map { it.toDomain() }

            val hydratedAttachments = attachmentsByEntryId[entity.id]
                .orEmpty()
                .map { it.toDomain() }

            val replyPreview = entity.replyToId?.let { parentId ->
                val parent = replyParentsById[parentId]
                if (parent != null) {
                    val isDeleted = parent.deletedAtMs != null
                    ReplyPreview(
                        entryId = parent.id,
                        snippet = if (isDeleted) "" else parent.bodyPlain.take(PREVIEW_MAX_CHARS),
                        createdAtMs = parent.createdAtMs,
                        zoneId = parent.zoneId,
                        isDeleted = isDeleted,
                    )
                } else {
                    ReplyPreview(
                        entryId = parentId,
                        snippet = "",
                        createdAtMs = 0L,
                        zoneId = entity.zoneId,
                        isDeleted = true,
                    )
                }
            }

            EntryWithDetails(
                entry = entity.toDomain(),
                tags = hydratedTags,
                bookmarks = hydratedBookmarks,
                attachments = hydratedAttachments,
                replyPreview = replyPreview,
            )
        }
    }

    /**
     * Recomputes the canonical SHA-256 `contentHash` of [entryId] from its persisted state
     * (body, reply target, tombstone flag, tags, bookmarks, attachments) and updates `entries`.
     */
    suspend fun recomputeAndPersistContentHash(entryId: String): EntryEntity? {
        val entry = entryDao.getById(entryId) ?: return null
        val entryTags = tagDao.getEntryTagsForEntry(entryId)
        val tagIds = entryTags.map { it.tagId }.distinct()
        val tagsById = if (tagIds.isEmpty()) {
            emptyMap()
        } else {
            tagDao.getByIds(tagIds).associateBy { it.id }
        }

        val visiblePaths = mutableListOf<String>()
        val hiddenPaths = mutableListOf<String>()
        val rejectedPaths = mutableListOf<String>()
        for (link in entryTags) {
            val path = tagsById[link.tagId]?.fullPath ?: continue
            when (runCatching { TagState.valueOf(link.state) }.getOrNull()) {
                TagState.USER, TagState.AUTO_PROMOTED -> visiblePaths += path
                TagState.AUTO_HIDDEN -> hiddenPaths += path
                TagState.AUTO_REJECTED -> rejectedPaths += path
                null -> Unit
            }
        }

        val bookmarks = bookmarkDao.getForEntry(entryId)
        val bookmarkSignatures = bookmarks.map { "${it.urlRaw}|${it.title.orEmpty()}" }
        val attachmentSha256s = attachmentDao.getForEntry(entryId).map { it.sha256 }

        val updatedHash = EntryContentHasher.compute(
            bodyRaw = entry.bodyRaw,
            replyToId = entry.replyToId,
            isDeleted = entry.deletedAtMs != null,
            visibleTagPaths = visiblePaths,
            hiddenTagPaths = hiddenPaths,
            rejectedTagPaths = rejectedPaths,
            bookmarkSignatures = bookmarkSignatures,
            attachmentSha256s = attachmentSha256s,
        )

        if (updatedHash == entry.contentHash) return entry
        val updated = entry.copy(contentHash = updatedHash)
        entryDao.update(updated)
        return updated
    }

    /**
     * Ensures every ancestor tag in [rawFullPath] exists with accurate `parentId`, `leafName`,
     * `rootCategory`, and `colorToken`, returning the leaf [TagEntity].
     *
     * Must be invoked inside an active write transaction when mutating multiple tables.
     */
    suspend fun ensureTagChain(
        rawFullPath: String,
        leafAliases: List<String> = emptyList(),
        preferredLeafId: String? = null,
    ): Pair<TagEntity, Boolean> {
        val normalized = TagPathNormalizer.normalize(rawFullPath)
        require(normalized.isNotEmpty()) { "Tag path must not be blank" }

        val segments = normalized.split('/')
        var parentId: String? = null
        var currentEntity: TagEntity? = null
        var aliasesChanged = false

        val pathBuilder = StringBuilder()
        for (index in segments.indices) {
            if (index > 0) pathBuilder.append('/')
            pathBuilder.append(segments[index])
            val currentPath = pathBuilder.toString()
            val isLeaf = index == segments.lastIndex

            val existing = tagDao.getByFullPath(currentPath)
                ?: tagDao.getByFullPathIgnoreCase(currentPath)

            if (existing != null) {
                var updated = existing
                if (isLeaf && leafAliases.isNotEmpty()) {
                    val existingAliases = AliasesJsonCodec.decode(existing.aliasesJson)
                    val merged = (existingAliases + leafAliases)
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .distinct()
                    if (merged != existingAliases) {
                        updated = existing.copy(aliasesJson = AliasesJsonCodec.encode(merged))
                        tagDao.upsert(updated)
                        aliasesChanged = true
                    }
                }
                currentEntity = updated
                parentId = updated.id
            } else {
                val category = TagRootCategory.fromPath(currentPath)
                val newId = if (isLeaf && !preferredLeafId.isNullOrBlank()) {
                    preferredLeafId
                } else {
                    UuidV7.generate()
                }
                val created = TagEntity(
                    id = newId,
                    parentId = parentId,
                    fullPath = currentPath,
                    leafName = segments[index],
                    rootCategory = category.name,
                    colorToken = category.key,
                    aliasesJson = if (isLeaf) AliasesJsonCodec.encode(leafAliases) else "[]",
                )
                tagDao.upsert(created)
                currentEntity = created
                parentId = created.id
            }
        }

        return requireNotNull(currentEntity) to aliasesChanged
    }

    private companion object {
        const val SQLITE_BIND_CHUNK_SIZE = 500
        const val PREVIEW_MAX_CHARS = 140
    }
}
