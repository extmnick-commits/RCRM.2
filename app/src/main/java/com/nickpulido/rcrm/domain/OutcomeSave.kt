package com.nickpulido.rcrm.domain

import com.nickpulido.rcrm.AppointmentAgenda
import com.nickpulido.rcrm.NoteUtils
import com.nickpulido.rcrm.ui.components.PipelineType
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

enum class WorkflowLifecycle {
    UNCHANGED,
    PAUSE,
    CLOSE
}

enum class FollowUpWrite {
    UNCHANGED,
    CLEAR,
    SET
}

enum class DueShortcut {
    TODAY,
    TOMORROW
}

data class StageChangeSpec(
    val pipelineType: PipelineType,
    val newStageId: String,
    val stageTitle: String
)

data class NextActionSpec(
    val title: String,
    val dueTime: Long,
    val type: ActionType,
    val appointmentId: String? = null
)

data class PendingActionRef(
    val id: String,
    val dueTime: Long
)

data class AppointmentChoice(
    val id: String,
    val label: String,
    val startMillis: Long
)

data class OutcomeSaveRequest(
    val operationId: String,
    val leadId: String,
    val ownerId: String,
    val outcome: String?,
    val note: String,
    val activityType: ActionType,
    val stage: StageChangeSpec?,
    val currentActionId: String?,
    val resolveCurrentActionIfMissing: Boolean,
    val nextAction: NextActionSpec?,
    val clearExistingNextAction: Boolean,
    val lifecycle: WorkflowLifecycle,
    val lifecycleReason: String?,
    val nowMillis: Long
)

data class LeadPatch(
    val stageField: String? = null,
    val stageValue: String? = null,
    val category: String? = null,
    val notes: String? = null,
    val followUp: FollowUpWrite = FollowUpWrite.UNCHANGED,
    val followUpMillis: Long? = null,
    val isPaused: Boolean? = null,
    val isClosed: Boolean? = null,
    val writePausedReason: Boolean = false,
    val pausedReason: String? = null,
    val lastAttemptedContact: Long? = null,
    val lastConnectedContact: Long? = null
)

data class OutcomeSavePlan(
    val activity: ActivityRecord,
    val newAction: NextAction?,
    val completeActionId: String?,
    val completeStatus: ActionStatus?,
    val completeReason: String?,
    val leadPatch: LeadPatch
)

data class OutcomeSubmission(
    val operationId: String,
    val outcome: String,
    val note: String,
    val scheduleNext: Boolean,
    val nextTitle: String,
    val nextTime: Long?,
    val nextType: ActionType,
    val clearExisting: Boolean,
    val lifecycle: WorkflowLifecycle,
    val lifecycleReason: String,
    val linkedAppointmentId: String?
)

data class OutcomeDraft(
    val outcome: String,
    val note: String,
    val nextTitle: String,
    val dueMillis: Long?,
    val actionType: ActionType,
    val scheduleNext: Boolean,
    val clearExisting: Boolean,
    val lifecycle: WorkflowLifecycle,
    val lifecycleReason: String,
    val appointmentId: String?,
    val operationId: String
)

data class OutcomeSaveUiResult(
    val dismiss: Boolean,
    val errorMessage: String?,
    val draft: OutcomeDraft
)

object OutcomeSavePlanner {

    private const val APPOINTMENT_LOOKBACK_MS = 12L * 60L * 60L * 1000L
    private const val MAX_APPOINTMENT_CHOICES = 6

    fun sanitizeOperationId(operationId: String): String {
        val trimmed = operationId.trim().ifBlank { "outcome" }
        return trimmed.replace("/", "_").replace("\\", "_")
    }

    fun activityDocumentId(operationId: String): String {
        return "act_${sanitizeOperationId(operationId)}"
    }

    fun nextActionDocumentId(operationId: String): String {
        return "next_${sanitizeOperationId(operationId)}"
    }

    fun alreadyApplied(existingActivityIds: Set<String>, operationId: String): Boolean {
        return activityDocumentId(operationId) in existingActivityIds
    }

