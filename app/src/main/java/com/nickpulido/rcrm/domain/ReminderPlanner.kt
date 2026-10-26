package com.nickpulido.rcrm.domain

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

enum class ReminderListFilter {
    OPEN,
    COMPLETED
}

data class MigrationPlan(
    val versionToStore: Int?,
    val actionToCreate: NextAction?,
    val followUp: FollowUpWrite,
    val followUpMillis: Long?,
    val cancelLegacyPhoneAlarm: Boolean
)

data class ReminderWritePlan(
    val error: String? = null,
    val action: NextAction? = null,
    val activity: ActivityRecord? = null,
    val deleteActivity: Boolean = false,
    val followUp: FollowUpWrite = FollowUpWrite.UNCHANGED,
    val followUpMillis: Long? = null,
    val scheduleAlarm: Boolean = false,
    val cancelAlarm: Boolean = false
)

data class ReminderAlarmState(
    val leadId: String,
    val actionId: String,
    val dueTime: Long,
    val title: String,
    val contactName: String
)

data class ReminderAlarmSync(
    val cancelActionIds: Set<String>,
    val schedule: List<ReminderAlarmState>
)

object ReminderPlanner {

    const val MIGRATION_VERSION = 1
    const val MIGRATION_FIELD = "reminderMigrationVersion"
    const val PREVIEW_LIMIT = 3
    const val PENDING_READ_LIMIT = 100L
    const val COMPLETION_OUTCOME = "Reminder completed"
    const val RESTORE_GRACE_MS = 6L * 60L * 60L * 1000L

    private val contactOutcomes = listOf(
        "Connected",
        "No answer",
        "Voicemail left",
        "Message sent",
        "Meeting completed",
        "No-show",
        "Rescheduled"
    )

    fun legacyActionId(leadId: String): String {
        return "legacy_followup_${sanitizeId(leadId)}"
    }

    fun completionActivityId(actionId: String): String {
        return "act_reminder_completed_${sanitizeId(actionId)}"
    }

    fun reminderRequestIdentity(leadId: String, actionId: String): String {
        return "reminder:${sanitizeId(leadId)}:${sanitizeId(actionId)}"
    }

    fun sanitizeId(value: String): String {
        val trimmed = value.trim().ifBlank { "reminder" }
        return trimmed.replace("/", "_").replace("\\", "_")
    }

    fun isAppointmentAlarmKey(key: String): Boolean {
        return key.startsWith("appt:") || key.startsWith("appt_")
    }

    fun typeLabel(type: ActionType): String {
        return when (type) {
            ActionType.CALL -> "Call"
            ActionType.MESSAGE -> "Message"
            ActionType.EMAIL -> "Email"
            ActionType.TO_DO -> "To-do"
            ActionType.MEETING -> "Meeting"
        }
    }

    fun reminderTypeChoices(existing: ActionType?): List<ActionType> {
        val choices = listOf(ActionType.CALL, ActionType.MESSAGE, ActionType.EMAIL, ActionType.TO_DO)
        return if (existing == ActionType.MEETING) choices + ActionType.MEETING else choices
    }

    fun actionFrom(
        id: String,
        leadId: String = "",
        ownerId: String = "",
        typeName: String? = null,
        title: String = "",
        notes: String? = null,
        dueTime: Long = 0L,
        statusName: String? = null,
        createdAt: Long = 0L,
        completedAt: Long? = null,
        appointmentId: String? = null,
        reason: String? = null,
        workflowName: String? = null
    ): NextAction {
        return NextAction(
            id = id,
            leadId = leadId,
            ownerId = ownerId,
            workflow = workflowName?.let { runCatching { WorkflowType.valueOf(it) }.getOrNull() }
                ?: WorkflowType.GENERAL,
            type = typeName?.let { runCatching { ActionType.valueOf(it) }.getOrNull() } ?: ActionType.TO_DO,
            title = title,
            notes = notes.orEmpty(),
            dueTime = dueTime,
            status = statusName?.let { runCatching { ActionStatus.valueOf(it) }.getOrNull() }
                ?: ActionStatus.PENDING,
            appointmentId = appointmentId?.takeIf { it.isNotBlank() },
            createdAt = createdAt,
            completedAt = completedAt,
            reason = reason?.takeIf { it.isNotBlank() }
        )
    }

