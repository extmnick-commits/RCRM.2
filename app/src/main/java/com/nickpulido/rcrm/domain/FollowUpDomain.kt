package com.nickpulido.rcrm.domain

import java.util.UUID

enum class ActionType {
    CALL, MESSAGE, MEETING, TO_DO, EMAIL
}

enum class ActionStatus {
    PENDING, COMPLETED, CANCELLED, PAUSED
}

enum class WorkflowType {
    SALES, RECRUITING, GENERAL
}

data class NextAction(
    val id: String = UUID.randomUUID().toString(),
    val leadId: String = "",
    val ownerId: String = "",
    val workflow: WorkflowType = WorkflowType.GENERAL,
    val type: ActionType = ActionType.TO_DO,
    val title: String = "",
    val notes: String = "",
    val dueTime: Long = 0L,
    val status: ActionStatus = ActionStatus.PENDING,
    val appointmentId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
    val reason: String? = null // For paused/cancelled
)

data class ActivityRecord(
    val id: String = UUID.randomUUID().toString(),
    val leadId: String = "",
    val ownerId: String = "",
    val type: ActionType = ActionType.TO_DO,
    val outcome: String = "", // e.g. "Connected", "No answer", "Meeting completed"
    val note: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

data class WorkflowState(
    val lastAttemptedContact: Long? = null,
    val lastConnectedContact: Long? = null,
    val stageEntryTime: Long? = null,
    val isPaused: Boolean = false,
    val pausedReason: String? = null,
    val resumeDate: Long? = null
)
