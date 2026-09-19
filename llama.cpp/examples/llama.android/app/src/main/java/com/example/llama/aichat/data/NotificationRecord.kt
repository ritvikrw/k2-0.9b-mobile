package com.example.llama.aichat.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "notification_records")
data class NotificationRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val notificationKey: String,
    val packageName: String,
    val appName: String,
    val title: String?,
    val text: String?,
    val sender: String?,
    val category: String?,
    val timestamp: Long,
    val important: Boolean,
    val alert: Boolean,
    val summary: String,
    val reason: String,
    val aiCategory: String,
    val processed: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)
