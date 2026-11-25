// Copyright (c) 2026 k8242
// SPDX-License-Identifier: MIT

package io.github.k8242.threadlog.core.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import io.github.k8242.threadlog.core.data.local.entity.EntryTagEntity
import io.github.k8242.threadlog.core.data.local.entity.TagEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TagDao {

    @Query("SELECT * FROM tags ORDER BY full_path ASC")
    fun observeAll(): Flow<List<TagEntity>>

    @Query(
        """
        SELECT t.* FROM tags t
        INNER JOIN entry_tags et ON et.tag_id = t.id
        WHERE et.state IN ('USER', 'AUTO_PROMOTED')
        GROUP BY t.id
        ORDER BY MAX(et.updated_at_ms) DESC, t.full_path ASC
        LIMIT :limit
        """,
    )
    fun observeRecentTags(limit: Int): Flow<List<TagEntity>>

    @Query("SELECT * FROM tags ORDER BY full_path ASC")
    suspend fun getAll(): List<TagEntity>

    @Query("SELECT * FROM tags WHERE id = :tagId LIMIT 1")
    suspend fun getById(tagId: String): TagEntity?

    @Query("SELECT * FROM tags WHERE id IN (:tagIds)")
    suspend fun getByIds(tagIds: List<String>): List<TagEntity>

    @Query("SELECT * FROM tags WHERE full_path = :fullPath LIMIT 1")
    suspend fun getByFullPath(fullPath: String): TagEntity?

    @Query("SELECT * FROM tags WHERE LOWER(full_path) = LOWER(:fullPath) LIMIT 1")
    suspend fun getByFullPathIgnoreCase(fullPath: String): TagEntity?

    @Query("SELECT * FROM tags WHERE full_path LIKE :subtreeLikePattern ESCAPE '\\' ORDER BY full_path ASC")
    suspend fun getDescendants(subtreeLikePattern: String): List<TagEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTagIgnore(tag: TagEntity): Long

    @Update
    suspend fun updateTag(tag: TagEntity)

    @Query("UPDATE tags SET color_token = :colorToken WHERE id = :tagId")
    suspend fun updateColorToken(tagId: String, colorToken: String)

    suspend fun upsert(tag: TagEntity) {
        if (insertTagIgnore(tag) == -1L) {
            updateTag(tag)
        }
    }

    suspend fun upsertAll(tags: List<TagEntity>) {
        for (tag in tags) {
            upsert(tag)
        }
    }

    @Query("DELETE FROM tags WHERE id = :tagId")
    suspend fun deleteById(tagId: String)

    @Query("SELECT * FROM entry_tags WHERE entry_id = :entryId")
    suspend fun getEntryTagsForEntry(entryId: String): List<EntryTagEntity>

    @Query("SELECT * FROM entry_tags WHERE entry_id IN (:entryIds)")
    suspend fun getEntryTagsForEntries(entryIds: List<String>): List<EntryTagEntity>

    @Query("SELECT * FROM entry_tags WHERE entry_id = :entryId AND tag_id = :tagId LIMIT 1")
    suspend fun getEntryTag(entryId: String, tagId: String): EntryTagEntity?

    @Query("SELECT DISTINCT entry_id FROM entry_tags WHERE tag_id = :tagId")
    suspend fun getEntryIdsForTag(tagId: String): List<String>

    @Query("SELECT DISTINCT entry_id FROM entry_tags WHERE tag_id IN (:tagIds)")
    suspend fun getEntryIdsForTags(tagIds: List<String>): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEntryTagIgnore(entryTag: EntryTagEntity): Long

    @Update
    suspend fun updateEntryTag(entryTag: EntryTagEntity)

    suspend fun upsertEntryTag(entryTag: EntryTagEntity) {
        if (insertEntryTagIgnore(entryTag) == -1L) {
            updateEntryTag(entryTag)
        }
    }

    suspend fun upsertEntryTags(entryTags: List<EntryTagEntity>) {
        for (entryTag in entryTags) {
            upsertEntryTag(entryTag)
        }
    }

    @Query("DELETE FROM entry_tags WHERE entry_id = :entryId AND tag_id = :tagId")
    suspend fun deleteEntryTag(entryId: String, tagId: String)

    @Query("DELETE FROM entry_tags WHERE entry_id = :entryId")
    suspend fun deleteEntryTagsForEntry(entryId: String)
}
