package io.github.k8242.threadlog.core.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import io.github.k8242.threadlog.core.data.local.entity.BookmarkEntity

@Dao
interface BookmarkDao {

    @Query("SELECT * FROM bookmarks WHERE id = :bookmarkId LIMIT 1")
    suspend fun getById(bookmarkId: String): BookmarkEntity?

    @Query("SELECT * FROM bookmarks WHERE entry_id = :entryId ORDER BY id ASC")
    suspend fun getForEntry(entryId: String): List<BookmarkEntity>

    @Query("SELECT * FROM bookmarks WHERE entry_id IN (:entryIds) ORDER BY id ASC")
    suspend fun getForEntries(entryIds: List<String>): List<BookmarkEntity>

    @Query(
        """
        SELECT * FROM bookmarks
        WHERE entry_id = :entryId
          AND (title IS NULL OR trim(title) = '')
        ORDER BY id ASC
        """,
    )
    suspend fun getMissingTitleForEntry(entryId: String): List<BookmarkEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(bookmark: BookmarkEntity): Long

    @Update
    suspend fun update(bookmark: BookmarkEntity)

    suspend fun upsert(bookmark: BookmarkEntity) {
        if (insertIgnore(bookmark) == -1L) {
            update(bookmark)
        }
    }

    suspend fun upsertAll(bookmarks: List<BookmarkEntity>) {
        for (bookmark in bookmarks) {
            upsert(bookmark)
        }
    }

    @Query("UPDATE bookmarks SET title = :title WHERE id = :bookmarkId")
    suspend fun updateTitle(bookmarkId: String, title: String)

    @Query("UPDATE bookmarks SET is_collapsed = :collapsed WHERE id = :bookmarkId")
    suspend fun setCollapsed(bookmarkId: String, collapsed: Boolean)

    @Query("DELETE FROM bookmarks WHERE id = :bookmarkId")
    suspend fun deleteById(bookmarkId: String)

    @Query("DELETE FROM bookmarks WHERE entry_id = :entryId")
    suspend fun deleteForEntry(entryId: String)
}
