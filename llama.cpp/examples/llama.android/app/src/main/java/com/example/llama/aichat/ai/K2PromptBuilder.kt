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
            builder.append("Important User Context:\n").append(generalContext.trim()).append("\n\n")
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
            builder.append("Active User Rules:\n")
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
               "You are an on-device notification classification engine.\n" +
               "Determine if the incoming notification is IMPORTANT strictly based on the provided USER CONTEXT and RULES.\n" +
               "Rules:\n" +
               "1. Set \"important\": true ONLY if the notification content directly matches the User Context or User Rules.\n" +
               "2. If it does not match, set \"important\": false and \"alert\": false.\n" +
               "3. Summarize only what was sent without hallucinating or inventing details.\n" +
               "4. Output a single valid JSON object only.\n" +
               "<|im_end|>\n" +
               "<|im_start|>user\n" +
               "USER CONTEXT & RULES:\n$userContext\n\n" +
               "INCOMING NOTIFICATION:\n" +
               "App: $safeApp\n" +
               "Sender: $safeSender\n" +
               "Title: $safeTitle\n" +
               "Content: $safeText\n\n" +
               "Classify this notification.\n" +
               "Output format:\n" +
               "{\n" +
               "  \"important\": false,\n" +
               "  \"alert\": false,\n" +
               "  \"summary\": \"Summary of notification\",\n" +
               "  \"reason\": \"Reason why it matches or does not match user context/rules\",\n" +
               "  \"category\": \"other\"\n" +
               "}\n" +
               "<|im_end|>\n" +
               "<|im_start|>assistant\n{"
    }
}
