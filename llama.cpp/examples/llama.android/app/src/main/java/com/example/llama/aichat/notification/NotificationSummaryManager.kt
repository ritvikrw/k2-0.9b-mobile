package com.example.llama.aichat.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.llama.aichat.MainActivity
import com.example.llama.aichat.data.NotificationRecord

class NotificationSummaryManager(private val context: Context) {
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val SUMMARY_ID = 1001
    private val CHANNEL_ID = "analyzer_summary"

    init {
        createChannel()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Notification Analyzer Summary",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows the summary of important notifications"
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun updateSummary(importantNotifications: List<NotificationRecord>) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val title = if (importantNotifications.isEmpty()) {
            "No important notifications"
        } else {
            "${importantNotifications.size} important notifications"
        }

        val inboxStyle = NotificationCompat.InboxStyle()
        importantNotifications.take(5).forEach {
            inboxStyle.addLine("• ${it.summary}")
        }
        if (importantNotifications.size > 5) {
            inboxStyle.setSummaryText("+${importantNotifications.size - 5} more")
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info) // Replace with app icon
            .setContentTitle(title)
            .setContentText(importantNotifications.firstOrNull()?.summary ?: "Tap to view full summary")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .setStyle(inboxStyle)
            .setOngoing(true)

        notificationManager.notify(SUMMARY_ID, builder.build())
    }

    fun clearSummary() {
        updateSummary(emptyList())
    }

    fun cancelSummary() {
        notificationManager.cancel(SUMMARY_ID)
    }
}
