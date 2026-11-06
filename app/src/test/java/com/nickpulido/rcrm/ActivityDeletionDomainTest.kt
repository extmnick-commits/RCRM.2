package com.nickpulido.rcrm

import com.nickpulido.rcrm.domain.ActionType
import com.nickpulido.rcrm.domain.ActivityRecord
import com.nickpulido.rcrm.domain.OutcomeSavePlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityDeletionDomainTest {

    @Test
    fun removeNoteDropsTaggedBlock() {
        val tagged = "[Oct 24, 2026 08:00 PM | eid:act_test]: Follow-up note"
        val removed = NoteUtils.removeNote("act_test", tagged)
        assertTrue(removed.isBlank())
    }

    @Test
    fun contactTimestampsRecomputesFromRemainingActivities() {
        val now = 1_729_800_000_000L
        val earlier = now - 86_400_000L
        val timestamps = OutcomeSavePlanner.contactTimestamps(
            listOf(
                ActivityRecord(
                    id = "a1",
                    leadId = "lead",
                    ownerId = "user",
                    type = ActionType.CALL,
                    outcome = "No answer",
                    note = "",
                    timestamp = earlier
                ),
                ActivityRecord(
                    id = "a2",
                    leadId = "lead",
                    ownerId = "user",
                    type = ActionType.CALL,
                    outcome = "Connected",
                    note = "",
                    timestamp = now
                )
            )
        )
        assertEquals(now, timestamps.attempted)
        assertEquals(now, timestamps.connected)
    }

    @Test
    fun contactTimestampsClearsWhenEmpty() {
        val timestamps = OutcomeSavePlanner.contactTimestamps(emptyList())
        assertNull(timestamps.attempted)
        assertNull(timestamps.connected)
    }
}
