package com.nickpulido.rcrm

import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.generationConfig

object GeminiApiClient {
    // The API key is now securely accessed from BuildConfig.
    private const val API_KEY = BuildConfig.GEMINI_API_KEY

    val generativeModel: GenerativeModel by lazy {
        val config = generationConfig {
            temperature = 0.7f
            // Significant increase to accommodate the "thinking" process of Gemini 2.5 models,
            // which can consume many tokens before providing the final response.
            maxOutputTokens = 8192
        }
        GenerativeModel(
            // Using the requested 'gemini-2.5-flash' model.
            modelName = "gemini-2.5-flash",
            apiKey = API_KEY,
            generationConfig = config
        )
    }
}
