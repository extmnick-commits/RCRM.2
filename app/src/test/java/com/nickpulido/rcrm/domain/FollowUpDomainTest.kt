package com.nickpulido.rcrm.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FollowUpDomainTest {

    @Test
    fun testNextActionDefaults() {
        val action = NextAction(
            leadId = "lead123",
            ownerId = "owner123"
        )
        
        assertNotNull(action.id)
        assertEquals("lead123", action.leadId)
        assertEquals(ActionType.TO_DO, action.type)
        assertEquals(ActionStatus.PENDING, action.status)
        assertTrue(action.createdAt > 0)
    }

    @Test
    fun testActivityRecordDefaults() {
        val record = ActivityRecord(
            leadId = "lead123",
            outcome = "Connected"
        )
        
        assertNotNull(record.id)
        assertEquals("Connected", record.outcome)
        assertEquals(ActionType.TO_DO, record.type)
    }
}
