package com.example.llama.aichat.notification

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.llama.aichat.ai.K2InferenceManager
import com.example.llama.aichat.data.AppDatabase
import com.example.llama.aichat.data.NotificationRepository
import com.example.llama.aichat.data.NotificationRuleRepository

class NotificationListener : NotificationListenerService() {
    private lateinit var processor: NotificationProcessor
    private lateinit var inferenceManager: K2InferenceManager

    override fun onCreate() {
        super.onCreate()

        val db = AppDatabase.getDatabase(this)
        val notificationRepo = NotificationRepository(db.notificationDao())
        val ruleRepo = NotificationRuleRepository(db.notificationRuleDao())

        inferenceManager = K2InferenceManager.getInstance(this)
        val summaryManager = NotificationSummaryManager(this)
        val alertManager = AIAlertManager(this)

        processor = NotificationProcessor(
            this,
            inferenceManager,
            notificationRepo,
            ruleRepo,
            summaryManager,
            alertManager
        )
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return

        // Skip our own notifications to avoid infinite loops
        if (sbn.packageName == packageName) return

        // Skip ongoing system status indicators (e.g. charging progress, USB connection, foreground system meters)
        if (sbn.isOngoing && (sbn.packageName == "com.android.systemui" || sbn.packageName == "android")) {
            return
        }

        Log.d("NotificationListener", "Incoming notification from: ${sbn.packageName}")

        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()
        var text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim()

        if (text.isNullOrBlank() && !bigText.isNullOrBlank()) {
            text = bigText
        }

        // Check EXTRA_TEXT_LINES (InboxStyle notifications)
        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
        if (!lines.isNullOrEmpty()) {
            val combinedLines = lines.filterNotNull().joinToString("\n") { it.toString().trim() }
            if (combinedLines.isNotBlank() && (text.isNullOrBlank() || text!!.contains("new message", ignoreCase = true))) {
                text = combinedLines
            }
        }

        // Check EXTRA_MESSAGES text (MessagingStyle notifications)
        try {
            val messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
            if (!messages.isNullOrEmpty()) {
                val lastMsg = messages.lastOrNull()
                if (lastMsg is android.os.Bundle) {
                    val msgText = lastMsg.getCharSequence("text")?.toString()?.trim()
                    if (!msgText.isNullOrBlank() && (text.isNullOrBlank() || text!!.contains("new message", ignoreCase = true))) {
                        text = msgText
                    }
                }
            }
        } catch (e: Exception) {
            Log.d("NotificationListener", "Error extracting message text: ${e.message}")
        }

        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim()

        // Extract sender name
        var sender: String? = null
        try {
            // Check messaging style messages first
            val messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
            if (!messages.isNullOrEmpty()) {
                val lastMsg = messages.lastOrNull()
                if (lastMsg is android.os.Bundle) {
                    val msgSender = lastMsg.getCharSequence("sender")?.toString()
                    if (!msgSender.isNullOrBlank() && !msgSender.equals("You", ignoreCase = true)) {
                        sender = msgSender
                    }
                }
            }
        } catch (e: Exception) {
            Log.d("NotificationListener", "Error extracting message sender: ${e.message}")
        }

        if (sender.isNullOrBlank() || sender.equals("You", ignoreCase = true)) {
            val convTitle = extras.getCharSequence(NotificationCompat.EXTRA_CONVERSATION_TITLE)?.toString()
            if (!convTitle.isNullOrBlank() && !convTitle.equals("You", ignoreCase = true)) {
                sender = convTitle
            }
        }

        if (sender.isNullOrBlank() || sender.equals("You", ignoreCase = true)) {
            if (!title.isNullOrBlank() && !title.equals("You", ignoreCase = true)) {
                sender = title
            }
        }

        Log.d("NotificationListener", "Final Summary - App: ${sbn.packageName}, Sender: $sender, Title: $title, Text: ${text?.take(20)}...")

        // Do not silently discard unless Android itself provides no usable content
        if (title.isNullOrBlank() && text.isNullOrBlank()) {
            Log.d("NotificationListener", "Skipping TRULY empty notification from ${sbn.packageName}")
            return
        }

        val appName = try {
            val pm = packageManager
            val ai = pm.getApplicationInfo(sbn.packageName, 0)
            pm.getApplicationLabel(ai).toString()
        } catch (e: Exception) {
            sbn.packageName
        }

        val notificationData = NotificationData(
            packageName = sbn.packageName,
            appName = appName,
            title = title,
            text = text,
            subText = subText,
            sender = sender,
            category = notification.category,
            notificationKey = sbn.key ?: "${sbn.packageName}_${sbn.id}_${sbn.postTime}",
            timestamp = sbn.postTime
        )

        processor.process(notificationData)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // Ignored for MVP
    }
}
