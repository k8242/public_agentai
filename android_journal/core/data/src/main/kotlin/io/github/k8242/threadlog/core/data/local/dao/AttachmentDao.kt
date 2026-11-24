// Copyright (c) 2026 k8242
// SPDX-License-Identifier: MIT

package io.github.k8242.threadlog.core.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import io.github.k8242.threadlog.core.data.local.entity.AttachmentEntity

@Dao
interface AttachmentDao {

    @Query("SELECT * FROM attachments WHERE entry_id = :entryId ORDER BY id ASC")
    suspend fun getForEntry(entryId: String): List<AttachmentEntity>

    @Query("SELECT * FROM attachments WHERE entry_id IN (:entryIds) ORDER BY id ASC")
    suspend fun getForEntries(entryIds: List<String>): List<AttachmentEntity>

    @Query("SELECT * FROM attachments WHERE sha256 = :sha256 LIMIT 1")
    suspend fun getBySha256(sha256: String): AttachmentEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(attachment: AttachmentEntity): Long

    @Update
    suspend fun update(attachment: AttachmentEntity)

    suspend fun upsert(attachment: AttachmentEntity) {
        if (insertIgnore(attachment) == -1L) {
            update(attachment)
        }
    }

    suspend fun upsertAll(attachments: List<AttachmentEntity>) {
        for (attachment in attachments) {
            upsert(attachment)
        }
    }

    @Query("UPDATE attachments SET drive_file_id = :driveFileId WHERE id = :attachmentId")
    suspend fun updateDriveFileId(attachmentId: String, driveFileId: String)

    @Query("DELETE FROM attachments WHERE entry_id = :entryId")
    suspend fun deleteForEntry(entryId: String)

    @Query("DELETE FROM attachments WHERE entry_id = :entryId AND id IN (:ids)")
    suspend fun deleteByIds(entryId: String, ids: List<String>)
}