    fun savesOnDismiss(): Boolean = false

    fun canSubmit(isSaving: Boolean): Boolean = !isSaving

    fun afterSave(success: Boolean, errorMessage: String?, draft: OutcomeDraft): OutcomeSaveUiResult {
        return if (success) {
            OutcomeSaveUiResult(dismiss = true, errorMessage = null, draft = draft)
        } else {
            OutcomeSaveUiResult(
                dismiss = false,
                errorMessage = errorMessage?.takeIf { it.isNotBlank() }
                    ?: "Couldn't save. Check your connection and try again.",
                draft = draft
            )
        }
    }

    fun outcomeContactName(name: String?): String {
        return name?.trim()?.takeIf { it.isNotEmpty() } ?: "Contact"
    }

    fun outcomeHeaderSubtitle(pipelineLabel: String, stageTitle: String): String {
        return listOf(pipelineLabel.trim(), stageTitle.trim())
            .filter { it.isNotEmpty() }
            .joinToString(" · ")
    }

    fun outcomeFormError(
        outcomeRequired: Boolean,
        outcome: String,
        scheduleNext: Boolean,
        nextTitle: String,
        dueMillis: Long?,
        lifecycle: WorkflowLifecycle
    ): String? {
        val hasOutcome = outcome.isNotBlank()
        val changingLifecycle = lifecycle != WorkflowLifecycle.UNCHANGED
        if (outcomeRequired && !hasOutcome && !changingLifecycle) {
            return "Choose an outcome, or use Pause or Close."
        }
        if (scheduleNext && !changingLifecycle) {
            if (nextTitle.isBlank()) return "Add a title for the next action."
            if (dueMillis == null || dueMillis <= 0L) {
                return "Choose a due date, or turn off the next action."
            }
        }
        return null
    }

    fun activityTypeForOutcome(outcome: String?, scheduledType: ActionType?): ActionType {
        if (scheduledType != null) return scheduledType
        return when (outcome?.trim()) {
            "Connected", "No answer", "Voicemail left" -> ActionType.CALL
            "Message sent" -> ActionType.MESSAGE
            "Meeting completed", "No-show", "Rescheduled" -> ActionType.MEETING
            else -> ActionType.TO_DO
        }
    }

    fun buildSaveRequest(
        submission: OutcomeSubmission,
        leadId: String,
        ownerId: String,
        stage: StageChangeSpec?,
        currentActionId: String?,
        nowMillis: Long
    ): OutcomeSaveRequest {
        val outcome = submission.outcome.trim().ifBlank { null }
        val changingLifecycle = submission.lifecycle != WorkflowLifecycle.UNCHANGED
        val title = submission.nextTitle.trim()
        val due = submission.nextTime
        val schedule = submission.scheduleNext &&
            !changingLifecycle &&
            !submission.clearExisting &&
            title.isNotEmpty() &&
            due != null &&
            due > 0L
        val nextAction = if (schedule) {
            NextActionSpec(
                title = title,
                dueTime = due ?: 0L,
                type = submission.nextType,
                appointmentId = submission.linkedAppointmentId?.trim()?.ifBlank { null }
            )
        } else {
            null
        }
        return OutcomeSaveRequest(
            operationId = sanitizeOperationId(submission.operationId),
            leadId = leadId,
            ownerId = ownerId,
            outcome = outcome,
            note = submission.note.trim(),
            activityType = activityTypeForOutcome(outcome, if (schedule) submission.nextType else null),
            stage = stage,
            currentActionId = currentActionId?.trim()?.ifBlank { null },
            resolveCurrentActionIfMissing = true,
            nextAction = nextAction,
            clearExistingNextAction = submission.clearExisting && !schedule && !changingLifecycle,
            lifecycle = submission.lifecycle,
            lifecycleReason = submission.lifecycleReason.trim().ifBlank { null },
            nowMillis = nowMillis
        )
    }

