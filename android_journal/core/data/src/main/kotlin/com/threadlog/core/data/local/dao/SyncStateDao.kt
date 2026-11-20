package com.threadlog.core.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.threadlog.core.data.local.entity.SyncEntryHashEntity

@Dao
interface SyncStateDao {

    @Query("SELECT * FROM sync_entry_hashes")
    suspend fun getAll(): List<SyncEntryHashEntity>

    @Query("SELECT * FROM sync_entry_hashes WHERE entry_id = :entryId LIMIT 1")
    suspend fun getByEntryId(entryId: String): SyncEntryHashEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(hash: SyncEntryHashEntity): Long

    @Update
    suspend fun update(hash: SyncEntryHashEntity)

    suspend fun upsertAll(hashes: List<SyncEntryHashEntity>) {
        for (hash in hashes) {
            if (insertIgnore(hash) == -1L) {
                update(hash)
            }
        }
    }

    @Query("DELETE FROM sync_entry_hashes WHERE entry_id = :entryId")
    suspend fun deleteByEntryId(entryId: String)
}
