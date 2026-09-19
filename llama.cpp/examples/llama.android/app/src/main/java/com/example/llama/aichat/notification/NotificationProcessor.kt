package com.example.llama.aichat.notification

import android.content.Context
import android.util.Log
import com.example.llama.aichat.ai.K2InferenceManager
import com.example.llama.aichat.ai.K2PromptBuilder
import com.example.llama.aichat.ai.K2ResponseParser
import com.example.llama.aichat.data.NotificationRecord
import com.example.llama.aichat.data.NotificationRepository
import com.example.llama.aichat.data.NotificationRuleRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class NotificationProcessor(
    private val context: Context,
    private val inferenceManager: K2InferenceManager,
    private val notificationRepository: NotificationRepository,
    private val ruleRepository: NotificationRuleRepository,
    private val summaryManager: NotificationSummaryManager,
    private val alertManager: AIAlertManager
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val queue = Channel<NotificationData>(Channel.UNLIMITED)

    init {
        // Sequential queue worker: processes one notification at a time in FIFO order
        scope.launch {
            for (data in queue) {
                processSingle(data)
            }
        }

        // Watch for model ready state to process backlog
        scope.launch {
            inferenceManager.state.collectLatest { state ->
                if (state == K2InferenceManager.State.READY) {
                    reprocessUnprocessed()
                }
            }
        }
    }

    private suspend fun reprocessUnprocessed() {
        val unprocessed = notificationRepository.getUnprocessedNotifications()
        if (unprocessed.isEmpty()) return

        Log.i("NotificationProcessor", "Reprocessing ${unprocessed.size} unprocessed notifications")
        for (record in unprocessed) {
            val data = NotificationData(
                packageName = record.packageName,
                appName = record.appName,
                title = record.title,
                text = record.text,
                subText = null,
                sender = record.sender,
                category = record.category,
                notificationKey = record.notificationKey,
                timestamp = record.timestamp
            )
            queue.send(data)
        }
    }

    fun process(data: NotificationData) {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("enabled", true)) {
            Log.d("NotificationProcessor", "Processing disabled by user switch")
            return
        }
        queue.trySend(data)
    }

    private suspend fun processSingle(data: NotificationData) {
        try {
            Log.d("NotificationProcessor", "Processing notification from ${data.packageName}: ${data.title}")

            // Deduplication and meaningful change check
            val latest = notificationRepository.getLatestByKey(data.notificationKey)

            // If already processed and content hasn't changed, skip
            if (latest != null && latest.processed && latest.title == data.title && latest.text == data.text) {
                Log.d("NotificationProcessor", "Skipping already processed duplicate key: ${data.notificationKey}")
                return
            }

            // Also check for recent identical notification within 15 seconds (same app, title, sender, text)
            val recentDuplicate = notificationRepository.findRecentDuplicate(
                packageName = data.packageName,
                title = data.title,
                text = data.text,
                sender = data.sender,
                sinceTimestamp = data.timestamp - 15_000L
            )
            if (recentDuplicate != null) {
                Log.d("NotificationProcessor", "Skipping duplicate notification from ${data.packageName} (matched ID ${recentDuplicate.id})")
                return
            }

            // Retrieve general user context and enabled rules
            val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            val generalContext = prefs.getString("important_context", "") ?: ""
            val rules = ruleRepository.getEnabledRules()
            val userContextText = K2PromptBuilder.buildUserContext(generalContext, rules.map { it.text })

            val prompt = K2PromptBuilder.buildPrompt(
                userContext = userContextText,
                appName = data.appName,
                packageName = data.packageName,
                title = data.title,
                text = data.text,
                sender = data.sender,
                category = data.category
            )

            Log.d("NotificationProcessor", "Sending prompt to AI for ${data.sender} (${data.appName}):\n$prompt")
            val aiResponse = inferenceManager.analyze(prompt)
            Log.d("NotificationProcessor", "Raw AI Response for ${data.sender}:\n$aiResponse")

            val analysis = if (aiResponse != null) {
                K2ResponseParser.parse(aiResponse, data.title ?: data.text ?: "Notification")
            } else {
                K2ResponseParser.parse(null, data.title ?: data.text ?: "Notification")
            }

            var isImportant = analysis.important
            var shouldAlert = analysis.alert
            var decisionReason = analysis.reason

            // Rule safety check: Support partial names, first names, and usernames (e.g. 'madhu' matching 'madhu_vasthram' or 'pranav' matching 'Pranav Rw')
            val stopWords = setOf(
                "messages", "message", "from", "any", "all", "every", "is", "are", 
                "important", "alert", "priority", "urgent", "on", "in", "notification", 
                "notifications", "about", "to", "the", "and", "with", "for", "instagram", "insta", "whatsapp"
            )

            val matchingRule = rules.firstOrNull { rule ->
                val ruleLower = rule.text.lowercase().trim()
                val senderLower = data.sender?.lowercase()?.trim() ?: ""
                val titleLower = data.title?.lowercase()?.trim() ?: ""
                val appLower = data.appName.lowercase().trim()

                // Extract meaningful person/subject keywords from rule (e.g., 'madhu', 'pranav')
                val ruleTokens = ruleLower.split(Regex("[^a-zA-Z0-9_]+"))
                    .filter { it.length >= 3 && it !in stopWords }

                val sMatch = senderLower.isNotEmpty() && (
                    ruleLower.contains(senderLower) || 
                    senderLower.contains(ruleLower) ||
                    ruleTokens.any { token -> senderLower.contains(token) }
                )

                val tMatch = titleLower.isNotEmpty() && (
                    ruleLower.contains(titleLower) ||
                    titleLower.contains(ruleLower) ||
                    ruleTokens.any { token -> titleLower.contains(token) }
                )

                // App-level rule matches only when rule specifies all from app with no other specific target person
                val aMatch = (ruleLower.contains("from $appLower") || ruleLower.contains("all $appLower")) && ruleTokens.isEmpty()

                (sMatch || tMatch || aMatch) && (
                    ruleLower.contains("important") || ruleLower.contains("alert") || 
                    ruleLower.contains("priority") || ruleLower.contains("urgent")
                )
            }

            if (matchingRule != null && !isImportant) {
                Log.i("NotificationProcessor", "Explicit rule match enforced: '${matchingRule.text}' for sender '${data.sender}'")
                isImportant = true
                shouldAlert = true
                decisionReason = "Matched rule: ${matchingRule.text}"
            }

            var record = NotificationRecord(
                id = latest?.id ?: 0,
                notificationKey = data.notificationKey,
                packageName = data.packageName,
                appName = data.appName,
                title = data.title,
                text = data.text,
                sender = data.sender,
                category = data.category,
                timestamp = data.timestamp,
                important = isImportant,
                alert = shouldAlert,
                summary = analysis.summary,
                reason = decisionReason,
                aiCategory = analysis.category,
                processed = true
            )

            notificationRepository.insert(record)
            Log.d("NotificationProcessor", "AI Analysis Complete: Important=$isImportant, Sender=${data.sender}, Reason=$decisionReason")

            if (record.important && record.alert) {
                alertManager.triggerAlert(record)
            }

            // Update status bar summary regardless
            try {
                val importantOnes = notificationRepository.getImportantNotificationsSync() // Need a sync or first()
                summaryManager.updateSummary(importantOnes)
            } catch (e: Exception) {
                // Ignore summary update errors
            }

        } catch (e: Exception) {
            Log.e("NotificationProcessor", "Error processing notification from ${data.packageName}", e)
        }
    }
}
