package com.nickpulido.rcrm.domain

import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.nickpulido.rcrm.ui.components.PipelineType
import java.util.Date
import java.util.UUID

class FollowUpRepository {

    private val db = FirebaseFirestore.getInstance()

    fun recordOutcomeAndNextStep(
        leadId: String,
        ownerId: String,
        currentActionId: String?,
        newOutcome: String,
        newNote: String,
        nextActionTitle: String?,
        nextActionTime: Long?,
        nextActionType: ActionType,
        clearExistingNextAction: Boolean = false,
        pauseReason: String? = null,
        operationId: String = UUID.randomUUID().toString(),
        resolveCurrentActionIfMissing: Boolean = false,
        linkedAppointmentId: String? = null,
        closeWorkflow: Boolean = false,
        onComplete: (Boolean) -> Unit
    ) {
        val lifecycle = when {
            closeWorkflow -> WorkflowLifecycle.CLOSE
            pauseReason != null -> WorkflowLifecycle.PAUSE
            else -> WorkflowLifecycle.UNCHANGED
        }
        val changingLifecycle = lifecycle != WorkflowLifecycle.UNCHANGED
        val nextAction = if (
            !changingLifecycle &&
            nextActionTitle != null &&
            nextActionTime != null
        ) {
            NextActionSpec(
                title = nextActionTitle,
                dueTime = nextActionTime,
                type = nextActionType,
                appointmentId = linkedAppointmentId
            )
        } else {
            null
        }
        saveOutcome(
            OutcomeSaveRequest(
                operationId = operationId,
                leadId = leadId,
                ownerId = ownerId,
                outcome = newOutcome,
                note = newNote,
                activityType = nextActionType,
                stage = null,
                currentActionId = currentActionId,
                resolveCurrentActionIfMissing = resolveCurrentActionIfMissing,
                nextAction = nextAction,
                clearExistingNextAction = clearExistingNextAction && nextAction == null && !changingLifecycle,
                lifecycle = lifecycle,
                lifecycleReason = pauseReason,
                nowMillis = System.currentTimeMillis()
            )
        ) { success, _ ->
            onComplete(success)
        }
    }

    fun recordStageChange(
        leadId: String,
        ownerId: String,
        pipelineType: PipelineType,
        newStageId: String,
        stageTitle: String,
        suggestedNextActionTitle: String?,
        suggestedNextActionTime: Long?,
        onComplete: (Boolean) -> Unit
    ) {
        val nextAction = if (!suggestedNextActionTitle.isNullOrBlank() && suggestedNextActionTime != null) {
            NextActionSpec(
                title = suggestedNextActionTitle,
                dueTime = suggestedNextActionTime,
                type = ActionType.TO_DO,
                appointmentId = null
            )
        } else {
            null
        }
        saveOutcome(
            OutcomeSaveRequest(
                operationId = UUID.randomUUID().toString(),
                leadId = leadId,
                ownerId = ownerId,
                outcome = null,
                note = "",
                activityType = ActionType.TO_DO,
                stage = StageChangeSpec(pipelineType, newStageId, stageTitle),
                currentActionId = null,
                resolveCurrentActionIfMissing = false,
                nextAction = nextAction,
                clearExistingNextAction = false,
                lifecycle = WorkflowLifecycle.UNCHANGED,
                lifecycleReason = null,
                nowMillis = System.currentTimeMillis()
            )
        ) { success, _ ->
            onComplete(success)
        }
    }

    fun loadEarliestPendingActionId(leadId: String, onResult: (String?) -> Unit) {
        db.collection("leads").document(leadId)
            .collection("next_actions")
            .whereEqualTo("status", ActionStatus.PENDING.name)
            .get()
            .addOnSuccessListener { snapshot ->
                val id = snapshot.documents
                    .minByOrNull { it.longField("dueTime") ?: Long.MAX_VALUE }
                    ?.id
                onResult(id)
            }
            .addOnFailureListener { error ->
                Log.w(TAG, "Couldn't load the open task for $leadId", error)
                onResult(null)
            }
    }

    fun saveOutcome(
        request: OutcomeSaveRequest,
        onComplete: (success: Boolean, errorMessage: String?) -> Unit
    ) {
        val leadRef = db.collection("leads").document(request.leadId)
        val nextActionsRef = leadRef.collection("next_actions")
        val activityRef = leadRef.collection("activity_records")
            .document(OutcomeSavePlanner.activityDocumentId(request.operationId))

        nextActionsRef.whereEqualTo("status", ActionStatus.PENDING.name)
            .limit(25)
            .get()
            .addOnSuccessListener { snapshot ->
                commitOutcome(request, leadRef, nextActionsRef, activityRef, snapshot.documents.map { it.reference }, onComplete)
            }
            .addOnFailureListener { error ->
                Log.w(TAG, "Couldn't list open tasks before saving ${request.leadId}", error)
                commitOutcome(request, leadRef, nextActionsRef, activityRef, emptyList(), onComplete)
            }
    }