    fun plan(
        request: OutcomeSaveRequest,
        existingNotes: String,
        existingCategory: String,
        pendingActions: List<PendingActionRef>
    ): OutcomeSavePlan {
        val operationId = sanitizeOperationId(request.operationId)
        val outcome = request.outcome?.trim()?.ifBlank { null }
        val changingLifecycle = request.lifecycle != WorkflowLifecycle.UNCHANGED
        val requestedNext = request.nextAction
        val schedule = !changingLifecycle &&
            !request.clearExistingNextAction &&
            requestedNext != null &&
            requestedNext.title.isNotBlank() &&
            requestedNext.dueTime > 0L

        val earliestPending = pendingActions.minByOrNull { it.dueTime }
        val shouldFinishCurrent = changingLifecycle ||
            outcome != null ||
            request.clearExistingNextAction ||
            schedule
        val finishedId = if (!shouldFinishCurrent) {
            null
        } else {
            request.currentActionId?.takeIf { it.isNotBlank() }
                ?: if (request.resolveCurrentActionIfMissing) earliestPending?.id else null
        }

        val completeStatus = when {
            finishedId == null -> null
            request.lifecycle == WorkflowLifecycle.PAUSE || request.clearExistingNextAction -> ActionStatus.CANCELLED
            else -> ActionStatus.COMPLETED
        }
        val completeReason = if (changingLifecycle) request.lifecycleReason else null

        val newAction = if (requestedNext != null && schedule) {
            NextAction(
                id = nextActionDocumentId(operationId),
                leadId = request.leadId,
                ownerId = request.ownerId,
                workflow = workflowFor(request.stage?.pipelineType),
                type = requestedNext.type,
                title = requestedNext.title.trim(),
                dueTime = requestedNext.dueTime,
                status = ActionStatus.PENDING,
                appointmentId = requestedNext.appointmentId?.takeIf { it.isNotBlank() },
                createdAt = request.nowMillis
            )
        } else {
            null
        }

        val followUp = resolveFollowUp(
            lifecycle = request.lifecycle,
            scheduleDue = newAction?.dueTime,
            clearExisting = request.clearExistingNextAction && newAction == null && !changingLifecycle,
            finishedId = if (outcome != null || request.clearExistingNextAction || schedule || changingLifecycle) finishedId else null,
            pendingActions = pendingActions
        )

        val stageField = request.stage?.let { stageFieldName(it.pipelineType) }
        val category = request.stage?.let { stage ->
            if (stage.newStageId == "in_force" && !existingCategory.contains("Client")) {
                if (existingCategory.isBlank()) "Client" else "$existingCategory, Client"
            } else {
                null
            }
        }

        val noteBody = buildNoteBody(
            stageTitle = request.stage?.stageTitle,
            note = request.note,
            lifecycle = request.lifecycle,
            lifecycleReason = request.lifecycleReason
        )
        val actId = activityDocumentId(operationId)
        val notes = if (noteBody.isBlank()) null else NoteUtils.prependNote(existingNotes, noteBody, eventId = actId)

        val resumeForNewWork = newAction != null && !changingLifecycle
        val isPaused = when (request.lifecycle) {
            WorkflowLifecycle.PAUSE -> true
            WorkflowLifecycle.CLOSE -> false
            WorkflowLifecycle.UNCHANGED -> if (resumeForNewWork) false else null
        }
        val isClosed = when (request.lifecycle) {
            WorkflowLifecycle.CLOSE -> true
            WorkflowLifecycle.PAUSE -> false
            WorkflowLifecycle.UNCHANGED -> if (resumeForNewWork) false else null
        }
        val writeReason = changingLifecycle || resumeForNewWork
        val connected = outcome != null && (
            outcome.contains("Connected", ignoreCase = true) ||
                outcome.contains("Meeting completed", ignoreCase = true)
            )

        val activity = ActivityRecord(
            id = actId,
            leadId = request.leadId,
            ownerId = request.ownerId,
            type = request.activityType,
            outcome = activityOutcome(request.stage, outcome),
            note = noteBody,
            timestamp = request.nowMillis
        )

        return OutcomeSavePlan(
            activity = activity,
            newAction = newAction,
            completeActionId = if (completeStatus == null) null else finishedId,
            completeStatus = completeStatus,
            completeReason = if (completeStatus == null) null else completeReason,
            leadPatch = LeadPatch(
                stageField = stageField,
                stageValue = request.stage?.newStageId,
                category = category,
                notes = notes,
                followUp = followUp.write,
                followUpMillis = followUp.millis,
                isPaused = isPaused,
                isClosed = isClosed,
                writePausedReason = writeReason,
                pausedReason = if (changingLifecycle) request.lifecycleReason else null,
                lastAttemptedContact = if (outcome != null) request.nowMillis else null,
                lastConnectedContact = if (connected) request.nowMillis else null
            )
        )
    }

