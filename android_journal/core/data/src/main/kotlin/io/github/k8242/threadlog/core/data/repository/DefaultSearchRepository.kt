// Copyright (c) 2026 k8242
// SPDX-License-Identifier: MIT

package io.github.k8242.threadlog.core.data.repository

import io.github.k8242.threadlog.core.data.local.ThreadLogDatabase
import io.github.k8242.threadlog.core.data.local.dao.EntryDao
import io.github.k8242.threadlog.core.data.local.dao.TagDao
import io.github.k8242.threadlog.core.data.local.fts.FtsIndexManager
import io.github.k8242.threadlog.core.data.local.fts.FtsQueryBuilder
import io.github.k8242.threadlog.core.data.local.inReadTransaction
import io.github.k8242.threadlog.core.data.mapper.toDomain
import io.github.k8242.threadlog.core.model.HybridSearchResult
import io.github.k8242.threadlog.core.model.SearchHit
import io.github.k8242.threadlog.core.model.TagState
import io.github.k8242.threadlog.core.model.contract.SearchRepository
import io.github.k8242.threadlog.core.parser.HiddenPlaceholderMatcher
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultSearchRepository @Inject constructor(
    private val database: ThreadLogDatabase,
    private val entryDao: EntryDao,
    private val tagDao: TagDao,
    private val ftsIndexManager: FtsIndexManager,
) : SearchRepository {

    override suspend fun search(
        query: String,
        includeHiddenAutoTags: Boolean,
        nowMs: Long,
    ): HybridSearchResult = search(
        query = query,
        includeHiddenAutoTags = includeHiddenAutoTags,
        includeArchived = false,
        nowMs = nowMs,
    )

    override suspend fun search(
        query: String,
        includeHiddenAutoTags: Boolean,
        includeArchived: Boolean,
        nowMs: Long,
    ): HybridSearchResult {
        val cutoffEpochMs = nowMs - HybridSearchResult.TIER_A_WINDOW_MS
        val queryTokens = FtsQueryBuilder.extractTokens(query)
        if (query.isBlank() || queryTokens.isEmpty()) {
            return HybridSearchResult(
                query = query,
                includeHiddenAutoTags = includeHiddenAutoTags,
                cutoffEpochMs = cutoffEpochMs,
                tierARecent = emptyList(),
                tierBOlder = emptyList(),
            )
        }

        return database.inReadTransaction {
            val rawMatches = ftsIndexManager.search(
                queryTokens = queryTokens,
                includeHiddenAutoTags = includeHiddenAutoTags,
            )
            if (rawMatches.isEmpty()) {
                return@inReadTransaction HybridSearchResult(
                    query = query,
                    includeHiddenAutoTags = includeHiddenAutoTags,
                    cutoffEpochMs = cutoffEpochMs,
                    tierARecent = emptyList(),
                    tierBOlder = emptyList(),
                )
            }

            val snippetByEntryId = rawMatches.associate { it.entryId to it.snippetMarked }
            val matchedIds = rawMatches.map { it.entryId }.distinct()

            val nonDeletedEntries = matchedIds
                .chunked(SQLITE_BIND_CHUNK_SIZE)
                .flatMap { entryDao.getByIds(it) }
                .filter { entry ->
                    entry.deletedAtMs == null && (includeArchived || entry.archivedAtMs == null)
                }

            val placeholderCandidates = nonDeletedEntries
                .filter { HiddenPlaceholderMatcher.isHiddenPlaceholder(it.bodyPlain) }
                .map { it.id }
            val attachedPlaceholderIds = if (placeholderCandidates.isEmpty()) {
                emptySet()
            } else {
                placeholderCandidates
                    .chunked(SQLITE_BIND_CHUNK_SIZE)
                    .flatMap { entryDao.getEntryIdsWithAttachments(it) }
                    .toHashSet()
            }

            val activeEntries = nonDeletedEntries.filter { entry ->
                !HiddenPlaceholderMatcher.isHiddenPlaceholder(entry.bodyPlain) ||
                    entry.id in attachedPlaceholderIds
            }

            if (activeEntries.isEmpty()) {
                return@inReadTransaction HybridSearchResult(
                    query = query,
                    includeHiddenAutoTags = includeHiddenAutoTags,
                    cutoffEpochMs = cutoffEpochMs,
                    tierARecent = emptyList(),
                    tierBOlder = emptyList(),
                )
            }

            val activeIds = activeEntries.map { it.id }
            val entryTagsByEntryId = activeIds
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

            val hits = activeEntries.map { entry ->
                val links = entryTagsByEntryId[entry.id].orEmpty()

                val visibleTags = links
                    .filter { it.state == TagState.USER.name || it.state == TagState.AUTO_PROMOTED.name }
                    .mapNotNull { tagsById[it.tagId] }
                    .sortedBy { it.fullPath }

                val hiddenMatchedTags = if (includeHiddenAutoTags) {
                    links
                        .filter { it.state == TagState.AUTO_HIDDEN.name }
                        .mapNotNull { tagsById[it.tagId] }
                        .filter { tag -> FtsQueryBuilder.tagMatchesQueryTokens(tag, queryTokens) }
                        .sortedBy { it.fullPath }
                } else {
                    emptyList()
                }

                val ftsSnippet = snippetByEntryId[entry.id].orEmpty()
                val snippetMarked = if (ftsSnippet.isNotBlank()) {
                    ftsSnippet
                } else {
                    entry.bodyPlain.take(FALLBACK_SNIPPET_MAX_CHARS)
                }

                SearchHit(
                    entryId = entry.id,
                    createdAtMs = entry.createdAtMs,
                    zoneId = entry.zoneId,
                    localDate = entry.localDate,
                    snippetMarked = snippetMarked,
                    visibleTags = visibleTags,
                    hiddenMatchedTags = hiddenMatchedTags,
                    matchedViaHiddenAutoTag = hiddenMatchedTags.isNotEmpty(),
                )
            }

            val sortedHits = hits.sortedWith(
                compareByDescending<SearchHit> { it.createdAtMs }
                    .thenByDescending { it.entryId },
            )

            val (tierA, tierB) = sortedHits.partition { it.createdAtMs >= cutoffEpochMs }

            HybridSearchResult(
                query = query,
                includeHiddenAutoTags = includeHiddenAutoTags,
                cutoffEpochMs = cutoffEpochMs,
                tierARecent = tierA,
                tierBOlder = tierB,
            )
        }
    }

    private companion object {
        const val SQLITE_BIND_CHUNK_SIZE = 500
        const val FALLBACK_SNIPPET_MAX_CHARS = 140
    }
}