    fun planMigration(
        leadId: String,
        ownerId: String,
        storedVersion: Int,
        legacyFollowUpMillis: Long?,
        actions: List<NextAction>,
        nowMillis: Long
    ): MigrationPlan {
        val legacyMillis = legacyFollowUpMillis?.takeIf { it > 0L }
        if (storedVersion >= MIGRATION_VERSION) {
            return MigrationPlan(
                versionToStore = null,
                actionToCreate = null,
                followUp = FollowUpWrite.UNCHANGED,
                followUpMillis = null,
                cancelLegacyPhoneAlarm = shouldCancelLegacyPhoneAlarm(
                    storedVersion,
                    datedPendingCount(actions),
                    legacyMillis
                )
            )
        }

        val hasAnyAction = actions.isNotEmpty()
        val legacyId = legacyActionId(leadId)
        val create = if (!hasAnyAction && legacyMillis != null) {
            NextAction(
                id = legacyId,
                leadId = leadId,
                ownerId = ownerId,
                workflow = WorkflowType.GENERAL,
                type = ActionType.TO_DO,
                title = "Follow up",
                notes = "",
                dueTime = normalizeDueMillis(legacyMillis, TimeZone.getDefault()),
                status = ActionStatus.PENDING,
                appointmentId = null,
                createdAt = nowMillis,
                completedAt = null,
                reason = null
            )
        } else {
            null
        }
        val projected = actions + listOfNotNull(create)
        val earliest = earliestDatedPendingMillis(projected)
        val followUp = if (earliest != null) {
            FollowUpWrite.SET to earliest
        } else if (legacyMillis != null || hasAnyAction) {
            FollowUpWrite.CLEAR to null
        } else {
            FollowUpWrite.UNCHANGED to null
        }
        return MigrationPlan(
            versionToStore = MIGRATION_VERSION,
            actionToCreate = create,
            followUp = followUp.first,
            followUpMillis = followUp.second,
            cancelLegacyPhoneAlarm = legacyMillis != null
        )
    }

    fun shouldCancelLegacyPhoneAlarm(
        storedVersion: Int,
        datedPendingCount: Int,
        legacyFollowUpMillis: Long?
    ): Boolean {
        if (storedVersion < MIGRATION_VERSION) return false
        if (datedPendingCount > 0) return true
        return legacyFollowUpMillis == null || legacyFollowUpMillis <= 0L
    }

    fun planCreate(
        leadId: String,
        ownerId: String,
        actionId: String,
        title: String,
        notes: String,
        type: ActionType,
        dueTime: Long,
        nowMillis: Long,
        zone: TimeZone,
        pendingActions: List<NextAction>
    ): ReminderWritePlan {
        val titleError = validateTitle(title)
        if (titleError != null) return ReminderWritePlan(error = titleError)
        val normalized = normalizeDueMillis(dueTime, zone)
        val timeError = validateDueMillis(normalized, nowMillis)
        if (timeError != null) return ReminderWritePlan(error = timeError)
        val action = NextAction(
            id = sanitizeId(actionId),
            leadId = leadId,
            ownerId = ownerId,
            workflow = WorkflowType.GENERAL,
            type = type,
            title = title.trim(),
            notes = notes.trim(),
            dueTime = normalized,
            status = ActionStatus.PENDING,
            createdAt = nowMillis
        )
        val followUp = followUpAfter(pendingActions, replacing = action, removingId = null)
        return ReminderWritePlan(
            action = action,
            followUp = followUp.write,
            followUpMillis = followUp.millis,
            scheduleAlarm = true,
            cancelAlarm = false
        )
    }

    fun planEdit(
        existing: NextAction,
        title: String,
        notes: String,
        type: ActionType,
        nowMillis: Long,
        zone: TimeZone,
        pendingActions: List<NextAction>
    ): ReminderWritePlan {
        return planUpdate(
            existing = existing,
            title = title,
            notes = notes,
            type = type,
            dueTime = existing.dueTime,
            nowMillis = nowMillis,
            zone = zone,
            pendingActions = pendingActions
        )
    }

    fun planReschedule(
        existing: NextAction,
        dueTime: Long,
        nowMillis: Long,
        zone: TimeZone,
        pendingActions: List<NextAction>
    ): ReminderWritePlan {
        return planUpdate(
            existing = existing,
            title = existing.title,
            notes = existing.notes,
            type = existing.type,
            dueTime = dueTime,
            nowMillis = nowMillis,
            zone = zone,
            pendingActions = pendingActions
        )
    }

    fun planUpdate(
        existing: NextAction,
        title: String,
        notes: String,
        type: ActionType,
        dueTime: Long,
        nowMillis: Long,
        zone: TimeZone,
        pendingActions: List<NextAction>
    ): ReminderWritePlan {
        if (existing.status != ActionStatus.PENDING) {
            return ReminderWritePlan(error = "That reminder is no longer open.")
        }
        val titleError = validateTitle(title)
        if (titleError != null) return ReminderWritePlan(error = titleError)
        val normalized = normalizeDueMillis(dueTime, zone)
        val previous = normalizeDueMillis(existing.dueTime, zone)
        if (normalized != previous) {
            val timeError = validateDueMillis(normalized, nowMillis)
            if (timeError != null) return ReminderWritePlan(error = timeError)
        }
        val action = existing.copy(
            title = title.trim(),
            notes = notes.trim(),
            type = type,
            dueTime = normalized,
            status = ActionStatus.PENDING,
            completedAt = null,
            reason = null
        )
        val followUp = followUpAfter(pendingActions, replacing = action, removingId = null)
        return ReminderWritePlan(
            action = action,
            followUp = followUp.write,
            followUpMillis = followUp.millis,
            scheduleAlarm = true,
            cancelAlarm = true
        )
    }

