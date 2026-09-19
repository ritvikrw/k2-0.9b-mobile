package com.example.llama.aichat.ai

object K2PromptBuilder {
    fun buildUserContext(generalContext: String?, rules: List<String>): String {
        val builder = StringBuilder()
        if (!generalContext.isNullOrBlank()) {
            builder.append("User General Context:\n").append(generalContext.trim()).append("\n\n")
        }
        if (rules.isNotEmpty()) {
            builder.append("User Defined Rules (MUST FOLLOW):\n")
            rules.forEach { rule ->
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
        return """
            You are an on-device AI notification analyzer.
            Analyze the notification below and determine if it is IMPORTANT or NOT IMPORTANT based on the USER CONTEXT and RULES.

            USER CONTEXT & RULES:
            $userContext

            NOTIFICATION DETAILS:
            App: $appName
            Package: $packageName
            Sender: ${sender?.ifBlank { "Unknown" } ?: "Unknown"}
            Title: ${title?.ifBlank { "N/A" } ?: "N/A"}
            Content: ${text?.ifBlank { "N/A" } ?: "N/A"}
            Category: ${category?.ifBlank { "N/A" } ?: "N/A"}

            DECISION INSTRUCTIONS:
            1. If any User Rule matches the Sender name, App, Title, or Content (e.g. sender is mentioned in a rule as important), you MUST set "important": true and "alert": true.
            2. If the notification is casual chatter, spam, promotional, or battery/system status unrelated to rules, set "important": false and "alert": false.
            3. Return ONLY a valid JSON object. Do NOT use markdown.

            Format:
            {
              "important": true,
              "alert": true,
              "summary": "short summary",
              "reason": "short reason",
              "category": "personal"
            }
        """.trimIndent()
    }
}
