package com.example.llama.aichat.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "notification_rules")
data class NotificationRule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
