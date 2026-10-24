package com.nickpulido.rcrm

import android.Manifest
import android.app.Activity
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import java.util.Locale
import java.util.TimeZone

data class GoogleSubCalendar(
    val id: Long,
    val displayName: String,
    val accountName: String,
    val color: Int,
    val isPrimary: Boolean,
    val canWrite: Boolean
)

data class SyncedCalendarEvent(
    val eventId: Long,
    val title: String,
    val description: String,
    val startMillis: Long,
    val endMillis: Long,
    val calendarId: Long,
    val calendarDisplayName: String,
    val calendarColor: Int,
    val allDay: Boolean,
    val leadId: String?,
    val accountName: String = "",
    val canWrite: Boolean = true,
    val recurring: Boolean = false,
    val originalInstanceTime: Long? = null,
    val originalEventId: Long? = null,
    val explicitAppointmentId: String? = null,
    val canceled: Boolean = false
)

object GoogleCalendarManager {

    private const val TAG = "GoogleCalendarManager"
    private const val PREFS_NAME = "rcrm_google_calendar"
    private const val KEY_VISIBLE_IDS = "visible_calendar_ids"
    private const val KEY_FILTER_SET = "calendar_filter_set"
    private const val KEY_DEFAULT_ID = "default_calendar_id"
    private const val KEY_PENDING_LEAD = "pending_deep_link_lead_id"
    private const val KEY_HIDE_DUPLICATE_READONLY = "hide_duplicate_holiday_readonly"
    private const val DEFAULT_DURATION_MS = 60L * 60L * 1000L
    private const val REMINDER_MINUTES = 15
    private const val GOOGLE_CALENDAR_PACKAGE = "com.google.android.calendar"
    private const val APPOINTMENT_PROPERTY = "rcrm_appointment_id"
    private val LEAD_LINK = Regex("""rcrm://lead/(\S+)""")

    sealed class AppointmentSyncResult {
        data class Linked(val eventId: Long) : AppointmentSyncResult()
        data object Cleared : AppointmentSyncResult()
        data object Skipped : AppointmentSyncResult()
        data class Failed(val message: String) : AppointmentSyncResult()
    }

    fun hasCalendarPermissions(context: Context): Boolean {
        val readGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED
        val writeGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED
        return readGranted && writeGranted
    }

    fun readStoredEventId(value: Any?): Long? {
        val id = when (value) {
            is Long -> value
            is Int -> value.toLong()
            is Number -> value.toLong()
            is String -> value.toLongOrNull()
            else -> null
        }
        return id?.takeIf { it > 0L }
    }

    fun displayColor(color: Int): Int {
        val rgb = color and 0x00FFFFFF
        if (rgb == 0) return 0xFF4C8DFF.toInt()
        return color or 0xFF000000.toInt()
    }

    fun leadIdFromUri(uri: Uri?): String? {
        if (uri == null) return null
        if (!uri.scheme.equals("rcrm", ignoreCase = true)) return null
        if (!uri.host.equals("lead", ignoreCase = true)) return null
        val segment = uri.lastPathSegment?.trim().orEmpty()
        if (segment.isEmpty() || segment.equals("lead", ignoreCase = true)) return null
        return segment.trimEnd('.', ',', ';', ')', ']')
    }

    fun extractLeadId(description: String?): String? {
        if (description.isNullOrBlank()) return null
        val raw = LEAD_LINK.find(description)?.groupValues?.getOrNull(1) ?: return null
        return raw.trim().trimEnd('.', ',', ';', ')', ']').takeIf { it.isNotEmpty() }
    }

    fun matchEventToLeadName(eventTitle: String, leadNames: List<String>): String? {
        val title = eventTitle.trim()
        if (title.isEmpty()) return null
        val lowerTitle = title.lowercase(Locale.getDefault())
        return leadNames
            .asSequence()
            .map { it.trim() }
            .filter { it.length >= 2 }
            .distinct()
            .sortedByDescending { it.length }
            .firstOrNull { name -> containsName(lowerTitle, name.lowercase(Locale.getDefault())) }
    }

    fun stashPendingLeadId(context: Context, leadId: String) {
        if (leadId.isBlank()) return
        prefs(context).edit { putString(KEY_PENDING_LEAD, leadId) }
    }

