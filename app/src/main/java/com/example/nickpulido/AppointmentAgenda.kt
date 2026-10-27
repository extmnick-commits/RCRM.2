package com.nickpulido.rcrm

import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * Pure calendar and appointment rules. UI, Firestore, and the device calendar
 * call into this object so month navigation, filtering, reconciliation, migration,
 * and outcomes can be tested without Android.
 */
object AppointmentAgenda {

    const val FIELD_APPOINTMENTS = "appointments"
    const val SAME_INSTANT_TOLERANCE_MS = 60_000L
    const val CRM_COLOR = 0xFF4C8DFF.toInt()
    const val UP_NEXT_WINDOW_MS = 24L * 60L * 60L * 1000L
    const val UP_NEXT_GRACE_MS = 10L * 60L * 1000L
    const val UP_NEXT_LIMIT = 8

    private const val MINUTE_MS = 60_000L
    private const val SLOT_ROUNDING_MS = 15L * MINUTE_MS

    private val APPOINTMENT_LINK = Regex("""rcrm://appointment/(\S+)""")
    private val LABELED_PHONE = Regex(
        """(?i)\b(?:phone|tel|cell|mobile|call|text)\b[ \t]*[:#-]?[ \t]*(\+?[\d \t().-]{7,22}\d)(?![\d:])"""
    )
    private val LOOSE_PHONE = Regex("""(?<![\w/=])\+?\(?\d[\d \t().-]{8,20}\d(?![\w/:])""")
    private val NOT_A_PHONE_LINE = Regex(
        """(?i)://|,,|#|meeting id|conference id|passcode|access code|\bpin\b|one tap|dial[- ]?in|zoom|meet\.google|teams"""
    )
    private val WEB_URL = Regex("""(?i)https?://[^\s<>"']+""")
    private val MEETING_HOST = Regex(
        """(?i)zoom\.us|meet\.google\.com|teams\.microsoft\.com|webex\.com|gotomeeting\.com"""
    )

    enum class MonthWindow { UPCOMING, ALL }

    enum class EventSource { ALL_EVENTS, CRM_ONLY }

    enum class AppointmentStatus { SCHEDULED, COMPLETED, NO_SHOW, CANCELED }

    enum class Outcome { COMPLETED, NO_SHOW, CANCELED }

    data class AppointmentRecord(
        val id: String,
        val startMillis: Long,
        val location: String?,
        val eventId: Long?,
        val status: AppointmentStatus,
        val originalInstanceTime: Long?,
        val updatedAtMillis: Long
    )

    data class CalendarNav(
        val monthStartMillis: Long,
        val selectedDayMillis: Long?,
        val monthWindow: MonthWindow,
        val source: EventSource
    )

    data class DayCellModel(
        val dayStartMillis: Long,
        val dayOfMonth: Int,
        val dowLabel: String,
        val isToday: Boolean,
        val selected: Boolean
    )

    data class CrmAppointmentRef(
        val leadId: String,
        val ownerId: String?,
        val appointmentId: String,
        val leadName: String,
        val phone: String,
        val location: String?,
        val startMillis: Long,
        val eventId: Long?,
        val status: AppointmentStatus,
        val originalInstanceTime: Long?
    )

    data class CalendarEventRef(
        val eventId: Long,
        val title: String,
        val startMillis: Long,
        val endMillis: Long,
        val allDay: Boolean,
        val calendarDisplayName: String,
        val accountName: String,
        val color: Int,
        val canWrite: Boolean,
        val recurring: Boolean,
        val originalInstanceTime: Long?,
        val originalEventId: Long?,
        val explicitLeadId: String?,
        val explicitAppointmentId: String?,
        val canceled: Boolean,
        val location: String? = null
    )

    data class AgendaEntry(
        val title: String,
        val startMillis: Long,
        val endMillis: Long,
        val allDay: Boolean,
        val color: Int,
        val eventId: Long?,
        val leadId: String?,
        val ownerId: String?,
        val appointmentId: String?,
        val leadName: String?,
        val phone: String?,
        val location: String?,
        val explicitCrm: Boolean,
        val suggestedLeadId: String?,
        val calendarDisplayName: String,
        val accountName: String,
        val canWrite: Boolean,
        val recurring: Boolean,
        val recurringMaster: Boolean,
        val storedStartMillis: Long?,
        val timeDrifted: Boolean,
        val status: AppointmentStatus?
    )

    data class LegacyMirror(
        val appointmentId: String?,
        val startMillis: Long?,
        val location: String?,
        val eventId: Long?
    )

    data class ReminderChange(
        val scheduleKey: String?,
        val scheduleAtMillis: Long?,
        val cancelKeys: List<String>
    )

    sealed class CalendarWritePlan {
        data class UpdateExisting(val eventId: Long) : CalendarWritePlan()
        data object InsertNew : CalendarWritePlan()
        data class Blocked(val message: String) : CalendarWritePlan()
    }

    sealed class ResolvedWrite {
        data class Update(val eventId: Long) : ResolvedWrite()
        data object Insert : ResolvedWrite()
        data class Fail(val message: String) : ResolvedWrite()
    }

    data class OpenTimeSlot(
        val startMillis: Long,
        val endMillis: Long,
        val durationMinutes: Int
    )

    data class BusyBlock(val startMillis: Long, val endMillis: Long)

    enum class VisitOutcome(val label: String) {
        COMPLETED("Completed ✅"),
        RESCHEDULED("Rescheduled 📅"),
        NO_SHOW("No-Show ⚠️"),
        CANCELED("Canceled 🚫")
    }

    fun startOfDay(millis: Long, zone: TimeZone): Long {
        return Calendar.getInstance(zone).apply {
            timeInMillis = millis
            zero(this)
        }.timeInMillis
    }

    fun startOfMonthContaining(millis: Long, zone: TimeZone): Long {
        return Calendar.getInstance(zone).apply {
            timeInMillis = millis
            set(Calendar.DAY_OF_MONTH, 1)
            zero(this)
        }.timeInMillis
    }

    fun shiftMonth(monthStartMillis: Long, delta: Int, zone: TimeZone): Long {
        return Calendar.getInstance(zone).apply {
            timeInMillis = startOfMonthContaining(monthStartMillis, zone)
            add(Calendar.MONTH, delta)
            set(Calendar.DAY_OF_MONTH, 1)
            zero(this)
        }.timeInMillis
    }

    fun shiftVisibleMonth(state: CalendarNav, delta: Int, zone: TimeZone): CalendarNav {
        return state.copy(
            monthStartMillis = shiftMonth(state.monthStartMillis, delta, zone),
            selectedDayMillis = null
        )
    }