    private fun commitOutcome(
        request: OutcomeSaveRequest,
        leadRef: DocumentReference,
        nextActionsRef: com.google.firebase.firestore.CollectionReference,
        activityRef: DocumentReference,
        pendingRefs: List<DocumentReference>,
        onComplete: (success: Boolean, errorMessage: String?) -> Unit
    ) {
        db.runTransaction { transaction ->
            val existingActivity = transaction.get(activityRef)
            if (existingActivity.exists()) {
                return@runTransaction
            }

            val leadSnapshot = transaction.get(leadRef)
            if (!leadSnapshot.exists()) {
                throw IllegalStateException("That contact is no longer in RCRM.")
            }

            val pendingSnapshots = pendingRefs.map { transaction.get(it) }
            val pending = pendingSnapshots.mapNotNull { doc -> pendingRef(doc) }
            val plan = OutcomeSavePlanner.plan(
                request = request,
                existingNotes = leadSnapshot.getString("notes") ?: "",
                existingCategory = leadSnapshot.getString("category") ?: "",
                pendingActions = pending
            )

            val knownIds = pendingSnapshots.map { it.id }.toSet()
            val actionSnapshot = plan.completeActionId?.let { actionId ->
                if (actionId in knownIds) {
                    pendingSnapshots.first { it.id == actionId }
                } else {
                    transaction.get(nextActionsRef.document(actionId))
                }
            }

            transaction.set(activityRef, plan.activity)

            if (actionSnapshot != null && actionSnapshot.exists() && plan.completeStatus != null) {
                val status = actionSnapshot.getString("status")
                if (status == null || status == ActionStatus.PENDING.name) {
                    val actionUpdates = mutableMapOf<String, Any>(
                        "status" to plan.completeStatus.name,
                        "completedAt" to request.nowMillis
                    )
                    if (!plan.completeReason.isNullOrBlank()) {
                        actionUpdates["reason"] = plan.completeReason
                    }
                    transaction.update(actionSnapshot.reference, actionUpdates)
                }
            }

            if (plan.newAction != null) {
                transaction.set(nextActionsRef.document(plan.newAction.id), plan.newAction)
            }

            val leadUpdates = leadUpdates(plan.leadPatch)
            if (leadUpdates.isNotEmpty()) {
                transaction.update(leadRef, leadUpdates)
            }
        }.addOnSuccessListener {
            onComplete(true, null)
        }.addOnFailureListener { error ->
            Log.e(TAG, "Couldn't save the outcome for ${request.leadId}", error)
            val message = error.message?.takeIf { it.isNotBlank() }
                ?: "Couldn't save. Check your connection and try again."
            onComplete(false, message)
        }
    }

    private fun leadUpdates(patch: LeadPatch): Map<String, Any> {
        val updates = mutableMapOf<String, Any>()
        if (patch.stageField != null && patch.stageValue != null) {
            updates[patch.stageField] = patch.stageValue
        }
        if (patch.category != null) updates["category"] = patch.category
        if (patch.notes != null) updates["notes"] = patch.notes
        when (patch.followUp) {
            FollowUpWrite.UNCHANGED -> Unit
            FollowUpWrite.CLEAR -> updates["followUpDate"] = FieldValue.delete()
            FollowUpWrite.SET -> {
                val millis = patch.followUpMillis
                if (millis != null) updates["followUpDate"] = Timestamp(Date(millis))
            }
        }
        if (patch.isPaused != null) updates["isPaused"] = patch.isPaused
        if (patch.isClosed != null) updates["isClosed"] = patch.isClosed
        if (patch.writePausedReason) {
            updates["pausedReason"] = patch.pausedReason ?: FieldValue.delete()
        }
        if (patch.lastAttemptedContact != null) {
            updates["lastAttemptedContact"] = patch.lastAttemptedContact
        }
        if (patch.lastConnectedContact != null) {
            updates["lastConnectedContact"] = patch.lastConnectedContact
        }
        return updates
    }

    private fun pendingRef(doc: DocumentSnapshot): PendingActionRef? {
        if (!doc.exists()) return null
        val status = doc.getString("status")
        if (status != null && status != ActionStatus.PENDING.name) return null
        return PendingActionRef(
            id = doc.id,
            dueTime = doc.longField("dueTime") ?: 0L
        )
    }

    private fun DocumentSnapshot.longField(name: String): Long? {
        val value = get(name) ?: return null
        return when (value) {
            is Long -> value
            is Int -> value.toLong()
            is Double -> value.toLong()
            is Number -> value.toLong()
            else -> null
        }
    }

    companion object {
        private const val TAG = "FollowUpRepository"
    }
}
