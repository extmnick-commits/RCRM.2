package com.nickpulido.rcrm

import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.generationConfig

object GeminiApiClient {
    // The API key is now securely accessed from BuildConfig.
    private const val API_KEY = BuildConfig.GEMINI_API_KEY

    val generativeModel: GenerativeModel by lazy {
        val config = generationConfig {
            temperature = 0.7f
            // The expected output is a relatively small JSON object.
            // A smaller token limit is safer and more efficient for this task.
            maxOutputTokens = 2048
        }
        GenerativeModel(
            // Using the stable 2.5 flash model
            modelName = "gemini-2.5-flash",
            apiKey = API_KEY,
            generationConfig = config
        )
    }
}
