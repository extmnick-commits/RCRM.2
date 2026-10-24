package com.nickpulido.rcrm

/**
 * Appointment writes and calendar retries that must stay exact after a failure.
 * Firestore transactions apply [AppointmentMutation] to the latest saved array.
 */
object AppointmentSync {

    data class LocalLeadUpdate(
        val put: Map<String, Any?>,
        val remove: Set<String>
    )

    fun splitLocalLeadUpdate(
        updates: Map<String, Any?>,
        isDeleteSentinel: (Any?) -> Boolean,
        isServerValue: (Any?) -> Boolean
    ): LocalLeadUpdate {
        val put = linkedMapOf<String, Any?>()
        val remove = linkedSetOf<String>()
        updates.forEach { (key, value) ->
            when {
                isDeleteSentinel(value) -> remove += key
                isServerValue(value) -> Unit
                value == null -> remove += key
                else -> put[key] = value
            }
        }
        return LocalLeadUpdate(put, remove)
    }

    sealed class AppointmentMutation {
        data class SaveOutcome(
            val appointmentId: String,
            val outcome: AppointmentAgenda.Outcome,
            val nowMillis: Long,
            val baseUpdatedAtMillis: Long
        ) : AppointmentMutation()

        data class Reschedule(
            val appointmentId: String,
            val newStartMillis: Long,
            val nowMillis: Long,
            val rememberInstance: Boolean,
            val baseUpdatedAtMillis: Long
        ) : AppointmentMutation()

        data class Upsert(
            val record: AppointmentAgenda.AppointmentRecord,
            val baseUpdatedAtMillis: Long
        ) : AppointmentMutation()

        data class SetEventId(
            val appointmentId: String,
            val eventId: Long?
        ) : AppointmentMutation()

        data class Migrate(
            val legacyStartMillis: Long?,
            val legacyLocation: String?,
            val legacyEventId: Long?,
            val nowMillis: Long
        ) : AppointmentMutation()

        data class SingleEdit(
            val previousStartMillis: Long?,
            val previousLocation: String?,
            val previousEventId: Long?,
            val newStartMillis: Long?,
            val newLocation: String?,
            val newEventId: Long?,
            val nowMillis: Long,
            val createdId: String,
            val baseUpdatedAtMillis: Long
        ) : AppointmentMutation()
    }

    data class MutationResult(
        val records: List<AppointmentAgenda.AppointmentRecord>,
        val changed: Boolean,
        val targetId: String?,
        val keptNewer: Boolean = false
    )

    fun applyAppointmentMutation(
        leadId: String,
        fresh: List<AppointmentAgenda.AppointmentRecord>,
        mutation: AppointmentMutation
    ): MutationResult {
        return when (mutation) {
            is AppointmentMutation.SaveOutcome -> applyOutcome(fresh, mutation)
            is AppointmentMutation.Reschedule -> applyReschedule(fresh, mutation)
            is AppointmentMutation.Upsert -> applyUpsert(fresh, mutation)
            is AppointmentMutation.SetEventId -> applyEventId(fresh, mutation)
            is AppointmentMutation.Migrate -> applyMigrate(leadId, fresh, mutation)
            is AppointmentMutation.SingleEdit -> applySingleEdit(leadId, fresh, mutation)
        }
    }

    data class SyncOperation(
        val kind: Kind,
        val leadId: String,
        val appointmentId: String,
        val leadName: String,
        val phone: String,
        val intendedStartMillis: Long,
        val location: String?,
        val eventId: Long?,
        val outcome: AppointmentAgenda.Outcome?,
        val baseUpdatedAtMillis: Long,
        val canWrite: Boolean,
        val recurringMaster: Boolean,
        val rememberInstance: Boolean,
        val soleAppointment: Boolean,
        val message: String
    ) {
        enum class Kind {
            SAVE_OUTCOME,
            RESCHEDULE,
            CANCEL_CALENDAR_EVENT,
            SAVE_EVENT_LINK,
            MIGRATE_HISTORY
        }
    }

    sealed class RetryDirective {
        data class Apply(val mutation: AppointmentMutation) : RetryDirective()
        data class CancelEvent(val eventId: Long) : RetryDirective()
        data class PushCalendar(
            val startMillis: Long,
            val eventId: Long?,
            val allowInsert: Boolean
        ) : RetryDirective()

        data object Remigrate : RetryDirective()
        data object NothingToDo : RetryDirective()
    }

