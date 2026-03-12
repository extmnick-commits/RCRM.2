package com.nickpulido.rcrm

import android.Manifest
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.CalendarContract
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()
    private lateinit var tvTotalLeads: TextView
    private lateinit var hotListCount: TextView
    private lateinit var hotListView: ListView
    private lateinit var searchBar: EditText

    private val allLeadsData = mutableListOf<Map<String, Any>>()
    private val displayLeadsData = mutableListOf<Map<String, Any>>()
    private lateinit var hotListAdapter: LeadAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvTotalLeads = findViewById(R.id.tvTotalLeads)
        hotListCount = findViewById(R.id.hot_list_count)
        hotListView = findViewById(R.id.hot_list_view)
        searchBar = findViewById(R.id.search_bar)

        val btnQuickAdd = findViewById<Button>(R.id.btnQuickAdd)
        val btnFollowUpList = findViewById<Button>(R.id.btnFollowUpList)
        val btnContacts = findViewById<Button>(R.id.btn_view_contacts)

        hotListAdapter = LeadAdapter(this, displayLeadsData)
        hotListView.adapter = hotListAdapter

        checkPermissions()
        searchBar.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { filterLeads(s.toString()) }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        btnQuickAdd.setOnClickListener { showNewLeadDialog() }
        btnFollowUpList.setOnClickListener {
            startActivity(Intent(this, FollowUpActivity::class.java))
        }
        btnContacts.setOnClickListener {
            startActivity(Intent(this, FollowUpActivity::class.java))
        }

        hotListView.setOnItemClickListener { _, _, position, _ ->
            val lead = displayLeadsData[position]
            val docId = lead["__docId"] as? String ?: ""
            showLeadDetailsDialog(lead, docId)
        }

        refreshDashboard()
    }

    override fun onResume() {
        super.onResume()
        refreshDashboard()
    }

    private fun refreshDashboard() {
        db.collection("leads").get().addOnSuccessListener { result ->
            tvTotalLeads.text = result.size().toString()
            val leadsList = result.documents.map { 
                val data = it.data?.toMutableMap() ?: mutableMapOf()
                data["__docId"] = it.id
                data
            }
            loadHotList(leadsList)
        }
    }

    private fun loadHotList(allData: List<Map<String, Any>>) {
        allLeadsData.clear()
        displayLeadsData.clear()
        var dueCount = 0
        val today = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
        }.time

        for (data in allData) {
            allLeadsData.add(data)
            val followUp = (data["followUpDate"] as? Timestamp)?.toDate()
            if (followUp != null && followUp.before(today)) {
                displayLeadsData.add(data)
                dueCount++
            }
        }
        hotListCount.text = dueCount.toString()
        hotListAdapter.notifyDataSetChanged()
    }

    private fun filterLeads(query: String) {
        displayLeadsData.clear()
        if (query.isEmpty()) {
            refreshDashboard()
            return
        }
        val filtered = allLeadsData.filter {
            val name = (it["name"] as? String ?: "").lowercase()
            val notes = (it["notes"] as? String ?: "").lowercase()
            name.contains(query.lowercase()) || notes.contains(query.lowercase())
        }
        displayLeadsData.addAll(filtered)
        hotListAdapter.notifyDataSetChanged()
    }

    private fun showNewLeadDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle(getString(R.string.dialog_title_quick_add))

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 40, 60, 10)
        }

        val nameInput = EditText(this).apply {
            hint = getString(R.string.hint_prospect_name)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                setAutofillHints(View.AUTOFILL_HINT_NAME)
            }
        }
        val phoneInput = EditText(this).apply {
            hint = getString(R.string.hint_phone_number)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                setAutofillHints(View.AUTOFILL_HINT_PHONE)
            }
        }
        val categoryInput = EditText(this).apply { hint = getString(R.string.hint_category) }
        val notesInput = EditText(this).apply { hint = getString(R.string.hint_notes) }

        layout.addView(nameInput); layout.addView(phoneInput); layout.addView(categoryInput); layout.addView(notesInput)
        builder.setView(layout)

        builder.setPositiveButton(getString(R.string.btn_save)) { _, _ ->
            val tomorrow = Date(System.currentTimeMillis() + 86400000)
            val leadData = hashMapOf(
                "name" to nameInput.text.toString(),
                "phone" to phoneInput.text.toString(),
                "category" to categoryInput.text.toString(),
                "notes" to notesInput.text.toString(),
                "followUpDate" to tomorrow,
                "timestamp" to Timestamp.now()
            )
            db.collection("leads").add(leadData).addOnSuccessListener {
                Toast.makeText(this, getString(R.string.msg_lead_saved), Toast.LENGTH_SHORT).show()
                refreshDashboard()
            }
        }
        builder.show()
    }

    private fun showLeadDetailsDialog(lead: Map<String, Any>, docId: String) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_lead_details, null)
        val dialog = AlertDialog.Builder(this).setView(dialogView).create()

        val editName = dialogView.findViewById<EditText>(R.id.editDetailName)
        val editPhone = dialogView.findViewById<EditText>(R.id.editDetailPhone)
        val cbRecruit = dialogView.findViewById<CheckBox>(R.id.cbRecruitDetail)
        val cbProspect = dialogView.findViewById<CheckBox>(R.id.cbProspectDetail)
        val cbClient = dialogView.findViewById<CheckBox>(R.id.cbClientDetail)
        val notesContainer = dialogView.findViewById<LinearLayout>(R.id.notesContainer)
        val btnAddDatedNote = dialogView.findViewById<Button>(R.id.btnAddDatedNote)
        val btnSetReminder = dialogView.findViewById<Button>(R.id.btnSetReminder)
        val btnAddCalendar = dialogView.findViewById<Button>(R.id.btnAddCalendar)
        val btnSaveChanges = dialogView.findViewById<Button>(R.id.btnSaveChanges)

        editName.setText(lead["name"] as? String ?: "")
        editPhone.setText(lead["phone"] as? String ?: "")
        
        val category = lead["category"] as? String ?: ""
        cbRecruit.isChecked = category.contains(getString(R.string.category_recruit), ignoreCase = true)
        cbProspect.isChecked = category.contains(getString(R.string.category_prospect), ignoreCase = true)
        cbClient.isChecked = category.contains(getString(R.string.category_client), ignoreCase = true)

        val rawNotes = lead["notes"] as? String ?: ""
        val noteList = parseNotes(rawNotes)
        
        fun refreshNotesUI() {
            notesContainer.removeAllViews()
            for (note in noteList) {
                val noteView = LayoutInflater.from(this).inflate(R.layout.item_note, notesContainer, false)
                val tvDate = noteView.findViewById<TextView>(R.id.tvNoteDate)
                val etContent = noteView.findViewById<EditText>(R.id.etNoteContent)
                val btnDelete = noteView.findViewById<ImageButton>(R.id.btnDeleteNote)
                
                tvDate.text = note.date
                etContent.setText(note.content)
                
                etContent.addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        note.content = s.toString()
                    }
                    override fun afterTextChanged(s: Editable?) {}
                })

                btnDelete.setOnClickListener {
                    AlertDialog.Builder(this)
                        .setTitle("Delete Note?")
                        .setMessage("Are you sure you want to remove this specific note?")
                        .setPositiveButton("Delete") { _, _ ->
                            noteList.remove(note)
                            refreshNotesUI()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
                
                notesContainer.addView(noteView)
            }
        }
        refreshNotesUI()

        btnAddDatedNote.setOnClickListener {
            val timestamp = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault()).format(Date())
            noteList.add(0, NoteItem(timestamp, ""))
            refreshNotesUI()
        }

        var newFollowUpDate: Date? = (lead["followUpDate"] as? Timestamp)?.toDate()

        btnAddCalendar.setOnClickListener {
            val intent = Intent(Intent.ACTION_INSERT).apply {
                data = CalendarContract.Events.CONTENT_URI
                putExtra(CalendarContract.Events.TITLE, getString(R.string.calendar_call_title, editName.text.toString()))
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
                    val format = SimpleDateFormat("MMM dd, hh:mm a", Locale.getDefault())
                    btnSetReminder.text = getString(R.string.msg_reminder_set, format.format(cal.time))
                }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), false).show()
            }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
        }

        btnSaveChanges.setOnClickListener {
            val cats = mutableListOf<String>()
            if (cbRecruit.isChecked) cats.add(getString(R.string.category_recruit))
            if (cbProspect.isChecked) cats.add(getString(R.string.category_prospect))
            if (cbClient.isChecked) cats.add(getString(R.string.category_client))
            
            val updatedData = mutableMapOf<String, Any>(
                "name" to editName.text.toString(),
                "phone" to editPhone.text.toString(),
                "category" to cats.joinToString(", "),
                "notes" to serializeNotes(noteList)
            )
            newFollowUpDate?.let { updatedData["followUpDate"] = Timestamp(it) }

            db.collection("leads").document(docId).update(updatedData).addOnSuccessListener {
                Toast.makeText(this, "Lead Updated", Toast.LENGTH_SHORT).show()
                refreshDashboard()
                dialog.dismiss()
            }
        }

        dialog.show()
    }

    private data class NoteItem(val date: String, var content: String)

    private fun parseNotes(raw: String): MutableList<NoteItem> {
        val list = mutableListOf<NoteItem>()
        val blocks = raw.split("\n\n")
        for (block in blocks) {
            val trimmed = block.trim()
            if (trimmed.isEmpty()) continue
            val dateMatch = Regex("^\\[?([A-Z][a-z]{2} \\d{2}, \\d{4} \\d{2}:\\d{2})\\]?:?\\s*(.*)", RegexOption.DOT_MATCHES_ALL).find(trimmed)
            if (dateMatch != null) {
                list.add(NoteItem(dateMatch.groupValues[1], dateMatch.groupValues[2].trim()))
            } else {
                list.add(NoteItem("Legacy Note", trimmed))
            }
        }
        return list
    }

    private fun serializeNotes(list: List<NoteItem>): String {
        return list.joinToString("\n\n") { "[${it.date}]: ${it.content}" }
    }

    private fun checkPermissions() {
        val permissions = arrayOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.SEND_SMS
        )
        val toRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (toRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, toRequest.toTypedArray(), 1)
        }

        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                "package:$packageName".toUri()
            )
            Toast.makeText(this, "Please allow 'Display over other apps' for automatic call logging.", Toast.LENGTH_LONG).show()
            startActivity(intent)
        }
    }
}

