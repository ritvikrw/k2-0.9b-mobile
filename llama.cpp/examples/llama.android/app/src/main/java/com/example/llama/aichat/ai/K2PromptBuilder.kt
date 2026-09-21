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
            builder.append("Context: ").append(generalContext.trim()).append("\n")
        }

        val contentLower = "${appName.lowercase()} ${sender?.lowercase() ?: ""} ${title?.lowercase() ?: ""} ${text?.lowercase() ?: ""}"
        
        val relevantRules = rules.filter { rule ->
            val ruleLower = rule.lowercase().trim()
            val tokens = ruleLower.split(Regex("[^a-zA-Z0-9_]+")).filter { it.length >= 3 }
            val isPersonRule = ruleLower.contains("from ") || ruleLower.contains("message from") || ruleLower.contains("messages from") || ruleLower.contains("msg from")
            if (!isPersonRule) {
                true // general rule
            } else {
                tokens.any { token -> 
                    token !in setOf("any", "all", "message", "messages", "from", "important", "urgent", "alert", "every", "msg", "msgs") &&
                    contentLower.contains(token)
                }
            }
        }

        if (relevantRules.isNotEmpty()) {
            builder.append("Rules:\n")
            relevantRules.forEach { rule ->
                builder.append("- ").append(rule.trim()).append("\n")
            }
        }

        val result = builder.toString().trim()
        return if (result.isEmpty()) "None." else result
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
        val safeSender = sender?.ifBlank { safeApp } ?: safeApp
        val safeTitle = title?.ifBlank { "" } ?: ""
        val safeText = text?.ifBlank { "" } ?: ""

        return "<|im_start|>system\n" +
               "Classify whether an incoming notification is IMPORTANT based on User Context and Rules.\n" +
               "Output valid JSON only: {\"important\": true/false, \"alert\": true/false, \"reason\": \"brief explanation\"}\n" +
               "<|im_end|>\n" +
               "<|im_start|>user\n" +
               "User Context & Rules:\n$userContext\n\n" +
               "Notification: App: $safeApp, Sender: $safeSender, Title: $safeTitle, Content: $safeText\n\n" +
               "Output JSON:\n" +
               "<|im_end|>\n" +
               "<|im_start|>assistant\n"
    }
}

