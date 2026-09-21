package com.example.llama.aichat.ai

import org.json.JSONObject

data class NotificationAnalysis(
    val important: Boolean,
    val alert: Boolean,
    val reason: String,
    val summary: String = "",
    val category: String = "other"
)

object K2ResponseParser {

    fun parse(rawResponse: String?, defaultSummary: String = "Notification received"): NotificationAnalysis {
        if (rawResponse.isNullOrBlank()) return fallback(defaultSummary)

        val fullText = rawResponse.trim()
        val jsonString = if (fullText.startsWith("{")) fullText else "{$fullText"

        val start = jsonString.indexOf('{')
        val end = jsonString.lastIndexOf('}')
        val cleanJson = if (start != -1 && end != -1 && end > start) {
            jsonString.substring(start, end + 1)
        } else {
            jsonString
        }

        return try {
            val json = JSONObject(cleanJson)
            val important = json.optBoolean("important", false)
            // Strict invariant: alert can ONLY be true if important is true
            val rawAlert = json.optBoolean("alert", false)
            val alert = if (important) (rawAlert || true) else false
            val reason = json.optString("reason", if (important) "Matches user rules or context" else "General notification")
            val summary = json.optString("summary", defaultSummary).ifBlank { defaultSummary }
            val category = json.optString("category", "other")

            NotificationAnalysis(
                important = important,
                alert = alert,
                reason = reason,
                summary = summary,
                category = category
            )
        } catch (e: Exception) {
            // Regex fallback for partially malformed JSON
            val importantMatch = Regex(""""important"\s*:\s*(true|false)""", RegexOption.IGNORE_CASE).find(cleanJson)
            val alertMatch = Regex(""""alert"\s*:\s*(true|false)""", RegexOption.IGNORE_CASE).find(cleanJson)
            val reasonMatch = Regex(""""reason"\s*:\s*"([^"\\]*(?:\\.[^"\\]*)*)"""", RegexOption.IGNORE_CASE).find(cleanJson)

            val important = importantMatch?.groupValues?.getOrNull(1)?.toBoolean() ?: false
            val rawAlert = alertMatch?.groupValues?.getOrNull(1)?.toBoolean() ?: false
            val alert = if (important) (rawAlert || true) else false
            val reason = reasonMatch?.groupValues?.getOrNull(1)?.replace("\\\"", "\"")?.trim() ?: if (important) "Matches user rules or context" else "General notification"

            NotificationAnalysis(
                important = important,
                alert = alert,
                reason = reason,
                summary = defaultSummary,
                category = "other"
            )
        }
    }

    private fun fallback(summary: String) = NotificationAnalysis(
        important = false,
        alert = false,
        reason = "Does not match user rules or context",
        summary = summary,
        category = "other"
    )
}
