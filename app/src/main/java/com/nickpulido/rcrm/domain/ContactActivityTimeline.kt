package com.nickpulido.rcrm.domain

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

data class ParsedNote(
    val date: String,
    val content: String,
    val timestampMillis: Long?,
    val eventId: String? = null
)

data class TimelineItem(
    val id: String,
    val type: ActionType,
    val outcome: String,
    val note: String,
    val timestampMillis: Long?,
    val dateDisplay: String,
    val isActivityRecord: Boolean,
    val eventId: String? = null,
    val stageContext: String? = null
)

object ContactActivityTimeline {

    private val DATE_PATTERNS = listOf(
        "MMM dd, yyyy hh:mm a",
        "MMM dd, yyyy h:mm a",
        "MMM d, yyyy hh:mm a",
        "MMM d, yyyy h:mm a",
        "MMM dd, yyyy, hh:mm a",
        "MMM dd, yyyy, h:mm a",
        "MMM d, yyyy, hh:mm a",
        "MMM d, yyyy, h:mm a",
        "yyyy-MM-dd HH:mm",
        "yyyy-MM-dd",
        "MMM dd, yyyy",
        "MMM d, yyyy"
    )

    private val ENTRY_HEADER_REGEX = Regex("""(?m)^\[([^\]\r\n]+)\]:\s*""")
    private val EVENT_ID_REGEX = Regex("""\b(?:eid|event):([a-zA-Z0-9_\-]+)""")

    fun parseTimestamp(dateStr: String): Long? {
        val trimmed = dateStr.trim()
        if (trimmed.isEmpty()) return null
        for (pattern in DATE_PATTERNS) {
            try {
                val sdfUs = SimpleDateFormat(pattern, Locale.US).apply { isLenient = false }
                val parsed = sdfUs.parse(trimmed)
                if (parsed != null) return parsed.time
            } catch (_: Exception) {
            }
            try {
                val sdfLocal = SimpleDateFormat(pattern, Locale.getDefault()).apply { isLenient = false }
                val parsed = sdfLocal.parse(trimmed)
                if (parsed != null) return parsed.time
            } catch (_: Exception) {
            }
        }
        return null
    }

    fun parseNotes(raw: String): List<ParsedNote> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return emptyList()

        val matches = ENTRY_HEADER_REGEX.findAll(trimmed).toList()
        if (matches.isEmpty()) {
            return listOf(
                ParsedNote(
                    date = "Unknown date",
                    content = trimmed,
                    timestampMillis = null,
                    eventId = null
                )
            )
        }

        val list = mutableListOf<ParsedNote>()

        // Text before the very first [header]:
        val firstMatch = matches.first()
        if (firstMatch.range.first > 0) {
            val prefix = trimmed.substring(0, firstMatch.range.first).trim()
            if (prefix.isNotEmpty()) {
                list.add(
                    ParsedNote(
                        date = "Unknown date",
                        content = prefix,
                        timestampMillis = null,
                        eventId = null
                    )
                )
            }
        }

        for (i in matches.indices) {
            val match = matches[i]
            val headerRaw = match.groupValues[1].trim()
            val contentStart = match.range.last + 1
            val contentEnd = if (i + 1 < matches.size) matches[i + 1].range.first else trimmed.length
            val content = trimmed.substring(contentStart, contentEnd).trim()

            var eventId: String? = null
            val eidMatch = EVENT_ID_REGEX.find(headerRaw)
            if (eidMatch != null) {
                eventId = eidMatch.groupValues[1]
            }

            val cleanedDate = headerRaw
                .replace(Regex("""\|?\s*\b(?:eid|event):[a-zA-Z0-9_\-]+\s*\|?"""), "")
                .trim()
                .ifEmpty { headerRaw }

            val millis = parseTimestamp(cleanedDate)

            list.add(
                ParsedNote(
                    date = cleanedDate,
                    content = content,
                    timestampMillis = millis,
                    eventId = eventId
                )
            )
        }

        return list
    }

    fun formatTimestamp(millis: Long): String {
        return SimpleDateFormat("MMM dd, yyyy, h:mm a", Locale.getDefault()).format(Date(millis))
    }

    fun extractStageContext(outcome: String?, note: String): String? {
        if (!outcome.isNullOrBlank() && outcome.startsWith("Stage Changed: ")) {
            return outcome.removePrefix("Stage Changed: ").trim()
        }
        val match = Regex("""Moved to (.+?) stage""").find(note)
        if (match != null) {
            return match.groupValues[1].trim()
        }
        return null
    }

    fun mergeActivityAndNotes(
        activityRecords: List<ActivityRecord>,
        rawNotes: String
    ): List<TimelineItem> {
        val parsedNotes = parseNotes(rawNotes)
        val activityIds = activityRecords.map { it.id }.toSet()
        val items = mutableListOf<TimelineItem>()

        for (record in activityRecords) {
            val stageCtx = extractStageContext(record.outcome, record.note)
            items.add(
                TimelineItem(
                    id = record.id,
                    type = record.type,
                    outcome = record.outcome.trim().ifEmpty { "Activity" },
                    note = record.note.trim(),
                    timestampMillis = record.timestamp,
                    dateDisplay = formatTimestamp(record.timestamp),
                    isActivityRecord = true,
                    eventId = record.id,
                    stageContext = stageCtx
                )
            )
        }

        parsedNotes.forEachIndexed { index, note ->
            val isDuplicate = if (note.eventId != null) {
                note.eventId in activityIds
            } else {
                activityRecords.any { rec ->
                    rec.note.trim() == note.content.trim() &&
                        note.timestampMillis != null &&
                        abs(rec.timestamp - note.timestampMillis) < 120_000L
                }
            }

            if (!isDuplicate && note.content.isNotBlank()) {
                val isVoice = note.content.startsWith("[Voice Log]", ignoreCase = true)
                val type = if (isVoice) ActionType.MESSAGE else ActionType.TO_DO
                val outcome = if (isVoice) "Voice Note" else "Note"
                val stageCtx = extractStageContext(null, note.content)
                val cleanedNote = if (isVoice) {
                    note.content.replace(Regex("""^\[Voice Log\]:?\s*""", RegexOption.IGNORE_CASE), "")
                } else {
                    note.content
                }

                items.add(
                    TimelineItem(
                        id = "note_${index}_${note.timestampMillis ?: index}",
                        type = type,
                        outcome = outcome,
                        note = cleanedNote,
                        timestampMillis = note.timestampMillis,
                        dateDisplay = note.date,
                        isActivityRecord = false,
                        eventId = note.eventId,
                        stageContext = stageCtx
                    )
                )
            }
        }

        // Sort newest first
        return items.sortedWith { a, b ->
            val timeA = a.timestampMillis
            val timeB = b.timestampMillis
            when {
                timeA != null && timeB != null -> timeB.compareTo(timeA)
                timeA != null -> -1
                timeB != null -> 1
                else -> 0
            }
        }
    }
}
