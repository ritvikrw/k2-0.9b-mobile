package com.example.llama.aichat.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface NotificationDao {
    @Query("SELECT * FROM notification_records WHERE processed = 1 AND timestamp >= :cutoff ORDER BY timestamp DESC")
    fun getNotificationsSince(cutoff: Long): Flow<List<NotificationRecord>>

    @Query("SELECT * FROM notification_records WHERE important = 1 AND processed = 1 AND timestamp >= :cutoff ORDER BY timestamp DESC")
    fun getImportantNotificationsSince(cutoff: Long): Flow<List<NotificationRecord>>

    @Query("SELECT * FROM notification_records WHERE important = 0 AND processed = 1 AND timestamp >= :cutoff ORDER BY timestamp DESC")
    fun getUnimportantNotificationsSince(cutoff: Long): Flow<List<NotificationRecord>>

    @Query("SELECT * FROM notification_records WHERE processed = 1 ORDER BY timestamp DESC")
    fun getAllNotifications(): Flow<List<NotificationRecord>>

    @Query("SELECT * FROM notification_records WHERE important = 1 AND processed = 1 ORDER BY timestamp DESC")
    fun getImportantNotifications(): Flow<List<NotificationRecord>>

    @Query("SELECT * FROM notification_records WHERE important = 1 AND processed = 1 ORDER BY timestamp DESC")
    suspend fun getImportantNotificationsSync(): List<NotificationRecord>

    @Query("SELECT * FROM notification_records WHERE important = 0 AND processed = 1 ORDER BY timestamp DESC")
    fun getUnimportantNotifications(): Flow<List<NotificationRecord>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: NotificationRecord)

    @Query("DELETE FROM notification_records")
    suspend fun clearHistory()

    @Query("DELETE FROM notification_records WHERE timestamp < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)

    @Query("DELETE FROM notification_records WHERE id < (SELECT id FROM notification_records ORDER BY id DESC LIMIT 1 OFFSET 2000)")
    suspend fun trimHistory()

    @Query("SELECT * FROM notification_records WHERE notificationKey = :key ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestByKey(key: String): NotificationRecord?

    @Query("SELECT * FROM notification_records WHERE packageName = :packageName AND ((title = :title AND text = :text) OR (sender = :sender AND text = :text)) AND timestamp >= :sinceTimestamp ORDER BY timestamp DESC LIMIT 1")
    suspend fun findRecentDuplicate(packageName: String, title: String?, text: String?, sender: String?, sinceTimestamp: Long): NotificationRecord?

    @Query("SELECT * FROM notification_records WHERE processed = 0")
    suspend fun getUnprocessedNotifications(): List<NotificationRecord>

    @Delete
    suspend fun delete(record: NotificationRecord)

    @Update
    suspend fun update(record: NotificationRecord)
}