class LeadAdapter(context: Context, private val leads: List<Map<String, Any>>) :
    ArrayAdapter<Map<String, Any>>(context, 0, leads) {
    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_lead_card, parent, false)
        val tvName = view.findViewById<TextView>(R.id.tvLeadName)
        val tvDetails = view.findViewById<TextView>(R.id.tvLeadDetails)
        val tvCategoryLabel = view.findViewById<TextView>(R.id.tvCategoryLabel)
        val btnCallIcon = view.findViewById<ImageView>(R.id.btnCallIcon)
        val cbSelectLead = view.findViewById<CheckBox>(R.id.cbSelectLead)

        cbSelectLead.visibility = View.GONE // Ensure checkbox is hidden on Main screen

        val lead = leads[position]
        val name = lead["name"] as? String ?: context.getString(R.string.unknown_contact)
        val category = lead["category"] as? String ?: ""
        val notes = lead["notes"] as? String ?: ""

        tvName.text = context.getString(R.string.lead_name_format, name)
        
        if (category.isNotEmpty()) {
            tvCategoryLabel.text = category
            tvCategoryLabel.visibility = View.VISIBLE
            when {
                category.contains(context.getString(R.string.category_recruit), ignoreCase = true) -> 
                    tvCategoryLabel.setBackgroundColor(Color.parseColor("#4CAF50"))
                category.contains(context.getString(R.string.category_client), ignoreCase = true) -> 
                    tvCategoryLabel.setBackgroundColor(Color.parseColor("#9C27B0"))
                else -> tvCategoryLabel.setBackgroundColor(Color.parseColor("#007BFF"))
            }
        } else {
            tvCategoryLabel.visibility = View.GONE
        }

        val latestNote = notes.substringBefore("\n\n").replace(Regex("^\\[.*?\\]: "), "")
        tvDetails.text = latestNote.ifEmpty { "No notes available" }

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