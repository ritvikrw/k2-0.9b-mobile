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
                description = "Shows the ongoing persistent summary of important notifications"
                setShowBadge(false)
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
            "Notification Analyzer · Active"
        } else {
            "${importantNotifications.size} Important Notification${if (importantNotifications.size > 1) "s" else ""}"
        }

        val contentText = if (importantNotifications.isEmpty()) {
            "0 priority items • Monitoring with on-device K2 AI"
        } else {
            importantNotifications.first().summary
        }

        val inboxStyle = NotificationCompat.InboxStyle()
            .setBigContentTitle(title)
            .setSummaryText("K2 On-Device AI")

        if (importantNotifications.isEmpty()) {
            inboxStyle.addLine("• No urgent notifications pending")
        } else {
            importantNotifications.take(6).forEach {
                val prefix = if (!it.sender.isNullOrBlank() && it.sender != it.appName) "${it.sender}: " else "${it.appName}: "
                inboxStyle.addLine("• $prefix${it.summary}")
            }
            if (importantNotifications.size > 6) {
                inboxStyle.setSummaryText("+${importantNotifications.size - 6} more important")
            }
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(contentText)
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
