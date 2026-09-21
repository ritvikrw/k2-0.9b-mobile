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

            // Content Type & System Flags
            val isReel = textLower.contains("reel") || titleLower.contains("reel")
            val isCall = textLower.contains("call") || titleLower.contains("call") || textLower.contains("calling") || titleLower.contains("calling")
            val isSocialReaction = textLower.contains("liked your") || textLower.contains("liked a") || textLower.contains("reacted") || textLower.contains("started following") || textLower.contains("commented")
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

            // 2. Strict Deterministic Rule Matching
            val stopWords = setOf(
                "messages", "message", "from", "any", "all", "every", "is", "are", 
                "important", "alert", "priority", "urgent", "on", "in", "notification", 
                "notifications", "about", "to", "the", "and", "with", "for", "msg", "msgs", 
                "sent", "by", "if", "its", "it's", "it", "anyone", "someone", "everyone", 
                "related", "relating", "containing", "contains", "having"
            )

            var matchingRule: NotificationRule? = null

            for (rule in rules) {
                val ruleLower = rule.text.lowercase().trim()
                val ruleTokens = ruleLower.split(Regex("[^a-zA-Z0-9_]+"))
                    .filter { it.length >= 2 && it !in stopWords }

                if (ruleTokens.isEmpty()) continue

                // Check target entity (sender / app name / title)
                val senderMatches = senderLower.isNotEmpty() && ruleTokens.any { token -> senderLower.contains(token) }
                val titleMatches = titleLower.isNotEmpty() && ruleTokens.any { token -> titleLower.contains(token) }
                val appMatches = ruleTokens.any { token -> appLower.contains(token) || token.contains(appLower) }
                val packageMatches = ruleTokens.any { token -> packageLower.contains(token) }
                val textMatches = textLower.isNotEmpty() && ruleTokens.any { token -> textLower.contains(token) }

                val hasPersonConstraint = ruleLower.contains("from ") || ruleLower.contains("msg from") || ruleLower.contains("message from") || ruleLower.contains("messages from")
                val isWildcardPerson = ruleLower.contains("from anyone") || ruleLower.contains("from someone") || ruleLower.contains("from everyone") || ruleLower.contains("from all")
                val isSpecificPersonRule = hasPersonConstraint && !isWildcardPerson

                val isReelRule = ruleLower.contains("reel") && !ruleLower.contains("message") && !ruleLower.contains("msg")
                val isCallRule = ruleLower.contains("call") && !ruleLower.contains("message") && !ruleLower.contains("msg")
                val isMessageRule = ruleLower.contains("message") || ruleLower.contains("messages") || ruleLower.contains("msg") || ruleLower.contains("msgs") || ruleLower.contains("chat")

                val matchesTarget: Boolean = if (isSpecificPersonRule) {
                    senderMatches || (titleMatches && !appLower.contains(titleLower))
                } else {
                    senderMatches || titleMatches || appMatches || packageMatches || textMatches
                }

                if (matchesTarget) {
                    val isSourceWide = ruleLower.contains("everything") || ruleLower.contains("all from") || ruleLower.contains("every notification")

                    if (isSourceWide) {
                        matchingRule = rule
                        break
                    } else if (isReelRule) {
                        if (isReel) {
                            matchingRule = rule
                            break
                        }
                    } else if (isCallRule) {
                        if (isCall) {
                            matchingRule = rule
                            break
                        }
                    } else if (isMessageRule) {
                        if (!isReel && !isCall && !isSocialReaction) {
                            matchingRule = rule
                            break
                        }
                    } else {
                        matchingRule = rule
                        break
                    }
                }
            }

            var isImportant = false
            var shouldAlert = false
            var decisionReason = "General notification; no matching rule"
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
                        aiCategory = "important"
                    } else {
                        isImportant = false
                        shouldAlert = false
                        decisionReason = "Notification matching rule: ${matchingRule.text} (requires urgent matter)"
                        aiCategory = "other"
                    }
                } else {
                    isImportant = true
                    shouldAlert = true
                    decisionReason = "Matches user rule: ${matchingRule.text}"
                    aiCategory = "important"
                }
            } else {
                // NO EXPLICIT RULE MATCHED
                if (isSystemOrScreenshot) {
                    decisionReason = "System or screenshot notification"
                    finalSummary = if (titleLower.contains("screenshot") || textLower.contains("screenshot")) "Screenshot saved" else defaultCleanSummary
                } else if (textLower.contains("checking for new messages") || textLower.contains("searching for new messages")) {
                    decisionReason = "Unlisted conversation placeholder / sync"
                } else {
                    decisionReason = "General notification; no matching rule"
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

            val latestByKey = notificationRepository.getLatestByKey(data.notificationKey)
            val recordId = if (isSystemOrScreenshot && latestByKey != null) latestByKey.id else 0L

            val record = NotificationRecord(
                id = recordId, // System status updates in-place; messages create separate history entries!
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
