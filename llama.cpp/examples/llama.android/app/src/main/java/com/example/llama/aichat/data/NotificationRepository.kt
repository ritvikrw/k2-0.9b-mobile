package com.example.llama.aichat.data

import kotlinx.coroutines.flow.Flow

class NotificationRepository(private val notificationDao: NotificationDao) {
    val allNotifications: Flow<List<NotificationRecord>> = notificationDao.getAllNotifications()
    val importantNotifications: Flow<List<NotificationRecord>> = notificationDao.getImportantNotifications()
    val unimportantNotifications: Flow<List<NotificationRecord>> = notificationDao.getUnimportantNotifications()

    suspend fun getImportantNotificationsSync(): List<NotificationRecord> {
        return notificationDao.getImportantNotificationsSync()
    }

    suspend fun insert(record: NotificationRecord) {
        notificationDao.insert(record)
        notificationDao.trimHistory()
    }

    suspend fun clearHistory() {
        notificationDao.clearHistory()
    }

    suspend fun getLatestByKey(key: String): NotificationRecord? {
        return notificationDao.getLatestByKey(key)
    }

    suspend fun findRecentDuplicate(packageName: String, title: String?, text: String?, sender: String?, sinceTimestamp: Long): NotificationRecord? {
        return notificationDao.findRecentDuplicate(packageName, title, text, sender, sinceTimestamp)
    }

    suspend fun getUnprocessedNotifications(): List<NotificationRecord> {
        return notificationDao.getUnprocessedNotifications()
    }

    suspend fun update(record: NotificationRecord) {
        notificationDao.update(record)
    }
}
