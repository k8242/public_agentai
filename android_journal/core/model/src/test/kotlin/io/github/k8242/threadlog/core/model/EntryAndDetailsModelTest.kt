// Copyright (c) 2026 k8242
// SPDX-License-Identifier: MIT

package io.github.k8242.threadlog.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EntryAndDetailsModelTest {

    private fun sampleEntry(
        createdAtMs: Long = 1_700_000_000_000L,
        updatedAtMs: Long? = null,
        deletedAtMs: Long? = null,
    ): Entry = Entry(
        id = "01934b10-0000-7000-8000-000000000001",
        createdAtMs = createdAtMs,
        zoneId = "America/Los_Angeles",
        localDate = "2026-11-19",
        isoWeek = "2026-W47",
        bodyRaw = "Hello *world*",
        bodyPlain = "Hello world",
        replyToId = null,
        contentHash = "abc123",
        updatedAtMs = updatedAtMs,
        deletedAtMs = deletedAtMs,
    )

    private fun sampleTag(id: String, path: String, category: TagRootCategory): Tag = Tag(
        id = id,
        parentId = null,
        fullPath = path,
        leafName = path.substringAfterLast('/'),
        rootCategory = category,
        colorToken = category.key,
    )

    @Test
    fun entry_isEdited_falseWhenUpdatedAtNull() {
        val entry = sampleEntry(updatedAtMs = null)
        assertThat(entry.isEdited).isFalse()
    }

    @Test
    fun entry_isEdited_falseWhenUpdatedAtEqualsCreatedAt() {
        val created = 1_700_000_000_000L
        val entry = sampleEntry(createdAtMs = created, updatedAtMs = created)
        assertThat(entry.isEdited).isFalse()
    }

    @Test
    fun entry_isEdited_falseWhenUpdatedAtLessThanCreatedAt() {
        val created = 1_700_000_000_000L
        val entry = sampleEntry(createdAtMs = created, updatedAtMs = created - 1L)
        assertThat(entry.isEdited).isFalse()
    }

    @Test
    fun entry_isEdited_trueWhenUpdatedAtGreaterThanCreatedAt() {
        val created = 1_700_000_000_000L
        assertThat(sampleEntry(createdAtMs = created, updatedAtMs = created + 1L).isEdited).isTrue()
        assertThat(sampleEntry(createdAtMs = created, updatedAtMs = created + 60_000L).isEdited).isTrue()
    }

    @Test
    fun entry_isDeleted_reflectsDeletedAtMsNullability() {
        assertThat(sampleEntry(deletedAtMs = null).isDeleted).isFalse()
        assertThat(sampleEntry(deletedAtMs = 0L).isDeleted).isTrue()
        assertThat(sampleEntry(deletedAtMs = 1_700_000_000_000L).isDeleted).isTrue()
    }

    @Test
    fun entryWithDetails_partitionsTagsCompletelyAndDisjointly() {
        val entry = sampleEntry()
        val userTag = EntryTag(
            entryId = entry.id,
            tagId = "t1",
            tag = sampleTag("t1", "people/friends/Lex", TagRootCategory.PEOPLE),
            state = TagState.USER,
            updatedAtMs = 100L,
        )
        val hiddenTag = EntryTag(
            entryId = entry.id,
            tagId = "t2",
            tag = sampleTag("t2", "auto/mood/energized", TagRootCategory.AUTO),
            state = TagState.AUTO_HIDDEN,
            confidence = 0.85f,
            updatedAtMs = 200L,
        )
        val promotedTag = EntryTag(
            entryId = entry.id,
            tagId = "t3",
            tag = sampleTag("t3", "projects/journal-app", TagRootCategory.PROJECTS),
            state = TagState.AUTO_PROMOTED,
            confidence = 0.9f,
            updatedAtMs = 300L,
        )
        val rejectedTag = EntryTag(
            entryId = entry.id,
            tagId = "t4",
            tag = sampleTag("t4", "topics/noise", TagRootCategory.TOPICS),
            state = TagState.AUTO_REJECTED,
            confidence = 0.7f,
            updatedAtMs = 400L,
        )

        val details = EntryWithDetails(
            entry = entry,
            tags = listOf(userTag, hiddenTag, promotedTag, rejectedTag),
            bookmarks = emptyList(),
            attachments = emptyList(),
        )

        assertThat(details.visibleTags).containsExactly(userTag, promotedTag).inOrder()
        assertThat(details.hiddenAutoTags).containsExactly(hiddenTag)
        assertThat(details.rejectedTags).containsExactly(rejectedTag)

        val visibleIds = details.visibleTags.map { it.tagId }.toSet()
        val hiddenIds = details.hiddenAutoTags.map { it.tagId }.toSet()
        val rejectedIds = details.rejectedTags.map { it.tagId }.toSet()
        assertThat(visibleIds.intersect(hiddenIds)).isEmpty()
        assertThat(visibleIds.intersect(rejectedIds)).isEmpty()
        assertThat(hiddenIds.intersect(rejectedIds)).isEmpty()
        assertThat(details.visibleTags.size + details.hiddenAutoTags.size + details.rejectedTags.size)
            .isEqualTo(details.tags.size)
    }

    @Test
    fun entryWithDetails_emptyAndSingleStateTagPartitions() {
        val entry = sampleEntry()
        val emptyDetails = EntryWithDetails(
            entry = entry,
            tags = emptyList(),
            bookmarks = emptyList(),
            attachments = emptyList(),
        )
        assertThat(emptyDetails.visibleTags).isEmpty()
        assertThat(emptyDetails.hiddenAutoTags).isEmpty()
        assertThat(emptyDetails.rejectedTags).isEmpty()

        val hiddenOnly = EntryWithDetails(
            entry = entry,
            tags = listOf(
                EntryTag(
                    entryId = entry.id,
                    tagId = "t-auto",
                    tag = sampleTag("t-auto", "auto/work", TagRootCategory.AUTO),
                    state = TagState.AUTO_HIDDEN,
                    confidence = 0.8f,
                    updatedAtMs = 100L,
                ),
            ),
            bookmarks = emptyList(),
            attachments = emptyList(),
        )
        assertThat(hiddenOnly.visibleTags).isEmpty()
        assertThat(hiddenOnly.hiddenAutoTags).hasSize(1)
        assertThat(hiddenOnly.rejectedTags).isEmpty()

        val rejectedOnly = EntryWithDetails(
            entry = entry,
            tags = listOf(
                EntryTag(
                    entryId = entry.id,
                    tagId = "t-rej",
                    tag = sampleTag("t-rej", "topics/spam", TagRootCategory.TOPICS),
                    state = TagState.AUTO_REJECTED,
                    confidence = 0.6f,
                    updatedAtMs = 100L,
                ),
            ),
            bookmarks = emptyList(),
            attachments = emptyList(),
        )
        assertThat(rejectedOnly.visibleTags).isEmpty()
        assertThat(rejectedOnly.hiddenAutoTags).isEmpty()
        assertThat(rejectedOnly.rejectedTags).hasSize(1)
    }

    @Test
    fun entryWithDetails_defaultsAndReplyPreview() {
        val entry = sampleEntry()
        val defaultDetails = EntryWithDetails(
            entry = entry,
            tags = emptyList(),
            bookmarks = emptyList(),
            attachments = emptyList(),
        )
        assertThat(defaultDetails.replyPreview).isNull()

        val activePreview = ReplyPreview(
            entryId = "parent-1",
            snippet = "Original message",
            createdAtMs = 1_699_999_000_000L,
            zoneId = "America/Los_Angeles",
            isDeleted = false,
        )
        val deletedPreview = activePreview.copy(isDeleted = true)

        assertThat(defaultDetails.copy(replyPreview = activePreview).replyPreview?.isDeleted).isFalse()
        assertThat(defaultDetails.copy(replyPreview = deletedPreview).replyPreview?.isDeleted).isTrue()
    }
}
