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
            val generalContext = prefs.getString("important_context", "") ?: ""
            val rules = ruleRepository.getEnabledRules()

            // 2. Build tailored context containing only general rules and rules matching this specific notification
            val userContextText = K2PromptBuilder.buildRelevantUserContext(
                generalContext = generalContext,
                rules = rules.map { it.text },
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

            Log.d("NotificationProcessor", "Sending prompt to AI for ${data.sender} (${data.appName}):\n$prompt")
            val aiResponse = inferenceManager.analyze(prompt)
            Log.d("NotificationProcessor", "Raw AI Response for ${data.sender}:\n$aiResponse")

            val defaultCleanSummary = when {
                !data.sender.isNullOrBlank() && !data.text.isNullOrBlank() && data.sender != data.appName -> "${data.sender}: ${data.text}"
                !data.text.isNullOrBlank() -> "${data.appName}: ${data.text}"
                !data.title.isNullOrBlank() -> data.title
                else -> "${data.appName} notification"
            }

            val analysis = if (aiResponse != null) {
                K2ResponseParser.parse(aiResponse, defaultCleanSummary)
            } else {
                K2ResponseParser.parse(null, defaultCleanSummary)
            }

            var isImportant = analysis.important
            var shouldAlert = analysis.alert
            var decisionReason = analysis.reason
            var finalSummary = analysis.summary

            // 3. Deterministic Rule Matching against sender & app
            val stopWords = setOf(
                "messages", "message", "from", "any", "all", "every", "is", "are", 
                "important", "alert", "priority", "urgent", "on", "in", "notification", 
                "notifications", "about", "to", "the", "and", "with", "for", "instagram", "insta", "whatsapp"
            )

            val senderLower = data.sender?.lowercase()?.trim() ?: ""
            val titleLower = data.title?.lowercase()?.trim() ?: ""
            val textLower = data.text?.lowercase()?.trim() ?: ""
            val appLower = data.appName.lowercase().trim()

            val matchingRule = rules.firstOrNull { rule ->
                val ruleLower = rule.text.lowercase().trim()
                val ruleTokens = ruleLower.split(Regex("[^a-zA-Z0-9_]+"))
                    .filter { it.length >= 3 && it !in stopWords }

                val sMatch = senderLower.isNotEmpty() && (
                    ruleTokens.any { token -> senderLower.contains(token) }
                )
                val tMatch = titleLower.isNotEmpty() && (
                    ruleTokens.any { token -> titleLower.contains(token) }
                )
                val aMatch = (ruleLower.contains("from $appLower") || ruleLower.contains("all $appLower")) && ruleTokens.isEmpty()

                (sMatch || tMatch || aMatch)
            }

            // 4. Check General Context (e.g. "if any job related msg from anyone its important")
            val jobKeywords = listOf("job", "opening", "interview", "recruiter", "hiring", "offer", "linkedin", "resume", "cv", "salary", "shortlisted", "referral")
            val contextLower = generalContext.lowercase()
            val matchesGeneralJobContext = (contextLower.contains("job") || contextLower.contains("interview") || contextLower.contains("recruiter")) &&
                    jobKeywords.any { kw -> textLower.contains(kw) || titleLower.contains(kw) }

            // 5. System, sports, and screenshot detection
            val isSystemOrScreenshot = data.packageName == "com.android.systemui" ||
                    data.packageName.contains("screencapture") ||
                    titleLower.contains("screenshot") ||
                    titleLower.contains("charging") ||
                    titleLower.contains("battery")

            val isSportsScore = (appLower.contains("google") || data.packageName.contains("google")) &&
                    (titleLower.contains("vs") || titleLower.contains("match") || textLower.contains("won by") || textLower.contains("wickets") || textLower.contains("score"))

            val isCasualShortGreeting = senderLower.isNotEmpty() &&
                    setOf("hi", "hello", "hey", "hii", "hiii", "yo", "sup", "👋", "👍", "k", "ok").contains(textLower.trim())

            // 6. Apply Decision Rules & Override AI Hallucinations
            if (matchingRule != null) {
                val ruleLower = matchingRule.text.lowercase()
                val isConditionalUrgency = ruleLower.contains("urgent") || ruleLower.contains("emergency") || ruleLower.contains("only")

                if (isConditionalUrgency) {
                    if (isImportant || shouldAlert) {
                        isImportant = true
                        decisionReason = "Urgent message matching rule: ${matchingRule.text}"
                    } else {
                        isImportant = false
                        shouldAlert = false
                        decisionReason = "Casual message (rule requires urgency): ${matchingRule.text}"
                    }
                } else {
                    isImportant = true
                    shouldAlert = ruleLower.contains("alert") || shouldAlert
                    decisionReason = "Matches user rule: ${matchingRule.text}"
                }
            } else if (matchesGeneralJobContext) {
                isImportant = true
                shouldAlert = contextLower.contains("alert") || shouldAlert
                decisionReason = "Matches important context: job-related message"
            } else if (isSystemOrScreenshot) {
                isImportant = false
                shouldAlert = false
                decisionReason = "System / screenshot notification"
                finalSummary = if (titleLower.contains("screenshot")) "Screenshot captured" else defaultCleanSummary
            } else if (isSportsScore) {
                isImportant = false
                shouldAlert = false
                decisionReason = "Sports match update"
                finalSummary = defaultCleanSummary
            } else if (isCasualShortGreeting) {
                isImportant = false
                shouldAlert = false
                decisionReason = "Casual greeting from unlisted contact"
                finalSummary = "${data.sender}: ${data.text}"
            } else if (!isImportant) {
                decisionReason = if (decisionReason.isBlank() || decisionReason.contains("Pranav", ignoreCase = true) || decisionReason.contains("Krishna", ignoreCase = true) || decisionReason.contains("Madhu", ignoreCase = true)) {
                    "General notification; no matching rule or context"
                } else {
                    decisionReason
                }
            } else {
                val mentionedOtherContact = (decisionReason.contains("Krishna", ignoreCase = true) && !senderLower.contains("krishna")) ||
                        (decisionReason.contains("Pranav", ignoreCase = true) && !senderLower.contains("pranav")) ||
                        (decisionReason.contains("Madhu", ignoreCase = true) && !senderLower.contains("madhu"))

                if (mentionedOtherContact) {
                    Log.w("NotificationProcessor", "Discarding hallucinated importance for ${data.sender}: $decisionReason")
                    isImportant = false
                    shouldAlert = false
                    decisionReason = "General notification; no matching rule"
                }
            }

            // 7. Sanitize Summary: Discard hallucinated contact names in summary
            val summaryMentionsOtherContact = (finalSummary.contains("Krishna", ignoreCase = true) && !senderLower.contains("krishna")) ||
                    (finalSummary.contains("Pranav", ignoreCase = true) && !senderLower.contains("pranav")) ||
                    (finalSummary.contains("Madhu", ignoreCase = true) && !senderLower.contains("madhu"))

            if (summaryMentionsOtherContact || finalSummary.isBlank() || finalSummary.startsWith("Summary of", ignoreCase = true)) {
                finalSummary = defaultCleanSummary
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
                summary = finalSummary,
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
