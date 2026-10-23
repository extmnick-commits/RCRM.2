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
    val accountName: String = ""
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
    private val LEAD_LINK = Regex("""rcrm://lead/(\S+)""")

    sealed class AppointmentSyncResult {
        data class Linked(val eventId: Long) : AppointmentSyncResult()
        data object Cleared : AppointmentSyncResult()
        data object Skipped : AppointmentSyncResult()
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

        val projection = arrayOf(
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

        return try {
            context.contentResolver.query(
                builder.build(),
                projection,
                selection,
                selectionArgs,
                "${CalendarContract.Instances.BEGIN} ASC"
            )?.use { cursor ->
                val events = mutableListOf<SyncedCalendarEvent>()
                while (cursor.moveToNext()) {
                    val eventId = cursor.longValue(CalendarContract.Instances.EVENT_ID)
                    if (eventId <= 0L) continue
                    val description = cursor.stringValue(CalendarContract.Instances.DESCRIPTION)
                    val start = cursor.longValue(CalendarContract.Instances.BEGIN)
                    val end = cursor.longValue(CalendarContract.Instances.END).takeIf { it > start }
                        ?: (start + DEFAULT_DURATION_MS)
                    val calendarId = cursor.longValue(CalendarContract.Instances.CALENDAR_ID)
                    events += SyncedCalendarEvent(
                        eventId = eventId,
                        title = cursor.stringValue(CalendarContract.Instances.TITLE),
                        description = description,
                        startMillis = start,
                        endMillis = end,
                        calendarId = calendarId,
                        calendarDisplayName = cursor.stringValue(CalendarContract.Instances.CALENDAR_DISPLAY_NAME),
                        calendarColor = displayColor(cursor.intValue(CalendarContract.Instances.CALENDAR_COLOR)),
                        allDay = cursor.intValue(CalendarContract.Instances.ALL_DAY) == 1,
                        leadId = extractLeadId(description),
                        accountName = calendarsById[calendarId]?.accountName.orEmpty()
                    )
                }
                dedupeSharedEvents(events, calendarsById)
            } ?: emptyList()
        } catch (e: SecurityException) {
            Log.e(TAG, "Calendar read permission missing", e)
            emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read events", e)
            emptyList()
        }
    }

    fun upsertCrmAppointment(
        context: Context,
        leadId: String,
        leadName: String,
        startMillis: Long,
        location: String?,
        existingEventId: Long?,
        phone: String? = null,
        calendarId: Long? = null
    ): Long? {
        if (!hasCalendarPermissions(context)) return null
        if (leadId.isBlank() || startMillis <= 0L) return null

        val targetCalendarId = calendarId?.takeIf { it > 0L }
            ?: getDefaultCalendarId(context)?.takeIf { id ->
                getUserCalendars(context).any { it.id == id && it.canWrite }
            }
            ?: writableCalendarId(context)
            ?: return null

        val safeName = leadName.trim().ifEmpty { "Lead" }
        val title = "Appointment: $safeName"
        val description = buildCrmDescription(safeName, phone, location, leadId)
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, targetCalendarId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DESCRIPTION, description)
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
            val resolver = context.contentResolver
            if (existingEventId != null && existingEventId > 0L) {
                val existingUri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, existingEventId)
                val updated = try {
                    resolver.update(existingUri, values, null, null)
                } catch (e: Exception) {
                    Log.w(TAG, "Updating calendar event $existingEventId failed", e)
                    0
                }
                if (updated > 0) {
                    ensureReminder(context, existingEventId)
                    return existingEventId
                }
            }

            val inserted = resolver.insert(CalendarContract.Events.CONTENT_URI, values) ?: return null
            val newId = ContentUris.parseId(inserted)
            if (newId <= 0L) return null
            ensureReminder(context, newId)
            newId
        } catch (e: SecurityException) {
            Log.e(TAG, "Calendar write permission missing", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save calendar event", e)
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
        existingEventId: Long?
    ): AppointmentSyncResult {
        if (!hasCalendarPermissions(context) || leadId.isBlank()) {
            return AppointmentSyncResult.Skipped
        }
        if (startMillis == null || startMillis <= 0L) {
            if (existingEventId != null && existingEventId > 0L) {
                deleteEvent(context, existingEventId)
                return AppointmentSyncResult.Cleared
            }
            return AppointmentSyncResult.Skipped
        }
        val eventId = upsertCrmAppointment(
            context = context,
            leadId = leadId,
            leadName = leadName,
            startMillis = startMillis,
            location = location,
            existingEventId = existingEventId,
            phone = phone
        )
        return if (eventId != null) AppointmentSyncResult.Linked(eventId) else AppointmentSyncResult.Skipped
    }

    fun syncCrmAppointmentAsync(
        context: Context,
        leadId: String,
        leadName: String,
        phone: String?,
        startMillis: Long?,
        location: String?,
        existingEventId: Long?,
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
                    existingEventId = existingEventId
                )
            } catch (e: Exception) {
                Log.e(TAG, "Calendar sync failed", e)
                AppointmentSyncResult.Skipped
            }
            Handler(Looper.getMainLooper()).post { onResult(result) }
        }.start()
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

    private fun buildCrmDescription(leadName: String, phone: String?, location: String?, leadId: String): String {
        val lines = mutableListOf("Appointment with $leadName")
        if (!phone.isNullOrBlank()) lines += "Phone: ${phone.trim()}"
        if (!location.isNullOrBlank()) lines += "Location: ${location.trim()}"
        return lines.joinToString("\n") + "\n\n---\nOpen Lead in RCRM: rcrm://lead/$leadId"
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
