package com.nickpulido.rcrm.domain

import com.nickpulido.rcrm.AppointmentAgenda
import org.junit.Assert.*
import org.junit.Test

class SmartLogPlannerTest {
    @Test fun formattedUsNumbersMatchWithoutCreatingAnotherContact() {
        assertTrue(SmartLogPlanner.phonesMatch("+1 (209) 555-0123", "209-555-0123"))
    }

    @Test fun missingNumbersNeverMatchEachOther() {
        assertFalse(SmartLogPlanner.phonesMatch("", ""))
        assertFalse(SmartLogPlanner.phonesMatch("Unknown", "Private"))
    }

    @Test fun differentNumbersAndInternationalPrefixesStayDistinct() {
        assertFalse(SmartLogPlanner.phonesMatch("2095550123", "2095550124"))
        assertFalse(SmartLogPlanner.phonesMatch("+44 20 7946 0958", "+33 20 7946 0958"))
    }

    @Test fun blankOptionalDetailsDoNotOverwriteAnExistingProfile() {
        val updates = SmartLogPlanner.profileUpdates(mapOf("name" to " Maria ", "email" to "", "category" to " ", "company" to ""))
        val existing = mutableMapOf<String, Any?>("email" to "maria@example.com", "category" to "Client", "company" to "Acme")
        existing.putAll(updates)
        assertEquals("Maria", existing["name"])
        assertEquals("maria@example.com", existing["email"])
        assertEquals("Client", existing["category"])
        assertEquals("Acme", existing["company"])
    }

    @Test fun outcomeWithoutAnExplicitReminderDoesNotCompleteAnUnrelatedTask() {
        val plan = OutcomeSavePlanner.plan(
            request = request(nextAction = null), existingNotes = "", existingCategory = "Client",
            pendingActions = listOf(PendingActionRef("send_quote", 5000L))
        )
        assertNull(plan.completeActionId)
        assertEquals(ActionType.CALL, plan.activity.type)
        assertEquals("Connected", plan.activity.outcome)
    }

    @Test fun followUpIsAdditiveAndRetryUsesTheSameActionId() {
        val request = request(NextActionSpec("Send information", 6000L, ActionType.MESSAGE))
        val plan = OutcomeSavePlanner.plan(request, "", "Prospect", listOf(PendingActionRef("existing", 5000L)))
        assertNull(plan.completeActionId)
        assertEquals("next_smart-test", plan.newAction?.id)
        assertEquals(ActionType.MESSAGE, plan.newAction?.type)
        assertEquals(5000L, plan.leadPatch.followUpMillis)
        assertTrue(OutcomeSavePlanner.alreadyApplied(setOf(plan.activity.id), request.operationId))
    }

    @Test fun addingAnAppointmentPreservesLegacyAppointments() {
        val records = SmartLogPlanner.addAppointment("contact", emptyList(), 4000L, "Office", 42L,
            "smart-test", 6000L, "Video call", 1000L)
        assertEquals(2, records.size)
        assertEquals(42L, records.first { it.id == "legacy:contact" }.eventId)
        assertEquals(6000L, records.first { it.id == "smart-test" }.startMillis)
    }

    @Test fun retryKeepsTheExistingCalendarEventAndDoesNotDuplicateTheAppointment() {
        val existing = AppointmentAgenda.AppointmentRecord("smart-test", 6000L, "Office", 42L,
            AppointmentAgenda.AppointmentStatus.SCHEDULED, null, 1000L)
        val records = SmartLogPlanner.addAppointment("contact", listOf(existing), 6000L, "Office", 42L,
            "smart-test", 6000L, "Office", 1000L)
        assertEquals(1, records.size)
        assertEquals(42L, records.single().eventId)
    }

    @Test fun addingAnAppointmentKeepsOtherScheduledRecords() {
        val existing = AppointmentAgenda.AppointmentRecord("other", 3000L, "Office", 11L,
            AppointmentAgenda.AppointmentStatus.SCHEDULED, null, 1000L)
        val records = SmartLogPlanner.addAppointment("contact", listOf(existing), null, null, null,
            "smart-test", 6000L, "Office", 1000L)
        assertEquals(existing, records.first { it.id == "other" })
        assertEquals(2, records.size)
    }

    private fun request(nextAction: NextActionSpec?) = OutcomeSaveRequest(
        operationId = "smart-test", leadId = "contact", ownerId = "owner", outcome = "Connected",
        note = "Discussed the quote", activityType = ActionType.CALL, stage = null,
        currentActionId = null, resolveCurrentActionIfMissing = false, nextAction = nextAction,
        clearExistingNextAction = false, lifecycle = WorkflowLifecycle.UNCHANGED,
        lifecycleReason = null, nowMillis = 1000L
    )
}
