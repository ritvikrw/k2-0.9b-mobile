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
            builder.append("Important Topics/Context:\n").append(generalContext.trim()).append("\n\n")
        }

        val contentLower = "${appName.lowercase()} ${sender?.lowercase() ?: ""} ${title?.lowercase() ?: ""} ${text?.lowercase() ?: ""}"
        
        // Include rules that are either general OR match the current notification content
        val relevantRules = rules.filter { rule ->
            val ruleLower = rule.lowercase().trim()
            val tokens = ruleLower.split(Regex("[^a-zA-Z0-9_]+")).filter { it.length >= 3 }
            val isPersonRule = ruleLower.contains("from ") || ruleLower.contains("message from") || ruleLower.contains("messages from")
            if (!isPersonRule) {
                true // general rule
            } else {
                tokens.any { token -> 
                    token !in setOf("any", "all", "message", "messages", "from", "important", "urgent", "alert", "every") &&
                    contentLower.contains(token)
                }
            }
        }

        if (relevantRules.isNotEmpty()) {
            builder.append("Active Rules:\n")
            relevantRules.forEach { rule ->
                builder.append("- ").append(rule.trim()).append("\n")
            }
        }

        val result = builder.toString().trim()
        return if (result.isEmpty()) "None specified." else result
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
               "You are an on-device notification classification engine. Respond with a valid JSON object only.\n" +
               "<|im_end|>\n" +
               "<|im_start|>user\n" +
               "User Context:\n$userContext\n\n" +
               "Notification:\n" +
               "App: $safeApp\n" +
               "Sender: $safeSender\n" +
               "Title: $safeTitle\n" +
               "Message: $safeText\n\n" +
               "Evaluate if this notification is IMPORTANT based on User Context.\n" +
               "Output format:\n" +
               "{\n" +
               "  \"important\": false,\n" +
               "  \"alert\": false,\n" +
               "  \"summary\": \"$safeSender: $safeText\",\n" +
               "  \"reason\": \"Reason\",\n" +
               "  \"category\": \"other\"\n" +
               "}\n" +
               "<|im_end|>\n" +
               "<|im_start|>assistant\n{"
    }
}