    fun retryDirective(
        operation: SyncOperation,
        fresh: List<AppointmentAgenda.AppointmentRecord>
    ): RetryDirective {
        val current = fresh.firstOrNull { it.id == operation.appointmentId }
        return when (operation.kind) {
            SyncOperation.Kind.CANCEL_CALENDAR_EVENT -> {
                val eventId = operation.eventId?.takeIf { it > 0L } ?: return RetryDirective.NothingToDo
                if (current != null &&
                    current.status != AppointmentAgenda.AppointmentStatus.CANCELED &&
                    current.updatedAtMillis > operation.baseUpdatedAtMillis
                ) {
                    RetryDirective.NothingToDo
                } else {
                    RetryDirective.CancelEvent(eventId)
                }
            }
            SyncOperation.Kind.SAVE_EVENT_LINK -> {
                val eventId = operation.eventId?.takeIf { it > 0L } ?: return RetryDirective.NothingToDo
                if (operation.appointmentId.isBlank()) return RetryDirective.NothingToDo
                RetryDirective.Apply(AppointmentMutation.SetEventId(operation.appointmentId, eventId))
            }
            SyncOperation.Kind.MIGRATE_HISTORY -> RetryDirective.Remigrate
            SyncOperation.Kind.SAVE_OUTCOME -> {
                val outcome = operation.outcome ?: return RetryDirective.NothingToDo
                if (operation.appointmentId.isBlank()) return RetryDirective.NothingToDo
                val desired = statusFor(outcome)
                if (current?.status == desired) {
                    if (outcome == AppointmentAgenda.Outcome.CANCELED) {
                        val eventId = (current.eventId ?: operation.eventId)?.takeIf { it > 0L }
                        if (eventId != null) RetryDirective.CancelEvent(eventId) else RetryDirective.NothingToDo
                    } else {
                        RetryDirective.NothingToDo
                    }
                } else {
                    RetryDirective.Apply(
                        AppointmentMutation.SaveOutcome(
                            appointmentId = operation.appointmentId,
                            outcome = outcome,
                            nowMillis = operation.baseUpdatedAtMillis.coerceAtLeast(1L) + 1L,
                            baseUpdatedAtMillis = operation.baseUpdatedAtMillis
                        )
                    )
                }
            }
            SyncOperation.Kind.RESCHEDULE -> {
                val intended = operation.intendedStartMillis
                if (operation.appointmentId.isBlank() || intended <= 0L) return RetryDirective.NothingToDo
                if (current != null && sameInstant(current.startMillis, intended)) {
                    val eventId = current.eventId ?: operation.eventId
                    RetryDirective.PushCalendar(
                        startMillis = intended,
                        eventId = eventId?.takeIf { it > 0L },
                        allowInsert = eventId == null || eventId <= 0L
                    )
                } else if (current != null &&
                    current.updatedAtMillis > operation.baseUpdatedAtMillis &&
                    !sameInstant(current.startMillis, intended)
                ) {
                    RetryDirective.NothingToDo
                } else {
                    RetryDirective.Apply(
                        AppointmentMutation.Reschedule(
                            appointmentId = operation.appointmentId,
                            newStartMillis = intended,
                            nowMillis = operation.baseUpdatedAtMillis.coerceAtLeast(1L) + 1L,
                            rememberInstance = operation.rememberInstance,
                            baseUpdatedAtMillis = operation.baseUpdatedAtMillis
                        )
                    )
                }
            }
        }
    }

    fun operation(
        kind: SyncOperation.Kind,
        leadId: String,
        appointmentId: String = "",
        leadName: String = "",
        phone: String = "",
        intendedStartMillis: Long = 0L,
        location: String? = null,
        eventId: Long? = null,
        outcome: AppointmentAgenda.Outcome? = null,
        baseUpdatedAtMillis: Long = 0L,
        canWrite: Boolean = true,
        recurringMaster: Boolean = false,
        rememberInstance: Boolean = false,
        soleAppointment: Boolean = false,
        message: String
    ): SyncOperation {
        return SyncOperation(
            kind = kind,
            leadId = leadId,
            appointmentId = appointmentId,
            leadName = leadName,
            phone = phone,
            intendedStartMillis = intendedStartMillis,
            location = location,
            eventId = eventId,
            outcome = outcome,
            baseUpdatedAtMillis = baseUpdatedAtMillis,
            canWrite = canWrite,
            recurringMaster = recurringMaster,
            rememberInstance = rememberInstance,
            soleAppointment = soleAppointment,
            message = message
        )
    }

    private fun applyOutcome(
        fresh: List<AppointmentAgenda.AppointmentRecord>,
        mutation: AppointmentMutation.SaveOutcome
    ): MutationResult {
        val existing = fresh.firstOrNull { it.id == mutation.appointmentId }
            ?: return MutationResult(fresh, changed = false, targetId = null)
        val desired = statusFor(mutation.outcome)
        if (existing.status == desired) {
            return MutationResult(fresh, changed = false, targetId = existing.id)
        }
        if (mutation.baseUpdatedAtMillis > 0L && existing.updatedAtMillis > mutation.baseUpdatedAtMillis) {
            return MutationResult(fresh, changed = false, targetId = existing.id, keptNewer = true)
        }
        val next = AppointmentAgenda.applyOutcome(
            fresh,
            mutation.appointmentId,
            mutation.outcome,
            mutation.nowMillis
        )
        return MutationResult(next, next != fresh, mutation.appointmentId)
    }

