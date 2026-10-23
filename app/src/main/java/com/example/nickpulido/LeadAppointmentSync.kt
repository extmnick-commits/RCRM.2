package com.nickpulido.rcrm

import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import java.util.Date

/**
 * Maps appointment records onto the lead document while keeping the original
 * appointmentDate, appointmentLocation, and googleCalendarEventId fields in sync
 * for screens that still read a single appointment.
 */
object LeadAppointmentSync {

    fun readRecords(raw: Any?): List<AppointmentAgenda.AppointmentRecord> {
        return AppointmentAgenda.parseRecords(raw, ::epochMillis)
    }

    fun fields(
        records: List<AppointmentAgenda.AppointmentRecord>,
        nowMillis: Long,
        forUpdate: Boolean
    ): Map<String, Any?> {
        val mirror = AppointmentAgenda.legacyMirror(records, nowMillis)
        val values = linkedMapOf<String, Any?>(
            AppointmentAgenda.FIELD_APPOINTMENTS to AppointmentAgenda.toFieldMaps(records)
        )
        putMirror(values, "appointmentDate", mirror.startMillis?.let { Timestamp(Date(it)) }, forUpdate)
        putMirror(values, "appointmentLocation", mirror.location?.takeIf { it.isNotBlank() }, forUpdate)
        putMirror(values, "googleCalendarEventId", mirror.eventId?.takeIf { it > 0L }, forUpdate)
        return values
    }

    private fun putMirror(values: MutableMap<String, Any?>, key: String, value: Any?, forUpdate: Boolean) {
        if (value != null) {
            values[key] = value
        } else if (forUpdate) {
            values[key] = FieldValue.delete()
        }
    }

    private fun epochMillis(value: Any?): Long? {
        return when (value) {
            is Timestamp -> value.toDate().time
            is Long -> value
            is Int -> value.toLong()
            is Number -> value.toLong()
            is String -> value.toLongOrNull()
            else -> null
        }
    }
}
