package com.example.llama.aichat.notification

import android.content.Context
import android.util.Log
import com.example.llama.aichat.ai.K2InferenceManager
import com.example.llama.aichat.ai.K2PromptBuilder
import com.example.llama.aichat.ai.K2ResponseParser
import com.example.llama.aichat.data.NotificationRecord
import com.example.llama.aichat.data.NotificationRepository
import com.example.llama.aichat.data.NotificationRule
import com.example.llama.aichat.data.NotificationRuleRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
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
        // Sequential FIFO queue worker: processes one notification at a time in order
        scope.launch {
            for (data in queue) {
                processSingle(data)
            }
        }

        // Watch for model ready state to process any backlog
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
        val startTime = System.currentTimeMillis()
        try {
            Log.d("NotificationProcessor", "Processing notification from ${data.packageName}: ${data.title}")

            // Deduplication: Only skip if exact identical content arrived within 10 seconds (OS re-post)
            val recentDuplicate = notificationRepository.findRecentDuplicate(
                packageName = data.packageName,
                title = data.title,
                text = data.text,
                sender = data.sender,
                sinceTimestamp = data.timestamp - 10_000L
            )
            if (recentDuplicate != null) {
                Log.d("NotificationProcessor", "Skipping duplicate notification event from ${data.packageName} (matched ID ${recentDuplicate.id})")
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
            val contentLower = "$senderLower $titleLower $textLower $appLower"

            // Content Type Flags
            val isReel = textLower.contains("reel") || titleLower.contains("reel")
            val isCall = textLower.contains("call") || titleLower.contains("call") || textLower.contains("calling") || titleLower.contains("calling")
            val isSocialReaction = textLower.contains("liked your") || textLower.contains("liked a") || textLower.contains("reacted") || textLower.contains("started following") || textLower.contains("commented")

            // 2. Strict Deterministic Rule Matching
            val stopWords = setOf(
                "messages", "message", "from", "any", "all", "every", "is", "are", 
                "important", "alert", "priority", "urgent", "on", "in", "notification", 
                "notifications", "about", "to", "the", "and", "with", "for", "msg", "msgs", "sent", "by"
            )

            var matchingRule: NotificationRule? = null

            for (rule in rules) {
                val ruleLower = rule.text.lowercase().trim()
                val ruleTokens = ruleLower.split(Regex("[^a-zA-Z0-9_]+"))
                    .filter { it.length >= 2 && it !in stopWords }

                // Check target entity (sender / app name / title)
                val senderMatches = senderLower.isNotEmpty() && ruleTokens.isNotEmpty() && ruleTokens.any { token -> senderLower.contains(token) }
                val titleMatches = titleLower.isNotEmpty() && ruleTokens.isNotEmpty() && ruleTokens.any { token -> titleLower.contains(token) }
                val appMatches = ruleTokens.isNotEmpty() && ruleTokens.any { token -> appLower.contains(token) || token.contains(appLower) }
                val packageMatches = ruleTokens.isNotEmpty() && ruleTokens.any { token -> packageLower.contains(token) }

                val entityMatches = senderMatches || titleMatches || appMatches || packageMatches

                if (entityMatches) {
                    // Entity matched! Now evaluate Rule Semantic Constraints:
                    val isSourceWide = ruleLower.contains("everything") || ruleLower.contains("all from") || ruleLower.contains("every notification") || (appMatches && !ruleLower.contains("message") && !ruleLower.contains("reel") && !ruleLower.contains("call"))
                    val isReelRule = ruleLower.contains("reel")
                    val isCallRule = ruleLower.contains("call")
                    val isMessageRule = ruleLower.contains("message") || ruleLower.contains("messages") || ruleLower.contains("msg") || ruleLower.contains("msgs") || ruleLower.contains("chat")

                    if (isSourceWide) {
                        // SOURCE-WIDE RULE: matches ALL notifications from this source (regardless of language or type)
                        matchingRule = rule
                        break
                    } else if (isReelRule) {
                        // PERSON + REEL RULE: strictly requires evidence of a Reel
                        if (isReel) {
                            matchingRule = rule
                            break
                        }
                    } else if (isCallRule) {
                        // PERSON + CALL RULE: strictly requires evidence of a Call
                        if (isCall) {
                            matchingRule = rule
                            break
                        }
                    } else if (isMessageRule) {
                        // PERSON + MESSAGE RULE: strictly requires that it is an actual message (NOT a reel, NOT a call, NOT a like)
                        if (!isReel && !isCall && !isSocialReaction) {
                            matchingRule = rule
                            break
                        }
                    } else {
                        // General entity rule without conflicting constraint
                        matchingRule = rule
                        break
                    }
                }
            }

            var isImportant = false
            var shouldAlert = false
            var decisionReason = "General notification; no matching rule or context"
            var finalSummary = defaultCleanSummary
            var aiCategory = "other"

            if (matchingRule != null) {
                // EXPLICIT RULE MATCHED
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
                    isImportant = true
                    shouldAlert = true
                    decisionReason = "Matches user rule: ${matchingRule.text}"
                }
            } else {
                // NO EXPLICIT RULE MATCHED. Apply triage and semantic context evaluation:

                // A. True OS System UI / Android OS / Screenshot / Battery detection
                val isSystemOrScreenshot = data.packageName == "com.android.systemui" ||
                        data.packageName == "android" ||
                        data.packageName.contains("smartcapture") ||
                        data.packageName.contains("screencapture") ||
                        data.packageName.contains("screenshot") ||
                        titleLower.contains("screenshot") ||
                        textLower.contains("screenshot") ||
                        titleLower.contains("charging") ||
                        titleLower.contains("battery") ||
                        textLower.contains("charging") ||
                        textLower.contains("battery") ||
                        titleLower.contains("usb for") ||
                        textLower.contains("tap for other usb")

                // B. Messaging placeholder count / background sync (without actual message content)
                val isPlaceholderSync = textLower.contains("checking for new messages") ||
                        textLower.contains("searching for new messages") ||
                        textLower.contains("whatsapp web") ||
                        textLower.contains("backup in progress") ||
                        Regex("^\\d+\\s+new\\s+messages?$", RegexOption.IGNORE_CASE).matches(textLower.trim()) ||
                        textLower.trim().equals("new message", ignoreCase = true) ||
                        textLower.trim().equals("new messages", ignoreCase = true)

                // C. Non-Latin / Regional Script (e.g. Telugu, Hindi) without a matching user rule
                val isIndicScript = Regex("[\\u0C00-\\u0C7F\\u0900-\\u097F\\u0B80-\\u0BFF\\u0C80-\\u0CFF\\u0D00-\\u0D7F]").containsMatchIn("${data.title} ${data.text}")

                when {
                    isSystemOrScreenshot -> {
                        isImportant = false
                        shouldAlert = false
                        decisionReason = "System or screenshot notification"
                        finalSummary = if (titleLower.contains("screenshot") || textLower.contains("screenshot")) "Screenshot saved" else defaultCleanSummary
                    }
                    isPlaceholderSync -> {
                        isImportant = false
                        shouldAlert = false
                        decisionReason = "Unlisted conversation placeholder / sync"
                    }
                    isIndicScript -> {
                        // Language filter for unlisted notifications
                        isImportant = false
                        shouldAlert = false
                        decisionReason = "Non-English notification (no matching user rule)"
                    }
                    generalContext.isNotBlank() -> {
                        // User provided natural language context: Let on-device K2 evaluate strictly against context!
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
                        shouldAlert = analysis.alert
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
                id = 0, // Always create a distinct history entry to preserve message history!
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
            val totalLatency = System.currentTimeMillis() - startTime
            Log.d("NotificationProcessor", "Analysis Complete in ${totalLatency}ms: Important=$isImportant, Alert=$shouldAlert, Sender=${data.sender ?: data.appName}, Reason=$decisionReason")

            if (record.important && record.alert) {
                Log.d("NotificationProcessor", "Firing AI alert chime for ${data.sender ?: data.appName}")
                alertManager.triggerAlert(record)
            }

            // Update persistent summary notification
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
