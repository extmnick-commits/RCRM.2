package com.nickpulido.rcrm.domain

import com.nickpulido.rcrm.NoteUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DailyFlowTest {

    @Test
    fun mergeNotesWithoutDuplicatesPreventsDuplicationAfterQuickNote() {
        // Quick note persisted to remote:
        val remoteNotes = "[Oct 25, 2026 07:00 PM]: Discussed term insurance options.\n\n[Oct 20, 2026 10:00 AM]: Initial outreach."
        // User had the same notes loaded in dialog when clicking Save Changes:
        val localNotes = "[Oct 25, 2026 07:00 PM]: Discussed term insurance options.\n\n[Oct 20, 2026 10:00 AM]: Initial outreach."

        val merged = NoteUtils.mergeNotesWithoutDuplicates(remote = remoteNotes, local = localNotes)
        assertEquals(remoteNotes, merged)
    }

    @Test
    fun mergeNotesWithoutDuplicatesIncludesNewLocalNotesWhilePreservingRemote() {
        val remoteNotes = "[Oct 25, 2026 07:00 PM]: Remote quick note.\n\n[Oct 20, 2026 10:00 AM]: Old note."
        val localNotes = "[Oct 25, 2026 07:05 PM]: Form save note.\n\n[Oct 20, 2026 10:00 AM]: Old note."

        val merged = NoteUtils.mergeNotesWithoutDuplicates(remote = remoteNotes, local = localNotes)
        assertTrue(merged.contains("Form save note."))
        assertTrue(merged.contains("Remote quick note."))
        assertTrue(merged.contains("Old note."))
    }

    @Test
    fun searchFilteringMatchesIndependentOfTabWhenQueryIsPresent() {
        data class TestLead(
            val name: String,
            val phone: String,
            val dateStr: String,
            val isClosed: Boolean = false
        )

        val leads = listOf(
            TestLead("Alice Smith", "555-1234", "20261020"), // past (needs attention)
            TestLead("Bob Jones", "555-5678", "20261026"), // today
            TestLead("Charlie Brown", "555-9012", "20261030"), // upcoming
            TestLead("David Miller", "555-3456", "20261026", isClosed = true)
        )

        fun filter(leads: List<TestLead>, query: String, tab: Int, selectedDate: String): List<TestLead> {
            val isSearching = query.isNotBlank()
            return leads.filter { lead ->
                val matchesSearch = lead.name.contains(query, ignoreCase = true) ||
                    lead.phone.contains(query, ignoreCase = true)
                if (!matchesSearch) return@filter false
                if (lead.isClosed && !isSearching) return@filter false

                if (isSearching) {
                    true
                } else {
                    when (tab) {
                        0 -> lead.dateStr.isNotEmpty() && lead.dateStr < selectedDate
                        1 -> lead.dateStr == selectedDate
                        2 -> lead.dateStr.isNotEmpty() && lead.dateStr > selectedDate
                        else -> true
                    }
                }
            }
        }

        // On tab 1 ("Today"):
        val todayResults = filter(leads, query = "", tab = 1, selectedDate = "20261026")
        assertEquals(1, todayResults.size)
        assertEquals("Bob Jones", todayResults[0].name)

        // Searching for "Alice" while on tab 1 ("Today") must find Alice even though her date is not today:
        val searchResults = filter(leads, query = "Alice", tab = 1, selectedDate = "20261026")
        assertEquals(1, searchResults.size)
        assertEquals("Alice Smith", searchResults[0].name)

        // Searching by phone:
        val phoneResults = filter(leads, query = "9012", tab = 1, selectedDate = "20261026")
        assertEquals(1, phoneResults.size)
        assertEquals("Charlie Brown", phoneResults[0].name)

        // Searching no match:
        val noMatch = filter(leads, query = "Nonexistent", tab = 1, selectedDate = "20261026")
        assertTrue(noMatch.isEmpty())
    }
}
