package com.nickpulido.rcrm

import android.annotation.SuppressLint
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.nickpulido.rcrm.databinding.ActivityFollowUpBinding
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

class FollowUpActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()
    private lateinit var binding: ActivityFollowUpBinding
    private val allLeads = mutableListOf<Map<String, Any>>()
    private val leads = mutableListOf<Map<String, Any>>()
    private lateinit var adapter: FollowUpAdapter
    private var statsListener: ListenerRegistration? = null
    private var currentSearchQuery = ""
    private var currentCategoryFilter = "All"
    private var showOnlySideKick = false

    private var applyVoiceLogToDialog: ((org.json.JSONObject, String) -> Unit)? = null

    private var onVoiceLogComplete: (() -> Unit)? = null

    private val speechRecognizerLauncherDetails = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val data = result.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)
            val spokenText = data?.get(0) ?: ""
            if (spokenText.isNotEmpty()) {
                processVoiceLogDetailsWithAI(spokenText)
            }
        }
    }

    private var dictatingForLeadId: String? = null

    private val speechRecognizerLauncherList = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val data = result.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)
            val spokenText = data?.get(0) ?: ""
            val leadId = dictatingForLeadId
            if (spokenText.isNotEmpty() && leadId != null) {
                processVoiceLogListWithAI(leadId, spokenText)
            }
        }
        dictatingForLeadId = null
    }
    
    private companion object {
        const val PREFS_NAME = "IntroPresets"
        const val PREFS_KEY = "saved_presets_list"
        const val NAME_TOKEN = "[NAME]"
        const val TAG = "FollowUpActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFollowUpBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.rvFollowUps.layoutManager = LinearLayoutManager(this)
        adapter = FollowUpAdapter(
            leads, 
            { id, isPinned ->
                db.collection("leads").document(id).update("isPinned", isPinned)
                    .addOnSuccessListener {
                        val lead = allLeads.find { it["id"] == id } as? MutableMap<String, Any>
                        if (lead != null) {
                            lead["isPinned"] = isPinned
                            applyFilters()
                        }
                    }
            }, { lead -> showLeadDetailsDialog(lead) })
        binding.rvFollowUps.adapter = adapter

        binding.btnBack.setOnClickListener { finish() }

        binding.btnSideKickPage.setOnClickListener {
            showOnlySideKick = !showOnlySideKick
            binding.btnSideKickPage.text = if (showOnlySideKick) "💡 All Leads" else "💡 Side-Kick"
            applyFilters()
        }

        binding.btnFilterCategory.setOnClickListener {
            val categories = arrayOf("All", getString(R.string.category_recruit), getString(R.string.category_prospect), getString(R.string.category_client))
            AlertDialog.Builder(this)
                .setTitle("Filter by Category")
                .setItems(categories) { _, which ->
                    currentCategoryFilter = categories[which]
                    Toast.makeText(this, "Filtering by: $currentCategoryFilter", Toast.LENGTH_SHORT).show()
                    applyFilters()
                }.show()
        }

        binding.etSearchLeads.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                currentSearchQuery = s.toString()
                applyFilters()
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        binding.cardFollowUpProgress.setOnClickListener {
            showGoalDialog()
        }
        
        binding.cardFollowUpProgress.setOnLongClickListener {
            showAdjustCountDialog()
            true
        }

        loadFollowUps()
        loadLocalStats()
        listenToStats()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(passedIntent: Intent?) {
        val targetId = passedIntent?.getStringExtra("targetLeadId")
        val targetPhone = passedIntent?.getStringExtra("targetPhone")
        
        if (!targetId.isNullOrEmpty()) {
            db.collection("leads").document(targetId).get().addOnSuccessListener { doc ->
                if (doc.exists()) {
                    val leadData = doc.data?.toMutableMap()
                    if (leadData != null) {
                        leadData["id"] = doc.id
                        showLeadDetailsDialog(leadData)
                    }
                }
            }
            passedIntent.removeExtra("targetLeadId")
            passedIntent.removeExtra("targetPhone")
        } else if (!targetPhone.isNullOrEmpty()) {
            val userId = auth.currentUser?.uid ?: return
            db.collection("leads").whereEqualTo("ownerId", userId).whereEqualTo("phone", targetPhone).get().addOnSuccessListener { docs ->
                val doc = docs.documents.firstOrNull()
                if (doc != null) {
                    val leadData = doc.data?.toMutableMap()
                    if (leadData != null) {
                        leadData["id"] = doc.id
                        showLeadDetailsDialog(leadData)
                    }
                }
            }
            passedIntent.removeExtra("targetPhone")
        }
    }

    private fun loadFollowUps() {
        val userId = auth.currentUser?.uid ?: return
        db.collection("leads")
            .whereEqualTo("ownerId", userId)
            .orderBy("followUpDate", Query.Direction.ASCENDING)
            .addSnapshotListener { snapshots, e ->
                if (e != null) return@addSnapshotListener
                allLeads.clear()
                snapshots?.forEach { doc ->
                    val data = doc.data.toMutableMap()
                    data["id"] = doc.id
                    allLeads.add(data)
                }
                applyFilters()
            }
    }

    private fun applyFilters() {
        leads.clear()
        val filtered = allLeads.filter { lead ->
            val matchesSearch = currentSearchQuery.isEmpty() ||
                    (lead["name"] as? String)?.contains(currentSearchQuery, ignoreCase = true) == true
            val matchesCategory = currentCategoryFilter == "All" ||
                    (lead["category"] as? String)?.contains(currentCategoryFilter, ignoreCase = true) == true
            val matchesSideKick = !showOnlySideKick || lead["sideKickSuggestion"] != null

            matchesSearch && matchesCategory && matchesSideKick
        }
        val sortedFiltered = filtered.sortedByDescending { it["isPinned"] as? Boolean ?: false }
        leads.addAll(sortedFiltered)
        adapter.notifyDataSetChanged()
        updateUI()
    }

    private fun updateUI() {
        binding.tvEmptyFollowUps.visibility = if (leads.isEmpty()) View.VISIBLE else View.GONE
        binding.bulkActionBar.visibility = View.GONE
    }

    @SuppressLint("MissingInflatedId")
    private fun showLeadDetailsDialog(lead: Map<String, Any>) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_lead_details, null)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        val docId = lead["id"] as String
        val editName = dialogView.findViewById<EditText>(R.id.editDetailName)
        val editPhone = dialogView.findViewById<EditText>(R.id.editDetailPhone)
        val editEmail = dialogView.findViewById<EditText>(R.id.editDetailEmail)
        val editAddress = dialogView.findViewById<EditText>(R.id.editDetailAddress)
        val editCompany = dialogView.findViewById<EditText>(R.id.editDetailCompany)
        val editJobTitle = dialogView.findViewById<EditText>(R.id.editDetailJobTitle)
        
        val btnSmsDetail = dialogView.findViewById<View>(R.id.btnSmsDetail)
        val btnCallDetail = dialogView.findViewById<View>(R.id.btnCallDetail)
        val btnEmailDetail = dialogView.findViewById<View>(R.id.btnEmailDetail)
        val btnMapDetail = dialogView.findViewById<View>(R.id.btnMapDetail)

        val cbRecruit = dialogView.findViewById<CheckBox>(R.id.cbRecruitDetail) ?: dialogView.findViewById<CheckBox>(R.id.cbRecruit)
        val cbProspect = dialogView.findViewById<CheckBox>(R.id.cbProspectDetail) ?: dialogView.findViewById<CheckBox>(R.id.cbProspect)
        val cbClient = dialogView.findViewById<CheckBox>(R.id.cbClientDetail) ?: dialogView.findViewById<CheckBox>(R.id.cbClient)
        
        val cbInv = dialogView.findViewById<CheckBox>(R.id.cbInvestmentDetail)
        val cbLife = dialogView.findViewById<CheckBox>(R.id.cbLifeInsuranceDetail)

        val llClientOptions = dialogView.findViewById<LinearLayout>(R.id.clientSubCategoryLayoutDetail)

        val tvReminderValue = dialogView.findViewById<TextView>(R.id.tvReminderValue)
        val btnSetReminder = dialogView.findViewById<Button>(R.id.btnSetReminder)
        val btnEditReminderIcon = dialogView.findViewById<View>(R.id.btnEditReminderIcon)
        val btnDeleteReminderIcon = dialogView.findViewById<View>(R.id.btnDeleteReminderIcon)
        val btnAddCalendar = dialogView.findViewById<Button>(R.id.btnAddCalendar)

        val btnSetAppt = dialogView.findViewById<Button>(R.id.btnSetAppt)
        val tvApptValue = dialogView.findViewById<TextView>(R.id.tvApptValue)
        val btnEditApptIcon = dialogView.findViewById<View>(R.id.btnEditApptIcon)
        val btnDeleteApptIcon = dialogView.findViewById<View>(R.id.btnDeleteApptIcon)

        val headerReminders = dialogView.findViewById<View>(R.id.headerReminders)
        val containerReminders = dialogView.findViewById<View>(R.id.containerReminders)
        val tvToggleReminders = dialogView.findViewById<TextView>(R.id.tvToggleReminders)
        if (lead["followUpDate"] != null || lead["appointmentDate"] != null) {
            containerReminders?.visibility = View.VISIBLE
            tvToggleReminders?.text = "Hide ▲"
        }
        headerReminders?.setOnClickListener {
            if (containerReminders?.visibility == View.VISIBLE) {
                containerReminders.visibility = View.GONE
                tvToggleReminders?.text = "Show ▼"
            } else {
                containerReminders?.visibility = View.VISIBLE
                tvToggleReminders?.text = "Hide ▲"
            }
        }

        val etQuickNote = dialogView.findViewById<EditText>(R.id.etQuickNote)
        val btnSendNote = dialogView.findViewById<View>(R.id.btnSendNote)
        val btnRecordVoiceNote = dialogView.findViewById<View>(R.id.btnRecordVoiceNote)
        val notesContainer = dialogView.findViewById<LinearLayout>(R.id.notesContainer)
        val tvTogglePreviousNotes = dialogView.findViewById<TextView>(R.id.tvTogglePreviousNotes)

        // Notes Logic
        val initialNotes = parseNotes(lead["notes"] as? String ?: "")
        val originalNotesCount = initialNotes.size
        val notesList = initialNotes.toMutableList()
        var showAllNotes = false

        fun refreshNotesUI() {
            notesContainer?.removeAllViews()

            if (notesList.size > 1) {
                tvTogglePreviousNotes?.visibility = View.VISIBLE
                tvTogglePreviousNotes?.text = if (showAllNotes) "Hide Previous ▲" else "Show Previous ▼"
            } else {
                tvTogglePreviousNotes?.visibility = View.GONE
            }

            notesList.forEachIndexed { index, note ->
                if (!showAllNotes && index < notesList.size - 1) {
                    return@forEachIndexed
                }

                val view = LayoutInflater.from(this).inflate(R.layout.item_dated_note, notesContainer, false)
                view.findViewById<TextView>(R.id.tvNoteDate).text = note.date
                view.findViewById<TextView>(R.id.tvNoteContent).text = note.content

                view.findViewById<View>(R.id.btnEditNote)?.setOnClickListener {
                    val editDialogView = LayoutInflater.from(this).inflate(R.layout.dialog_edit_note, null)
                    val input = editDialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etEditNoteContent)
                    val tvDate = editDialogView.findViewById<TextView>(R.id.tvEditNoteDate)

                    tvDate?.text = note.date
                    input?.setText(note.content)

                    AlertDialog.Builder(this)
                        .setTitle("Edit Note")
                        .setView(editDialogView)
                        .setPositiveButton("Save") { _, _ ->
                            notesList[index] = note.copy(content = input?.text.toString().trim())
                            refreshNotesUI()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
                view.findViewById<View>(R.id.btnDeleteNote)?.setOnClickListener {
                    notesList.removeAt(index)
                    refreshNotesUI()
                }
                notesContainer?.addView(view)
            }
        }

        tvTogglePreviousNotes?.setOnClickListener {
            showAllNotes = !showAllNotes
            refreshNotesUI()
        }

        refreshNotesUI()
        
        val btnDefaultIntro = dialogView.findViewById<Button>(R.id.btnDefaultIntro)
        val etIntroText = dialogView.findViewById<EditText>(R.id.etIntroText)
        val btnManagePresets = dialogView.findViewById<View>(R.id.btnManagePresets)
        val btnSendIntroAction = dialogView.findViewById<Button>(R.id.btnSendIntroAction)
        val btnAIGenerate = dialogView.findViewById<Button>(R.id.btnAIGenerate)
        
        val headerSmsIntro = dialogView.findViewById<View>(R.id.headerSmsIntro)
        val containerSmsIntro = dialogView.findViewById<View>(R.id.containerSmsIntro)
        val tvToggleSms = dialogView.findViewById<TextView>(R.id.tvToggleSms)

        headerSmsIntro?.setOnClickListener {
            if (containerSmsIntro?.visibility == View.VISIBLE) {
                containerSmsIntro.visibility = View.GONE
                tvToggleSms?.text = "Show ▼"
            } else {
                containerSmsIntro?.visibility = View.VISIBLE
                tvToggleSms?.text = "Hide ▲"
            }
        }

        btnAIGenerate?.setOnClickListener {
            val currentName = editName?.text.toString()
            val currentNote = notesList.joinToString("\n") { it.content }
            
            btnAIGenerate.isEnabled = false
            btnAIGenerate.text = "Drafting..."
            
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val prompt = "You are an assistant for a Primerica agent. Draft a short, warm, and professional SMS message to $currentName. " +
                                 "Use the following notes for context. Do not mention the notes directly. " +
                                 "Keep it under 2 sentences and ready to send.\nNotes:\n$currentNote"
                    val response = GeminiApiClient.generativeModel.generateContent(prompt)
                    
                    withContext(Dispatchers.Main) {
                        etIntroText?.setText(response.text?.trim() ?: "Could not generate message.")
                        btnAIGenerate.isEnabled = true
                        btnAIGenerate.text = "✨ AI Draft"
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "AI Draft Error", e)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@FollowUpActivity, "AI Error: ${e.message}", Toast.LENGTH_LONG).show()
                        btnAIGenerate.isEnabled = true
                        btnAIGenerate.text = "✨ AI Draft"
                    }
                }
            }
        }

        val btnSave = dialogView.findViewById<Button>(R.id.btnSaveChanges)

        // Target Market UI
        val cbTmMarried = dialogView.findViewById<CheckBox>(R.id.cbTmMarried)
        val cbTmAge = dialogView.findViewById<CheckBox>(R.id.cbTmAge)
        val cbTmChildren = dialogView.findViewById<CheckBox>(R.id.cbTmChildren)
        val cbTmHomeowner = dialogView.findViewById<CheckBox>(R.id.cbTmHomeowner)
        val cbTmOccupation = dialogView.findViewById<CheckBox>(R.id.cbTmOccupation)
        val tvScore = dialogView.findViewById<TextView>(R.id.tvTargetMarketScore)
        val dial = dialogView.findViewById<com.google.android.material.progressindicator.CircularProgressIndicator>(R.id.dialTargetMarket)

        fun updateTargetMarketScore() {
            var score = 0
            if (cbTmMarried?.isChecked == true) score += 20
            if (cbTmAge?.isChecked == true) score += 20
            if (cbTmChildren?.isChecked == true) score += 20
            if (cbTmHomeowner?.isChecked == true) score += 20
            if (cbTmOccupation?.isChecked == true) score += 20
            tvScore?.text = "$score%"
            dial?.progress = score
        }

        listOf(cbTmMarried, cbTmAge, cbTmChildren, cbTmHomeowner, cbTmOccupation).forEach {
            it?.setOnCheckedChangeListener { _, _ -> updateTargetMarketScore() }
        }

        // Pre-fill data
        editName?.setText(lead["name"] as? String)
        editPhone?.setText(lead["phone"] as? String)
        editEmail?.setText(lead["email"] as? String)
        editAddress?.setText(lead["address"] as? String)
        editCompany?.setText(lead["company"] as? String)
        editJobTitle?.setText(lead["jobTitle"] as? String)

        val category = lead["category"] as? String ?: ""
        cbRecruit?.isChecked = category.contains(getString(R.string.category_recruit))
        cbProspect?.isChecked = category.contains(getString(R.string.category_prospect))
        cbClient?.isChecked = category.contains(getString(R.string.category_client))
        cbInv?.isChecked = category.contains("Investment")
        cbLife?.isChecked = category.contains("Life Insurance")
        llClientOptions?.visibility = if (cbClient?.isChecked == true) View.VISIBLE else View.GONE
        cbClient?.setOnCheckedChangeListener { _, isChecked ->
            llClientOptions?.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        val tmString = lead["targetMarket"] as? String ?: ""
        cbTmMarried?.isChecked = tmString.contains("Married")
        cbTmAge?.isChecked = tmString.contains("Age 25-55")
        cbTmChildren?.isChecked = tmString.contains("Children")
        cbTmHomeowner?.isChecked = tmString.contains("Homeowner")
        cbTmOccupation?.isChecked = tmString.contains("Occupation")
        updateTargetMarketScore()

        // Handle Side-Kick Suggestion Visibility
        val sideKickContainer = dialogView.findViewById<View>(R.id.sideKickSuggestionContainer)
        val tvSideKickTip = dialogView.findViewById<TextView>(R.id.tvSideKickTip)
        val suggestion = lead["sideKickSuggestion"] as? String
        if (suggestion != null) {
            sideKickContainer?.visibility = View.VISIBLE
            tvSideKickTip?.text = "💡 Side-Kick Tip: $suggestion"
        }

        dialogView.findViewById<View>(R.id.btnDismissSideKick)?.setOnClickListener {
            db.collection("leads").document(docId).update("sideKickSuggestion", FieldValue.delete())
            sideKickContainer?.visibility = View.GONE
        }

        // Collapsible sections
        val headerTM = dialogView.findViewById<View>(R.id.headerTargetMarket)
        val containerTM = dialogView.findViewById<View>(R.id.containerTargetMarketCheckboxes)
        val tvToggleTM = dialogView.findViewById<TextView>(R.id.tvToggleTargetMarket)
        headerTM?.setOnClickListener {
            if (containerTM?.visibility == View.VISIBLE) {
                containerTM.visibility = View.GONE
                tvToggleTM?.text = "Edit ▼"
            } else {
                containerTM?.visibility = View.VISIBLE
                tvToggleTM?.text = "Hide ▲"
            }
        }

        val headerExt = dialogView.findViewById<View>(R.id.headerExtendedDetails)
        val containerExt = dialogView.findViewById<View>(R.id.containerExtendedDetails)
        val tvToggleExt = dialogView.findViewById<TextView>(R.id.tvToggleExtended)
        headerExt?.setOnClickListener {
            if (containerExt?.visibility == View.VISIBLE) {
                containerExt.visibility = View.GONE
                tvToggleExt?.text = "Show ▼"
            } else {
                containerExt?.visibility = View.VISIBLE
                tvToggleExt?.text = "Hide ▲"
            }
        }

        // Contact Buttons
        btnSmsDetail?.setOnClickListener {
            val phone = editPhone?.text.toString()
            if (phone.isNotEmpty()) {
                incrementDailyStat("followup_count")
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("sms:$phone")))
            }
        }
        btnCallDetail?.setOnClickListener {
            val phone = editPhone?.text.toString()
            if (phone.isNotEmpty()) {
                incrementDailyStat("followup_count")
                startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")))
            }
        }
        btnEmailDetail?.setOnClickListener {
            val email = editEmail?.text.toString()
            if (email.isNotEmpty()) {
                incrementDailyStat("followup_count")
                val intent = Intent(Intent.ACTION_SENDTO).apply {
                    data = Uri.parse("mailto:")
                    putExtra(Intent.EXTRA_EMAIL, arrayOf(email))
                }
                startActivity(intent)
            }
        }
        btnMapDetail?.setOnClickListener {
            val addr = editAddress?.text.toString()
            if (addr.isNotEmpty()) startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(addr)}")))
        }

        btnRecordVoiceNote?.setOnClickListener {
            btnRecordVoiceNote.isEnabled = false
            onVoiceLogComplete = { btnRecordVoiceNote.isEnabled = true }
            val intent = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            intent.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            intent.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            intent.putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Speak your updates...")
            try {
                speechRecognizerLauncherDetails.launch(intent)
            } catch (e: Exception) {
                btnRecordVoiceNote.isEnabled = true
                onVoiceLogComplete = null
                Toast.makeText(this, "Speech recognition not available", Toast.LENGTH_SHORT).show()
            }
        }

        btnSendNote?.setOnClickListener {
            val text = etQuickNote?.text.toString().trim()
            if (text.isNotEmpty()) {
                val date = SimpleDateFormat("MMM dd, yyyy hh:mm a", Locale.getDefault()).format(Date())
                
                // Append to end for chat-style interface
                notesList.add(DatedNote(date, text))
                refreshNotesUI()
                
                etQuickNote?.text?.clear()
            }
        }

        var selectedFollowUpDate: Date? = (lead["followUpDate"] as? Timestamp)?.toDate()
        fun updateReminderUI() {
            if (selectedFollowUpDate != null) {
                val sdf = SimpleDateFormat("MMM dd, yyyy hh:mm a", Locale.getDefault())
                tvReminderValue?.text = sdf.format(selectedFollowUpDate!!)
                btnSetReminder?.text = getString(R.string.btn_change_reminder)
                btnEditReminderIcon?.visibility = View.VISIBLE
                btnDeleteReminderIcon?.visibility = View.VISIBLE
            } else {
                tvReminderValue?.text = getString(R.string.reminder_none)
                btnSetReminder?.text = getString(R.string.btn_set_reminder)
                btnEditReminderIcon?.visibility = View.GONE
                btnDeleteReminderIcon?.visibility = View.GONE
            }
        }
        updateReminderUI()

        val reminderAction = {
            val cal = Calendar.getInstance()
            selectedFollowUpDate?.let { cal.time = it }
            DatePickerDialog(this, { _, year, month, day ->
                TimePickerDialog(this, { _, hour, minute ->
                    val newCal = Calendar.getInstance()
                    newCal.set(year, month, day, hour, minute)
                    selectedFollowUpDate = newCal.time
                    updateReminderUI()
                }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), false).show()
            }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
        }

        btnSetReminder?.setOnClickListener { reminderAction() }
        btnEditReminderIcon?.setOnClickListener { reminderAction() }
        btnDeleteReminderIcon?.setOnClickListener {
            selectedFollowUpDate = null
            updateReminderUI()
        }

        var appointmentDate: Date? = (lead["appointmentDate"] as? Timestamp)?.toDate()
        var appointmentLocation: String? = lead["appointmentLocation"] as? String
        val prefs = getSharedPreferences("user_settings", Context.MODE_PRIVATE)
        var defaultOfficeAddress = prefs.getString("default_office_address", "") ?: ""

        fun updateAppointmentButtonUI() {
            if (appointmentDate != null) {
                val sdf = SimpleDateFormat("MMM dd, yyyy hh:mm a", Locale.getDefault())
                tvApptValue?.text = "${sdf.format(appointmentDate!!)}\n@ ${appointmentLocation ?: "No location"}"
                btnSetAppt?.text = "Change Appointment"
                btnEditApptIcon?.visibility = View.VISIBLE
                btnDeleteApptIcon?.visibility = View.VISIBLE
            } else {
                tvApptValue?.text = "No appointment scheduled"
                btnSetAppt?.text = "Set Appointment"
                btnEditApptIcon?.visibility = View.GONE
                btnDeleteApptIcon?.visibility = View.GONE
            }
        }
        updateAppointmentButtonUI()

        btnDeleteApptIcon?.setOnClickListener {
            appointmentDate = null
            appointmentLocation = null
            updateAppointmentButtonUI()
        }

        val apptClickAction = View.OnClickListener {
            val apptDialogView = LayoutInflater.from(this).inflate(R.layout.dialog_appointment, null)
            val apptDialog = AlertDialog.Builder(this).setView(apptDialogView).create()
            apptDialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

            val btnApptDate = apptDialogView.findViewById<Button>(R.id.btnApptDate)
            val btnApptTime = apptDialogView.findViewById<Button>(R.id.btnApptTime)
            val tvApptDateTime = apptDialogView.findViewById<TextView>(R.id.tvSelectedDateTime)
            val rbOffice = apptDialogView.findViewById<RadioButton>(R.id.rbOffice)
            val rbCustom = apptDialogView.findViewById<RadioButton>(R.id.rbCustom)
            val llOfficeAddressConfig = apptDialogView.findViewById<LinearLayout>(R.id.llOfficeAddressConfig)
            val tvOfficeAddress = apptDialogView.findViewById<TextView>(R.id.tvCurrentOffice)
            val btnEditOffice = apptDialogView.findViewById<View>(R.id.btnEditOffice)
            val tilCustomAddress = apptDialogView.findViewById<View>(R.id.tilCustomAddress)
            val etCustomAddress = apptDialogView.findViewById<EditText>(R.id.etCustomAddress)
            val btnSaveAppt = apptDialogView.findViewById<Button>(R.id.btnSaveAppt)
            val btnCancelAppt = apptDialogView.findViewById<Button>(R.id.btnCancelAppt)

            var tempCal: Calendar? = appointmentDate?.let { Calendar.getInstance().apply { time = it } }

            fun updateDateTimeUI() {
                if (tempCal != null) {
                    val sdf = SimpleDateFormat("EEE, MMM dd, yyyy @ hh:mm a", Locale.getDefault())
                    tvApptDateTime?.text = sdf.format(tempCal!!.time)
                } else {
                    tvApptDateTime?.text = "No date/time selected"
                }
            }
            updateDateTimeUI()

            fun updateOfficeUI() {
                if (defaultOfficeAddress.isEmpty()) {
                    tvOfficeAddress?.text = "No address set"
                    tvOfficeAddress?.setTextColor(Color.RED)
                } else {
                    tvOfficeAddress?.text = defaultOfficeAddress
                    tvOfficeAddress?.setTextColor(Color.BLACK)
                }
            }
            updateOfficeUI()

            rbOffice?.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    llOfficeAddressConfig?.visibility = View.VISIBLE
                    tilCustomAddress?.visibility = View.GONE
                }
            }
            rbCustom?.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    llOfficeAddressConfig?.visibility = View.GONE
                    tilCustomAddress?.visibility = View.VISIBLE
                }
            }
            
            if (appointmentLocation != null) {
                if (appointmentLocation == defaultOfficeAddress) {
                    rbOffice?.isChecked = true
                } else {
                    rbCustom?.isChecked = true
                    etCustomAddress?.setText(appointmentLocation)
                    llOfficeAddressConfig?.visibility = View.GONE
                    tilCustomAddress?.visibility = View.VISIBLE
                }
            } else {
                rbOffice?.isChecked = true
                llOfficeAddressConfig?.visibility = View.VISIBLE
                tilCustomAddress?.visibility = View.GONE
            }

            btnEditOffice?.setOnClickListener {
                val input = EditText(this@FollowUpActivity).apply {
                    setText(defaultOfficeAddress)
                    setPadding(48, 24, 48, 24)
                }
                
                AlertDialog.Builder(this@FollowUpActivity)
                    .setTitle("Default Office Address")
                    .setView(input)
                    .setPositiveButton("Save") { _, _ ->
                        defaultOfficeAddress = input.text.toString().trim()
                        updateOfficeUI()
                        prefs.edit { putString("default_office_address", defaultOfficeAddress) }
                        auth.currentUser?.uid?.let { userId ->
                            db.collection("user_settings").document(userId)
                                .set(mapOf("default_office_address" to defaultOfficeAddress), SetOptions.merge())
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }

            btnApptDate?.setOnClickListener {
                val cal = tempCal ?: Calendar.getInstance()
                DatePickerDialog(this@FollowUpActivity, { _, year, month, day ->
                    if (tempCal == null) tempCal = Calendar.getInstance()
                    tempCal!!.set(year, month, day)
                    updateDateTimeUI()
                }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
            }

            btnApptTime?.setOnClickListener {
                val cal = tempCal ?: Calendar.getInstance()
                TimePickerDialog(this@FollowUpActivity, { _, hour, minute ->
                    if (tempCal == null) tempCal = Calendar.getInstance()
                    tempCal!!.set(Calendar.HOUR_OF_DAY, hour)
                    tempCal!!.set(Calendar.MINUTE, minute)
                    updateDateTimeUI()
                }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), false).show()
            }

            btnCancelAppt?.setOnClickListener { apptDialog.dismiss() }

            btnSaveAppt?.setOnClickListener {
                if (tempCal == null) {
                    Toast.makeText(this@FollowUpActivity, "Please select Date & Time", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (rbOffice?.isChecked == true && defaultOfficeAddress.isEmpty()) {
                    Toast.makeText(this@FollowUpActivity, "Please set a default Office Address first", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                
                val finalLoc = if (rbOffice?.isChecked == true) defaultOfficeAddress else etCustomAddress?.text.toString().trim()
                if (finalLoc.isEmpty()) {
                    Toast.makeText(this@FollowUpActivity, "Please provide a location", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                appointmentDate = tempCal!!.time
                appointmentLocation = finalLoc
                
                updateAppointmentButtonUI()
                
                Toast.makeText(this@FollowUpActivity, "Appointment scheduled for saving", Toast.LENGTH_SHORT).show()
                apptDialog.dismiss()
            }

            apptDialog.show()
        }
        btnSetAppt?.setOnClickListener(apptClickAction)
        btnEditApptIcon?.setOnClickListener(apptClickAction)

        // Assign lambda AFTER all local dialog variables are declared so the closure
        // can correctly reference selectedFollowUpDate, appointmentDate, notesList, etc.
        applyVoiceLogToDialog = { json, spokenText ->
            if (json.has("phone") && !json.isNull("phone")) {
                editPhone?.setText(json.getString("phone"))
            }
            if (json.has("email") && !json.isNull("email")) {
                editEmail?.setText(json.getString("email"))
            }
            if (json.has("category") && !json.isNull("category")) {
                val cat = json.getString("category")
                cbRecruit?.isChecked = cat.contains("Recruit", true)
                cbProspect?.isChecked = cat.contains("Prospect", true)
                cbClient?.isChecked = cat.contains("Client", true)
            }
            if (json.has("targetMarket") && !json.isNull("targetMarket")) {
                val tmArray = json.getJSONArray("targetMarket")
                for (i in 0 until tmArray.length()) {
                    val trait = tmArray.getString(i)
                    if (trait.contains("Married", true)) cbTmMarried?.isChecked = true
                    if (trait.contains("Age", true)) cbTmAge?.isChecked = true
                    if (trait.contains("Children", true)) cbTmChildren?.isChecked = true
                    if (trait.contains("Homeowner", true)) cbTmHomeowner?.isChecked = true
                    if (trait.contains("Occupation", true)) cbTmOccupation?.isChecked = true
                }
                updateTargetMarketScore()
            }
            var extractedNotes = if (json.has("notes") && !json.isNull("notes")) json.getString("notes") else spokenText
            if (json.has("followUpDate") && !json.isNull("followUpDate")) {
                val dateStr = json.getString("followUpDate")
                try {
                    val parsedDate = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).parse(dateStr)
                    if (parsedDate != null) {
                        selectedFollowUpDate = parsedDate
                        updateReminderUI()
                        extractedNotes += "\n[Reminder Set: ${SimpleDateFormat("MMM dd h:mm a", Locale.getDefault()).format(parsedDate)}]"
                    }
                } catch (e: Exception) { Log.e(TAG, "Failed to parse followUpDate: $dateStr", e) }
            }
            if (json.has("appointmentDate") && !json.isNull("appointmentDate")) {
                val dateStr = json.getString("appointmentDate")
                try {
                    val parsedDate = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).parse(dateStr)
                    if (parsedDate != null) {
                        appointmentDate = parsedDate
                        extractedNotes += "\n[Appointment Set: ${SimpleDateFormat("MMM dd h:mm a", Locale.getDefault()).format(parsedDate)}]"
                        Toast.makeText(this@FollowUpActivity, "Appointment captured!", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) { Log.e(TAG, "Failed to parse appointmentDate: $dateStr", e) }
            }
            if (json.has("appointmentLocation") && !json.isNull("appointmentLocation")) {
                appointmentLocation = json.getString("appointmentLocation")
                extractedNotes += " at $appointmentLocation"
            }
            updateAppointmentButtonUI()
            notesList.add(DatedNote(SimpleDateFormat("MMM dd, yyyy hh:mm a", Locale.getDefault()).format(Date()), "[Voice Log]: $extractedNotes"))
            refreshNotesUI()
        }

        // Clear lambda on dismiss to prevent stale dialog view references from being
        // applied to a different lead if a delayed result arrives.
        dialog.setOnDismissListener {
            applyVoiceLogToDialog = null
            onVoiceLogComplete = null
        }

        btnDefaultIntro?.setOnClickListener {
            val firstName = editName?.text.toString().split(" ").firstOrNull() ?: ""
            val defaultMsg = getSharedPreferences("settings", Context.MODE_PRIVATE).getString("default_intro_sms", null)
                ?: "Hi $NAME_TOKEN, just following up! Looking forward to connecting."
            etIntroText?.setText(defaultMsg.replace(NAME_TOKEN, firstName))
        }

        btnManagePresets?.setOnClickListener { 
            if(etIntroText != null && editName != null) {
               showPresetsDialog(etIntroText, editName) 
            }
        }

        btnSendIntroAction?.setOnClickListener {
            val msg = etIntroText?.text.toString()
            val phone = editPhone?.text.toString()
            if (msg.isNotEmpty() && phone.isNotEmpty()) {
                incrementDailyStat("followup_count")
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("sms:$phone")).apply { putExtra("sms_body", msg) })
            }
        }

        btnSave?.setOnClickListener {
            // Capture any unsent text from the Quick Note input before saving
            val pendingNoteText = etQuickNote?.text.toString().trim()
            if (pendingNoteText.isNotEmpty()) {
                val date = SimpleDateFormat("MMM dd, yyyy hh:mm a", Locale.getDefault()).format(Date())
                notesList.add(DatedNote(date, pendingNoteText))
                etQuickNote?.text?.clear()
                refreshNotesUI()
            }

            val updatedCats = mutableListOf<String>()
            if (cbRecruit?.isChecked == true) updatedCats.add(getString(R.string.category_recruit))
            if (cbProspect?.isChecked == true) updatedCats.add(getString(R.string.category_prospect))
            if (cbClient?.isChecked == true) {
                updatedCats.add(getString(R.string.category_client))
                if (cbInv?.isChecked == true) updatedCats.add("Investment")
                if (cbLife?.isChecked == true) updatedCats.add("Life Insurance")
            }
            
            val updatedNotes = notesList.joinToString("\n\n") { "[${it.date}]: ${it.content}" }
            val tmList = mutableListOf<String>()
            if (cbTmMarried?.isChecked == true) tmList.add("Married")
            if (cbTmAge?.isChecked == true) tmList.add("Age 25-55")
            if (cbTmChildren?.isChecked == true) tmList.add("Children")
            if (cbTmHomeowner?.isChecked == true) tmList.add("Homeowner")
            if (cbTmOccupation?.isChecked == true) tmList.add("Occupation")

            val updates: Map<String, Any?> = hashMapOf(
                "name" to editName?.text.toString(),
                "phone" to editPhone?.text.toString(),
                "email" to editEmail?.text.toString(),
                "address" to editAddress?.text.toString(),
                "company" to editCompany?.text.toString(),
                "jobTitle" to editJobTitle?.text.toString(),
                "category" to updatedCats.joinToString(", "),
                "notes" to updatedNotes,
                "targetMarket" to tmList.joinToString(", "),
                "followUpDate" to selectedFollowUpDate?.let { Timestamp(it) },
                "appointmentDate" to appointmentDate?.let { Timestamp(it) },
                "appointmentLocation" to appointmentLocation
            )
            
            // Instantly update the local item in memory so the UI is fresh if reopened quickly
            if (lead is MutableMap<*, *>) {
                @Suppress("UNCHECKED_CAST")
                (lead as MutableMap<String, Any?>).putAll(updates)
            }

            db.collection("leads").document(docId).update(updates).addOnSuccessListener {
                if (notesList.size > originalNotesCount) {
                    incrementDailyStat("followup_count")
                }
                
                val phoneToNotify = editPhone?.text.toString().trim().ifEmpty { docId }
                
                if (selectedFollowUpDate != null) {
                    ReminderReceiver.scheduleReminder(this, phoneToNotify, editName?.text.toString(), selectedFollowUpDate!!.time)
                } else {
                    ReminderReceiver.cancelReminder(this, phoneToNotify)
                }
                
                if (appointmentDate != null) {
                    ReminderReceiver.scheduleReminder(this, "appt_$phoneToNotify", "Appointment: ${editName?.text}", appointmentDate!!.time)
                } else {
                    ReminderReceiver.cancelReminder(this, "appt_$phoneToNotify")
                }
                
                Toast.makeText(this, getString(R.string.msg_lead_updated), Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }

        btnAddCalendar?.setOnClickListener {
            val phone = editPhone?.text.toString()
            val name = editName?.text.toString()
            val notes = notesList.firstOrNull()?.content ?: ""
            val intent = Intent(Intent.ACTION_INSERT)
                .setData(android.provider.CalendarContract.Events.CONTENT_URI)
                .putExtra(android.provider.CalendarContract.Events.TITLE, getString(R.string.calendar_call_title, name))
                .putExtra(android.provider.CalendarContract.Events.DESCRIPTION, getString(R.string.calendar_description_format, phone, notes))
            selectedFollowUpDate?.let {
                intent.putExtra(android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME, it.time)
                intent.putExtra(android.provider.CalendarContract.EXTRA_EVENT_END_TIME, it.time + 30 * 60 * 1000)
            }
            startActivity(intent)
        }

        dialog.show()
    }

    private fun showPresetsDialog(target: EditText, nameSource: EditText) {
        val presets = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getStringSet(PREFS_KEY, emptySet())?.toList() ?: emptyList()
        if (presets.isEmpty()) {
            Toast.makeText(this, getString(R.string.msg_no_presets), Toast.LENGTH_SHORT).show()
            return
        }

        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, presets)
        AlertDialog.Builder(this)
            .setTitle("Select Preset")
            .setAdapter(adapter) { _, which ->
                val firstName = nameSource.text.toString().split(" ").firstOrNull() ?: ""
                target.setText(presets[which].replace(NAME_TOKEN, firstName))
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun loadLocalStats() {
        val prefs = getSharedPreferences("daily_stats_local", Context.MODE_PRIVATE)
        val today = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
        val lastDate = prefs.getString("last_date", "")
        if (lastDate != today) {
            prefs.edit {
                putString("last_date", today)
            }
        }
        updateDialUI()
    }

    private fun listenToStats() {
        val userId = auth.currentUser?.uid ?: return
        val dateStr = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
        statsListener = db.collection("user_settings").document(userId).collection("daily_stats").document(dateStr)
            .addSnapshotListener { snapshot, _ ->
                if (snapshot != null && snapshot.exists()) {
                    val prefs = getSharedPreferences("daily_stats_local", Context.MODE_PRIVATE)
                    prefs.edit {
                        putInt("${dateStr}_followup_count", (snapshot.getLong("followup_count") ?: 0).toInt())
                        putInt("${dateStr}_total_count", (snapshot.getLong("total_count") ?: 0).toInt())
                        val goal = snapshot.getLong("followup_goal")?.toInt()
                        if (goal != null) putInt("${dateStr}_followup_goal", goal)
                    }
                    updateDialUI()
                }
            }
    }

    private fun incrementDailyStat(field: String) {
        val userId = auth.currentUser?.uid ?: return
        val dateStr = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
        
        // Update local stats for immediate UI sync in MainActivity
        val statsPrefs = getSharedPreferences("daily_stats_local", Context.MODE_PRIVATE)
        val currentLocalCount = statsPrefs.getInt("${dateStr}_$field", 0)
        statsPrefs.edit { putInt("${dateStr}_$field", currentLocalCount + 1) }

        if (field == "followup_count") {
            updateDialUI()
        }

        db.collection("user_settings").document(userId)
            .collection("daily_stats").document(dateStr)
            .set(mapOf(field to FieldValue.increment(1)), SetOptions.merge())
    }

    private fun updateDialUI() {
        val prefs = getSharedPreferences("daily_stats_local", Context.MODE_PRIVATE)
        val dateStr = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
        val count = prefs.getInt("${dateStr}_followup_count", 0)
        val goal = prefs.getInt("${dateStr}_followup_goal", 5)

        binding.progressFollowUps.max = if (goal > 0) goal else 1
        binding.progressFollowUps.progress = count
        binding.tvFollowUpCountDisplay.text = count.toString()
        binding.tvFollowUpGoalDisplay.text = "Goal: $goal"
    }

    private fun showGoalDialog() {
        val prefs = getSharedPreferences("daily_stats_local", Context.MODE_PRIVATE)
        val dateStr = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
        val currentGoal = prefs.getInt("${dateStr}_followup_goal", 5)

        val input = EditText(this)
        input.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        input.setText(currentGoal.toString())

        AlertDialog.Builder(this)
            .setTitle("Daily Follow-up Goal")
            .setMessage("Set your target goal:")
            .setView(input)
            .setPositiveButton("Set") { _, _ ->
                val goal = input.text.toString().toIntOrNull() ?: currentGoal
                prefs.edit { putInt("${dateStr}_followup_goal", goal) }
                
                auth.currentUser?.uid?.let { userId ->
                    db.collection("user_settings").document(userId).set(mapOf("followup_goal" to goal), SetOptions.merge())
                    db.collection("user_settings").document(userId).collection("daily_stats").document(dateStr).set(mapOf("followup_goal" to goal), SetOptions.merge())
                }
                updateDialUI()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAdjustCountDialog() {
        val prefs = getSharedPreferences("daily_stats_local", Context.MODE_PRIVATE)
        val dateStr = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
        val currentCount = prefs.getInt("${dateStr}_followup_count", 0)

        val input = EditText(this)
        input.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        input.setText(currentCount.toString())

        AlertDialog.Builder(this)
            .setTitle("Manual Follow-Ups Adjustment")
            .setMessage("Manually adjust today's count:")
            .setView(input)
            .setPositiveButton("Update") { _, _ ->
                val newVal = input.text.toString().toIntOrNull() ?: currentCount
                prefs.edit { putInt("${dateStr}_followup_count", newVal) }
                
                auth.currentUser?.uid?.let { userId ->
                    db.collection("user_settings").document(userId).collection("daily_stats").document(dateStr).set(mapOf("followup_count" to newVal), SetOptions.merge())
                }
                updateDialUI()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun processVoiceLogListWithAI(leadId: String, spokenText: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val prompt = "You are an assistant for a Primerica agent. Extract structured updates from the following voice log. " +
                             "The lead ID is $leadId. " +
                             "Look for: phone, email, category (Recruit, Prospect, Client), targetMarket (list: Married, Age 25-55, Children, Homeowner, Occupation), " +
                             "followUpDate (yyyy-MM-dd HH:mm), appointmentDate (yyyy-MM-dd HH:mm), appointmentLocation, and notes. " +
                             "Return ONLY valid JSON.\nVoice Log: $spokenText"
                val response = GeminiApiClient.generativeModel.generateContent(prompt)
                val jsonStr = response.text?.replace("```json", "")?.replace("```", "")?.trim() ?: "{}"
                val json = org.json.JSONObject(jsonStr)

                withContext(Dispatchers.Main) {
                    val updates = mutableMapOf<String, Any?>()
                    if (json.has("phone") && !json.isNull("phone")) updates["phone"] = json.getString("phone")
                    if (json.has("email") && !json.isNull("email")) updates["email"] = json.getString("email")
                    if (json.has("category") && !json.isNull("category")) updates["category"] = json.getString("category")
                    
                    var newNotes = if (json.has("notes") && !json.isNull("notes")) json.getString("notes") else spokenText
                    
                    if (json.has("followUpDate") && !json.isNull("followUpDate")) {
                        val dateStr = json.getString("followUpDate")
                        try {
                            val parsedDate = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).parse(dateStr)
                            if (parsedDate != null) {
                                updates["followUpDate"] = Timestamp(parsedDate)
                                newNotes += "\n[Reminder Set: ${SimpleDateFormat("MMM dd h:mm a", Locale.getDefault()).format(parsedDate)}]"
                            }
                        } catch (e: Exception) {}
                    }
                    if (json.has("appointmentDate") && !json.isNull("appointmentDate")) {
                        val dateStr = json.getString("appointmentDate")
                        try {
                            val parsedDate = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).parse(dateStr)
                            if (parsedDate != null) {
                                updates["appointmentDate"] = Timestamp(parsedDate)
                                newNotes += "\n[Appointment Set: ${SimpleDateFormat("MMM dd h:mm a", Locale.getDefault()).format(parsedDate)}]"
                            }
                        } catch (e: Exception) {}
                    }
                    if (json.has("appointmentLocation") && !json.isNull("appointmentLocation")) {
                        updates["appointmentLocation"] = json.getString("appointmentLocation")
                    }

                    db.collection("leads").document(leadId).get().addOnSuccessListener { doc ->
                        val oldNotes = doc.getString("notes") ?: ""
                        val dateTag = SimpleDateFormat("MMM dd, yyyy hh:mm a", Locale.getDefault()).format(Date())
                        val combinedNotes = if (oldNotes.isEmpty()) "[$dateTag]: [Voice Log]: $newNotes" else "$oldNotes\n\n[$dateTag]: [Voice Log]: $newNotes"
                        updates["notes"] = combinedNotes
                        
                        db.collection("leads").document(leadId).update(updates).addOnSuccessListener {
                            Toast.makeText(this@FollowUpActivity, "Lead updated via Voice!", Toast.LENGTH_SHORT).show()
                            incrementDailyStat("followup_count")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Voice AI Error", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@FollowUpActivity, "AI Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun processVoiceLogDetailsWithAI(spokenText: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val prompt = "You are an assistant for a Primerica agent. Extract structured updates from the following voice log. " +
                             "Look for: phone, email, category (Recruit, Prospect, Client), targetMarket (list: Married, Age 25-55, Children, Homeowner, Occupation), " +
                             "followUpDate (yyyy-MM-dd HH:mm), appointmentDate (yyyy-MM-dd HH:mm), appointmentLocation, and notes. " +
                             "Return ONLY valid JSON.\nVoice Log: $spokenText"
                val response = GeminiApiClient.generativeModel.generateContent(prompt)
                val jsonStr = response.text?.replace("```json", "")?.replace("```", "")?.trim() ?: "{}"
                val json = org.json.JSONObject(jsonStr)

                withContext(Dispatchers.Main) {
                    applyVoiceLogToDialog?.invoke(json, spokenText)
                    onVoiceLogComplete?.invoke()
                    onVoiceLogComplete = null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Voice AI Error", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@FollowUpActivity, "AI Error: ${e.message}", Toast.LENGTH_SHORT).show()
                    onVoiceLogComplete?.invoke()
                    onVoiceLogComplete = null
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        statsListener?.remove()
    }

    private data class DatedNote(val date: String, val content: String)

    private fun parseNotes(raw: String): List<DatedNote> {
        if (raw.isEmpty()) return emptyList()
        val list = mutableListOf<DatedNote>()
        // Split by double newline which separates entries
        val blocks = raw.split("\n\n")
        for (block in blocks) {
            if (block.isBlank()) continue
            // Format is "[MMM dd, yyyy hh:mm a]: Content"
            val endBracket = block.indexOf("]: ")
            if (endBracket != -1) {
                val date = block.substring(1, endBracket)
                val content = block.substring(endBracket + 3)
                list.add(DatedNote(date, content))
            } else {
                list.add(DatedNote("Unknown Date", block))
            }
        }
        return list
    }
}
