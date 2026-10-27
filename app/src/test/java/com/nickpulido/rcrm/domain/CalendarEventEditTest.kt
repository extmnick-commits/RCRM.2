package com.nickpulido.rcrm.domain

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream

class CalendarEventEditTest {
    private fun event() = CalendarEditSnapshot(42L, 7L, "Appointment", 1000L, 2000L,
        "Office", "Bring documents", true, false, false, false, false, false)

    @Test fun simpleWritableEventCanBeEditedInTheApp() {
        assertEquals(CalendarEventEdit.Route.IN_APP, CalendarEventEdit.route(event()))
    }

    @Test fun complexEventsUseTheCalendarEditor() {
        listOf(event().copy(recurring = true), event().copy(allDay = true),
            event().copy(linkedToCrm = true), event().copy(hasGuests = true)).forEach {
            assertEquals(CalendarEventEdit.Route.GOOGLE_EDIT, CalendarEventEdit.route(it))
        }
    }

    @Test fun readOnlyOrCanceledEventsAreViewOnly() {
        assertEquals(CalendarEventEdit.Route.GOOGLE_VIEW, CalendarEventEdit.route(event().copy(canWrite = false)))
        assertEquals(CalendarEventEdit.Route.GOOGLE_VIEW, CalendarEventEdit.route(event().copy(canceled = true)))
    }

    @Test fun titleAndEndTimeAreValidated() {
        val draft = CalendarEventEdit.draft(event())
        assertEquals(CalendarEventEdit.Error.TITLE, CalendarEventEdit.validate(draft.copy(title = "  ")))
        assertEquals(CalendarEventEdit.Error.END_TIME, CalendarEventEdit.validate(draft.copy(endMillis = 1000L)))
        assertEquals(CalendarEventEdit.Error.END_TIME, CalendarEventEdit.validate(draft.copy(startMillis = 0L)))
        assertNull(CalendarEventEdit.validate(draft))
    }

    @Test fun changingACalendarEventWhileTheFormIsOpenPreventsOverwrite() {
        val original = event()
        assertTrue(CalendarEventEdit.canSave(original, original))
        listOf(original.copy(title = "Updated elsewhere"), original.copy(startMillis = 1200L),
            original.copy(endMillis = 2500L), original.copy(location = "Home"),
            original.copy(description = "New notes"), original.copy(calendarId = 8L),
            original.copy(eventId = 43L), original.copy(canWrite = false), original.copy(hasGuests = true)).forEach {
            assertFalse(CalendarEventEdit.canSave(original, it))
        }
    }

    @Test fun draftKeepsExactDescriptionAndDurationUntilTheUserEditsThem() {
        val original = event().copy(description = "First paragraph\n\nSecond paragraph", endMillis = 2800L)
        val draft = CalendarEventEdit.draft(original)
        assertEquals(original.description, draft.description)
        assertEquals(1800L, draft.endMillis - draft.startMillis)
        assertEquals(original.location, draft.location)
    }

    @Test fun retryAfterRotationRecognizesAnAlreadySavedDraft() {
        val original = event()
        val fresh = original.copy(title = "Updated", startMillis = 3000L, endMillis = 5000L)
        val draft = CalendarEventEdit.draft(fresh)
        assertTrue(CalendarEventEdit.alreadyApplied(original, fresh, draft))
        assertFalse(CalendarEventEdit.alreadyApplied(original, fresh.copy(calendarId = 8L), draft))
        assertFalse(CalendarEventEdit.alreadyApplied(original, fresh.copy(eventId = 43L), draft))
    }

    @Test fun draftAndOriginalBaselineSurviveStateSerialization() {
        val session = CalendarEditSession(event(), CalendarEventEdit.draft(event()).copy(description = "Unsaved note"))
        val bytes = ByteArrayOutputStream().also { output -> ObjectOutputStream(output).use { it.writeObject(session) } }.toByteArray()
        val restored = ObjectInputStream(ByteArrayInputStream(bytes)).use { it.readObject() } as CalendarEditSession
        assertEquals(session, restored)
        assertEquals("Unsaved note", restored.draft.description)
        assertEquals("Bring documents", restored.original.description)
    }
}