    fun columnContentWidthPx(
        availableWidthPx: Int,
        columns: Int,
        gapPx: Int,
        horizontalPaddingPx: Int
    ): Int {
        if (columns <= 0 || availableWidthPx <= 0) return 0
        val gaps = gapPx * (columns - 1).coerceAtLeast(0)
        val cell = (availableWidthPx - gaps) / columns
        return cell - horizontalPaddingPx
    }

    fun outcomeColumnCount(
        textWidthsPx: List<Int>,
        availableWidthPx: Int,
        horizontalPaddingPx: Int,
        gapPx: Int,
        minCellPx: Int = 0,
        maxColumns: Int = 3
    ): Int {
        if (textWidthsPx.isEmpty()) return 1
        val upper = maxColumns.coerceIn(1, textWidthsPx.size.coerceAtLeast(1))
        for (columns in upper downTo 1) {
            val gaps = gapPx * (columns - 1)
            val cell = if (availableWidthPx > gaps) (availableWidthPx - gaps) / columns else 0
            val content = cell - horizontalPaddingPx
            val wideEnough = cell >= minCellPx && content > 0 && textWidthsPx.all { it <= content }
            if (wideEnough) return columns
        }
        return 1
    }

    fun choiceLabelsFit(
        textWidthsPx: List<Int>,
        availableWidthPx: Int,
        horizontalPaddingPx: Int,
        gapPx: Int,
        minCellPx: Int = 0,
        maxColumns: Int = 3
    ): Boolean {
        val columns = outcomeColumnCount(
            textWidthsPx = textWidthsPx,
            availableWidthPx = availableWidthPx,
            horizontalPaddingPx = horizontalPaddingPx,
            gapPx = gapPx,
            minCellPx = minCellPx,
            maxColumns = maxColumns
        )
        val content = columnContentWidthPx(availableWidthPx, columns, gapPx, horizontalPaddingPx)
        return content > 0 && textWidthsPx.all { it <= content }
    }

