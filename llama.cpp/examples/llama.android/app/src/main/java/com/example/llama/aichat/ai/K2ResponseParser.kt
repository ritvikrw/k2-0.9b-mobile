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

        // 1. Try finding JSON block
        val start = fullText.indexOf('{')
        val end = fullText.lastIndexOf('}')
        if (start != -1 && end != -1 && end > start) {
            val jsonCandidate = fullText.substring(start, end + 1)
            try {
                val json = JSONObject(jsonCandidate)
                val important = json.optBoolean("important", false)
                val alert = if (important) json.optBoolean("alert", false) else false
                val reason = json.optString("reason", if (important) "Matches user context" else "General notification")
                val summary = json.optString("summary", defaultSummary).ifBlank { defaultSummary }
                val category = json.optString("category", if (important) "important" else "other")

                return NotificationAnalysis(
                    important = important,
                    alert = alert,
                    reason = reason,
                    summary = summary,
                    category = category
                )
            } catch (e: Exception) {
                // fallback to key-value inspection
            }
        }

        // 2. Resilient text parsing for key-value outputs
        val lower = fullText.lowercase()
        val hasImportantTrue = lower.contains("\"important\": true") || lower.contains("\"important\":true") || 
                lower.contains("important: true") || lower.contains("important:true")
        val hasImportantFalse = lower.contains("\"important\": false") || lower.contains("\"important\":false") || 
                lower.contains("important: false") || lower.contains("important:false")

        if (hasImportantTrue && !hasImportantFalse) {
            val hasAlertTrue = lower.contains("\"alert\": true") || lower.contains("alert: true")
            return NotificationAnalysis(
                important = true,
                alert = hasAlertTrue,
                reason = "Matches user context",
                summary = defaultSummary,
                category = "important"
            )
        }

        return fallback(defaultSummary)
    }

    private fun fallback(summary: String) = NotificationAnalysis(
        important = false,
        alert = false,
        reason = "General notification; does not match user context",
        summary = summary,
        category = "other"
    )
}

