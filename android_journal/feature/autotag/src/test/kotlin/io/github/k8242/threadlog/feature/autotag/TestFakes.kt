// Copyright (c) 2026 k8242
// SPDX-License-Identifier: MIT

package io.github.k8242.threadlog.feature.autotag

import androidx.paging.PagingData
import io.github.k8242.threadlog.core.model.AutoTagRule
import io.github.k8242.threadlog.core.model.AutoTagSuggestion
import io.github.k8242.threadlog.core.model.Bookmark
import io.github.k8242.threadlog.core.model.CalendarDayCount
import io.github.k8242.threadlog.core.model.CompactEntrySummary
import io.github.k8242.threadlog.core.model.Entry
import io.github.k8242.threadlog.core.model.EntryTag
import io.github.k8242.threadlog.core.model.EntryWithDetails
import io.github.k8242.threadlog.core.model.Tag
import io.github.k8242.threadlog.core.model.TagRootCategory
import io.github.k8242.threadlog.core.model.TagState
import io.github.k8242.threadlog.core.model.contract.AutoTagProvider
import io.github.k8242.threadlog.core.model.contract.AutoTagRuleRepository
import io.github.k8242.threadlog.core.model.contract.EntryRepository
import io.github.k8242.threadlog.core.model.contract.LeafDisambiguator
import io.github.k8242.threadlog.core.model.contract.PendingAttachment
import io.github.k8242.threadlog.core.model.contract.TagRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map

internal fun testEntry(
    id: String = "entry-1",
    bodyPlain: String = "Sample journal entry",
    bodyRaw: String = bodyPlain,
    createdAtMs: Long = 1_763_654_400_000L,
    zoneId: String = "UTC",
    localDate: String = "2026-11-20",
    isoWeek: String = "2026-W47",
    deletedAtMs: Long? = null,
): Entry = Entry(
    id = id,
    createdAtMs = createdAtMs,
    zoneId = zoneId,
    localDate = localDate,
    isoWeek = isoWeek,
    bodyRaw = bodyRaw,
    bodyPlain = bodyPlain,
    contentHash = "hash-$id",
    deletedAtMs = deletedAtMs,
)

internal fun testTag(
    id: String,
    fullPath: String,
    aliases: List<String> = emptyList(),
    rootCategory: TagRootCategory = TagRootCategory.fromPath(fullPath),
): Tag {
    val normalized = fullPath.trim().trim('/')
    val leaf = normalized.substringAfterLast('/')
    return Tag(
        id = id,
        parentId = null,
        fullPath = normalized,
        leafName = leaf,
        rootCategory = rootCategory,
        colorToken = rootCategory.key,
        aliases = aliases,
    )
}

internal fun testEntryTag(
    entryId: String,
    tag: Tag,
    state: TagState,
    confidence: Float = 0.85f,
    updatedAtMs: Long = 1_763_654_400_000L,
): EntryTag = EntryTag(
    entryId = entryId,
    tagId = tag.id,
    tag = tag,
    state = state,
    confidence = confidence,
    updatedAtMs = updatedAtMs,
)

internal fun testBookmark(
    id: String = "bm-1",
    entryId: String = "entry-1",
    urlRaw: String = "https://github.com/threadlog/app",
    domain: String = "github.com",
    title: String? = null,
    note: String? = null,
): Bookmark = Bookmark(
    id = id,
    entryId = entryId,
    urlRaw = urlRaw,
    domain = domain,
    title = title,
    note = note,
)

internal fun testEntryWithDetails(
    entry: Entry = testEntry(),
    tags: List<EntryTag> = emptyList(),
    bookmarks: List<Bookmark> = emptyList(),
): EntryWithDetails = EntryWithDetails(
    entry = entry,
    tags = tags,
    bookmarks = bookmarks,
    attachments = emptyList(),
    replyPreview = null,
)

internal class FakeEntryRepository(
    initialEntries: List<EntryWithDetails> = emptyList(),
) : EntryRepository {

    val entriesFlow = MutableStateFlow(initialEntries)
    var throwOnGetEntry: Throwable? = null

    fun setEntries(entries: List<EntryWithDetails>) {
        entriesFlow.value = entries
    }

    override suspend fun getEntry(entryId: String): EntryWithDetails? {
        throwOnGetEntry?.let { throw it }
        return entriesFlow.value.firstOrNull { it.entry.id == entryId }
    }

    override fun observeEntry(entryId: String): Flow<EntryWithDetails?> =
        entriesFlow.map { list -> list.firstOrNull { it.entry.id == entryId } }

    override fun observeEntriesWithPendingAutoTags(): Flow<List<EntryWithDetails>> =
        entriesFlow

    override fun observePagedEntries(): Flow<PagingData<EntryWithDetails>> = emptyFlow()

    override suspend fun createEntry(
        bodyRaw: String,
        userTagIds: List<String>,
        replyToId: String?,
        pendingAttachments: List<PendingAttachment>,
        nowMs: Long,
        zoneId: String?,
        taskStatus: io.github.k8242.threadlog.core.model.TaskStatus,
    ): EntryWithDetails = throw UnsupportedOperationException()

    override suspend fun editEntry(
        entryId: String,
        newBodyRaw: String,
        nowMs: Long,
    ): EntryWithDetails = throw UnsupportedOperationException()

    override suspend fun softDeleteEntry(entryId: String, nowMs: Long) =
        throw UnsupportedOperationException()

    override suspend fun undoSoftDelete(entryId: String) =
        throw UnsupportedOperationException()

    override fun observeCompactSummaries(): Flow<List<CompactEntrySummary>> = emptyFlow()

    override fun observeCalendarDayCounts(
        yearMonth: String,
        filterTagPathPrefix: String?,
    ): Flow<List<CalendarDayCount>> = emptyFlow()

    override fun observeEntriesForDate(
        localDate: String,
        filterTagPathPrefix: String?,
    ): Flow<List<EntryWithDetails>> = emptyFlow()

    override fun observeContextWindow(startMs: Long, endMs: Long): Flow<List<EntryWithDetails>> =
        emptyFlow()
}

