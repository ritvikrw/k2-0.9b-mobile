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
            Analyze the notification content against the USER CONTEXT & RULES and output a JSON decision.

            USER CONTEXT & RULES:
            $userContext

            NOTIFICATION TO CLASSIFY:
            - App: $safeApp ($packageName)
            - Sender: $safeSender
            - Title: $safeTitle
            - Message Body: $safeText
            - Category: $safeCategory

            EVALUATION GUIDELINES:
            1. SUMMARY: Write a concise 1-sentence summary describing the actual message/content. STRICTLY ground it in the actual message text. DO NOT use the literal text "short summary". DO NOT copy rule names into unrelated app notifications.
            2. URGENCY & IMPORTANCE:
               - Read the 'Message Body' carefully.
               - If user rules or context designate a person or topic as important only when urgent (e.g., 'urgent messages from X'), check if the message is actually urgent (needs quick action, emergency, time-sensitive question, call, meeting) vs casual (greetings, memes, casual chatter).
               - Mark "important": true if it matches an important rule, is urgent, or matters to user context. Otherwise "important": false.
            3. ALERT:
               - Set "alert": true ONLY if this notification requires an immediate chime alert (urgent action needed or explicitly requested in rules).
               - Set "alert": false for casual messages, marketing, background updates, or non-urgent notifications.
            4. REASON: 1 short phrase explaining why it was classified as important/unimportant.
            5. CATEGORY: One of: personal, work, college, finance, social, delivery, security, promotional, other.
            6. Output ONLY valid JSON matching the schema below.

            JSON Schema:
            {
              "important": false,
              "alert": false,
              "summary": "Clear summary of this notification",
              "reason": "Reason for decision",
              "category": "other"
            }
        """.trimIndent()
    }
}
