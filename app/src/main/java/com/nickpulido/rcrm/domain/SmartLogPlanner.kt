package com.nickpulido.rcrm.domain

import com.nickpulido.rcrm.AppointmentAgenda

/** Small rules shared by contact lookup and the Smart Log save path. */
object SmartLogPlanner {
    fun normalizedPhone(phone: String): String {
        val digits = phone.filter(Char::isDigit)
        return if (digits.length == 11 && digits.startsWith("1")) digits.drop(1) else digits
    }

    fun phonesMatch(first: String, second: String): Boolean {
        val normalized = normalizedPhone(first)
        return normalized.isNotEmpty() && normalized == normalizedPhone(second)
    }

    /** Optional fields left empty in a quick log never erase the full contact profile. */
    fun profileUpdates(draft: Map<String, String>): Map<String, Any?> =
        draft.mapValues { it.value.trim() }.filterValues { it.isNotBlank() }

    fun addAppointment(
        leadId: String,
        existing: List<AppointmentAgenda.AppointmentRecord>,
        legacyStart: Long?,
        legacyLocation: String?,
        legacyEventId: Long?,
        appointmentId: String,
        start: Long,
        location: String?,
        now: Long
    ): List<AppointmentAgenda.AppointmentRecord> {
        val migrated = AppointmentAgenda.migrateAppointments(leadId, existing, legacyStart, legacyLocation, legacyEventId, now)
        val current = migrated.firstOrNull { it.id == appointmentId }
        val record = current?.copy(startMillis = start, location = location, updatedAtMillis = now)
            ?: AppointmentAgenda.AppointmentRecord(
                id = appointmentId, startMillis = start, location = location, eventId = null,
                status = AppointmentAgenda.AppointmentStatus.SCHEDULED, originalInstanceTime = null,
                updatedAtMillis = now
            )
        return AppointmentAgenda.upsertRecord(migrated, record)
    }
}
