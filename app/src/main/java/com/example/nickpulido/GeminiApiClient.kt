package com.nickpulido.rcrm

import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.generationConfig

object GeminiApiClient {
    // The API key is now securely accessed from BuildConfig.
    private const val API_KEY = BuildConfig.GEMINI_API_KEY

    val generativeModel: GenerativeModel by lazy {
        val config = generationConfig {
            temperature = 0.7f
            // The expected output is either a short SMS draft or a small JSON object.
            // Limiting the tokens tightly guarantees you won't overpay for accidental long responses.
            maxOutputTokens = 150
        }
        GenerativeModel(
            // Use gemini-1.5-flash as the standard active model
            modelName = "gemini-1.5-flash",
            apiKey = API_KEY,
            generationConfig = config
        )
    }
}
