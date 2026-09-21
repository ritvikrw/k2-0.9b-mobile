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
        val end = jsonString.indexOf('}', start)
        if (start == -1 || end == -1 || end <= start) {
            return fallback(defaultSummary)
        }

        val cleanJson = jsonString.substring(start, end + 1)

        return try {
            val json = JSONObject(cleanJson)
            val important = json.optBoolean("important", false)
            val alert = if (important) json.optBoolean("alert", false) else false
            val reason = json.optString("reason", if (important) "Matches user context" else "General notification")
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
            fallback(defaultSummary)
        }
    }

    private fun fallback(summary: String) = NotificationAnalysis(
        important = false,
        alert = false,
        reason = "General notification; does not match user context",
        summary = summary,
        category = "other"
    )
}