    fun consumePendingLeadId(context: Context): String? {
        val stored = prefs(context).getString(KEY_PENDING_LEAD, null)?.takeIf { it.isNotBlank() } ?: return null
        prefs(context).edit { remove(KEY_PENDING_LEAD) }
        return stored
    }

    fun getVisibleCalendarIds(context: Context): Set<Long>? {
        val preferences = prefs(context)
        if (!preferences.getBoolean(KEY_FILTER_SET, false)) return null
        return preferences.getStringSet(KEY_VISIBLE_IDS, emptySet())
            .orEmpty()
            .mapNotNull { it.toLongOrNull() }
            .toSet()
    }

    fun saveVisibleCalendarIds(context: Context, ids: Set<Long>) {
        prefs(context).edit {
            putBoolean(KEY_FILTER_SET, true)
            putStringSet(KEY_VISIBLE_IDS, ids.map { it.toString() }.toSet())
        }
    }

    fun getDefaultCalendarId(context: Context): Long? {
        val preferences = prefs(context)
        if (!preferences.contains(KEY_DEFAULT_ID)) return null
        return preferences.getLong(KEY_DEFAULT_ID, -1L).takeIf { it > 0L }
    }

    fun saveDefaultCalendarId(context: Context, calendarId: Long) {
        if (calendarId <= 0L) return
        prefs(context).edit { putLong(KEY_DEFAULT_ID, calendarId) }
    }