    fun jumpToToday(state: CalendarNav, nowMillis: Long, zone: TimeZone): CalendarNav {
        return state.copy(
            monthStartMillis = startOfMonthContaining(nowMillis, zone),
            selectedDayMillis = null,
            monthWindow = MonthWindow.UPCOMING
        )
    }

    fun buildDayCells(
        monthStartMillis: Long,
        todayStartMillis: Long,
        selectedDayMillis: Long?,
        zone: TimeZone,
        locale: Locale
    ): List<DayCellModel> {
        val cursor = Calendar.getInstance(zone).apply {
            timeInMillis = startOfMonthContaining(monthStartMillis, zone)
            zero(this)
        }
        val days = cursor.getActualMaximum(Calendar.DAY_OF_MONTH)
        val dow = java.text.SimpleDateFormat("EEE", locale)
        return (1..days).map { day ->
            cursor.set(Calendar.DAY_OF_MONTH, day)
            zero(cursor)
            val start = cursor.timeInMillis
            DayCellModel(
                dayStartMillis = start,
                dayOfMonth = day,
                dowLabel = dow.format(cursor.time),
                isToday = start == todayStartMillis,
                selected = start == selectedDayMillis
            )
        }
    }

    /**
     * Where the horizontal day strip should scroll.
     * A month that does not contain today starts at day 1 so the strip cannot
     * stay parked on the previous month's visible week (22–28).
     */
    fun dayStripAnchorIndex(dayStarts: List<Long>, todayStart: Long, selectedDay: Long?): Int {
        if (dayStarts.isEmpty()) return 0
        if (selectedDay != null) {
            val selectedIndex = dayStarts.indexOf(selectedDay)
            if (selectedIndex >= 0) return selectedIndex
        }
        val todayIndex = dayStarts.indexOf(todayStart)
        if (todayIndex >= 0) return todayIndex
        return 0
    }

