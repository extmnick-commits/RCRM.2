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

    fun mergeNotesWithoutDuplicates(remote: String, local: String): String {
        if (remote.isBlank()) return local
        if (local.isBlank()) return remote

        val parsedRemote = com.nickpulido.rcrm.domain.ContactActivityTimeline.parseNotes(remote)
        val parsedLocal = com.nickpulido.rcrm.domain.ContactActivityTimeline.parseNotes(local)

        val newFromLocal = mutableListOf<com.nickpulido.rcrm.domain.ParsedNote>()
        for (localNote in parsedLocal) {
            val existsInRemote = parsedRemote.any { remoteNote ->
                if (localNote.eventId != null && remoteNote.eventId != null) {
                    localNote.eventId == remoteNote.eventId
                } else {
                    localNote.content.trim() == remoteNote.content.trim() &&
                        (localNote.date == remoteNote.date || localNote.date == "Unknown date" || remoteNote.date == "Unknown date")
                }
            }
            if (!existsInRemote && localNote.content.isNotBlank()) {
                newFromLocal.add(localNote)
            }
        }

        if (newFromLocal.isEmpty()) {
            return remote
        }

        val prepended = newFromLocal.joinToString("\n\n") { p ->
            if (p.eventId != null) "[${p.date} | eid:${p.eventId}]: ${p.content}"
            else if (p.date == "Unknown date") p.content
            else "[${p.date}]: ${p.content}"
        }
        return "$prepended\n\n$remote"
    }
}