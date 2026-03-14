package com.nickpulido.rcrm

import android.Manifest
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()
    private lateinit var tvTotalLeads: TextView
    private lateinit var hotListCount: TextView
    private lateinit var hotListView: ListView
    private lateinit var searchBar: EditText
    private lateinit var prefs: SharedPreferences

    private lateinit var progressTotal: com.google.android.material.progressindicator.CircularProgressIndicator
    private lateinit var tvTotalGoal: TextView
    private lateinit var progressFollowUps: com.google.android.material.progressindicator.CircularProgressIndicator
    private lateinit var tvFollowUpGoal: TextView
    private lateinit var tvDateDisplay: TextView
    private lateinit var tvHotListHeader: TextView

    private val allLeadsData = mutableListOf<Map<String, Any>>()
    private val displayLeadsData = mutableListOf<Map<String, Any>>()
    private lateinit var hotListAdapter: LeadAdapter

    private var selectedCalendar = Calendar.getInstance()
    private val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
    private val dateKeyFormat = SimpleDateFormat("yyyyMMdd", Locale.getDefault())

    private var accountDefaultSms: String? = null
    private var defaultGoal: Int = 10
    private var defaultFollowUpGoal: Int = 5

    private var leadsListener: ListenerRegistration? = null
    private var statsListener: ListenerRegistration? = null
    
    private var currentDailyGoal: Int = 10
    private var currentFollowUpGoal: Int = 5
    private var currentContactCount: Int = 0
    private var currentFollowUpCount: Int = 0

    private val TAG = "MainActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        if (prefs.getBoolean("dark_mode", false)) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        } else {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        }

        super.onCreate(savedInstanceState)

        if (auth.currentUser == null) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_main)

        tvTotalLeads = findViewById(R.id.tvTotalLeads)
        hotListCount = findViewById(R.id.hot_list_count)
        hotListView = findViewById(R.id.hot_list_view)
        searchBar = findViewById(R.id.search_bar)
        
        progressTotal = findViewById(R.id.progressTotalLeads)
        tvTotalGoal = findViewById(R.id.tvTotalLeadsGoal)
        progressFollowUps = findViewById(R.id.progressFollowUps)
        tvFollowUpGoal = findViewById(R.id.tvFollowUpGoal)
        tvDateDisplay = findViewById(R.id.tvSelectedDateDisplay)
        tvHotListHeader = findViewById(R.id.tvHotListHeader)

        val btnQuickAdd = findViewById<Button>(R.id.btnQuickAdd)
        val btnFollowUpList = findViewById<Button>(R.id.btnFollowUpList)
        val btnContacts = findViewById<Button>(R.id.btn_view_contacts)
        val btnSettings = findViewById<ImageButton>(R.id.btnSettings)
        val btnPickDate = findViewById<Button>(R.id.btnPickDate)

        hotListAdapter = LeadAdapter(this, displayLeadsData)
        hotListView.adapter = hotListAdapter

        checkPermissions()
        searchBar.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { filterLeads(s.toString()) }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        btnQuickAdd.setOnClickListener { showNewLeadDialog() }
        btnFollowUpList.setOnClickListener { startActivity(Intent(this, FollowUpActivity::class.java)) }
        btnContacts.setOnClickListener { startActivity(Intent(this, ContactsActivity::class.java)) }
        btnSettings.setOnClickListener { showSettingsDialog() }
        
        btnPickDate.setOnClickListener { showDatePicker() }

        findViewById<View>(R.id.cardTotalLeads).setOnClickListener { 
            showGoalDialog("total_goal", "Daily Contact Goal", currentDailyGoal) 
        }
        findViewById<View>(R.id.cardDueToday).setOnClickListener { 
            showGoalDialog("followup_goal", "Daily Follow-up Goal", currentFollowUpGoal) 
        }

        hotListView.setOnItemClickListener { _, _, position, _ ->
            val lead = displayLeadsData[position]
            showLeadDetailsDialog(lead, lead["__docId"] as String)
        }

        setupSettingsListener()
        startLeadsListener()
        startStatsListener()
    }

    private fun setupSettingsListener() {
        val userId = auth.currentUser?.uid ?: return
        db.collection("user_settings").document(userId).addSnapshotListener { doc, _ ->
            if (doc != null && doc.exists()) {
                defaultGoal = doc.getLong("total_goal")?.toInt() ?: 10
                defaultFollowUpGoal = doc.getLong("followup_goal")?.toInt() ?: 5
                accountDefaultSms = doc.getString("default_intro_sms")
            }
        }
    }

    private fun startLeadsListener() {
        val userId = auth.currentUser?.uid ?: return
        leadsListener?.remove()
        leadsListener = db.collection("leads").whereEqualTo("ownerId", userId).addSnapshotListener { result, _ ->
            if (result == null) return@addSnapshotListener
            allLeadsData.clear()
            allLeadsData.addAll(result.documents.map {
                val data = it.data?.toMutableMap() ?: mutableMapOf()
                data["__docId"] = it.id
                data
            })
            updateHotList()
        }
    }

    private fun startStatsListener() {
        val userId = auth.currentUser?.uid ?: return
        val dateStr = dateKeyFormat.format(selectedCalendar.time)
        statsListener?.remove()
        statsListener = db.collection("user_settings").document(userId)
            .collection("daily_stats").document(dateStr).addSnapshotListener { doc, _ ->
                if (doc != null && doc.exists()) {
                    currentDailyGoal = doc.getLong("total_goal")?.toInt() ?: defaultGoal
                    currentFollowUpGoal = doc.getLong("followup_goal")?.toInt() ?: defaultFollowUpGoal
                    currentContactCount = doc.getLong("contact_count")?.toInt() ?: 0
                    currentFollowUpCount = doc.getLong("followup_count")?.toInt() ?: 0
                } else {
                    currentDailyGoal = defaultGoal
                    currentFollowUpGoal = defaultFollowUpGoal
                    currentContactCount = 0
                    currentFollowUpCount = 0
                }
                refreshDials()
            }
    }

    private fun showDatePicker() {
        DatePickerDialog(this, { _, year, month, day ->
            selectedCalendar.set(year, month, day)
            val isToday = isSameDay(selectedCalendar, Calendar.getInstance())
            tvDateDisplay.text = if (isToday) "Today" else dateFormat.format(selectedCalendar.time)
            tvHotListHeader.text = if (isToday) "Today's Hot List" else "History for ${dateFormat.format(selectedCalendar.time)}"
            startStatsListener()
            updateHotList()
        }, selectedCalendar.get(Calendar.YEAR), selectedCalendar.get(Calendar.MONTH), selectedCalendar.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun isSameDay(cal1: Calendar, cal2: Calendar): Boolean {
        return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
               cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)
    }

    private fun showGoalDialog(prefKey: String, title: String, currentVal: Int) {
        val input = EditText(this)
        input.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        input.setText(currentVal.toString())

        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage("Set your target for ${if (isSameDay(selectedCalendar, Calendar.getInstance())) "today" else dateFormat.format(selectedCalendar.time)}:")
            .setView(input)
            .setPositiveButton("Set") { _, _ ->
                val goal = input.text.toString().toIntOrNull() ?: currentVal
                saveDailyGoalToFirestore(prefKey, goal)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun saveDailyGoalToFirestore(key: String, value: Int) {
        val userId = auth.currentUser?.uid ?: return
        val dateStr = dateKeyFormat.format(selectedCalendar.time)
        db.collection("user_settings").document(userId)
            .collection("daily_stats").document(dateStr)
            .set(hashMapOf(key to value), SetOptions.merge())
    }

    private fun refreshDials() {
        tvTotalGoal.text = "Goal: $currentDailyGoal"
        progressTotal.max = if (currentDailyGoal > 0) currentDailyGoal else 1
        progressTotal.progress = currentContactCount
        tvTotalLeads.text = currentContactCount.toString()

        tvFollowUpGoal.text = "Goal: $currentFollowUpGoal"
        progressFollowUps.max = if (currentFollowUpGoal > 0) currentFollowUpGoal else 1
        progressFollowUps.progress = currentFollowUpCount
        hotListCount.text = currentFollowUpCount.toString()
    }

    private fun updateHotList() {
        val dateString = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(selectedCalendar.time)
        val startOfDay = (selectedCalendar.clone() as Calendar).apply { 
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) 
        }.time
        val endOfDay = (selectedCalendar.clone() as Calendar).apply { 
            set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 59); set(Calendar.MILLISECOND, 999) 
        }.time

        displayLeadsData.clear()
        if (isSameDay(selectedCalendar, Calendar.getInstance())) {
            val todayEnd = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59) }.time
            for (lead in allLeadsData) {
                val followUp = (lead["followUpDate"] as? Timestamp)?.toDate()
                if (followUp != null && followUp.before(todayEnd)) {
                    displayLeadsData.add(lead)
                }
            }
        } else {
            val dayHistory = allLeadsData.filter { lead ->
                val created = (lead["timestamp"] as? Timestamp)?.toDate()
                val createdOnDay = created != null && created.after(startOfDay) && created.before(endOfDay)
                val notes = lead["notes"] as? String ?: ""
                createdOnDay || notes.contains(dateString)
            }
            displayLeadsData.addAll(dayHistory)
        }
        hotListAdapter.notifyDataSetChanged()
    }

    private fun filterLeads(query: String) {
        if (query.isEmpty()) { 
            updateHotList()
            return 
        }
        val filtered = allLeadsData.filter {
            val name = (it["name"] as? String ?: "").lowercase()
            val notes = (it["notes"] as? String ?: "").lowercase()
            name.contains(query.lowercase()) || notes.contains(query.lowercase())
        }
        displayLeadsData.clear()
        displayLeadsData.addAll(filtered)
        hotListAdapter.notifyDataSetChanged()
    }

    private fun showNewLeadDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_quick_add, null)
        val dialog = AlertDialog.Builder(this).setView(dialogView).create()

        val nameInput = dialogView.findViewById<EditText>(R.id.editQuickName)
        val phoneInput = dialogView.findViewById<EditText>(R.id.editQuickPhone)
        val cbRecruit = dialogView.findViewById<CheckBox>(R.id.cbRecruitQuick)
        val cbProspect = dialogView.findViewById<CheckBox>(R.id.cbProspectQuick)
        val cbClient = dialogView.findViewById<CheckBox>(R.id.cbClientQuick)
        val notesInput = dialogView.findViewById<EditText>(R.id.editQuickNotes)
        val btnSave = dialogView.findViewById<Button>(R.id.btnQuickSave)

        btnSave.setOnClickListener {
            val name = nameInput.text.toString().trim()
            if (name.isEmpty()) return@setOnClickListener

            val cats = mutableListOf<String>()
            if (cbRecruit.isChecked) cats.add(getString(R.string.category_recruit))
            if (cbProspect.isChecked) cats.add(getString(R.string.category_prospect))
            if (cbClient.isChecked) cats.add(getString(R.string.category_client))

            val timestamp = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault()).format(Date())
            val leadData = hashMapOf(
                "name" to name,
                "phone" to phoneInput.text.toString(),
                "category" to cats.joinToString(", "),
                "notes" to "[$timestamp]: ${notesInput.text.ifEmpty { "Created lead" }}",
                "followUpDate" to Timestamp(Date(System.currentTimeMillis() + 86400000)),
                "timestamp" to Timestamp.now(),
                "ownerId" to auth.currentUser?.uid
            )

            db.collection("leads").add(leadData).addOnSuccessListener {
                incrementDailyStat("contact_count")
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun incrementDailyStat(field: String) {
        val userId = auth.currentUser?.uid ?: return
        val dateStr = dateKeyFormat.format(Date()) // Always increment TODAY's stats
        db.collection("user_settings").document(userId)
            .collection("daily_stats").document(dateStr)
            .update(field, FieldValue.increment(1))
            .addOnFailureListener {
                // If doc doesn't exist, create it
                val data = hashMapOf(field to 1, "total_goal" to defaultGoal, "followup_goal" to defaultFollowUpGoal)
                db.collection("user_settings").document(userId)
                    .collection("daily_stats").document(dateStr)
                    .set(data, SetOptions.merge())
            }
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
        val btnSaveChanges = dialogView.findViewById<Button>(R.id.btnSaveChanges)

        val etIntroText = dialogView.findViewById<EditText>(R.id.etIntroText)
        val btnDefaultIntro = dialogView.findViewById<Button>(R.id.btnDefaultIntro)
        val btnManagePresets = dialogView.findViewById<Button>(R.id.btnManagePresets)
        val btnSendIntroAction = dialogView.findViewById<Button>(R.id.btnSendIntroAction)

        editName.setText(lead["name"] as? String ?: "")
        editPhone.setText(lead["phone"] as? String ?: "")
        
        val category = lead["category"] as? String ?: ""
        cbRecruit.isChecked = category.contains(getString(R.string.category_recruit), ignoreCase = true)
        cbProspect.isChecked = category.contains(getString(R.string.category_prospect), ignoreCase = true)
        cbClient.isChecked = category.contains(getString(R.string.category_client), ignoreCase = true)

        val contactName = editName.text.toString()
        fun applyDefaultIntro() {
            val firstName = contactName.split(" ").firstOrNull() ?: contactName
            val baseMsg = accountDefaultSms ?: "Hi [NAME], just following up to see if you had any questions! Looking forward to connecting again."
            etIntroText.setText(baseMsg.replace("[NAME]", firstName))
        }
        applyDefaultIntro()

        btnDefaultIntro.setOnClickListener { applyDefaultIntro() }
        btnManagePresets.setOnClickListener { showPresetManagerForDialog(etIntroText, contactName) }
        
        btnSendIntroAction.setOnClickListener {
            val phone = editPhone.text.toString()
            if (phone.isNotEmpty()) {
                val message = etIntroText.text.toString()
                val smsIntent = Intent(Intent.ACTION_VIEW, "sms:$phone".toUri())
                smsIntent.putExtra("sms_body", message)
                startActivity(smsIntent)
            }
        }

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
                    override fun onTextChanged(s: CharSequence?, start: Int, b: Int, c: Int) { note.content = s.toString() }
                    override fun afterTextChanged(s: Editable?) {}
                })
                btnDelete.setOnClickListener {
                    noteList.remove(note)
                    refreshNotesUI()
                }
                notesContainer.addView(noteView)
            }
        }
        refreshNotesUI()

        btnAddDatedNote.setOnClickListener {
            val ts = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault()).format(Date())
            noteList.add(0, NoteItem(ts, ""))
            refreshNotesUI()
        }

        var newFollowUpDate: Date? = (lead["followUpDate"] as? Timestamp)?.toDate()

        btnSetReminder.setOnClickListener {
            val cal = Calendar.getInstance()
            newFollowUpDate?.let { cal.time = it }
            DatePickerDialog(this, { _, year, month, day ->
                cal.set(year, month, day)
                TimePickerDialog(this, { _, h, m ->
                    cal.set(Calendar.HOUR_OF_DAY, h)
                    cal.set(Calendar.MINUTE, m)
                    newFollowUpDate = cal.time
                    btnSetReminder.text = "Reminder: " + SimpleDateFormat("MMM dd, hh:mm a", Locale.getDefault()).format(cal.time)
                }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), false).show()
            }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
        }

        btnSaveChanges.setOnClickListener {
            val cats = mutableListOf<String>()
            if (cbRecruit.isChecked) cats.add(getString(R.string.category_recruit))
            if (cbProspect.isChecked) cats.add(getString(R.string.category_prospect))
            if (cbClient.isChecked) cats.add(getString(R.string.category_client))
            
            val now = Date()
            val dateStr = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(now)
            val ts = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault()).format(now)
            val followUpMsg = "Followed up on $dateStr"
            
            // Auto-add follow-up note if not already there for today
            if (noteList.none { it.content == followUpMsg }) {
                noteList.add(0, NoteItem(ts, followUpMsg))
                incrementDailyStat("followup_count")
                if (isSameDay(selectedCalendar, Calendar.getInstance())) {
                    currentFollowUpCount++
                }
            }

            // If no manual reminder set, roll it over to tomorrow
            if (newFollowUpDate == null || newFollowUpDate!!.before(now)) {
                val cal = Calendar.getInstance()
                cal.add(Calendar.DAY_OF_YEAR, 1)
                newFollowUpDate = cal.time
            }

            val updatedData = mutableMapOf<String, Any>(
                "name" to editName.text.toString(),
                "phone" to editPhone.text.toString(),
                "category" to cats.joinToString(", "),
                "notes" to serializeNotes(noteList),
                "followUpDate" to Timestamp(newFollowUpDate!!)
            )

            db.collection("leads").document(docId).update(updatedData).addOnSuccessListener {
                refreshDials()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun showSettingsDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_manage_presets, null)
        val dialog = AlertDialog.Builder(this).setView(dialogView).create()

        val etNewPreset = dialogView.findViewById<EditText>(R.id.etNewPreset)
        val btnInsertName = dialogView.findViewById<Button>(R.id.btnInsertNameTagDialog)
        val btnDefaultFollowUp = dialogView.findViewById<Button>(R.id.btnDefaultFollowUp)
        val btnSetAccountDefault = dialogView.findViewById<Button>(R.id.btnSetAccountDefault)
        val btnAddPreset = dialogView.findViewById<Button>(R.id.btnAddPreset)
        val listView = dialogView.findViewById<ListView>(R.id.listViewPresets)
        
        val NAME_TOKEN = "[NAME]"

        val prefs = getSharedPreferences("IntroPresets", Context.MODE_PRIVATE)
        val presets = prefs.getStringSet("saved_presets_list", emptySet())?.toMutableList() ?: mutableListOf()
        val presetAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, presets)
        listView.adapter = presetAdapter

        btnInsertName.setOnClickListener {
            val start = etNewPreset.selectionStart
            etNewPreset.editableText.insert(start, NAME_TOKEN)
        }

        btnDefaultFollowUp.setOnClickListener {
            etNewPreset.setText("Hi $NAME_TOKEN, just following up to see if you had any questions! Looking forward to connecting again.")
        }

        btnSetAccountDefault.setOnClickListener {
            val text = etNewPreset.text.toString().trim()
            if (text.isNotEmpty()) {
                val userId = auth.currentUser?.uid ?: return@setOnClickListener
                db.collection("user_settings").document(userId).update("default_intro_sms", text)
                    .addOnSuccessListener { Toast.makeText(this, "Account Default Set", Toast.LENGTH_SHORT).show() }
            } else {
                Toast.makeText(this, "Enter text first", Toast.LENGTH_SHORT).show()
            }
        }

        btnAddPreset.setOnClickListener {
            val text = etNewPreset.text.toString().trim()
            if (text.isNotEmpty()) {
                presets.add(text)
                prefs.edit().putStringSet("saved_presets_list", presets.toSet()).apply()
                presetAdapter.notifyDataSetChanged()
                etNewPreset.text.clear()
                Toast.makeText(this, "Preset Added", Toast.LENGTH_SHORT).show()
            }
        }
        dialog.show()
    }

    private fun showPresetManagerForDialog(etTarget: EditText, name: String) {
        val prefs = getSharedPreferences("IntroPresets", Context.MODE_PRIVATE)
        val presets = prefs.getStringSet("saved_presets_list", emptySet())?.toList() ?: emptyList()
        
        if (presets.isEmpty()) {
            Toast.makeText(this, "No presets saved.", Toast.LENGTH_SHORT).show()
            return
        }
        val firstName = name.split(" ").firstOrNull() ?: name
        AlertDialog.Builder(this)
            .setTitle("Select Preset")
            .setItems(presets.toTypedArray()) { _, which ->
                etTarget.setText(presets[which].replace("[NAME]", firstName))
            }.show()
    }

    private data class NoteItem(val date: String, var content: String)

    private fun parseNotes(raw: String): MutableList<NoteItem> {
        if (raw.isEmpty()) return mutableListOf()
        val list = mutableListOf<NoteItem>()
        val regex = Regex("\\[(.*?)\\]: (.*)")
        val matches = regex.findAll(raw)
        for (m in matches) {
            list.add(NoteItem(m.groupValues[1], m.groupValues[2]))
        }
        return list
    }

    private fun serializeNotes(list: List<NoteItem>): String {
        return list.joinToString("\n") { "[${it.date}]: ${it.content}" }
    }

    private fun checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.SEND_SMS), 101)
        }
    }
}
