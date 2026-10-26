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
import androidx.core.content.ContextCompat
import com.nickpulido.rcrm.domain.ActionStatus
import com.nickpulido.rcrm.domain.ActionType
import com.nickpulido.rcrm.domain.ActivityRecord
import com.nickpulido.rcrm.domain.ContactActivityTimeline
import com.nickpulido.rcrm.domain.FollowUpRepository
import com.nickpulido.rcrm.domain.NextAction
import com.nickpulido.rcrm.domain.OutcomeSavePlanner
import com.nickpulido.rcrm.domain.ParsedNote
import com.nickpulido.rcrm.domain.ReminderListFilter
import com.nickpulido.rcrm.domain.ReminderPlanner
import com.nickpulido.rcrm.ui.components.PipelineType
import com.nickpulido.rcrm.databinding.ActivityFollowUpBinding
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

private typealias DatedNote = ParsedNote

class FollowUpActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()
    private val followUpRepository = FollowUpRepository()
    private var pendingReminderId: String? = null
    private var replacingLeadDialog = false
    private lateinit var binding: ActivityFollowUpBinding
    private var pendingCreatedAppointmentId: String? = null
    private var activeProfileLeadId: String? = null
    private var activeLeadDetailsDialog: AlertDialog? = null
    private var refreshActiveProfileCallback: (() -> Unit)? = null
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
        ReminderAlarmStore.onAppStart(this)
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
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        refreshActiveProfileCallback?.invoke()
    }

    private fun handleIntent(passedIntent: Intent?) {
        val uriLeadId = GoogleCalendarManager.leadIdFromUri(passedIntent?.data)
        val targetId = uriLeadId
            ?: passedIntent?.getStringExtra("targetLeadId")
            ?: passedIntent?.getStringExtra("OPEN_LEAD_ID")
        pendingReminderId = passedIntent?.getStringExtra(ReminderReceiver.EXTRA_REMINDER_ID)
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
            passedIntent?.removeExtra(ReminderReceiver.EXTRA_REMINDER_ID)
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
            passedIntent?.removeExtra(ReminderReceiver.EXTRA_REMINDER_ID)
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

    private fun actionFromSnapshot(
        document: com.google.firebase.firestore.DocumentSnapshot,
        leadId: String
    ): NextAction {
        return ReminderPlanner.actionFrom(
            id = document.id,
            leadId = document.getString("leadId") ?: leadId,
            ownerId = document.getString("ownerId") ?: "",
            typeName = document.getString("type"),
            title = document.getString("title") ?: "",
            notes = document.getString("notes"),
            dueTime = (document.get("dueTime") as? Number)?.toLong() ?: 0L,
            statusName = document.getString("status"),
            createdAt = (document.get("createdAt") as? Number)?.toLong() ?: 0L,
            completedAt = (document.get("completedAt") as? Number)?.toLong(),
            appointmentId = document.getString("appointmentId"),
            reason = document.getString("reason"),
            workflowName = document.getString("workflow")
        )
    }

    @SuppressLint("MissingInflatedId")
    private fun showLeadDetailsDialog(lead: Map<String, Any>, finishOnDismiss: Boolean = false) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_lead_details, null)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        replacingLeadDialog = activeLeadDetailsDialog != null
        activeLeadDetailsDialog?.dismiss()
        replacingLeadDialog = false
        activeLeadDetailsDialog = dialog

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

        // Reminder variables removed

        val btnSetAppt = dialogView.findViewById<Button>(R.id.btnSetAppt)
        val tvApptValue = dialogView.findViewById<TextView>(R.id.tvApptValue)
        val btnEditApptIcon = dialogView.findViewById<View>(R.id.btnEditApptIcon)
        val btnDeleteApptIcon = dialogView.findViewById<View>(R.id.btnDeleteApptIcon)

        val headerReminders = dialogView.findViewById<View>(R.id.headerReminders)
        val containerReminders = dialogView.findViewById<View>(R.id.containerReminders)
        val tvToggleReminders = dialogView.findViewById<TextView>(R.id.tvToggleReminders)
        val layoutRemindersList = dialogView.findViewById<LinearLayout>(R.id.layoutRemindersList)
        val tvViewAllReminders = dialogView.findViewById<TextView>(R.id.tvViewAllReminders)
        val btnAddReminder = dialogView.findViewById<Button>(R.id.btnAddReminder)

        val loadedActivities = mutableListOf<ActivityRecord>()
        val loadedNextActions = mutableListOf<NextAction>()
        val layoutReminderFilters = dialogView.findViewById<LinearLayout>(R.id.layoutReminderFilters)
        val btnRemindersOpen = dialogView.findViewById<TextView>(R.id.btnRemindersOpen)
        val btnRemindersCompleted = dialogView.findViewById<TextView>(R.id.btnRemindersCompleted)
        var showAllReminders = !pendingReminderId.isNullOrBlank()
        var reminderFilter = ReminderListFilter.OPEN
        var migrationVersion = -1
        var followUpMillis: Long? = (lead["followUpDate"] as? Timestamp)?.toDate()?.time
        var remindersLoaded = false
        val reminderSavesInFlight = mutableSetOf<String>()
        pendingReminderId = null

        fun contactName(): String {
            return editName?.text?.toString()?.trim().orEmpty().ifBlank {
                (lead["name"] as? String).orEmpty().ifBlank { "Contact" }
            }
        }

        fun contactPhone(): String {
            return editPhone?.text?.toString()?.trim().orEmpty().ifBlank {
                (lead["phone"] as? String).orEmpty()
            }
        }

        fun openReminderEditor(existing: NextAction?, mode: ReminderEditorMode) {
            showReminderEditor(
                repository = followUpRepository,
                leadId = docId,
                ownerId = auth.currentUser?.uid ?: "",
                contactName = contactName(),
                phone = contactPhone(),
                existing = existing,
                mode = mode
            )
        }

        fun syncReminderAlarms() {
            if (!remindersLoaded) return
            ReminderAlarmStore.applySync(
                context = applicationContext,
                leadId = docId,
                contactName = contactName(),
                phone = contactPhone(),
                actions = loadedNextActions.toList()
            )
            if (migrationVersion >= 0 &&
                ReminderPlanner.shouldCancelLegacyPhoneAlarm(
                    storedVersion = migrationVersion,
                    datedPendingCount = loadedNextActions.count { it.status == ActionStatus.PENDING && it.dueTime > 0L },
                    legacyFollowUpMillis = followUpMillis
                )
            ) {
                ReminderAlarmStore.cancelLegacyPhone(applicationContext, contactPhone(), docId)
            }
        }

        btnAddReminder?.setOnClickListener {
            openReminderEditor(existing = null, mode = ReminderEditorMode.ADD)
        }

        if (loadedNextActions.isNotEmpty() || lead["followUpDate"] != null || lead["appointmentDate"] != null || showAllReminders) {
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
        val pbNextActionLoading = dialogView.findViewById<ProgressBar>(R.id.pbNextActionLoading)
        val layoutNextActionContent = dialogView.findViewById<View>(R.id.layoutNextActionContent)
        val ivNextActionTypeIcon = dialogView.findViewById<ImageView>(R.id.ivNextActionTypeIcon)
        val tvNextActionTitle = dialogView.findViewById<TextView>(R.id.tvNextActionTitle)
        val tvNextActionDue = dialogView.findViewById<TextView>(R.id.tvNextActionDue)
        val tvNextActionEmpty = dialogView.findViewById<TextView>(R.id.tvNextActionEmpty)
        val tvNextActionError = dialogView.findViewById<TextView>(R.id.tvNextActionError)

        val pbActivityLoading = dialogView.findViewById<ProgressBar>(R.id.pbActivityLoading)
        val tvToggleAllActivity = dialogView.findViewById<TextView>(R.id.tvToggleAllActivity)
        val activityContainer = dialogView.findViewById<LinearLayout>(R.id.activityContainer)
        val tvActivityEmpty = dialogView.findViewById<TextView>(R.id.tvActivityEmpty)
        val tvActivityError = dialogView.findViewById<TextView>(R.id.tvActivityError)

        var currentRawNotes: String = (lead["notes"] as? String) ?: ""
        var initialRawNotes: String = currentRawNotes
        var showAllActivity = false

        activeProfileLeadId = docId

        fun createPipelineIntent(stageContext: String? = null): Intent {
            val contactName = lead["name"] as? String ?: ""
            val category = lead["category"] as? String ?: ""
            val isRecruit = category.contains(getString(R.string.category_recruit), ignoreCase = true)
            val pType = if (isRecruit) PipelineType.RECRUITING.name else PipelineType.SALES.name

            var targetStageId: String? = null
            if (!stageContext.isNullOrBlank()) {
                if (isRecruit) {
                    targetStageId = com.nickpulido.rcrm.ui.components.RecruitStage.values().find {
                        it.title.equals(stageContext, ignoreCase = true) || it.shortLabel.equals(stageContext, ignoreCase = true) || it.id.equals(stageContext, ignoreCase = true)
                    }?.id
                } else {
                    targetStageId = com.nickpulido.rcrm.ui.components.SalesStage.values().find {
                        it.title.equals(stageContext, ignoreCase = true) || it.shortLabel.equals(stageContext, ignoreCase = true) || it.id.equals(stageContext, ignoreCase = true)
                    }?.id
                }
            }
            if (targetStageId == null) {
                targetStageId = if (isRecruit) (lead["recruitStage"] as? String) else (lead["salesStage"] as? String)
            }

            return Intent(this@FollowUpActivity, PipelinesActivity::class.java).apply {
                putExtra("targetLeadId", docId)
                putExtra("searchQuery", contactName)
                putExtra("pipelineType", pType)
                if (!targetStageId.isNullOrBlank()) {
                    putExtra("targetStageId", targetStageId)
                }
            }
        }

        val openPipelineAction = View.OnClickListener {
            startActivity(createPipelineIntent())
        }

        fun renderNextActionUI() {
            val pendingAction = loadedNextActions
                .filter { it.status == ActionStatus.PENDING }
                .minByOrNull { it.dueTime }

            if (pendingAction != null) {
                layoutNextActionContent?.visibility = View.VISIBLE
                tvNextActionEmpty?.visibility = View.GONE
                tvNextActionError?.visibility = View.GONE

                tvNextActionTitle?.text = pendingAction.title
                val dueStr = if (pendingAction.dueTime > 0L) {
                    ReminderPlanner.dueLabel(pendingAction.dueTime, System.currentTimeMillis())
                } else {
                    "No date"
                }
                tvNextActionDue?.text = dueStr

                if (dueStr.startsWith("Overdue")) {
                    tvNextActionDue?.setTextColor(Color.parseColor("#FF5252"))
                } else {
                    tvNextActionDue?.setTextColor(Color.parseColor("#2EE7FF"))
                }

                when (pendingAction.type) {
                    ActionType.CALL -> {
                        ivNextActionTypeIcon?.setImageResource(android.R.drawable.ic_menu_call)
                        ivNextActionTypeIcon?.background = ContextCompat.getDrawable(this, R.drawable.bg_icon_circle_green)
                        ivNextActionTypeIcon?.setColorFilter(Color.parseColor("#3DFF9A"))
                    }
                    ActionType.MESSAGE -> {
                        ivNextActionTypeIcon?.setImageResource(android.R.drawable.sym_action_chat)
                        ivNextActionTypeIcon?.background = ContextCompat.getDrawable(this, R.drawable.bg_icon_circle_cyan)
                        ivNextActionTypeIcon?.setColorFilter(Color.parseColor("#2EE7FF"))
                    }
                    ActionType.EMAIL -> {
                        ivNextActionTypeIcon?.setImageResource(android.R.drawable.ic_dialog_email)
                        ivNextActionTypeIcon?.background = ContextCompat.getDrawable(this, R.drawable.bg_icon_circle_blue)
                        ivNextActionTypeIcon?.setColorFilter(Color.parseColor("#D5E6FF"))
                    }
                    ActionType.MEETING -> {
                        ivNextActionTypeIcon?.setImageResource(android.R.drawable.ic_menu_agenda)
                        ivNextActionTypeIcon?.background = ContextCompat.getDrawable(this, R.drawable.bg_icon_circle_purple)
                        ivNextActionTypeIcon?.setColorFilter(Color.parseColor("#B14CFF"))
                    }
                    ActionType.TO_DO -> {
                        ivNextActionTypeIcon?.setImageResource(android.R.drawable.checkbox_on_background)
                        ivNextActionTypeIcon?.background = ContextCompat.getDrawable(this, R.drawable.bg_icon_circle_cyan)
                        ivNextActionTypeIcon?.setColorFilter(Color.parseColor("#2EE7FF"))
                    }
                }
            } else {
                layoutNextActionContent?.visibility = View.GONE
                tvNextActionEmpty?.visibility = View.VISIBLE
                tvNextActionError?.visibility = View.GONE
            }
        }

        fun renderRemindersUI() {
            layoutRemindersList?.removeAllViews()
            val visibleActions = ReminderPlanner.remindersToDisplay(
                actions = loadedNextActions,
                filter = reminderFilter,
                expanded = showAllReminders
            )
            val showViewAll = ReminderPlanner.shouldShowViewAll(loadedNextActions) || showAllReminders
            tvViewAllReminders?.visibility = if (showViewAll) View.VISIBLE else View.GONE
            tvViewAllReminders?.text = if (showAllReminders) "Show less" else "View all"
            layoutReminderFilters?.visibility = if (showAllReminders) View.VISIBLE else View.GONE
            btnRemindersOpen?.setTextColor(Color.parseColor(if (reminderFilter == ReminderListFilter.OPEN) "#2EE7FF" else "#8E99B0"))
            btnRemindersCompleted?.setTextColor(Color.parseColor(if (reminderFilter == ReminderListFilter.COMPLETED) "#2EE7FF" else "#8E99B0"))

            visibleActions.forEach { action ->
                val view = layoutInflater.inflate(R.layout.item_reminder, layoutRemindersList, false)
                
                val tvTitle = view.findViewById<TextView>(R.id.tvReminderTitle)
                val tvDue = view.findViewById<TextView>(R.id.tvReminderDue)
                val ivIcon = view.findViewById<ImageView>(R.id.ivReminderTypeIcon)
                val btnComplete = view.findViewById<ImageButton>(R.id.btnCompleteReminder)
                val btnOverflow = view.findViewById<ImageButton>(R.id.btnReminderOverflow)
                val btnLaunchAction = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnLaunchAction)
                val btnRemindLater = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnRemindLater)
                val layoutActions = view.findViewById<LinearLayout>(R.id.layoutReminderActions)

                tvTitle.text = action.title
                val notesView = view.findViewById<TextView>(R.id.tvReminderNotes)
                if (action.notes.isNotBlank()) {
                    notesView?.text = action.notes
                    notesView?.visibility = View.VISIBLE
                } else {
                    notesView?.visibility = View.GONE
                }
                val completed = action.status == ActionStatus.COMPLETED
                if (completed) {
                    val completedAt = action.completedAt
                    tvDue.text = if (completedAt != null && completedAt > 0L) {
                        "Completed · ${OutcomeSavePlanner.formatDue(completedAt)}"
                    } else {
                        "Completed"
                    }
                    tvDue.setTextColor(Color.parseColor("#8E99B0"))
                    btnComplete.visibility = View.GONE
                    btnOverflow.visibility = View.GONE
                    layoutActions?.visibility = View.GONE
                } else if (action.dueTime > 0L) {
                    val dueLabel = ReminderPlanner.dueLabel(action.dueTime, System.currentTimeMillis())
                    tvDue.text = dueLabel
                    tvDue.setTextColor(
                        Color.parseColor(if (dueLabel.startsWith("Overdue")) "#FF5252" else "#2EE7FF")
                    )
                    btnComplete.visibility = View.VISIBLE
                    btnOverflow.visibility = View.VISIBLE
                    layoutActions?.visibility = View.VISIBLE
                } else {
                    tvDue.text = "No date"
                    tvDue.setTextColor(Color.parseColor("#2EE7FF"))
                    btnComplete.visibility = View.VISIBLE
                    btnOverflow.visibility = View.VISIBLE
                    layoutActions?.visibility = View.VISIBLE
                }

                if (btnLaunchAction != null) {
                    when (action.type) {
                        ActionType.CALL -> {
                            btnLaunchAction.visibility = View.VISIBLE
                            btnLaunchAction.text = "Call"
                            btnLaunchAction.setIconResource(android.R.drawable.ic_menu_call)
                            btnLaunchAction.setOnClickListener {
                                val phone = contactPhone()
                                val intent = android.content.Intent(android.content.Intent.ACTION_DIAL).apply {
                                    data = android.net.Uri.parse("tel:$phone")
                                }
                                try { startActivity(intent) } catch (e: Exception) { Toast.makeText(this@FollowUpActivity, "No dialer found", Toast.LENGTH_SHORT).show() }
                            }
                        }
                        ActionType.MESSAGE -> {
                            btnLaunchAction.visibility = View.VISIBLE
                            btnLaunchAction.text = "Message"
                            btnLaunchAction.setIconResource(android.R.drawable.sym_action_chat)
                            btnLaunchAction.setOnClickListener {
                                val phone = contactPhone()
                                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                                    data = android.net.Uri.parse("sms:$phone")
                                }
                                try { startActivity(intent) } catch (e: Exception) { Toast.makeText(this@FollowUpActivity, "No messaging app found", Toast.LENGTH_SHORT).show() }
                            }
                        }
                        ActionType.EMAIL -> {
                            btnLaunchAction.visibility = View.VISIBLE
                            btnLaunchAction.text = "Email"
                            btnLaunchAction.setIconResource(android.R.drawable.ic_dialog_email)
                            btnLaunchAction.setOnClickListener {
                                val email = editEmail?.text?.toString()?.trim().orEmpty().ifBlank { (lead["email"] as? String).orEmpty() }
                                val intent = android.content.Intent(android.content.Intent.ACTION_SENDTO).apply {
                                    data = android.net.Uri.parse("mailto:$email")
                                }
                                try { startActivity(intent) } catch (e: Exception) { Toast.makeText(this@FollowUpActivity, "No email app found", Toast.LENGTH_SHORT).show() }
                            }
                        }
                        else -> {
                            btnLaunchAction.visibility = View.GONE
                        }
                    }
                }
                
                if (btnRemindLater != null) {
                    btnRemindLater.setOnClickListener {
                        val popup = android.widget.PopupMenu(this, btnRemindLater)
                        popup.menu.add("Tomorrow").setOnMenuItemClickListener {
                            val nextTime = ReminderPlanner.tomorrowShortcutMillis(System.currentTimeMillis(), java.util.TimeZone.getDefault())
                            if (!reminderSavesInFlight.add(action.id)) return@setOnMenuItemClickListener true
                            followUpRepository.rescheduleReminder(
                                leadId = docId,
                                ownerId = auth.currentUser?.uid ?: "",
                                actionId = action.id,
                                dueTime = nextTime,
                                nowMillis = System.currentTimeMillis(),
                                zone = java.util.TimeZone.getDefault()
                            ) { success, _ ->
                                reminderSavesInFlight.remove(action.id)
                                if (success) {
                                    ReminderAlarmStore.scheduleAction(
                                        context = this@FollowUpActivity,
                                        leadId = docId,
                                        actionId = action.id,
                                        title = action.title,
                                        contactName = contactName(),
                                        phone = contactPhone(),
                                        dueTime = nextTime
                                    )
                                    Toast.makeText(this@FollowUpActivity, "Rescheduled for tomorrow", Toast.LENGTH_SHORT).show()
                                }
                            }
                            true
                        }
                        popup.menu.add("Next week").setOnMenuItemClickListener {
                            val cal = java.util.Calendar.getInstance()
                            cal.add(java.util.Calendar.DAY_OF_YEAR, 7)
                            val nextTime = ReminderPlanner.normalizeDueMillis(cal.timeInMillis, java.util.TimeZone.getDefault())
                            if (!reminderSavesInFlight.add(action.id)) return@setOnMenuItemClickListener true
                            followUpRepository.rescheduleReminder(
                                leadId = docId,
                                ownerId = auth.currentUser?.uid ?: "",
                                actionId = action.id,
                                dueTime = nextTime,
                                nowMillis = System.currentTimeMillis(),
                                zone = java.util.TimeZone.getDefault()
                            ) { success, _ ->
                                reminderSavesInFlight.remove(action.id)
                                if (success) {
                                    ReminderAlarmStore.scheduleAction(
                                        context = this@FollowUpActivity,
                                        leadId = docId,
                                        actionId = action.id,
                                        title = action.title,
                                        contactName = contactName(),
                                        phone = contactPhone(),
                                        dueTime = nextTime
                                    )
                                    Toast.makeText(this@FollowUpActivity, "Rescheduled for next week", Toast.LENGTH_SHORT).show()
                                }
                            }
                            true
                        }
                        popup.menu.add("Custom...").setOnMenuItemClickListener {
                            openReminderEditor(action, ReminderEditorMode.RESCHEDULE)
                            true
                        }
                        popup.show()
                    }
                }

                when (action.type) {
                    ActionType.CALL -> {
                        ivIcon.setImageResource(android.R.drawable.ic_menu_call)
                        ivIcon.background = ContextCompat.getDrawable(this, R.drawable.bg_icon_circle_green)
                        ivIcon.setColorFilter(Color.parseColor("#3DFF9A"))
                    }
                    ActionType.MESSAGE -> {
                        ivIcon.setImageResource(android.R.drawable.sym_action_chat)
                        ivIcon.background = ContextCompat.getDrawable(this, R.drawable.bg_icon_circle_cyan)
                        ivIcon.setColorFilter(Color.parseColor("#2EE7FF"))
                    }
                    ActionType.EMAIL -> {
                        ivIcon.setImageResource(android.R.drawable.ic_dialog_email)
                        ivIcon.background = ContextCompat.getDrawable(this, R.drawable.bg_icon_circle_blue)
                        ivIcon.setColorFilter(Color.parseColor("#D5E6FF"))
                    }
                    ActionType.MEETING -> {
                        ivIcon.setImageResource(android.R.drawable.ic_menu_agenda)
                        ivIcon.background = ContextCompat.getDrawable(this, R.drawable.bg_icon_circle_purple)
                        ivIcon.setColorFilter(Color.parseColor("#B14CFF"))
                    }
                    ActionType.TO_DO -> {
                        ivIcon.setImageResource(android.R.drawable.checkbox_on_background)
                        ivIcon.background = ContextCompat.getDrawable(this, R.drawable.bg_icon_circle_cyan)
                        ivIcon.setColorFilter(Color.parseColor("#2EE7FF"))
                    }
                }

                btnComplete.setOnClickListener {
                    if (!reminderSavesInFlight.add(action.id)) return@setOnClickListener
                    followUpRepository.completeReminder(
                        leadId = docId,
                        ownerId = auth.currentUser?.uid ?: "",
                        actionId = action.id
                    ) { success, error ->
                        reminderSavesInFlight.remove(action.id)
                        if (success) {
                            ReminderAlarmStore.cancelAction(this, docId, action.id)
                            com.google.android.material.snackbar.Snackbar.make(
                                findViewById(android.R.id.content),
                                "Reminder completed",
                                com.google.android.material.snackbar.Snackbar.LENGTH_LONG
                            ).setAction("Undo") {
                                if (!reminderSavesInFlight.add(action.id)) return@setAction
                                followUpRepository.restoreReminder(
                                    leadId = docId,
                                    ownerId = auth.currentUser?.uid ?: "",
                                    actionId = action.id
                                ) { restoreSuccess, restoreError ->
                                    reminderSavesInFlight.remove(action.id)
                                    if (restoreSuccess) {
                                        if (ReminderPlanner.shouldRestoreAlarm(action.dueTime, System.currentTimeMillis())) {
                                            ReminderAlarmStore.scheduleAction(
                                                context = this,
                                                leadId = docId,
                                                actionId = action.id,
                                                title = action.title,
                                                contactName = contactName(),
                                                phone = contactPhone(),
                                                dueTime = action.dueTime
                                            )
                                        }
                                    } else {
                                        Toast.makeText(this, restoreError ?: "Couldn't undo.", Toast.LENGTH_LONG).show()
                                    }
                                }
                            }.show()
                        } else {
                            Toast.makeText(this, error ?: "Couldn't complete that reminder.", Toast.LENGTH_LONG).show()
                        }
                    }
                }
                btnOverflow.setOnClickListener {
                    val popup = android.widget.PopupMenu(this, btnOverflow)
                    popup.menu.add("Edit").setOnMenuItemClickListener {
                        openReminderEditor(action, ReminderEditorMode.EDIT)
                        true
                    }
                    popup.menu.add("Reschedule").setOnMenuItemClickListener {
                        openReminderEditor(action, ReminderEditorMode.RESCHEDULE)
                        true
                    }
                    popup.menu.add("Delete").setOnMenuItemClickListener {
                        if (!reminderSavesInFlight.add(action.id)) return@setOnMenuItemClickListener true
                        followUpRepository.cancelReminder(
                            leadId = docId,
                            ownerId = auth.currentUser?.uid ?: "",
                            actionId = action.id
                        ) { success, error ->
                            reminderSavesInFlight.remove(action.id)
                            if (success) {
                                ReminderAlarmStore.cancelAction(this, docId, action.id)
                            } else {
                                Toast.makeText(this, error ?: "Couldn't delete that reminder.", Toast.LENGTH_LONG).show()
                            }
                        }
                        true
                    }
                    popup.show()
                }

                layoutRemindersList?.addView(view)
            }
        }

        tvViewAllReminders?.setOnClickListener {
            showAllReminders = !showAllReminders
            renderRemindersUI()
        }
        btnRemindersOpen?.setOnClickListener {
            reminderFilter = ReminderListFilter.OPEN
            renderRemindersUI()
        }
        btnRemindersCompleted?.setOnClickListener {
            reminderFilter = ReminderListFilter.COMPLETED
            renderRemindersUI()
        }

        fun renderActivityTimelineUI() {
            activityContainer?.removeAllViews()

            val timeline = ContactActivityTimeline.mergeActivityAndNotes(
                activityRecords = loadedActivities,
                rawNotes = currentRawNotes
            )

            if (timeline.isEmpty()) {
                tvActivityEmpty?.visibility = View.VISIBLE
                tvToggleAllActivity?.visibility = View.GONE
                tvActivityError?.visibility = View.GONE
                return
            }

            tvActivityEmpty?.visibility = View.GONE
            tvActivityError?.visibility = View.GONE

            if (timeline.size > 3) {
                tvToggleAllActivity?.visibility = View.VISIBLE
                tvToggleAllActivity?.text = if (showAllActivity) "Show less ▲" else "View all (${timeline.size}) ▼"
            } else {
                tvToggleAllActivity?.visibility = View.GONE
            }

            val displayedItems = if (showAllActivity) timeline else timeline.take(3)

            for (item in displayedItems) {
                val itemView = LayoutInflater.from(this).inflate(R.layout.item_activity_entry, activityContainer, false)
                val ivIcon = itemView.findViewById<ImageView>(R.id.ivActivityIcon)
                val tvOutcome = itemView.findViewById<TextView>(R.id.tvActivityOutcome)
                val tvStageBadge = itemView.findViewById<TextView>(R.id.tvActivityStageBadge)
                val tvTimestamp = itemView.findViewById<TextView>(R.id.tvActivityTimestamp)
                val tvNote = itemView.findViewById<TextView>(R.id.tvActivityNote)
                val actionsLayout = itemView.findViewById<View>(R.id.llActivityItemActions)
                val btnEdit = itemView.findViewById<View>(R.id.btnEditActivityNote)
                val btnDelete = itemView.findViewById<View>(R.id.btnDeleteActivityNote)

                tvOutcome.text = item.outcome.ifBlank { "Activity" }
                tvTimestamp.text = item.dateDisplay

                if (!item.stageContext.isNullOrBlank()) {
                    tvStageBadge.text = item.stageContext
                    tvStageBadge.visibility = View.VISIBLE
                } else {
                    tvStageBadge.visibility = View.GONE
                }

                if (item.note.isNotBlank()) {
                    tvNote.text = item.note
                    tvNote.visibility = View.VISIBLE
                } else {
                    tvNote.visibility = View.GONE
                }

                when (item.type) {
                    ActionType.CALL -> {
                        ivIcon.setImageResource(android.R.drawable.ic_menu_call)
                        ivIcon.background = ContextCompat.getDrawable(this, R.drawable.bg_icon_circle_green)
                        ivIcon.setColorFilter(Color.parseColor("#3DFF9A"))
                    }
                    ActionType.MESSAGE -> {
                        ivIcon.setImageResource(android.R.drawable.sym_action_chat)
                        ivIcon.background = ContextCompat.getDrawable(this, R.drawable.bg_icon_circle_cyan)
                        ivIcon.setColorFilter(Color.parseColor("#2EE7FF"))
                    }
                    ActionType.EMAIL -> {
                        ivIcon.setImageResource(android.R.drawable.ic_dialog_email)
                        ivIcon.background = ContextCompat.getDrawable(this, R.drawable.bg_icon_circle_blue)
                        ivIcon.setColorFilter(Color.parseColor("#D5E6FF"))
                    }
                    ActionType.MEETING -> {
                        ivIcon.setImageResource(android.R.drawable.ic_menu_agenda)
                        ivIcon.background = ContextCompat.getDrawable(this, R.drawable.bg_icon_circle_purple)
                        ivIcon.setColorFilter(Color.parseColor("#B14CFF"))
                    }
                    ActionType.TO_DO -> {
                        val iconRes = if (item.outcome == "Note") android.R.drawable.ic_menu_edit else android.R.drawable.checkbox_on_background
                        ivIcon.setImageResource(iconRes)
                        ivIcon.background = ContextCompat.getDrawable(this, R.drawable.bg_icon_circle_cyan)
                        ivIcon.setColorFilter(Color.parseColor("#2EE7FF"))
                    }
                }

                if (!item.isActivityRecord) {
                    actionsLayout?.visibility = View.VISIBLE
                    btnEdit?.setOnClickListener {
                        val editDialogView = LayoutInflater.from(this).inflate(R.layout.dialog_edit_note, null)
                        val input = editDialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etEditNoteContent)
                        val tvDate = editDialogView.findViewById<TextView>(R.id.tvEditNoteDate)

                        tvDate?.text = item.dateDisplay
                        input?.setText(item.note)

                        AlertDialog.Builder(this)
                            .setTitle("Edit Note")
                            .setView(editDialogView)
                            .setPositiveButton("Save") { _, _ ->
                                val newContent = input?.text?.toString()?.trim() ?: ""
                                val parsed = ContactActivityTimeline.parseNotes(currentRawNotes).toMutableList()
                                val targetIdx = parsed.indexOfFirst { it.content.trim() == item.note.trim() }
                                if (targetIdx >= 0) {
                                    parsed[targetIdx] = parsed[targetIdx].copy(content = newContent)
                                    currentRawNotes = parsed.joinToString("\n\n") { p ->
                                        if (p.eventId != null) "[${p.date} | eid:${p.eventId}]: ${p.content}"
                                        else "[${p.date}]: ${p.content}"
                                    }
                                    renderActivityTimelineUI()
                                }
                            }
                            .setNegativeButton("Cancel", null)
                            .create().apply {
                                window?.setBackgroundDrawableResource(android.R.color.transparent)
                                show()
                            }
                    }
                    btnDelete?.setOnClickListener {
                        val parsed = ContactActivityTimeline.parseNotes(currentRawNotes).toMutableList()
                        val targetIdx = parsed.indexOfFirst { it.content.trim() == item.note.trim() }
                        if (targetIdx >= 0) {
                            parsed.removeAt(targetIdx)
                            currentRawNotes = parsed.joinToString("\n\n") { p ->
                                if (p.eventId != null) "[${p.date} | eid:${p.eventId}]: ${p.content}"
                                else "[${p.date}]: ${p.content}"
                            }
                            renderActivityTimelineUI()
                        }
                    }
                } else {
                    actionsLayout?.visibility = View.GONE
                }

                itemView.setOnClickListener {
                    startActivity(createPipelineIntent(item.stageContext))
                }
                activityContainer?.addView(itemView)
            }
        }

        val llRecentActivityHeader = dialogView.findViewById<View>(R.id.llRecentActivityHeader)
        llRecentActivityHeader?.setOnClickListener(openPipelineAction)
        tvActivityEmpty?.setOnClickListener(openPipelineAction)

        tvToggleAllActivity?.setOnClickListener {
            showAllActivity = !showAllActivity
            renderActivityTimelineUI()
        }

        renderNextActionUI()
        renderRemindersUI()
        renderActivityTimelineUI()
        refreshActiveProfileCallback = {
            renderNextActionUI()
            renderRemindersUI()
            renderActivityTimelineUI()
        }

        pbNextActionLoading?.visibility = View.VISIBLE
        pbActivityLoading?.visibility = View.VISIBLE

        val nextActionsReg = db.collection("leads").document(docId)
            .collection("next_actions")
            .addSnapshotListener { snapshot, error ->
                pbNextActionLoading?.visibility = View.GONE
                if (error != null) {
                    tvNextActionError?.visibility = View.VISIBLE
                    tvNextActionError?.text = "Couldn't load upcoming action"
                    return@addSnapshotListener
                }
                loadedNextActions.clear()
                snapshot?.documents?.forEach { document ->
                    loadedNextActions.add(actionFromSnapshot(document, docId))
                }
                remindersLoaded = true
                renderNextActionUI()
                renderRemindersUI()
                syncReminderAlarms()
            }

        val ownerId = auth.currentUser?.uid.orEmpty()
        if (ownerId.isNotBlank()) {
            followUpRepository.migrateLegacyReminder(docId, ownerId) { plan ->
                if (plan == null) return@migrateLegacyReminder
                val phone = (lead["phone"] as? String).orEmpty()
                val name = (lead["name"] as? String).orEmpty().ifBlank { "Contact" }
                if (plan.cancelLegacyPhoneAlarm) {
                    ReminderAlarmStore.cancelLegacyPhone(applicationContext, phone, docId)
                }
                plan.actionToCreate?.let { created ->
                    ReminderAlarmStore.scheduleAction(
                        context = applicationContext,
                        leadId = docId,
                        actionId = created.id,
                        title = created.title,
                        contactName = name,
                        phone = phone,
                        dueTime = created.dueTime
                    )
                }
            }
        }

        val activityReg = db.collection("leads").document(docId)
            .collection("activity_records")
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, error ->
                pbActivityLoading?.visibility = View.GONE
                if (error != null) {
                    tvActivityError?.visibility = View.VISIBLE
                    tvActivityError?.text = "Couldn't load activity. Tap to retry."
                    return@addSnapshotListener
                }
                loadedActivities.clear()
                snapshot?.documents?.forEach { d ->
                    val act = d.toObject(ActivityRecord::class.java)?.copy(id = d.id)
                        ?: ActivityRecord(
                            id = d.id,
                            leadId = docId,
                            type = runCatching { ActionType.valueOf(d.getString("type") ?: "") }.getOrDefault(ActionType.TO_DO),
                            outcome = d.getString("outcome") ?: "",
                            note = d.getString("note") ?: "",
                            timestamp = d.getLong("timestamp") ?: 0L
                        )
                    loadedActivities.add(act)
                }
                renderActivityTimelineUI()
            }

        val leadDocReg = db.collection("leads").document(docId)
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null || !snapshot.exists()) return@addSnapshotListener
                migrationVersion = when (val value = snapshot.get(ReminderPlanner.MIGRATION_FIELD)) {
                    is Number -> value.toInt()
                    else -> 0
                }
                followUpMillis = snapshot.getTimestamp("followUpDate")?.toDate()?.time
                val remoteNotes = snapshot.getString("notes") ?: ""
                if (remoteNotes != initialRawNotes && remoteNotes != currentRawNotes) {
                    currentRawNotes = remoteNotes
                    initialRawNotes = remoteNotes
                    renderActivityTimelineUI()
                }

                // Protect unsaved form edits: only update if matching initial value
                val initialName = lead["name"] as? String ?: ""
                if (editName?.text?.toString() == initialName) {
                    val remoteName = snapshot.getString("name")
                    if (remoteName != null) editName?.setText(remoteName)
                }
                val initialPhone = lead["phone"] as? String ?: ""
                if (editPhone?.text?.toString() == initialPhone) {
                    val remotePhone = snapshot.getString("phone")
                    if (remotePhone != null) editPhone?.setText(remotePhone)
                }
                val initialEmail = lead["email"] as? String ?: ""
                if (editEmail?.text?.toString() == initialEmail) {
                    val remoteEmail = snapshot.getString("email")
                    if (remoteEmail != null) editEmail?.setText(remoteEmail)
                }
                syncReminderAlarms()
            }

        tvActivityError?.setOnClickListener {
            tvActivityError.visibility = View.GONE
            pbActivityLoading?.visibility = View.VISIBLE
            db.collection("leads").document(docId)
                .collection("activity_records")
                .orderBy("timestamp", Query.Direction.DESCENDING)
                .get()
                .addOnSuccessListener { snapshot ->
                    pbActivityLoading?.visibility = View.GONE
                    loadedActivities.clear()
                    snapshot.documents.forEach { d ->
                        val act = d.toObject(ActivityRecord::class.java)?.copy(id = d.id)
                            ?: ActivityRecord(
                                id = d.id,
                                leadId = docId,
                                type = runCatching { ActionType.valueOf(d.getString("type") ?: "") }.getOrDefault(ActionType.TO_DO),
                                outcome = d.getString("outcome") ?: "",
                                note = d.getString("note") ?: "",
                                timestamp = d.getLong("timestamp") ?: 0L
                            )
                        loadedActivities.add(act)
                    }
                    renderActivityTimelineUI()
                }
                .addOnFailureListener {
                    pbActivityLoading?.visibility = View.GONE
                    tvActivityError.visibility = View.VISIBLE
                }
        }

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
            val currentNote = ContactActivityTimeline.parseNotes(currentRawNotes).joinToString("\n") { it.content }
            
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

        val showOutcomeSheet = {
            val bottomSheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
            val composeView = androidx.compose.ui.platform.ComposeView(this).apply {
                setContent {
                    com.nickpulido.rcrm.ui.theme.RCRMTheme {
                        com.nickpulido.rcrm.ui.components.OutcomeSheet(
                            request = com.nickpulido.rcrm.ui.components.OutcomeSheetRequest(
                                leadId = docId,
                                contactName = editName?.text?.toString() ?: "",
                                subtitle = com.nickpulido.rcrm.ui.components.homeOutcomeSubtitle(
                                    lead["category"] as? String ?: "", 
                                    lead["salesStage"] as? String, 
                                    lead["recruitStage"] as? String
                                ),
                                currentActionId = loadedNextActions.firstOrNull { it.status == ActionStatus.PENDING }?.id,
                                existingFollowUpMillis = (lead["followUpDate"] as? com.google.firebase.Timestamp)?.toDate()?.time,
                                appointments = com.nickpulido.rcrm.ui.components.appointmentChoicesFromLead(
                                    lead["appointments"],
                                    lead["appointmentDate"] as? com.google.firebase.Timestamp
                                ),
                                outcomeRequired = false,
                                stage = null
                            ),
                            onDismiss = { bottomSheet.dismiss() },
                            onSave = { submission, complete ->
                                lifecycleScope.launch {
                                    followUpRepository.saveOutcome(
                                        com.nickpulido.rcrm.domain.OutcomeSaveRequest(
                                            operationId = submission.operationId,
                                            leadId = docId,
                                            ownerId = auth.currentUser?.uid ?: "",
                                            outcome = submission.outcome.takeIf { it.isNotBlank() },
                                            note = submission.note,
                                            activityType = submission.nextType,
                                            stage = null,
                                            currentActionId = loadedNextActions.firstOrNull { it.status == ActionStatus.PENDING }?.id,
                                            resolveCurrentActionIfMissing = true,
                                            nextAction = if (submission.scheduleNext) {
                                                com.nickpulido.rcrm.domain.NextActionSpec(
                                                    title = submission.nextTitle,
                                                    dueTime = submission.nextTime ?: 0L,
                                                    type = submission.nextType,
                                                    appointmentId = submission.linkedAppointmentId
                                                )
                                            } else null,
                                            clearExistingNextAction = submission.clearExisting,
                                            lifecycle = submission.lifecycle,
                                            lifecycleReason = submission.lifecycleReason,
                                            nowMillis = System.currentTimeMillis()
                                        )
                                    ) { success, errorMessage ->
                                        complete(success, errorMessage)
                                        if (success) {
                                            bottomSheet.dismiss()
                                        }
                                    }
                                }
                            }
                        )
                    }
                }
            }
            bottomSheet.setContentView(composeView)
            bottomSheet.show()
        }

        // Contact Buttons
        btnSmsDetail?.setOnClickListener {
            val phone = editPhone?.text.toString()
            if (phone.isNotEmpty()) {
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("sms:$phone"))) } catch (e: Exception) {}
                showOutcomeSheet()
            }
        }
        btnCallDetail?.setOnClickListener {
            val phone = editPhone?.text.toString()
            if (phone.isNotEmpty()) {
                try { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone"))) } catch (e: Exception) {}
                showOutcomeSheet()
            }
        }
        btnEmailDetail?.setOnClickListener {
            val email = editEmail?.text.toString()
            if (email.isNotEmpty()) {
                try {
                    val intent = Intent(Intent.ACTION_SENDTO).apply {
                        data = Uri.parse("mailto:")
                        putExtra(Intent.EXTRA_EMAIL, arrayOf(email))
                    }
                    startActivity(intent)
                } catch (e: Exception) {}
                showOutcomeSheet()
            }
        }
        btnMapDetail?.setOnClickListener {
            val addr = editAddress?.text.toString()
            if (addr.isNotEmpty()) {
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(addr)}"))) } catch (e: Exception) {}
                showOutcomeSheet()
            }
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
                currentRawNotes = NoteUtils.prependNote(currentRawNotes, text)
                renderActivityTimelineUI()
                etQuickNote?.text?.clear()
            }
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
                extractedNotes += "\n[Requested follow up date: $dateStr]"
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
            currentRawNotes = NoteUtils.prependNote(currentRawNotes, "[Voice Log]: $extractedNotes")
            renderActivityTimelineUI()
        }

        // Clear lambda on dismiss to prevent stale dialog view references from being
        // applied to a different lead if a delayed result arrives.
        dialog.setOnDismissListener {
            nextActionsReg.remove()
            activityReg.remove()
            leadDocReg.remove()
            applyVoiceLogToDialog = null
            onVoiceLogComplete = null
            activeProfileLeadId = null
            refreshActiveProfileCallback = null
            if (activeLeadDetailsDialog == dialog) {
                activeLeadDetailsDialog = null
            }
            if (finishOnDismiss && !replacingLeadDialog) {
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
                currentRawNotes = NoteUtils.prependNote(currentRawNotes, pendingNoteText)
                etQuickNote?.text?.clear()
                renderActivityTimelineUI()
            }

            val updatedCats = mutableListOf<String>()
            if (cbRecruit?.isChecked == true) updatedCats.add(getString(R.string.category_recruit))
            if (cbProspect?.isChecked == true) updatedCats.add(getString(R.string.category_prospect))
            if (cbClient?.isChecked == true) {
                updatedCats.add(getString(R.string.category_client))
                if (cbInv?.isChecked == true) updatedCats.add("Investment")
                if (cbLife?.isChecked == true) updatedCats.add("Life Insurance")
            }
            
            val updatedNotes = currentRawNotes
            val tmList = mutableListOf<String>()
            if (cbTmMarried?.isChecked == true) tmList.add("Married")
            if (cbTmAge?.isChecked == true) tmList.add("Age 25-55")
            if (cbTmChildren?.isChecked == true) tmList.add("Children")
            if (cbTmHomeowner?.isChecked == true) tmList.add("Homeowner")
            if (cbTmOccupation?.isChecked == true) tmList.add("Occupation")

            val previousStart = (lead?.get("appointmentDate") as? Timestamp)?.toDate()?.time
            val previousLocation = lead?.get("appointmentLocation") as? String
            val previousEventId = GoogleCalendarManager.readStoredEventId(lead?.get("googleCalendarEventId"))
            val priorRecords = LeadAppointmentSync.readRecords(lead?.get(AppointmentAgenda.FIELD_APPOINTMENTS))
            val appointmentEdit = AppointmentAgenda.describeSingleAppointmentEdit(
                leadId = docId,
                existing = priorRecords,
                previousStartMillis = previousStart,
                previousLocation = previousLocation,
                previousEventId = previousEventId,
                newStartMillis = appointmentDate?.time,
                newLocation = appointmentLocation,
                newEventId = previousEventId,
                nowMillis = System.currentTimeMillis(),
                createdId = pendingCreatedAppointmentId
                    ?: AppointmentAgenda.newAppointmentId().also { pendingCreatedAppointmentId = it }
            )
            val appointmentChanged = appointmentDate?.time != previousStart ||
                appointmentLocation.orEmpty().trim() != previousLocation.orEmpty().trim()
            val editedBaseUpdatedAt = priorRecords
                .firstOrNull { it.id == appointmentEdit.editedId }
                ?.updatedAtMillis ?: 0L
            val updates: MutableMap<String, Any?> = hashMapOf(
                "name" to editName?.text.toString(),
                "phone" to editPhone?.text.toString(),
                "email" to editEmail?.text.toString(),
                "address" to editAddress?.text.toString(),
                "company" to editCompany?.text.toString(),
                "jobTitle" to editJobTitle?.text.toString(),
                "category" to updatedCats.joinToString(", "),
                "notes" to updatedNotes,
                "targetMarket" to tmList.joinToString(", ")
            )
            updates["insCarrier"] = editInsCarrier?.text.toString()
            updates["insPolicyType"] = spinnerInsPolicyType?.selectedItem?.toString()?.takeIf { it != "Select Policy Type..." }
            updates["insPolicyNumber"] = editInsPolicyNumber?.text.toString()
            updates["insFaceAmount"] = editInsFaceAmount?.text.toString()
            updates["insMonthlyPremium"] = editInsMonthlyPremium?.text.toString()
            updates["insUnderwritingStatus"] = spinnerInsStatus?.selectedItem?.toString()?.takeIf { it != "Select Status..." }
            updates["insBeneficiaries"] = editInsBeneficiaries?.text.toString()
            updates["insRenewalDate"] = insRenewalDateStr
            updates["invAccountType"] = spinnerInvAccountType?.selectedItem?.toString()?.takeIf { it != "Select Account Type..." }
            updates["invPlatform"] = editInvPlatform?.text.toString()
            updates["invLumpSumRollover"] = editInvLumpSum?.text.toString()
            updates["invMonthlyContribution"] = editInvMonthlyContribution?.text.toString()
            updates["invAccountStatus"] = spinnerInvStatus?.selectedItem?.toString()?.takeIf { it != "Select Status..." }
            
            if (lead is MutableMap<*, *>) {
                @Suppress("UNCHECKED_CAST")
                LeadAppointmentSync.applyLocal(lead as MutableMap<String, Any?>, updates)
            }

            val createdId = pendingCreatedAppointmentId
                ?: AppointmentAgenda.newAppointmentId().also { pendingCreatedAppointmentId = it }
            LeadAppointmentWriter.commit(
                db = db,
                leadId = docId,
                mutationFor = { _, _, _, _ ->
                    AppointmentSync.AppointmentMutation.SingleEdit(
                        previousStartMillis = previousStart,
                        previousLocation = previousLocation,
                        previousEventId = previousEventId,
                        newStartMillis = appointmentDate?.time,
                        newLocation = appointmentLocation,
                        newEventId = previousEventId,
                        nowMillis = System.currentTimeMillis(),
                        createdId = createdId,
                        baseUpdatedAtMillis = editedBaseUpdatedAt
                    )
                },
                extras = { _ -> updates },
                onSuccess = { result ->
                    val phoneToNotify = editPhone?.text.toString().trim().ifEmpty { docId }
                    val leadName = editName?.text.toString()

                    if (appointmentChanged && result.keptNewer) {
                        Toast.makeText(this, "A newer appointment change was kept.", Toast.LENGTH_LONG).show()
                    } else if (appointmentChanged) {
                        if (lead is MutableMap<*, *>) {
                            @Suppress("UNCHECKED_CAST")
                            LeadAppointmentSync.applyLocal(
                                lead as MutableMap<String, Any?>,
                                LeadAppointmentSync.fields(result.records, System.currentTimeMillis(), forUpdate = true)
                            )
                        }
                        scheduleAppointmentReminders(
                            records = result.records,
                            phoneOrLeadId = phoneToNotify,
                            leadName = leadName
                        )
                        val target = result.records.firstOrNull { it.id == result.targetId }
                        if (target != null) {
                            val clearing = target.status != AppointmentAgenda.AppointmentStatus.SCHEDULED
                            syncAppointmentAndStore(
                                leadId = docId,
                                leadName = leadName,
                                phone = editPhone?.text?.toString(),
                                startMillis = if (clearing) null else target.startMillis,
                                location = target.location,
                                existingEventId = target.eventId ?: previousEventId,
                                appointmentId = target.id,
                                leadMap = lead,
                                soleAppointment = result.records.size == 1
                            )
                        }
                    }
                    Toast.makeText(this, getString(R.string.msg_lead_updated), Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                },
                onFailure = {
                    Toast.makeText(this, "Couldn't save that lead.", Toast.LENGTH_LONG).show()
                }
            )
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
                        val spokenAppointment = updates["appointmentDate"] as? Timestamp
                        updates.remove("appointmentDate")
                        val spokenLocation = (updates.remove("appointmentLocation") as? String)
                            ?: doc.getString("appointmentLocation")
                        val previousStart = doc.getTimestamp("appointmentDate")?.toDate()?.time
                        val previousLocation = doc.getString("appointmentLocation")
                        val previousEventId = GoogleCalendarManager.readStoredEventId(doc.get("googleCalendarEventId"))
                        val createdId = AppointmentAgenda.newAppointmentId()
                        if (spokenAppointment != null) {
                            LeadAppointmentWriter.commit(
                                db = db,
                                leadId = leadId,
                                mutationFor = { fresh, _, _, _ ->
                                    val preview = AppointmentAgenda.describeSingleAppointmentEdit(
                                        leadId = leadId,
                                        existing = fresh,
                                        previousStartMillis = previousStart,
                                        previousLocation = previousLocation,
                                        previousEventId = previousEventId,
                                        newStartMillis = spokenAppointment.toDate().time,
                                        newLocation = spokenLocation,
                                        newEventId = previousEventId,
                                        nowMillis = System.currentTimeMillis(),
                                        createdId = createdId
                                    )
                                    val baseUpdatedAt = fresh.firstOrNull { it.id == preview.editedId }?.updatedAtMillis ?: 0L
                                    AppointmentSync.AppointmentMutation.SingleEdit(
                                        previousStartMillis = previousStart,
                                        previousLocation = previousLocation,
                                        previousEventId = previousEventId,
                                        newStartMillis = spokenAppointment.toDate().time,
                                        newLocation = spokenLocation,
                                        newEventId = previousEventId,
                                        nowMillis = System.currentTimeMillis(),
                                        createdId = createdId,
                                        baseUpdatedAtMillis = baseUpdatedAt
                                    )
                                },
                                extras = { _ -> updates },
                                onSuccess = { result ->
                                    Toast.makeText(this@FollowUpActivity, "Lead updated via Voice!", Toast.LENGTH_SHORT).show()
                                    incrementDailyStat("followup_count")
                                    val target = result.records.firstOrNull { it.id == result.targetId }
                                    if (target != null && !result.keptNewer) {
                                        val phoneToNotify = ((updates["phone"] as? String) ?: doc.getString("phone"))
                                            .orEmpty()
                                            .ifBlank { leadId }
                                        scheduleAppointmentReminders(
                                            records = result.records,
                                            phoneOrLeadId = phoneToNotify,
                                            leadName = doc.getString("name").orEmpty()
                                        )
                                        syncAppointmentAndStore(
                                            leadId = leadId,
                                            leadName = doc.getString("name").orEmpty(),
                                            phone = (updates["phone"] as? String) ?: doc.getString("phone"),
                                            startMillis = target.startMillis,
                                            location = target.location,
                                            existingEventId = target.eventId ?: previousEventId,
                                            appointmentId = target.id,
                                            leadMap = null,
                                            soleAppointment = result.records.size == 1
                                        )
                                    }
                                },
                                onFailure = {
                                    Toast.makeText(this@FollowUpActivity, "Couldn't save that lead.", Toast.LENGTH_LONG).show()
                                }
                            )
                        } else {
                            db.collection("leads").document(leadId).update(updates).addOnSuccessListener {
                                Toast.makeText(this@FollowUpActivity, "Lead updated via Voice!", Toast.LENGTH_SHORT).show()
                                incrementDailyStat("followup_count")
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

    private fun scheduleAppointmentReminders(
        records: List<AppointmentAgenda.AppointmentRecord>,
        phoneOrLeadId: String,
        leadName: String
    ) {
        val legacyKey = AppointmentAgenda.legacyAppointmentReminderKey(phoneOrLeadId)
        records.forEach { record ->
            val change = AppointmentAgenda.reminderChange(record, legacyKey)
            change.cancelKeys.forEach { key -> ReminderReceiver.cancelReminder(this, key) }
            val at = change.scheduleAtMillis
            val key = change.scheduleKey
            if (key != null && at != null) {
                ReminderReceiver.scheduleReminder(this, key, "Appointment: $leadName", at)
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
        appointmentId: String? = null,
        leadMap: Map<String, Any>?,
        soleAppointment: Boolean = false
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
                existingEventId = existingEventId,
                appointmentId = appointmentId,
                soleAppointmentForLead = soleAppointment
            ) { result ->
                when (result) {
                    is GoogleCalendarManager.AppointmentSyncResult.Linked -> {
                        storeSyncedEventId(leadId, appointmentId, result.eventId, leadMap)
                    }
                    GoogleCalendarManager.AppointmentSyncResult.Cleared -> {
                        if (!appointmentId.isNullOrBlank()) {
                            storeSyncedEventId(leadId, appointmentId, null, leadMap)
                        } else {
                            db.collection("leads").document(leadId)
                                .update("googleCalendarEventId", FieldValue.delete())
                            (leadMap as? MutableMap<String, Any?>)?.remove("googleCalendarEventId")
                        }
                    }
                    GoogleCalendarManager.AppointmentSyncResult.Skipped -> Unit
                    is GoogleCalendarManager.AppointmentSyncResult.Failed -> {
                        Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
                    }
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

    private fun storeSyncedEventId(
        leadId: String,
        appointmentId: String?,
        eventId: Long?,
        leadMap: Map<String, Any>?
    ) {
        if (appointmentId.isNullOrBlank()) {
            if (eventId != null && eventId > 0L) {
                db.collection("leads").document(leadId).update("googleCalendarEventId", eventId)
                (leadMap as? MutableMap<String, Any?>)?.put("googleCalendarEventId", eventId)
            }
            return
        }
        LeadAppointmentWriter.commit(
            db = db,
            leadId = leadId,
            mutationFor = { _, _, _, _ ->
                AppointmentSync.AppointmentMutation.SetEventId(appointmentId, eventId)
            },
            onSuccess = { result ->
                val mirror = AppointmentAgenda.legacyMirror(result.records, System.currentTimeMillis())
                val local = leadMap as? MutableMap<String, Any?> ?: return@commit
                if (mirror.eventId != null) local["googleCalendarEventId"] = mirror.eventId
                else local.remove("googleCalendarEventId")
                if (mirror.startMillis != null) local["appointmentDate"] = Timestamp(Date(mirror.startMillis))
                else local.remove("appointmentDate")
                if (!mirror.location.isNullOrBlank()) local["appointmentLocation"] = mirror.location
                else local.remove("appointmentLocation")
            },
            onFailure = {
                Toast.makeText(this, "The calendar changed, but RCRM couldn't store the event link.", Toast.LENGTH_LONG).show()
            }
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        statsListener?.remove()
    }

    private fun parseNotes(raw: String): List<DatedNote> {
        return ContactActivityTimeline.parseNotes(raw)
    }
}
