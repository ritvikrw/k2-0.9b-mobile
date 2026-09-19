package com.example.llama.aichat.notification

data class NotificationData(
    val packageName: String,
    val appName: String,
    val title: String?,
    val text: String?,
    val subText: String?,
    val sender: String?,
    val category: String?,
    val notificationKey: String,
    val timestamp: Long
)
