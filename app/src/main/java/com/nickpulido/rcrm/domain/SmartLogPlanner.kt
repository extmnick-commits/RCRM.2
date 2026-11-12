package com.nickpulido.rcrm.domain

import com.nickpulido.rcrm.AppointmentAgenda

data class LabeledPhone(
    val label: String,
    val number: String
)

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

    fun isValidPhoneInput(phone: String): Boolean {
        val trimmed = phone.trim()
        return trimmed.count(Char::isDigit) >= 7 &&
            trimmed.all { it.isDigit() || it in "+-(). " }
    }

    fun sanitizePhoneForSms(phone: String): String =
        phone.trim().filter { it.isDigit() || it == '+' }

    fun readAdditionalPhones(raw: Any?): List<LabeledPhone> {
        val list = raw as? List<*> ?: return emptyList()
        return list.mapNotNull { entry ->
            val map = entry as? Map<*, *> ?: return@mapNotNull null
            val number = (map["number"] as? String)?.trim().orEmpty()
            if (number.isEmpty()) return@mapNotNull null
            val label = (map["label"] as? String)?.trim().orEmpty().ifEmpty { "Phone" }
            LabeledPhone(label = label, number = number)
        }
    }

    fun serializeAdditionalPhones(phones: List<LabeledPhone>): List<Map<String, String>> =
        phones.mapNotNull { entry ->
            val number = entry.number.trim()
            if (number.isEmpty()) null
            else mapOf(
                "label" to entry.label.trim().ifEmpty { "Phone" },
                "number" to number
            )
        }

    fun allLeadPhones(primaryPhone: String?, additionalRaw: Any?): List<String> =
        buildList {
            primaryPhone?.trim()?.takeIf { it.isNotEmpty() }?.let(::add)
            readAdditionalPhones(additionalRaw).forEach { add(it.number) }
        }

    fun leadMatchesPhone(phone: String, primaryPhone: String?, additionalRaw: Any?): Boolean {
        if (normalizedPhone(phone).isEmpty()) return false
        return allLeadPhones(primaryPhone, additionalRaw).any { phonesMatch(phone, it) }
    }

    fun mergeAdditionalPhones(
        existing: List<LabeledPhone>,
        incoming: List<LabeledPhone>,
        primaryPhone: String? = null
    ): List<LabeledPhone> {
        val merged = existing.filter { it.number.isNotBlank() }.toMutableList()
        val primary = primaryPhone?.trim().orEmpty()
        if (primary.isNotEmpty() && merged.none { phonesMatch(it.number, primary) } && incoming.isNotEmpty()) {
            merged.add(LabeledPhone(label = "Main", number = primary))
        }
        incoming.forEach { entry ->
            val number = entry.number.trim()
            if (number.isNotEmpty() && merged.none { phonesMatch(it.number, number) }) {
                merged.add(LabeledPhone(label = entry.label.trim().ifEmpty { "Phone" }, number = number))
            }
        }
        return merged
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
