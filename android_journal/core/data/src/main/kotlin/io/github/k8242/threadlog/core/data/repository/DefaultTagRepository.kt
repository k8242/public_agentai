package io.github.k8242.threadlog.core.data.repository

import io.github.k8242.threadlog.core.data.local.ThreadLogDatabase
import io.github.k8242.threadlog.core.data.local.dao.EntryDao
import io.github.k8242.threadlog.core.data.local.dao.TagDao
import io.github.k8242.threadlog.core.data.local.entity.EntryTagEntity
import io.github.k8242.threadlog.core.data.local.fts.FtsIndexManager
import io.github.k8242.threadlog.core.data.local.inWriteTransaction
import io.github.k8242.threadlog.core.data.mapper.AliasesJsonCodec
import io.github.k8242.threadlog.core.data.mapper.TagPathNormalizer
import io.github.k8242.threadlog.core.data.mapper.toDomain
import io.github.k8242.threadlog.core.model.Tag
import io.github.k8242.threadlog.core.model.TagRootCategory
import io.github.k8242.threadlog.core.model.TagState
import io.github.k8242.threadlog.core.model.contract.TagRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Singleton
class DefaultTagRepository @Inject constructor(
    private val database: ThreadLogDatabase,
    private val entryDao: EntryDao,
    private val tagDao: TagDao,
    private val ftsIndexManager: FtsIndexManager,
    private val hydrator: EntryHydrator,
) : TagRepository {

    override fun observeAllTags(): Flow<List<Tag>> =
        tagDao.observeAll().map { entities -> entities.map { it.toDomain() } }

    override suspend fun getAllTags(): List<Tag> =
        tagDao.getAll().map { it.toDomain() }

    override suspend fun createOrGetTagByPath(fullPath: String, aliases: List<String>): Tag =
        database.inWriteTransaction {
            val (leafEntity, aliasesChanged) = hydrator.ensureTagChain(
                rawFullPath = fullPath,
                leafAliases = aliases,
            )
            if (aliasesChanged) {
                val affectedEntryIds = tagDao.getEntryIdsForTag(leafEntity.id)
                ftsIndexManager.reindexEntries(affectedEntryIds)
            }
            leafEntity.toDomain()
        }

    override suspend fun renameOrMoveTag(tagId: String, newFullPath: String) {
        val normalizedNewPath = TagPathNormalizer.normalize(newFullPath)
        require(normalizedNewPath.isNotEmpty()) { "New tag path must not be blank" }

        database.inWriteTransaction {
            val target = tagDao.getById(tagId)
                ?: throw NoSuchElementException("Tag not found: $tagId")
            if (target.fullPath == normalizedNewPath) return@inWriteTransaction

            require(!normalizedNewPath.startsWith("${target.fullPath}/")) {
                "Cannot move tag '${target.fullPath}' under its own descendant '$normalizedNewPath'"
            }

            val collision = tagDao.getByFullPath(normalizedNewPath)
            require(collision == null || collision.id == tagId) {
                "Tag already exists at path '$normalizedNewPath'"
            }

            val newParentPath = normalizedNewPath.substringBeforeLast('/', missingDelimiterValue = "")
            val newParentId = if (newParentPath.isNotEmpty()) {
                hydrator.ensureTagChain(newParentPath).first.id
            } else {
                null
            }

            val descendants = tagDao.getDescendants(
                "${TagPathNormalizer.escapeLike(target.fullPath)}/%",
            )

            val updatedTargetCategory = TagRootCategory.fromPath(normalizedNewPath)
            val updatedTarget = target.copy(
                parentId = newParentId,
                fullPath = normalizedNewPath,
                leafName = normalizedNewPath.substringAfterLast('/'),
                rootCategory = updatedTargetCategory.name,
                colorToken = updatedTargetCategory.key,
            )

            val updatedDescendants = descendants.map { child ->
                val suffix = child.fullPath.removePrefix("${target.fullPath}/")
                val childNewPath = "$normalizedNewPath/$suffix"
                val childCollision = tagDao.getByFullPath(childNewPath)
                require(childCollision == null || childCollision.id == child.id) {
                    "Descendant tag collision at path '$childNewPath'"
                }
                val category = TagRootCategory.fromPath(childNewPath)
                child.copy(
                    fullPath = childNewPath,
                    leafName = childNewPath.substringAfterLast('/'),
                    rootCategory = category.name,
                    colorToken = category.key,
                )
            }

            tagDao.upsert(updatedTarget)
            if (updatedDescendants.isNotEmpty()) {
                tagDao.upsertAll(updatedDescendants)
            }

            val affectedTagIds = listOf(target.id) + descendants.map { it.id }
            val affectedEntryIds = tagDao.getEntryIdsForTags(affectedTagIds)
            for (entryId in affectedEntryIds) {
                hydrator.recomputeAndPersistContentHash(entryId)
            }
            ftsIndexManager.reindexEntries(affectedEntryIds)
        }
    }

    override suspend fun updateAliases(tagId: String, aliases: List<String>) {
        database.inWriteTransaction {
            val existing = tagDao.getById(tagId)
                ?: throw NoSuchElementException("Tag not found: $tagId")
            val updated = existing.copy(aliasesJson = AliasesJsonCodec.encode(aliases))
            tagDao.upsert(updated)

            val affectedEntryIds = tagDao.getEntryIdsForTag(tagId)
            ftsIndexManager.reindexEntries(affectedEntryIds)
        }
    }

    override suspend fun deleteTag(tagId: String) {
        database.inWriteTransaction {
            val target = tagDao.getById(tagId) ?: return@inWriteTransaction
            val descendants = tagDao.getDescendants(
                "${TagPathNormalizer.escapeLike(target.fullPath)}/%",
            )
            val affectedTagIds = listOf(target.id) + descendants.map { it.id }
            val affectedEntryIds = tagDao.getEntryIdsForTags(affectedTagIds).distinct()

            // Delete descendants deepest-first, then target; FK CASCADE cleans up entry_tags.
            for (child in descendants.sortedByDescending { it.fullPath.length }) {
                tagDao.deleteById(child.id)
            }
            tagDao.deleteById(target.id)

            for (entryId in affectedEntryIds) {
                hydrator.recomputeAndPersistContentHash(entryId)
            }
            ftsIndexManager.reindexEntries(affectedEntryIds)
        }
    }

    override suspend fun setUserTag(
        entryId: String,
        tagId: String,
        attached: Boolean,
        nowMs: Long,
    ) {
        database.inWriteTransaction {
            if (attached) {
                tagDao.upsertEntryTag(
                    EntryTagEntity(
                        entryId = entryId,
                        tagId = tagId,
                        state = TagState.USER.name,
                        confidence = 1.0f,
                        updatedAtMs = nowMs,
                    ),
                )
            } else {
                tagDao.deleteEntryTag(entryId = entryId, tagId = tagId)
            }
            hydrator.recomputeAndPersistContentHash(entryId)
            ftsIndexManager.reindexEntry(entryId)
        }
    }

    override suspend fun applyAutoTags(
        entryId: String,
        suggestions: List<Pair<String, Float>>,
        nowMs: Long,
    ) {
        if (suggestions.isEmpty()) return

        database.inWriteTransaction {
            entryDao.getById(entryId) ?: return@inWriteTransaction

            var changed = false
            for ((rawPath, confidence) in suggestions) {
                if (rawPath.isBlank()) continue
                val (tagEntity, _) = hydrator.ensureTagChain(rawPath)
                val existingLink = tagDao.getEntryTag(entryId, tagEntity.id)

                // Tombstone & user-decision protection: never overwrite USER, AUTO_PROMOTED,
                // or AUTO_REJECTED states.
                if (existingLink != null && existingLink.state in PROTECTED_TAG_STATES) {
                    continue
                }

                tagDao.upsertEntryTag(
                    EntryTagEntity(
                        entryId = entryId,
                        tagId = tagEntity.id,
                        state = TagState.AUTO_HIDDEN.name,
                        confidence = confidence.coerceIn(0f, 1f),
                        updatedAtMs = nowMs,
                    ),
                )
                changed = true
            }

            if (changed) {
                hydrator.recomputeAndPersistContentHash(entryId)
                ftsIndexManager.reindexEntry(entryId)
            }
        }
    }

    override suspend fun promoteAutoTag(entryId: String, tagId: String, nowMs: Long) {
        database.inWriteTransaction {
            val existing = tagDao.getEntryTag(entryId, tagId)
            tagDao.upsertEntryTag(
                EntryTagEntity(
                    entryId = entryId,
                    tagId = tagId,
                    state = TagState.AUTO_PROMOTED.name,
                    confidence = existing?.confidence ?: 1.0f,
                    updatedAtMs = nowMs,
                ),
            )
            hydrator.recomputeAndPersistContentHash(entryId)
            ftsIndexManager.reindexEntry(entryId)
        }
    }

    override suspend fun rejectAutoTag(entryId: String, tagId: String, nowMs: Long) {
        database.inWriteTransaction {
            val existing = tagDao.getEntryTag(entryId, tagId)
            tagDao.upsertEntryTag(
                EntryTagEntity(
                    entryId = entryId,
                    tagId = tagId,
                    state = TagState.AUTO_REJECTED.name,
                    confidence = existing?.confidence ?: 0f,
                    updatedAtMs = nowMs,
                ),
            )
            hydrator.recomputeAndPersistContentHash(entryId)
            ftsIndexManager.reindexEntry(entryId)
        }
    }

    private companion object {
        val PROTECTED_TAG_STATES: Set<String> = setOf(
            TagState.USER.name,
            TagState.AUTO_PROMOTED.name,
            TagState.AUTO_REJECTED.name,
        )
    }
}
