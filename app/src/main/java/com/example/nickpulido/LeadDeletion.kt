package com.nickpulido.rcrm

import android.content.Context
import com.google.firebase.firestore.FirebaseFirestore

object LeadDeletion {

    fun deleteLead(
        context: Context,
        db: FirebaseFirestore,
        leadId: String,
        phone: String?,
        onComplete: (Boolean, String?) -> Unit
    ) {
        if (leadId.isBlank()) {
            onComplete(false, "That contact is no longer in RCRM.")
            return
        }
        val leadRef = db.collection("leads").document(leadId)
        leadRef.collection("next_actions").get()
            .addOnSuccessListener { actionsSnap ->
                leadRef.collection("activity_records").get()
                    .addOnSuccessListener { activitySnap ->
                        val batch = db.batch()
                        for (doc in actionsSnap.documents) {
                            batch.delete(doc.reference)
                        }
                        for (doc in activitySnap.documents) {
                            batch.delete(doc.reference)
                        }
                        batch.delete(leadRef)
                        batch.commit()
                            .addOnSuccessListener {
                                ReminderAlarmStore.cancelAllForLead(context.applicationContext, leadId, phone)
                                onComplete(true, null)
                            }
                            .addOnFailureListener { error ->
                                onComplete(false, error.message ?: "Couldn't delete that contact.")
                            }
                    }
                    .addOnFailureListener { error ->
                        onComplete(false, error.message ?: "Couldn't delete that contact.")
                    }
            }
            .addOnFailureListener { error ->
                onComplete(false, error.message ?: "Couldn't delete that contact.")
            }
    }
}
