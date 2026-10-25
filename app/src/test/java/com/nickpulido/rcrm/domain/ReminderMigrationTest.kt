package com.nickpulido.rcrm.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class ReminderMigrationTest {

    @Test
    fun testMigrationGeneratesSingleAction() {
        val legacyFollowUpDate = 1700000000000L
        val loadedActions = emptyList<NextAction>()
        
        // Simulating the migration logic in FollowUpActivity
        val migratedAction = if (loadedActions.isEmpty() && legacyFollowUpDate > 0) {
            NextAction(
                id = UUID.randomUUID().toString(),
                leadId = "lead123",
                ownerId = "owner123",
                type = ActionType.TO_DO,
                title = "Follow up",
                dueTime = legacyFollowUpDate,
                status = ActionStatus.PENDING
            )
        } else null
        
        assertNotNull(migratedAction)
        assertEquals(legacyFollowUpDate, migratedAction?.dueTime)
        assertEquals("Follow up", migratedAction?.title)
    }

    @Test
    fun testMultipleRemindersDoNotReplaceEachOther() {
        // Simulating multiple reminders
        val action1 = NextAction(
            id = "action_1",
            title = "Call morning",
            dueTime = 1000L
        )
        val action2 = NextAction(
            id = "action_2",
            title = "Call evening",
            dueTime = 5000L
        )
        val pendingActions = listOf(action1, action2)
        
        val sorted = pendingActions.sortedBy { it.dueTime }
        assertEquals("Call morning", sorted[0].title)
        assertEquals("Call evening", sorted[1].title)
    }
    
    @Test
    fun testDuplicatePreventionWithStableIds() {
        val operationId = "save_op_123"
        val actId1 = OutcomeSavePlanner.activityDocumentId(operationId)
        val actId2 = OutcomeSavePlanner.activityDocumentId(operationId)
        
        // Stable IDs ensure idempotency
        assertEquals(actId1, actId2)
        
        val existingActivityIds = setOf(actId1)
        assertTrue(OutcomeSavePlanner.alreadyApplied(existingActivityIds, operationId))
    }
    
    @Test
    fun testReschedulingUpdatesCorrectly() {
        val plan = OutcomeSavePlanner.plan(
            request = OutcomeSaveRequest(
                operationId = "reschedule_op",
                leadId = "lead123",
                ownerId = "owner123",
                outcome = null,
                note = "",
                activityType = ActionType.CALL,
                stage = null,
                currentActionId = "old_action_123",
                resolveCurrentActionIfMissing = true,
                nextAction = NextActionSpec(
                    title = "Rescheduled Call",
                    dueTime = 9000L,
                    type = ActionType.CALL
                ),
                clearExistingNextAction = false,
                lifecycle = WorkflowLifecycle.UNCHANGED,
                lifecycleReason = null,
                nowMillis = 8000L
            ),
            existingNotes = "",
            existingCategory = "",
            pendingActions = listOf(PendingActionRef("old_action_123", 1000L))
        )
        
        // The old action is completed/cancelled, and a new one is scheduled
        assertEquals("old_action_123", plan.completeActionId)
        assertEquals(ActionStatus.COMPLETED, plan.completeStatus)
        
        assertNotNull(plan.newAction)
        assertEquals("Rescheduled Call", plan.newAction?.title)
        assertEquals(9000L, plan.newAction?.dueTime)
        
        // Follow-up date is updated to the new due time
        assertEquals(FollowUpWrite.SET, plan.leadPatch.followUp)
        assertEquals(9000L, plan.leadPatch.followUpMillis)
    }
}
