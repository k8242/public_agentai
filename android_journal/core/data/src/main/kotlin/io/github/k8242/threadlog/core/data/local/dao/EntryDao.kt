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

private const val NORM_BODY_E =
    "LOWER(TRIM(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(" +
        "e.body_plain, char(8206), ''), char(8207), ''), char(65279), ''), " +
        "char(8234), ''), char(8235), ''), char(8236), ''), char(8237), ''), char(8238), '')))"

private const val CLEAN_PLACEHOLDER_BODY_E =
    "CASE " +
        "WHEN instr($NORM_BODY_E, char(10)) = 0 " +
        "AND instr($NORM_BODY_E, ':') > 1 " +
        "AND $NORM_BODY_E NOT LIKE '<attached:%' " +
        "THEN TRIM(substr($NORM_BODY_E, instr($NORM_BODY_E, ':') + 1)) " +
        "ELSE $NORM_BODY_E " +
        "END"

private const val NOT_HIDDEN_PLACEHOLDER_E =
    "(EXISTS (SELECT 1 FROM attachments a WHERE a.entry_id = e.id) OR (" +
        "$CLEAN_PLACEHOLDER_BODY_E NOT IN (" +
        "'<media omitted>', 'media omitted', " +
        "'image omitted', 'video omitted', 'audio omitted', 'sticker omitted', 'gif omitted', 'document omitted', " +
        "'<image omitted>', '<video omitted>', '<audio omitted>', '<sticker omitted>', '<gif omitted>', '<document omitted>', " +
        "'you deleted this message', 'you deleted this message.', " +
        "'this message was deleted', 'this message was deleted.'" +
        ") AND NOT ($CLEAN_PLACEHOLDER_BODY_E LIKE '<attached:%>' AND instr($CLEAN_PLACEHOLDER_BODY_E, char(10)) = 0)))"

private const val PRIORITY_RANK_E =
    "CASE e.task_priority WHEN 'HIGH' THEN 3 WHEN 'MEDIUM' THEN 2 WHEN 'LOW' THEN 1 ELSE 0 END"

data class CompactEntrySummaryProjection(
    val id: String,
    val createdAtMs: Long,
    val zoneId: String,
    val localDate: String,
    val preview140: String,
    val hasAttachments: Boolean,
    val hasBookmarks: Boolean,
    val hasCodeBlock: Boolean,
    val taskStatus: String,
    val taskPriority: String,
    val isHighlighted: Boolean,
)

data class CalendarDayCountProjection(
    val localDate: String,
    val entryCount: Int,
    val openTodoCount: Int,
    val completedTodoCount: Int,
)