    fun dueMillisForShortcut(shortcut: DueShortcut, nowMillis: Long, zone: TimeZone): Long {
        val now = Calendar.getInstance(zone).apply {
            timeInMillis = nowMillis
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return when (shortcut) {
            DueShortcut.TODAY -> {
                val evening = (now.clone() as Calendar).apply {
                    set(Calendar.HOUR_OF_DAY, 17)
                    set(Calendar.MINUTE, 0)
                }
                if (evening.timeInMillis > nowMillis) {
                    evening.timeInMillis
                } else {
                    (now.clone() as Calendar).apply {
                        add(Calendar.HOUR_OF_DAY, 1)
                        set(Calendar.MINUTE, 0)
                    }.timeInMillis
                }
            }
            DueShortcut.TOMORROW -> {
                (now.clone() as Calendar).apply {
                    add(Calendar.DAY_OF_YEAR, 1)
                    set(Calendar.HOUR_OF_DAY, 9)
                    set(Calendar.MINUTE, 0)
                }.timeInMillis
            }
        }
    }

    fun formatDue(millis: Long, zone: TimeZone = TimeZone.getDefault(), locale: Locale = Locale.getDefault()): String {
        val format = SimpleDateFormat("EEE, MMM d, yyyy, h:mm a", locale)
        format.timeZone = zone
        return format.format(Date(millis))
    }

    fun relevantAppointments(
        records: List<AppointmentAgenda.AppointmentRecord>,
        legacyStartMillis: Long?,
        nowMillis: Long,
        zone: TimeZone = TimeZone.getDefault(),
        locale: Locale = Locale.getDefault()
    ): List<AppointmentChoice> {
        val earliest = nowMillis - APPOINTMENT_LOOKBACK_MS
        val scheduled = records
            .filter { record ->
                record.status == AppointmentAgenda.AppointmentStatus.SCHEDULED &&
                    record.startMillis >= earliest
            }
            .sortedBy { it.startMillis }
            .take(MAX_APPOINTMENT_CHOICES)
            .map { record ->
                val time = formatDue(record.startMillis, zone, locale)
                val place = record.location?.trim()?.takeIf { it.isNotEmpty() }
                AppointmentChoice(
                    id = record.id,
                    label = if (place == null) time else "$time · $place",
                    startMillis = record.startMillis
                )
            }
        if (scheduled.isNotEmpty()) return scheduled
        val legacy = legacyStartMillis ?: return emptyList()
        if (legacy < earliest) return emptyList()
        return listOf(
            AppointmentChoice(
                id = "",
                label = formatDue(legacy, zone, locale),
                startMillis = legacy
            )
        )
    }

    private data class FollowUpDecision(val write: FollowUpWrite, val millis: Long?)

    private fun resolveFollowUp(
        lifecycle: WorkflowLifecycle,
        scheduleDue: Long?,
        clearExisting: Boolean,
        finishedId: String?,
        pendingActions: List<PendingActionRef>
    ): FollowUpDecision {
        if (lifecycle != WorkflowLifecycle.UNCHANGED) {
            return FollowUpDecision(FollowUpWrite.CLEAR, null)
        }
        val surviving = pendingActions
            .filter { it.id != finishedId }
            .map { it.dueTime }
            .toMutableList()
        if (scheduleDue != null) surviving += scheduleDue
        if (scheduleDue == null && !clearExisting && finishedId == null) {
            return FollowUpDecision(FollowUpWrite.UNCHANGED, null)
        }
        val earliest = surviving.minOrNull()
        return if (earliest != null) {
            FollowUpDecision(FollowUpWrite.SET, earliest)
        } else {
            FollowUpDecision(FollowUpWrite.CLEAR, null)
        }
    }

    private fun activityOutcome(stage: StageChangeSpec?, outcome: String?): String {
        return when {
            stage != null && !outcome.isNullOrBlank() -> "Stage Changed: $outcome"
            stage != null -> "Stage Changed"
            else -> outcome.orEmpty()
        }
    }

    private fun buildNoteBody(
        stageTitle: String?,
        note: String,
        lifecycle: WorkflowLifecycle,
        lifecycleReason: String?
    ): String {
        val parts = mutableListOf<String>()
        if (!stageTitle.isNullOrBlank()) parts += "Moved to $stageTitle stage"
        val typed = note.trim()
        if (typed.isNotEmpty()) parts += typed
        val reason = lifecycleReason?.trim().orEmpty()
        if (lifecycle == WorkflowLifecycle.PAUSE && reason.isNotEmpty()) parts += "Paused: $reason"
        if (lifecycle == WorkflowLifecycle.CLOSE && reason.isNotEmpty()) parts += "Closed: $reason"
        return parts.joinToString("\n\n")
    }

    private fun stageFieldName(pipelineType: PipelineType): String {
        return if (pipelineType == PipelineType.SALES) "salesStage" else "recruitStage"
    }

    private fun workflowFor(pipelineType: PipelineType?): WorkflowType {
        return when (pipelineType) {
            PipelineType.SALES -> WorkflowType.SALES
            PipelineType.RECRUITING -> WorkflowType.RECRUITING
            null -> WorkflowType.GENERAL
        }
    }
}
