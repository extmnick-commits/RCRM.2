package com.nickpulido.rcrm.domain

import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Transaction
import com.nickpulido.rcrm.ui.components.PipelineType
import java.util.Date
import java.util.TimeZone
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

    fun migrateLegacyReminder(
        leadId: String,
        ownerId: String,
        nowMillis: Long = System.currentTimeMillis(),
        onComplete: (MigrationPlan?) -> Unit
    ) {
        if (leadId.isBlank() || ownerId.isBlank()) {
            onComplete(null)
            return
        }
        val leadRef = db.collection("leads").document(leadId)
        val actionsRef = leadRef.collection("next_actions")
        loadReminderRefs(
            actionsRef = actionsRef,
            onLoaded = { refs ->
                db.runTransaction { transaction ->
                    val read = readMigration(transaction, leadRef, actionsRef, leadId, ownerId, nowMillis, refs)
                    writeReminderTransaction(
                        transaction = transaction,
                        leadRef = leadRef,
                        actionsRef = actionsRef,
                        migration = read.plan,
                        actionRef = null,
                        activityRef = null,
                        plan = null
                    )
                    read.plan
                }.addOnSuccessListener { plan ->
                    onComplete(plan)
                }.addOnFailureListener { error ->
                    Log.w(TAG, "Couldn't migrate reminders for $leadId", error)
                    onComplete(null)
                }
            },
            onFailure = { error ->
                Log.w(TAG, "Couldn't list reminders before migration for $leadId", error)
                onComplete(null)
            }
        )
    }

    fun createReminder(
        leadId: String,
        ownerId: String,
        actionId: String,
        title: String,
        notes: String,
        type: ActionType,
        dueTime: Long,
        nowMillis: Long = System.currentTimeMillis(),
        zone: TimeZone = TimeZone.getDefault(),
        onComplete: (Boolean, String?) -> Unit
    ) {
        val actionRefId = ReminderPlanner.sanitizeId(actionId)
        runReminderWrite(leadId, onComplete) { transaction, leadRef, actionsRef, refs ->
            val read = readMigration(transaction, leadRef, actionsRef, leadId, ownerId, nowMillis, refs)
            val pending = pendingAfterMigration(read)
            val plan = ReminderPlanner.planCreate(
                leadId = leadId,
                ownerId = ownerId,
                actionId = actionRefId,
                title = title,
                notes = notes,
                type = type,
                dueTime = dueTime,
                nowMillis = nowMillis,
                zone = zone,
                pendingActions = pending
            )
            writeReminderTransaction(
                transaction,
                leadRef,
                actionsRef,
                read.plan,
                actionsRef.document(actionRefId),
                null,
                plan
            )
        }
    }

    fun editReminder(
        leadId: String,
        ownerId: String,
        actionId: String,
        title: String,
        notes: String,
        type: ActionType,
        nowMillis: Long = System.currentTimeMillis(),
        zone: TimeZone = TimeZone.getDefault(),
        onComplete: (Boolean, String?) -> Unit
    ) {
        runReminderWrite(leadId, onComplete) { transaction, leadRef, actionsRef, refs ->
            val read = readMigration(transaction, leadRef, actionsRef, leadId, ownerId, nowMillis, refs)
            val actionRef = actionsRef.document(ReminderPlanner.sanitizeId(actionId))
            val existing = existingAction(transaction, actionRef, leadId, actionRef.id, read)
            val plan = ReminderPlanner.planEdit(
                existing = existing,
                title = title,
                notes = notes,
                type = type,
                nowMillis = nowMillis,
                zone = zone,
                pendingActions = pendingAfterMigration(read)
            )
            writeReminderTransaction(transaction, leadRef, actionsRef, read.plan, actionRef, null, plan)
        }
    }

    fun rescheduleReminder(
        leadId: String,
        ownerId: String,
        actionId: String,
        dueTime: Long,
        nowMillis: Long = System.currentTimeMillis(),
        zone: TimeZone = TimeZone.getDefault(),
        onComplete: (Boolean, String?) -> Unit
    ) {
        runReminderWrite(leadId, onComplete) { transaction, leadRef, actionsRef, refs ->
            val read = readMigration(transaction, leadRef, actionsRef, leadId, ownerId, nowMillis, refs)
            val actionRef = actionsRef.document(ReminderPlanner.sanitizeId(actionId))
            val existing = existingAction(transaction, actionRef, leadId, actionRef.id, read)
            val plan = ReminderPlanner.planReschedule(
                existing = existing,
                dueTime = dueTime,
                nowMillis = nowMillis,
                zone = zone,
                pendingActions = pendingAfterMigration(read)
            )
            writeReminderTransaction(transaction, leadRef, actionsRef, read.plan, actionRef, null, plan)
        }
    }

    fun updateReminder(
        leadId: String,
        ownerId: String,
        actionId: String,
        title: String,
        notes: String,
        type: ActionType,
        dueTime: Long,
        nowMillis: Long = System.currentTimeMillis(),
        zone: TimeZone = TimeZone.getDefault(),
        onComplete: (Boolean, String?) -> Unit
    ) {
        runReminderWrite(leadId, onComplete) { transaction, leadRef, actionsRef, refs ->
            val read = readMigration(transaction, leadRef, actionsRef, leadId, ownerId, nowMillis, refs)
            val actionRef = actionsRef.document(ReminderPlanner.sanitizeId(actionId))
            val existing = existingAction(transaction, actionRef, leadId, actionRef.id, read)
            val plan = ReminderPlanner.planUpdate(
                existing = existing,
                title = title,
                notes = notes,
                type = type,
                dueTime = dueTime,
                nowMillis = nowMillis,
                zone = zone,
                pendingActions = pendingAfterMigration(read)
            )
            writeReminderTransaction(transaction, leadRef, actionsRef, read.plan, actionRef, null, plan)
        }
    }

    fun completeReminder(
        leadId: String,
        ownerId: String,
        actionId: String,
        nowMillis: Long = System.currentTimeMillis(),
        onComplete: (Boolean, String?) -> Unit
    ) {
        val safeId = ReminderPlanner.sanitizeId(actionId)
        runReminderWrite(leadId, onComplete) { transaction, leadRef, actionsRef, refs ->
            val read = readMigration(transaction, leadRef, actionsRef, leadId, ownerId, nowMillis, refs)
            val actionRef = actionsRef.document(safeId)
            val activityRef = leadRef.collection("activity_records")
                .document(ReminderPlanner.completionActivityId(safeId))
            val existing = existingAction(transaction, actionRef, leadId, safeId, read)
            val activityExists = transaction.get(activityRef).exists()
            val plan = ReminderPlanner.planComplete(
                existing = existing,
                pendingActions = pendingAfterMigration(read),
                activityAlreadyExists = activityExists,
                nowMillis = nowMillis
            )
            writeReminderTransaction(transaction, leadRef, actionsRef, read.plan, actionRef, activityRef, plan)
        }
    }

    fun restoreReminder(
        leadId: String,
        ownerId: String,
        actionId: String,
        nowMillis: Long = System.currentTimeMillis(),
        onComplete: (Boolean, String?) -> Unit
    ) {
        val safeId = ReminderPlanner.sanitizeId(actionId)
        runReminderWrite(leadId, onComplete) { transaction, leadRef, actionsRef, refs ->
            val read = readMigration(transaction, leadRef, actionsRef, leadId, ownerId, nowMillis, refs)
            val actionRef = actionsRef.document(safeId)
            val activityRef = leadRef.collection("activity_records")
                .document(ReminderPlanner.completionActivityId(safeId))
            val existing = existingAction(transaction, actionRef, leadId, safeId, read)
            val plan = ReminderPlanner.planRestore(
                existing = existing,
                pendingActions = pendingAfterMigration(read),
                nowMillis = nowMillis
            )
            writeReminderTransaction(transaction, leadRef, actionsRef, read.plan, actionRef, activityRef, plan)
        }
    }

    fun cancelReminder(
        leadId: String,
        ownerId: String,
        actionId: String,
        nowMillis: Long = System.currentTimeMillis(),
        onComplete: (Boolean, String?) -> Unit
    ) {
        val safeId = ReminderPlanner.sanitizeId(actionId)
        runReminderWrite(leadId, onComplete) { transaction, leadRef, actionsRef, refs ->
            val read = readMigration(transaction, leadRef, actionsRef, leadId, ownerId, nowMillis, refs)
            val actionRef = actionsRef.document(safeId)
            val existing = existingAction(transaction, actionRef, leadId, safeId, read)
            val plan = ReminderPlanner.planCancel(
                existing = existing,
                pendingActions = pendingAfterMigration(read)
            )
            writeReminderTransaction(transaction, leadRef, actionsRef, read.plan, actionRef, null, plan)
        }
    }

    private data class ReminderRefs(
        val anyRef: DocumentReference?,
        val pendingRefs: List<DocumentReference>
    )

    private data class MigrationRead(
        val plan: MigrationPlan,
        val pending: List<NextAction>,
        val readActions: Map<String, NextAction>
    )

    private fun readMigration(
        transaction: Transaction,
        leadRef: DocumentReference,
        actionsRef: CollectionReference,
        leadId: String,
        ownerId: String,
        nowMillis: Long,
        refs: ReminderRefs
    ): MigrationRead {
        val lead = transaction.get(leadRef)
        if (!lead.exists()) throw IllegalStateException("That contact is no longer in RCRM.")
        val readActions = linkedMapOf<String, NextAction>()
        val pending = mutableListOf<NextAction>()
        val seen = mutableSetOf<String>()
        fun take(ref: DocumentReference) {
            if (!seen.add(ref.path)) return
            val action = readAction(transaction.get(ref), leadId) ?: return
            readActions.putIfAbsent(action.id, action)
            if (action.status == ActionStatus.PENDING) pending += action
        }
        refs.pendingRefs.forEach(::take)
        refs.anyRef?.let(::take)
        take(actionsRef.document(ReminderPlanner.legacyActionId(leadId)))
        val plan = ReminderPlanner.planMigration(
            leadId = leadId,
            ownerId = ownerId,
            storedVersion = migrationVersion(lead),
            legacyFollowUpMillis = lead.getTimestamp("followUpDate")?.toDate()?.time,
            actions = readActions.values.toList(),
            nowMillis = nowMillis
        )
        return MigrationRead(plan = plan, pending = pending, readActions = readActions)
    }

    private fun existingAction(
        transaction: Transaction,
        actionRef: DocumentReference,
        leadId: String,
        actionId: String,
        read: MigrationRead
    ): NextAction {
        read.readActions[actionId]?.let { return it }
        return readAction(transaction.get(actionRef), leadId)
            ?: throw IllegalStateException("That reminder is no longer in RCRM.")
    }

    private fun pendingAfterMigration(read: MigrationRead): List<NextAction> {
        val created = read.plan.actionToCreate ?: return read.pending
        if (read.pending.any { it.id == created.id }) return read.pending
        return read.pending + created
    }

    private fun writeReminderTransaction(
        transaction: Transaction,
        leadRef: DocumentReference,
        actionsRef: CollectionReference,
        migration: MigrationPlan,
        actionRef: DocumentReference?,
        activityRef: DocumentReference?,
        plan: ReminderWritePlan?
    ) {
        if (plan?.error != null) throw IllegalStateException(plan.error)
        val created = migration.actionToCreate
        if (created != null && created.id != plan?.action?.id) {
            transaction.set(actionsRef.document(created.id), created)
        }
        if (plan?.activity != null && activityRef != null) {
            transaction.set(activityRef, plan.activity)
        } else if (plan?.deleteActivity == true && activityRef != null) {
            transaction.delete(activityRef)
        }
        if (plan?.action != null && actionRef != null) {
            transaction.set(actionRef, plan.action)
        }
        val followUp = plan?.followUp ?: migration.followUp
        val followUpMillis = if (plan != null) plan.followUpMillis else migration.followUpMillis
        val updates = leadUpdates(
            LeadPatch(followUp = followUp, followUpMillis = followUpMillis)
        ).toMutableMap()
        if (migration.versionToStore != null) {
            updates[ReminderPlanner.MIGRATION_FIELD] = migration.versionToStore.toLong()
        }
        if (updates.isNotEmpty()) transaction.update(leadRef, updates)
    }

    private fun runReminderWrite(
        leadId: String,
        onComplete: (Boolean, String?) -> Unit,
        block: (Transaction, DocumentReference, CollectionReference, ReminderRefs) -> Unit
    ) {
        if (leadId.isBlank()) {
            onComplete(false, "That contact is no longer in RCRM.")
            return
        }
        val leadRef = db.collection("leads").document(leadId)
        val actionsRef = leadRef.collection("next_actions")
        loadReminderRefs(
            actionsRef = actionsRef,
            onLoaded = { refs ->
                db.runTransaction { transaction ->
                    block(transaction, leadRef, actionsRef, refs)
                    null
                }.addOnSuccessListener {
                    onComplete(true, null)
                }.addOnFailureListener { error ->
                    Log.e(TAG, "Couldn't save a reminder for $leadId", error)
                    onComplete(false, reminderErrorMessage(error))
                }
            },
            onFailure = { error ->
                Log.e(TAG, "Couldn't list reminders for $leadId", error)
                onComplete(false, "Couldn't save. Check your connection and try again.")
            }
        )
    }

    private fun loadReminderRefs(
        actionsRef: CollectionReference,
        onLoaded: (ReminderRefs) -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        actionsRef.limit(1).get()
            .addOnSuccessListener { any ->
                actionsRef.whereEqualTo("status", ActionStatus.PENDING.name)
                    .limit(ReminderPlanner.PENDING_READ_LIMIT)
                    .get()
                    .addOnSuccessListener { pending ->
                        onLoaded(
                            ReminderRefs(
                                anyRef = any.documents.firstOrNull()?.reference,
                                pendingRefs = pending.documents.map { it.reference }
                            )
                        )
                    }
                    .addOnFailureListener(onFailure)
            }
            .addOnFailureListener(onFailure)
    }

    private fun readAction(doc: DocumentSnapshot, fallbackLeadId: String): NextAction? {
        if (!doc.exists()) return null
        return ReminderPlanner.actionFrom(
            id = doc.id,
            leadId = doc.getString("leadId") ?: fallbackLeadId,
            ownerId = doc.getString("ownerId") ?: "",
            typeName = doc.getString("type"),
            title = doc.getString("title") ?: "",
            notes = doc.getString("notes"),
            dueTime = doc.longField("dueTime") ?: 0L,
            statusName = doc.getString("status"),
            createdAt = doc.longField("createdAt") ?: 0L,
            completedAt = doc.longField("completedAt"),
            appointmentId = doc.getString("appointmentId"),
            reason = doc.getString("reason"),
            workflowName = doc.getString("workflow")
        )
    }

    private fun migrationVersion(doc: DocumentSnapshot): Int {
        val value = doc.get(ReminderPlanner.MIGRATION_FIELD) ?: return 0
        return when (value) {
            is Number -> value.toInt()
            else -> 0
        }
    }

    private fun reminderErrorMessage(error: Exception): String {
        val texts = listOfNotNull(error.message, error.cause?.message)
        val friendly = listOf(
            "That reminder is no longer open.",
            "That reminder is already completed.",
            "That reminder is no longer in RCRM.",
            "That contact is no longer in RCRM.",
            "Please enter a title.",
            "Choose a time that hasn't already passed."
        )
        return friendly.firstOrNull { candidate -> texts.any { it.contains(candidate) } }
            ?: "Couldn't save. Check your connection and try again."
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
