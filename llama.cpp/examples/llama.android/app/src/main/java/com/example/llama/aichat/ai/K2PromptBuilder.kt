package com.example.llama.aichat.ai

object K2PromptBuilder {

    fun buildRelevantUserContext(
        generalContext: String?,
        rules: List<String>,
        appName: String,
        sender: String?,
        title: String?,
        text: String?
    ): String {
        val builder = StringBuilder()
        if (!generalContext.isNullOrBlank()) {
            builder.append("User General Context:\n").append(generalContext.trim()).append("\n\n")
        }

        val contentLower = "${appName.lowercase()} ${sender?.lowercase() ?: ""} ${title?.lowercase() ?: ""} ${text?.lowercase() ?: ""}"
        
        // Include rules that are either general (not person-specific) OR match the current notification content
        val relevantRules = rules.filter { rule ->
            val ruleLower = rule.lowercase().trim()
            val tokens = ruleLower.split(Regex("[^a-zA-Z0-9_]+")).filter { it.length >= 3 }
            val isPersonRule = ruleLower.contains("from ") || ruleLower.contains("message from") || ruleLower.contains("messages from")
            if (!isPersonRule) {
                true // general rule
            } else {
                // Only include if at least one meaningful token matches the notification content
                tokens.any { token -> 
                    token !in setOf("any", "all", "message", "messages", "from", "important", "urgent", "alert") &&
                    contentLower.contains(token)
                }
            }
        }

        if (relevantRules.isNotEmpty()) {
            builder.append("Relevant User Rules:\n")
            relevantRules.forEach { rule ->
                builder.append("- ").append(rule.trim()).append("\n")
            }
        }

        val result = builder.toString().trim()
        return if (result.isEmpty()) "No specific user rules or context provided." else result
    }

    fun buildPrompt(
        userContext: String,
        appName: String,
        packageName: String,
        title: String?,
        text: String?,
        sender: String?,
        category: String?
    ): String {
        val safeApp = appName.ifBlank { "App" }
        val safeSender = sender?.ifBlank { "Unknown" } ?: "Unknown"
        val safeTitle = title?.ifBlank { "N/A" } ?: "N/A"
        val safeText = text?.ifBlank { "N/A" } ?: "N/A"
        val safeCategory = category?.ifBlank { "other" } ?: "other"

        return """
            You are a strict on-device notification classification engine.
            Classify this notification based ONLY on the provided USER CONTEXT and notification content.

            USER CONTEXT:
            $userContext

            NOTIFICATION TO EVALUATE:
            - App: $safeApp
            - Sender: $safeSender
            - Title: $safeTitle
            - Message: $safeText

            STRICT CLASSIFICATION RULES:
            1. DEFAULT TO FALSE: Casual messages (e.g. "Hi", "Hello", memes), sports scores, screenshots, system notices, promotional deals are NOT IMPORTANT ("important": false, "alert": false).
            2. IMPORTANCE: Mark "important": true ONLY if the notification content directly matches the User Context or contains an explicit urgent matter.
            3. ALERT: Mark "alert": true ONLY if immediate sound notification is necessary. Otherwise "alert": false.
            4. SUMMARY: 1 short sentence summarizing ONLY what $safeSender wrote/sent. NEVER invent facts or mention people not in this notification.
            5. REASON: 1 short sentence explaining why it is important or not important.
            6. CATEGORY: One of: personal, work, college, finance, social, delivery, security, promotional, other.

            Return JSON ONLY:
            {
              "important": false,
              "alert": false,
              "summary": "Summary of $safeSender message",
              "reason": "Reason for classification",
              "category": "other"
            }
        """.trimIndent()
    }
}
