package com.nickpulido.rcrm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppointmentSyncTest {

    private val hour = 3_600_000L

    @Test
    fun deleteSentinelsStayOutOfTheLocalLead() {
        val sentinel = Any()
        val serverValue = Any()
        val split = AppointmentSync.splitLocalLeadUpdate(
            updates = mapOf(
                "name" to "Ada",
                "appointmentDate" to sentinel,
                "appointmentLocation" to sentinel,
                "googleCalendarEventId" to sentinel,
                "followUpDate" to null,
                "timestamp" to serverValue
            ),
            isDeleteSentinel = { it === sentinel },
            isServerValue = { it === serverValue }
        )
        assertEquals("Ada", split.put["name"])
        assertFalse(split.put.containsKey("appointmentDate"))
        assertFalse(split.put.values.any { it === sentinel || it === serverValue })
        assertEquals(setOf("appointmentDate", "appointmentLocation", "googleCalendarEventId", "followUpDate"), split.remove)
    }

    @Test
    fun failedCancellationRetriesAsCancelWithTheSavedEvent() {
        val operation = AppointmentSync.operation(
            kind = AppointmentSync.SyncOperation.Kind.CANCEL_CALENDAR_EVENT,
            leadId = "lead-1",
            appointmentId = "appt-1",
            intendedStartMillis = 9 * hour,
            eventId = 42L,
            outcome = AppointmentAgenda.Outcome.CANCELED,
            baseUpdatedAtMillis = 100L,
            message = "Couldn't cancel the calendar event."
        )
        val fresh = listOf(record("appt-1", start = 9 * hour, status = AppointmentAgenda.AppointmentStatus.CANCELED, updatedAt = 100L))
        val directive = AppointmentSync.retryDirective(operation, fresh)
        assertTrue(directive is AppointmentSync.RetryDirective.CancelEvent)
        assertEquals(42L, (directive as AppointmentSync.RetryDirective.CancelEvent).eventId)
    }

    @Test
    fun rescheduleRetryUsesTheIntendedTime() {
        val operation = AppointmentSync.operation(
            kind = AppointmentSync.SyncOperation.Kind.RESCHEDULE,
            leadId = "lead-1",
            appointmentId = "appt-1",
            intendedStartMillis = 14 * hour,
            eventId = 7L,
            baseUpdatedAtMillis = 100L,
            message = "Couldn't save that appointment."
        )
        val stillOld = AppointmentSync.retryDirective(
            operation,
            listOf(record("appt-1", start = 10 * hour, updatedAt = 100L))
        )
        val write = stillOld as AppointmentSync.RetryDirective.Apply
        val mutation = write.mutation as AppointmentSync.AppointmentMutation.Reschedule
        assertEquals(14 * hour, mutation.newStartMillis)

        val alreadyMoved = AppointmentSync.retryDirective(
            operation,
            listOf(record("appt-1", start = 14 * hour, updatedAt = 250L, eventId = 7L))
        )
        val push = alreadyMoved as AppointmentSync.RetryDirective.PushCalendar
        assertEquals(14 * hour, push.startMillis)
        assertEquals(7L, push.eventId)
    }

    @Test
    fun outcomeRetryDoesNotBecomeARescheduleAfterPartialSuccess() {
        val operation = AppointmentSync.operation(
            kind = AppointmentSync.SyncOperation.Kind.SAVE_OUTCOME,
            leadId = "lead-1",
            appointmentId = "appt-1",
            intendedStartMillis = 10 * hour,
            eventId = 7L,
            outcome = AppointmentAgenda.Outcome.CANCELED,
            baseUpdatedAtMillis = 100L,
            message = "Couldn't save that appointment."
        )
        val savedButNotCanceled = AppointmentSync.retryDirective(
            operation,
            listOf(record("appt-1", start = 10 * hour, status = AppointmentAgenda.AppointmentStatus.CANCELED, updatedAt = 200L, eventId = 7L))
        )
        assertTrue(savedButNotCanceled is AppointmentSync.RetryDirective.CancelEvent)

        val notSaved = AppointmentSync.retryDirective(
            operation,
            listOf(record("appt-1", start = 10 * hour, updatedAt = 100L, eventId = 7L))
        )
        val mutation = (notSaved as AppointmentSync.RetryDirective.Apply).mutation
        assertTrue(mutation is AppointmentSync.AppointmentMutation.SaveOutcome)
    }

    @Test
    fun overlappingSavesKeepUnrelatedAppointmentsAndNewerTimes() {
        val first = record("appt-a", start = 10 * hour, updatedAt = 100L)
        val second = record("appt-b", start = 15 * hour, updatedAt = 100L, eventId = 3L)
        val rescheduled = AppointmentSync.applyAppointmentMutation(
            leadId = "lead-1",
            fresh = listOf(first, second),
            mutation = AppointmentSync.AppointmentMutation.Reschedule(
                appointmentId = "appt-a",
                newStartMillis = 14 * hour,
                nowMillis = 200L,
                rememberInstance = false,
                baseUpdatedAtMillis = 100L
            )
        )
        assertEquals(14 * hour, rescheduled.records.first { it.id == "appt-a" }.startMillis)
        assertEquals(second, rescheduled.records.first { it.id == "appt-b" })

        val withNew = AppointmentSync.applyAppointmentMutation(
            leadId = "lead-1",
            fresh = rescheduled.records,
            mutation = AppointmentSync.AppointmentMutation.Upsert(
                record = record("appt-c", start = 18 * hour, updatedAt = 300L),
                baseUpdatedAtMillis = 0L
            )
        )
        assertEquals(listOf("appt-a", "appt-b", "appt-c"), withNew.records.map { it.id })

        val linked = AppointmentSync.applyAppointmentMutation(
            leadId = "lead-1",
            fresh = withNew.records,
            mutation = AppointmentSync.AppointmentMutation.SetEventId("appt-a", 55L)
        )
        assertEquals(14 * hour, linked.records.first { it.id == "appt-a" }.startMillis)
        assertEquals(55L, linked.records.first { it.id == "appt-a" }.eventId)
        assertEquals(3L, linked.records.first { it.id == "appt-b" }.eventId)
        assertNull(linked.records.first { it.id == "appt-c" }.eventId)

        val stale = AppointmentSync.applyAppointmentMutation(
            leadId = "lead-1",
            fresh = linked.records,
            mutation = AppointmentSync.AppointmentMutation.Reschedule(
                appointmentId = "appt-a",
                newStartMillis = 11 * hour,
                nowMillis = 150L,
                rememberInstance = false,
                baseUpdatedAtMillis = 100L
            )
        )
        assertTrue(stale.keptNewer)
        assertEquals(14 * hour, stale.records.first { it.id == "appt-a" }.startMillis)
        assertEquals(3, stale.records.size)
    }

    @Test
    fun eventLinkRetryDoesNotRewriteANewerAppointment() {
        val moved = record("appt-a", start = 14 * hour, updatedAt = 400L)
        val other = record("appt-b", start = 15 * hour, updatedAt = 100L)
        val linked = AppointmentSync.applyAppointmentMutation(
            leadId = "lead-1",
            fresh = listOf(moved, other),
            mutation = AppointmentSync.AppointmentMutation.SetEventId("appt-a", 90L)
        )
        assertEquals(14 * hour, linked.records.first { it.id == "appt-a" }.startMillis)
        assertEquals(90L, linked.records.first { it.id == "appt-a" }.eventId)
        assertEquals(other, linked.records.first { it.id == "appt-b" })

        val directive = AppointmentSync.retryDirective(
            AppointmentSync.operation(
                kind = AppointmentSync.SyncOperation.Kind.SAVE_EVENT_LINK,
                leadId = "lead-1",
                appointmentId = "appt-a",
                eventId = 90L,
                message = "store id"
            ),
            listOf(moved, other)
        )
        assertTrue((directive as AppointmentSync.RetryDirective.Apply).mutation is AppointmentSync.AppointmentMutation.SetEventId)
    }

    private fun record(
        id: String,
        start: Long,
        updatedAt: Long,
        eventId: Long? = null,
        status: AppointmentAgenda.AppointmentStatus = AppointmentAgenda.AppointmentStatus.SCHEDULED
    ): AppointmentAgenda.AppointmentRecord {
        return AppointmentAgenda.AppointmentRecord(
            id = id,
            startMillis = start,
            location = "Office",
            eventId = eventId,
            status = status,
            originalInstanceTime = null,
            updatedAtMillis = updatedAt
        )
    }
}