    fun hideDuplicateHolidayCalendars(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_HIDE_DUPLICATE_READONLY, true)
    }

    fun saveHideDuplicateHolidayCalendars(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_HIDE_DUPLICATE_READONLY, enabled) }
    }

    /**
     * Read-only copies of a calendar that already exists on another account
     * (Family, Holidays in United States, and similar shared calendars).
     * One keeper stays visible: the primary writable calendar when there is one.
     */
    fun redundantReadOnlyCalendarIds(calendars: List<GoogleSubCalendar>): Set<Long> {
        val hide = mutableSetOf<Long>()
        calendars
            .groupBy { it.displayName.trim().lowercase(Locale.getDefault()) }
            .values
            .forEach { group ->
                if (group.size < 2) return@forEach
                val keeper = group.minWithOrNull(
                    compareBy<GoogleSubCalendar> { calendarPreferenceRank(it) }
                        .thenBy { it.accountName.trim().lowercase(Locale.getDefault()) }
                        .thenBy { it.id }
                ) ?: return@forEach
                val holidayGroup = keeper.displayName.contains("holiday", ignoreCase = true)
                group.forEach { calendar ->
                    if (calendar.id == keeper.id) return@forEach
                    if (!calendar.canWrite || holidayGroup) hide += calendar.id
                }
            }
        return hide
    }

    fun getUserCalendars(context: Context): List<GoogleSubCalendar> {
        if (!hasCalendarPermissions(context)) return emptyList()
        val fullProjection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.CALENDAR_COLOR,
            CalendarContract.Calendars.IS_PRIMARY,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL
        )
        val basicProjection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.CALENDAR_COLOR,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL
        )
        return try {
            queryCalendars(context, fullProjection, includePrimary = true)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Primary calendar column unavailable", e)
            try {
                queryCalendars(context, basicProjection, includePrimary = false)
            } catch (fallback: Exception) {
                Log.e(TAG, "Failed to read calendars", fallback)
                emptyList()
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Calendar read permission missing", e)
            emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read calendars", e)
            emptyList()
        }
    }

    fun getEventsInRange(
        context: Context,
        startMillis: Long,
        endMillis: Long,
        selectedCalendarIds: Set<Long>? = null
    ): List<SyncedCalendarEvent> {
        if (!hasCalendarPermissions(context)) return emptyList()
        if (endMillis <= startMillis) return emptyList()

        val calendars = getUserCalendars(context)
        val calendarsById = calendars.associateBy { it.id }
        val effectiveIds = effectiveCalendarIds(context, calendars, selectedCalendarIds)
        if (effectiveIds != null && effectiveIds.isEmpty()) return emptyList()

        val selection: String?
        val selectionArgs: Array<String>?
        if (effectiveIds == null) {
            selection = null
            selectionArgs = null
        } else {
            val placeholders = effectiveIds.joinToString(",") { "?" }
            selection = "${CalendarContract.Instances.CALENDAR_ID} IN ($placeholders)"
            selectionArgs = effectiveIds.map { it.toString() }.toTypedArray()
        }

        // Instances expands recurring events only when the window is appended as two path ids.
        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, startMillis)
        ContentUris.appendId(builder, endMillis)

        val uri = builder.build()
        return try {
            queryInstances(context, uri, instanceProjection(extended = true), selection, selectionArgs, calendarsById)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Extended instance columns unavailable", e)
            try {
                queryInstances(context, uri, instanceProjection(extended = false), selection, selectionArgs, calendarsById)
            } catch (fallback: Exception) {
                Log.e(TAG, "Failed to read events", fallback)
                emptyList()
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Calendar read permission missing", e)
            emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read events", e)
            emptyList()
        }
    }

    fun lookupEvent(context: Context, eventId: Long): SyncedCalendarEvent? {
        if (!hasCalendarPermissions(context) || eventId <= 0L) return null
        val calendarsById = getUserCalendars(context).associateBy { it.id }
        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DESCRIPTION,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.CALENDAR_ID,
            CalendarContract.Events.ALL_DAY,
            CalendarContract.Events.RRULE,
            CalendarContract.Events.ORIGINAL_INSTANCE_TIME,
            CalendarContract.Events.ORIGINAL_ID,
            CalendarContract.Events.STATUS
        )
        return try {
            context.contentResolver.query(
                ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
                projection,
                null,
                null,
                null
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                readEventRow(cursor, calendarsById, fromInstances = false)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't read calendar event $eventId", e)
            null
        }
    }

    fun syncCrmAppointment(
        context: Context,
        leadId: String,
        leadName: String,
        phone: String?,
        startMillis: Long?,
        location: String?,
        existingEventId: Long?,
        appointmentId: String? = null,
        allowInsert: Boolean = true,
        soleAppointmentForLead: Boolean = false
    ): AppointmentSyncResult {
        if (!hasCalendarPermissions(context) || leadId.isBlank()) {
            return AppointmentSyncResult.Skipped
        }
        if (startMillis == null || startMillis <= 0L) {
            return clearLinkedEvent(
                context,
                existingEventId,
                leadId,
                appointmentId,
                soleAppointmentForLead
            )
        }
        val preferred = existingEventId?.takeIf { it > 0L }?.let { lookupEvent(context, it) }
        if (preferred != null && linkedToAppointment(preferred, leadId, appointmentId, soleAppointmentForLead)) {
            val preferredResult = updateLinkedEvent(
                context, preferred, leadId, leadName, phone, startMillis, location, appointmentId
            )
            if (preferredResult is AppointmentSyncResult.Linked || preferredResult is AppointmentSyncResult.Failed) {
                return preferredResult
            }
        }

        val foundId = findExistingLinkedEventId(context, leadId, appointmentId, startMillis, preferred?.eventId)
        if (foundId != null && foundId != preferred?.eventId) {
            val found = lookupEvent(context, foundId)
            if (found != null && linkedToAppointment(found, leadId, appointmentId, soleAppointmentForLead)) {
                val updated = updateLinkedEvent(
                    context, found, leadId, leadName, phone, startMillis, location, appointmentId
                )
                if (updated is AppointmentSyncResult.Linked || updated is AppointmentSyncResult.Failed) return updated
            }
        }
        if (!allowInsert) {
            return AppointmentSyncResult.Failed(
                "The calendar event could not be found on this device. Tap retry to add it once."
            )
        }
        return insertLinkedEvent(context, leadId, leadName, phone, startMillis, location, appointmentId)
    }

    fun syncCrmAppointmentAsync(
        context: Context,
        leadId: String,
        leadName: String,
        phone: String?,
        startMillis: Long?,
        location: String?,
        existingEventId: Long?,
        appointmentId: String? = null,
        allowInsert: Boolean = true,
        soleAppointmentForLead: Boolean = false,
        onResult: (AppointmentSyncResult) -> Unit
    ) {
        val appContext = context.applicationContext
        if (!hasCalendarPermissions(appContext) || leadId.isBlank()) {
            onResult(AppointmentSyncResult.Skipped)
            return
        }
        Thread {
            val result = try {
                syncCrmAppointment(
                    context = appContext,
                    leadId = leadId,
                    leadName = leadName,
                    phone = phone,
                    startMillis = startMillis,
                    location = location,
                    existingEventId = existingEventId,
                    appointmentId = appointmentId,
                    allowInsert = allowInsert,
                    soleAppointmentForLead = soleAppointmentForLead
                )
            } catch (e: Exception) {
                Log.e(TAG, "Calendar sync failed", e)
                AppointmentSyncResult.Failed(e.message ?: "Calendar sync failed")
            }
            Handler(Looper.getMainLooper()).post { onResult(result) }
        }.start()
    }

    fun markEventCanceled(
        context: Context,
        eventId: Long,
        leadId: String,
        appointmentId: String?,
        soleAppointmentForLead: Boolean = false
    ): AppointmentSyncResult {
        if (!hasCalendarPermissions(context) || eventId <= 0L) return AppointmentSyncResult.Skipped
        val existing = lookupEvent(context, eventId)
            ?: return AppointmentSyncResult.Cleared
        if (!linkedToAppointment(existing, leadId, appointmentId, soleAppointmentForLead)) {
            return AppointmentSyncResult.Failed(
                "That saved event id belongs to a different calendar event on this device, so it was left unchanged."
            )
        }
        if (existing.recurring && existing.originalInstanceTime == null) {
            return AppointmentSyncResult.Failed(
                "This event repeats. Cancel that occurrence in Google Calendar so the rest of the series is left alone."
            )
        }
        if (!existing.canWrite) {
            return AppointmentSyncResult.Failed(
                "That calendar is read-only. The RCRM appointment was saved, and the calendar event was left unchanged."
            )
        }
        return try {
            val values = ContentValues().apply {
                put(CalendarContract.Events.STATUS, CalendarContract.Events.STATUS_CANCELED)
            }
            val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
            val updated = context.contentResolver.update(uri, values, null, null)
            if (updated > 0) AppointmentSyncResult.Linked(eventId) else AppointmentSyncResult.Failed(
                "Couldn't mark the calendar event canceled."
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cancel calendar event $eventId", e)
            AppointmentSyncResult.Failed("Couldn't mark the calendar event canceled.")
        }
    }

    fun deleteEvent(context: Context, eventId: Long): Boolean {
        if (!hasCalendarPermissions(context) || eventId <= 0L) return false
        return try {
            val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
            context.contentResolver.delete(uri, null, null) >= 0
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete calendar event $eventId", e)
            false
        }
    }

    fun openEventInGoogleCalendar(context: Context, eventId: Long, startMillis: Long, endMillis: Long) {
        if (eventId <= 0L) {
            openGoogleCalendarAtTime(context, startMillis)
            return
        }
        val end = if (endMillis > startMillis) endMillis else startMillis + DEFAULT_DURATION_MS
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMillis)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)
        }
        launchCalendarIntent(context, intent)
    }

    fun openGoogleCalendarAtTime(context: Context, timeMillis: Long = System.currentTimeMillis()) {
        val whenMillis = if (timeMillis > 0L) timeMillis else System.currentTimeMillis()
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("content://com.android.calendar/time/$whenMillis")
        }
        launchCalendarIntent(context, intent)
    }

    private fun buildCrmDescription(
        leadName: String,
        phone: String?,
        location: String?,
        leadId: String,
        appointmentId: String?
    ): String {
        return AppointmentAgenda.crmDescription(leadName, phone, location, leadId, appointmentId)
    }

    private fun instanceProjection(extended: Boolean): Array<String> {
        val basic = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.DESCRIPTION,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.CALENDAR_ID,
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
            CalendarContract.Instances.CALENDAR_COLOR,
            CalendarContract.Instances.ALL_DAY
        )
        if (!extended) return basic
        return basic + arrayOf(
            CalendarContract.Instances.RRULE,
            CalendarContract.Instances.ORIGINAL_INSTANCE_TIME,
            CalendarContract.Instances.ORIGINAL_ID,
            CalendarContract.Instances.STATUS
        )
    }

    private fun queryInstances(
        context: Context,
        uri: Uri,
        projection: Array<String>,
        selection: String?,
        selectionArgs: Array<String>?,
        calendarsById: Map<Long, GoogleSubCalendar>
    ): List<SyncedCalendarEvent> {
        return context.contentResolver.query(
            uri,
            projection,
            selection,
            selectionArgs,
            "${CalendarContract.Instances.BEGIN} ASC"
        )?.use { cursor ->
            val events = mutableListOf<SyncedCalendarEvent>()
            while (cursor.moveToNext()) {
                readEventRow(cursor, calendarsById, fromInstances = true)?.let { events += it }
            }
            dedupeSharedEvents(events, calendarsById)
        } ?: emptyList()
    }

    private fun readEventRow(
        cursor: Cursor,
        calendarsById: Map<Long, GoogleSubCalendar>,
        fromInstances: Boolean
    ): SyncedCalendarEvent? {
        val idColumn = if (fromInstances) CalendarContract.Instances.EVENT_ID else CalendarContract.Events._ID
        val startColumn = if (fromInstances) CalendarContract.Instances.BEGIN else CalendarContract.Events.DTSTART
        val endColumn = if (fromInstances) CalendarContract.Instances.END else CalendarContract.Events.DTEND
        val eventId = cursor.longValue(idColumn)
        if (eventId <= 0L) return null
        val description = cursor.stringValue(CalendarContract.Events.DESCRIPTION)
        val start = cursor.longValue(startColumn)
        if (start <= 0L) return null
        val end = cursor.longValue(endColumn).takeIf { it > start } ?: (start + DEFAULT_DURATION_MS)
        val calendarId = cursor.longValue(CalendarContract.Events.CALENDAR_ID)
        val calendar = calendarsById[calendarId]
        val rrule = cursor.stringValue(CalendarContract.Events.RRULE)
        val originalInstance = cursor.longValue(CalendarContract.Events.ORIGINAL_INSTANCE_TIME).takeIf { it > 0L }
        val originalId = cursor.longValue(CalendarContract.Events.ORIGINAL_ID).takeIf { it > 0L }
        val status = cursor.intValue(CalendarContract.Events.STATUS)
        val displayName = if (fromInstances) {
            cursor.stringValue(CalendarContract.Instances.CALENDAR_DISPLAY_NAME)
        } else {
            ""
        }
        return SyncedCalendarEvent(
            eventId = eventId,
            title = cursor.stringValue(CalendarContract.Events.TITLE),
            description = description,
            startMillis = start,
            endMillis = end,
            calendarId = calendarId,
            calendarDisplayName = displayName.ifBlank { calendar?.displayName.orEmpty() },
            calendarColor = displayColor(
                if (fromInstances) cursor.intValue(CalendarContract.Instances.CALENDAR_COLOR) else calendar?.color ?: 0
            ),
            allDay = cursor.intValue(CalendarContract.Events.ALL_DAY) == 1,
            leadId = extractLeadId(description),
            accountName = calendar?.accountName.orEmpty(),
            canWrite = calendar?.canWrite == true,
            recurring = rrule.isNotBlank(),
            originalInstanceTime = originalInstance,
            originalEventId = originalId,
            explicitAppointmentId = AppointmentAgenda.extractAppointmentId(description),
            canceled = status == CalendarContract.Events.STATUS_CANCELED
        )
    }

    private fun linkedToAppointment(
        event: SyncedCalendarEvent,
        leadId: String,
        appointmentId: String?,
        soleAppointmentForLead: Boolean
    ): Boolean {
        return AppointmentAgenda.eventIdentityAllows(
            eventLeadId = event.leadId,
            eventAppointmentId = event.explicitAppointmentId,
            expectedLeadId = leadId,
            expectedAppointmentId = appointmentId,
            soleAppointmentForLead = soleAppointmentForLead,
            recurringMaster = false
        )
    }

    private fun clearLinkedEvent(
        context: Context,
        existingEventId: Long?,
        leadId: String,
        appointmentId: String?,
        soleAppointmentForLead: Boolean
    ): AppointmentSyncResult {
        if (existingEventId == null || existingEventId <= 0L) return AppointmentSyncResult.Skipped
        val existing = lookupEvent(context, existingEventId) ?: return AppointmentSyncResult.Cleared
        if (!linkedToAppointment(existing, leadId, appointmentId, soleAppointmentForLead)) {
            return AppointmentSyncResult.Cleared
        }
        if (existing.recurring && existing.originalInstanceTime == null) {
            return AppointmentSyncResult.Failed(
                "This event repeats. RCRM left the series on the calendar."
            )
        }
        if (!existing.canWrite) {
            return AppointmentSyncResult.Failed(
                "That calendar is read-only. The calendar event was left unchanged."
            )
        }
        return if (deleteEvent(context, existingEventId)) {
            AppointmentSyncResult.Cleared
        } else {
            AppointmentSyncResult.Failed("Couldn't remove the calendar event.")
        }
    }

    private fun updateLinkedEvent(
        context: Context,
        existing: SyncedCalendarEvent,
        leadId: String,
        leadName: String,
        phone: String?,
        startMillis: Long,
        location: String?,
        appointmentId: String?
    ): AppointmentSyncResult {
        if (existing.recurring && existing.originalInstanceTime == null) {
            return AppointmentSyncResult.Failed(
                "This event repeats. Change that occurrence in Google Calendar so the rest of the series is left alone."
            )
        }
        if (!existing.canWrite) {
            return AppointmentSyncResult.Failed(
                "That calendar is read-only. The RCRM appointment was saved, and the calendar event was left unchanged."
            )
        }
        val description = AppointmentAgenda.mergeDescription(
            existing = existing.description,
            leadName = leadName.trim().ifEmpty { "Lead" },
            phone = phone,
            location = location,
            leadId = leadId,
            appointmentId = appointmentId
        )
        val values = ContentValues().apply {
            put(CalendarContract.Events.DESCRIPTION, description)
            put(CalendarContract.Events.EVENT_LOCATION, location?.trim().orEmpty())
            put(CalendarContract.Events.DTSTART, startMillis)
            put(CalendarContract.Events.DTEND, startMillis + DEFAULT_DURATION_MS)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            put(CalendarContract.Events.EVENT_END_TIMEZONE, TimeZone.getDefault().id)
            put(CalendarContract.Events.HAS_ALARM, 1)
        }
        return try {
            val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, existing.eventId)
            val updated = context.contentResolver.update(uri, values, null, null)
            if (updated > 0) {
                ensureReminder(context, existing.eventId)
                rememberAppointmentId(context, existing.eventId, appointmentId)
                AppointmentSyncResult.Linked(existing.eventId)
            } else {
                AppointmentSyncResult.Skipped
            }
        } catch (e: Exception) {
            Log.w(TAG, "Updating calendar event ${existing.eventId} failed", e)
            AppointmentSyncResult.Failed("Couldn't update the calendar event.")
        }
    }

    private fun insertLinkedEvent(
        context: Context,
        leadId: String,
        leadName: String,
        phone: String?,
        startMillis: Long,
        location: String?,
        appointmentId: String?
    ): AppointmentSyncResult {
        val targetCalendarId = getDefaultCalendarId(context)?.takeIf { id ->
            getUserCalendars(context).any { it.id == id && it.canWrite }
        } ?: writableCalendarId(context)
        if (targetCalendarId == null) {
            return AppointmentSyncResult.Failed("No writable calendar is available for this appointment.")
        }
        val safeName = leadName.trim().ifEmpty { "Lead" }
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, targetCalendarId)
            put(CalendarContract.Events.TITLE, "Appointment: $safeName")
            put(
                CalendarContract.Events.DESCRIPTION,
                buildCrmDescription(safeName, phone, location, leadId, appointmentId)
            )
            put(CalendarContract.Events.EVENT_LOCATION, location?.trim().orEmpty())
            put(CalendarContract.Events.DTSTART, startMillis)
            put(CalendarContract.Events.DTEND, startMillis + DEFAULT_DURATION_MS)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            put(CalendarContract.Events.EVENT_END_TIMEZONE, TimeZone.getDefault().id)
            put(CalendarContract.Events.HAS_ALARM, 1)
            put(CalendarContract.Events.STATUS, CalendarContract.Events.STATUS_CONFIRMED)
            put(CalendarContract.Events.AVAILABILITY, CalendarContract.Events.AVAILABILITY_BUSY)
        }
        return try {
            val inserted = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
                ?: return AppointmentSyncResult.Failed("Couldn't add the appointment to Google Calendar.")
            val newId = ContentUris.parseId(inserted)
            if (newId <= 0L) {
                return AppointmentSyncResult.Failed("Couldn't add the appointment to Google Calendar.")
            }
            ensureReminder(context, newId)
            rememberAppointmentId(context, newId, appointmentId)
            AppointmentSyncResult.Linked(newId)
        } catch (e: SecurityException) {
            Log.e(TAG, "Calendar write permission missing", e)
            AppointmentSyncResult.Failed("Calendar permission is needed to save this appointment.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save calendar event", e)
            AppointmentSyncResult.Failed("Couldn't add the appointment to Google Calendar.")
        }
    }

    private fun findExistingLinkedEventId(
        context: Context,
        leadId: String,
        appointmentId: String?,
        aroundMillis: Long,
        ignoreEventId: Long?
    ): Long? {
        val windowStart = aroundMillis - 180L * 24L * 60L * 60L * 1000L
        val windowEnd = aroundMillis + 180L * 24L * 60L * 60L * 1000L
        val candidates = getEventsInRange(context, windowStart, windowEnd, selectedCalendarIds = null)
            .filter { it.eventId != ignoreEventId }
            .map { event ->
                AppointmentAgenda.CalendarEventRef(
                    eventId = event.eventId,
                    title = event.title,
                    startMillis = event.startMillis,
                    endMillis = event.endMillis,
                    allDay = event.allDay,
                    calendarDisplayName = event.calendarDisplayName,
                    accountName = event.accountName,
                    color = event.calendarColor,
                    canWrite = event.canWrite,
                    recurring = event.recurring,
                    originalInstanceTime = event.originalInstanceTime,
                    originalEventId = event.originalEventId,
                    explicitLeadId = event.leadId,
                    explicitAppointmentId = event.explicitAppointmentId,
                    canceled = event.canceled
                )
            }
        return AppointmentAgenda.selectRetryTarget(
            candidates,
            appointmentId,
            leadId,
            preferredEventId = null
        )
    }

    private fun rememberAppointmentId(context: Context, eventId: Long, appointmentId: String?) {
        if (appointmentId.isNullOrBlank() || eventId <= 0L) return
        try {
            val resolver = context.contentResolver
            resolver.delete(
                CalendarContract.ExtendedProperties.CONTENT_URI,
                "${CalendarContract.ExtendedProperties.EVENT_ID}=? AND ${CalendarContract.ExtendedProperties.NAME}=?",
                arrayOf(eventId.toString(), APPOINTMENT_PROPERTY)
            )
            val values = ContentValues().apply {
                put(CalendarContract.ExtendedProperties.EVENT_ID, eventId)
                put(CalendarContract.ExtendedProperties.NAME, APPOINTMENT_PROPERTY)
                put(CalendarContract.ExtendedProperties.VALUE, appointmentId)
            }
            resolver.insert(CalendarContract.ExtendedProperties.CONTENT_URI, values)
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't store appointment id on event $eventId", e)
        }
    }

    private fun ensureReminder(context: Context, eventId: Long) {
        val resolver = context.contentResolver
        try {
            resolver.delete(
                CalendarContract.Reminders.CONTENT_URI,
                "${CalendarContract.Reminders.EVENT_ID}=?",
                arrayOf(eventId.toString())
            )
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't clear old reminders for $eventId", e)
        }
        try {
            val reminder = ContentValues().apply {
                put(CalendarContract.Reminders.EVENT_ID, eventId)
                put(CalendarContract.Reminders.MINUTES, REMINDER_MINUTES)
                put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
            }
            resolver.insert(CalendarContract.Reminders.CONTENT_URI, reminder)
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't attach reminder for $eventId", e)
        }
    }

    private fun effectiveCalendarIds(
        context: Context,
        calendars: List<GoogleSubCalendar>,
        selectedCalendarIds: Set<Long>?
    ): Set<Long>? {
        if (!hideDuplicateHolidayCalendars(context)) return selectedCalendarIds
        val hidden = redundantReadOnlyCalendarIds(calendars)
        if (hidden.isEmpty()) return selectedCalendarIds
        val base = selectedCalendarIds ?: calendars.map { it.id }.toSet()
        return base - hidden
    }

    private fun dedupeSharedEvents(
        events: List<SyncedCalendarEvent>,
        calendars: Map<Long, GoogleSubCalendar>
    ): List<SyncedCalendarEvent> {
        val ranked = events.sortedWith(
            compareBy<SyncedCalendarEvent> { calendarPreferenceRank(calendars[it.calendarId]) }
                .thenBy { calendars[it.calendarId]?.accountName.orEmpty().lowercase(Locale.getDefault()) }
                .thenBy { it.calendarId }
                .thenBy { it.eventId }
        )
        val seen = HashSet<String>(ranked.size)
        val kept = ArrayList<SyncedCalendarEvent>(ranked.size)
        for (event in ranked) {
            val key = "${normalizeEventTitle(event.title)}|${event.startMillis}|${event.endMillis}"
            if (seen.add(key)) kept += event
        }
        return kept.sortedWith(
            compareBy<SyncedCalendarEvent> { it.startMillis }
                .thenBy { it.title.lowercase(Locale.getDefault()) }
        )
    }

    private fun normalizeEventTitle(title: String): String {
        return title.trim().lowercase(Locale.getDefault())
    }

    private fun calendarPreferenceRank(calendar: GoogleSubCalendar?): Int {
        if (calendar == null) return 4
        return when {
            calendar.isPrimary && calendar.canWrite -> 0
            calendar.canWrite -> 1
            calendar.isPrimary -> 2
            else -> 3
        }
    }

    private fun writableCalendarId(context: Context): Long? {
        val calendars = getUserCalendars(context)
        return calendars.firstOrNull { it.isPrimary && it.canWrite }?.id
            ?: calendars.firstOrNull { it.canWrite }?.id
    }

    private fun queryCalendars(
        context: Context,
        projection: Array<String>,
        includePrimary: Boolean
    ): List<GoogleSubCalendar> {
        val calendars = mutableListOf<GoogleSubCalendar>()
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            "${CalendarContract.Calendars.VISIBLE}=1",
            null,
            "${CalendarContract.Calendars.CALENDAR_DISPLAY_NAME} ASC"
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.longValue(CalendarContract.Calendars._ID)
                if (id <= 0L) continue
                val access = cursor.intValue(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL)
                calendars += GoogleSubCalendar(
                    id = id,
                    displayName = cursor.stringValue(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)
                        .ifBlank { "Calendar" },
                    accountName = cursor.stringValue(CalendarContract.Calendars.ACCOUNT_NAME),
                    color = displayColor(cursor.intValue(CalendarContract.Calendars.CALENDAR_COLOR)),
                    isPrimary = includePrimary && cursor.intValue(CalendarContract.Calendars.IS_PRIMARY) == 1,
                    canWrite = access >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR
                )
            }
        }
        return calendars
    }

    private fun launchCalendarIntent(context: Context, intent: Intent) {
        try {
            if (context !is Activity) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val googleIntent = Intent(intent).setPackage(GOOGLE_CALENDAR_PACKAGE)
            val resolved = try {
                googleIntent.resolveActivity(context.packageManager) != null
            } catch (e: Exception) {
                false
            }
            if (resolved) {
                context.startActivity(googleIntent)
            } else {
                context.startActivity(intent)
            }
        } catch (e: Exception) {
            try {
                val fallback = Intent(intent).apply {
                    setPackage(null)
                    if (context !is Activity) {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                }
                context.startActivity(fallback)
            } catch (fallbackError: Exception) {
                Log.e(TAG, "No calendar app available", fallbackError)
                Toast.makeText(
                    context,
                    "Google Calendar isn't available on this device.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun containsName(lowerTitle: String, lowerName: String): Boolean {
        if (lowerName.isEmpty()) return false
        var from = 0
        while (from <= lowerTitle.length - lowerName.length) {
            val index = lowerTitle.indexOf(lowerName, from)
            if (index < 0) return false
            val beforeOk = index == 0 || !lowerTitle[index - 1].isLetterOrDigit()
            val afterIndex = index + lowerName.length
            val afterOk = afterIndex == lowerTitle.length || !lowerTitle[afterIndex].isLetterOrDigit()
            if (beforeOk && afterOk) return true
            from = index + 1
        }
        return false
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun Cursor.stringValue(column: String): String {
        val index = getColumnIndex(column)
        if (index < 0 || isNull(index)) return ""
        return getString(index) ?: ""
    }

    private fun Cursor.longValue(column: String): Long {
        val index = getColumnIndex(column)
        if (index < 0 || isNull(index)) return 0L
        return getLong(index)
    }

    private fun Cursor.intValue(column: String): Int {
        val index = getColumnIndex(column)
        if (index < 0 || isNull(index)) return 0
        return getInt(index)
    }
}
