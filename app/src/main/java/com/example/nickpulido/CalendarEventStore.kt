package com.nickpulido.rcrm

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import android.util.Log
import com.nickpulido.rcrm.domain.CalendarEditDraft
import com.nickpulido.rcrm.domain.CalendarEditSnapshot
import com.nickpulido.rcrm.domain.CalendarEventEdit

/** Device calendar access for the deliberately limited, single-event editor. Run on IO. */
object CalendarEventStore {
    enum class SaveResult { SAVED, UNCHANGED, CONFLICT, USE_CALENDAR, FAILED }

    fun read(context: Context, eventId: Long): CalendarEditSnapshot? {
        if (!GoogleCalendarManager.hasCalendarPermissions(context) || eventId <= 0L) return null
        return try {
            val columns = arrayOf(
                CalendarContract.Events._ID, CalendarContract.Events.CALENDAR_ID,
                CalendarContract.Events.TITLE, CalendarContract.Events.DTSTART, CalendarContract.Events.DTEND,
                CalendarContract.Events.EVENT_LOCATION, CalendarContract.Events.DESCRIPTION,
                CalendarContract.Events.RRULE, CalendarContract.Events.RDATE,
                CalendarContract.Events.ORIGINAL_ID, CalendarContract.Events.ORIGINAL_INSTANCE_TIME,
                CalendarContract.Events.ALL_DAY, CalendarContract.Events.HAS_ATTENDEE_DATA,
                CalendarContract.Events.STATUS
            )
            val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
            context.contentResolver.query(uri, columns, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                fun text(column: String): String = cursor.getString(cursor.getColumnIndexOrThrow(column)).orEmpty()
                fun number(column: String): Long = cursor.getLong(cursor.getColumnIndexOrThrow(column))
                val calendarId = number(CalendarContract.Events.CALENDAR_ID)
                val description = text(CalendarContract.Events.DESCRIPTION)
                val editorAccess = context.contentResolver.query(
                    ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI, calendarId),
                    arrayOf(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL), null, null, null
                )?.use { calendar ->
                    calendar.moveToFirst() && calendar.getInt(0) >= CalendarContract.Calendars.CAL_ACCESS_EDITOR
                } ?: false
                // HAS_ATTENDEE_DATA describes completeness, not whether any guests actually exist.
                val hasGuests = number(CalendarContract.Events.HAS_ATTENDEE_DATA) == 0L ||
                    (context.contentResolver.query(CalendarContract.Attendees.CONTENT_URI,
                        arrayOf(CalendarContract.Attendees._ID), "${CalendarContract.Attendees.EVENT_ID} = ?",
                        arrayOf(eventId.toString()), null)?.use { it.moveToFirst() } ?: true)
                CalendarEditSnapshot(
                    eventId, calendarId, text(CalendarContract.Events.TITLE),
                    number(CalendarContract.Events.DTSTART), number(CalendarContract.Events.DTEND),
                    text(CalendarContract.Events.EVENT_LOCATION), description, editorAccess,
                    text(CalendarContract.Events.RRULE).isNotBlank() || text(CalendarContract.Events.RDATE).isNotBlank() ||
                        number(CalendarContract.Events.ORIGINAL_ID) > 0L || number(CalendarContract.Events.ORIGINAL_INSTANCE_TIME) > 0L,
                    number(CalendarContract.Events.ALL_DAY) != 0L,
                    description.contains("rcrm://", ignoreCase = true), hasGuests,
                    number(CalendarContract.Events.STATUS) == CalendarContract.Events.STATUS_CANCELED.toLong()
                )
            }
        } catch (error: Exception) {
            Log.w("CalendarEventStore", "Couldn't read editable event $eventId", error)
            null
        }
    }

    fun save(context: Context, original: CalendarEditSnapshot, draft: CalendarEditDraft): SaveResult {
        if (CalendarEventEdit.validate(draft) != null) return SaveResult.FAILED
        val fresh = read(context, original.eventId) ?: return SaveResult.USE_CALENDAR
        if (CalendarEventEdit.route(fresh) != CalendarEventEdit.Route.IN_APP) return SaveResult.USE_CALENDAR
        if (CalendarEventEdit.alreadyApplied(original, fresh, draft)) return SaveResult.UNCHANGED
        if (!CalendarEventEdit.canSave(original, fresh)) return SaveResult.CONFLICT
        if (draft == CalendarEventEdit.draft(original)) return SaveResult.UNCHANGED
        val values = ContentValues().apply {
            put(CalendarContract.Events.TITLE, draft.title.trim())
            put(CalendarContract.Events.DTSTART, draft.startMillis)
            put(CalendarContract.Events.DTEND, draft.endMillis)
            put(CalendarContract.Events.EVENT_LOCATION, draft.location.trim())
            put(CalendarContract.Events.DESCRIPTION, draft.description)
        }
        // Compare the editable values again in the update, so a concurrent edit is not overwritten.
        val selection = "${CalendarContract.Events.CALENDAR_ID} = ? AND " +
            "COALESCE(${CalendarContract.Events.TITLE}, '') = ? AND ${CalendarContract.Events.DTSTART} = ? AND " +
            "${CalendarContract.Events.DTEND} = ? AND COALESCE(${CalendarContract.Events.EVENT_LOCATION}, '') = ? AND " +
            "COALESCE(${CalendarContract.Events.DESCRIPTION}, '') = ? AND ${CalendarContract.Events.ALL_DAY} = 0 AND " +
            "COALESCE(${CalendarContract.Events.RRULE}, '') = '' AND COALESCE(${CalendarContract.Events.RDATE}, '') = '' AND " +
            "COALESCE(${CalendarContract.Events.ORIGINAL_ID}, 0) = 0 AND " +
            "COALESCE(${CalendarContract.Events.ORIGINAL_INSTANCE_TIME}, 0) = 0 AND " +
            "COALESCE(${CalendarContract.Events.HAS_ATTENDEE_DATA}, 0) != 0 AND " +
            "COALESCE(${CalendarContract.Events.STATUS}, 0) != ${CalendarContract.Events.STATUS_CANCELED}"
        val args = arrayOf(original.calendarId.toString(), original.title, original.startMillis.toString(),
            original.endMillis.toString(), original.location, original.description)
        return try {
            val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, original.eventId)
            if (context.contentResolver.update(uri, values, selection, args) == 1) SaveResult.SAVED else SaveResult.CONFLICT
        } catch (error: Exception) {
            Log.w("CalendarEventStore", "Couldn't update event ${original.eventId}", error)
            SaveResult.FAILED
        }
    }
}
