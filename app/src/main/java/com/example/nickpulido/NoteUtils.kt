package com.nickpulido.rcrm

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object NoteUtils {
    fun getDisplayNote(fullNotes: String): String {
        val latestNote = fullNotes.substringBefore("\n\n").replace(Regex("^\\[.*?\\]: "), "")
        return latestNote.ifEmpty { "No notes available" }
    }

    fun formatNewNote(content: String): String {
        val timestamp = SimpleDateFormat("MMM dd, yyyy hh:mm a", Locale.getDefault()).format(Date())
        return "[$timestamp]: $content"
    }

    fun wrapVoiceLog(originalNotes: String, newVoiceText: String): String {
        return if (originalNotes.isEmpty()) newVoiceText else "$originalNotes\n\n[Voice Log]: $newVoiceText"
    }
}