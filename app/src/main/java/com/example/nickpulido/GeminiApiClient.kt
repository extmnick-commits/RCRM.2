package com.nickpulido.rcrm

import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.generationConfig

object GeminiApiClient {
    // This key is hardcoded for simplicity. For a production app,
    // it's recommended to secure this using the BuildConfig method.
    private const val API_KEY = "AIzaSyBaWZ7b6k0Fs5Tg92bXGazi0U8r0qd8c4Y"

    val generativeModel: GenerativeModel by lazy {
        val config = generationConfig {
            temperature = 0.7f
        }
        GenerativeModel(
            modelName = "gemini-1.5-flash",
            apiKey = API_KEY,
            generationConfig = config
        )
    }
}