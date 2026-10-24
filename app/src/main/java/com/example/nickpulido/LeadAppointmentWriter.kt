package com.nickpulido.rcrm

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore

/**
 * Writes one appointment change onto the latest lead document.
 * Unrelated appointments stay as they were saved.
 */
object LeadAppointmentWriter {

    private val appointmentKeys = setOf(
        AppointmentAgenda.FIELD_APPOINTMENTS,
        "appointmentDate",
        "appointmentLocation",
        "googleCalendarEventId"
    )

    fun commit(
        db: FirebaseFirestore,
        leadId: String,
        mutationFor: (
            fresh: List<AppointmentAgenda.AppointmentRecord>,
            legacyStartMillis: Long?,
            legacyLocation: String?,
            legacyEventId: Long?
        ) -> AppointmentSync.AppointmentMutation,
        extras: (Map<String, Any>) -> Map<String, Any?> = { emptyMap() },
        onSuccess: (AppointmentSync.MutationResult) -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        val ref = db.collection("leads").document(leadId)
        db.runTransaction { transaction ->
            val snap = transaction.get(ref)
            if (!snap.exists()) {
                throw IllegalStateException("That lead is no longer available.")
            }
            val fresh = LeadAppointmentSync.readRecords(snap.get(AppointmentAgenda.FIELD_APPOINTMENTS))
            val legacyStart = snap.getTimestamp("appointmentDate")?.toDate()?.time
            val legacyLocation = snap.getString("appointmentLocation")
            val legacyEventId = GoogleCalendarManager.readStoredEventId(snap.get("googleCalendarEventId"))
            val mutation = mutationFor(fresh, legacyStart, legacyLocation, legacyEventId)
            val result = AppointmentSync.applyAppointmentMutation(leadId, fresh, mutation)
            val payload = linkedMapOf<String, Any>()
            extras(snap.data ?: emptyMap()).forEach { (key, value) ->
                if (key in appointmentKeys) return@forEach
                payload[key] = value ?: FieldValue.delete()
            }
            if (result.changed) {
                LeadAppointmentSync.fields(result.records, System.currentTimeMillis(), forUpdate = true)
                    .forEach { (key, value) ->
                        if (value != null) payload[key] = value
                    }
            }
            if (payload.isNotEmpty()) {
                transaction.update(ref, payload)
            }
            result
        }.addOnSuccessListener { result ->
            onSuccess(result)
        }.addOnFailureListener { error ->
            onFailure(error)
        }
    }
}
