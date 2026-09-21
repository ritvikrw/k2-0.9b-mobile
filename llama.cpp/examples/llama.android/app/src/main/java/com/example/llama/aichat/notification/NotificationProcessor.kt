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

            // 1. Retrieve user settings and rules
            val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            val generalContext = prefs.getString("important_context", "")?.trim() ?: ""
            val rules = ruleRepository.getEnabledRules()

            val defaultCleanSummary = when {
                !data.sender.isNullOrBlank() && !data.text.isNullOrBlank() && data.sender != data.appName -> "${data.sender}: ${data.text}"
                !data.text.isNullOrBlank() -> "${data.appName}: ${data.text}"
                !data.title.isNullOrBlank() -> data.title
                else -> "${data.appName} notification"
            }

            val senderLower = data.sender?.lowercase()?.trim() ?: ""
            val titleLower = data.title?.lowercase()?.trim() ?: ""
            val textLower = data.text?.lowercase()?.trim() ?: ""
            val appLower = data.appName.lowercase().trim()
            val packageLower = data.packageName.lowercase().trim()

            // 2. Dynamic Rule Matching against ANY user rule
            val stopWords = setOf(
                "messages", "message", "from", "any", "all", "every", "is", "are", 
                "important", "alert", "priority", "urgent", "on", "in", "notification", 
                "notifications", "about", "to", "the", "and", "with", "for", "msg", "msgs", "sent", "by"
            )

            val matchingRule = rules.firstOrNull { rule ->
                val ruleLower = rule.text.lowercase().trim()
                val ruleTokens = ruleLower.split(Regex("[^a-zA-Z0-9_]+"))
                    .filter { it.length >= 2 && it !in stopWords }

                val sMatch = senderLower.isNotEmpty() && ruleTokens.isNotEmpty() && ruleTokens.any { token -> senderLower.contains(token) }
                val tMatch = titleLower.isNotEmpty() && ruleTokens.isNotEmpty() && ruleTokens.any { token -> titleLower.contains(token) }
                val aMatch = ruleTokens.isNotEmpty() && ruleTokens.any { token -> appLower.contains(token) || token.contains(appLower) }
                val pkgMatch = ruleTokens.isNotEmpty() && ruleTokens.any { token -> packageLower.contains(token) }

                sMatch || tMatch || aMatch || pkgMatch
            }

            var isImportant = false
            var shouldAlert = false
            var decisionReason = "General notification; no matching rule or context"
            var finalSummary = defaultCleanSummary
            var aiCategory = "other"

            if (matchingRule != null) {
                // User rule matched: check if rule specifies conditional urgency
                val ruleLower = matchingRule.text.lowercase()
                val isConditionalUrgency = ruleLower.contains("urgent only") || ruleLower.contains("emergency only")

                if (isConditionalUrgency) {
                    val containsUrgentWord = textLower.contains("urgent") || textLower.contains("emergency") || textLower.contains("asap")
                    if (containsUrgentWord) {
                        isImportant = true
                        shouldAlert = true
                        decisionReason = "Urgent notification matching rule: ${matchingRule.text}"
                    } else {
                        isImportant = false
                        shouldAlert = false
                        decisionReason = "Notification matching rule: ${matchingRule.text} (requires urgent matter)"
                    }
                } else {
                    // Unconditional rule: ALWAYS IMPORTANT & ALERT (reels, calls, messages, regional language)
                    isImportant = true
                    shouldAlert = true
                    decisionReason = "Matches user rule: ${matchingRule.text}"
                }
            } else {
                // NO user rule matched for this sender/app. Apply dynamic evaluation:

                // A. True OS System UI / Background empty sync detection
                val isSystemOrScreenshot = data.packageName == "com.android.systemui" ||
                        titleLower.contains("charging") ||
                        titleLower.contains("battery") ||
                        textLower.contains("checking for new messages") ||
                        textLower.contains("searching for new messages") ||
                        textLower.contains("whatsapp web")

                // B. Non-Latin / Regional Script (e.g. Telugu) without a matching user rule
                val isIndicScript = Regex("[\\u0C00-\\u0C7F\\u0900-\\u097F\\u0B80-\\u0BFF\\u0C80-\\u0CFF\\u0D00-\\u0D7F]").containsMatchIn("${data.title} ${data.text}")

                when {
                    isSystemOrScreenshot -> {
                        isImportant = false
                        shouldAlert = false
                        decisionReason = "System or background notification"
                        finalSummary = if (titleLower.contains("screenshot")) "Screenshot captured" else defaultCleanSummary
                    }
                    isIndicScript -> {
                        isImportant = false
                        shouldAlert = false
                        decisionReason = "Non-English notification (no matching user rule)"
                    }
                    generalContext.isNotBlank() -> {
                        // User provided natural language context: Let on-device K2 evaluate dynamically!
                        val userContextText = K2PromptBuilder.buildRelevantUserContext(
                            generalContext = generalContext,
                            rules = emptyList(),
                            appName = data.appName,
                            sender = data.sender,
                            title = data.title,
                            text = data.text
                        )
                        val prompt = K2PromptBuilder.buildPrompt(
                            userContext = userContextText,
                            appName = data.appName,
                            packageName = data.packageName,
                            title = data.title,
                            text = data.text,
                            sender = data.sender,
                            category = data.category
                        )
                        Log.d("NotificationProcessor", "Invoking K2 AI for evaluation:\n$prompt")
                        val aiResponse = inferenceManager.analyze(prompt)
                        Log.d("NotificationProcessor", "Raw K2 AI Response:\n$aiResponse")

                        val analysis = K2ResponseParser.parse(aiResponse, defaultCleanSummary)
                        aiCategory = analysis.category

                        isImportant = analysis.important
                        shouldAlert = analysis.alert || analysis.important
                        decisionReason = if (analysis.important) {
                            analysis.reason.ifBlank { "Matches user context: $generalContext" }
                        } else {
                            analysis.reason.ifBlank { "General notification; does not match user context" }
                        }
                        if (analysis.summary.isNotBlank() && !analysis.summary.startsWith("Summary of", ignoreCase = true)) {
                            finalSummary = analysis.summary
                        }
                    }
                    else -> {
                        // No rules and no context provided
                        isImportant = false
                        shouldAlert = false
                        decisionReason = "General notification; no matching rule or context"
                    }
                }
            }

            // Check if user has an explicit 'do not alert' rule
            val isExplicitDoNotAlert = rules.any { rule ->
                val rLower = rule.text.lowercase()
                (rLower.contains("do not alert") || rLower.contains("dont alert") || rLower.contains("no alert")) &&
                ((senderLower.isNotEmpty() && rLower.contains(senderLower)) || (titleLower.isNotEmpty() && rLower.contains(titleLower)) || (appLower.isNotEmpty() && rLower.contains(appLower)))
            }
            if (isExplicitDoNotAlert) {
                shouldAlert = false
            }

            // Fallback for summary formatting
            if (finalSummary.isBlank() || finalSummary.startsWith("Summary of", ignoreCase = true)) {
                finalSummary = defaultCleanSummary
            }

            val record = NotificationRecord(
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
                summary = finalSummary,
                reason = decisionReason,
                aiCategory = aiCategory,
                processed = true
            )

            notificationRepository.insert(record)
            Log.d("NotificationProcessor", "AI Analysis Complete: Important=$isImportant, Alert=$shouldAlert, Sender=${data.sender}, Reason=$decisionReason")

            if (record.important && record.alert) {
                Log.d("NotificationProcessor", "Firing alert chime for ${data.sender ?: data.appName}")
                alertManager.triggerAlert(record)
            }

            // Update status bar summary
            try {
                val importantOnes = notificationRepository.getImportantNotificationsSync()
                summaryManager.updateSummary(importantOnes)
            } catch (e: Exception) {
                // Ignore summary update errors
            }

        } catch (e: Exception) {
            Log.e("NotificationProcessor", "Error processing notification from ${data.packageName}", e)
        }
    }
}
