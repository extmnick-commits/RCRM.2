package com.example.rcrm

import android.app.AlarmManager
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.PendingIntent
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.CalendarContract
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import java.text.SimpleDateFormat
import java.util.*

class FollowUpActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()
    private val displayLeadsData = mutableListOf<Map<String, Any>>()
    private val documentIds = mutableListOf<String>()
    private lateinit var adapter: FollowUpAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_follow_up)

        val listView = findViewById<ListView>(R.id.listViewFollowUps)
        findViewById<LinearLayout>(R.id.bulkActionBar).visibility = View.GONE

        adapter = FollowUpAdapter(this, displayLeadsData)
        listView.adapter = adapter

        loadLeadsFromCloud()

        listView.setOnItemClickListener { _, _, position, _ ->
            showLeadDetailsDialog(displayLeadsData[position], documentIds[position])
        }
    }

    private fun loadLeadsFromCloud() {
        db.collection("leads").get().addOnSuccessListener { documents ->
            displayLeadsData.clear()
            documentIds.clear()
            for (document in documents) {
                displayLeadsData.add(document.data)
                documentIds.add(document.id)
            }
            adapter.notifyDataSetChanged()
        }
    }

    private fun showLeadDetailsDialog(lead: Map<String, Any>, docId: String) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_lead_details, null)
        val dialog = AlertDialog.Builder(this).setView(dialogView).create()

        val editName = dialogView.findViewById<EditText>(R.id.editDetailName)
        val editPhone = dialogView.findViewById<EditText>(R.id.editDetailPhone)
        val editCategory = dialogView.findViewById<EditText>(R.id.editDetailCategory)
        val editNotes = dialogView.findViewById<EditText>(R.id.editDetailNotes)
        val btnSetReminder = dialogView.findViewById<Button>(R.id.btnSetReminder)
        val btnAddDatedNote = dialogView.findViewById<Button>(R.id.btnAddDatedNote)
        val btnAddCalendar = dialogView.findViewById<Button>(R.id.btnAddCalendar)
        val btnSaveChanges = dialogView.findViewById<Button>(R.id.btnSaveChanges)

        editName.setText(lead["name"] as? String ?: "")
        editPhone.setText(lead["phone"] as? String ?: "")
        editCategory.setText(lead["category"] as? String ?: "")
        editNotes.setText(lead["notes"] as? String ?: "")

        var newFollowUpDate: Date? = (lead["followUpDate"] as? Timestamp)?.toDate()

        btnAddDatedNote.setOnClickListener {
            val timestamp = SimpleDateFormat("[MMM dd, yyyy HH:mm]", Locale.getDefault()).format(Date())
            val currentText = editNotes.text.toString()
            val updatedNotes = "$timestamp: \n$currentText"
            editNotes.setText(updatedNotes)
            editNotes.setSelection(timestamp.length + 2)
        }

        btnAddCalendar.setOnClickListener {
            val intent = Intent(Intent.ACTION_INSERT).apply {
                data = CalendarContract.Events.CONTENT_URI
                putExtra(CalendarContract.Events.TITLE, getString(R.string.calendar_call_title, editName.text.toString()))
                putExtra(CalendarContract.Events.DESCRIPTION, getString(R.string.calendar_description_format, editPhone.text.toString(), editNotes.text.toString()))

                val startTime = newFollowUpDate?.time ?: System.currentTimeMillis()
                putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startTime)
                putExtra(CalendarContract.EXTRA_EVENT_END_TIME, startTime + 3600000)
            }
            startActivity(intent)
        }

        btnSetReminder.setOnClickListener {
            val cal = Calendar.getInstance()
            newFollowUpDate?.let { cal.time = it }

            DatePickerDialog(this, { _, year, month, day ->
                cal.set(year, month, day)
                TimePickerDialog(this, { _, hour, minute ->
                    cal.set(Calendar.HOUR_OF_DAY, hour)
                    cal.set(Calendar.MINUTE, minute)

                    newFollowUpDate = cal.time
                    scheduleSystemNotification(cal.timeInMillis, editName.text.toString())

                    val format = SimpleDateFormat("MMM dd, hh:mm a", Locale.getDefault())
                    btnSetReminder.text = getString(R.string.msg_reminder_set, format.format(cal.time))

                }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), false).show()
            }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
        }

        btnSaveChanges.setOnClickListener {
            val updatedData = mapOf(
                "name" to editName.text.toString(),
                "phone" to editPhone.text.toString(),
                "category" to editCategory.text.toString(),
                "notes" to editNotes.text.toString(),
                "followUpDate" to newFollowUpDate
            )
            db.collection("leads").document(docId).update(updatedData).addOnSuccessListener {
                Toast.makeText(this, getString(R.string.msg_lead_updated), Toast.LENGTH_SHORT).show()
                dialog.dismiss()
                loadLeadsFromCloud()
            }
        }
        dialog.show()
    }

    private fun scheduleSystemNotification(timeInMillis: Long, leadName: String) {
        // FIXED: Removed redundant 'Context.' qualifier
        val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            val intent = Intent().apply { action = Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM }
            startActivity(intent)
            return
        }

        val intent = Intent(this, ReminderReceiver::class.java).apply {
            putExtra("LEAD_NAME", leadName)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            this,
            leadName.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, timeInMillis, pendingIntent)
        Toast.makeText(this, "Notification set for $leadName", Toast.LENGTH_SHORT).show()
    }
}

class FollowUpAdapter(context: Context, private val leads: List<Map<String, Any>>) :
    ArrayAdapter<Map<String, Any>>(context, 0, leads) {
    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_lead_card, parent, false)
        val tvName = view.findViewById<TextView>(R.id.tvLeadName)
        val tvDetails = view.findViewById<TextView>(R.id.tvLeadDetails)
        val btnCallIcon = view.findViewById<ImageView>(R.id.btnCallIcon)

        val lead = leads[position]
        val name = lead["name"] as? String ?: context.getString(R.string.unknown_contact)
        val category = lead["category"] as? String ?: context.getString(R.string.category_prospect)
        val notes = lead["notes"] as? String ?: ""

        tvName.text = context.getString(R.string.lead_name_format, name)
        tvDetails.text = if (notes.isNotEmpty()) {
            context.getString(R.string.lead_details_format, category, notes)
        } else {
            category
        }

        btnCallIcon.setOnClickListener {
            val phone = lead["phone"] as? String
            if (!phone.isNullOrEmpty()) {
                val intent = Intent(Intent.ACTION_DIAL, "tel:$phone".toUri())
                context.startActivity(intent)
            } else {
                Toast.makeText(context, context.getString(R.string.msg_no_phone), Toast.LENGTH_SHORT).show()
            }
        }
        return view
    }
}