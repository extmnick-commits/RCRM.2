package com.nickpulido.rcrm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.ListenableWorker.Result
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

/**
 * Specialized Primerica Sales Assistant worker.
 * Analyzes lead activity and provides actionable coaching based on the history of notes written.
 */
class SideKickWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val isEnabled = prefs.getBoolean(KEY_ENABLED, false)

        if (!isEnabled) {
            Log.d(TAG, "Side-Kick is disabled in settings. Skipping work.")
            return@withContext Result.success()
        }

        val userId = FirebaseAuth.getInstance().currentUser?.uid
        if (userId == null) {
            Log.d(TAG, "No user logged in. Skipping Side-Kick work.")
            return@withContext Result.success()
        }
        val db = FirebaseFirestore.getInstance()

        try {
            val leads = db.collection(COLLECTION_LEADS)
                .whereEqualTo(FIELD_OWNER_ID, userId)
                .get()
                .await()

            val now = System.currentTimeMillis()
            val sevenDaysAgo = now - (THRESHOLD_RECENT_DAYS * DAY_IN_MS)
            val fourteenDaysAgo = now - (THRESHOLD_NEGLECT_DAYS * DAY_IN_MS)

            val neglectedLeads = mutableListOf<NeglectedLead>()

            for (doc in leads.documents) {
                // Respect WorkManager cancellation
                if (isStopped) {
                    Log.i(TAG, "Worker stopped by system request.")
                    break
                }

                val name = doc.getString(FIELD_NAME) ?: "Someone"
                val phone = doc.getString(FIELD_PHONE) ?: ""
                val followUpTimestamp = doc.getTimestamp(FIELD_FOLLOW_UP_DATE)
                val lastFollowUpDate = followUpTimestamp?.toDate()?.time ?: 0L
                val rawNotes = doc.getString(FIELD_NOTES) ?: ""
                
                val notes = parseNotes(rawNotes)
                
                // Engagement: Notes in last 7 days
                val recentNotesCount = notes.count { it.timestamp > sevenDaysAgo }
                
                // Neglect: High historical notes but zero activity in last 14 days
                val totalNotesCount = notes.size
                val activityInLast14Days = notes.any { it.timestamp > fourteenDaysAgo }
                val isNeglected = totalNotesCount >= MIN_NOTES_FOR_NEGLECT && !activityInLast14Days

                // Recency: Last follow up was more than 14 days ago
                val isOldFollowUp = lastFollowUpDate < fourteenDaysAgo && lastFollowUpDate != 0L

                if (isNeglected || (isOldFollowUp && recentNotesCount == 0)) {
                    val lastActivityTime = notes.maxByOrNull { it.timestamp }?.timestamp ?: 0L
                    val neglectScore = now - lastActivityTime
                    
                    val suggestion = analyzeLeadHistory(notes)
                    val lastNoteContent = notes.maxByOrNull { it.timestamp }?.content ?: "No notes recorded"

                    neglectedLeads.add(NeglectedLead(doc.id, name, phone, lastNoteContent, suggestion, neglectScore))
                }
            }

            if (neglectedLeads.isNotEmpty()) {
                val topNeglected = neglectedLeads.sortedByDescending { it.neglectScore }.take(MAX_NOTIFICATION_ITEMS)
                for (lead in topNeglected) {
                    db.collection(COLLECTION_LEADS).document(lead.id)
                        .update(
                            FIELD_SIDE_KICK_SUGGESTION, lead.suggestion,
                            FIELD_LAST_COACHED_AT, com.google.firebase.Timestamp.now()
                        ).await()
                }
                sendBatchNotification(topNeglected)
            }
        } catch (e: FirebaseFirestoreException) {
            Log.e(TAG, "Transient Firestore error: ${e.message}", e)
            return@withContext Result.retry()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process leads in SideKickWorker", e)
            return@withContext Result.failure()
        }

        Result.success()
    }

    private data class Note(val timestamp: Long, val content: String)

    private data class NeglectedLead(
        val id: String,
        val name: String,
        val phone: String,
        val lastNoteContent: String,
        val suggestion: String,
        val neglectScore: Long
    )

    /**
     * Efficiently parses notes without heavy Regex operations.
     * Expected format: [MMM dd, yyyy HH:mm]: Content
     */
    private fun parseNotes(raw: String): List<Note> {
        if (raw.isBlank()) return emptyList()
        
        val list = mutableListOf<Note>()
        val blocks = raw.split("\n\n")
        
        val formats = listOf(
            SimpleDateFormat("MMM dd, yyyy hh:mm a", Locale.getDefault()),
            SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault()),
            SimpleDateFormat("MMM dd, hh:mm a", Locale.getDefault())
        )
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        
        // Accounts for any spaces left behind by the meta tags
        val metaRegex = Regex("\\s*\\[C:(true|false)\\|T:(true|false)\\]")
        
        for (block in blocks) {
            var trimmed = block.trim()
            if (trimmed.isEmpty()) continue
            
            trimmed = trimmed.replace(metaRegex, "").trim()
            
            // Look for the date header pattern "[...]" safely bypassing dynamic colon spacing
            if (trimmed.startsWith("[")) {
                val closingBracketIndex = trimmed.indexOf("]")
                if (closingBracketIndex != -1) {
                    val dateStr = trimmed.substring(1, closingBracketIndex).trim()
                    var content = trimmed.substring(closingBracketIndex + 1).trim()
                    
                    if (content.startsWith(":")) {
                        content = content.substring(1).trim()
                    }
                    var parsedDate: Date? = null
                    for (format in formats) {
                        try {
                            parsedDate = format.parse(dateStr)
                            if (parsedDate != null) {
                                val cal = Calendar.getInstance()
                                cal.time = parsedDate
                                if (cal.get(Calendar.YEAR) == 1970) {
                                    cal.set(Calendar.YEAR, currentYear)
                                    if (cal.timeInMillis > System.currentTimeMillis() + 86400000L) {
                                        cal.add(Calendar.YEAR, -1)
                                    }
                                    parsedDate = cal.time
                                }
                                break
                            }
                        } catch (e: Exception) {
                            // Try next format
                        }
                    }
                    
                    if (parsedDate != null) {
                        list.add(Note(parsedDate.time, content))
                    }
                }
            }
        }
        return list
    }

    /**
     * Tailors tips based on Primerica's business model and specific keywords, looking at all interactions.
     */
    private fun analyzeLeadHistory(notes: List<Note>): String {
        if (notes.isEmpty()) return "No notes found. Give them a quick call to establish contact."
        
        val allContent = notes.joinToString(" ") { it.content.lowercase() }
        val lastNote = notes.maxByOrNull { it.timestamp }?.content?.lowercase() ?: ""
        
        // 1. Check for repeated failed follow-up attempts
        val followUpAttempts = notes.count { 
            val text = it.content.lowercase()
            text.contains("vm") || text.contains("voicemail") || text.contains("left message") || 
            text.contains("no answer") || text.contains("called") || text.contains("no response")
        }
        
        val hasConnected = allContent.contains("connected") || allContent.contains("spoke") || allContent.contains("met")
        
        if (followUpAttempts >= 3 && !hasConnected) {
            return "You've tried reaching out multiple times without success. Consider sending a 'takeaway' text or wait 30 days before retrying."
        }
        
        // 2. Contextual suggestions based on the most recent interactions
        return when {
            lastNote.contains("reschedule") || lastNote.contains("missed") || lastNote.contains("cancel") || lastNote.contains("no show") ->
                "They missed their appointment. Send a quick text offering two specific alternative times to reschedule."
            lastNote.contains("spouse") || lastNote.contains("wife") || lastNote.contains("husband") || lastNote.contains("partner") ->
                "Make sure to coordinate a time when both them and their partner are available to review the information."
            lastNote.contains("expensive") || lastNote.contains("money") || lastNote.contains("afford") || lastNote.contains("budget") ->
                "They mentioned budget concerns. Focus on finding a comfortable starting point or emphasize the free value of the FNA."
            lastNote.contains("fna") || lastNote.contains("kitchen") || lastNote.contains("kt") || lastNote.contains("data") -> 
                "Suggest following up to present the Financial Needs Analysis (FNA) or finalize next steps."
            lastNote.contains("recruit") || lastNote.contains("op") || lastNote.contains("ibp") || lastNote.contains("interview") || lastNote.contains("video") || lastNote.contains("overview") -> 
                "Follow up on their interest in the business opportunity. Ask what they liked most about the overview."
            lastNote.contains("life") || lastNote.contains("insur") || lastNote.contains("term") || lastNote.contains("app") || lastNote.contains("policy") -> 
                "Check on the status of their life insurance application or discuss the 'Buy Term and Invest the Difference' strategy."
            lastNote.contains("ira") || lastNote.contains("401k") || lastNote.contains("invest") || lastNote.contains("rollover") || lastNote.contains("roth") || lastNote.contains("mutual fund") -> 
                "Suggest touching base on their investment goals, compound interest, or a potential rollover."
            lastNote.contains("met at") || lastNote.contains("approached") || lastNote.contains("stranger") || lastNote.contains("cold") -> 
                "Cold Market contact. Suggest a warm-up text offering a complimentary financial review to build trust."
            lastNote.contains("referral") || lastNote.contains("friend") || lastNote.contains("warm") || lastNote.contains("family") -> 
                "Warm Market referral. Suggest an immediate phone call mentioning your mutual connection."
            else -> "Suggest a general check-in call to see how they're doing and keep the relationship warm."
        }
    }

    private fun sendBatchNotification(leads: List<NeglectedLead>) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(
                    applicationContext,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                Log.w(TAG, "Missing POST_NOTIFICATIONS permission. Cannot show Side-Kick notification.")
                return
            }
        }
        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Automated coaching and lead reminders"
            }
            notificationManager.createNotificationChannel(channel)
        }

        val intent = Intent(applicationContext, FollowUpActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        
        val pendingIntent = PendingIntent.getActivity(
            applicationContext, 
            0, 
            intent, 
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val inboxStyle = NotificationCompat.InboxStyle()
        inboxStyle.setBigContentTitle("Side-Kick: ${leads.size} leads need attention")
        for (lead in leads) {
            inboxStyle.addLine("${lead.name}: ${lead.suggestion}")
        }
        
        val title = "Side-Kick: ${leads.size} leads need attention"

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText("Check coaching suggestions for your neglected leads")
            .setStyle(inboxStyle)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val TAG = "SideKickWorker"
        
        // Configuration
        private const val PREFS_NAME = "settings"
        private const val KEY_ENABLED = "side_kick_enabled"
        private const val CHANNEL_ID = "side_kick_channel"
        private const val CHANNEL_NAME = "Side-Kick AI"
        private const val NOTIFICATION_ID = 1001
        
        // Logic Thresholds
        private const val THRESHOLD_NEGLECT_DAYS = 14L
        private const val THRESHOLD_RECENT_DAYS = 7L
        private const val MIN_NOTES_FOR_NEGLECT = 3
        private const val MAX_NOTIFICATION_ITEMS = 5
        
        // Database Constants
        private const val COLLECTION_LEADS = "leads"
        private const val FIELD_OWNER_ID = "ownerId"
        private const val FIELD_NAME = "name"
        private const val FIELD_PHONE = "phone"
        private const val FIELD_FOLLOW_UP_DATE = "followUpDate"
        private const val FIELD_NOTES = "notes"
        private const val FIELD_SIDE_KICK_SUGGESTION = "sideKickSuggestion"
        private const val FIELD_LAST_COACHED_AT = "lastCoachedAt"
        
        // Utils
        private const val DAY_IN_MS = 24 * 60 * 60 * 1000L
    }
}