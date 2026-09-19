package com.example.llama.aichat.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.llama.aichat.MainActivity
import com.example.llama.aichat.R
import com.example.llama.aichat.data.NotificationRecord

class AIAlertManager(private val context: Context) {
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val CHANNEL_ID = "ai_important_alerts_v2"
    private val ALERT_NOTIFICATION_ID = 1002

    init {
        createChannel()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Clean up legacy channel if present
            try {
                notificationManager.deleteNotificationChannel("ai_important_alerts")
            } catch (e: Exception) {
                // Ignore
            }

            val soundUri = Uri.parse("android.resource://${context.packageName}/${R.raw.ai_alert}")
            val audioAttributes = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .build()

            val channel = NotificationChannel(
                CHANNEL_ID,
                "AI Important Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Distinctive alerts for AI-classified important notifications"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 250, 150, 250)
                setSound(soundUri, audioAttributes)
                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun triggerAlert(record: NotificationRecord) {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val soundEnabled = prefs.getBoolean("ai_alert_sound_enabled", true)
        Log.d("AIAlertManager", "triggerAlert called for: ${record.sender ?: record.appName}, soundEnabled=$soundEnabled")
        if (!soundEnabled) return

        // 1. Play the distinctive custom AI chime sound via MediaPlayer immediately
        try {
            val mediaPlayer = MediaPlayer.create(context, R.raw.ai_alert)
            if (mediaPlayer != null) {
                mediaPlayer.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .build()
                )
                mediaPlayer.setVolume(1.0f, 1.0f)
                mediaPlayer.setOnCompletionListener { mp ->
                    mp.release()
                }
                mediaPlayer.start()
                Log.d("AIAlertManager", "AI chime sound started successfully")
            } else {
                Log.w("AIAlertManager", "MediaPlayer.create returned null for R.raw.ai_alert")
            }
        } catch (e: Exception) {
            Log.e("AIAlertManager", "MediaPlayer sound playback error", e)
        }

        // 2. Trigger dual-pulse vibration pattern
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator?.vibrate(
                    VibrationEffect.createWaveform(longArrayOf(0, 200, 100, 200), -1)
                )
            } else {
                @Suppress("DEPRECATION")
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                @Suppress("DEPRECATION")
                vibrator?.vibrate(longArrayOf(0, 200, 100, 200), -1)
            }
        } catch (e: Exception) {
            Log.w("AIAlertManager", "Vibration error: ${e.message}")
        }

        // 3. Post the high-priority heads-up AI alert notification
        try {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            val pendingIntent = PendingIntent.getActivity(
                context, 1, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val soundUri = Uri.parse("android.resource://${context.packageName}/${R.raw.ai_alert}")

            val title = if (!record.sender.isNullOrBlank() && record.sender != record.appName) {
                "⚡ ${record.appName} · ${record.sender}"
            } else {
                "⚡ Important: ${record.appName}"
            }

            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(record.summary)
                .setStyle(
                    NotificationCompat.BigTextStyle()
                        .setBigContentTitle(title)
                        .bigText("${record.summary}\n\nDecision: ${record.reason}")
                )
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setSound(soundUri)
                .setVibrate(longArrayOf(0, 250, 150, 250))
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setTimeoutAfter(60000)

            notificationManager.notify(ALERT_NOTIFICATION_ID, builder.build())
            Log.d("AIAlertManager", "Posted alert notification ID $ALERT_NOTIFICATION_ID")
        } catch (e: Exception) {
            Log.e("AIAlertManager", "Failed to post alert notification: ${e.message}")
        }
    }
}

