package com.nickpulido.rcrm.domain

import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.nickpulido.rcrm.NoteUtils
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
        pauseReason: String? = null,
        onComplete: (Boolean) -> Unit
    ) {
        val leadRef = db.collection("leads").document(leadId)
        val nextActionsRef = leadRef.collection("next_actions")
        val activityRecordsRef = leadRef.collection("activity_records")

        db.runTransaction { transaction ->
            // 1. Read existing lead (required for appending notes)
            val leadSnapshot = transaction.get(leadRef)
            val existingNotes = leadSnapshot.getString("notes") ?: ""
            val updatedNotes = NoteUtils.prependNote(existingNotes, newNote)

            // 2. Mark currentAction as COMPLETED or CANCELLED
            if (currentActionId != null) {
                val currentActionRef = nextActionsRef.document(currentActionId)
                val status = if (pauseReason != null) ActionStatus.CANCELLED else ActionStatus.COMPLETED
                val actionUpdates = mutableMapOf<String, Any>(
                    "status" to status.name,
                    "completedAt" to System.currentTimeMillis()
                )
                if (pauseReason != null) {
                    actionUpdates["reason"] = pauseReason
                }
                transaction.update(currentActionRef, actionUpdates)
            }

            // 3. Save an ActivityRecord
            val activityRecord = ActivityRecord(
                id = UUID.randomUUID().toString(),
                leadId = leadId,
                ownerId = ownerId,
                type = nextActionType,
                outcome = newOutcome,
                note = newNote,
                timestamp = System.currentTimeMillis()
            )
            val activityRef = activityRecordsRef.document(activityRecord.id)
            transaction.set(activityRef, activityRecord)

            // 4. Update lead document
            val isConnected = newOutcome.contains("Connected", ignoreCase = true) || 
                              newOutcome.contains("Meeting completed", ignoreCase = true)

            val leadUpdates = mutableMapOf<String, Any?>()
            leadUpdates["lastAttemptedContact"] = System.currentTimeMillis()
            leadUpdates["notes"] = updatedNotes

            if (isConnected) {
                leadUpdates["lastConnectedContact"] = System.currentTimeMillis()
            }

            if (pauseReason != null) {
                leadUpdates["followUpDate"] = null
                leadUpdates["isPaused"] = true
            } else {
                leadUpdates["isPaused"] = false
                if (nextActionTime != null) {
                    leadUpdates["followUpDate"] = Timestamp(Date(nextActionTime))
                } else {
                    leadUpdates["followUpDate"] = null
                }
            }

            transaction.update(leadRef, leadUpdates)

            // 5. Insert new NextAction (PENDING)
            if (nextActionTitle != null && nextActionTime != null && pauseReason == null) {
                val newAction = NextAction(
                    id = UUID.randomUUID().toString(),
                    leadId = leadId,
                    ownerId = ownerId,
                    type = nextActionType,
                    title = nextActionTitle,
                    dueTime = nextActionTime,
                    status = ActionStatus.PENDING,
                    createdAt = System.currentTimeMillis()
                )
                val newActionRef = nextActionsRef.document(newAction.id)
                transaction.set(newActionRef, newAction)
            }
        }.addOnSuccessListener {
            onComplete(true)
        }.addOnFailureListener {
            it.printStackTrace()
            onComplete(false)
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
        val leadRef = db.collection("leads").document(leadId)
        val nextActionsRef = leadRef.collection("next_actions")
        val activityRecordsRef = leadRef.collection("activity_records")

        db.runTransaction { transaction ->
            val leadSnapshot = transaction.get(leadRef)
            val leadUpdates = mutableMapOf<String, Any?>()

            // 2. Update stage
            val stageField = if (pipelineType == PipelineType.SALES) "salesStage" else "recruitStage"
            leadUpdates[stageField] = newStageId

            // 3. Update category if in_force
            val currentCategory = leadSnapshot.getString("category") ?: ""
            if (newStageId == "in_force" && !currentCategory.contains("Client")) {
                val newCategory = if (currentCategory.isEmpty()) "Client" else "$currentCategory, Client"
                leadUpdates["category"] = newCategory
            }

            // 4. Append note
            val stageNote = "Moved to $stageTitle stage"
            val existingNotes = leadSnapshot.getString("notes") ?: ""
            val updatedNotes = NoteUtils.prependNote(existingNotes, stageNote)
            leadUpdates["notes"] = updatedNotes

            // 5. Insert NextAction and update followUpDate
            if (suggestedNextActionTitle != null && suggestedNextActionTime != null) {
                val newAction = NextAction(
                    id = UUID.randomUUID().toString(),
                    leadId = leadId,
                    ownerId = ownerId,
                    type = ActionType.TO_DO,
                    title = suggestedNextActionTitle,
                    dueTime = suggestedNextActionTime,
                    status = ActionStatus.PENDING,
                    createdAt = System.currentTimeMillis()
                )
                val newActionRef = nextActionsRef.document(newAction.id)
                transaction.set(newActionRef, newAction)
                
                leadUpdates["followUpDate"] = Timestamp(Date(suggestedNextActionTime))
            }

            // 6. Insert ActivityRecord
            val activityRecord = ActivityRecord(
                id = UUID.randomUUID().toString(),
                leadId = leadId,
                ownerId = ownerId,
                type = ActionType.TO_DO,
                outcome = "Stage Changed",
                note = stageNote,
                timestamp = System.currentTimeMillis()
            )
            val activityRef = activityRecordsRef.document(activityRecord.id)
            transaction.set(activityRef, activityRecord)

            // Update lead document
            transaction.update(leadRef, leadUpdates)
        }.addOnSuccessListener {
            onComplete(true)
        }.addOnFailureListener {
            it.printStackTrace()
            onComplete(false)
        }
    }
}
