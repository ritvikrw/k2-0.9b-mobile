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
        val safeApp = appName.ifBlank { "App" }
        val safeSender = sender?.ifBlank { "Unknown" } ?: "Unknown"
        val safeTitle = title?.ifBlank { "N/A" } ?: "N/A"
        val safeText = text?.ifBlank { "N/A" } ?: "N/A"
        val safeCategory = category?.ifBlank { "other" } ?: "other"

        return """
            You are an on-device AI notification analyzer.
            Analyze the notification content against the USER CONTEXT & RULES and classify it into JSON.

            USER CONTEXT & RULES:
            $userContext

            NOTIFICATION TO CLASSIFY:
            - App: $safeApp ($packageName)
            - Sender: $safeSender
            - Title: $safeTitle
            - Message: $safeText
            - Category: $safeCategory

            CRITICAL RULES:
            1. SUMMARY: Write a concise 1-sentence summary based STRICTLY on the actual notification content (e.g., "$safeSender sent a message about..."). NEVER use the phrase "short summary" or copy rule names into unrelated notifications.
            2. IMPORTANCE: Mark "important": true if the notification matches a user rule, contains an urgent request, or relates to important user context. Otherwise mark "important": false.
            3. ALERT: Set "alert": true ONLY if the notification requires an immediate chime alert based on user context/rules.
            4. REASON: Explain your decision in 1 short phrase.
            5. CATEGORY: One of: personal, work, college, finance, social, delivery, security, promotional, other.
            6. Output ONLY valid raw JSON with no markdown formatting.

            JSON Schema:
            {
              "important": false,
              "alert": false,
              "summary": "Specific summary of $safeApp notification",
              "reason": "Reason for classification",
              "category": "other"
            }
        """.trimIndent()
    }
}
