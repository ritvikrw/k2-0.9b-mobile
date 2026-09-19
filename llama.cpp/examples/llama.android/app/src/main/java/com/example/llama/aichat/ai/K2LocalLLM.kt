package com.example.llama.aichat.ai

import com.arm.aichat.InferenceEngine
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.collect
import java.lang.StringBuilder

class K2LocalLLM(private val engine: InferenceEngine) : LocalLLM {

    override suspend fun generate(prompt: String, maxTokens: Int): String {
        val result = StringBuilder()
        engine.sendUserPrompt(prompt, maxTokens).collect { token ->
            result.append(token)
        }
        return result.toString()
    }

    override suspend fun setSystemPrompt(prompt: String) {
        engine.setSystemPrompt(prompt)
    }
}