    fun planComplete(
        existing: NextAction,
        pendingActions: List<NextAction>,
        activityAlreadyExists: Boolean,
        nowMillis: Long
    ): ReminderWritePlan {
        if (existing.status == ActionStatus.CANCELLED) {
            return ReminderWritePlan(error = "That reminder is no longer open.")
        }
        val completed = existing.copy(
            status = ActionStatus.COMPLETED,
            completedAt = existing.completedAt ?: nowMillis
        )
        val activity = if (activityAlreadyExists || existing.status == ActionStatus.COMPLETED) {
            null
        } else {
            ActivityRecord(
                id = completionActivityId(existing.id),
                leadId = existing.leadId,
                ownerId = existing.ownerId,
                type = ActionType.TO_DO,
                outcome = COMPLETION_OUTCOME,
                note = completionNote(existing),
                timestamp = nowMillis
            )
        }
        val followUp = followUpAfter(pendingActions, replacing = null, removingId = existing.id)
        return ReminderWritePlan(
            action = completed,
            activity = activity,
            followUp = followUp.write,
            followUpMillis = followUp.millis,
            scheduleAlarm = false,
            cancelAlarm = true
        )
    }

    fun planRestore(
        existing: NextAction,
        pendingActions: List<NextAction>,
        nowMillis: Long
    ): ReminderWritePlan {
        if (existing.status != ActionStatus.COMPLETED) {
            return ReminderWritePlan(error = "That reminder is not completed.")
        }
        val restored = existing.copy(
            status = ActionStatus.PENDING,
            completedAt = null
        )
        
        val followUp = followUpAfter(pendingActions, replacing = restored, removingId = null)
        val shouldRestore = shouldRestoreAlarm(restored.dueTime, nowMillis)
        
        return ReminderWritePlan(
            action = restored,
            activity = null,
            deleteActivity = true,
            followUp = followUp.write,
            followUpMillis = followUp.millis,
            scheduleAlarm = shouldRestore,
            cancelAlarm = !shouldRestore
        )
    }

    fun planCancel(
        existing: NextAction,
        pendingActions: List<NextAction>
    ): ReminderWritePlan {
        if (existing.status == ActionStatus.COMPLETED) {
            return ReminderWritePlan(error = "That reminder is already completed.")
        }
        val cancelled = existing.copy(
            status = ActionStatus.CANCELLED,
            reason = existing.reason ?: "Deleted"
        )
        val followUp = followUpAfter(pendingActions, replacing = null, removingId = existing.id)
        return ReminderWritePlan(
            action = cancelled,
            activity = null,
            followUp = followUp.write,
            followUpMillis = followUp.millis,
            scheduleAlarm = false,
            cancelAlarm = true
        )
    }

    fun earliestPending(actions: List<NextAction>): NextAction? {
        return actions
            .filter { it.status == ActionStatus.PENDING && it.dueTime > 0L }
            .minByOrNull { it.dueTime }
    }

    fun shouldShowViewAll(actions: List<NextAction>, previewLimit: Int = PREVIEW_LIMIT): Boolean {
        val open = actions.count { it.status == ActionStatus.PENDING }
        val completed = actions.count { it.status == ActionStatus.COMPLETED }
        return open > previewLimit || completed > 0
    }

    fun remindersToDisplay(
        actions: List<NextAction>,
        filter: ReminderListFilter,
        expanded: Boolean,
        previewLimit: Int = PREVIEW_LIMIT
    ): List<NextAction> {
        val open = actions
            .filter { it.status == ActionStatus.PENDING }
            .sortedBy { it.dueTime }
        if (!expanded) return open.take(previewLimit)
        if (filter == ReminderListFilter.COMPLETED) {
            return actions
                .filter { it.status == ActionStatus.COMPLETED }
                .sortedByDescending { it.completedAt ?: 0L }
        }
        return open
    }

    fun defaultDueMillis(nowMillis: Long, zone: TimeZone): Long {
        return OutcomeSavePlanner.dueMillisForShortcut(DueShortcut.TODAY, nowMillis, zone)
    }

    fun todayShortcutMillis(nowMillis: Long, zone: TimeZone): Long = defaultDueMillis(nowMillis, zone)

