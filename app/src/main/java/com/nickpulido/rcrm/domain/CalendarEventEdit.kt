package com.nickpulido.rcrm.domain

import java.io.Serializable

data class CalendarEditSnapshot(
    val eventId: Long,
    val calendarId: Long,
    val title: String,
    val startMillis: Long,
    val endMillis: Long,
    val location: String,
    val description: String,
    val canWrite: Boolean,
    val recurring: Boolean,
    val allDay: Boolean,
    val linkedToCrm: Boolean,
    val hasGuests: Boolean,
    val canceled: Boolean
) : Serializable

data class CalendarEditDraft(
    val title: String,
    val startMillis: Long,
    val endMillis: Long,
    val location: String,
    val description: String
) : Serializable

data class CalendarEditSession(val original: CalendarEditSnapshot, val draft: CalendarEditDraft) : Serializable

object CalendarEventEdit {
    enum class Route { IN_APP, GOOGLE_EDIT, GOOGLE_VIEW }
    enum class Error { TITLE, END_TIME }

    fun route(event: CalendarEditSnapshot): Route = when {
        !event.canWrite || event.canceled -> Route.GOOGLE_VIEW
        event.recurring || event.allDay || event.linkedToCrm || event.hasGuests -> Route.GOOGLE_EDIT
        else -> Route.IN_APP
    }

    fun draft(event: CalendarEditSnapshot) = CalendarEditDraft(
        event.title, event.startMillis, event.endMillis, event.location, event.description
    )

    fun validate(draft: CalendarEditDraft): Error? = when {
        draft.title.isBlank() -> Error.TITLE
        draft.startMillis <= 0 || draft.endMillis <= draft.startMillis -> Error.END_TIME
        else -> null
    }

    /** A fresh read must match the form's baseline before saving an existing event. */
    fun canSave(original: CalendarEditSnapshot, fresh: CalendarEditSnapshot): Boolean =
        route(fresh) == Route.IN_APP && original == fresh

    fun alreadyApplied(original: CalendarEditSnapshot, fresh: CalendarEditSnapshot, draft: CalendarEditDraft): Boolean =
        original.eventId == fresh.eventId && original.calendarId == fresh.calendarId &&
            route(fresh) == Route.IN_APP && draft == CalendarEventEdit.draft(fresh)
}