    fun eventDayStart(startMillis: Long, allDay: Boolean, zone: TimeZone): Long {
        if (!allDay) return startOfDay(startMillis, zone)
        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = startMillis }
        return Calendar.getInstance(zone).apply {
            set(Calendar.YEAR, utc.get(Calendar.YEAR))
            set(Calendar.MONTH, utc.get(Calendar.MONTH))
            set(Calendar.DAY_OF_MONTH, utc.get(Calendar.DAY_OF_MONTH))
            zero(this)
        }.timeInMillis
    }

    fun legacyAppointmentId(leadId: String): String = "legacy:$leadId"

    fun newAppointmentId(): String = UUID.randomUUID().toString()

    fun appointmentReminderKey(appointmentId: String): String = "appt:$appointmentId"

    fun legacyAppointmentReminderKey(phoneOrLeadId: String): String = "appt_$phoneOrLeadId"

    fun ownerCanEdit(ownerId: String?, currentUserId: String?): Boolean {
        if (currentUserId.isNullOrBlank()) return false
        if (ownerId.isNullOrBlank()) return true
        return ownerId == currentUserId
    }

    fun extractAppointmentId(description: String?): String? {
        if (description.isNullOrBlank()) return null
        val raw = APPOINTMENT_LINK.find(description)?.groupValues?.getOrNull(1) ?: return null
        return raw.trim().trimEnd('.', ',', ';', ')', ']').takeIf { it.isNotEmpty() }
    }

    fun crmDescription(
        leadName: String,
        phone: String?,
        location: String?,
        leadId: String,
        appointmentId: String?,
        notes: String? = null
    ): String {
        val lines = mutableListOf("Appointment with $leadName")
        if (!phone.isNullOrBlank()) lines += "Phone: ${phone.trim()}"
        if (!location.isNullOrBlank()) lines += "Location: ${location.trim()}"
        if (!notes.isNullOrBlank()) {
            lines += ""
            lines += "Notes:"
            lines += notes.trim()
        }
        lines += ""
        lines += "---"
        lines += "Open Lead in RCRM: rcrm://lead/$leadId"
        if (!appointmentId.isNullOrBlank()) lines += "rcrm://appointment/$appointmentId"
        return lines.joinToString("\n")
    }

    fun mergeDescription(
        existing: String?,
        leadName: String,
        phone: String?,
        location: String?,
        leadId: String,
        appointmentId: String?,
        notes: String? = null
    ): String {
        if (existing.isNullOrBlank()) {
            return crmDescription(leadName, phone, location, leadId, appointmentId, notes)
        }
        var text = existing.trimEnd()
        if (!notes.isNullOrBlank() && !text.contains(notes.trim())) {
            text += "\n\nNotes:\n${notes.trim()}"
        }
        if (!text.contains("rcrm://lead/$leadId")) {
            text += "\n\nOpen Lead in RCRM: rcrm://lead/$leadId"
        }
        if (!appointmentId.isNullOrBlank() && !text.contains("rcrm://appointment/$appointmentId")) {
            text += "\nrcrm://appointment/$appointmentId"
        }
        return text
    }

    fun parseRecords(raw: Any?, epochMillis: (Any?) -> Long?): List<AppointmentRecord> {
        val list = raw as? List<*> ?: return emptyList()
        val records = list.mapNotNull { entry ->
            val map = entry as? Map<*, *> ?: return@mapNotNull null
            val id = (map["id"] as? String)?.trim().orEmpty()
            val start = map["startAt"]?.let(epochMillis) ?: return@mapNotNull null
            if (id.isEmpty() || start <= 0L) return@mapNotNull null
            AppointmentRecord(
                id = id,
                startMillis = start,
                location = (map["location"] as? String)?.trim()?.takeIf { it.isNotEmpty() },
                eventId = longOrNull(map["eventId"])?.takeIf { it > 0L },
                status = statusOf(map["status"] as? String),
                originalInstanceTime = map["originalInstanceTime"]?.let(epochMillis)?.takeIf { it > 0L },
                updatedAtMillis = map["updatedAt"]?.let(epochMillis) ?: 0L
            )
        }
        return normalize(records)
    }

    fun toFieldMaps(records: List<AppointmentRecord>): List<Map<String, Any?>> {
        return normalize(records).map { record ->
            linkedMapOf(
                "id" to record.id,
                "startAt" to record.startMillis,
                "location" to record.location,
                "eventId" to record.eventId,
                "status" to statusName(record.status),
                "originalInstanceTime" to record.originalInstanceTime,
                "updatedAt" to record.updatedAtMillis
            )
        }
    }

    fun migrateAppointments(
        leadId: String,
        existing: List<AppointmentRecord>,
        legacyStartMillis: Long?,
        legacyLocation: String?,
        legacyEventId: Long?,
        nowMillis: Long
    ): List<AppointmentRecord> {
        val normalized = normalize(existing)
        if (legacyStartMillis == null || legacyStartMillis <= 0L) return normalized
        val legacyId = legacyAppointmentId(leadId)
        if (normalized.any { it.id == legacyId }) return normalized
        if (legacyEventId != null && legacyEventId > 0L && normalized.any { it.eventId == legacyEventId }) {
            return normalized
        }
        if (normalized.any { sameInstant(it.startMillis, legacyStartMillis) }) return normalized
        return normalized + AppointmentRecord(
            id = legacyId,
            startMillis = legacyStartMillis,
            location = legacyLocation?.trim()?.takeIf { it.isNotEmpty() },
            eventId = legacyEventId?.takeIf { it > 0L },
            status = AppointmentStatus.SCHEDULED,
            originalInstanceTime = null,
            updatedAtMillis = nowMillis
        )
    }

    data class SingleEditResult(
        val records: List<AppointmentRecord>,
        val editedId: String?,
        val keptNewer: Boolean = false
    )

    fun applySingleAppointmentEdit(
        leadId: String,
        existing: List<AppointmentRecord>,
        previousStartMillis: Long?,
        previousLocation: String?,
        previousEventId: Long?,
        newStartMillis: Long?,
        newLocation: String?,
        newEventId: Long?,
        nowMillis: Long,
        createdId: String
    ): List<AppointmentRecord> {
        return describeSingleAppointmentEdit(
            leadId = leadId,
            existing = existing,
            previousStartMillis = previousStartMillis,
            previousLocation = previousLocation,
            previousEventId = previousEventId,
            newStartMillis = newStartMillis,
            newLocation = newLocation,
            newEventId = newEventId,
            nowMillis = nowMillis,
            createdId = createdId
        ).records
    }

    /**
     * The appointment the user actually edited. Callers must pass the start,
     * location, and event id from before the local lead map is updated.
     * [baseUpdatedAtMillis] is that record's saved timestamp; a newer write wins.
     */
    fun describeSingleAppointmentEdit(
        leadId: String,
        existing: List<AppointmentRecord>,
        previousStartMillis: Long?,
        previousLocation: String?,
        previousEventId: Long?,
        newStartMillis: Long?,
        newLocation: String?,
        newEventId: Long?,
        nowMillis: Long,
        createdId: String,
        baseUpdatedAtMillis: Long = Long.MAX_VALUE
    ): SingleEditResult {
        val base = migrateAppointments(
            leadId = leadId,
            existing = existing,
            legacyStartMillis = previousStartMillis,
            legacyLocation = previousLocation,
            legacyEventId = previousEventId,
            nowMillis = nowMillis
        )
        val index = findEditedIndex(base, leadId, previousStartMillis, previousEventId)
        if (index >= 0 && keptNewerEdit(base[index], newStartMillis, newLocation, nowMillis, baseUpdatedAtMillis)) {
            return SingleEditResult(base, base[index].id, keptNewer = true)
        }
        if (newStartMillis == null || newStartMillis <= 0L) {
            if (index < 0) return SingleEditResult(base, null)
            val current = base[index]
            if (current.status == AppointmentStatus.CANCELED) {
                return SingleEditResult(base, current.id)
            }
            return SingleEditResult(
                base.replacing(
                    index,
                    current.copy(status = AppointmentStatus.CANCELED, updatedAtMillis = nowMillis)
                ),
                current.id
            )
        }
        val location = newLocation?.trim()?.takeIf { it.isNotEmpty() }
        val eventId = newEventId?.takeIf { it > 0L }
        if (index >= 0) {
            val current = base[index]
            return SingleEditResult(
                base.replacing(
                    index,
                    current.copy(
                        startMillis = newStartMillis,
                        location = location ?: current.location,
                        eventId = eventId ?: current.eventId,
                        status = AppointmentStatus.SCHEDULED,
                        updatedAtMillis = nowMillis
                    )
                ),
                current.id
            )
        }
        val id = createdId.trim().ifEmpty { newAppointmentId() }
        val created = AppointmentRecord(
            id = id,
            startMillis = newStartMillis,
            location = location,
            eventId = eventId,
            status = AppointmentStatus.SCHEDULED,
            originalInstanceTime = null,
            updatedAtMillis = nowMillis
        )
        return SingleEditResult(upsertRecord(base, created), id)
    }

    fun addAppointment(
        records: List<AppointmentRecord>,
        record: AppointmentRecord
    ): List<AppointmentRecord> = upsertRecord(normalize(records), record)

    fun upsertRecord(records: List<AppointmentRecord>, record: AppointmentRecord): List<AppointmentRecord> {
        if (record.id.isBlank() || record.startMillis <= 0L) return normalize(records)
        val normalized = normalize(records)
        val index = normalized.indexOfFirst { it.id == record.id }
        return if (index < 0) normalized + record else normalized.replacing(index, record)
    }

    fun withEventId(
        records: List<AppointmentRecord>,
        appointmentId: String,
        eventId: Long
    ): List<AppointmentRecord>? {
        return withEventLink(records, appointmentId, eventId)
    }

    fun withEventLink(
        records: List<AppointmentRecord>,
        appointmentId: String,
        eventId: Long?
    ): List<AppointmentRecord>? {
        if (appointmentId.isBlank()) return null
        val index = records.indexOfFirst { it.id == appointmentId }
        if (index < 0) return null
        val stored = eventId?.takeIf { it > 0L }
        val current = records[index]
        if (current.eventId == stored) return records
        return records.replacing(index, current.copy(eventId = stored))
    }

    fun applyOutcome(
        records: List<AppointmentRecord>,
        appointmentId: String,
        outcome: Outcome,
        nowMillis: Long
    ): List<AppointmentRecord> {
        val index = records.indexOfFirst { it.id == appointmentId }
        if (index < 0) return records
        val status = when (outcome) {
            Outcome.COMPLETED -> AppointmentStatus.COMPLETED
            Outcome.NO_SHOW -> AppointmentStatus.NO_SHOW
            Outcome.CANCELED -> AppointmentStatus.CANCELED
        }
        val current = records[index]
        if (current.status == status) return records
        return records.replacing(index, current.copy(status = status, updatedAtMillis = nowMillis))
    }

    fun reschedule(
        records: List<AppointmentRecord>,
        appointmentId: String,
        newStartMillis: Long,
        nowMillis: Long,
        rememberInstance: Boolean
    ): List<AppointmentRecord> {
        if (newStartMillis <= 0L) return records
        val index = records.indexOfFirst { it.id == appointmentId }
        if (index < 0) return records
        val current = records[index]
        val original = current.originalInstanceTime
            ?: if (rememberInstance) current.startMillis else null
        return records.replacing(
            index,
            current.copy(
                startMillis = newStartMillis,
                status = AppointmentStatus.SCHEDULED,
                originalInstanceTime = original,
                updatedAtMillis = nowMillis
            )
        )
    }

    fun legacyMirror(records: List<AppointmentRecord>, nowMillis: Long): LegacyMirror {
        val scheduled = records.filter { it.status == AppointmentStatus.SCHEDULED && it.startMillis > 0L }
        val primary = scheduled.filter { it.startMillis >= nowMillis }.minByOrNull { it.startMillis }
            ?: scheduled.maxByOrNull { it.startMillis }
        return LegacyMirror(
            appointmentId = primary?.id,
            startMillis = primary?.startMillis,
            location = primary?.location,
            eventId = primary?.eventId
        )
    }

    fun reminderChange(record: AppointmentRecord, legacyPhoneKey: String?): ReminderChange {
        val key = appointmentReminderKey(record.id)
        val legacy = legacyPhoneKey?.takeIf { it.isNotBlank() && it != key }
        return if (record.status == AppointmentStatus.SCHEDULED && record.startMillis > 0L) {
            ReminderChange(
                scheduleKey = key,
                scheduleAtMillis = record.startMillis,
                cancelKeys = listOfNotNull(legacy)
            )
        } else {
            ReminderChange(
                scheduleKey = null,
                scheduleAtMillis = null,
                cancelKeys = listOfNotNull(key, legacy).distinct()
            )
        }
    }

    fun digestAppointmentStarts(
        records: List<AppointmentRecord>,
        dayStart: Long,
        dayEnd: Long
    ): List<Long> {
        return records
            .filter { it.status == AppointmentStatus.SCHEDULED && it.startMillis in dayStart until dayEnd }
            .map { it.startMillis }
            .sorted()
    }

    fun reconcile(
        appointments: List<CrmAppointmentRef>,
        events: List<CalendarEventRef>,
        suggestedLeadIdsByEventId: Map<Long, String>,
        rangeStart: Long,
        rangeEnd: Long,
        zone: TimeZone
    ): List<AgendaEntry> {
        return applyMonthBoundary(
            matchAgenda(appointments, events, suggestedLeadIdsByEventId),
            rangeStart,
            rangeEnd,
            zone
        )
    }

    /**
     * Pair CRM appointments with calendar events, including events that were
     * fetched outside the visible month so a move can be recognized.
     * The effective time of a match is the calendar event time.
     */
    fun matchAgenda(
        appointments: List<CrmAppointmentRef>,
        events: List<CalendarEventRef>,
        suggestedLeadIdsByEventId: Map<Long, String>
    ): List<AgendaEntry> {
        val used = mutableSetOf<Int>()
        val rows = mutableListOf<AgendaEntry>()
        appointments.forEach { appointment ->
            val match = matchEventIndex(appointment, events, used, appointments)
            if (match != null) {
                used += match
                rows += mergedEntry(appointment, events[match])
            } else {
                rows += crmOnlyEntry(appointment)
            }
        }
        events.forEachIndexed { index, event ->
            if (index in used || event.canceled) return@forEachIndexed
            val suggestion = if (event.explicitLeadId.isNullOrBlank() && event.explicitAppointmentId.isNullOrBlank()) {
                suggestedLeadIdsByEventId[event.eventId]
            } else {
                null
            }
            rows += eventEntry(event, suggestion)
        }
        return rows.sortedWith(compareBy<AgendaEntry> { it.startMillis }.thenBy { it.title.lowercase(Locale.US) })
    }

    fun applyMonthBoundary(
        entries: List<AgendaEntry>,
        rangeStart: Long,
        rangeEnd: Long,
        zone: TimeZone
    ): List<AgendaEntry> {
        return entries
            .filter { entry -> inRange(entry.startMillis, entry.allDay, rangeStart, rangeEnd, zone) }
            .sortedWith(compareBy<AgendaEntry> { it.startMillis }.thenBy { it.title.lowercase(Locale.US) })
    }

    fun visibleEntries(
        entries: List<AgendaEntry>,
        monthWindow: MonthWindow,
        source: EventSource,
        selectedDayMillis: Long?,
        todayStartMillis: Long,
        zone: TimeZone
    ): List<AgendaEntry> {
        return entries.filter { entry ->
            if (source == EventSource.CRM_ONLY && !entry.explicitCrm) return@filter false
            val day = eventDayStart(entry.startMillis, entry.allDay, zone)
            if (selectedDayMillis != null) return@filter day == selectedDayMillis
            if (monthWindow == MonthWindow.UPCOMING && day < todayStartMillis) return@filter false
            true
        }
    }

    fun emptyMessage(monthWindow: MonthWindow, source: EventSource, daySelected: Boolean): String {
        if (daySelected && source == EventSource.CRM_ONLY) return "No RCRM appointments on this day"
        if (daySelected) return "No events on this day"
        if (source == EventSource.CRM_ONLY && monthWindow == MonthWindow.UPCOMING) {
            return "No upcoming RCRM appointments this month."
        }
        if (source == EventSource.CRM_ONLY) return "No RCRM appointments this month."
        if (monthWindow == MonthWindow.UPCOMING) {
            return "Nothing upcoming this month. Switch to All Month to see earlier days."
        }
        return "No appointments or calendar events this month"
    }

    fun shortCalendarLabel(displayName: String, explicitCrm: Boolean): String {
        val name = displayName.trim()
        if (name.isNotEmpty()) return name
        return if (explicitCrm) "RCRM" else "Calendar"
    }

    fun statusLabel(status: AppointmentStatus?): String? {
        return when (status) {
            null, AppointmentStatus.SCHEDULED -> null
            AppointmentStatus.COMPLETED -> "Completed"
            AppointmentStatus.NO_SHOW -> "No-show"
            AppointmentStatus.CANCELED -> "Canceled"
        }
    }

    fun isLinkedToCrm(leadId: String?, explicitCrm: Boolean): Boolean {
        return explicitCrm || !leadId.isNullOrBlank()
    }

    fun crmLinkedBadge(leadName: String?, linked: Boolean): String? {
        if (!linked) return null
        val name = leadName?.trim().orEmpty()
        return if (name.isNotEmpty()) "🔗 $name" else "🔗 CRM Linked"
    }

    fun eventSubtitle(calendarDisplayName: String, linkedLeadName: String?): String {
        val calendar = calendarDisplayName.trim().ifBlank { "Calendar" }
        val link = if (!linkedLeadName.isNullOrBlank()) "Linked: $linkedLeadName" else "Unlinked Event"
        return "● $calendar • $link"
    }

    fun stripRcrmFooter(text: String?): String {
        if (text.isNullOrBlank()) return ""
        return text.lineSequence()
            .filterNot { line ->
                val trimmed = line.trim()
                trimmed.isEmpty() ||
                    trimmed == "---" ||
                    trimmed.startsWith("rcrm://", ignoreCase = true) ||
                    trimmed.contains("rcrm://lead/", ignoreCase = true) ||
                    trimmed.contains("rcrm://appointment/", ignoreCase = true) ||
                    trimmed.startsWith("Open Lead in RCRM:", ignoreCase = true)
            }
            .joinToString("\n")
            .trim()
    }

    fun eventPreview(description: String?, location: String?): String {
        val loc = location?.trim().orEmpty()
        val desc = stripRcrmFooter(description)
        return when {
            desc.isNotEmpty() && loc.isNotEmpty() && !desc.contains(loc) -> "$loc\n$desc"
            desc.isNotEmpty() -> desc
            else -> loc
        }
    }

    fun extractMeetingOrWebUrl(location: String?, description: String?): String? {
        urlIn(location)?.let { return it }
        val cleaned = stripRcrmFooter(description)
        val urls = WEB_URL.findAll(cleaned).map { trimUrl(it.value) }.filter { it.isNotEmpty() }.toList()
        return urls.firstOrNull { MEETING_HOST.containsMatchIn(it) } ?: urls.firstOrNull()
    }

    fun locationChipLabel(location: String?, meetingUrl: String?): String? {
        if (meetingUrl.isNullOrBlank() && location.isNullOrBlank()) return null
        return "📍 Location / Join"
    }

    fun eventDetail(
        entry: AgendaEntry,
        storedTimeLabel: String?,
        description: String? = null
    ): String {
        val lines = mutableListOf<String>()
        val preview = eventPreview(description, entry.location)
        if (preview.isNotBlank()) lines += preview
        statusLabel(entry.status)?.let { lines += "Status: $it" }
        if (entry.recurring) {
            lines += "Repeats on the calendar. RCRM will not change the whole series."
        }
        if (entry.timeDrifted) {
            val saved = storedTimeLabel?.takeIf { it.isNotBlank() }
            lines += if (saved == null) {
                "The calendar time differs from the time saved in RCRM. Neither one was overwritten."
            } else {
                "Saved in RCRM for $saved. The calendar time is shown here, and neither one was overwritten."
            }
        }
        return lines.joinToString("\n")
    }

    fun planCalendarWrite(
        eventId: Long?,
        canWrite: Boolean,
        recurringMaster: Boolean,
        allowInsert: Boolean
    ): CalendarWritePlan {
        if (recurringMaster) {
            return CalendarWritePlan.Blocked(
                "This event repeats. Change that occurrence in Google Calendar so the rest of the series is left alone."
            )
        }
        if (eventId != null && eventId > 0L) {
            if (!canWrite) {
                return CalendarWritePlan.Blocked(
                    "That calendar is read-only. The RCRM appointment was saved, and the calendar event was left unchanged."
                )
            }
            return CalendarWritePlan.UpdateExisting(eventId)
        }
        if (!allowInsert) {
            return CalendarWritePlan.Blocked(
                "The calendar event could not be found on this device. Tap retry to add it once."
            )
        }
        return CalendarWritePlan.InsertNew
    }

    fun resolveWrite(plan: CalendarWritePlan, foundEventId: Long?): ResolvedWrite {
        return when (plan) {
            is CalendarWritePlan.Blocked -> ResolvedWrite.Fail(plan.message)
            is CalendarWritePlan.UpdateExisting -> ResolvedWrite.Update(plan.eventId)
            CalendarWritePlan.InsertNew -> {
                if (foundEventId != null && foundEventId > 0L) {
                    ResolvedWrite.Update(foundEventId)
                } else {
                    ResolvedWrite.Insert
                }
            }
        }
    }

    /**
     * A device-local event id is only the same event when the description still
     * carries this appointment or an unambiguous legacy lead link.
     * Recurring series masters are not safe write targets.
     */
    fun eventIdentityAllows(
        eventLeadId: String?,
        eventAppointmentId: String?,
        expectedLeadId: String,
        expectedAppointmentId: String?,
        soleAppointmentForLead: Boolean,
        recurringMaster: Boolean = false
    ): Boolean {
        if (recurringMaster || expectedLeadId.isBlank()) return false
        val eventAppointment = eventAppointmentId?.trim()?.takeIf { it.isNotEmpty() }
        val eventLead = eventLeadId?.trim()?.takeIf { it.isNotEmpty() }
        if (eventAppointment != null) {
            if (eventAppointment != expectedAppointmentId) return false
            if (eventLead != null && eventLead != expectedLeadId) return false
            return !expectedAppointmentId.isNullOrBlank()
        }
        if (eventLead != null) {
            if (eventLead != expectedLeadId) return false
            if (expectedAppointmentId.isNullOrBlank()) return true
            if (expectedAppointmentId == legacyAppointmentId(expectedLeadId)) return true
            return soleAppointmentForLead
        }
        return false
    }

    fun selectRetryTarget(
        candidates: List<CalendarEventRef>,
        appointmentId: String?,
        leadId: String,
        preferredEventId: Long?,
        soleAppointmentForLead: Boolean = false
    ): Long? {
        if (preferredEventId != null && preferredEventId > 0L) {
            val preferred = candidates.firstOrNull { event ->
                event.eventId == preferredEventId || event.originalEventId == preferredEventId
            }
            if (preferred != null && eventIdentityAllows(
                    eventLeadId = preferred.explicitLeadId,
                    eventAppointmentId = preferred.explicitAppointmentId,
                    expectedLeadId = leadId,
                    expectedAppointmentId = appointmentId,
                    soleAppointmentForLead = soleAppointmentForLead
                )
            ) {
                return preferred.eventId
            }
        }
        if (!appointmentId.isNullOrBlank()) {
            val matches = candidates
                .filter { it.explicitAppointmentId == appointmentId && it.explicitLeadId.let { lead ->
                    lead.isNullOrBlank() || lead == leadId
                } }
                .map { it.eventId }
                .distinct()
            if (matches.isNotEmpty()) return matches.first()
        }
        if (leadId.isNotBlank()) {
            val leadMatches = candidates
                .filter { it.explicitLeadId == leadId && it.explicitAppointmentId == null }
                .map { it.eventId }
                .distinct()
            if (leadMatches.size == 1 &&
                (appointmentId.isNullOrBlank() || appointmentId == legacyAppointmentId(leadId) || soleAppointmentForLead)
            ) {
                return leadMatches.first()
            }
        }
        return null
    }

    /**
     * Free windows inside working hours on one local day. All-day banners
     * (holidays, birthdays) and canceled events never block time.
     */
    fun findOpenSlotsForDay(
        events: List<SyncedCalendarEvent>,
        dayStartMillis: Long,
        workStartHour: Int = 9,
        workEndHour: Int = 18,
        minSlotMinutes: Int = 60,
        zone: TimeZone = TimeZone.getDefault(),
        notBeforeMillis: Long? = null
    ): List<OpenTimeSlot> {
        val busy = events
            .filter { !it.allDay && !it.canceled }
            .map { BusyBlock(it.startMillis, it.endMillis) }
        return findOpenSlots(busy, dayStartMillis, workStartHour, workEndHour, minSlotMinutes, zone, notBeforeMillis)
    }

    /**
     * [notBeforeMillis] hides time that has already passed; the first slot then
     * starts on the next quarter hour so the booking time is a clean one.
     */
    fun findOpenSlots(
        busy: List<BusyBlock>,
        dayStartMillis: Long,
        workStartHour: Int = 9,
        workEndHour: Int = 18,
        minSlotMinutes: Int = 60,
        zone: TimeZone = TimeZone.getDefault(),
        notBeforeMillis: Long? = null
    ): List<OpenTimeSlot> {
        val startHour = workStartHour.coerceIn(0, 24)
        val endHour = workEndHour.coerceIn(0, 24)
        if (endHour <= startHour) return emptyList()
        val day = startOfDay(dayStartMillis, zone)
        val workStart = atHour(day, startHour, zone)
        val workEnd = atHour(day, endHour, zone)
        val windowStart = if (notBeforeMillis == null) {
            workStart
        } else {
            maxOf(workStart, roundUpToQuarterHour(notBeforeMillis))
        }
        if (windowStart >= workEnd) return emptyList()
        val minMillis = minSlotMinutes.coerceAtLeast(1) * MINUTE_MS
        val slots = mutableListOf<OpenTimeSlot>()
        fun emit(from: Long, to: Long) {
            if (to - from >= minMillis) {
                slots += OpenTimeSlot(from, to, ((to - from) / MINUTE_MS).toInt())
            }
        }
        var cursor = windowStart
        busy
            .filter { it.endMillis > it.startMillis && it.endMillis > windowStart && it.startMillis < workEnd }
            .sortedBy { it.startMillis }
            .forEach { block ->
                if (cursor >= workEnd) return@forEach
                if (block.startMillis > cursor) emit(cursor, minOf(block.startMillis, workEnd))
                cursor = maxOf(cursor, block.endMillis)
            }
        if (cursor < workEnd) emit(cursor, workEnd)
        return slots
    }

    fun durationLabel(minutes: Int): String {
        val safe = minutes.coerceAtLeast(0)
        val hours = safe / 60
        val rest = safe % 60
        return when {
            hours == 0 -> "${rest}m"
            rest == 0 -> "${hours}h"
            else -> "${hours}h ${rest}m"
        }
    }

    /**
     * A dialable number from free text. Labeled numbers ("Phone: …") win; an
     * unlabeled number needs at least 10 digits so dates and short codes are
     * skipped. Video-call dial-in lines are ignored.
     */
    fun extractPhone(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val lines = text.lineSequence().filterNot { NOT_A_PHONE_LINE.containsMatchIn(it) }.toList()
        lines.forEach { line ->
            LABELED_PHONE.findAll(line).forEach { match ->
                normalizePhone(match.groupValues[1], minDigits = 7)?.let { return it }
            }
        }
        lines.forEach { line ->
            LOOSE_PHONE.findAll(line).forEach { match ->
                normalizePhone(match.value, minDigits = 10)?.let { return it }
            }
        }
        return null
    }

    fun firstName(fullName: String?): String? {
        val token = fullName?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.trim(',', '.', ';', ':')
        return token?.takeIf { it.isNotEmpty() && it.any(Char::isLetter) }
    }

    fun confirmationText(
        leadName: String?,
        eventTitle: String,
        dateLabel: String,
        timeLabel: String?
    ): String {
        val first = firstName(leadName) ?: "there"
        val title = eventTitle.trim().ifEmpty { "Appointment" }
        val whenText = if (timeLabel.isNullOrBlank()) "on $dateLabel" else "on $dateLabel at $timeLabel"
        return "Hi $first, confirming our upcoming appointment ($title) $whenText. Reply here if you need anything!"
    }

    fun outcomeNote(
        outcome: VisitOutcome?,
        eventTitle: String,
        whenLabel: String,
        note: String
    ): String {
        val extra = note.trim()
        if (outcome == null) return extra
        val head = buildString {
            append("Outcome: ").append(outcome.label)
            val title = eventTitle.trim()
            if (title.isNotEmpty()) append(" — ").append(title)
            val whenText = whenLabel.trim()
            if (whenText.isNotEmpty()) append(" (").append(whenText).append(")")
        }
        return if (extra.isEmpty()) head else "$head\n$extra"
    }

    /** Rescheduling only leaves a note; moving the appointment has its own flow. */
    fun appointmentOutcomeFor(outcome: VisitOutcome): Outcome? {
        return when (outcome) {
            VisitOutcome.COMPLETED -> Outcome.COMPLETED
            VisitOutcome.NO_SHOW -> Outcome.NO_SHOW
            VisitOutcome.CANCELED -> Outcome.CANCELED
            VisitOutcome.RESCHEDULED -> null
        }
    }

    fun isLiveNow(startMillis: Long, endMillis: Long, nowMillis: Long): Boolean {
        return nowMillis >= startMillis && nowMillis <= endMillis
    }

    fun liveBadge(endMillis: Long, nowMillis: Long): String {
        val remaining = (endMillis - nowMillis).coerceAtLeast(0L)
        val minutes = ((remaining + MINUTE_MS - 1) / MINUTE_MS).toInt().coerceAtLeast(1)
        val ends = if (minutes < 60) "${minutes}m" else durationLabel(minutes)
        return "LIVE NOW • Ends in $ends"
    }

    /**
     * Timed events that are live now or start within [windowMillis]. An event
     * that began less than [graceMillis] ago still counts even if [endMillis]
     * is missing. Live meetings sort first so the dashboard can show them.
     */
    fun upcomingEvents(
        events: List<SyncedCalendarEvent>,
        nowMillis: Long,
        windowMillis: Long = UP_NEXT_WINDOW_MS,
        graceMillis: Long = UP_NEXT_GRACE_MS,
        limit: Int = UP_NEXT_LIMIT
    ): List<SyncedCalendarEvent> {
        return events
            .filter { !it.allDay && !it.canceled && it.endMillis > nowMillis }
            .filter { event ->
                isLiveNow(event.startMillis, event.endMillis, nowMillis) ||
                    (event.startMillis >= nowMillis - graceMillis && event.startMillis < nowMillis + windowMillis)
            }
            .sortedWith(
                compareBy<SyncedCalendarEvent> { event ->
                    if (isLiveNow(event.startMillis, event.endMillis, nowMillis)) 0 else 1
                }.thenBy { it.startMillis }.thenBy { it.title.lowercase(Locale.US) }
            )
            .take(limit.coerceAtLeast(1))
    }

    fun pickUpNext(
        events: List<SyncedCalendarEvent>,
        nowMillis: Long,
        windowMillis: Long = UP_NEXT_WINDOW_MS,
        graceMillis: Long = UP_NEXT_GRACE_MS
    ): SyncedCalendarEvent? {
        return upcomingEvents(events, nowMillis, windowMillis, graceMillis, limit = 1).firstOrNull()
    }

    fun countdownLabel(startMillis: Long, nowMillis: Long, zone: TimeZone, locale: Locale): String {
        val diff = startMillis - nowMillis
        if (diff <= 0L) return "Now"
        val minutes = ((diff + MINUTE_MS - 1) / MINUTE_MS).toInt()
        if (minutes < 60) return "In ${minutes}m"
        if (minutes < 180) return "In ${durationLabel(minutes)}"
        val time = java.text.SimpleDateFormat("h:mm a", locale).apply { timeZone = zone }.format(Date(startMillis))
        val day = startOfDay(startMillis, zone)
        val today = startOfDay(nowMillis, zone)
        return when (day) {
            today -> "Today at $time"
            nextDayStart(today, zone) -> "Tomorrow at $time"
            else -> {
                val dow = java.text.SimpleDateFormat("EEE", locale).apply { timeZone = zone }.format(Date(startMillis))
                "$dow at $time"
            }
        }
    }

    fun upNextHeadline(startMillis: Long, nowMillis: Long, zone: TimeZone): String {
        if (startMillis <= nowMillis) return "Happening Now"
        return if (startOfDay(startMillis, zone) == startOfDay(nowMillis, zone)) "Up Next Today" else "Up Next Tomorrow"
    }

    private fun normalizePhone(raw: String, minDigits: Int): String? {
        val digits = raw.filter { it.isDigit() }
        if (digits.length < minDigits || digits.length > 15) return null
        return if (raw.trimStart().startsWith("+")) "+$digits" else digits
    }

    private fun roundUpToQuarterHour(millis: Long): Long {
        val remainder = Math.floorMod(millis, SLOT_ROUNDING_MS)
        return if (remainder == 0L) millis else millis - remainder + SLOT_ROUNDING_MS
    }

    private fun atHour(dayStartMillis: Long, hour: Int, zone: TimeZone): Long {
        return Calendar.getInstance(zone).apply {
            timeInMillis = dayStartMillis
            zero(this)
            if (hour >= 24) {
                add(Calendar.DAY_OF_MONTH, 1)
            } else {
                set(Calendar.HOUR_OF_DAY, hour)
            }
        }.timeInMillis
    }

    private fun urlIn(text: String?): String? {
        if (text.isNullOrBlank()) return null
        return WEB_URL.find(text)?.value?.let { trimUrl(it) }?.takeIf { it.isNotEmpty() }
    }

    private fun trimUrl(raw: String): String {
        return raw.trim().trimEnd('.', ',', ';', ')', ']', '"', '\'')
    }

    private fun nextDayStart(dayStartMillis: Long, zone: TimeZone): Long {
        return Calendar.getInstance(zone).apply {
            timeInMillis = dayStartMillis
            add(Calendar.DAY_OF_MONTH, 1)
            zero(this)
        }.timeInMillis
    }

    private fun matchEventIndex(
        appointment: CrmAppointmentRef,
        events: List<CalendarEventRef>,
        used: Set<Int>,
        appointments: List<CrmAppointmentRef>
    ): Int? {
        val open = events.indices.filter { index ->
            index !in used && !events[index].canceled
        }
        fun pick(indices: List<Int>): Int? {
            if (indices.isEmpty()) return null
            val exact = indices.filter { sameInstant(events[it].startMillis, appointment.startMillis) }
            if (exact.size == 1) return exact.first()
            if (exact.size > 1) return exact.minByOrNull { events[it].eventId }
            val storedInstance = appointment.originalInstanceTime
            val moved = indices.filter { index ->
                val event = events[index]
                val original = event.originalInstanceTime
                (original != null && sameInstant(original, appointment.startMillis)) ||
                    (storedInstance != null && sameInstant(event.startMillis, storedInstance))
            }
            if (moved.size == 1) return moved.first()
            if (moved.isNotEmpty()) return null
            if (indices.size == 1) return indices.first()
            return null
        }

        pick(open.filter { events[it].explicitAppointmentId == appointment.appointmentId })?.let { return it }

        val eventId = appointment.eventId
        if (eventId != null && eventId > 0L) {
            val sole = appointments.count { it.leadId == appointment.leadId } == 1
            pick(
                open.filter { index ->
                    val event = events[index]
                    val idHit = event.eventId == eventId || event.originalEventId == eventId
                    idHit && eventIdentityAllows(
                        eventLeadId = event.explicitLeadId,
                        eventAppointmentId = event.explicitAppointmentId,
                        expectedLeadId = appointment.leadId,
                        expectedAppointmentId = appointment.appointmentId,
                        soleAppointmentForLead = sole,
                        recurringMaster = false
                    )
                }
            )?.let { return it }
        }

        val leadEvents = open.filter { index ->
            val event = events[index]
            event.explicitLeadId == appointment.leadId && event.explicitAppointmentId == null
        }
        val leadAppointments = appointments.count { it.leadId == appointment.leadId }
        if (leadEvents.size == 1 && leadAppointments == 1) return leadEvents.first()
        return null
    }

    private fun mergedEntry(appointment: CrmAppointmentRef, event: CalendarEventRef): AgendaEntry {
        val drifted = !sameInstant(appointment.startMillis, event.startMillis)
        val entry = AgendaEntry(
            title = event.title.ifBlank { appointment.leadName.ifBlank { "Appointment" } },
            startMillis = event.startMillis,
            endMillis = event.endMillis,
            allDay = event.allDay,
            color = event.color,
            eventId = event.eventId,
            leadId = appointment.leadId,
            ownerId = appointment.ownerId,
            appointmentId = appointment.appointmentId,
            leadName = appointment.leadName,
            phone = appointment.phone,
            location = event.location?.takeIf { it.isNotBlank() },
            explicitCrm = true,
            suggestedLeadId = null,
            calendarDisplayName = event.calendarDisplayName,
            accountName = event.accountName,
            canWrite = event.canWrite,
            recurring = event.recurring || event.originalEventId != null,
            recurringMaster = event.recurring && event.originalInstanceTime == null,
            storedStartMillis = appointment.startMillis,
            timeDrifted = drifted,
            status = appointment.status
        )
        return entry
    }

    private fun crmOnlyEntry(appointment: CrmAppointmentRef): AgendaEntry {
        val end = appointment.startMillis + 60L * 60L * 1000L
        return AgendaEntry(
            title = appointment.leadName.ifBlank { "Appointment" },
            startMillis = appointment.startMillis,
            endMillis = end,
            allDay = false,
            color = CRM_COLOR,
            eventId = null,
            leadId = appointment.leadId,
            ownerId = appointment.ownerId,
            appointmentId = appointment.appointmentId,
            leadName = appointment.leadName,
            phone = appointment.phone,
            location = appointment.location,
            explicitCrm = true,
            suggestedLeadId = null,
            calendarDisplayName = "",
            accountName = "",
            canWrite = true,
            recurring = false,
            recurringMaster = false,
            storedStartMillis = appointment.startMillis,
            timeDrifted = false,
            status = appointment.status
        )
    }

    private fun eventEntry(event: CalendarEventRef, suggestedLeadId: String?): AgendaEntry {
        val explicit = !event.explicitLeadId.isNullOrBlank() || !event.explicitAppointmentId.isNullOrBlank()
        return AgendaEntry(
            title = event.title.ifBlank { "(No title)" },
            startMillis = event.startMillis,
            endMillis = event.endMillis,
            allDay = event.allDay,
            color = event.color,
            eventId = event.eventId,
            leadId = event.explicitLeadId,
            ownerId = null,
            appointmentId = event.explicitAppointmentId,
            leadName = null,
            phone = null,
            location = event.location?.takeIf { it.isNotBlank() },
            explicitCrm = explicit,
            suggestedLeadId = if (explicit) null else suggestedLeadId,
            calendarDisplayName = event.calendarDisplayName,
            accountName = event.accountName,
            canWrite = event.canWrite,
            recurring = event.recurring || event.originalEventId != null,
            recurringMaster = event.recurring && event.originalInstanceTime == null,
            storedStartMillis = null,
            timeDrifted = false,
            status = null
        )
    }

    private fun inRange(
        startMillis: Long,
        allDay: Boolean,
        rangeStart: Long,
        rangeEnd: Long,
        zone: TimeZone
    ): Boolean {
        if (allDay) {
            val day = eventDayStart(startMillis, true, zone)
            return day >= rangeStart && day < rangeEnd
        }
        return startMillis >= rangeStart && startMillis < rangeEnd
    }

    private fun keptNewerEdit(
        current: AppointmentRecord,
        newStartMillis: Long?,
        newLocation: String?,
        nowMillis: Long,
        baseUpdatedAtMillis: Long
    ): Boolean {
        if (current.updatedAtMillis <= baseUpdatedAtMillis) return false
        if (current.updatedAtMillis == nowMillis) return false
        val clearing = newStartMillis == null || newStartMillis <= 0L
        val location = newLocation?.trim()?.takeIf { it.isNotEmpty() }
        val startDiffers = !clearing && !sameInstant(current.startMillis, newStartMillis ?: 0L)
        val locationDiffers = location != null && location != current.location
        val clearDiffers = clearing && current.status != AppointmentStatus.CANCELED
        return startDiffers || locationDiffers || clearDiffers
    }

    private fun findEditedIndex(
        records: List<AppointmentRecord>,
        leadId: String,
        previousStartMillis: Long?,
        previousEventId: Long?
    ): Int {
        if (previousEventId != null && previousEventId > 0L) {
            val byEvent = records.indexOfFirst { it.eventId == previousEventId }
            if (byEvent >= 0) return byEvent
        }
        if (previousStartMillis != null && previousStartMillis > 0L) {
            val byStart = records.indexOfFirst { sameInstant(it.startMillis, previousStartMillis) }
            if (byStart >= 0) return byStart
        }
        val legacy = records.indexOfFirst { it.id == legacyAppointmentId(leadId) }
        if (legacy >= 0) return legacy
        val scheduled = records.mapIndexedNotNull { index, record ->
            if (record.status == AppointmentStatus.SCHEDULED) index else null
        }
        return if (scheduled.size == 1) scheduled.first() else -1
    }

    private fun normalize(records: List<AppointmentRecord>): List<AppointmentRecord> {
        val seen = HashSet<String>()
        val kept = ArrayList<AppointmentRecord>(records.size)
        records.forEach { record ->
            if (record.id.isBlank() || record.startMillis <= 0L) return@forEach
            if (seen.add(record.id)) kept += record
        }
        return kept
    }

    private fun statusOf(raw: String?): AppointmentStatus {
        return when (raw?.trim()?.lowercase(Locale.US)) {
            "completed" -> AppointmentStatus.COMPLETED
            "no_show", "noshow", "no-show" -> AppointmentStatus.NO_SHOW
            "canceled", "cancelled" -> AppointmentStatus.CANCELED
            else -> AppointmentStatus.SCHEDULED
        }
    }

    private fun statusName(status: AppointmentStatus): String {
        return when (status) {
            AppointmentStatus.SCHEDULED -> "scheduled"
            AppointmentStatus.COMPLETED -> "completed"
            AppointmentStatus.NO_SHOW -> "no_show"
            AppointmentStatus.CANCELED -> "canceled"
        }
    }

    private fun longOrNull(value: Any?): Long? {
        return when (value) {
            is Long -> value
            is Int -> value.toLong()
            is Number -> value.toLong()
            is String -> value.toLongOrNull()
            else -> null
        }
    }

    private fun sameInstant(left: Long, right: Long): Boolean {
        return kotlin.math.abs(left - right) <= SAME_INSTANT_TOLERANCE_MS
    }

    private fun <T> List<T>.replacing(index: Int, value: T): List<T> {
        val copy = toMutableList()
        copy[index] = value
        return copy
    }

    private fun zero(calendar: Calendar) {
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
    }
}
