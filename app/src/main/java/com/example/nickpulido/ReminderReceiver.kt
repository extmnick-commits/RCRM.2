package com.nickpulido.rcrm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val leadName = intent.getStringExtra("LEAD_NAME") ?: "a lead"
        val leadPhone = intent.getStringExtra("LEAD_PHONE")
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

        // Open FollowUpActivity and highlight the specific lead
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

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.mipmap.ic_launcher_round)
            .setContentTitle("Follow-Up Reminder")
            .setContentText("It's time to follow up with $leadName!")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        // Use phone hash for unique notifications per lead
        val notificationId = leadPhone?.hashCode() ?: leadName.hashCode()
        notificationManager.notify(notificationId, builder.build())
    }
}