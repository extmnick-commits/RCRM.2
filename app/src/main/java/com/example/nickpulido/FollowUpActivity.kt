package com.nickpulido.rcrm

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.CalendarContract
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.net.toUri
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

class FollowUpActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()
    private val allLeadsData = mutableListOf<Map<String, Any>>()
    private val displayLeadsData = mutableListOf<Map<String, Any>>()
    private lateinit var adapter: FollowUpAdapter
    private var currentSearchQuery = ""
    private var currentCategoryFilter: String? = null
    private var targetPhone: String? = null
    
    private val selectedDocIds = mutableSetOf<String>()
    private var isSelectionMode = false

    private var accountDefaultSms: String? = null
    private var defaultGoal: Int = 10
    private var defaultFollowUpGoal: Int = 5
    private val dateKeyFormat = SimpleDateFormat("yyyyMMdd", Locale.getDefault())

    private lateinit var progressFollowUps: com.google.android.material.progressindicator.CircularProgressIndicator
    private lateinit var tvFollowUpCountDisplay: TextView
    private lateinit var tvFollowUpGoalDisplay: TextView
    private var statsListener: ListenerRegistration? = null
    
    private var currentFollowUpCount = 0
    private var currentFollowUpGoal = 5

    private companion object {
        const val PREFS_NAME = "IntroPresets"
        const val PREFS_KEY = "saved_presets_list"
        const val NAME_TOKEN = "[NAME]"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_follow_up)

        if (auth.currentUser == null) {
            finish()
            return
        }

        progressFollowUps = findViewById(R.id.progressFollowUps)
        tvFollowUpCountDisplay = findViewById(R.id.tvFollowUpCountDisplay)
        tvFollowUpGoalDisplay = findViewById(R.id.tvFollowUpGoalDisplay)

        targetPhone = intent.getStringExtra("targetPhone")

        val listView = findViewById<ListView>(R.id.listViewFollowUps)
        adapter = FollowUpAdapter(this, displayLeadsData) { docId, isChecked ->
            if (isChecked) selectedDocIds.add(docId) else selectedDocIds.remove(docId)
            updateSelectionTitle()
        }
        listView.adapter = adapter

        listView.setOnItemClickListener { _, _, position, _ ->
            if (!isSelectionMode) {
                val lead = displayLeadsData[position]
                showLeadDetailsDialog(lead, lead["__docId"] as String)
            }
        }

        findViewById<EditText>(R.id.etSearchLeads).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                currentSearchQuery = s.toString()
                applyFilters()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        findViewById<ImageButton>(R.id.btnFilterCategory).setOnClickListener { showFilterPopupMenu(it) }
        findViewById<ImageButton>(R.id.btnSettings).setOnClickListener { showSettingsDialog() }
        
        findViewById<View>(R.id.cardFollowUpProgress).setOnClickListener {
            showGoalDialog("followup_goal", "Daily Follow-up Goal", currentFollowUpGoal)
        }

        findViewById<Button>(R.id.btnBulkDelete).setOnClickListener {
            if (selectedDocIds.isEmpty()) {
                Toast.makeText(this, "No leads selected", Toast.LENGTH_SHORT).show()
            } else {
                AlertDialog.Builder(this)
                    .setTitle("Delete Selected?")
                    .setMessage("Are you sure you want to delete ${selectedDocIds.size} leads?")
                    .setPositiveButton("Delete") { _, _ -> performBulkDelete() }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }

        findViewById<Button>(R.id.btnCancelBulk).setOnClickListener {
            exitSelectionMode()
        }

        loadLeadsFromCloud()
        loadAccountSettings()
        startStatsListener()
    }

    private fun loadAccountSettings() {
        val userId = auth.currentUser?.uid ?: return
        db.collection("user_settings").document(userId).addSnapshotListener { snapshot, e ->
            if (e != null) return@addSnapshotListener
            if (snapshot != null && snapshot.exists()) {
                accountDefaultSms = snapshot.getString("default_intro_sms")
                defaultGoal = snapshot.getLong("total_goal")?.toInt() ?: 10
                defaultFollowUpGoal = snapshot.getLong("followup_goal")?.toInt() ?: 5
            }
        }
    }

    private fun startStatsListener() {
        val userId = auth.currentUser?.uid ?: return
        val dateStr = dateKeyFormat.format(Date())
        statsListener?.remove()
        statsListener = db.collection("user_settings").document(userId)
            .collection("daily_stats").document(dateStr).addSnapshotListener { doc, _ ->
                if (doc != null && doc.exists()) {
                    currentFollowUpGoal = doc.getLong("followup_goal")?.toInt() ?: defaultFollowUpGoal
                    currentFollowUpCount = doc.getLong("followup_count")?.toInt() ?: 0
                } else {
                    currentFollowUpGoal = defaultFollowUpGoal
                    currentFollowUpCount = 0
                }
                refreshProgressUI()
            }
    }

    private fun refreshProgressUI() {
        progressFollowUps.max = if (currentFollowUpGoal > 0) currentFollowUpGoal else 1
        progressFollowUps.progress = currentFollowUpCount
        tvFollowUpCountDisplay.text = currentFollowUpCount.toString()
        tvFollowUpGoalDisplay.text = "Goal: $currentFollowUpGoal"
    }

    private fun showGoalDialog(prefKey: String, title: String, currentVal: Int) {
        val input = EditText(this)
        input.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        input.setText(currentVal.toString())

        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage("Set your follow-up target for today:")
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
        val dateStr = dateKeyFormat.format(Date())
        db.collection("user_settings").document(userId)
            .collection("daily_stats").document(dateStr)
            .set(hashMapOf(key to value), SetOptions.merge())
    }

    private fun incrementDailyStat(field: String) {
        val userId = auth.currentUser?.uid ?: return
        val dateStr = dateKeyFormat.format(Date())
        db.collection("user_settings").document(userId)
            .collection("daily_stats").document(dateStr)
            .update(field, FieldValue.increment(1))
            .addOnFailureListener {
                val data = hashMapOf(field to 1, "total_goal" to defaultGoal, "followup_goal" to defaultFollowUpGoal)
                db.collection("user_settings").document(userId)
                    .collection("daily_stats").document(dateStr)
                    .set(data, SetOptions.merge())
            }
    }

    private fun enterSelectionMode() {
        isSelectionMode = true
        selectedDocIds.clear()
        adapter.setSelectionMode(true)
        findViewById<LinearLayout>(R.id.bulkActionBar).visibility = View.VISIBLE
        updateSelectionTitle()
    }

    private fun exitSelectionMode() {
        isSelectionMode = false
        selectedDocIds.clear()
        adapter.setSelectionMode(false)
        findViewById<LinearLayout>(R.id.bulkActionBar).visibility = View.GONE
        findViewById<TextView>(R.id.tvFollowUpHeader).text = getString(R.string.follow_up_header)
    }

    private fun updateSelectionTitle() {
        findViewById<TextView>(R.id.tvFollowUpHeader).text = "${selectedDocIds.size} Selected"
    }

    private fun performBulkDelete() {
        val batch = db.batch()
        selectedDocIds.forEach { docId ->
            batch.delete(db.collection("leads").document(docId))
        }
        batch.commit().addOnSuccessListener {
            Toast.makeText(this, "Deleted successfully", Toast.LENGTH_SHORT).show()
            exitSelectionMode()
            loadLeadsFromCloud()
        }.addOnFailureListener {
            Toast.makeText(this, "Error deleting leads", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showFilterPopupMenu(view: View) {
        val popup = PopupMenu(this, view)
        popup.menu.add("All Categories")
        popup.menu.add(getString(R.string.category_recruit))
        popup.menu.add(getString(R.string.category_prospect))
        popup.menu.add(getString(R.string.category_client))

        popup.setOnMenuItemClickListener { item ->
            currentCategoryFilter = if (item.title == "All Categories") null else item.title.toString()
            applyFilters()
            true
        }
        popup.show()
    }

    private fun applyFilters() {
        displayLeadsData.clear()
        for (lead in allLeadsData) {
            val name = (lead["name"] as? String ?: "").lowercase()
            val category = (lead["category"] as? String ?: "").lowercase()
            
            val matchesSearch = name.contains(currentSearchQuery.lowercase())
            val matchesCategory = currentCategoryFilter == null || category.contains(currentCategoryFilter!!.lowercase())

            if (matchesSearch && matchesCategory) {
                displayLeadsData.add(lead)
            }
        }
        adapter.notifyDataSetChanged()
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
        val btnCleanDuplicates = dialogView.findViewById<Button>(R.id.btnCleanDuplicates)
        val btnBulkDeleteContacts = dialogView.findViewById<Button>(R.id.btnBulkDeleteContacts)
        
        val presets = getPresetsLocally().toMutableList()
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
                saveAccountDefaultSms(text)
            } else {
                Toast.makeText(this, "Enter text first", Toast.LENGTH_SHORT).show()
            }
        }

        btnAddPreset.setOnClickListener {
            val text = etNewPreset.text.toString().trim()
            if (text.isNotEmpty()) {
                presets.add(text)
                saveAllPresetsLocally(presets)
                presetAdapter.notifyDataSetChanged()
                etNewPreset.text.clear()
                Toast.makeText(this, "Preset Added", Toast.LENGTH_SHORT).show()
            }
        }

        listView.setOnItemLongClickListener { _, _, position, _ ->
            val selectedPreset = presets[position]
            AlertDialog.Builder(this)
                .setTitle("Preset Actions")
                .setItems(arrayOf("Set as Account Default", getString(R.string.menu_delete_preset))) { _, which ->
                    if (which == 0) {
                        saveAccountDefaultSms(selectedPreset)
                    } else {
                        AlertDialog.Builder(this)
                            .setTitle("Delete Preset?")
                            .setMessage("Are you sure you want to remove this message?")
                            .setPositiveButton("Delete") { _, _ ->
                                presets.removeAt(position)
                                saveAllPresetsLocally(presets)
                                presetAdapter.notifyDataSetChanged()
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                }
                .show()
            true
        }

        btnCleanDuplicates.setOnClickListener {
            dialog.dismiss()
            findAndMergeDuplicates()
        }

        btnBulkDeleteContacts.setOnClickListener {
            dialog.dismiss()
            enterSelectionMode()
        }

        dialog.show()
    }

    private fun saveAccountDefaultSms(text: String) {
        val userId = auth.currentUser?.uid ?: return
        db.collection("user_settings").document(userId)
            .set(mapOf("default_intro_sms" to text), SetOptions.merge())
            .addOnSuccessListener {
                Toast.makeText(this, "Account default updated", Toast.LENGTH_SHORT).show()
            }
    }

    private fun findAndMergeDuplicates() {
        val duplicates = mutableMapOf<String, MutableList<Map<String, Any>>>()
        for (lead in allLeadsData) {
            val name = (lead["name"] as? String ?: "").lowercase().trim()
            if (name.isNotEmpty()) {
                duplicates.getOrPut(name) { mutableListOf() }.add(lead)
            }
        }

        val groupsToMerge = duplicates.filter { it.value.size > 1 }
        if (groupsToMerge.isEmpty()) {
            Toast.makeText(this, "No duplicates found", Toast.LENGTH_SHORT).show()
            return
        }

        showMergeDuplicatesDialog(groupsToMerge.values.toList())
    }

    private fun showMergeDuplicatesDialog(groups: List<List<Map<String, Any>>>) {
        var currentGroupIndex = 0

        fun processNextGroup() {
            if (currentGroupIndex >= groups.size) {
                Toast.makeText(this, "All duplicates processed", Toast.LENGTH_SHORT).show()
                loadLeadsFromCloud()
                return
            }

            val group = groups[currentGroupIndex]
            val names = group.map { it["name"] as? String ?: "Unknown" }
            val phones = group.map { it["phone"] as? String ?: "No Phone" }
            
            val items = names.zip(phones).map { "${it.first} (${it.second})" }.toTypedArray()

            AlertDialog.Builder(this)
                .setTitle("Merge Duplicates (${currentGroupIndex + 1}/${groups.size})")
                .setItems(items) { _, which ->
                    val masterLead = group[which]
                    val otherLeads = group.filterIndexed { index, _ -> index != which }
                    mergeLeads(masterLead, otherLeads) {
                        currentGroupIndex++
                        processNextGroup()
                    }
                }
                .setNeutralButton("Skip") { _, _ ->
                    currentGroupIndex++
                    processNextGroup()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        processNextGroup()
    }

    private fun mergeLeads(master: Map<String, Any>, others: List<Map<String, Any>>, onComplete: () -> Unit) {
        val masterId = master["__docId"] as String
        val masterNotes = master["notes"] as? String ?: ""
        val otherNotes = others.joinToString("\n\n") { it["notes"] as? String ?: "" }
        val mergedNotes = if (otherNotes.isEmpty()) masterNotes else "$masterNotes\n\n--- Merged Notes ---\n\n$otherNotes"

        val batch = db.batch()
        batch.update(db.collection("leads").document(masterId), "notes", mergedNotes)
        others.forEach { lead ->
            val id = lead["__docId"] as String
            batch.delete(db.collection("leads").document(id))
        }

        batch.commit().addOnSuccessListener {
            onComplete()
        }.addOnFailureListener {
            Toast.makeText(this, "Failed to merge", Toast.LENGTH_SHORT).show()
            onComplete()
        }
    }

    private fun loadLeadsFromCloud() {
        val userId = auth.currentUser?.uid ?: return
        
        // Ensure users only load their own leads
        db.collection("leads")
            .whereEqualTo("ownerId", userId)
            .get()
            .addOnSuccessListener { documents ->
                allLeadsData.clear()
                for (document in documents) {
                    val data = document.data.toMutableMap()
                    data["__docId"] = document.id
                    allLeadsData.add(data)
                }
                applyFilters()
                
                targetPhone?.let { phone ->
                    val targetIndex = displayLeadsData.indexOfFirst { it["phone"] == phone }
                    if (targetIndex != -1) {
                        val targetLead = displayLeadsData[targetIndex]
                        showLeadDetailsDialog(targetLead, targetLead["__docId"] as String)
                        targetPhone = null
                    }
                }
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
        val btnAddCalendar = dialogView.findViewById<Button>(R.id.btnAddCalendar)
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
            val baseMsg = accountDefaultSms ?: "Hi $NAME_TOKEN, just following up to see if you had any questions! Looking forward to connecting again."
            etIntroText.setText(baseMsg.replace(NAME_TOKEN, firstName))
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
            } else {
                Toast.makeText(this, "No phone number", Toast.LENGTH_SHORT).show()
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
            val timestamp = SimpleDateFormat("MMM dd, yyyy hh:mm a", Locale.getDefault()).format(Date())
            noteList.add(0, NoteItem(timestamp, ""))
            refreshNotesUI()
        }

        btnAddCalendar.setOnClickListener {
            val intent = Intent(Intent.ACTION_INSERT).apply {
                data = CalendarContract.Events.CONTENT_URI
                putExtra(CalendarContract.Events.TITLE, getString(R.string.calendar_call_title, editName.text.toString()))
                val startTime = (lead["followUpDate"] as? Timestamp)?.toDate()?.time ?: System.currentTimeMillis()
                putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startTime)
                putExtra(CalendarContract.EXTRA_EVENT_END_TIME, startTime + 3600000)
            }
            startActivity(intent)
        }

        var newFollowUpDate: Date? = (lead["followUpDate"] as? Timestamp)?.toDate()

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
            
            val now = Date()
            val dateStr = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(now)
            val ts = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault()).format(now)
            val followUpMsg = "Followed up on $dateStr"
            
            var shouldIncrement = false
            // Auto-add follow-up note if not already there for today
            if (noteList.none { it.content == followUpMsg }) {
                noteList.add(0, NoteItem(ts, followUpMsg))
                shouldIncrement = true
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
                if (shouldIncrement) {
                    incrementDailyStat("followup_count")
                }
                Toast.makeText(this, "Lead Updated", Toast.LENGTH_SHORT).show()
                loadLeadsFromCloud()
                dialog.dismiss()
            }
        }

        dialog.show()
    }

    private fun showPresetManagerForDialog(etTarget: EditText, name: String) {
        val presets = getPresetsLocally().toList()
        if (presets.isEmpty()) {
            Toast.makeText(this, "No presets saved", Toast.LENGTH_SHORT).show()
            return
        }
        val firstName = name.split(" ").firstOrNull() ?: name
        AlertDialog.Builder(this)
            .setTitle("Select Preset")
            .setItems(presets.toTypedArray()) { _, which ->
                etTarget.setText(presets[which].replace(NAME_TOKEN, firstName))
            }.show()
    }

    private data class NoteItem(val date: String, var content: String)

    private fun parseNotes(raw: String): MutableList<NoteItem> {
        val list = mutableListOf<NoteItem>()
        val blocks = raw.split("\n\n")
        for (block in blocks) {
            var trimmed = block.trim()
            if (trimmed.isEmpty()) continue
            
            while (trimmed.startsWith("[Legacy Note]:", ignoreCase = true)) {
                trimmed = trimmed.substringAfter(":", "").trim()
            }

            val dateRegex = Regex("^\\[?([A-Z][a-z]{2} \\d{2}, (?:\\d{4} )?\\d{2}:\\d{2}(?: [AaPp][Mm])?)\\]?:?\\s*(.*)", RegexOption.DOT_MATCHES_ALL)
            val dateMatch = dateRegex.find(trimmed)
            
            if (dateMatch != null) {
                list.add(NoteItem(dateMatch.groupValues[1], dateMatch.groupValues[2].trim()))
            } else if (trimmed.isNotEmpty()) {
                list.add(NoteItem("Legacy Note", trimmed))
            }
        }
        return list
    }

    private fun serializeNotes(list: List<NoteItem>): String {
        return list.joinToString("\n\n") { "[${it.date}]: ${it.content}" }
    }

    private fun saveAllPresetsLocally(list: List<String>) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit { putStringSet(PREFS_KEY, list.toSet()) }
    }

    private fun getPresetsLocally(): Set<String> {
        return getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getStringSet(PREFS_KEY, emptySet()) ?: emptySet()
    }

    override fun onDestroy() {
        super.onDestroy()
        statsListener?.remove()
    }
}

