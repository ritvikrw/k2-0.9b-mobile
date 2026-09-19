package com.example.llama.aichat

import com.example.llama.aichat.ai.K2PromptBuilder
import com.example.llama.aichat.ai.K2ResponseParser
import org.junit.Assert.*
import org.junit.Test

class K2AiTest {

    @Test
    fun testValidJsonResponseParsing() {
        val json = """
            {
              "important": true,
              "alert": true,
              "summary": "Rahul sent you a Reel",
              "reason": "Matches the user's Instagram Reel rule",
              "category": "social"
            }
        """.trimIndent()

        val analysis = K2ResponseParser.parse(json)
        assertTrue(analysis.important)
        assertTrue(analysis.alert)
        assertEquals("Rahul sent you a Reel", analysis.summary)
        assertEquals("Matches the user's Instagram Reel rule", analysis.reason)
        assertEquals("social", analysis.category)
    }

    @Test
    fun testUnimportantJsonResponseParsing() {
        val json = """
            {
              "important": false,
              "alert": false,
              "summary": "Promotional offer available",
              "reason": "Promotional notification",
              "category": "promotional"
            }
        """.trimIndent()

        val analysis = K2ResponseParser.parse(json)
        assertFalse(analysis.important)
        assertFalse(analysis.alert)
        assertEquals("Promotional offer available", analysis.summary)
        assertEquals("promotional", analysis.category)
    }

    @Test
    fun testInvalidCategoryFallback() {
        val json = """
            {
              "important": true,
              "alert": false,
              "summary": "Some summary",
              "reason": "Some reason",
              "category": "unknown_invalid_category"
            }
        """.trimIndent()

        val analysis = K2ResponseParser.parse(json)
        assertEquals("other", analysis.category)
    }

    @Test
    fun testMalformedJsonFallback() {
        val malformed = "I am an AI and here is your result: { invalid json"
        val analysis = K2ResponseParser.parse(malformed, defaultSummary = "Default message")

        assertFalse(analysis.important)
        assertFalse(analysis.alert)
        assertEquals("Default message", analysis.summary)
        assertEquals("AI response could not be parsed", analysis.reason)
        assertEquals("other", analysis.category)
    }

    @Test
    fun testNullAndEmptyHandling() {
        val analysisNull = K2ResponseParser.parse(null, defaultSummary = "Test Summary")
        assertFalse(analysisNull.important)
        assertEquals("Test Summary", analysisNull.summary)

        val analysisEmpty = K2ResponseParser.parse("", defaultSummary = "Test Summary")
        assertFalse(analysisEmpty.important)
        assertEquals("Test Summary", analysisEmpty.summary)
    }

    @Test
    fun testPromptBuilderFormatting() {
        val userContext = K2PromptBuilder.buildUserContext(
            generalContext = "Waiting for interview result",
            rules = listOf("Messages from parents are important", "Alert me about Reel from Rahul")
        )

        assertTrue(userContext.contains("Waiting for interview result"))
        assertTrue(userContext.contains("Messages from parents are important"))
        assertTrue(userContext.contains("Alert me about Reel from Rahul"))

        val prompt = K2PromptBuilder.buildPrompt(
            userContext = userContext,
            appName = "Instagram",
            packageName = "com.instagram.android",
            title = "Rahul",
            text = "Rahul sent you a reel",
            sender = "Rahul",
            category = "msg"
        )

        assertTrue(prompt.contains("Instagram"))
        assertTrue(prompt.contains("Rahul sent you a reel"))
        assertTrue(prompt.contains("Waiting for interview result"))
    }
}
