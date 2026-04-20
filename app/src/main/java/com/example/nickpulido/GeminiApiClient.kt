package com.nickpulido.rcrm

import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.generationConfig

object GeminiApiClient {
    private const val API_KEY = BuildConfig.GEMINI_API_KEY

    val generativeModel: GenerativeModel by lazy {
        val config = generationConfig {
            temperature = 0.7f
            // Increased to allow enough room for JSON responses from voice logs
            maxOutputTokens = 1000
        }
        GenerativeModel(
            modelName = "gemini-2.5-flash",
            apiKey = API_KEY,
            generationConfig = config
        )
    }
}