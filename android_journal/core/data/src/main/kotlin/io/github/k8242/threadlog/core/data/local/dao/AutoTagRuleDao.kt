package io.github.k8242.threadlog.core.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import io.github.k8242.threadlog.core.data.local.entity.AutoTagRuleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AutoTagRuleDao {

    @Query("SELECT * FROM auto_tag_rules ORDER BY target_tag_path ASC, pattern ASC, id ASC")
    fun observeAll(): Flow<List<AutoTagRuleEntity>>

    @Query("SELECT * FROM auto_tag_rules WHERE enabled = 1 ORDER BY target_tag_path ASC, pattern ASC, id ASC")
    suspend fun getEnabled(): List<AutoTagRuleEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(rule: AutoTagRuleEntity): Long

    @Update
    suspend fun update(rule: AutoTagRuleEntity)

    suspend fun upsert(rule: AutoTagRuleEntity) {
        if (insertIgnore(rule) == -1L) {
            update(rule)
        }
    }

    @Query("DELETE FROM auto_tag_rules WHERE id = :ruleId")
    suspend fun deleteById(ruleId: String)
}
