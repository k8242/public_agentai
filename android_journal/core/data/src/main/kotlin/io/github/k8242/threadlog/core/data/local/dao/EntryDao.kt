// Copyright (c) 2026 k8242
// SPDX-License-Identifier: MIT

package io.github.k8242.threadlog.core.data.local.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import io.github.k8242.threadlog.core.data.local.entity.EntryEntity
import kotlinx.coroutines.flow.Flow

data class CompactEntrySummaryProjection(
    val id: String,
    val createdAtMs: Long,
    val zoneId: String,
    val localDate: String,
    val preview140: String,
    val hasAttachments: Boolean,
    val hasBookmarks: Boolean,
    val hasCodeBlock: Boolean,
)

data class CalendarDayCountProjection(
    val localDate: String,
    val entryCount: Int,
)

@Dao
interface EntryDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(entry: EntryEntity): Long

    @Update
    suspend fun update(entry: EntryEntity)

    suspend fun upsert(entry: EntryEntity) {
        if (insertIgnore(entry) == -1L) {
            update(entry)
        }
    }

    @Query("SELECT * FROM entries WHERE id = :entryId LIMIT 1")
    suspend fun getById(entryId: String): EntryEntity?

    @Query("SELECT * FROM entries WHERE id IN (:entryIds)")
    suspend fun getByIds(entryIds: List<String>): List<EntryEntity>

    @Query("SELECT * FROM entries WHERE id = :entryId LIMIT 1")
    fun observeById(entryId: String): Flow<EntryEntity?>

    @Query("SELECT * FROM entries ORDER BY created_at_ms ASC, id ASC")
    suspend fun getAll(): List<EntryEntity>

    @Query("SELECT id FROM entries WHERE deleted_at_ms IS NULL")
    suspend fun getAllActiveIds(): List<String>

    @Query(
        """
        SELECT * FROM entries
        WHERE deleted_at_ms IS NULL
        ORDER BY created_at_ms DESC, id DESC
        """,
    )
    fun pagingSource(): PagingSource<Int, EntryEntity>

    @Query(
        """
        SELECT * FROM entries
        WHERE deleted_at_ms IS NULL
          AND task_status IN (:statuses)
        ORDER BY created_at_ms DESC, id DESC
        """,
    )
    fun pagingSourceByTaskStatuses(statuses: List<String>): PagingSource<Int, EntryEntity>

    @Query(
        """
        SELECT e.* FROM entries e
        WHERE e.deleted_at_ms IS NULL
          AND EXISTS (
              SELECT 1
              FROM entry_tags et
              INNER JOIN tags t ON t.id = et.tag_id
              WHERE et.entry_id = e.id
                AND et.state IN ('USER', 'AUTO_PROMOTED')
                AND (
                    LOWER(t.full_path) = LOWER(:exactTagPath)
                    OR LOWER(t.full_path) LIKE LOWER(:subtreeLikePattern) ESCAPE '\'
                )
          )
        ORDER BY e.created_at_ms DESC, e.id DESC
        """,
    )
    fun pagingSourceByTagPrefix(
        exactTagPath: String,
        subtreeLikePattern: String,
    ): PagingSource<Int, EntryEntity>

    @Query(
        """
        SELECT
            COUNT(*) AS totalEntryCount,
            (SELECT substr(body_plain, 1, 100) FROM entries WHERE deleted_at_ms IS NULL ORDER BY created_at_ms DESC, id DESC LIMIT 1) AS latestEntryPreview,
            SUM(CASE WHEN task_status = 'TODO' THEN 1 ELSE 0 END) AS openTodoCount,
            SUM(CASE WHEN task_status = 'DONE' THEN 1 ELSE 0 END) AS completedTodoCount,
            (SELECT substr(body_plain, 1, 100) FROM entries WHERE deleted_at_ms IS NULL AND task_status = 'TODO' ORDER BY created_at_ms DESC, id DESC LIMIT 1) AS latestOpenTodoPreview
        FROM entries
        WHERE deleted_at_ms IS NULL
        """,
    )
    suspend fun getDrawerOverview(): DrawerOverviewProjection

    @Query(
        """
        SELECT
            e.id AS entryId,
            e.created_at_ms AS createdAtMs,
            substr(e.body_plain, 1, 100) AS preview,
            e.task_status AS taskStatus,
            t.full_path AS tagFullPath
        FROM entry_tags et
        INNER JOIN entries e ON e.id = et.entry_id
        INNER JOIN tags t ON t.id = et.tag_id
        WHERE e.deleted_at_ms IS NULL
          AND et.state IN ('USER', 'AUTO_PROMOTED')
        ORDER BY e.created_at_ms DESC, e.id DESC
        """,
    )
    suspend fun getActiveVisibleEntryTagLinks(): List<ActiveVisibleEntryTagLinkProjection>

    @Query(
        """
        SELECT
            e.id AS id,
            e.created_at_ms AS createdAtMs,
            e.zone_id AS zoneId,
            e.local_date AS localDate,
            substr(e.body_plain, 1, 140) AS preview140,
            EXISTS(SELECT 1 FROM attachments a WHERE a.entry_id = e.id) AS hasAttachments,
            EXISTS(SELECT 1 FROM bookmarks b WHERE b.entry_id = e.id) AS hasBookmarks,
            CASE WHEN instr(e.body_raw, '```') > 0 THEN 1 ELSE 0 END AS hasCodeBlock
        FROM entries e
        WHERE e.deleted_at_ms IS NULL
        ORDER BY e.created_at_ms DESC, e.id DESC
        """,
    )
    fun observeCompactSummaries(): Flow<List<CompactEntrySummaryProjection>>

    @Query(
        """
        SELECT
            e.local_date AS localDate,
            COUNT(*) AS entryCount
        FROM entries e
        WHERE e.deleted_at_ms IS NULL
          AND substr(e.local_date, 1, 7) = :yearMonth
        GROUP BY e.local_date
        ORDER BY e.local_date ASC
        """,
    )
    fun observeCalendarDayCounts(yearMonth: String): Flow<List<CalendarDayCountProjection>>

    @Query(
        """
        SELECT
            e.local_date AS localDate,
            COUNT(*) AS entryCount
        FROM entries e
        WHERE e.deleted_at_ms IS NULL
          AND substr(e.local_date, 1, 7) = :yearMonth
          AND EXISTS (
              SELECT 1
              FROM entry_tags et
              INNER JOIN tags t ON t.id = et.tag_id
              WHERE et.entry_id = e.id
                AND et.state IN ('USER', 'AUTO_PROMOTED')
                AND (
                    LOWER(t.full_path) = LOWER(:exactTagPath)
                    OR LOWER(t.full_path) LIKE LOWER(:subtreeLikePattern) ESCAPE '\'
                )
          )
        GROUP BY e.local_date
        ORDER BY e.local_date ASC
        """,
    )
    fun observeCalendarDayCountsByTagPrefix(
        yearMonth: String,
        exactTagPath: String,
        subtreeLikePattern: String,
    ): Flow<List<CalendarDayCountProjection>>

    @Query(
        """
        SELECT * FROM entries
        WHERE deleted_at_ms IS NULL
          AND local_date = :localDate
        ORDER BY created_at_ms ASC, id ASC
        """,
    )
    suspend fun getEntriesForDate(localDate: String): List<EntryEntity>

    @Query(
        """
        SELECT e.* FROM entries e
        WHERE e.deleted_at_ms IS NULL
          AND e.local_date = :localDate
          AND EXISTS (
              SELECT 1
              FROM entry_tags et
              INNER JOIN tags t ON t.id = et.tag_id
              WHERE et.entry_id = e.id
                AND et.state IN ('USER', 'AUTO_PROMOTED')
                AND (
                    LOWER(t.full_path) = LOWER(:exactTagPath)
                    OR LOWER(t.full_path) LIKE LOWER(:subtreeLikePattern) ESCAPE '\'
                )
          )
        ORDER BY e.created_at_ms ASC, e.id ASC
        """,
    )
    suspend fun getEntriesForDateByTagPrefix(
        localDate: String,
        exactTagPath: String,
        subtreeLikePattern: String,
    ): List<EntryEntity>

    @Query(
        """
        SELECT * FROM entries
        WHERE deleted_at_ms IS NULL
          AND created_at_ms BETWEEN :startMs AND :endMs
        ORDER BY created_at_ms ASC, id ASC
        """,
    )
    suspend fun getContextWindow(startMs: Long, endMs: Long): List<EntryEntity>

    @Query(
        """
        SELECT e.* FROM entries e
        WHERE e.deleted_at_ms IS NULL
          AND EXISTS (
              SELECT 1 FROM entry_tags et
              WHERE et.entry_id = e.id AND et.state = 'AUTO_HIDDEN'
          )
        ORDER BY e.created_at_ms DESC, e.id DESC
        """,
    )
    suspend fun getEntriesWithPendingAutoTags(): List<EntryEntity>
}

data class DrawerOverviewProjection(
    val totalEntryCount: Int,
    val latestEntryPreview: String?,
    val openTodoCount: Int?,
    val completedTodoCount: Int?,
    val latestOpenTodoPreview: String?,
)

data class ActiveVisibleEntryTagLinkProjection(
    val entryId: String,
    val createdAtMs: Long,
    val preview: String,
    val taskStatus: String,
    val tagFullPath: String,
)
