package com.example.llama.aichat.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.llama.aichat.MainActivity
import com.example.llama.aichat.R
import com.example.llama.aichat.data.NotificationRecord

class AIAlertManager(private val context: Context) {
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val CHANNEL_ID = "ai_important_alerts"
    private val ALERT_NOTIFICATION_ID = 1002

    init {
        createChannel()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val soundUri = Uri.parse("android.resource://${context.packageName}/raw/ai_alert")
            val audioAttributes = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .build()

            val channel = NotificationChannel(
                CHANNEL_ID,
                "AI Important Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Distinctive alerts for AI-classified important notifications"
                enableVibration(true)
                setSound(soundUri, audioAttributes)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun triggerAlert(record: NotificationRecord) {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val soundEnabled = prefs.getBoolean("ai_alert_sound_enabled", true)
        if (!soundEnabled) return

        // 1. Play the distinctive custom AI chime sound
        try {
            val mediaPlayer = MediaPlayer.create(context, R.raw.ai_alert)
            mediaPlayer?.setOnCompletionListener { it.release() }
            mediaPlayer?.start()
        } catch (e: Exception) {
            Log.w("AIAlertManager", "MediaPlayer sound playback error: ${e.message}")
        }

        // 2. Post the high-priority AI alert notification
        try {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            val pendingIntent = PendingIntent.getActivity(
                context, 1, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val soundUri = Uri.parse("android.resource://${context.packageName}/raw/ai_alert")

            val title = if (!record.sender.isNullOrBlank() && record.sender != record.appName) {
                "${record.appName} · ${record.sender}"
            } else {
                "Important: ${record.appName}"
            }

            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(record.summary)
                .setStyle(NotificationCompat.BigTextStyle().bigText(record.summary + "\nReason: " + record.reason))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setSound(soundUri)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setTimeoutAfter(30000)

            notificationManager.notify(ALERT_NOTIFICATION_ID, builder.build())
        } catch (e: Exception) {
            Log.e("AIAlertManager", "Failed to post alert notification: ${e.message}")
        }
    }
}
