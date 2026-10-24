package com.nickpulido.rcrm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class AppointmentAgendaTest {

    private val zone: TimeZone = TimeZone.getTimeZone("America/Los_Angeles")
    private val locale: Locale = Locale.US

    @Test
    fun nextMonthStartsTheDayStripOnDayOne() {
        val today = local(2026, Calendar.OCTOBER, 22, 18, 24)
        val todayStart = AppointmentAgenda.startOfDay(today, zone)
        val october = AppointmentAgenda.startOfMonthContaining(today, zone)
        val november = AppointmentAgenda.shiftMonth(october, 1, zone)
        val cells = AppointmentAgenda.buildDayCells(november, todayStart, null, zone, locale)

        assertEquals(30, cells.size)
        assertEquals(1, cells.first().dayOfMonth)
        assertEquals("Sun", cells.first().dowLabel)
        assertEquals(0, AppointmentAgenda.dayStripAnchorIndex(cells.map { it.dayStartMillis }, todayStart, null))
        assertEquals(22, cells[21].dayOfMonth)
        assertFalse(cells[21].isToday)
    }

    @Test
    fun previousMonthAndTodayRealignTheStripAndFilters() {
        val today = local(2026, Calendar.OCTOBER, 22, 18, 0)
        val todayStart = AppointmentAgenda.startOfDay(today, zone)
        val october = AppointmentAgenda.startOfMonthContaining(today, zone)
        val november = AppointmentAgenda.shiftMonth(october, 1, zone)
        val viewingNovember = AppointmentAgenda.CalendarNav(
            monthStartMillis = november,
            selectedDayMillis = local(2026, Calendar.NOVEMBER, 4),
            monthWindow = AppointmentAgenda.MonthWindow.ALL,
            source = AppointmentAgenda.EventSource.CRM_ONLY
        )

        val back = AppointmentAgenda.shiftVisibleMonth(viewingNovember, -1, zone)
        assertEquals(october, back.monthStartMillis)
        assertNull(back.selectedDayMillis)
        assertEquals(AppointmentAgenda.MonthWindow.ALL, back.monthWindow)
        assertEquals(AppointmentAgenda.EventSource.CRM_ONLY, back.source)
        val octoberCells = AppointmentAgenda.buildDayCells(back.monthStartMillis, todayStart, null, zone, locale)
        assertEquals(21, AppointmentAgenda.dayStripAnchorIndex(octoberCells.map { it.dayStartMillis }, todayStart, null))
        assertEquals(22, octoberCells[21].dayOfMonth)
        assertTrue(octoberCells[21].isToday)

        val jumped = AppointmentAgenda.jumpToToday(viewingNovember, today, zone)
        assertEquals(october, jumped.monthStartMillis)
        assertNull(jumped.selectedDayMillis)
        assertEquals(AppointmentAgenda.MonthWindow.UPCOMING, jumped.monthWindow)
        assertEquals(AppointmentAgenda.EventSource.CRM_ONLY, jumped.source)
    }

    @Test
    fun februaryUsesTheRealMonthLength() {
        val february = AppointmentAgenda.startOfMonthContaining(local(2026, Calendar.FEBRUARY, 10), zone)
        val cells = AppointmentAgenda.buildDayCells(february, 0L, null, zone, locale)
        assertEquals(28, cells.size)
        assertEquals(1, cells.first().dayOfMonth)
    }

    @Test
    fun allDayHolidayStaysOnItsCalendarDate() {
        val utcMidnight = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(2026, Calendar.NOVEMBER, 1, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val day = AppointmentAgenda.eventDayStart(utcMidnight, allDay = true, zone)
        val localDay = Calendar.getInstance(zone).apply { timeInMillis = day }
        assertEquals(2026, localDay.get(Calendar.YEAR))
        assertEquals(Calendar.NOVEMBER, localDay.get(Calendar.MONTH))
        assertEquals(1, localDay.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun dateFiltersAndCrmFilterAreIndependent() {
        val todayStart = AppointmentAgenda.startOfDay(local(2026, Calendar.OCTOBER, 22, 12, 0), zone)
        val pastPersonal = event(id = 1, title = "Water bill", day = 21, hour = 10)
        val pastCrm = crmEntry(day = 21, hour = 9, appointmentId = "a1")
        val todayPersonal = event(id = 2, title = "Office Work", day = 22, hour = 9)
        val todayNamedLikeLead = event(id = 3, title = "Meeting with Pedro and Estevan.", day = 22, hour = 12)
            .copy(suggestedLeadId = "lead-pedro")
        val todayCrm = crmEntry(day = 22, hour = 15, appointmentId = "a2")
        val entries = listOf(pastPersonal, pastCrm, todayPersonal, todayNamedLikeLead, todayCrm)

        val upcomingAll = AppointmentAgenda.visibleEntries(
            entries,
            AppointmentAgenda.MonthWindow.UPCOMING,
            AppointmentAgenda.EventSource.ALL_EVENTS,
            selectedDayMillis = null,
            todayStartMillis = todayStart,
            zone = zone
        )
        assertEquals(listOf("Office Work", "Meeting with Pedro and Estevan.", "Alex"), upcomingAll.map { it.title })

        val allMonthCrm = AppointmentAgenda.visibleEntries(
            entries,
            AppointmentAgenda.MonthWindow.ALL,
            AppointmentAgenda.EventSource.CRM_ONLY,
            selectedDayMillis = null,
            todayStartMillis = todayStart,
            zone = zone
        )
        assertEquals(listOf("a1", "a2"), allMonthCrm.map { it.appointmentId })
        assertTrue(allMonthCrm.all { it.explicitCrm })

        val selectedPast = AppointmentAgenda.visibleEntries(
            entries,
            AppointmentAgenda.MonthWindow.UPCOMING,
            AppointmentAgenda.EventSource.ALL_EVENTS,
            selectedDayMillis = AppointmentAgenda.startOfDay(local(2026, Calendar.OCTOBER, 21), zone),
            todayStartMillis = todayStart,
            zone = zone
        )
        assertEquals(listOf("Water bill", "Alex"), selectedPast.map { it.title })
    }

    @Test
    fun movedLinkedEventDoesNotLeaveAStaleDuplicate() {
        val range = monthRange(2026, Calendar.OCTOBER)
        val stored = local(2026, Calendar.OCTOBER, 26, 9, 0)
        val moved = local(2026, Calendar.OCTOBER, 26, 14, 0)
        val appointment = appointment(start = stored, eventId = 10, appointmentId = "appt-1")
        val calendar = calendarEvent(
            eventId = 10,
            title = "Office Work",
            start = moved,
            end = moved + 5L * 60L * 60L * 1000L
        ).copy(explicitAppointmentId = "appt-1", explicitLeadId = "lead-1")

        val rows = AppointmentAgenda.reconcile(
            appointments = listOf(appointment),
            events = listOf(calendar),
            suggestedLeadIdsByEventId = emptyMap(),
            rangeStart = range.first,
            rangeEnd = range.second,
            zone = zone
        )

        assertEquals(1, rows.size)
        assertEquals(moved, rows[0].startMillis)
        assertEquals("appt-1", rows[0].appointmentId)
        assertTrue(rows[0].explicitCrm)
        assertTrue(rows[0].timeDrifted)
        assertEquals("Family", AppointmentAgenda.shortCalendarLabel(rows[0].calendarDisplayName, true))
        assertFalse(AppointmentAgenda.eventDetail(rows[0], "Oct 26, 9:00 AM").contains("extmnick@gmail.com") &&
            AppointmentAgenda.shortCalendarLabel(rows[0].calendarDisplayName, true).contains("@"))
        assertTrue(AppointmentAgenda.eventDetail(rows[0], "Oct 26, 9:00 AM").contains("Account: extmnick@gmail.com"))
        assertTrue(AppointmentAgenda.eventDetail(rows[0], "Oct 26, 9:00 AM").contains("neither one was overwritten"))
    }

    @Test
    fun tenMinuteMoveOfALinkedEventIsStillOneRow() {
        val range = monthRange(2026, Calendar.OCTOBER)
        val stored = local(2026, Calendar.OCTOBER, 26, 9, 0)
        val moved = stored + 10L * 60L * 1000L
        val rows = AppointmentAgenda.reconcile(
            appointments = listOf(appointment(start = stored, eventId = 10, appointmentId = "appt-1")),
            events = listOf(
                calendarEvent(10, "Office Work", moved, moved + 3_600_000L).copy(
                    explicitAppointmentId = "appt-1",
                    explicitLeadId = "lead-1"
                )
            ),
            suggestedLeadIdsByEventId = emptyMap(),
            rangeStart = range.first,
            rangeEnd = range.second,
            zone = zone
        )
        assertEquals(1, rows.size)
        assertEquals(moved, rows[0].startMillis)
    }

    @Test
    fun unlinkedEventsWithinFifteenMinutesStaySeparate() {
        val range = monthRange(2026, Calendar.OCTOBER)
        val first = local(2026, Calendar.OCTOBER, 26, 9, 0)
        val second = first + 10L * 60L * 1000L
        val rows = AppointmentAgenda.reconcile(
            appointments = listOf(appointment(start = first, eventId = null, appointmentId = "appt-1")),
            events = listOf(calendarEvent(77, "Office Work", second, second + 3_600_000L)),
            suggestedLeadIdsByEventId = mapOf(77L to "lead-1"),
            rangeStart = range.first,
            rangeEnd = range.second,
            zone = zone
        )
        assertEquals(2, rows.size)
        assertTrue(rows.any { it.explicitCrm && it.eventId == null })
        val suggested = rows.first { it.eventId == 77L }
        assertFalse(suggested.explicitCrm)
        assertEquals("lead-1", suggested.suggestedLeadId)
    }

    @Test
    fun recurringInstancesStayDistinct() {
        val range = monthRange(2026, Calendar.OCTOBER)
        val weekOne = local(2026, Calendar.OCTOBER, 6, 10, 0)
        val weeks = (0 until 4).map { weekOne + it * 7L * 24L * 60L * 60L * 1000L }
        val instances = weeks.map { start ->
            calendarEvent(10, "Office Work", start, start + 3_600_000L).copy(
                recurring = true,
                explicitAppointmentId = if (start == weekOne) "appt-1" else null,
                explicitLeadId = if (start == weekOne) "lead-1" else null
            )
        }
        val rows = AppointmentAgenda.reconcile(
            appointments = listOf(appointment(start = weekOne, eventId = 10, appointmentId = "appt-1")),
            events = instances,
            suggestedLeadIdsByEventId = emptyMap(),
            rangeStart = range.first,
            rangeEnd = range.second,
            zone = zone
        )
        assertEquals(4, rows.size)
        assertEquals(1, rows.count { it.explicitCrm })
        assertEquals(weekOne, rows.first { it.explicitCrm }.startMillis)
        assertEquals(3, rows.count { !it.explicitCrm })
    }

    @Test
    fun movedRecurringInstanceUsesOriginalTimeAndDoesNotDuplicate() {
        val range = monthRange(2026, Calendar.OCTOBER)
        val original = local(2026, Calendar.OCTOBER, 6, 10, 0)
        val moved = local(2026, Calendar.OCTOBER, 6, 15, 0)
        val otherWeeks = listOf(1, 2, 3).map { original + it * 7L * 24L * 60L * 60L * 1000L }
        val series = otherWeeks.map { start ->
            calendarEvent(10, "Office Work", start, start + 3_600_000L).copy(recurring = true)
        }
        val exception = calendarEvent(99, "Office Work", moved, moved + 3_600_000L).copy(
            recurring = false,
            originalInstanceTime = original,
            originalEventId = 10L,
            explicitAppointmentId = "appt-1",
            explicitLeadId = "lead-1"
        )
        val rows = AppointmentAgenda.reconcile(
            appointments = listOf(appointment(start = original, eventId = 10, appointmentId = "appt-1")),
            events = series + exception,
            suggestedLeadIdsByEventId = emptyMap(),
            rangeStart = range.first,
            rangeEnd = range.second,
            zone = zone
        )
        val linked = rows.filter { it.appointmentId == "appt-1" && it.explicitCrm && it.leadId == "lead-1" }
        assertEquals(1, linked.size)
        assertEquals(moved, linked[0].startMillis)
        assertEquals(99L, linked[0].eventId)
        assertFalse(rows.any { it.explicitCrm && it.startMillis == original && it.eventId == 10L })
    }

    @Test
    fun deviceLocalIdChangeMergesOnlyAUniqueExplicitLink() {
        val range = monthRange(2026, Calendar.OCTOBER)
        val start = local(2026, Calendar.OCTOBER, 26, 9, 0)
        val unique = AppointmentAgenda.reconcile(
            appointments = listOf(appointment(start = start, eventId = 5, appointmentId = "appt-1")),
            events = listOf(
                calendarEvent(9, "Office Work", start + 3_600_000L, start + 7_200_000L).copy(
                    explicitAppointmentId = "appt-1",
                    explicitLeadId = "lead-1"
                )
            ),
            suggestedLeadIdsByEventId = emptyMap(),
            rangeStart = range.first,
            rangeEnd = range.second,
            zone = zone
        )
        assertEquals(1, unique.size)
        assertEquals(9L, unique[0].eventId)
        assertTrue(unique[0].timeDrifted)

        val ambiguous = AppointmentAgenda.reconcile(
            appointments = listOf(
                appointment(start = start, eventId = 5, appointmentId = "appt-1"),
                appointment(start = start + 86_400_000L, eventId = 6, appointmentId = "appt-2")
            ),
            events = listOf(
                calendarEvent(9, "Office Work", start, start + 3_600_000L).copy(explicitLeadId = "lead-1"),
                calendarEvent(11, "Counseling", start + 3_600_000L, start + 7_200_000L).copy(explicitLeadId = "lead-1")
            ),
            suggestedLeadIdsByEventId = emptyMap(),
            rangeStart = range.first,
            rangeEnd = range.second,
            zone = zone
        )
        assertEquals(4, ambiguous.size)
    }

    @Test
    fun collidingEventIdOnAnotherDeviceIsNotTheSameAppointment() {
        val range = monthRange(2026, Calendar.OCTOBER)
        val start = local(2026, Calendar.OCTOBER, 26, 9, 0)
        val foreign = calendarEvent(10, "Dentist", start, start + 3_600_000L).copy(
            explicitLeadId = "someone-else",
            explicitAppointmentId = "other-appt"
        )
        val unlabeled = calendarEvent(10, "Dentist", start, start + 3_600_000L)
        val appointment = appointment(start = start, eventId = 10, appointmentId = "appt-1")

        val foreignRows = AppointmentAgenda.reconcile(
            appointments = listOf(appointment),
            events = listOf(foreign),
            suggestedLeadIdsByEventId = emptyMap(),
            rangeStart = range.first,
            rangeEnd = range.second,
            zone = zone
        )
        assertEquals(2, foreignRows.size)
        assertTrue(foreignRows.any { it.appointmentId == "appt-1" && it.title == "Alex" })
        assertTrue(foreignRows.any { it.title == "Dentist" && it.appointmentId == "other-appt" })

        val unlabeledRows = AppointmentAgenda.reconcile(
            appointments = listOf(appointment),
            events = listOf(unlabeled),
            suggestedLeadIdsByEventId = emptyMap(),
            rangeStart = range.first,
            rangeEnd = range.second,
            zone = zone
        )
        assertEquals(2, unlabeledRows.size)
        assertTrue(unlabeledRows.any { it.appointmentId == "appt-1" && it.title == "Alex" })
        assertTrue(unlabeledRows.any { it.title == "Dentist" && it.appointmentId == null })
        assertNull(
            AppointmentAgenda.selectRetryTarget(
                candidates = listOf(unlabeled),
                appointmentId = "appt-1",
                leadId = "lead-1",
                preferredEventId = 10L
            )
        )
        assertFalse(
            AppointmentAgenda.eventIdentityAllows(
                eventLeadId = null,
                eventAppointmentId = null,
                expectedLeadId = "lead-1",
                expectedAppointmentId = "appt-1",
                soleAppointmentForLead = true
            )
        )
        assertFalse(
            AppointmentAgenda.eventIdentityAllows(
                eventLeadId = "lead-1",
                eventAppointmentId = "appt-1",
                expectedLeadId = "lead-1",
                expectedAppointmentId = "appt-1",
                soleAppointmentForLead = true,
                recurringMaster = true
            )
        )
        assertTrue(
            AppointmentAgenda.eventIdentityAllows(
                eventLeadId = "lead-1",
                eventAppointmentId = null,
                expectedLeadId = "lead-1",
                expectedAppointmentId = AppointmentAgenda.legacyAppointmentId("lead-1"),
                soleAppointmentForLead = false
            )
        )
    }

    @Test
    fun movedEventAppearsOnlyInTheMonthOfItsEffectiveDate() {
        val october = monthRange(2026, Calendar.OCTOBER)
        val november = monthRange(2026, Calendar.NOVEMBER)
        val stored = local(2026, Calendar.OCTOBER, 26, 9, 0)
        val moved = local(2026, Calendar.NOVEMBER, 3, 11, 0)
        val appointment = appointment(start = stored, eventId = 10, appointmentId = "appt-1")
        val event = calendarEvent(10, "Office Work", moved, moved + 3_600_000L).copy(
            explicitAppointmentId = "appt-1",
            explicitLeadId = "lead-1"
        )
        val matched = AppointmentAgenda.matchAgenda(
            appointments = listOf(appointment),
            events = listOf(event),
            suggestedLeadIdsByEventId = emptyMap()
        )
        assertEquals(1, matched.size)
        assertEquals(moved, matched[0].startMillis)

        val inOctober = AppointmentAgenda.applyMonthBoundary(matched, october.first, october.second, zone)
        assertTrue(inOctober.isEmpty())

        val inNovember = AppointmentAgenda.reconcile(
            appointments = listOf(appointment),
            events = listOf(event),
            suggestedLeadIdsByEventId = emptyMap(),
            rangeStart = november.first,
            rangeEnd = november.second,
            zone = zone
        )
        assertEquals(1, inNovember.size)
        assertEquals(moved, inNovember[0].startMillis)
        assertEquals("appt-1", inNovember[0].appointmentId)
        assertEquals(10L, inNovember[0].eventId)
    }

    @Test
    fun followUpEditSyncsTheEditedAppointmentNotTheLegacyMirror() {
        val now = local(2026, Calendar.OCTOBER, 22, 12, 0)
        val soonerStart = local(2026, Calendar.OCTOBER, 23, 9, 0)
        val laterStart = local(2026, Calendar.OCTOBER, 30, 15, 0)
        val newStart = local(2026, Calendar.OCTOBER, 31, 16, 0)
        val sooner = AppointmentAgenda.AppointmentRecord(
            id = "appt-soon",
            startMillis = soonerStart,
            location = "Office",
            eventId = 4L,
            status = AppointmentAgenda.AppointmentStatus.SCHEDULED,
            originalInstanceTime = null,
            updatedAtMillis = now
        )
        val later = sooner.copy(id = "appt-later", startMillis = laterStart, eventId = 8L, location = "Client")
        val edit = AppointmentAgenda.describeSingleAppointmentEdit(
            leadId = "lead-1",
            existing = listOf(sooner, later),
            previousStartMillis = laterStart,
            previousLocation = "Client",
            previousEventId = 8L,
            newStartMillis = newStart,
            newLocation = "Client site",
            newEventId = 8L,
            nowMillis = now + 1,
            createdId = "should-not-be-used"
        )
        assertEquals("appt-later", edit.editedId)
        assertEquals(newStart, edit.records.first { it.id == "appt-later" }.startMillis)
        assertEquals(soonerStart, edit.records.first { it.id == "appt-soon" }.startMillis)
        val mirror = AppointmentAgenda.legacyMirror(edit.records, now)
        assertEquals("appt-soon", mirror.appointmentId)
        assertTrue(mirror.appointmentId != edit.editedId)

        val cleared = AppointmentAgenda.describeSingleAppointmentEdit(
            leadId = "lead-1",
            existing = edit.records,
            previousStartMillis = newStart,
            previousLocation = "Client site",
            previousEventId = 8L,
            newStartMillis = null,
            newLocation = null,
            newEventId = 8L,
            nowMillis = now + 2,
            createdId = "new"
        )
        assertEquals("appt-later", cleared.editedId)
        assertEquals(AppointmentAgenda.AppointmentStatus.CANCELED, cleared.records.first { it.id == "appt-later" }.status)
        assertEquals("appt-soon", AppointmentAgenda.legacyMirror(cleared.records, now).appointmentId)
    }

    @Test
    fun nameMatchIsNotACrmAppointment() {
        val range = monthRange(2026, Calendar.OCTOBER)
        val start = local(2026, Calendar.OCTOBER, 22, 12, 30)
        val rows = AppointmentAgenda.reconcile(
            appointments = emptyList(),
            events = listOf(calendarEvent(3, "Meeting with Pedro and Estevan.", start, start + 3_600_000L)),
            suggestedLeadIdsByEventId = mapOf(3L to "lead-pedro"),
            rangeStart = range.first,
            rangeEnd = range.second,
            zone = zone
        )
        assertEquals(1, rows.size)
        assertFalse(rows[0].explicitCrm)
        assertEquals("Family", AppointmentAgenda.shortCalendarLabel(rows[0].calendarDisplayName, false))
        val visible = AppointmentAgenda.visibleEntries(
            rows,
            AppointmentAgenda.MonthWindow.ALL,
            AppointmentAgenda.EventSource.CRM_ONLY,
            null,
            start,
            zone
        )
        assertTrue(visible.isEmpty())
    }

    @Test
    fun readOnlyAndRecurringPlansDoNotInsert() {
        val readOnly = AppointmentAgenda.planCalendarWrite(
            eventId = 10L,
            canWrite = false,
            recurringMaster = false,
            allowInsert = true
        )
        assertTrue(readOnly is AppointmentAgenda.CalendarWritePlan.Blocked)
        val recurring = AppointmentAgenda.planCalendarWrite(
            eventId = 10L,
            canWrite = true,
            recurringMaster = true,
            allowInsert = true
        )
        assertTrue(recurring is AppointmentAgenda.CalendarWritePlan.Blocked)
        val retry = AppointmentAgenda.resolveWrite(
            AppointmentAgenda.CalendarWritePlan.InsertNew,
            foundEventId = 44L
        )
        assertEquals(AppointmentAgenda.ResolvedWrite.Update(44L), retry)
        assertEquals(
            44L,
            AppointmentAgenda.selectRetryTarget(
                candidates = listOf(calendarEvent(44, "Office Work", 1L, 2L).copy(explicitAppointmentId = "appt-1")),
                appointmentId = "appt-1",
                leadId = "lead-1",
                preferredEventId = 999L
            )
        )
    }

    @Test
    fun migrationIsIdempotentAndPreservesHistory() {
        val now = local(2026, Calendar.OCTOBER, 22, 12, 0)
        val legacyStart = local(2026, Calendar.OCTOBER, 26, 9, 0)
        val first = AppointmentAgenda.migrateAppointments(
            leadId = "lead-1",
            existing = emptyList(),
            legacyStartMillis = legacyStart,
            legacyLocation = "Office",
            legacyEventId = 10L,
            nowMillis = now
        )
        assertEquals(1, first.size)
        assertEquals("legacy:lead-1", first[0].id)
        assertEquals(legacyStart, first[0].startMillis)
        assertEquals(10L, first[0].eventId)

        val second = AppointmentAgenda.migrateAppointments(
            "lead-1",
            first,
            legacyStart,
            "Office",
            10L,
            now + 5_000L
        )
        assertEquals(first, second)

        val completed = first[0].copy(status = AppointmentAgenda.AppointmentStatus.COMPLETED)
        val extra = AppointmentAgenda.AppointmentRecord(
            id = "future",
            startMillis = local(2026, Calendar.NOVEMBER, 2, 9, 0),
            location = "Client",
            eventId = 12L,
            status = AppointmentAgenda.AppointmentStatus.SCHEDULED,
            originalInstanceTime = null,
            updatedAtMillis = now
        )
        val withHistory = AppointmentAgenda.migrateAppointments(
            "lead-1",
            listOf(completed, extra, extra),
            legacyStart,
            "Office",
            10L,
            now
        )
        assertEquals(2, withHistory.size)
        assertEquals(AppointmentAgenda.AppointmentStatus.COMPLETED, withHistory[0].status)
        assertEquals("future", withHistory[1].id)

        val roundTrip = AppointmentAgenda.parseRecords(AppointmentAgenda.toFieldMaps(withHistory)) { value ->
            (value as Number).toLong()
        }
        assertEquals(withHistory, roundTrip)
    }

    @Test
    fun newAppointmentDoesNotReplaceAnExistingOne() {
        val now = local(2026, Calendar.OCTOBER, 22, 12, 0)
        val first = AppointmentAgenda.AppointmentRecord(
            id = "legacy:lead-1",
            startMillis = local(2026, Calendar.OCTOBER, 26, 9, 0),
            location = "Office",
            eventId = 10L,
            status = AppointmentAgenda.AppointmentStatus.SCHEDULED,
            originalInstanceTime = null,
            updatedAtMillis = now
        )
        val second = first.copy(
            id = "appt-2",
            startMillis = local(2026, Calendar.NOVEMBER, 2, 11, 0),
            eventId = null
        )
        val saved = AppointmentAgenda.addAppointment(listOf(first), second)
        val retried = AppointmentAgenda.addAppointment(saved, second.copy(location = "Updated"))
        assertEquals(2, retried.size)
        assertEquals("Updated", retried[1].location)
        assertEquals(first.id, retried[0].id)
    }

    @Test
    fun outcomesKeepHistoryAndRescheduleKeepsTheId() {
        val now = local(2026, Calendar.OCTOBER, 22, 12, 0)
        val record = AppointmentAgenda.AppointmentRecord(
            id = "appt-1",
            startMillis = local(2026, Calendar.OCTOBER, 26, 9, 0),
            location = "Office",
            eventId = 10L,
            status = AppointmentAgenda.AppointmentStatus.SCHEDULED,
            originalInstanceTime = null,
            updatedAtMillis = now
        )
        val other = record.copy(id = "appt-2", startMillis = record.startMillis + 86_400_000L)
        val completed = AppointmentAgenda.applyOutcome(
            listOf(record, other),
            "appt-1",
            AppointmentAgenda.Outcome.COMPLETED,
            now + 1
        )
        assertEquals(2, completed.size)
        assertEquals(AppointmentAgenda.AppointmentStatus.COMPLETED, completed[0].status)
        assertEquals(other, completed[1])
        assertEquals(
            completed,
            AppointmentAgenda.applyOutcome(completed, "appt-1", AppointmentAgenda.Outcome.COMPLETED, now + 2)
        )
        assertEquals(
            completed,
            AppointmentAgenda.applyOutcome(completed, "missing", AppointmentAgenda.Outcome.CANCELED, now)
        )

        val noShow = AppointmentAgenda.applyOutcome(listOf(record), "appt-1", AppointmentAgenda.Outcome.NO_SHOW, now)
        assertEquals(AppointmentAgenda.AppointmentStatus.NO_SHOW, noShow[0].status)
        val canceled = AppointmentAgenda.applyOutcome(listOf(record), "appt-1", AppointmentAgenda.Outcome.CANCELED, now)
        assertEquals(AppointmentAgenda.AppointmentStatus.CANCELED, canceled[0].status)
        assertEquals(
            listOf("appt:appt-1", "appt_555"),
            AppointmentAgenda.reminderChange(canceled[0], "appt_555").cancelKeys
        )
        assertNull(AppointmentAgenda.reminderChange(canceled[0], "appt_555").scheduleKey)

        val newStart = local(2026, Calendar.OCTOBER, 27, 16, 0)
        val moved = AppointmentAgenda.reschedule(listOf(record), "appt-1", newStart, now, rememberInstance = false)
        assertEquals("appt-1", moved[0].id)
        assertEquals(newStart, moved[0].startMillis)
        assertEquals(AppointmentAgenda.AppointmentStatus.SCHEDULED, moved[0].status)
        val reminder = AppointmentAgenda.reminderChange(moved[0], "appt_555")
        assertEquals("appt:appt-1", reminder.scheduleKey)
        assertEquals(newStart, reminder.scheduleAtMillis)
        assertFalse(reminder.cancelKeys.contains("appt:appt-1"))
    }

    @Test
    fun singleFieldEditUpdatesTheMatchingRecordAndClearsWithoutDeletingHistory() {
        val now = local(2026, Calendar.OCTOBER, 22, 12, 0)
        val original = local(2026, Calendar.OCTOBER, 26, 9, 0)
        val edited = AppointmentAgenda.applySingleAppointmentEdit(
            leadId = "lead-1",
            existing = emptyList(),
            previousStartMillis = original,
            previousLocation = "Office",
            previousEventId = 10L,
            newStartMillis = original + 3_600_000L,
            newLocation = "Client site",
            newEventId = 10L,
            nowMillis = now,
            createdId = "should-not-be-used"
        )
        assertEquals(1, edited.size)
        assertEquals("legacy:lead-1", edited[0].id)
        assertEquals("Client site", edited[0].location)

        val cleared = AppointmentAgenda.applySingleAppointmentEdit(
            leadId = "lead-1",
            existing = edited,
            previousStartMillis = edited[0].startMillis,
            previousLocation = edited[0].location,
            previousEventId = 10L,
            newStartMillis = null,
            newLocation = null,
            newEventId = 10L,
            nowMillis = now + 1,
            createdId = "new"
        )
        assertEquals(1, cleared.size)
        assertEquals(AppointmentAgenda.AppointmentStatus.CANCELED, cleared[0].status)
        val mirror = AppointmentAgenda.legacyMirror(cleared, now)
        assertNull(mirror.startMillis)
    }

    @Test
    fun ownerCheckAndCardLabel() {
        assertTrue(AppointmentAgenda.ownerCanEdit("user", "user"))
        assertFalse(AppointmentAgenda.ownerCanEdit("user", "other"))
        assertFalse(AppointmentAgenda.ownerCanEdit("user", null))
        assertTrue(AppointmentAgenda.ownerCanEdit(null, "user"))
        assertEquals("RCRM", AppointmentAgenda.shortCalendarLabel("  ", explicitCrm = true))
        assertEquals("Family", AppointmentAgenda.shortCalendarLabel("Family", explicitCrm = true))
        val detail = AppointmentAgenda.eventDetail(
            crmEntry(22, 9, "a").copy(
                calendarDisplayName = "Family",
                accountName = "extmnick@gmail.com",
                canWrite = true,
                eventId = 1L
            ),
            null
        )
        assertTrue(detail.contains("Calendar: Family"))
        assertTrue(detail.contains("Account: extmnick@gmail.com"))
        assertFalse(AppointmentAgenda.shortCalendarLabel("Family", true).contains("extmnick"))
    }

    private fun local(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0): Long {
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

    private fun monthRange(year: Int, month: Int): Pair<Long, Long> {
        val start = AppointmentAgenda.startOfMonthContaining(local(year, month, 1), zone)
        return start to AppointmentAgenda.shiftMonth(start, 1, zone)
    }

    private fun appointment(start: Long, eventId: Long?, appointmentId: String): AppointmentAgenda.CrmAppointmentRef {
        return AppointmentAgenda.CrmAppointmentRef(
            leadId = "lead-1",
            ownerId = "user",
            appointmentId = appointmentId,
            leadName = "Alex",
            phone = "555",
            location = "Office",
            startMillis = start,
            eventId = eventId,
            status = AppointmentAgenda.AppointmentStatus.SCHEDULED,
            originalInstanceTime = null
        )
    }

    private fun calendarEvent(
        eventId: Long,
        title: String,
        start: Long,
        end: Long
    ): AppointmentAgenda.CalendarEventRef {
        return AppointmentAgenda.CalendarEventRef(
            eventId = eventId,
            title = title,
            startMillis = start,
            endMillis = end,
            allDay = false,
            calendarDisplayName = "Family",
            accountName = "extmnick@gmail.com",
            color = 0xFF4C8DFF.toInt(),
            canWrite = true,
            recurring = false,
            originalInstanceTime = null,
            originalEventId = null,
            explicitLeadId = null,
            explicitAppointmentId = null,
            canceled = false
        )
    }

    private fun event(id: Long, title: String, day: Int, hour: Int): AppointmentAgenda.AgendaEntry {
        val start = local(2026, Calendar.OCTOBER, day, hour, 0)
        return AppointmentAgenda.AgendaEntry(
            title = title,
            startMillis = start,
            endMillis = start + 3_600_000L,
            allDay = false,
            color = 1,
            eventId = id,
            leadId = null,
            ownerId = null,
            appointmentId = null,
            leadName = null,
            phone = null,
            location = null,
            explicitCrm = false,
            suggestedLeadId = null,
            calendarDisplayName = "Family",
            accountName = "extmnick@gmail.com",
            canWrite = true,
            recurring = false,
            recurringMaster = false,
            storedStartMillis = null,
            timeDrifted = false,
            status = null
        )
    }

    private fun crmEntry(day: Int, hour: Int, appointmentId: String): AppointmentAgenda.AgendaEntry {
        val start = local(2026, Calendar.OCTOBER, day, hour, 0)
        return AppointmentAgenda.AgendaEntry(
            title = "Alex",
            startMillis = start,
            endMillis = start + 3_600_000L,
            allDay = false,
            color = 1,
            eventId = null,
            leadId = "lead-1",
            ownerId = "user",
            appointmentId = appointmentId,
            leadName = "Alex",
            phone = "555",
            location = "Office",
            explicitCrm = true,
            suggestedLeadId = null,
            calendarDisplayName = "",
            accountName = "",
            canWrite = true,
            recurring = false,
            recurringMaster = false,
            storedStartMillis = start,
            timeDrifted = false,
            status = AppointmentAgenda.AppointmentStatus.SCHEDULED
        )
    }
}
