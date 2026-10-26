package com.nickpulido.rcrm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.nickpulido.rcrm.domain.ActionStatus
import com.nickpulido.rcrm.domain.NextAction
import com.nickpulido.rcrm.domain.ReminderAlarmState
import com.nickpulido.rcrm.domain.ReminderPlanner
import org.json.JSONArray
import org.json.JSONObject

data class AlertAccess(
    val notificationsGranted: Boolean,
    val exactAlarmsGranted: Boolean
)

object ReminderAlarmStore {

    private const val PREFS = "rcrm_reminder_alarms"
    private const val KEY_ALARMS = "alarms"
    private const val KIND_REMINDER = "reminder"
    private const val KIND_APPOINTMENT = "appointment"
    private const val KIND_LEGACY = "legacy"
    private const val TAG = "ReminderAlarmStore"

    @Volatile
    private var reconciledThisProcess = false

    fun onAppStart(context: Context) {
        restore(context)
        if (reconciledThisProcess) return
        if (FirebaseAuth.getInstance().currentUser == null) return
        reconciledThisProcess = true
        reconcilePersistedLeads(context.applicationContext)
    }

    fun alertAccess(context: Context): AlertAccess {
        val notifications = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        val exact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            manager.canScheduleExactAlarms()
        } else {
            true
        }
        return AlertAccess(notificationsGranted = notifications, exactAlarmsGranted = exact)
    }

    fun schedule(
        context: Context,
        alarmKey: String,
        contactName: String,
        dueTime: Long,
        actionId: String? = null,
        leadId: String? = null,
        title: String? = null
    ) {
        if (!actionId.isNullOrBlank()) {
            scheduleAction(
                context = context,
                leadId = leadId?.takeIf { it.isNotBlank() } ?: alarmKey,
                actionId = actionId,
                title = title?.ifBlank { null } ?: "Reminder",
                contactName = contactName,
                phone = alarmKey,
                dueTime = dueTime
            )
            return
        }
        if (ReminderPlanner.isAppointmentAlarmKey(alarmKey)) {
            remember(
                context,
                stored(
                    kind = KIND_APPOINTMENT,
                    identity = alarmKey,
                    leadId = "",
                    actionId = "",
                    dueTime = dueTime,
                    title = contactName,
                    contactName = contactName,
                    phone = ""
                )
            )
            arm(context, alarmKey.hashCode(), dueTime, legacyExtras(alarmKey, contactName))
            return
        }
        remember(
            context,
            stored(
                kind = KIND_LEGACY,
                identity = "legacy:$alarmKey",
                leadId = leadId.orEmpty(),
                actionId = "",
                dueTime = dueTime,
                title = title ?: "Follow-Up Reminder",
                contactName = contactName,
                phone = alarmKey
            )
        )
        arm(context, alarmKey.hashCode(), dueTime, legacyExtras(alarmKey, contactName))
    }

    fun cancel(context: Context, alarmKey: String, actionId: String? = null) {
        if (!actionId.isNullOrBlank()) {
            val match = alarms(context).firstOrNull { it.kind == KIND_REMINDER && it.actionId == actionId }
            if (match != null) {
                cancelAction(context, match.leadId, actionId)
            } else {
                disarm(context, actionId.hashCode())
                val lead = alarmKey
                disarm(context, ReminderPlanner.reminderRequestIdentity(lead, actionId).hashCode())
            }
            return
        }
        if (ReminderPlanner.isAppointmentAlarmKey(alarmKey)) {
            forget(context) { it.kind == KIND_APPOINTMENT && it.identity == alarmKey }
            disarm(context, alarmKey.hashCode())
            return
        }
        cancelLegacyPhone(context, alarmKey, null)
    }

    fun scheduleAction(
        context: Context,
        leadId: String,
        actionId: String,
        title: String,
        contactName: String,
        phone: String?,
        dueTime: Long
    ) {
        val identity = ReminderPlanner.reminderRequestIdentity(leadId, actionId)
        remember(
            context,
            stored(
                kind = KIND_REMINDER,
                identity = identity,
                leadId = leadId,
                actionId = actionId,
                dueTime = dueTime,
                title = title.ifBlank { "Reminder" },
                contactName = contactName.ifBlank { "Contact" },
                phone = phone.orEmpty()
            )
        )
        disarm(context, actionId.hashCode())
        arm(
            context,
            identity.hashCode(),
            dueTime,
            mapOf(
                ReminderReceiver.EXTRA_LEAD_ID to leadId,
                ReminderReceiver.EXTRA_REMINDER_ID to actionId,
                ReminderReceiver.EXTRA_TITLE to title.ifBlank { "Reminder" },
                ReminderReceiver.EXTRA_CONTACT_NAME to contactName.ifBlank { "Contact" }
            )
        )
        if (!phone.isNullOrBlank() && !ReminderPlanner.isAppointmentAlarmKey(phone)) {
            cancelLegacyPhone(context, phone, null)
        }
    }

    fun cancelAction(context: Context, leadId: String, actionId: String) {
        val identity = ReminderPlanner.reminderRequestIdentity(leadId, actionId)
        forget(context) { it.kind == KIND_REMINDER && it.leadId == leadId && it.actionId == actionId }
        disarm(context, identity.hashCode())
        disarm(context, actionId.hashCode())
    }

    fun cancelLegacyPhone(context: Context, phone: String?, leadId: String?) {
        if (!phone.isNullOrBlank() && !ReminderPlanner.isAppointmentAlarmKey(phone)) {
            forget(context) { it.kind == KIND_LEGACY && (it.phone == phone || it.identity == "legacy:$phone") }
            disarm(context, phone.hashCode())
        }
        if (!leadId.isNullOrBlank() && leadId != phone && !ReminderPlanner.isAppointmentAlarmKey(leadId)) {
            forget(context) { it.kind == KIND_LEGACY && (it.phone == leadId || it.leadId == leadId || it.identity == "legacy:$leadId") }
            disarm(context, leadId.hashCode())
        }
    }

    fun applySync(
        context: Context,
        leadId: String,
        contactName: String,
        phone: String?,
        actions: List<NextAction>
    ) {
        val persisted = alarms(context)
            .filter { it.kind == KIND_REMINDER && it.leadId == leadId }
            .map {
                ReminderAlarmState(
                    leadId = it.leadId,
                    actionId = it.actionId,
                    dueTime = it.dueTime,
                    title = it.title,
                    contactName = it.contactName
                )
            }
        val sync = ReminderPlanner.syncAlarms(
            leadId = leadId,
            contactName = contactName,
            persisted = persisted,
            actions = actions,
            nowMillis = System.currentTimeMillis()
        )
        sync.cancelActionIds.forEach { cancelAction(context, leadId, it) }
        sync.schedule.forEach { state ->
            scheduleAction(
                context = context,
                leadId = state.leadId,
                actionId = state.actionId,
                title = state.title,
                contactName = state.contactName,
                phone = phone,
                dueTime = state.dueTime
            )
        }
    }

    fun restore(context: Context) {
        val now = System.currentTimeMillis()
        val kept = mutableListOf<StoredAlarm>()
        for (alarm in alarms(context)) {
            if (!ReminderPlanner.shouldRestoreAlarm(alarm.dueTime, now)) continue
            kept += alarm
            when (alarm.kind) {
                KIND_APPOINTMENT -> arm(
                    context,
                    alarm.identity.hashCode(),
                    alarm.dueTime,
                    legacyExtras(alarm.identity, alarm.contactName)
                )
                KIND_LEGACY -> arm(
                    context,
                    alarm.phone.ifBlank { alarm.identity.removePrefix("legacy:") }.hashCode(),
                    alarm.dueTime,
                    legacyExtras(alarm.phone, alarm.contactName)
                )
                else -> arm(
                    context,
                    alarm.identity.hashCode(),
                    alarm.dueTime,
                    mapOf(
                        ReminderReceiver.EXTRA_LEAD_ID to alarm.leadId,
                        ReminderReceiver.EXTRA_REMINDER_ID to alarm.actionId,
                        ReminderReceiver.EXTRA_TITLE to alarm.title,
                        ReminderReceiver.EXTRA_CONTACT_NAME to alarm.contactName
                    )
                )
            }
        }
        replaceAll(context, kept)
    }

    fun reconcilePersistedLeads(context: Context) {
        val userId = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val leadIds = alarms(context).map { it.leadId }.filter { it.isNotBlank() }.distinct()
        if (leadIds.isEmpty()) return
        val db = FirebaseFirestore.getInstance()
        for (leadId in leadIds) {
            db.collection("leads").document(leadId).get()
                .addOnSuccessListener { lead ->
                    if (!lead.exists()) {
                        clearLeadReminders(context, leadId)
                        return@addOnSuccessListener
                    }
                    if (lead.getString("ownerId") != userId) return@addOnSuccessListener
                    val name = lead.getString("name")?.ifBlank { null } ?: "Contact"
                    val phone = lead.getString("phone").orEmpty()
                    lead.reference.collection("next_actions").get()
                        .addOnSuccessListener { snap ->
                            val actions = snap.documents.mapNotNull { actionFrom(it, leadId) }
                            applySync(context, leadId, name, phone, actions)
                            val version = when (val value = lead.get(ReminderPlanner.MIGRATION_FIELD)) {
                                is Number -> value.toInt()
                                else -> 0
                            }
                            val followUp = lead.getTimestamp("followUpDate")?.toDate()?.time
                            val datedPending = actions.count { it.status == ActionStatus.PENDING && it.dueTime > 0L }
                            if (ReminderPlanner.shouldCancelLegacyPhoneAlarm(version, datedPending, followUp)) {
                                cancelLegacyPhone(context, phone, leadId)
                            }
                        }
                        .addOnFailureListener { error ->
                            Log.w(TAG, "Couldn't reconcile reminders for $leadId", error)
                        }
                }
                .addOnFailureListener { error ->
                    Log.w(TAG, "Couldn't open $leadId to reconcile reminders", error)
                }
        }
    }

    private fun clearLeadReminders(context: Context, leadId: String) {
        val existing = alarms(context).filter { it.kind == KIND_REMINDER && it.leadId == leadId }
        existing.forEach { cancelAction(context, leadId, it.actionId) }
    }

    private fun actionFrom(doc: DocumentSnapshot, leadId: String): NextAction? {
        if (!doc.exists()) return null
        return ReminderPlanner.actionFrom(
            id = doc.id,
            leadId = doc.getString("leadId") ?: leadId,
            ownerId = doc.getString("ownerId") ?: "",
            typeName = doc.getString("type"),
            title = doc.getString("title") ?: "",
            notes = doc.getString("notes"),
            dueTime = longField(doc, "dueTime") ?: 0L,
            statusName = doc.getString("status"),
            createdAt = longField(doc, "createdAt") ?: 0L,
            completedAt = longField(doc, "completedAt"),
            appointmentId = doc.getString("appointmentId"),
            reason = doc.getString("reason"),
            workflowName = doc.getString("workflow")
        )
    }

    private fun longField(doc: DocumentSnapshot, name: String): Long? {
        val value = doc.get(name) ?: return null
        return when (value) {
            is Number -> value.toLong()
            else -> null
        }
    }

    private fun arm(context: Context, requestCode: Int, dueTime: Long, extras: Map<String, String>) {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            extras.forEach { (key, value) -> putExtra(key, value) }
        }
        val pending = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try {
            val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()
            if (canExact) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, dueTime, pending)
            } else {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, dueTime, pending)
            }
        } catch (error: SecurityException) {
            Log.w(TAG, "Exact alarm was denied. Using a regular alarm.", error)
            try {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, dueTime, pending)
            } catch (fallback: Exception) {
                Log.w(TAG, "Couldn't schedule the reminder alarm", fallback)
            }
        }
    }

    private fun disarm(context: Context, requestCode: Int) {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ReminderReceiver::class.java)
        val pending = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        manager.cancel(pending)
    }

    private fun legacyExtras(phoneOrKey: String, contactName: String): Map<String, String> {
        return mapOf(
            "LEAD_PHONE" to phoneOrKey,
            "LEAD_NAME" to contactName
        )
    }

    private data class StoredAlarm(
        val kind: String,
        val identity: String,
        val leadId: String,
        val actionId: String,
        val dueTime: Long,
        val title: String,
        val contactName: String,
        val phone: String
    )

    private fun stored(
        kind: String,
        identity: String,
        leadId: String,
        actionId: String,
        dueTime: Long,
        title: String,
        contactName: String,
        phone: String
    ) = StoredAlarm(kind, identity, leadId, actionId, dueTime, title, contactName, phone)

    private fun remember(context: Context, alarm: StoredAlarm) {
        synchronized(this) {
            val next = alarms(context).filterNot { sameAlarm(it, alarm) } + alarm
            replaceAll(context, next)
        }
    }

    private fun forget(context: Context, predicate: (StoredAlarm) -> Boolean) {
        synchronized(this) {
            replaceAll(context, alarms(context).filterNot(predicate))
        }
    }

    private fun sameAlarm(left: StoredAlarm, right: StoredAlarm): Boolean {
        return left.kind == right.kind && left.identity == right.identity
    }

    private fun alarms(context: Context): List<StoredAlarm> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ALARMS, null)
            ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    add(
                        StoredAlarm(
                            kind = item.optString("kind"),
                            identity = item.optString("identity"),
                            leadId = item.optString("leadId"),
                            actionId = item.optString("actionId"),
                            dueTime = item.optLong("dueTime"),
                            title = item.optString("title"),
                            contactName = item.optString("contactName"),
                            phone = item.optString("phone")
                        )
                    )
                }
            }
        } catch (error: Exception) {
            Log.w(TAG, "Couldn't read saved reminder alarms", error)
            emptyList()
        }
    }

    private fun replaceAll(context: Context, alarms: List<StoredAlarm>) {
        val array = JSONArray()
        alarms.forEach { alarm ->
            array.put(
                JSONObject()
                    .put("kind", alarm.kind)
                    .put("identity", alarm.identity)
                    .put("leadId", alarm.leadId)
                    .put("actionId", alarm.actionId)
                    .put("dueTime", alarm.dueTime)
                    .put("title", alarm.title)
                    .put("contactName", alarm.contactName)
                    .put("phone", alarm.phone)
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ALARMS, array.toString())
            .apply()
    }
}
