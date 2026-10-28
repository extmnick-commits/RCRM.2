package com.nickpulido.rcrm

import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import kotlinx.coroutines.CancellationException

object LocalSmsDraft {
    private const val TAG = "LocalSmsDraft"

    suspend fun generate(name: String, notes: String, onDownloading: () -> Unit): String {
        val targetName = name.trim().ifBlank { "there" }
        val firstName = targetName.split(" ").firstOrNull() ?: targetName
        val notesContext = if (notes.isBlank()) "No recent call notes recorded. Write a friendly check-in." else notes.take(6000)
        val prompt = "Draft a short, warm, professional SMS to $firstName for a Primerica agent. " +
            "Use the notes as context without mentioning them. Keep it under two sentences. " +
            "Return only the message; do not invent details.\nNotes:\n$notesContext"

        // Tier 1: On-Device Gemini Nano (if available)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                return generateOnDevice(prompt, onDownloading)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                safeLogW("On-device AI unavailable (${e.message}). Attempting Gemini Cloud API fallback...")
            }
        }

        // Tier 2: Cloud Gemini API
        try {
            return generateCloudFallback(prompt)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            safeLogW("Cloud AI unavailable (${e.message}). Using smart local template fallback.")
        }

        // Tier 3: Smart Local Fallback (Always succeeds offline or without API key)
        return generateLocalFallback(firstName, notes)
    }

    private fun safeLogW(msg: String) {
        try {
            Log.w(TAG, msg)
        } catch (_: Throwable) {
            println("[$TAG] $msg")
        }
    }

    suspend fun generateOnDevice(prompt: String, onDownloading: () -> Unit): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            throw IllegalStateException("On-device AI requires Android 8 or newer.")
        }
        return generateOnSupportedDevice(prompt, onDownloading)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private suspend fun generateOnSupportedDevice(prompt: String, onDownloading: () -> Unit): String {
        val model = Generation.getClient()
        try {
            when (model.checkStatus()) {
                FeatureStatus.AVAILABLE -> Unit
                FeatureStatus.DOWNLOADABLE -> {
                    onDownloading()
                    var completed = false
                    model.download().collect { status ->
                        when (status) {
                            is DownloadStatus.DownloadCompleted -> completed = true
                            is DownloadStatus.DownloadFailed -> throw IllegalStateException("On-device AI download failed.", status.e)
                            else -> Unit
                        }
                    }
                    if (!completed) throw IllegalStateException("On-device AI download did not finish.")
                }
                FeatureStatus.DOWNLOADING -> throw IllegalStateException("On-device AI is downloading.")
                else -> throw IllegalStateException("On-device AI is not available on this phone.")
            }
            val text = model.generateContent(prompt).candidates.firstOrNull()?.text?.trim()
            return text?.takeIf { it.isNotEmpty() }
                ?: throw IllegalStateException("On-device AI returned an empty draft.")
        } finally {
            model.close()
        }
    }

    private suspend fun generateCloudFallback(prompt: String): String {
        val response = GeminiApiClient.generativeModel.generateContent(prompt)
        val text = response.text?.trim()
        return text?.takeIf { it.isNotEmpty() }
            ?: throw IllegalStateException("AI returned an empty draft.")
    }

    private fun generateLocalFallback(firstName: String, notes: String): String {
        val lowerNotes = notes.lowercase()
        return when {
            lowerNotes.contains("quote") || lowerNotes.contains("insurance") || lowerNotes.contains("policy") ->
                "Hi $firstName, following up on our recent chat regarding your coverage options! Let me know if you have any questions."
            lowerNotes.contains("invest") || lowerNotes.contains("rollover") || lowerNotes.contains("account") ->
                "Hi $firstName, following up on our discussion about your financial goals! Let me know when you'd like to review the details."
            lowerNotes.contains("appointment") || lowerNotes.contains("meet") || lowerNotes.contains("schedule") ->
                "Hi $firstName, looking forward to connecting for our upcoming meeting! Please let me know if that time still works best for you."
            else ->
                "Hi $firstName, just following up to see how your day is going! Let me know if you have any questions or when is a good time to connect."
        }
    }
}