data class TimelineDateBucketProjection(
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

    @Query("SELECT DISTINCT entry_id FROM attachments WHERE entry_id IN (:entryIds)")
    suspend fun getEntryIdsWithAttachments(entryIds: List<String>): List<String>

    @Query("SELECT * FROM entries WHERE id = :entryId LIMIT 1")
    fun observeById(entryId: String): Flow<EntryEntity?>

    @Query("SELECT * FROM entries ORDER BY created_at_ms ASC, id ASC")
    suspend fun getAll(): List<EntryEntity>

    @Query("SELECT id FROM entries WHERE deleted_at_ms IS NULL")
    suspend fun getAllActiveIds(): List<String>

    // ---- Paging 3 feed queries ----

    @Query(
        """
        SELECT e.* FROM entries e
        WHERE e.deleted_at_ms IS NULL
          AND e.archived_at_ms IS NULL
          AND (:includeHidden = 1 OR $NOT_HIDDEN_PLACEHOLDER_E)
        ORDER BY e.created_at_ms DESC, e.id DESC
        """,
    )
    fun pagingSourceAll(includeHidden: Boolean = false): PagingSource<Int, EntryEntity>

    @Query(
        """
        SELECT e.* FROM entries e
        WHERE e.deleted_at_ms IS NULL
          AND e.archived_at_ms IS NULL
          AND $NOT_HIDDEN_PLACEHOLDER_E
          AND e.task_status IN (:statuses)
          AND (:priorityFilter IS NULL OR e.task_priority = :priorityFilter)
        ORDER BY e.created_at_ms DESC, e.id DESC
        """,
    )
    fun pagingSourceTodosByNewest(
        statuses: List<String>,
        priorityFilter: String?,
    ): PagingSource<Int, EntryEntity>

    @Query(
        """
        SELECT e.* FROM entries e
        WHERE e.deleted_at_ms IS NULL
          AND e.archived_at_ms IS NULL
          AND $NOT_HIDDEN_PLACEHOLDER_E
          AND e.task_status IN (:statuses)
          AND (:priorityFilter IS NULL OR e.task_priority = :priorityFilter)
        ORDER BY $PRIORITY_RANK_E DESC, e.created_at_ms DESC, e.id DESC
        """,
    )
    fun pagingSourceTodosByPriority(
        statuses: List<String>,
        priorityFilter: String?,
    ): PagingSource<Int, EntryEntity>

    @Query(
        """
        SELECT e.* FROM entries e
        WHERE e.deleted_at_ms IS NULL
          AND e.archived_at_ms IS NULL
          AND $NOT_HIDDEN_PLACEHOLDER_E
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
        SELECT e.* FROM entries e
        WHERE e.deleted_at_ms IS NULL
          AND e.archived_at_ms IS NOT NULL
          AND $NOT_HIDDEN_PLACEHOLDER_E
        ORDER BY e.created_at_ms DESC, e.id DESC
        """,
    )
    fun pagingSourceArchived(): PagingSource<Int, EntryEntity>

    // ---- Exact offset lookup queries (matching feed sort orders) ----

    @Query(
        """
        SELECT EXISTS(
            SELECT 1
            FROM entry_tags et
            INNER JOIN tags t ON t.id = et.tag_id
            WHERE et.entry_id = :entryId
              AND et.state IN ('USER', 'AUTO_PROMOTED')
              AND (
                  LOWER(t.full_path) = LOWER(:exactTagPath)
                  OR LOWER(t.full_path) LIKE LOWER(:subtreeLikePattern) ESCAPE '\'
              )
        )
        """,
    )
    suspend fun entryMatchesTagPrefix(
        entryId: String,
        exactTagPath: String,
        subtreeLikePattern: String,
    ): Boolean

    @Query(
        """
        SELECT COUNT(*) FROM entries e
        WHERE e.deleted_at_ms IS NULL
          AND e.archived_at_ms IS NULL
          AND (:includeHidden = 1 OR $NOT_HIDDEN_PLACEHOLDER_E)
          AND (
              e.created_at_ms > :targetCreatedAtMs
              OR (e.created_at_ms = :targetCreatedAtMs AND e.id > :targetId)
          )
        """,
    )
    suspend fun countAheadAll(
        targetCreatedAtMs: Long,
        targetId: String,
        includeHidden: Boolean,
    ): Int

    @Query(
        """
        SELECT COUNT(*) FROM entries e
        WHERE e.deleted_at_ms IS NULL
          AND e.archived_at_ms IS NOT NULL
          AND $NOT_HIDDEN_PLACEHOLDER_E
          AND (
              e.created_at_ms > :targetCreatedAtMs
              OR (e.created_at_ms = :targetCreatedAtMs AND e.id > :targetId)
          )
        """,
    )
    suspend fun countAheadArchived(
        targetCreatedAtMs: Long,
        targetId: String,
    ): Int

    @Query(
        """
        SELECT COUNT(*) FROM entries e
        WHERE e.deleted_at_ms IS NULL
          AND e.archived_at_ms IS NULL
          AND $NOT_HIDDEN_PLACEHOLDER_E
          AND e.task_status IN (:statuses)
          AND (:priorityFilter IS NULL OR e.task_priority = :priorityFilter)
          AND (
              e.created_at_ms > :targetCreatedAtMs
              OR (e.created_at_ms = :targetCreatedAtMs AND e.id > :targetId)
          )
        """,
    )
    suspend fun countAheadTodosByNewest(
        statuses: List<String>,
        priorityFilter: String?,
        targetCreatedAtMs: Long,
        targetId: String,
    ): Int

    @Query(
        """
        SELECT COUNT(*) FROM entries e
        WHERE e.deleted_at_ms IS NULL
          AND e.archived_at_ms IS NULL
          AND $NOT_HIDDEN_PLACEHOLDER_E
          AND e.task_status IN (:statuses)
          AND (:priorityFilter IS NULL OR e.task_priority = :priorityFilter)
          AND (
              ($PRIORITY_RANK_E) > :targetPriorityRank
              OR (
                  ($PRIORITY_RANK_E) = :targetPriorityRank
                  AND (
                      e.created_at_ms > :targetCreatedAtMs
                      OR (e.created_at_ms = :targetCreatedAtMs AND e.id > :targetId)
                  )
              )
          )
        """,
    )
    suspend fun countAheadTodosByPriority(
        statuses: List<String>,
        priorityFilter: String?,
        targetPriorityRank: Int,
        targetCreatedAtMs: Long,
        targetId: String,
    ): Int

    @Query(
        """
        SELECT COUNT(*) FROM entries e
        WHERE e.deleted_at_ms IS NULL
          AND e.archived_at_ms IS NULL
          AND $NOT_HIDDEN_PLACEHOLDER_E
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
          AND (
              e.created_at_ms > :targetCreatedAtMs
              OR (e.created_at_ms = :targetCreatedAtMs AND e.id > :targetId)
          )
        """,
    )
    suspend fun countAheadTagPrefix(
        exactTagPath: String,
        subtreeLikePattern: String,
        targetCreatedAtMs: Long,
        targetId: String,
    ): Int

    // ---- Date jump helpers (newest entry on or before localDate) ----

    @Query(
        """
        SELECT e.* FROM entries e
        WHERE e.deleted_at_ms IS NULL
          AND e.archived_at_ms IS NULL
          AND (:includeHidden = 1 OR $NOT_HIDDEN_PLACEHOLDER_E)
          AND e.local_date <= :localDate
        ORDER BY e.local_date DESC, e.created_at_ms DESC, e.id DESC
        LIMIT 1
        """,
    )
    suspend fun getNewestEntryForDateOnOrBeforeAll(
        localDate: String,
        includeHidden: Boolean,
    ): EntryEntity?

    @Query(
        """
        SELECT e.* FROM entries e
        WHERE e.deleted_at_ms IS NULL
          AND e.archived_at_ms IS NOT NULL
          AND $NOT_HIDDEN_PLACEHOLDER_E
          AND e.local_date <= :localDate
        ORDER BY e.local_date DESC, e.created_at_ms DESC, e.id DESC
        LIMIT 1
        """,
    )
    suspend fun getNewestEntryForDateOnOrBeforeArchived(
        localDate: String,
    ): EntryEntity?

    @Query(
        """
        SELECT e.* FROM entries e
        WHERE e.deleted_at_ms IS NULL
          AND e.archived_at_ms IS NULL
          AND $NOT_HIDDEN_PLACEHOLDER_E
          AND e.task_status IN (:statuses)
          AND (:priorityFilter IS NULL OR e.task_priority = :priorityFilter)
          AND e.local_date <= :localDate
        ORDER BY e.local_date DESC, e.created_at_ms DESC, e.id DESC
        LIMIT 1
        """,
    )
    suspend fun getNewestEntryForDateOnOrBeforeTodos(
        localDate: String,
        statuses: List<String>,
        priorityFilter: String?,
    ): EntryEntity?

    @Query(
        """
        SELECT e.* FROM entries e
        WHERE e.deleted_at_ms IS NULL
          AND e.archived_at_ms IS NULL
          AND $NOT_HIDDEN_PLACEHOLDER_E
          AND e.local_date <= :localDate
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
        ORDER BY e.local_date DESC, e.created_at_ms DESC, e.id DESC
        LIMIT 1
        """,
    )
    suspend fun getNewestEntryForDateOnOrBeforeTagPrefix(
        localDate: String,
        exactTagPath: String,
        subtreeLikePattern: String,
    ): EntryEntity?

    // ---- Timeline date buckets for fast scrubber ----

    @Query(
        """
        SELECT
            e.local_date AS localDate,
            COUNT(*) AS entryCount
        FROM entries e
        WHERE e.deleted_at_ms IS NULL
          AND e.archived_at_ms IS NULL
          AND (:includeHidden = 1 OR $NOT_HIDDEN_PLACEHOLDER_E)
        GROUP BY e.local_date
        ORDER BY MAX(e.created_at_ms) DESC, e.local_date DESC
        """,
    )
    fun observeTimelineDateBucketsAll(includeHidden: Boolean): Flow<List<TimelineDateBucketProjection>>

    @Query(
        """
        SELECT
            e.local_date AS localDate,
            COUNT(*) AS entryCount
        FROM entries e
        WHERE e.deleted_at_ms IS NULL
          AND e.archived_at_ms IS NOT NULL
          AND $NOT_HIDDEN_PLACEHOLDER_E
        GROUP BY e.local_date
        ORDER BY MAX(e.created_at_ms) DESC, e.local_date DESC
        """,
    )
    fun observeTimelineDateBucketsArchived(): Flow<List<TimelineDateBucketProjection>>

    @Query(
        """
        SELECT
            e.local_date AS localDate,
            COUNT(*) AS entryCount
        FROM entries e
        WHERE e.deleted_at_ms IS NULL
          AND e.archived_at_ms IS NULL
          AND $NOT_HIDDEN_PLACEHOLDER_E
          AND e.task_status IN (:statuses)
          AND (:priorityFilter IS NULL OR e.task_priority = :priorityFilter)
        GROUP BY e.local_date
        ORDER BY MAX(e.created_at_ms) DESC, e.local_date DESC
        """,
    )
    fun observeTimelineDateBucketsTodos(
        statuses: List<String>,
        priorityFilter: String?,
    ): Flow<List<TimelineDateBucketProjection>>

    @Query(
        """
        SELECT
            e.local_date AS localDate,
            COUNT(*) AS entryCount
        FROM entries e
        WHERE e.deleted_at_ms IS NULL
          AND e.archived_at_ms IS NULL
          AND $NOT_HIDDEN_PLACEHOLDER_E
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
        ORDER BY MAX(e.created_at_ms) DESC, e.local_date DESC
        """,
    )
    fun observeTimelineDateBucketsTagPrefix(
        exactTagPath: String,
        subtreeLikePattern: String,
    ): Flow<List<TimelineDateBucketProjection>>

    // ---- Drawer, Weekly, Calendar, ContextWindow, and AutoTag queries ----

    @Query(
        """
        SELECT
            COUNT(*) AS totalEntryCount,
            (SELECT substr(e.body_plain, 1, 100) FROM entries e WHERE e.deleted_at_ms IS NULL AND e.archived_at_ms IS NULL AND $NOT_HIDDEN_PLACEHOLDER_E ORDER BY e.created_at_ms DESC, e.id DESC LIMIT 1) AS latestEntryPreview,
            SUM(CASE WHEN e.task_status = 'TODO' THEN 1 ELSE 0 END) AS openTodoCount,
            SUM(CASE WHEN e.task_status = 'DONE' THEN 1 ELSE 0 END) AS completedTodoCount,
            (SELECT substr(e.body_plain, 1, 100) FROM entries e WHERE e.deleted_at_ms IS NULL AND e.archived_at_ms IS NULL AND e.task_status = 'TODO' AND $NOT_HIDDEN_PLACEHOLDER_E ORDER BY e.created_at_ms DESC, e.id DESC LIMIT 1) AS latestOpenTodoPreview,
            (SELECT COUNT(*) FROM entries e WHERE e.deleted_at_ms IS NULL AND e.archived_at_ms IS NOT NULL AND $NOT_HIDDEN_PLACEHOLDER_E) AS archivedCount
        FROM entries e
        WHERE e.deleted_at_ms IS NULL
          AND e.archived_at_ms IS NULL
          AND $NOT_HIDDEN_PLACEHOLDER_E
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
          AND e.archived_at_ms IS NULL
          AND $NOT_HIDDEN_PLACEHOLDER_E
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
            CASE WHEN instr(e.body_raw, '```') > 0 THEN 1 ELSE 0 END AS hasCodeBlock,
            e.task_status AS taskStatus,
            e.task_priority AS taskPriority,
            e.is_highlighted AS isHighlighted
        FROM entries e
        WHERE e.deleted_at_ms IS NULL
          AND e.archived_at_ms IS NULL
          AND $NOT_HIDDEN_PLACEHOLDER_E
        ORDER BY e.created_at_ms DESC, e.id DESC
        """,
    )
    fun observeCompactSummaries(): Flow<List<CompactEntrySummaryProjection>>

    @Query(
        """
        SELECT
            e.local_date AS localDate,
            COUNT(*) AS entryCount,
            COALESCE(SUM(CASE WHEN e.task_status = 'TODO' THEN 1 ELSE 0 END), 0) AS openTodoCount,
            COALESCE(SUM(CASE WHEN e.task_status = 'DONE' THEN 1 ELSE 0 END), 0) AS completedTodoCount
        FROM entries e
        WHERE e.deleted_at_ms IS NULL
          AND e.archived_at_ms IS NULL
          AND $NOT_HIDDEN_PLACEHOLDER_E
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
            COUNT(*) AS entryCount,
            COALESCE(SUM(CASE WHEN e.task_status = 'TODO' THEN 1 ELSE 0 END), 0) AS openTodoCount,
            COALESCE(SUM(CASE WHEN e.task_status = 'DONE' THEN 1 ELSE 0 END), 0) AS completedTodoCount
        FROM entries e
        WHERE e.deleted_at_ms IS NULL
          AND e.archived_at_ms IS NULL
          AND $NOT_HIDDEN_PLACEHOLDER_E
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
        SELECT e.* FROM entries e
        WHERE e.deleted_at_ms IS NULL
          AND e.archived_at_ms IS NULL
          AND $NOT_HIDDEN_PLACEHOLDER_E
          AND e.local_date = :localDate
        ORDER BY e.created_at_ms ASC, e.id ASC
        """,
    )
    suspend fun getEntriesForDate(localDate: String): List<EntryEntity>

    @Query(
        """
        SELECT e.* FROM entries e
        WHERE e.deleted_at_ms IS NULL
          AND e.archived_at_ms IS NULL
          AND $NOT_HIDDEN_PLACEHOLDER_E
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
        SELECT e.* FROM entries e
        WHERE e.deleted_at_ms IS NULL
          AND e.archived_at_ms IS NULL
          AND $NOT_HIDDEN_PLACEHOLDER_E
          AND e.created_at_ms BETWEEN :startMs AND :endMs
        ORDER BY e.created_at_ms ASC, e.id ASC
        """,
    )
    suspend fun getContextWindow(startMs: Long, endMs: Long): List<EntryEntity>

    @Query(
        """
        SELECT e.* FROM entries e
        WHERE e.deleted_at_ms IS NULL
          AND e.archived_at_ms IS NULL
          AND $NOT_HIDDEN_PLACEHOLDER_E
          AND EXISTS (
              SELECT 1 FROM entry_tags et
              WHERE et.entry_id = e.id AND et.state = 'AUTO_HIDDEN'
          )
        ORDER BY e.created_at_ms DESC, e.id DESC
        """,
    )
    suspend fun getEntriesWithPendingAutoTags(): List<EntryEntity>

    @Query(
        """
        SELECT e.id, e.local_date AS localDate
        FROM entries e
        WHERE e.deleted_at_ms IS NULL
          AND e.archived_at_ms IS NULL
          AND $NOT_HIDDEN_PLACEHOLDER_E
          AND e.local_date IN (:localDates)
        ORDER BY e.local_date ASC, e.created_at_ms ASC, e.id ASC
        """,
    )
    suspend fun getActiveEntryIdsForDates(localDates: List<String>): List<DailyEntryIdProjection>
}

data class DailyEntryIdProjection(
    val id: String,
    val localDate: String,
)

data class DrawerOverviewProjection(
    val totalEntryCount: Int,
    val latestEntryPreview: String?,
    val openTodoCount: Int?,
    val completedTodoCount: Int?,
    val latestOpenTodoPreview: String?,
    val archivedCount: Int?,
)

data class ActiveVisibleEntryTagLinkProjection(
    val entryId: String,
    val createdAtMs: Long,
    val preview: String,
    val taskStatus: String,
    val tagFullPath: String,
)
