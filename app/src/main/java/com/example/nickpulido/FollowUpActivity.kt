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
import com.google.firebase.firestore.FirebaseFirestore
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

class FollowUpActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()
    private val allLeadsData = mutableListOf<Map<String, Any>>()
    private val displayLeadsData = mutableListOf<Map<String, Any>>()
    private lateinit var adapter: FollowUpAdapter
    private var currentSearchQuery = ""
    private var currentCategoryFilter: String? = null
    private var targetPhone: String? = null
    
    private val selectedDocIds = mutableSetOf<String>()
    private var isSelectionMode = false

    private val PREFS_NAME = "SMS_Presets"
    private val PREFS_KEY = "preset_list"
    private val NAME_TOKEN = "[NAME]"

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { writeBackupToUri(it) }
    }

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { readBackupFromUri(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_follow_up)

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
        val btnAddPreset = dialogView.findViewById<Button>(R.id.btnAddPreset)
        val listView = dialogView.findViewById<ListView>(R.id.listViewPresets)
        val btnCleanDuplicates = dialogView.findViewById<Button>(R.id.btnCleanDuplicates)
        val btnBulkDeleteContacts = dialogView.findViewById<Button>(R.id.btnBulkDeleteContacts)
        
        val btnToggleCloud = dialogView.findViewById<Button>(R.id.btnToggleCloudOptions)
        val layoutCloud = dialogView.findViewById<LinearLayout>(R.id.layoutCloudOptions)
        val btnExport = dialogView.findViewById<Button>(R.id.btnExportBackup)
        val btnImport = dialogView.findViewById<Button>(R.id.btnImportBackup)
        val btnOverride = dialogView.findViewById<Button>(R.id.btnOverrideCloud)

        val presets = getPresetsLocally().toMutableList()
        val presetAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, presets)
        listView.adapter = presetAdapter

        btnInsertName.setOnClickListener {
            val start = etNewPreset.selectionStart
            etNewPreset.editableText.insert(start, NAME_TOKEN)
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

        btnToggleCloud.setOnClickListener {
            if (layoutCloud.visibility == View.GONE) {
                layoutCloud.visibility = View.VISIBLE
            } else {
                layoutCloud.visibility = View.GONE
            }
        }

        btnExport.setOnClickListener {
            dialog.dismiss()
            startBackupExport()
        }

        btnImport.setOnClickListener {
            dialog.dismiss()
            startBackupImport()
        }

        btnOverride.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Override Cloud Data?")
                .setMessage("This will DELETE ALL contacts currently in Firebase and replace them with the current data on this phone. This cannot be undone.")
                .setPositiveButton("Override & Sync") { _, _ ->
                    dialog.dismiss()
                    performOverrideCloud()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        dialog.show()
    }

    private fun performOverrideCloud() {
        Toast.makeText(this, "Starting Cloud Override...", Toast.LENGTH_SHORT).show()
        
        db.collection("leads").get().addOnSuccessListener { snapshot ->
            val batch = db.batch()
            for (doc in snapshot.documents) {
                batch.delete(doc.reference)
            }
            
            batch.commit().addOnSuccessListener {
                val uploadBatch = db.batch()
                for (lead in allLeadsData) {
                    val data = lead.filterKeys { it != "__docId" }
                    val newRef = db.collection("leads").document()
                    uploadBatch.set(newRef, data)
                }
                
                uploadBatch.commit().addOnSuccessListener {
                    Toast.makeText(this, "Cloud Override Complete!", Toast.LENGTH_LONG).show()
                    loadLeadsFromCloud()
                }.addOnFailureListener {
                    Toast.makeText(this, "Failed to re-upload data", Toast.LENGTH_SHORT).show()
                }
            }.addOnFailureListener {
                Toast.makeText(this, "Failed to clear cloud data", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startBackupExport() {
        val fileName = "RCRM_Backup_${SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())}.json"
        exportLauncher.launch(fileName)
    }

    private fun writeBackupToUri(uri: Uri) {
        try {
            val jsonArray = JSONArray()
            for (lead in allLeadsData) {
                val jsonObj = JSONObject()
                lead.forEach { (key, value) ->
                    if (key != "__docId") {
                        when (value) {
                            is Timestamp -> jsonObj.put(key, value.seconds)
                            else -> jsonObj.put(key, value)
                        }
                    }
                }
                jsonArray.put(jsonObj)
            }
            
            contentResolver.openOutputStream(uri)?.use { outputStream ->
                outputStream.write(jsonArray.toString(4).toByteArray())
            }
            Toast.makeText(this, "Backup exported successfully", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun startBackupImport() {
        importLauncher.launch(arrayOf("application/json"))
    }

    private fun readBackupFromUri(uri: Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri)
            val jsonString = inputStream?.bufferedReader().use { it?.readText() }
            if (jsonString != null) {
                val jsonArray = JSONArray(jsonString)
                val batch = db.batch()
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val data = mutableMapOf<String, Any>()
                    val keys = obj.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val value = obj.get(key)
                        if (key == "followUpDate" || key == "timestamp") {
                            data[key] = Timestamp(obj.getLong(key), 0)
                        } else {
                            data[key] = value
                        }
                    }
                    val newDocRef = db.collection("leads").document()
                    batch.set(newDocRef, data)
                }
                batch.commit().addOnSuccessListener {
                    Toast.makeText(this, "Import successful", Toast.LENGTH_LONG).show()
                    loadLeadsFromCloud()
                }.addOnFailureListener {
                    Toast.makeText(this, "Import failed during upload", Toast.LENGTH_LONG).show()
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
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
        db.collection("leads").get().addOnSuccessListener { documents ->
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
            val timestamp = SimpleDateFormat("MMM dd, yyyy hh:mm a", Locale.getDefault()).format(Date())
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
                loadLeadsFromCloud()
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