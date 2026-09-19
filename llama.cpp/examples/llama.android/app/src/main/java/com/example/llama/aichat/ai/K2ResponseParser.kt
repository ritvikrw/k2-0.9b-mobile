package com.example.llama.aichat.ai

data class NotificationAnalysis(
    val important: Boolean,
    val alert: Boolean,
    val summary: String,
    val reason: String,
    val category: String
)

object K2ResponseParser {
    private val ALLOWED_CATEGORIES = setOf(
        "personal", "work", "college", "finance", "social", "delivery", "security", "promotional", "other"
    )

    private val IMPORTANT_REGEX = Regex(""""important"\s*:\s*(true|false)""", RegexOption.IGNORE_CASE)
    private val ALERT_REGEX = Regex(""""alert"\s*:\s*(true|false)""", RegexOption.IGNORE_CASE)
    private val SUMMARY_REGEX = Regex(""""summary"\s*:\s*"([^"\\]*(?:\\.[^"\\]*)*)"""", RegexOption.IGNORE_CASE)
    private val REASON_REGEX = Regex(""""reason"\s*:\s*"([^"\\]*(?:\\.[^"\\]*)*)"""", RegexOption.IGNORE_CASE)
    private val CATEGORY_REGEX = Regex(""""category"\s*:\s*"([^"\\]*(?:\\.[^"\\]*)*)"""", RegexOption.IGNORE_CASE)

    fun parse(jsonString: String?, defaultSummary: String = "Notification received"): NotificationAnalysis {
        if (jsonString.isNullOrBlank()) return fallback(defaultSummary)

        return try {
            val start = jsonString.indexOf('{')
            val end = jsonString.lastIndexOf('}')
            if (start == -1 || end == -1 || end <= start) return fallback(defaultSummary)

            val cleanJson = jsonString.substring(start, end + 1)

            val importantMatch = IMPORTANT_REGEX.find(cleanJson)
            val alertMatch = ALERT_REGEX.find(cleanJson)
            val summaryMatch = SUMMARY_REGEX.find(cleanJson)
            val reasonMatch = REASON_REGEX.find(cleanJson)
            val categoryMatch = CATEGORY_REGEX.find(cleanJson)

            val important = importantMatch?.groupValues?.getOrNull(1)?.toBoolean() ?: false
            val alert = alertMatch?.groupValues?.getOrNull(1)?.toBoolean() ?: false
            val summary = summaryMatch?.groupValues?.getOrNull(1)?.replace("\\\"", "\"")?.trim()?.ifEmpty { defaultSummary } ?: defaultSummary
            val reason = reasonMatch?.groupValues?.getOrNull(1)?.replace("\\\"", "\"")?.trim() ?: "Analyzed by local AI"
            val rawCategory = categoryMatch?.groupValues?.getOrNull(1)?.lowercase()?.trim() ?: "other"
            val validatedCategory = if (ALLOWED_CATEGORIES.contains(rawCategory)) rawCategory else "other"

            NotificationAnalysis(
                important = important,
                alert = alert,
                summary = summary,
                reason = reason,
                category = validatedCategory
            )
        } catch (e: Exception) {
            fallback(defaultSummary)
        }
    }

    private fun fallback(summary: String) = NotificationAnalysis(
        important = false,
        alert = false,
        summary = summary,
        reason = "AI response could not be parsed",
        category = "other"
    )
}