    fun tomorrowShortcutMillis(nowMillis: Long, zone: TimeZone): Long {
        return OutcomeSavePlanner.dueMillisForShortcut(DueShortcut.TOMORROW, nowMillis, zone)
    }

    fun normalizeDueMillis(millis: Long, zone: TimeZone): Long {
        return Calendar.getInstance(zone).apply {
            timeInMillis = millis
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    fun validateTitle(title: String): String? {
        return if (title.trim().isEmpty()) "Please enter a title." else null
    }

    fun validateDueMillis(dueMillis: Long, nowMillis: Long): String? {
        if (dueMillis <= nowMillis) return "Choose a time that hasn't already passed."
        return null
    }

    fun dueLabel(
        dueTime: Long,
        nowMillis: Long,
        zone: TimeZone = TimeZone.getDefault(),
        locale: Locale = Locale.getDefault()
    ): String {
        if (dueTime <= 0L) return "No date"
        val formatted = OutcomeSavePlanner.formatDue(dueTime, zone, locale)
        return if (dueTime < nowMillis) "Overdue · $formatted" else formatted
    }

    fun formatDate(millis: Long, zone: TimeZone, locale: Locale = Locale.getDefault()): String {
        return formatWith("EEE, MMM d, yyyy", millis, zone, locale)
    }

    fun formatTime(millis: Long, zone: TimeZone, locale: Locale = Locale.getDefault()): String {
        return formatWith("h:mm a", millis, zone, locale)
    }

    fun syncAlarms(
        leadId: String,
        contactName: String,
        persisted: List<ReminderAlarmState>,
        actions: List<NextAction>,
        nowMillis: Long
    ): ReminderAlarmSync {
        val desired = actions.filter { action ->
            action.status == ActionStatus.PENDING &&
                action.dueTime > 0L &&
                shouldRestoreAlarm(action.dueTime, nowMillis)
        }
        val cancel = persisted.map { it.actionId }.filter { actionId ->
            val match = desired.find { it.id == actionId }
            val stored = persisted.first { it.actionId == actionId }
            match == null ||
                match.dueTime != stored.dueTime ||
                match.title != stored.title ||
                contactName != stored.contactName
        }.toSet()
        val schedule = desired.filter { action ->
            val stored = persisted.find { it.actionId == action.id }
            stored == null ||
                stored.dueTime != action.dueTime ||
                stored.title != action.title ||
                stored.contactName != contactName
        }.map { action ->
            ReminderAlarmState(
                leadId = leadId,
                actionId = action.id,
                dueTime = action.dueTime,
                title = action.title,
                contactName = contactName
            )
        }
        return ReminderAlarmSync(cancelActionIds = cancel, schedule = schedule)
    }

    fun shouldRestoreAlarm(dueTime: Long, nowMillis: Long): Boolean {
        return dueTime > nowMillis - RESTORE_GRACE_MS
    }

    fun completionRecordsContact(activity: ActivityRecord?): Boolean {
        if (activity == null) return false
        if (activity.type == ActionType.CALL || activity.type == ActionType.MESSAGE || activity.type == ActionType.MEETING) {
            return true
        }
        return contactOutcomes.any { outcome ->
            activity.outcome.equals(outcome, ignoreCase = true)
        }
    }

    private fun completionNote(action: NextAction): String {
        val title = action.title.trim()
        val notes = action.notes.trim()
        return if (notes.isEmpty()) title else "$title\n$notes"
    }

    private fun datedPendingCount(actions: List<NextAction>): Int {
        return actions.count { it.status == ActionStatus.PENDING && it.dueTime > 0L }
    }

    private fun earliestDatedPendingMillis(actions: List<NextAction>): Long? {
        return actions
            .filter { it.status == ActionStatus.PENDING && it.dueTime > 0L }
            .minOfOrNull { it.dueTime }
    }

    private data class FollowUpDecision(val write: FollowUpWrite, val millis: Long?)

    private fun followUpAfter(
        pendingActions: List<NextAction>,
        replacing: NextAction?,
        removingId: String?
    ): FollowUpDecision {
        val remaining = pendingActions.filter { action ->
            action.status == ActionStatus.PENDING &&
                action.id != removingId &&
                action.id != replacing?.id
        }.toMutableList()
        if (replacing != null && replacing.status == ActionStatus.PENDING) {
            remaining += replacing
        }
        val earliest = remaining.filter { it.dueTime > 0L }.minOfOrNull { it.dueTime }
        return if (earliest != null) {
            FollowUpDecision(FollowUpWrite.SET, earliest)
        } else {
            FollowUpDecision(FollowUpWrite.CLEAR, null)
        }
    }

    private fun formatWith(pattern: String, millis: Long, zone: TimeZone, locale: Locale): String {
        val format = SimpleDateFormat(pattern, locale)
        format.timeZone = zone
        return format.format(Date(millis))
    }
}
