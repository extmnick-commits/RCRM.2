package com.nickpulido.rcrm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            ReminderAlarmStore.onAppStart(context.applicationContext)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }

        val leadId = intent.getStringExtra(EXTRA_LEAD_ID)
        val reminderId = intent.getStringExtra(EXTRA_REMINDER_ID)
        val contactName = intent.getStringExtra(EXTRA_CONTACT_NAME)
            ?: intent.getStringExtra("LEAD_NAME")
            ?: "a lead"
        if (!leadId.isNullOrBlank() && !reminderId.isNullOrBlank()) {
            val title = intent.getStringExtra(EXTRA_TITLE)?.ifBlank { null } ?: "Reminder"
            showReminder(context, leadId, reminderId, title, contactName)
        } else {
            showLegacy(context, contactName, intent.getStringExtra("LEAD_PHONE"))
        }
    }

    private fun showReminder(
        context: Context,
        leadId: String,
        reminderId: String,
        title: String,
        contactName: String
    ) {
        val openAppIntent = Intent(context, FollowUpActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_LEAD_ID, leadId)
            putExtra(EXTRA_REMINDER_ID, reminderId)
        }
        val requestCode = ReminderPlannerRequest.requestCode(leadId, reminderId)
        val pendingIntent = PendingIntent.getActivity(
            context,
            requestCode,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = baseBuilder(context)
            .setContentTitle(title)
            .setContentText(contactName)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        notify(context, requestCode, notification)
    }

    private fun showLegacy(context: Context, leadName: String, leadPhone: String?) {
        val openAppIntent = Intent(context, FollowUpActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("targetPhone", leadPhone)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            leadPhone?.hashCode() ?: 0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = baseBuilder(context)
            .setContentTitle("Follow-Up Reminder")
            .setContentText("It's time to follow up with $leadName!")
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        val notificationId = leadPhone?.hashCode() ?: leadName.hashCode()
        notify(context, notificationId, notification)
    }

    private fun baseBuilder(context: Context): NotificationCompat.Builder {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "rcrm_reminders"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Lead Reminders",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Reminders to follow up with leads"
            }
            notificationManager.createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.mipmap.ic_launcher_round)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
    }

    private fun notify(context: Context, id: Int, notification: android.app.Notification) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(id, notification)
    }

    companion object {
        const val EXTRA_LEAD_ID = "targetLeadId"
        const val EXTRA_REMINDER_ID = "reminderId"
        const val EXTRA_TITLE = "REMINDER_TITLE"
        const val EXTRA_CONTACT_NAME = "LEAD_NAME"

        fun scheduleReminder(
            context: Context,
            leadPhone: String,
            leadName: String,
            timeInMillis: Long,
            actionId: String? = null,
            leadId: String? = null,
            title: String? = null
        ) {
            ReminderAlarmStore.schedule(
                context = context,
                alarmKey = leadPhone,
                contactName = leadName,
                dueTime = timeInMillis,
                actionId = actionId,
                leadId = leadId,
                title = title
            )
        }

        fun cancelReminder(context: Context, leadPhone: String, actionId: String? = null) {
            ReminderAlarmStore.cancel(context, leadPhone, actionId)
        }
    }
}

private object ReminderPlannerRequest {
    fun requestCode(leadId: String, actionId: String): Int {
        return com.nickpulido.rcrm.domain.ReminderPlanner.reminderRequestIdentity(leadId, actionId).hashCode()
    }
}
