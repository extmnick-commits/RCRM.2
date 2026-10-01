package com.nickpulido.rcrm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale

class DailyFollowUpWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_ENABLED, DEFAULT_ENABLED)) {
            Log.d(TAG, "Daily digest disabled. Skipping.")
            return@withContext Result.success()
        }

        val userId = FirebaseAuth.getInstance().currentUser?.uid
        if (userId == null) {
            Log.d(TAG, "No user logged in. Skipping daily digest.")
            return@withContext Result.success()
        }

        try {
            val todayKey = dayKeyForToday()
            val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())

            val snapshot = FirebaseFirestore.getInstance()
                .collection(COLLECTION_LEADS)
                .whereEqualTo(FIELD_OWNER_ID, userId)
                .get()
                .await()

            val followUps = mutableListOf<String>()
            val appointments = mutableListOf<String>()

            for (doc in snapshot.documents) {
                if (isStopped) break
                val name = doc.getString(FIELD_NAME) ?: "Unknown"

                doc.getTimestamp(FIELD_FOLLOW_UP_DATE)?.let { ts ->
                    if (ts.isSameCalendarDay(todayKey)) {
                        followUps.add("$name · ${timeFormat.format(ts.toDate())}")
                    }
                }

                doc.getTimestamp(FIELD_APPOINTMENT_DATE)?.let { ts ->
                    if (ts.isSameCalendarDay(todayKey)) {
                        appointments.add("$name · ${timeFormat.format(ts.toDate())}")
                    }
                }
            }

            if (followUps.isEmpty() && appointments.isEmpty()) {
                Log.d(TAG, "No follow-ups or appointments today. No notification.")
                return@withContext Result.success()
            }

            sendDigestNotification(followUps, appointments)
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Daily digest failed", e)
            Result.retry()
        }
    }

    private fun sendDigestNotification(followUps: List<String>, appointments: List<String>) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    applicationContext,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Log.w(TAG, "Missing POST_NOTIFICATIONS permission.")
                return
            }
        }

        val notificationManager =
            applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                applicationContext.getString(R.string.daily_digest_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = applicationContext.getString(R.string.daily_digest_channel_description)
            }
            notificationManager.createNotificationChannel(channel)
        }

        val openIntent = Intent(applicationContext, FollowUpActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val totalCount = followUps.size + appointments.size
        val title = applicationContext.getString(R.string.daily_digest_notification_title, totalCount)
        val summary = applicationContext.getString(R.string.daily_digest_notification_summary)

        val inboxStyle = NotificationCompat.InboxStyle()
            .setBigContentTitle(title)
            .setSummaryText(summary)

        if (followUps.isNotEmpty()) {
            inboxStyle.addLine(applicationContext.getString(R.string.daily_digest_section_follow_ups))
            addSectionLines(inboxStyle, followUps)
        }
        if (appointments.isNotEmpty()) {
            inboxStyle.addLine(applicationContext.getString(R.string.daily_digest_section_appointments))
            addSectionLines(inboxStyle, appointments)
        }

        val contentText = when {
            followUps.isNotEmpty() && appointments.isNotEmpty() ->
                applicationContext.getString(
                    R.string.daily_digest_content_both,
                    followUps.size,
                    appointments.size
                )
            followUps.isNotEmpty() ->
                applicationContext.getString(R.string.daily_digest_content_follow_ups, followUps.size)
            else ->
                applicationContext.getString(R.string.daily_digest_content_appointments, appointments.size)
        }

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher_round)
            .setContentTitle(title)
            .setContentText(contentText)
            .setStyle(inboxStyle)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun addSectionLines(style: NotificationCompat.InboxStyle, lines: List<String>) {
        val shown = lines.take(MAX_LINES_PER_SECTION)
        for (line in shown) {
            style.addLine(line)
        }
        val remaining = lines.size - shown.size
        if (remaining > 0) {
            style.addLine(
                applicationContext.getString(R.string.daily_digest_and_more, remaining)
            )
        }
    }

    companion object {
        private const val TAG = "DailyFollowUpWorker"
        const val PREFS_NAME = "settings"
        const val KEY_ENABLED = "daily_digest_enabled"
        const val KEY_HOUR = "daily_digest_hour"
        const val KEY_MINUTE = "daily_digest_minute"
        const val DEFAULT_ENABLED = true
        const val WORK_NAME = "DailyFollowUpWorker"

        private const val CHANNEL_ID = "rcrm_daily_digest"
        private const val NOTIFICATION_ID = 1002
        private const val COLLECTION_LEADS = "leads"
        private const val FIELD_OWNER_ID = "ownerId"
        private const val FIELD_NAME = "name"
        private const val FIELD_FOLLOW_UP_DATE = "followUpDate"
        private const val FIELD_APPOINTMENT_DATE = "appointmentDate"
        private const val MAX_LINES_PER_SECTION = 8
    }
}
