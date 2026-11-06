package com.nickpulido.rcrm

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.nickpulido.rcrm.domain.ActionType
import com.nickpulido.rcrm.domain.ActivityRecord
import com.nickpulido.rcrm.domain.OutcomeSavePlanner

data class ActivityDeletionResult(
    val updatedNotes: String,
    val lastAttemptedContact: Long?,
    val lastConnectedContact: Long?,
    val clearLastAttempted: Boolean,
    val clearLastConnected: Boolean
)

object ActivityDeletion {

    fun deleteActivityRecord(
        db: FirebaseFirestore,
        leadId: String,
        userId: String,
        activityId: String,
        onComplete: (Boolean, String?, ActivityDeletionResult?) -> Unit
    ) {
        if (leadId.isBlank() || userId.isBlank() || activityId.isBlank()) {
            onComplete(false, "That activity entry is no longer available.", null)
            return
        }
        val leadRef = db.collection("leads").document(leadId)
        val activityRef = leadRef.collection("activity_records").document(activityId)
        leadRef.collection("activity_records").get()
            .addOnSuccessListener { listed ->
                val remaining = listed.documents
                    .filter { it.id != activityId }
                    .mapNotNull { doc -> activityFromSnapshot(doc.id, leadId, doc.data ?: emptyMap()) }
                val timestamps = OutcomeSavePlanner.contactTimestamps(remaining)
                db.runTransaction { transaction ->
                    val leadSnap = transaction.get(leadRef)
                    val ownerId = leadSnap.getString("ownerId")
                    if (ownerId != userId) {
                        throw IllegalStateException("This contact does not belong to your account.")
                    }
                    val activitySnap = transaction.get(activityRef)
                    if (!activitySnap.exists()) {
                        throw IllegalStateException("That activity entry was already removed.")
                    }
                    transaction.delete(activityRef)
                    val existingNotes = leadSnap.getString("notes").orEmpty()
                    val updatedNotes = NoteUtils.removeNote(withEventId = activityId, existing = existingNotes)
                    val updates = mutableMapOf<String, Any>("ownerId" to userId)
                    if (updatedNotes != existingNotes) {
                        updates["notes"] = updatedNotes
                    }
                    if (timestamps.attempted != null) {
                        updates["lastAttemptedContact"] = timestamps.attempted
                    } else {
                        updates["lastAttemptedContact"] = FieldValue.delete()
                    }
                    if (timestamps.connected != null) {
                        updates["lastConnectedContact"] = timestamps.connected
                    } else {
                        updates["lastConnectedContact"] = FieldValue.delete()
                    }
                    transaction.update(leadRef, updates)
                    ActivityDeletionResult(
                        updatedNotes = updatedNotes,
                        lastAttemptedContact = timestamps.attempted,
                        lastConnectedContact = timestamps.connected,
                        clearLastAttempted = timestamps.attempted == null,
                        clearLastConnected = timestamps.connected == null
                    )
                }.addOnSuccessListener { result ->
                    onComplete(true, null, result)
                }.addOnFailureListener { error ->
                    onComplete(false, error.message ?: "Couldn't delete that activity.", null)
                }
            }
            .addOnFailureListener { error ->
                onComplete(false, error.message ?: "Couldn't delete that activity.", null)
            }
    }

    private fun activityFromSnapshot(
        id: String,
        leadId: String,
        data: Map<String, Any>
    ): ActivityRecord {
        val timestamp = when (val raw = data["timestamp"]) {
            is com.google.firebase.Timestamp -> raw.toDate().time
            is Number -> raw.toLong()
            else -> 0L
        }
        return ActivityRecord(
            id = id,
            leadId = data["leadId"] as? String ?: leadId,
            ownerId = data["ownerId"] as? String ?: "",
            type = runCatching { ActionType.valueOf((data["type"] as? String).orEmpty()) }
                .getOrDefault(ActionType.TO_DO),
            outcome = data["outcome"] as? String ?: "",
            note = data["note"] as? String ?: "",
            timestamp = timestamp
        )
    }
}
