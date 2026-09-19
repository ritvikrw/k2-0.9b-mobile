package com.example.llama.aichat.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface NotificationRuleDao {
    @Query("SELECT * FROM notification_rules ORDER BY createdAt DESC")
    fun getAllRules(): Flow<List<NotificationRule>>

    @Query("SELECT * FROM notification_rules WHERE enabled = 1")
    suspend fun getEnabledRules(): List<NotificationRule>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(rule: NotificationRule)

    @Update
    suspend fun update(rule: NotificationRule)

    @Delete
    suspend fun delete(rule: NotificationRule)
}