internal class FakeTagRepository(
    initialTags: List<Tag> = emptyList(),
    private val entryRepository: FakeEntryRepository? = null,
) : TagRepository {

    val tagsFlow = MutableStateFlow(initialTags)
    val appliedAutoTags = mutableListOf<Pair<String, List<Pair<String, Float>>>>()
    val promotedCalls = mutableListOf<Pair<String, String>>()
    val rejectedCalls = mutableListOf<Pair<String, String>>()
    var throwOnApply: Throwable? = null
    var throwOnPromote: Throwable? = null
    var throwOnReject: Throwable? = null

    override fun observeAllTags(): Flow<List<Tag>> = tagsFlow

    override suspend fun getAllTags(): List<Tag> = tagsFlow.value

    override suspend fun applyAutoTags(
        entryId: String,
        suggestions: List<Pair<String, Float>>,
        nowMs: Long,
    ) {
        throwOnApply?.let { throw it }
        appliedAutoTags += entryId to suggestions
    }

    override suspend fun promoteAutoTag(entryId: String, tagId: String, nowMs: Long) {
        throwOnPromote?.let { throw it }
        promotedCalls += entryId to tagId
        updateEntryTagState(entryId, tagId, TagState.AUTO_PROMOTED)
    }

    override suspend fun rejectAutoTag(entryId: String, tagId: String, nowMs: Long) {
        throwOnReject?.let { throw it }
        rejectedCalls += entryId to tagId
        updateEntryTagState(entryId, tagId, TagState.AUTO_REJECTED)
    }

    private fun updateEntryTagState(entryId: String, tagId: String, newState: TagState) {
        val repo = entryRepository ?: return
        repo.entriesFlow.value = repo.entriesFlow.value.map { details ->
            if (details.entry.id != entryId) {
                details
            } else {
                details.copy(
                    tags = details.tags.map { et ->
                        if (et.tagId == tagId) et.copy(state = newState) else et
                    },
                )
            }
        }
    }

    override suspend fun createOrGetTagByPath(fullPath: String, aliases: List<String>): Tag =
        throw UnsupportedOperationException()

    override suspend fun renameOrMoveTag(tagId: String, newFullPath: String) =
        throw UnsupportedOperationException()

    override suspend fun updateAliases(tagId: String, aliases: List<String>) =
        throw UnsupportedOperationException()

    override suspend fun deleteTag(tagId: String) =
        throw UnsupportedOperationException()

    override suspend fun setUserTag(entryId: String, tagId: String, attached: Boolean, nowMs: Long) =
        throw UnsupportedOperationException()
}

internal class FakeAutoTagRuleRepository(
    initialRules: List<AutoTagRule> = emptyList(),
) : AutoTagRuleRepository {

    val rulesFlow = MutableStateFlow(initialRules)

    override fun observeRules(): Flow<List<AutoTagRule>> = rulesFlow

    override suspend fun getEnabledRules(): List<AutoTagRule> =
        rulesFlow.value.filter { it.enabled }

    override suspend fun upsertRule(rule: AutoTagRule) {
        rulesFlow.value = rulesFlow.value.filterNot { it.id == rule.id } + rule
    }

    override suspend fun deleteRule(ruleId: String) {
        rulesFlow.value = rulesFlow.value.filterNot { it.id == ruleId }
    }
}

internal class FakeAutoTagProvider(
    private val provider: suspend (Entry, List<Bookmark>, List<Tag>, List<AutoTagRule>) -> List<AutoTagSuggestion>,
) : AutoTagProvider {
    override val providerId: String = "fake_provider"

    override suspend fun suggestTags(
        entry: Entry,
        bookmarks: List<Bookmark>,
        allTags: List<Tag>,
        rules: List<AutoTagRule>,
    ): List<AutoTagSuggestion> = provider(entry, bookmarks, allTags, rules)
}

internal class FakeLeafDisambiguator : LeafDisambiguator {
    override fun computeDisplayLabels(allActiveTags: List<Tag>): Map<String, String> {
        val byLeaf = allActiveTags.groupBy { it.leafName.lowercase() }
        return allActiveTags.associate { tag ->
            val collisions = byLeaf[tag.leafName.lowercase()].orEmpty()
            val label = if (collisions.size <= 1) {
                tag.leafName
            } else {
                val parent = tag.fullPath.substringBeforeLast('/', missingDelimiterValue = "")
                val context = parent.substringAfterLast('/', missingDelimiterValue = parent)
                if (context.isNotEmpty()) "${tag.leafName} ($context)" else tag.leafName
            }
            tag.id to label
        }
    }
}
