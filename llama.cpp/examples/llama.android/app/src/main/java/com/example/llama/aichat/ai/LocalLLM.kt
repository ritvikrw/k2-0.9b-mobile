package com.example.llama.aichat.ai

interface LocalLLM {
    suspend fun generate(prompt: String, maxTokens: Int = 128): String
    suspend fun setSystemPrompt(prompt: String)
}
