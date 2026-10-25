package com.nickpulido.rcrm.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactActivityTimelineTest {

    @Test
    fun parseNotesPreservesMultilineNotesAsOneEntry() {
        val raw = """
            [Oct 24, 2026 08:30 PM]: Spoke with John.
            
            He is interested in $500k term life.
            Spouse is also interested.
            
            Paused: Waiting on spouse info
            
            [Oct 15, 2026 10:00 AM]: Initial outreach call.
        """.trimIndent()

        val parsed = ContactActivityTimeline.parseNotes(raw)
        assertEquals(2, parsed.size)
        assertEquals("Oct 24, 2026 08:30 PM", parsed[0].date)
        assertTrue(parsed[0].content.contains("Spoke with John."))
        assertTrue(parsed[0].content.contains("He is interested in $500k term life."))
        assertTrue(parsed[0].content.contains("Paused: Waiting on spouse info"))
        assertNotNull(parsed[0].timestampMillis)

        assertEquals("Oct 15, 2026 10:00 AM", parsed[1].date)
        assertEquals("Initial outreach call.", parsed[1].content)
        assertNotNull(parsed[1].timestampMillis)
    }

    @Test
    fun parseNotesHandlesUnknownDatesGracefully() {
        val raw = """
            Imported contact from old spreadsheet
            No specific call logged yet
            
            [Oct 20, 2026 11:00 AM]: Follow up email sent
        """.trimIndent()

        val parsed = ContactActivityTimeline.parseNotes(raw)
        assertEquals(2, parsed.size)
        assertEquals("Unknown date", parsed[0].date)
        assertTrue(parsed[0].content.contains("Imported contact from old spreadsheet"))
        assertNull(parsed[0].timestampMillis)

        assertEquals("Oct 20, 2026 11:00 AM", parsed[1].date)
        assertEquals("Follow up email sent", parsed[1].content)
        assertNotNull(parsed[1].timestampMillis)
    }

    @Test
    fun parseNotesExtractsEventId() {
        val raw = "[Oct 24, 2026 08:30 PM | eid:act_op-12345]: Follow up completed."
        val parsed = ContactActivityTimeline.parseNotes(raw)
        assertEquals(1, parsed.size)
        assertEquals("act_op-12345", parsed[0].eventId)
        assertEquals("Oct 24, 2026 08:30 PM", parsed[0].date)
        assertEquals("Follow up completed.", parsed[0].content)
    }

    @Test
    fun outcomeWithoutNoteIsDisplayedInTimeline() {
        val activity = ActivityRecord(
            id = "act_no_note",
            leadId = "lead1",
            ownerId = "user1",
            type = ActionType.CALL,
            outcome = "Connected",
            note = "",
            timestamp = 1_729_800_000_000L
        )

        val timeline = ContactActivityTimeline.mergeActivityAndNotes(
            activityRecords = listOf(activity),
            rawNotes = ""
        )

        assertEquals(1, timeline.size)
        assertEquals("Connected", timeline[0].outcome)
        assertEquals("", timeline[0].note)
        assertEquals(ActionType.CALL, timeline[0].type)
        assertTrue(timeline[0].isActivityRecord)
    }

    @Test
    fun stableEventIdDeduplicatesFutureRecords() {
        val activity = ActivityRecord(
            id = "act_future_1",
            leadId = "lead1",
            ownerId = "user1",
            type = ActionType.CALL,
            outcome = "Connected",
            note = "Met with client and discussed Roth IRA",
            timestamp = 1_729_800_000_000L
        )

        val rawNotes = "[Oct 24, 2026 08:00 PM | eid:act_future_1]: Met with client and discussed Roth IRA"

        val timeline = ContactActivityTimeline.mergeActivityAndNotes(
            activityRecords = listOf(activity),
            rawNotes = rawNotes
        )

        assertEquals(1, timeline.size)
        assertEquals("Connected", timeline[0].outcome)
        assertEquals("Met with client and discussed Roth IRA", timeline[0].note)
        assertTrue(timeline[0].isActivityRecord)
    }

    @Test
    fun ambiguousNotesWithMatchingTextOnDifferentDatesAreNotMerged() {
        val todayMillis = ContactActivityTimeline.parseTimestamp("Oct 24, 2026 08:00 PM")!!
        // Two calls where both notes happen to say "Left voicemail"
        val activity = ActivityRecord(
            id = "act_call_today",
            leadId = "lead1",
            ownerId = "user1",
            type = ActionType.CALL,
            outcome = "Voicemail left",
            note = "Left voicemail",
            timestamp = todayMillis
        )

        // Historical note from earlier with same text
        val rawNotes = "[Oct 10, 2026 09:00 AM]: Left voicemail"

        val timeline = ContactActivityTimeline.mergeActivityAndNotes(
            activityRecords = listOf(activity),
            rawNotes = rawNotes
        )

        // Must show both! Do NOT merge ambiguous entries based only on matching text!
        assertEquals(2, timeline.size)
        assertEquals(todayMillis, timeline[0].timestampMillis)
        assertEquals("Voicemail left", timeline[0].outcome)
        assertEquals("Note", timeline[1].outcome)
    }

    @Test
    fun timelineIsSortedNewestFirstRegardlessOfRawNoteOrder() {
        // Raw notes has older note prepended before newer note
        val rawNotes = """
            [Oct 10, 2026 09:00 AM]: Older note
            
            [Oct 24, 2026 08:00 PM]: Newer note
        """.trimIndent()

        val timeline = ContactActivityTimeline.mergeActivityAndNotes(
            activityRecords = emptyList(),
            rawNotes = rawNotes
        )

        assertEquals(2, timeline.size)
        assertEquals("Newer note", timeline[0].note)
        assertEquals("Older note", timeline[1].note)
    }

    @Test
    fun stageChangeContextIsExtracted() {
        val activity = ActivityRecord(
            id = "act_stage",
            leadId = "lead1",
            ownerId = "user1",
            type = ActionType.TO_DO,
            outcome = "Stage Changed: Contact Made",
            note = "Moved to Contact Made stage\nClient ready to proceed",
            timestamp = 1_729_800_000_000L
        )

        val timeline = ContactActivityTimeline.mergeActivityAndNotes(
            activityRecords = listOf(activity),
            rawNotes = ""
        )

        assertEquals(1, timeline.size)
        assertEquals("Contact Made", timeline[0].stageContext)
    }
}
