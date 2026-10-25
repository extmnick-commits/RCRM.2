package com.nickpulido.rcrm.domain

import com.nickpulido.rcrm.AppointmentAgenda
import com.nickpulido.rcrm.ui.components.PipelineType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class OutcomeSaveTest {

    @Test
    fun pipelineSaveKeepsTheFollowUpTheSecondCallUsedToClear() {
        val chosen = 1_800_000_000_000L
        val buggySecondCall = request(
            outcome = "Connected",
            nextAction = null,
            clearExistingNextAction = true
        )
        val cleared = OutcomeSavePlanner.plan(buggySecondCall, "old", "", emptyList())
        assertEquals(FollowUpWrite.CLEAR, cleared.leadPatch.followUp)

        val saved = OutcomeSavePlanner.plan(
            request(
                stage = StageChangeSpec(PipelineType.SALES, "contacted", "Contacted"),
                outcome = "Connected",
                note = "Spoke with them",
                nextAction = NextActionSpec("Follow up", chosen, ActionType.CALL),
                clearExistingNextAction = false,
                currentActionId = "task-current"
            ),
            existingNotes = "old",
            existingCategory = "",
            pendingActions = listOf(PendingActionRef("task-current", chosen - 86_400_000L))
        )

        assertEquals(FollowUpWrite.SET, saved.leadPatch.followUp)
        assertEquals(chosen, saved.leadPatch.followUpMillis)
        assertEquals("act_op-1", saved.activity.id)
        assertEquals("next_op-1", saved.newAction?.id)
        assertEquals(ActionType.CALL, saved.newAction?.type)
        assertEquals("task-current", saved.completeActionId)
        assertEquals("salesStage", saved.leadPatch.stageField)
        assertEquals("contacted", saved.leadPatch.stageValue)
        assertTrue(saved.leadPatch.notes!!.contains("Moved to Contacted stage"))
        assertTrue(saved.leadPatch.notes!!.contains("Spoke with them"))
        assertTrue(saved.leadPatch.notes!!.contains("old"))
    }

    @Test
    fun sameOperationIdReusesTheSameDocuments() {
        val first = OutcomeSavePlanner.plan(
            request(nextAction = NextActionSpec("Follow up", 1_800_000_000_000L, ActionType.EMAIL)),
            "",
            "",
            emptyList()
        )
        val retry = OutcomeSavePlanner.plan(
            request(nextAction = NextActionSpec("Follow up", 1_800_000_000_000L, ActionType.EMAIL)),
            "",
            "",
            emptyList()
        )
        assertEquals(first.activity.id, retry.activity.id)
        assertEquals(first.newAction?.id, retry.newAction?.id)
        assertTrue(OutcomeSavePlanner.alreadyApplied(setOf(first.activity.id), "op-1"))
        assertFalse(OutcomeSavePlanner.alreadyApplied(emptySet(), "op-1"))
    }

    @Test
    fun leavingTheNextActionUnchangedDoesNotClearTheFollowUp() {
        val plan = OutcomeSavePlanner.plan(
            request(outcome = "No answer", nextAction = null, clearExistingNextAction = false),
            "",
            "",
            listOf(PendingActionRef("later", 5_000L))
        )
        assertEquals(FollowUpWrite.UNCHANGED, plan.leadPatch.followUp)
        assertNull(plan.newAction)
        assertNull(plan.completeActionId)
        assertNull(plan.leadPatch.isPaused)
        assertNull(plan.leadPatch.isClosed)
    }

    @Test
    fun explicitClearCancelsOnlyTheCurrentTaskAndKeepsAnEarlierUnrelatedOne() {
        val plan = OutcomeSavePlanner.plan(
            request(
                outcome = null,
                stage = StageChangeSpec(PipelineType.SALES, "appt_set", "Appointment Set"),
                clearExistingNextAction = true,
                currentActionId = "current",
                resolveCurrentActionIfMissing = true
            ),
            "",
            "",
            listOf(
                PendingActionRef("current", 9_000L),
                PendingActionRef("other", 4_000L)
            )
        )
        assertEquals("current", plan.completeActionId)
        assertEquals(ActionStatus.CANCELLED, plan.completeStatus)
        assertNull(plan.newAction)
        assertEquals(FollowUpWrite.SET, plan.leadPatch.followUp)
        assertEquals(4_000L, plan.leadPatch.followUpMillis)
        assertNull(plan.leadPatch.lastAttemptedContact)
    }

    @Test
    fun stageChangeAloneDoesNotInventAnOutcomeOrAFollowUp() {
        val plan = OutcomeSavePlanner.plan(
            request(
                outcome = null,
                note = "",
                stage = StageChangeSpec(PipelineType.RECRUITING, "prospect", "Recruit Prospect"),
                nextAction = null
            ),
            "existing",
            "Recruit",
            listOf(PendingActionRef("keep", 2_000L))
        )
        assertEquals("Stage Changed", plan.activity.outcome)
        assertEquals(ActionType.TO_DO, plan.activity.type)
        assertNull(plan.newAction)
        assertNull(plan.completeActionId)
        assertEquals(FollowUpWrite.UNCHANGED, plan.leadPatch.followUp)
        assertEquals("recruitStage", plan.leadPatch.stageField)
        assertNull(plan.leadPatch.lastAttemptedContact)
        assertNull(plan.leadPatch.isPaused)
        assertTrue(plan.leadPatch.notes!!.contains("Moved to Recruit Prospect stage"))
    }

    @Test
    fun doesNotScheduleAFollowUpTheUserDidNotChoose() {
        val now = 1_700_000_000_000L
        val plan = OutcomeSavePlanner.plan(
            request(outcome = "No-show", nextAction = null, nowMillis = now),
            "",
            "",
            emptyList()
        )
        assertNull(plan.newAction)
        assertNotEquals(now + 86_400_000L, plan.leadPatch.followUpMillis)
        assertEquals(ActionType.MEETING, plan.activity.type)
    }

    @Test
    fun pauseAndCloseAreSeparateAndDoNotCreateAnAction() {
        val paused = OutcomeSavePlanner.plan(
            request(
                outcome = "Not interested",
                lifecycle = WorkflowLifecycle.PAUSE,
                lifecycleReason = "On vacation",
                nextAction = NextActionSpec("Follow up", 1_800_000_000_000L, ActionType.CALL),
                currentActionId = "current"
            ),
            "",
            "",
            listOf(
                PendingActionRef("current", 1_000L),
                PendingActionRef("other", 2_000L)
            )
        )
        assertTrue(paused.leadPatch.isPaused == true)
        assertTrue(paused.leadPatch.isClosed == false)
        assertEquals(FollowUpWrite.CLEAR, paused.leadPatch.followUp)
        assertNull(paused.newAction)
        assertEquals("current", paused.completeActionId)
        assertEquals(ActionStatus.CANCELLED, paused.completeStatus)
        assertEquals("On vacation", paused.completeReason)
        assertTrue(paused.leadPatch.notes!!.contains("Paused: On vacation"))

        val closed = OutcomeSavePlanner.plan(
            request(
                outcome = null,
                lifecycle = WorkflowLifecycle.CLOSE,
                lifecycleReason = "Won",
                currentActionId = "current"
            ),
            "",
            "Client",
            listOf(PendingActionRef("current", 1_000L))
        )
        assertTrue(closed.leadPatch.isClosed == true)
        assertTrue(closed.leadPatch.isPaused == false)
        assertEquals(FollowUpWrite.CLEAR, closed.leadPatch.followUp)
        assertNull(closed.newAction)
        assertEquals(ActionStatus.COMPLETED, closed.completeStatus)
        assertTrue(closed.leadPatch.notes!!.contains("Closed: Won"))
    }

    @Test
    fun inForceAddsClientWithoutDroppingTheExistingCategory() {
        val plan = OutcomeSavePlanner.plan(
            request(
                outcome = null,
                stage = StageChangeSpec(PipelineType.SALES, "in_force", "Client / In-Force")
            ),
            "",
            "Prospect",
            emptyList()
        )
        assertEquals("Prospect, Client", plan.leadPatch.category)
    }

    @Test
    fun resolvesTheEarliestOpenTaskWhenTheCallerDoesNotHaveAnId() {
        val plan = OutcomeSavePlanner.plan(
            request(
                outcome = "Connected",
                currentActionId = null,
                resolveCurrentActionIfMissing = true
            ),
            "",
            "",
            listOf(
                PendingActionRef("later", 500L),
                PendingActionRef("sooner", 100L)
            )
        )
        assertEquals("sooner", plan.completeActionId)
        assertEquals(ActionStatus.COMPLETED, plan.completeStatus)
        assertEquals(FollowUpWrite.SET, plan.leadPatch.followUp)
        assertEquals(500L, plan.leadPatch.followUpMillis)
        assertEquals(plan.activity.timestamp, plan.leadPatch.lastConnectedContact)
    }

    @Test
    fun schedulingResumesAPausedWorkflowWithoutErasingASoonerTask() {
        val plan = OutcomeSavePlanner.plan(
            request(
                outcome = "Message sent",
                nextAction = NextActionSpec("Send application", 9_000L, ActionType.EMAIL, "appt-1"),
                currentActionId = "current"
            ),
            "",
            "",
            listOf(
                PendingActionRef("current", 8_000L),
                PendingActionRef("other", 3_000L)
            )
        )
        assertEquals("appt-1", plan.newAction?.appointmentId)
        assertEquals(ActionType.EMAIL, plan.newAction?.type)
        assertEquals(ActionType.EMAIL, plan.activity.type)
        assertEquals(false, plan.leadPatch.isPaused)
        assertEquals(false, plan.leadPatch.isClosed)
        assertEquals(3_000L, plan.leadPatch.followUpMillis)
        assertEquals("current", plan.completeActionId)
    }

    @Test
    fun failedSaveKeepsTheDraftAndASuccessfulSaveCloses() {
        val draft = OutcomeDraft(
            outcome = "No-show",
            note = "Traffic on the bridge",
            nextTitle = "Retry visit",
            dueMillis = 1_800_000_000_000L,
            actionType = ActionType.MEETING,
            scheduleNext = true,
            clearExisting = false,
            lifecycle = WorkflowLifecycle.UNCHANGED,
            lifecycleReason = "",
            appointmentId = "appt-9",
            operationId = "op-1"
        )
        val failed = OutcomeSavePlanner.afterSave(false, "The network dropped.", draft)
        assertFalse(failed.dismiss)
        assertEquals(draft, failed.draft)
        assertEquals("The network dropped.", failed.errorMessage)
        assertEquals("No-show", failed.draft.outcome)
        assertEquals("Traffic on the bridge", failed.draft.note)

        val blank = OutcomeSavePlanner.afterSave(false, "  ", draft)
        assertFalse(blank.dismiss)
        assertTrue(blank.errorMessage!!.contains("Couldn't save"))

        val saved = OutcomeSavePlanner.afterSave(true, null, draft)
        assertTrue(saved.dismiss)
        assertNull(saved.errorMessage)
        assertEquals(draft, saved.draft)
    }

    @Test
    fun savingBlocksAnotherSubmitAndDismissDoesNotSave() {
        assertFalse(OutcomeSavePlanner.canSubmit(isSaving = true))
        assertTrue(OutcomeSavePlanner.canSubmit(isSaving = false))
        assertFalse(OutcomeSavePlanner.savesOnDismiss())
    }

    @Test
    fun stageChangeCanSaveWithoutAnOutcomeAndAChosenActionNeedsADate() {
        assertNull(
            OutcomeSavePlanner.outcomeFormError(
                outcomeRequired = false,
                outcome = "",
                scheduleNext = false,
                nextTitle = "",
                dueMillis = null,
                lifecycle = WorkflowLifecycle.UNCHANGED
            )
        )
        assertEquals(
            "Choose an outcome, or use Pause or Close.",
            OutcomeSavePlanner.outcomeFormError(
                outcomeRequired = true,
                outcome = "",
                scheduleNext = false,
                nextTitle = "",
                dueMillis = null,
                lifecycle = WorkflowLifecycle.UNCHANGED
            )
        )
        assertNull(
            OutcomeSavePlanner.outcomeFormError(
                outcomeRequired = true,
                outcome = "",
                scheduleNext = false,
                nextTitle = "",
                dueMillis = null,
                lifecycle = WorkflowLifecycle.PAUSE
            )
        )
        assertEquals(
            "Choose a due date, or turn off the next action.",
            OutcomeSavePlanner.outcomeFormError(
                outcomeRequired = false,
                outcome = "",
                scheduleNext = true,
                nextTitle = "Follow up",
                dueMillis = null,
                lifecycle = WorkflowLifecycle.UNCHANGED
            )
        )
    }

    @Test
    fun builderDoesNotCreateAnActionUnlessSchedulingIsChosen() {
        val now = 1_700_000_000_000L
        val submission = OutcomeSubmission(
            operationId = "op/1",
            outcome = "No-show",
            note = " Missed ",
            scheduleNext = false,
            nextTitle = "Follow up",
            nextTime = now + 86_400_000L,
            nextType = ActionType.CALL,
            clearExisting = false,
            lifecycle = WorkflowLifecycle.UNCHANGED,
            lifecycleReason = "",
            linkedAppointmentId = "appt-1"
        )
        val request = OutcomeSavePlanner.buildSaveRequest(
            submission = submission,
            leadId = "lead-1",
            ownerId = "owner-1",
            stage = null,
            currentActionId = "task-1",
            nowMillis = now
        )
        assertEquals("op_1", request.operationId)
        assertEquals("No-show", request.outcome)
        assertEquals("Missed", request.note)
        assertNull(request.nextAction)
        assertEquals(ActionType.MEETING, request.activityType)
        assertEquals("task-1", request.currentActionId)
        assertTrue(request.resolveCurrentActionIfMissing)

        val scheduled = OutcomeSavePlanner.buildSaveRequest(
            submission.copy(scheduleNext = true, nextType = ActionType.EMAIL),
            leadId = "lead-1",
            ownerId = "owner-1",
            stage = StageChangeSpec(PipelineType.SALES, "contacted", "Contacted"),
            currentActionId = "task-1",
            nowMillis = now
        )
        assertEquals("Follow up", scheduled.nextAction?.title)
        assertEquals(now + 86_400_000L, scheduled.nextAction?.dueTime)
        assertEquals(ActionType.EMAIL, scheduled.nextAction?.type)
        assertEquals("appt-1", scheduled.nextAction?.appointmentId)
        assertFalse(scheduled.clearExistingNextAction)
    }

    @Test
    fun noShowGetsAColumnWideEnoughForTheWholeLabel() {
        val noShowWidth = 180
        val widths = listOf(120, 140, noShowWidth, 210, 130)
        val available = 520
        val padding = 32
        val gap = 16
        val columns = OutcomeSavePlanner.outcomeColumnCount(
            textWidthsPx = widths,
            availableWidthPx = available,
            horizontalPaddingPx = padding,
            gapPx = gap,
            minCellPx = 48,
            maxColumns = 3
        )
        val content = OutcomeSavePlanner.columnContentWidthPx(available, columns, gap, padding)
        assertTrue(columns < 3)
        assertTrue(content >= noShowWidth)
        assertTrue(
            OutcomeSavePlanner.choiceLabelsFit(
                textWidthsPx = widths,
                availableWidthPx = available,
                horizontalPaddingPx = padding,
                gapPx = gap,
                minCellPx = 48
            )
        )
    }

    @Test
    fun narrowWidthFallsBackToASingleColumn() {
        val widths = listOf(180, 160)
        val columns = OutcomeSavePlanner.outcomeColumnCount(
            textWidthsPx = widths,
            availableWidthPx = 200,
            horizontalPaddingPx = 32,
            gapPx = 16,
            minCellPx = 48,
            maxColumns = 3
        )
        assertEquals(1, columns)
        assertFalse(
            OutcomeSavePlanner.choiceLabelsFit(
                textWidthsPx = widths,
                availableWidthPx = 200,
                horizontalPaddingPx = 32,
                gapPx = 16,
                minCellPx = 48
            )
        )
    }

    @Test
    fun shortcutsUseAnExactLocalTime() {
        val zone = TimeZone.getTimeZone("UTC")
        val afternoon = calendarMillis(zone, 2026, Calendar.OCTOBER, 24, 15, 0)
        val today = OutcomeSavePlanner.dueMillisForShortcut(DueShortcut.TODAY, afternoon, zone)
        val todayCal = Calendar.getInstance(zone).apply { timeInMillis = today }
        assertEquals(17, todayCal.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, todayCal.get(Calendar.MINUTE))
        assertEquals(24, todayCal.get(Calendar.DAY_OF_MONTH))

        val evening = calendarMillis(zone, 2026, Calendar.OCTOBER, 24, 18, 0)
        val laterToday = OutcomeSavePlanner.dueMillisForShortcut(DueShortcut.TODAY, evening, zone)
        val laterCal = Calendar.getInstance(zone).apply { timeInMillis = laterToday }
        assertEquals(19, laterCal.get(Calendar.HOUR_OF_DAY))
        assertEquals(24, laterCal.get(Calendar.DAY_OF_MONTH))

        val tomorrow = OutcomeSavePlanner.dueMillisForShortcut(DueShortcut.TOMORROW, afternoon, zone)
        val tomorrowCal = Calendar.getInstance(zone).apply { timeInMillis = tomorrow }
        assertEquals(25, tomorrowCal.get(Calendar.DAY_OF_MONTH))
        assertEquals(9, tomorrowCal.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, tomorrowCal.get(Calendar.MINUTE))

        val label = OutcomeSavePlanner.formatDue(today, zone, java.util.Locale.US)
        assertTrue(label.contains("Oct"))
        assertTrue(label.contains("5:00"))
        assertFalse(label.contains("86400000"))
    }

    @Test
    fun relevantAppointmentsSkipClosedOnesAndTheLegacyMirror() {
        val now = calendarMillis(TimeZone.getTimeZone("UTC"), 2026, Calendar.OCTOBER, 24, 12, 0)
        val soon = now + 3_600_000L
        val choices = OutcomeSavePlanner.relevantAppointments(
            records = listOf(
                appointment("done", soon, AppointmentAgenda.AppointmentStatus.COMPLETED),
                appointment("open", soon + 3_600_000L, AppointmentAgenda.AppointmentStatus.SCHEDULED)
            ),
            legacyStartMillis = soon,
            nowMillis = now,
            zone = TimeZone.getTimeZone("UTC"),
            locale = java.util.Locale.US
        )
        assertEquals(1, choices.size)
        assertEquals("open", choices.first().id)
        assertFalse(choices.first().label.contains("done"))
    }

    @Test
    fun headerUsesTheContactNameAndNeverALeadId() {
        assertEquals("Fingerprint Security", OutcomeSavePlanner.outcomeContactName("  Fingerprint Security "))
        assertEquals("Contact", OutcomeSavePlanner.outcomeContactName("  "))
        val subtitle = OutcomeSavePlanner.outcomeHeaderSubtitle("Client Sales", "New Lead")
        assertEquals("Client Sales · New Lead", subtitle)
        assertFalse(subtitle.contains("lead_"))
        assertFalse(OutcomeSavePlanner.outcomeContactName("Fingerprint Security").contains("abc123"))
    }

    private fun request(
        outcome: String? = "Connected",
        note: String = "",
        stage: StageChangeSpec? = null,
        nextAction: NextActionSpec? = null,
        clearExistingNextAction: Boolean = false,
        currentActionId: String? = null,
        resolveCurrentActionIfMissing: Boolean = false,
        lifecycle: WorkflowLifecycle = WorkflowLifecycle.UNCHANGED,
        lifecycleReason: String? = null,
        nowMillis: Long = 1_600_000_000_000L
    ): OutcomeSaveRequest {
        return OutcomeSaveRequest(
            operationId = "op-1",
            leadId = "lead-1",
            ownerId = "owner-1",
            outcome = outcome,
            note = note,
            activityType = OutcomeSavePlanner.activityTypeForOutcome(
                outcome,
                nextAction?.type
            ),
            stage = stage,
            currentActionId = currentActionId,
            resolveCurrentActionIfMissing = resolveCurrentActionIfMissing,
            nextAction = nextAction,
            clearExistingNextAction = clearExistingNextAction,
            lifecycle = lifecycle,
            lifecycleReason = lifecycleReason,
            nowMillis = nowMillis
        )
    }

    private fun appointment(
        id: String,
        start: Long,
        status: AppointmentAgenda.AppointmentStatus
    ): AppointmentAgenda.AppointmentRecord {
        return AppointmentAgenda.AppointmentRecord(
            id = id,
            startMillis = start,
            location = "Office",
            eventId = null,
            status = status,
            originalInstanceTime = null,
            updatedAtMillis = start
        )
    }

    private fun calendarMillis(
        zone: TimeZone,
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int
    ): Long {
        return Calendar.getInstance(zone).apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month)
            set(Calendar.DAY_OF_MONTH, day)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}