    private fun applyReschedule(
        fresh: List<AppointmentAgenda.AppointmentRecord>,
        mutation: AppointmentMutation.Reschedule
    ): MutationResult {
        if (mutation.newStartMillis <= 0L) {
            return MutationResult(fresh, changed = false, targetId = null)
        }
        val existing = fresh.firstOrNull { it.id == mutation.appointmentId }
            ?: return MutationResult(fresh, changed = false, targetId = null)
        if (sameInstant(existing.startMillis, mutation.newStartMillis) &&
            existing.status == AppointmentAgenda.AppointmentStatus.SCHEDULED
        ) {
            return MutationResult(fresh, changed = false, targetId = existing.id)
        }
        if (existing.updatedAtMillis > mutation.baseUpdatedAtMillis &&
            !sameInstant(existing.startMillis, mutation.newStartMillis)
        ) {
            return MutationResult(fresh, changed = false, targetId = existing.id, keptNewer = true)
        }
        val next = AppointmentAgenda.reschedule(
            fresh,
            mutation.appointmentId,
            mutation.newStartMillis,
            mutation.nowMillis,
            mutation.rememberInstance
        )
        return MutationResult(next, next != fresh, mutation.appointmentId)
    }

    private fun applyUpsert(
        fresh: List<AppointmentAgenda.AppointmentRecord>,
        mutation: AppointmentMutation.Upsert
    ): MutationResult {
        val record = mutation.record
        val existing = fresh.firstOrNull { it.id == record.id }
        if (existing != null &&
            existing.updatedAtMillis > mutation.baseUpdatedAtMillis &&
            (existing.startMillis != record.startMillis || existing.status != record.status || existing.location != record.location)
        ) {
            return MutationResult(fresh, changed = false, targetId = existing.id, keptNewer = true)
        }
        val next = AppointmentAgenda.upsertRecord(fresh, record)
        return MutationResult(next, next != fresh, record.id)
    }

    private fun applyEventId(
        fresh: List<AppointmentAgenda.AppointmentRecord>,
        mutation: AppointmentMutation.SetEventId
    ): MutationResult {
        val next = AppointmentAgenda.withEventLink(fresh, mutation.appointmentId, mutation.eventId)
            ?: return MutationResult(fresh, changed = false, targetId = null)
        return MutationResult(next, next != fresh, mutation.appointmentId)
    }

    private fun applyMigrate(
        leadId: String,
        fresh: List<AppointmentAgenda.AppointmentRecord>,
        mutation: AppointmentMutation.Migrate
    ): MutationResult {
        val next = AppointmentAgenda.migrateAppointments(
            leadId = leadId,
            existing = fresh,
            legacyStartMillis = mutation.legacyStartMillis,
            legacyLocation = mutation.legacyLocation,
            legacyEventId = mutation.legacyEventId,
            nowMillis = mutation.nowMillis
        )
        val target = next.firstOrNull { it.id == AppointmentAgenda.legacyAppointmentId(leadId) }?.id
        return MutationResult(next, next != fresh, target)
    }

    private fun applySingleEdit(
        leadId: String,
        fresh: List<AppointmentAgenda.AppointmentRecord>,
        mutation: AppointmentMutation.SingleEdit
    ): MutationResult {
        val described = AppointmentAgenda.describeSingleAppointmentEdit(
            leadId = leadId,
            existing = fresh,
            previousStartMillis = mutation.previousStartMillis,
            previousLocation = mutation.previousLocation,
            previousEventId = mutation.previousEventId,
            newStartMillis = mutation.newStartMillis,
            newLocation = mutation.newLocation,
            newEventId = mutation.newEventId,
            nowMillis = mutation.nowMillis,
            createdId = mutation.createdId,
            baseUpdatedAtMillis = mutation.baseUpdatedAtMillis
        )
        val changed = described.records != fresh
        return MutationResult(
            records = described.records,
            changed = changed,
            targetId = described.editedId,
            keptNewer = described.keptNewer
        )
    }

    private fun statusFor(outcome: AppointmentAgenda.Outcome): AppointmentAgenda.AppointmentStatus {
        return when (outcome) {
            AppointmentAgenda.Outcome.COMPLETED -> AppointmentAgenda.AppointmentStatus.COMPLETED
            AppointmentAgenda.Outcome.NO_SHOW -> AppointmentAgenda.AppointmentStatus.NO_SHOW
            AppointmentAgenda.Outcome.CANCELED -> AppointmentAgenda.AppointmentStatus.CANCELED
        }
    }

    private fun sameInstant(left: Long, right: Long): Boolean {
        return kotlin.math.abs(left - right) <= AppointmentAgenda.SAME_INSTANT_TOLERANCE_MS
    }
}
