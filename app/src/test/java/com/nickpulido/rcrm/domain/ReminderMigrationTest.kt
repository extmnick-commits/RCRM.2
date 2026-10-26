package com.nickpulido.rcrm.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class ReminderMigrationTest {

    private val zone: TimeZone = TimeZone.getTimeZone("UTC")

    @Test
    fun legacyMigrationRunsOnceAndUsesAStableIdentity() {
        val legacy = millis(2024, Calendar.JANUARY, 2, 15, 0)
        val first = ReminderPlanner.planMigration(
            leadId = "lead/123",
            ownerId = "owner123",
            storedVersion = 0,
            legacyFollowUpMillis = legacy,
            actions = emptyList(),
            nowMillis = legacy - 86_400_000L
        )
        val secondDevice = ReminderPlanner.planMigration(
            leadId = "lead/123",
            ownerId = "owner123",
            storedVersion = 0,
            legacyFollowUpMillis = legacy,
            actions = emptyList(),
            nowMillis = legacy - 86_400_000L
        )

        val created = first.actionToCreate
        assertEquals(ReminderPlanner.legacyActionId("lead/123"), created?.id)
        assertEquals(created?.id, secondDevice.actionToCreate?.id)
        assertEquals("Follow up", created?.title)
        assertEquals(ActionStatus.PENDING, created?.status)
        assertEquals(ReminderPlanner.normalizeDueMillis(legacy, TimeZone.getDefault()), created?.dueTime)
        assertEquals(ReminderPlanner.MIGRATION_VERSION, first.versionToStore)
        assertEquals(FollowUpWrite.SET, first.followUp)
        assertTrue(first.cancelLegacyPhoneAlarm)

        val again = ReminderPlanner.planMigration(
            leadId = "lead/123",
            ownerId = "owner123",
            storedVersion = first.versionToStore ?: 0,
            legacyFollowUpMillis = legacy,
            actions = emptyList(),
            nowMillis = legacy
        )
        assertNull(again.actionToCreate)
        assertNull(again.versionToStore)
        assertEquals(FollowUpWrite.UNCHANGED, again.followUp)
    }

    @Test
    fun emptyPendingListDoesNotRecreateAMigratedReminder() {
        val legacy = millis(2024, Calendar.MARCH, 1, 9, 0)
        val migrated = ReminderPlanner.planMigration(
            leadId = "lead123",
            ownerId = "owner123",
            storedVersion = ReminderPlanner.MIGRATION_VERSION,
            legacyFollowUpMillis = legacy,
            actions = emptyList(),
            nowMillis = legacy
        )
        assertNull(migrated.actionToCreate)
    }

    @Test
    fun completingOrDeletingTheLastReminderDoesNotRecreateIt() {
        val legacy = millis(2024, Calendar.MARCH, 1, 9, 0)
        val migrated = ReminderPlanner.planMigration(
            leadId = "lead123",
            ownerId = "owner123",
            storedVersion = 0,
            legacyFollowUpMillis = legacy,
            actions = emptyList(),
            nowMillis = legacy - 1_000L
        )
        val created = migrated.actionToCreate ?: error("Expected the legacy reminder")

        val completed = ReminderPlanner.planComplete(
            existing = created,
            pendingActions = listOf(created),
            activityAlreadyExists = false,
            nowMillis = legacy + 1_000L
        )
        assertEquals(ActionStatus.COMPLETED, completed.action?.status)
        assertEquals(FollowUpWrite.CLEAR, completed.followUp)
        assertNull(completed.followUpMillis)

        val afterComplete = ReminderPlanner.planMigration(
            leadId = "lead123",
            ownerId = "owner123",
            storedVersion = migrated.versionToStore ?: 0,
            legacyFollowUpMillis = legacy,
            actions = listOf(completed.action!!),
            nowMillis = legacy + 2_000L
        )
        assertNull(afterComplete.actionToCreate)

        val cancelled = ReminderPlanner.planCancel(created, listOf(created))
        assertEquals(ActionStatus.CANCELLED, cancelled.action?.status)
        assertEquals(FollowUpWrite.CLEAR, cancelled.followUp)
        assertNull(cancelled.activity)
        val afterDelete = ReminderPlanner.planMigration(
            leadId = "lead123",
            ownerId = "owner123",
            storedVersion = 0,
            legacyFollowUpMillis = legacy,
            actions = listOf(cancelled.action!!),
            nowMillis = legacy + 3_000L
        )
        assertNull(afterDelete.actionToCreate)
        assertEquals(FollowUpWrite.CLEAR, afterDelete.followUp)
    }

    @Test
    fun fourRemindersAreAccessibleThroughViewAll() {
        val reminders = (1..4).map { index ->
            reminder(id = "reminder-$index", dueTime = index * 3_600_000L, title = "Reminder $index")
        }
        val preview = ReminderPlanner.remindersToDisplay(
            actions = reminders,
            filter = ReminderListFilter.OPEN,
            expanded = false
        )
        val allOpen = ReminderPlanner.remindersToDisplay(
            actions = reminders,
            filter = ReminderListFilter.OPEN,
            expanded = true
        )
        assertEquals(3, preview.size)
        assertEquals(4, allOpen.size)
        assertEquals(reminders.map { it.id }, allOpen.map { it.id })
        assertTrue(ReminderPlanner.shouldShowViewAll(reminders))
        assertEquals("reminder-1", ReminderPlanner.earliestPending(reminders)?.id)
    }

    @Test
    fun notesSurviveSavingReopeningAndEditing() {
        val due = futureDue()
        val saved = ReminderPlanner.planCreate(
            leadId = "lead123",
            ownerId = "owner123",
            actionId = "action-notes",
            title = "Call Sam",
            notes = "Bring the application",
            type = ActionType.CALL,
            dueTime = due,
            nowMillis = due - 3_600_000L,
            zone = zone,
            pendingActions = emptyList()
        ).action ?: error("Expected a saved reminder")

        val reopened = ReminderPlanner.actionFrom(
            id = saved.id,
            leadId = saved.leadId,
            ownerId = saved.ownerId,
            typeName = saved.type.name,
            title = saved.title,
            notes = saved.notes,
            dueTime = saved.dueTime,
            statusName = saved.status.name
        )
        assertEquals("Bring the application", reopened.notes)

        val edited = ReminderPlanner.planEdit(
            existing = reopened,
            title = "Call Sam",
            notes = "Bring the application and ID",
            type = ActionType.CALL,
            nowMillis = due - 3_000_000L,
            zone = zone,
            pendingActions = listOf(reopened)
        )
        assertNull(edited.error)
        assertEquals(reopened.id, edited.action?.id)
        assertEquals(ActionStatus.PENDING, edited.action?.status)
        assertEquals("Bring the application and ID", edited.action?.notes)
        assertNull(edited.activity)
    }

    @Test
    fun rescheduleKeepsTheSameOpenReminderAndReplacesItsAlarm() {
        val originalDue = futureDue()
        val original = reminder(
            id = "same-reminder",
            dueTime = originalDue,
            title = "Call Sam",
            notes = "Bring the application",
            type = ActionType.CALL
        )
        val rescheduledDue = originalDue + 86_400_000L
        val plan = ReminderPlanner.planReschedule(
            existing = original,
            dueTime = rescheduledDue + 12_345L,
            nowMillis = originalDue - 60_000L,
            zone = zone,
            pendingActions = listOf(original)
        )

        assertNull(plan.error)
        assertEquals("same-reminder", plan.action?.id)
        assertEquals(ActionStatus.PENDING, plan.action?.status)
        assertEquals("Call Sam", plan.action?.title)
        assertEquals("Bring the application", plan.action?.notes)
        assertEquals(ActionType.CALL, plan.action?.type)
        assertEquals(ReminderPlanner.normalizeDueMillis(rescheduledDue + 12_345L, zone), plan.action?.dueTime)
        assertNull(plan.activity)
        assertTrue(plan.cancelAlarm)
        assertTrue(plan.scheduleAlarm)
        assertNotEquals(ActionStatus.COMPLETED, plan.action?.status)

        val persisted = listOf(
            ReminderAlarmState("lead123", original.id, original.dueTime, original.title, "Sam")
        )
        val sync = ReminderPlanner.syncAlarms(
            leadId = "lead123",
            contactName = "Sam",
            persisted = persisted,
            actions = listOf(plan.action!!),
            nowMillis = originalDue - 60_000L
        )
        assertTrue(sync.cancelActionIds.contains(original.id))
        assertEquals(plan.action?.dueTime, sync.schedule.single().dueTime)
    }

    @Test
    fun dashboardDateAdvancesAfterCompletionAndClearsOnTheLastOne() {
        val early = reminder(id = "early", dueTime = 1_700_000_000_000L)
        val later = reminder(id = "later", dueTime = 1_700_086_400_000L)
        val first = ReminderPlanner.planComplete(
            existing = early,
            pendingActions = listOf(early, later),
            activityAlreadyExists = false,
            nowMillis = 1_699_000_000_000L
        )
        assertEquals(FollowUpWrite.SET, first.followUp)
        assertEquals(later.dueTime, first.followUpMillis)
        assertEquals(later.id, ReminderPlanner.earliestPending(listOf(first.action!!, later))?.id)

        val last = ReminderPlanner.planComplete(
            existing = later,
            pendingActions = listOf(later),
            activityAlreadyExists = false,
            nowMillis = 1_700_000_100_000L
        )
        assertEquals(FollowUpWrite.CLEAR, last.followUp)
        assertNull(last.followUpMillis)
        assertNull(ReminderPlanner.earliestPending(listOf(first.action!!, last.action!!)))
    }

    @Test
    fun repeatedSavesAndRetriesDoNotDuplicateRemindersOrHistory() {
        val due = futureDue()
        val first = ReminderPlanner.planCreate(
            leadId = "lead123",
            ownerId = "owner123",
            actionId = "save-once",
            title = "Send the quote",
            notes = "Email draft",
            type = ActionType.EMAIL,
            dueTime = due,
            nowMillis = due - 60_000L,
            zone = zone,
            pendingActions = emptyList()
        )
        val retry = ReminderPlanner.planCreate(
            leadId = "lead123",
            ownerId = "owner123",
            actionId = "save-once",
            title = "Send the quote",
            notes = "Email draft",
            type = ActionType.EMAIL,
            dueTime = due,
            nowMillis = due - 60_000L,
            zone = zone,
            pendingActions = listOf(first.action!!)
        )
        val documents = linkedMapOf<String, NextAction>()
        listOf(first, retry).forEach { plan ->
            val action = plan.action ?: return@forEach
            documents[action.id] = action
        }
        assertEquals(1, documents.size)
        assertEquals(first.action?.id, retry.action?.id)

        val history = linkedMapOf<String, ActivityRecord>()
        val completed = ReminderPlanner.planComplete(
            existing = first.action!!,
            pendingActions = listOf(first.action!!),
            activityAlreadyExists = false,
            nowMillis = due - 30_000L
        )
        val completedRetry = ReminderPlanner.planComplete(
            existing = completed.action!!,
            pendingActions = emptyList(),
            activityAlreadyExists = true,
            nowMillis = due - 20_000L
        )
        listOfNotNull(completed.activity, completedRetry.activity).forEach { history[it.id] = it }
        assertEquals(1, history.size)
        assertEquals(
            ReminderPlanner.completionActivityId(first.action!!.id),
            completed.activity?.id
        )
        assertNull(completedRetry.activity)
        assertEquals(ReminderPlanner.COMPLETION_OUTCOME, completed.activity?.outcome)
        assertEquals(ActionType.TO_DO, completed.activity?.type)
        assertFalse(ReminderPlanner.completionRecordsContact(completed.activity))
    }

    @Test
    fun todayShortcutAndSaveValidationStayInTheFutureWithoutSeconds() {
        val evening = millis(2026, Calendar.OCTOBER, 25, 18, 30)
        val chosen = ReminderPlanner.todayShortcutMillis(evening, zone)
        assertTrue(chosen > evening)
        val chosenClock = clock(chosen)
        assertNotEquals(17, chosenClock.get(Calendar.HOUR_OF_DAY))

        val afternoon = millis(2026, Calendar.OCTOBER, 25, 15, 0)
        val today = ReminderPlanner.todayShortcutMillis(afternoon, zone)
        assertEquals(17, clock(today).get(Calendar.HOUR_OF_DAY))
        assertTrue(today > afternoon)

        val raw = millis(2026, Calendar.OCTOBER, 26, 19, 15, 45, 250)
        val saved = ReminderPlanner.planCreate(
            leadId = "lead123",
            ownerId = "owner123",
            actionId = "timed",
            title = "To-do",
            notes = "",
            type = ActionType.TO_DO,
            dueTime = raw,
            nowMillis = afternoon,
            zone = zone,
            pendingActions = emptyList()
        )
        val savedClock = clock(saved.action?.dueTime ?: 0L)
        assertEquals(0, savedClock.get(Calendar.SECOND))
        assertEquals(0, savedClock.get(Calendar.MILLISECOND))
        assertEquals(19, savedClock.get(Calendar.HOUR_OF_DAY))
        assertEquals(15, savedClock.get(Calendar.MINUTE))

        val overdue = ReminderPlanner.dueLabel(afternoon, evening, zone, java.util.Locale.US)
        assertTrue(overdue.startsWith("Overdue"))
        val upcoming = ReminderPlanner.dueLabel(chosen, afternoon, zone, java.util.Locale.US)
        assertFalse(upcoming.startsWith("Overdue"))

        val rejected = ReminderPlanner.planCreate(
            leadId = "lead123",
            ownerId = "owner123",
            actionId = "past",
            title = "Too late",
            notes = "",
            type = ActionType.CALL,
            dueTime = afternoon,
            nowMillis = evening,
            zone = zone,
            pendingActions = emptyList()
        )
        assertEquals("Choose a time that hasn't already passed.", rejected.error)
        assertNull(rejected.action)
        assertEquals(
            listOf(ActionType.CALL, ActionType.MESSAGE, ActionType.EMAIL, ActionType.TO_DO),
            ReminderPlanner.reminderTypeChoices(null)
        )
    }

    private fun reminder(
        id: String,
        dueTime: Long,
        title: String = "Follow up",
        notes: String = "",
        type: ActionType = ActionType.TO_DO
    ): NextAction {
        return NextAction(
            id = id,
            leadId = "lead123",
            ownerId = "owner123",
            type = type,
            title = title,
            notes = notes,
            dueTime = dueTime,
            status = ActionStatus.PENDING,
            createdAt = dueTime - 1_000L
        )
    }

    private fun futureDue(): Long {
        return millis(2026, Calendar.NOVEMBER, 2, 9, 0)
    }

    private fun millis(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        second: Int = 0,
        millis: Int = 0
    ): Long {
        return Calendar.getInstance(zone).apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month)
            set(Calendar.DAY_OF_MONTH, day)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, second)
            set(Calendar.MILLISECOND, millis)
        }.timeInMillis
    }

    private fun clock(millis: Long): Calendar {
        return Calendar.getInstance(zone).apply { timeInMillis = millis }
    }
}
