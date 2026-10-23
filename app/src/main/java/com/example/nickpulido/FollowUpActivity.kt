package com.nickpulido.rcrm

import android.Manifest
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
import androidx.activity.result.contract.ActivityResultContracts
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
    
    private var pendingCalendarAction: ((Boolean) -> Unit)? = null

    private val calendarPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result[Manifest.permission.READ_CALENDAR] == true &&
            result[Manifest.permission.WRITE_CALENDAR] == true
        val action = pendingCalendarAction
        pendingCalendarAction = null
        action?.invoke(granted)
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
        val uriLeadId = GoogleCalendarManager.leadIdFromUri(passedIntent?.data)
        val targetId = uriLeadId
            ?: passedIntent?.getStringExtra("targetLeadId")
            ?: passedIntent?.getStringExtra("OPEN_LEAD_ID")
        val targetPhone = passedIntent?.getStringExtra("targetPhone")
        if (uriLeadId != null) {
            passedIntent?.data = null
        }
        
        if (!targetId.isNullOrEmpty()) {
            db.collection("leads").document(targetId).get().addOnSuccessListener { doc ->
                if (doc.exists()) {
                    val leadData = doc.data?.toMutableMap()
                    if (leadData != null) {
                        leadData["id"] = doc.id
                        showLeadDetailsDialog(leadData, finishOnDismiss = true)
                    } else {
                        finish()
                    }
                } else {
                    finish()
                }
            }.addOnFailureListener {
                finish()
            }
            passedIntent?.removeExtra("targetLeadId")
            passedIntent?.removeExtra("OPEN_LEAD_ID")
            passedIntent?.removeExtra("targetPhone")
        } else if (!targetPhone.isNullOrEmpty()) {
            val userId = auth.currentUser?.uid ?: return
            db.collection("leads").whereEqualTo("ownerId", userId).whereEqualTo("phone", targetPhone).get().addOnSuccessListener { docs ->
                val doc = docs.documents.firstOrNull()
                if (doc != null) {
                    val leadData = doc.data?.toMutableMap()
                    if (leadData != null) {
                        leadData["id"] = doc.id
                        showLeadDetailsDialog(leadData, finishOnDismiss = true)
                    } else {
                        finish()
                    }
                } else {
                    finish()
                }
            }.addOnFailureListener {
                finish()
            }
            passedIntent?.removeExtra("targetPhone")
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
    private fun showLeadDetailsDialog(lead: Map<String, Any>, finishOnDismiss: Boolean = false) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_lead_details, null)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

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

        val tabActivity = dialogView.findViewById<TextView>(R.id.tabActivity)
        val tabProfile = dialogView.findViewById<TextView>(R.id.tabProfile)
        val tabProducts = dialogView.findViewById<TextView>(R.id.tabProducts)
        
        val sectionActivity = dialogView.findViewById<View>(R.id.sectionActivity)
        val sectionProfile = dialogView.findViewById<View>(R.id.sectionProfile)
        val sectionProducts = dialogView.findViewById<View>(R.id.sectionProducts)
        
        fun styleTabButton(tabView: TextView?, isSelected: Boolean) {
            if (tabView == null) return
            if (isSelected) {
                val selectedBg = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.parseColor("#332EE7FF"))
                    cornerRadius = 20f * resources.displayMetrics.density
                    setStroke((1.25f * resources.displayMetrics.density).toInt(), Color.parseColor("#2EE7FF"))
                }
                tabView.background = selectedBg
                tabView.setTextColor(Color.parseColor("#2EE7FF"))
                tabView.setTypeface(null, android.graphics.Typeface.BOLD)
            } else {
                tabView.background = null
                tabView.setTextColor(Color.parseColor("#9AA6BD"))
                tabView.setTypeface(null, android.graphics.Typeface.NORMAL)
            }
        }

        fun updateTabColors(selected: TextView, other1: TextView, other2: TextView, showSection: View, hide1: View, hide2: View) {
            styleTabButton(selected, true)
            styleTabButton(other1, false)
            styleTabButton(other2, false)
            
            showSection.visibility = View.VISIBLE
            hide1.visibility = View.GONE
            hide2.visibility = View.GONE
        }
        
        styleTabButton(tabActivity, true)
        styleTabButton(tabProfile, false)
        styleTabButton(tabProducts, false)

        tabActivity?.setOnClickListener { updateTabColors(tabActivity, tabProfile!!, tabProducts!!, sectionActivity!!, sectionProfile!!, sectionProducts!!) }
        tabProfile?.setOnClickListener { updateTabColors(tabProfile!!, tabActivity!!, tabProducts!!, sectionProfile!!, sectionActivity!!, sectionProducts!!) }
        tabProducts?.setOnClickListener { updateTabColors(tabProducts!!, tabActivity!!, tabProfile!!, sectionProducts!!, sectionActivity!!, sectionProfile!!) }

        val tvInsSummaryBadge = dialogView.findViewById<TextView>(R.id.tvInsSummaryBadge)
        val btnToggleInsEdit = dialogView.findViewById<Button>(R.id.btnToggleInsEdit)
        val containerInsForm = dialogView.findViewById<View>(R.id.containerInsForm)
        
        val tvInvSummaryBadge = dialogView.findViewById<TextView>(R.id.tvInvSummaryBadge)
        val btnToggleInvEdit = dialogView.findViewById<Button>(R.id.btnToggleInvEdit)
        val containerInvForm = dialogView.findViewById<View>(R.id.containerInvForm)

        val llClientOptions = dialogView.findViewById<LinearLayout>(R.id.clientSubCategoryLayoutDetail)
        val cardLifeInsuranceDetails = dialogView.findViewById<View>(R.id.cardLifeInsuranceDetails)
        val spinnerInsPolicyType = dialogView.findViewById<Spinner>(R.id.spinnerInsPolicyType)
        val spinnerInsStatus = dialogView.findViewById<Spinner>(R.id.spinnerInsStatus)
        val editInsCarrier = dialogView.findViewById<EditText>(R.id.editInsCarrier)
        val editInsPolicyNumber = dialogView.findViewById<EditText>(R.id.editInsPolicyNumber)
        val editInsFaceAmount = dialogView.findViewById<EditText>(R.id.editInsFaceAmount)
        val editInsMonthlyPremium = dialogView.findViewById<EditText>(R.id.editInsMonthlyPremium)
        val editInsBeneficiaries = dialogView.findViewById<EditText>(R.id.editInsBeneficiaries)
        val tvInsRenewalDate = dialogView.findViewById<TextView>(R.id.tvInsRenewalDate)
        val btnSetInsRenewalDate = dialogView.findViewById<Button>(R.id.btnSetInsRenewalDate)

        val cardInvestmentDetails = dialogView.findViewById<View>(R.id.cardInvestmentDetails)
        val spinnerInvAccountType = dialogView.findViewById<Spinner>(R.id.spinnerInvAccountType)
        val spinnerInvStatus = dialogView.findViewById<Spinner>(R.id.spinnerInvStatus)
        val editInvPlatform = dialogView.findViewById<EditText>(R.id.editInvPlatform)
        val editInvLumpSum = dialogView.findViewById<EditText>(R.id.editInvLumpSum)
        val editInvMonthlyContribution = dialogView.findViewById<EditText>(R.id.editInvMonthlyContribution)

        fun updateProductCardsVisibility() {
            val isClient = cbClient?.isChecked == true
            llClientOptions?.visibility = if (isClient) View.VISIBLE else View.GONE
            cardLifeInsuranceDetails?.visibility = View.VISIBLE
            cardInvestmentDetails?.visibility = View.VISIBLE
        }

        fun updateInsSummary() {
            val isChecked = cbLife?.isChecked == true
            if (!isChecked) {
                tvInsSummaryBadge?.text = "Not Enrolled • Tap + Add"
                tvInsSummaryBadge?.setTextColor(Color.parseColor("#8E99B0"))
                btnToggleInsEdit?.text = "+ Add"
            } else {
                val face = editInsFaceAmount?.text?.toString()?.trim() ?: ""
                val prem = editInsMonthlyPremium?.text?.toString()?.trim() ?: ""
                val status = spinnerInsStatus?.selectedItem?.toString() ?: ""
                val parts = listOf(
                    if (face.isNotEmpty()) "$$face" else "",
                    if (prem.isNotEmpty()) "$$prem/mo" else "",
                    if (status != "Select Status..." && status.isNotEmpty()) status else ""
                ).filter { it.isNotEmpty() }
                
                if (parts.isEmpty()) {
                    tvInsSummaryBadge?.text = "Enrolled • Tap Edit"
                    tvInsSummaryBadge?.setTextColor(Color.parseColor("#3DFF9A"))
                } else {
                    tvInsSummaryBadge?.text = parts.joinToString(" • ")
                    tvInsSummaryBadge?.setTextColor(Color.parseColor("#3DFF9A"))
                }

                if (containerInsForm?.visibility == View.VISIBLE) {
                    btnToggleInsEdit?.text = "Done ▲"
                } else {
                    btnToggleInsEdit?.text = "Edit ▼"
                }
            }
        }

        fun updateInvSummary() {
            val isChecked = cbInv?.isChecked == true
            if (!isChecked) {
                tvInvSummaryBadge?.text = "Not Enrolled • Tap + Add"
                tvInvSummaryBadge?.setTextColor(Color.parseColor("#8E99B0"))
                btnToggleInvEdit?.text = "+ Add"
            } else {
                val acct = spinnerInvAccountType?.selectedItem?.toString() ?: ""
                val pac = editInvMonthlyContribution?.text?.toString()?.trim() ?: ""
                val parts = listOf(
                    if (acct != "Select Account Type..." && acct.isNotEmpty()) acct else "",
                    if (pac.isNotEmpty()) "$$pac/mo" else ""
                ).filter { it.isNotEmpty() }
                
                if (parts.isEmpty()) {
                    tvInvSummaryBadge?.text = "Enrolled • Tap Edit"
                    tvInvSummaryBadge?.setTextColor(Color.parseColor("#3DFF9A"))
                } else {
                    tvInvSummaryBadge?.text = parts.joinToString(" • ")
                    tvInvSummaryBadge?.setTextColor(Color.parseColor("#3DFF9A"))
                }

                if (containerInvForm?.visibility == View.VISIBLE) {
                    btnToggleInvEdit?.text = "Done ▲"
                } else {
                    btnToggleInvEdit?.text = "Edit ▼"
                }
            }
        }

        fun toggleInsForm() {
            if (containerInsForm?.visibility == View.VISIBLE) {
                containerInsForm.visibility = View.GONE
            } else {
                cbClient?.isChecked = true
                cbLife?.isChecked = true
                containerInsForm?.visibility = View.VISIBLE
            }
            updateProductCardsVisibility()
            updateInsSummary()
        }

        btnToggleInsEdit?.setOnClickListener { toggleInsForm() }
        (btnToggleInsEdit?.parent as? View)?.setOnClickListener { toggleInsForm() }

        fun toggleInvForm() {
            if (containerInvForm?.visibility == View.VISIBLE) {
                containerInvForm.visibility = View.GONE
            } else {
                cbClient?.isChecked = true
                cbInv?.isChecked = true
                containerInvForm?.visibility = View.VISIBLE
            }
            updateProductCardsVisibility()
            updateInvSummary()
        }

        btnToggleInvEdit?.setOnClickListener { toggleInvForm() }
        (btnToggleInvEdit?.parent as? View)?.setOnClickListener { toggleInvForm() }

        cbClient?.setOnCheckedChangeListener { _, _ -> updateProductCardsVisibility() }
        cbLife?.setOnCheckedChangeListener { _, isChecked -> 
            if (isChecked) {
                cbClient?.isChecked = true
                containerInsForm?.visibility = View.VISIBLE
            } else {
                containerInsForm?.visibility = View.GONE
            }
            updateProductCardsVisibility() 
            updateInsSummary()
        }
        cbInv?.setOnCheckedChangeListener { _, isChecked -> 
            if (isChecked) {
                cbClient?.isChecked = true
                containerInvForm?.visibility = View.VISIBLE
            } else {
                containerInvForm?.visibility = View.GONE
            }
            updateProductCardsVisibility() 
            updateInvSummary()
        }

        val insWatcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { updateInsSummary() }
            override fun afterTextChanged(s: android.text.Editable?) {}
        }
        editInsFaceAmount?.addTextChangedListener(insWatcher)
        editInsMonthlyPremium?.addTextChangedListener(insWatcher)
        spinnerInsStatus?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) { updateInsSummary() }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        val invWatcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { updateInvSummary() }
            override fun afterTextChanged(s: android.text.Editable?) {}
        }
        editInvMonthlyContribution?.addTextChangedListener(invWatcher)
        spinnerInvAccountType?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) { updateInvSummary() }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        var insRenewalDateStr = lead["insRenewalDate"] as? String
        btnSetInsRenewalDate?.setOnClickListener {
            val calendar = Calendar.getInstance()
            DatePickerDialog(this, { _, y, m, d ->
                insRenewalDateStr = String.format("%04d-%02d-%02d", y, m + 1, d)
                tvInsRenewalDate?.text = "Annual Review / Issue Date: $insRenewalDateStr"
            }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH)).show()
        }

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
                        .create().apply {
                            window?.setBackgroundDrawableResource(android.R.color.transparent)
                            show()
                        }
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
        updateProductCardsVisibility()

        // Pre-fill Insurance Fields
        editInsCarrier?.setText(lead["insCarrier"] as? String)
        editInsPolicyNumber?.setText(lead["insPolicyNumber"] as? String)
        editInsFaceAmount?.setText(lead["insFaceAmount"] as? String)
        editInsMonthlyPremium?.setText(lead["insMonthlyPremium"] as? String)
        editInsBeneficiaries?.setText(lead["insBeneficiaries"] as? String)
        if (lead["insRenewalDate"] != null) {
            tvInsRenewalDate?.text = "Annual Review / Issue Date: ${lead["insRenewalDate"]}"
        }

        val insPolicyTypes = resources.getStringArray(R.array.ins_policy_types)
        val insStatusTypes = resources.getStringArray(R.array.ins_underwriting_status)
        val typeIdx = insPolicyTypes.indexOf(lead["insPolicyType"] as? String)
        if (typeIdx >= 0) spinnerInsPolicyType?.setSelection(typeIdx)
        val statusIdx = insStatusTypes.indexOf(lead["insUnderwritingStatus"] as? String)
        if (statusIdx >= 0) spinnerInsStatus?.setSelection(statusIdx)

        // Pre-fill Investment Fields
        editInvPlatform?.setText(lead["invPlatform"] as? String)
        editInvLumpSum?.setText(lead["invLumpSumRollover"] as? String)
        editInvMonthlyContribution?.setText(lead["invMonthlyContribution"] as? String)
        
        val invAcctTypes = resources.getStringArray(R.array.inv_account_types)
        val invStatusTypes = resources.getStringArray(R.array.inv_account_status)
        val invAcctIdx = invAcctTypes.indexOf(lead["invAccountType"] as? String)
        if (invAcctIdx >= 0) spinnerInvAccountType?.setSelection(invAcctIdx)
        val invStatusIdx = invStatusTypes.indexOf(lead["invAccountStatus"] as? String)
        if (invStatusIdx >= 0) spinnerInvStatus?.setSelection(invStatusIdx)

        val tmString = lead["targetMarket"] as? String ?: ""
        cbTmMarried?.isChecked = tmString.contains("Married")
        cbTmAge?.isChecked = tmString.contains("Age 25-55")
        cbTmChildren?.isChecked = tmString.contains("Children")
        cbTmHomeowner?.isChecked = tmString.contains("Homeowner")
        cbTmOccupation?.isChecked = tmString.contains("Occupation")
        updateTargetMarketScore()
        
        updateInsSummary()
        updateInvSummary()

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
            val pickerTheme = android.R.style.Theme_DeviceDefault_Dialog_Alert
            DatePickerDialog(this, pickerTheme, { _, year, month, day ->
                TimePickerDialog(this, pickerTheme, { _, hour, minute ->
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
                    tvOfficeAddress?.setTextColor(Color.parseColor("#FF3B8D"))
                } else {
                    tvOfficeAddress?.text = defaultOfficeAddress
                    tvOfficeAddress?.setTextColor(Color.parseColor("#F4F7FF"))
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
                DatePickerDialog(this@FollowUpActivity, android.R.style.Theme_DeviceDefault_Dialog_Alert, { _, year, month, day ->
                    if (tempCal == null) tempCal = Calendar.getInstance()
                    tempCal!!.set(year, month, day)
                    updateDateTimeUI()
                }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
            }

            btnApptTime?.setOnClickListener {
                val cal = tempCal ?: Calendar.getInstance()
                TimePickerDialog(this@FollowUpActivity, android.R.style.Theme_DeviceDefault_Dialog_Alert, { _, hour, minute ->
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
                if (cat.contains("Life Insurance", true)) cbLife?.isChecked = true
                if (cat.contains("Investment", true)) cbInv?.isChecked = true
                updateProductCardsVisibility()
            }
            if (json.has("insCarrier") && !json.isNull("insCarrier")) {
                cbClient?.isChecked = true; cbLife?.isChecked = true; updateProductCardsVisibility()
                editInsCarrier?.setText(json.getString("insCarrier"))
            }
            if (json.has("insPolicyType") && !json.isNull("insPolicyType")) {
                cbClient?.isChecked = true; cbLife?.isChecked = true; updateProductCardsVisibility()
                val insPolicyTypes = resources.getStringArray(R.array.ins_policy_types)
                val typeIdx = insPolicyTypes.indexOf(json.getString("insPolicyType"))
                if (typeIdx >= 0) spinnerInsPolicyType?.setSelection(typeIdx)
            }
            if (json.has("insPolicyNumber") && !json.isNull("insPolicyNumber")) {
                cbClient?.isChecked = true; cbLife?.isChecked = true; updateProductCardsVisibility()
                editInsPolicyNumber?.setText(json.getString("insPolicyNumber"))
            }
            if (json.has("insFaceAmount") && !json.isNull("insFaceAmount")) {
                cbClient?.isChecked = true; cbLife?.isChecked = true; updateProductCardsVisibility()
                editInsFaceAmount?.setText(json.getString("insFaceAmount"))
            }
            if (json.has("insMonthlyPremium") && !json.isNull("insMonthlyPremium")) {
                cbClient?.isChecked = true; cbLife?.isChecked = true; updateProductCardsVisibility()
                editInsMonthlyPremium?.setText(json.getString("insMonthlyPremium"))
            }
            if (json.has("insUnderwritingStatus") && !json.isNull("insUnderwritingStatus")) {
                cbClient?.isChecked = true; cbLife?.isChecked = true; updateProductCardsVisibility()
                val insStatusTypes = resources.getStringArray(R.array.ins_underwriting_status)
                val statusIdx = insStatusTypes.indexOf(json.getString("insUnderwritingStatus"))
                if (statusIdx >= 0) spinnerInsStatus?.setSelection(statusIdx)
            }
            if (json.has("insBeneficiaries") && !json.isNull("insBeneficiaries")) {
                cbClient?.isChecked = true; cbLife?.isChecked = true; updateProductCardsVisibility()
                editInsBeneficiaries?.setText(json.getString("insBeneficiaries"))
            }
            
            if (json.has("invAccountType") && !json.isNull("invAccountType")) {
                cbClient?.isChecked = true; cbInv?.isChecked = true; updateProductCardsVisibility()
                val invAcctTypes = resources.getStringArray(R.array.inv_account_types)
                val invAcctIdx = invAcctTypes.indexOf(json.getString("invAccountType"))
                if (invAcctIdx >= 0) spinnerInvAccountType?.setSelection(invAcctIdx)
            }
            if (json.has("invPlatform") && !json.isNull("invPlatform")) {
                cbClient?.isChecked = true; cbInv?.isChecked = true; updateProductCardsVisibility()
                editInvPlatform?.setText(json.getString("invPlatform"))
            }
            if (json.has("invLumpSumRollover") && !json.isNull("invLumpSumRollover")) {
                cbClient?.isChecked = true; cbInv?.isChecked = true; updateProductCardsVisibility()
                editInvLumpSum?.setText(json.getString("invLumpSumRollover"))
            }
            if (json.has("invMonthlyContribution") && !json.isNull("invMonthlyContribution")) {
                cbClient?.isChecked = true; cbInv?.isChecked = true; updateProductCardsVisibility()
                editInvMonthlyContribution?.setText(json.getString("invMonthlyContribution"))
            }
            if (json.has("invAccountStatus") && !json.isNull("invAccountStatus")) {
                cbClient?.isChecked = true; cbInv?.isChecked = true; updateProductCardsVisibility()
                val invStatusTypes = resources.getStringArray(R.array.inv_account_status)
                val invStatusIdx = invStatusTypes.indexOf(json.getString("invAccountStatus"))
                if (invStatusIdx >= 0) spinnerInvStatus?.setSelection(invStatusIdx)
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
            if (finishOnDismiss) {
                finish()
            }
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
                "appointmentLocation" to appointmentLocation,
                "insCarrier" to editInsCarrier?.text.toString(),
                "insPolicyType" to spinnerInsPolicyType?.selectedItem?.toString()?.takeIf { it != "Select Policy Type..." },
                "insPolicyNumber" to editInsPolicyNumber?.text.toString(),
                "insFaceAmount" to editInsFaceAmount?.text.toString(),
                "insMonthlyPremium" to editInsMonthlyPremium?.text.toString(),
                "insUnderwritingStatus" to spinnerInsStatus?.selectedItem?.toString()?.takeIf { it != "Select Status..." },
                "insBeneficiaries" to editInsBeneficiaries?.text.toString(),
                "insRenewalDate" to insRenewalDateStr,
                "invAccountType" to spinnerInvAccountType?.selectedItem?.toString()?.takeIf { it != "Select Account Type..." },
                "invPlatform" to editInvPlatform?.text.toString(),
                "invLumpSumRollover" to editInvLumpSum?.text.toString(),
                "invMonthlyContribution" to editInvMonthlyContribution?.text.toString(),
                "invAccountStatus" to spinnerInvStatus?.selectedItem?.toString()?.takeIf { it != "Select Status..." }
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

                syncAppointmentAndStore(
                    leadId = docId,
                    leadName = editName?.text?.toString().orEmpty(),
                    phone = editPhone?.text?.toString(),
                    startMillis = appointmentDate?.time,
                    location = appointmentLocation,
                    existingEventId = GoogleCalendarManager.readStoredEventId(lead["googleCalendarEventId"]),
                    leadMap = lead
                )
                
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
        dialog.window?.setDimAmount(0.75f)
        val displayMetrics = resources.displayMetrics
        val width = (displayMetrics.widthPixels * 0.95).toInt()
        val height = (displayMetrics.heightPixels * 0.88).toInt()
        dialog.window?.setLayout(width, height)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
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
                             "Look for: phone, email, category (Recruit, Prospect, Client, Life Insurance, Investment), targetMarket (list: Married, Age 25-55, Children, Homeowner, Occupation), " +
                             "followUpDate (yyyy-MM-dd HH:mm), appointmentDate (yyyy-MM-dd HH:mm), appointmentLocation, and notes. " +
                             "Also extract optional insurance and investment keys: insCarrier, insPolicyType, insPolicyNumber, insFaceAmount, insMonthlyPremium, insUnderwritingStatus, insBeneficiaries, invAccountType, invPlatform, invLumpSumRollover, invMonthlyContribution, invAccountStatus. " +
                             "Return ONLY valid JSON.\nVoice Log: $spokenText"
                val response = GeminiApiClient.generativeModel.generateContent(prompt)
                val jsonStr = response.text?.replace("```json", "")?.replace("```", "")?.trim() ?: "{}"
                val json = org.json.JSONObject(jsonStr)

                withContext(Dispatchers.Main) {
                    val updates = mutableMapOf<String, Any?>()
                    if (json.has("phone") && !json.isNull("phone")) updates["phone"] = json.getString("phone")
                    if (json.has("email") && !json.isNull("email")) updates["email"] = json.getString("email")
                    if (json.has("category") && !json.isNull("category")) updates["category"] = json.getString("category")
                    
                    val optKeys = listOf("insCarrier", "insPolicyType", "insPolicyNumber", "insFaceAmount", "insMonthlyPremium", "insUnderwritingStatus", "insBeneficiaries", "invAccountType", "invPlatform", "invLumpSumRollover", "invMonthlyContribution", "invAccountStatus")
                    optKeys.forEach { k -> if (json.has(k) && !json.isNull(k)) updates[k] = json.getString(k) }
                    
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
                            val spokenAppointment = updates["appointmentDate"] as? Timestamp
                            if (spokenAppointment != null) {
                                syncAppointmentAndStore(
                                    leadId = leadId,
                                    leadName = doc.getString("name").orEmpty(),
                                    phone = (updates["phone"] as? String) ?: doc.getString("phone"),
                                    startMillis = spokenAppointment.toDate().time,
                                    location = (updates["appointmentLocation"] as? String) ?: doc.getString("appointmentLocation"),
                                    existingEventId = GoogleCalendarManager.readStoredEventId(doc.get("googleCalendarEventId")),
                                    leadMap = null
                                )
                            }
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
                             "Look for: phone, email, category (Recruit, Prospect, Client, Life Insurance, Investment), targetMarket (list: Married, Age 25-55, Children, Homeowner, Occupation), " +
                             "followUpDate (yyyy-MM-dd HH:mm), appointmentDate (yyyy-MM-dd HH:mm), appointmentLocation, and notes. " +
                             "Also extract optional insurance and investment keys: insCarrier, insPolicyType, insPolicyNumber, insFaceAmount, insMonthlyPremium, insUnderwritingStatus, insBeneficiaries, invAccountType, invPlatform, invLumpSumRollover, invMonthlyContribution, invAccountStatus. " +
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

    private fun syncAppointmentAndStore(
        leadId: String,
        leadName: String,
        phone: String?,
        startMillis: Long?,
        location: String?,
        existingEventId: Long?,
        leadMap: Map<String, Any>?
    ) {
        if (leadId.isBlank()) return
        if ((startMillis == null || startMillis <= 0L) && existingEventId == null) return
        val runSync = {
            GoogleCalendarManager.syncCrmAppointmentAsync(
                context = this,
                leadId = leadId,
                leadName = leadName.ifBlank { "Lead" },
                phone = phone,
                startMillis = startMillis,
                location = location,
                existingEventId = existingEventId
            ) { result ->
                when (result) {
                    is GoogleCalendarManager.AppointmentSyncResult.Linked -> {
                        db.collection("leads").document(leadId)
                            .update("googleCalendarEventId", result.eventId)
                        (leadMap as? MutableMap<String, Any?>)?.put("googleCalendarEventId", result.eventId)
                    }
                    GoogleCalendarManager.AppointmentSyncResult.Cleared -> {
                        db.collection("leads").document(leadId)
                            .update("googleCalendarEventId", FieldValue.delete())
                        (leadMap as? MutableMap<String, Any?>)?.remove("googleCalendarEventId")
                    }
                    GoogleCalendarManager.AppointmentSyncResult.Skipped -> Unit
                }
            }
        }
        if (GoogleCalendarManager.hasCalendarPermissions(this)) {
            runSync()
        } else {
            pendingCalendarAction = { granted ->
                if (granted) {
                    runSync()
                } else if (startMillis != null) {
                    Toast.makeText(
                        this,
                        "Appointment saved. Allow calendar access to sync with Google Calendar.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            calendarPermissionLauncher.launch(
                arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
            )
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
