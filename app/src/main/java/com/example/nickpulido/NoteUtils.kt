package com.nickpulido.rcrm

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object NoteUtils {
    fun getDisplayNote(fullNotes: String): String {
        val parsed = com.nickpulido.rcrm.domain.ContactActivityTimeline.parseNotes(fullNotes)
        val newest = parsed.firstOrNull { it.content.isNotBlank() }
        if (newest != null) {
            val firstLine = newest.content.lines().firstOrNull { it.isNotBlank() } ?: newest.content
            return firstLine.trim()
        }
        val fallback = fullNotes.substringBefore("\n\n").replace(Regex("""^\[.*?\]:\s*"""), "")
        return fallback.ifEmpty { "No notes available" }
    }

    fun formatNewNote(content: String, eventId: String? = null): String {
        val timestamp = SimpleDateFormat("MMM dd, yyyy hh:mm a", Locale.US).format(Date())
        return if (eventId.isNullOrBlank()) {
            "[$timestamp]: $content"
        } else {
            "[$timestamp | eid:${eventId.trim()}]: $content"
        }
    }

    fun prependNote(existing: String, content: String, eventId: String? = null): String {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return existing
        val formatted = formatNewNote(trimmed, eventId)
        return if (existing.isBlank()) formatted else "$formatted\n\n$existing"
    }

    fun wrapVoiceLog(originalNotes: String, newVoiceText: String): String {
        return if (originalNotes.isEmpty()) newVoiceText else "$originalNotes\n\n[Voice Log]: $newVoiceText"
    }
}