class FollowUpAdapter(
    context: Context,
    private val leads: List<Map<String, Any>>,
    private val onSelectionChanged: (String, Boolean) -> Unit
) : ArrayAdapter<Map<String, Any>>(context, 0, leads) {

    private var selectionMode = false

    fun setSelectionMode(enabled: Boolean) {
        selectionMode = enabled
        notifyDataSetChanged()
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_lead_card, parent, false)
        val tvName = view.findViewById<TextView>(R.id.tvLeadName)
        val tvDetails = view.findViewById<TextView>(R.id.tvLeadDetails)
        val tvCategoryLabel = view.findViewById<TextView>(R.id.tvCategoryLabel)
        val btnCallIcon = view.findViewById<ImageView>(R.id.btnCallIcon)
        val cbSelectLead = view.findViewById<CheckBox>(R.id.cbSelectLead)

        val lead = leads[position]
        val docId = lead["__docId"] as? String ?: ""
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

        if (selectionMode) {
            cbSelectLead.visibility = View.VISIBLE
            btnCallIcon.visibility = View.GONE
            cbSelectLead.setOnCheckedChangeListener(null)
            cbSelectLead.isChecked = false
            cbSelectLead.setOnCheckedChangeListener { _, isChecked ->
                onSelectionChanged(docId, isChecked)
            }
        } else {
            cbSelectLead.visibility = View.GONE
            btnCallIcon.visibility = View.VISIBLE
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
