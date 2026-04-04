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
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
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
    private val selectedLeads = mutableSetOf<String>()
    private var statsListener: ListenerRegistration? = null
    private var currentSearchQuery = ""
    private var currentCategoryFilter = "All"
    private var showOnlySideKick = false

    private var applyVoiceLogToDialog: ((org.json.JSONObject, String) -> Unit)? = null

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
            selectedLeads, 
            { updateUI() },
            { id, isPinned ->
                db.collection("leads").document(id).update("isPinned", isPinned)
                    .addOnSuccessListener {
                        val lead = allLeads.find { it["id"] == id } as? MutableMap<String, Any>
                        if (lead != null) {
                            lead["isPinned"] = isPinned
                            applyFilters()
                        }
                    }
            }
        ) { lead -> showLeadDetailsDialog(lead) }
        binding.rvFollowUps.adapter = adapter

        binding.btnBack.setOnClickListener { finish() }
        binding.btnBulkDelete.setOnClickListener { performBulkDelete() }
        binding.btnCancelBulk.setOnClickListener { 
            selectedLeads.clear()
            adapter.notifyDataSetChanged()
            updateUI()
        }

        binding.btnBulkDictate.setOnClickListener {
            dictatingForLeadId = selectedLeads.firstOrNull()
            if (dictatingForLeadId != null) {
                val intent = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                intent.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                intent.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                intent.putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Speak your updates for this lead...")
                try { speechRecognizerLauncherList.launch(intent) } catch (e: Exception) { Toast.makeText(this, "Speech recognition not available", Toast.LENGTH_SHORT).show() }
            }
        }

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
        binding.bulkActionBar.visibility = if (selectedLeads.isEmpty()) View.GONE else View.VISIBLE
        binding.btnBulkDictate.visibility = if (selectedLeads.size == 1) View.VISIBLE else View.GONE
    }

    private fun performBulkDelete() {
        AlertDialog.Builder(this)
            .setTitle("Delete Leads")
            .setMessage("Are you sure you want to delete the selected lead(s)? This action cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                val batch = db.batch()
                selectedLeads.forEach { id ->
                    batch.delete(db.collection("leads").document(id))
                }
                batch.commit().addOnSuccessListener {
                    Toast.makeText(this, "Leads deleted successfully.", Toast.LENGTH_SHORT).show()
                    allLeads.removeAll { lead -> selectedLeads.contains(lead["id"]) }
                    selectedLeads.clear()
                    applyFilters()
                }.addOnFailureListener { e ->
                    Log.e(TAG, "Failed to delete leads", e)
                    Toast.makeText(this, "Failed to delete leads.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showLeadDetailsDialog(lead: Map<String, Any>) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_lead_details, null)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        val editName = dialogView.findViewById<EditText>(R.id.editDetailName)
        val editPhone = dialogView.findViewById<EditText>(R.id.editDetailPhone)
        val editEmail = dialogView.findViewById<EditText>(R.id.editDetailEmail)
        val editAddress = dialogView.findViewById<EditText>(R.id.editDetailAddress)
        val editCompany = dialogView.findViewById<EditText>(R.id.editDetailCompany)
        val editJobTitle = dialogView.findViewById<EditText>(R.id.editDetailJobTitle)
        
        val btnCallDetail = dialogView.findViewById<ImageButton>(R.id.btnCallDetail)
        val btnSmsDetail = dialogView.findViewById<ImageButton>(R.id.btnSmsDetail)
        val btnEmailDetail = dialogView.findViewById<ImageButton>(R.id.btnEmailDetail)
        val btnMapDetail = dialogView.findViewById<ImageButton>(R.id.btnMapDetail)

        val cbRecruit = dialogView.findViewById<CheckBox>(R.id.cbRecruitDetail)
        val cbProspect = dialogView.findViewById<CheckBox>(R.id.cbProspectDetail)
        val cbClient = dialogView.findViewById<CheckBox>(R.id.cbClientDetail)
        val subCatLayout = dialogView.findViewById<LinearLayout>(R.id.clientSubCategoryLayoutDetail)
        val cbInv = dialogView.findViewById<CheckBox>(R.id.cbInvestmentDetail)
        val cbLife = dialogView.findViewById<CheckBox>(R.id.cbLifeInsuranceDetail)

        val headerTargetMarket = dialogView.findViewById<LinearLayout>(R.id.headerTargetMarket)
        val containerTargetMarketCheckboxes = dialogView.findViewById<LinearLayout>(R.id.containerTargetMarketCheckboxes)
        val tvToggleTargetMarket = dialogView.findViewById<TextView>(R.id.tvToggleTargetMarket)
        
        val cbTmMarried = dialogView.findViewById<CheckBox>(R.id.cbTmMarried)
        val cbTmAge = dialogView.findViewById<CheckBox>(R.id.cbTmAge)
        val cbTmChildren = dialogView.findViewById<CheckBox>(R.id.cbTmChildren)
        val cbTmHomeowner = dialogView.findViewById<CheckBox>(R.id.cbTmHomeowner)
        val cbTmOccupation = dialogView.findViewById<CheckBox>(R.id.cbTmOccupation)
        val dialTargetMarket = dialogView.findViewById<com.google.android.material.progressindicator.CircularProgressIndicator>(R.id.dialTargetMarket)
        val tvTargetMarketScore = dialogView.findViewById<TextView>(R.id.tvTargetMarketScore)

        val headerExtendedDetails = dialogView.findViewById<LinearLayout>(R.id.headerExtendedDetails)
        val containerExtendedDetails = dialogView.findViewById<LinearLayout>(R.id.containerExtendedDetails)
        val tvToggleExtended = dialogView.findViewById<TextView>(R.id.tvToggleExtended)

        val notesContainer = dialogView.findViewById<LinearLayout>(R.id.notesContainer)
        val btnAddDatedNote = dialogView.findViewById<View>(R.id.btnAddDatedNote)
        
        val btnSetReminder = dialogView.findViewById<Button>(R.id.btnSetReminder)
        val btnAddCalendar = dialogView.findViewById<Button>(R.id.btnAddCalendar)
        val btnMarkAppointmentDetail = dialogView.findViewById<Button>(R.id.btnMarkAppointmentDetail)
        val btnSave = dialogView.findViewById<Button>(R.id.btnSaveChanges)

        val sideKickSuggestionContainer = dialogView.findViewById<View>(R.id.sideKickSuggestionContainer)
        val tvSideKickTip = dialogView.findViewById<TextView>(R.id.tvSideKickTip)
        val btnThumbsUp = dialogView.findViewById<ImageButton>(R.id.btnThumbsUp)
        val btnThumbsDown = dialogView.findViewById<ImageButton>(R.id.btnThumbsDown)
        val btnDismissSideKick = dialogView.findViewById<Button>(R.id.btnDismissSideKick)

        val docId = lead["id"] as String
        val sideKickTip = lead["sideKickSuggestion"] as? String

        if (sideKickTip != null) {
            sideKickSuggestionContainer.visibility = View.VISIBLE
            tvSideKickTip.text = "💡 Side-Kick Tip: $sideKickTip"
            
            btnDismissSideKick.setOnClickListener {
                db.collection("leads").document(docId).update("sideKickSuggestion", FieldValue.delete())
                    .addOnSuccessListener {
                        Toast.makeText(this, "Side-Kick tip dismissed.", Toast.LENGTH_SHORT).show()
                        sideKickSuggestionContainer.visibility = View.GONE
                    }
            }

            btnThumbsUp.setOnClickListener {
                db.collection("leads").document(docId).update("aiFeedback", "positive")
                Toast.makeText(this, "Feedback recorded: Positive!", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            btnThumbsDown.setOnClickListener {
                db.collection("leads").document(docId).update("aiFeedback", "negative")
                Toast.makeText(this, "Feedback recorded: Negative.", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        } else {
            sideKickSuggestionContainer.visibility = View.GONE
        }

        val containerSmsIntro = dialogView.findViewById<LinearLayout>(R.id.containerSmsIntro)
        val tvToggleSms = dialogView.findViewById<TextView>(R.id.tvToggleSms)
        val headerSmsIntro = dialogView.findViewById<LinearLayout>(R.id.headerSmsIntro)
        
        headerTargetMarket?.setOnClickListener {
            android.transition.TransitionManager.beginDelayedTransition(dialogView as ViewGroup)
            if (containerTargetMarketCheckboxes?.visibility == View.GONE) {
                containerTargetMarketCheckboxes.visibility = View.VISIBLE
                tvToggleTargetMarket?.text = "Hide ▲"
            } else {
                containerTargetMarketCheckboxes?.visibility = View.GONE
                tvToggleTargetMarket?.text = "Edit ▼"
            }
        }

        headerExtendedDetails?.setOnClickListener {
            android.transition.TransitionManager.beginDelayedTransition(dialogView as ViewGroup)
            if (containerExtendedDetails?.visibility == View.GONE) {
                containerExtendedDetails.visibility = View.VISIBLE
                tvToggleExtended?.text = "Hide ▲"
            } else {
                containerExtendedDetails?.visibility = View.GONE
                tvToggleExtended?.text = "Show ▼"
            }
        }
        
        headerSmsIntro?.setOnClickListener {
            android.transition.TransitionManager.beginDelayedTransition(dialogView as ViewGroup)
            if (containerSmsIntro?.visibility == View.GONE) {
                containerSmsIntro.visibility = View.VISIBLE
                tvToggleSms?.text = "Hide ▲"
            } else {
                containerSmsIntro?.visibility = View.GONE
                tvToggleSms?.text = "Show ▼"
            }
        }

        val etIntroText = dialogView.findViewById<EditText>(R.id.etIntroText)
        val btnDefaultIntro = dialogView.findViewById<Button>(R.id.btnDefaultIntro)
        val btnManagePresets = dialogView.findViewById<Button>(R.id.btnManagePresets)
        val btnSendIntroAction = dialogView.findViewById<Button>(R.id.btnSendIntroAction)
        val btnAIGenerate = dialogView.findViewById<Button>(R.id.btnAIGenerate)

        btnAIGenerate.setOnClickListener {
            val contactName = editName.text.toString()
            val history = parseNotes(lead["notes"] as? String ?: "").take(5).joinToString("\n") { "- ${it.content}" }
            
            btnAIGenerate.isEnabled = false
            btnAIGenerate.text = "Drafting..."
            
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val prompt = "You are an assistant for a Primerica agent. Draft a short, warm, and professional SMS message to $contactName. " +
                                 "Use the following interaction history for context. Do not mention the notes directly. " +
                                 "Keep it under 2 sentences and ready to send.\nHistory:\n$history"
                    val response = GeminiApiClient.generativeModel.generateContent(prompt)
                    
                    withContext(Dispatchers.Main) {
                        etIntroText.setText(response.text?.trim() ?: "Could not generate message.")
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

        val tvReminderValue = dialogView.findViewById<TextView>(R.id.tvReminderValue)
        val btnEditReminderIcon = dialogView.findViewById<ImageButton>(R.id.btnEditReminderIcon)
        val btnDeleteReminderIcon = dialogView.findViewById<ImageButton>(R.id.btnDeleteReminderIcon)

        editPhone.addTextChangedListener(android.telephony.PhoneNumberFormattingTextWatcher())

        editName.setText(lead["name"] as? String ?: "")
        editPhone.setText(lead["phone"] as? String ?: "")
        editEmail.setText(lead["email"] as? String ?: "")
        editAddress.setText(lead["address"] as? String ?: "")
        editCompany.setText(lead["company"] as? String ?: "")
        editJobTitle.setText(lead["jobTitle"] as? String ?: "")
        
        btnCallDetail.setOnClickListener {
            val phone = editPhone.text.toString().trim()
            if (phone.isNotEmpty()) {
                val intent = Intent(Intent.ACTION_DIAL, "tel:$phone".toUri())
                startActivity(intent)
            } else {
                Toast.makeText(this, "No phone number", Toast.LENGTH_SHORT).show()
            }
        }

        btnSmsDetail?.setOnClickListener {
            val phone = editPhone.text.toString().trim()
            if (phone.isNotEmpty()) {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("sms:$phone"))
                startActivity(intent)
            } else {
                Toast.makeText(this, "No phone number", Toast.LENGTH_SHORT).show()
            }
        }

        btnEmailDetail?.setOnClickListener {
            val email = editEmail.text.toString().trim()
            if (email.isNotEmpty()) {
                val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$email"))
                try {
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(this, "No email app found", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "No email address", Toast.LENGTH_SHORT).show()
            }
        }

        btnMapDetail?.setOnClickListener {
            val address = editAddress.text.toString().trim()
            if (address.isNotEmpty()) {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(address)}"))
                startActivity(intent)
            } else {
                Toast.makeText(this, "No address", Toast.LENGTH_SHORT).show()
            }
        }

        val currentCat = lead["category"] as? String ?: ""
        cbRecruit.isChecked = currentCat.contains(getString(R.string.category_recruit))
        cbProspect.isChecked = currentCat.contains(getString(R.string.category_prospect))
        cbClient.isChecked = currentCat.contains(getString(R.string.category_client))
        cbInv.isChecked = currentCat.contains("Investment")
        cbLife.isChecked = currentCat.contains("Life Insurance")
        subCatLayout.visibility = if (cbClient.isChecked) View.VISIBLE else View.GONE

        cbClient.setOnCheckedChangeListener { _, isChecked ->
            subCatLayout.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        val targetMarketRaw = lead["targetMarket"]
        if (targetMarketRaw is String) {
            cbTmMarried.isChecked = targetMarketRaw.contains("Married")
            cbTmAge.isChecked = targetMarketRaw.contains("Age 25-55")
            cbTmChildren.isChecked = targetMarketRaw.contains("Children")
            cbTmHomeowner.isChecked = targetMarketRaw.contains("Homeowner")
            cbTmOccupation.isChecked = targetMarketRaw.contains("Occupation")
        } else if (targetMarketRaw is Map<*, *>) {
            cbTmMarried.isChecked = targetMarketRaw["married"] as? Boolean ?: false
            cbTmAge.isChecked = targetMarketRaw["ageRange"] as? Boolean ?: false
            cbTmChildren.isChecked = targetMarketRaw["children"] as? Boolean ?: false
            cbTmHomeowner.isChecked = targetMarketRaw["homeowner"] as? Boolean ?: false
            cbTmOccupation.isChecked = targetMarketRaw["occupation"] as? Boolean ?: false
        } else {
            cbTmMarried.isChecked = false
            cbTmAge.isChecked = false
            cbTmChildren.isChecked = false
            cbTmHomeowner.isChecked = false
            cbTmOccupation.isChecked = false
        }

        fun updateTargetMarketScore() {
            var points = 0
            if (cbTmMarried.isChecked) points++
            if (cbTmAge.isChecked) points++
            if (cbTmChildren.isChecked) points++
            if (cbTmHomeowner.isChecked) points++
            if (cbTmOccupation.isChecked) points++

            val percentage = when (points) {
                1 -> 2
                2 -> 9
                3 -> 19
                4 -> 42
                5 -> 95
                else -> 0
            }

            dialTargetMarket.setProgressCompat(percentage, true)
            tvTargetMarketScore.text = "$percentage%"

            val color = when {
                percentage >= 80 -> Color.parseColor("#4CAF50")
                percentage >= 40 -> Color.parseColor("#FF9800")
                else -> Color.parseColor("#F44336")
            }
            dialTargetMarket.setIndicatorColor(color)
            tvTargetMarketScore.setTextColor(color)
        }
        updateTargetMarketScore()

        cbTmMarried.setOnCheckedChangeListener { _, _ -> updateTargetMarketScore() }
        cbTmAge.setOnCheckedChangeListener { _, _ -> updateTargetMarketScore() }
        cbTmChildren.setOnCheckedChangeListener { _, _ -> updateTargetMarketScore() }
        cbTmHomeowner.setOnCheckedChangeListener { _, _ -> updateTargetMarketScore() }
        cbTmOccupation.setOnCheckedChangeListener { _, _ -> updateTargetMarketScore() }

        val birthdayStamp = lead["birthday"] as? Timestamp
        val tvBirthdayDisplay = dialogView.findViewById<TextView>(R.id.tvBirthdayDisplay)
        val btnSetBirthday = dialogView.findViewById<Button>(R.id.btnSetBirthday)
        var selectedBirthday: Date? = birthdayStamp?.toDate()

        fun updateBirthdayUI() {
            if (selectedBirthday != null) {
                val sdf = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
                tvBirthdayDisplay.text = "Birthday: ${sdf.format(selectedBirthday!!)}"
                btnSetBirthday.text = "📅 Change"
            } else {
                tvBirthdayDisplay.text = "Birthday: None"
                btnSetBirthday.text = "📅 Add Birthday"
            }
        }
        updateBirthdayUI()

        btnSetBirthday.setOnClickListener {
            val cal = Calendar.getInstance()
            selectedBirthday?.let { cal.time = it }
            DatePickerDialog(this, { _, y, m, d ->
                cal.set(y, m, d)
                selectedBirthday = cal.time
                updateBirthdayUI()
            }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
        }

        val initialNotes = parseNotes(lead["notes"] as? String ?: "")
        val originalNotesCount = initialNotes.size
        val notesList = initialNotes.toMutableList()
        fun refreshNotesUI() {
            notesContainer.removeAllViews()
            notesList.forEachIndexed { index, note ->
                val view = LayoutInflater.from(this).inflate(R.layout.item_dated_note, notesContainer, false)
                view.findViewById<TextView>(R.id.tvNoteDate).text = note.date
                view.findViewById<TextView>(R.id.tvNoteContent).text = note.content
                
                view.findViewById<ImageButton>(R.id.btnEditNote).setOnClickListener {
                    val input = EditText(this).apply { setText(note.content) }
                    AlertDialog.Builder(this).setTitle("Edit Note").setView(input)
                        .setPositiveButton("Save") { _, _ ->
                            notesList[index] = note.copy(content = input.text.toString())
                            refreshNotesUI()
                        }.show()
                }
                view.findViewById<ImageButton>(R.id.btnDeleteNote).setOnClickListener {
                    notesList.removeAt(index)
                    refreshNotesUI()
                }
                notesContainer.addView(view)
            }
        }
        refreshNotesUI()


        val notesParent = btnAddDatedNote.parent as? ViewGroup
        if (notesParent != null) {
            val index = notesParent.indexOfChild(btnAddDatedNote)
            notesParent.removeView(btnAddDatedNote)
            
            val wrapper = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = btnAddDatedNote.layoutParams
            }
            
            btnAddDatedNote.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 8 }
            
            val btnVoiceLog = Button(this).apply {
                text = "🎤 Dictate Log"
                backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#FF9800"))
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = 8 }
            }
            
            wrapper.addView(btnAddDatedNote)
            wrapper.addView(btnVoiceLog)
            notesParent.addView(wrapper, index)
            
            btnVoiceLog.setOnClickListener {
                btnVoiceLog.isEnabled = false
                // Re-enable button as soon as AI processing completes (not just on dialog close)
                onVoiceLogComplete = { btnVoiceLog.isEnabled = true }
                val intent = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                intent.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                intent.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                intent.putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Speak your updates...")
                try {
                    speechRecognizerLauncherDetails.launch(intent)
                } catch (e: Exception) {
                    btnVoiceLog.isEnabled = true
                    onVoiceLogComplete = null
                    Toast.makeText(this, "Speech recognition not available", Toast.LENGTH_SHORT).show()
                }
            }
        }

        btnAddDatedNote.setOnClickListener {
            val input = EditText(this).apply { hint = "Add a new note..." }
            AlertDialog.Builder(this).setTitle("Add Dated Note").setView(input)
                .setPositiveButton("Add") { _, _ ->
                    val content = input.text.toString().trim()
                    if (content.isNotEmpty()) {
                        val date = SimpleDateFormat("MMM dd, yyyy hh:mm a", Locale.getDefault()).format(Date())
                        notesList.add(0, DatedNote(date, content))
                        refreshNotesUI()
                    }
                }.show()
        }

        var selectedFollowUpDate: Date? = (lead["followUpDate"] as? Timestamp)?.toDate()
        fun updateReminderUI() {
            if (selectedFollowUpDate != null) {
                val sdf = SimpleDateFormat("MMM dd, yyyy hh:mm a", Locale.getDefault())
                tvReminderValue.text = sdf.format(selectedFollowUpDate!!)
                btnSetReminder.text = getString(R.string.btn_change_reminder)
                btnEditReminderIcon.visibility = View.VISIBLE
                btnDeleteReminderIcon.visibility = View.VISIBLE
            } else {
                tvReminderValue.text = getString(R.string.reminder_none)
                btnSetReminder.text = getString(R.string.btn_set_reminder)
                btnEditReminderIcon.visibility = View.GONE
                btnDeleteReminderIcon.visibility = View.GONE
            }
        }
        updateReminderUI()

        val reminderAction = View.OnClickListener {
            val cal = Calendar.getInstance()
            selectedFollowUpDate?.let { cal.time = it }
            DatePickerDialog(this, { _, y, m, d ->
                cal.set(y, m, d)
                TimePickerDialog(this, { _, hh, mm ->
                    cal.set(Calendar.HOUR_OF_DAY, hh)
                    cal.set(Calendar.MINUTE, mm)
                    selectedFollowUpDate = cal.time
                    updateReminderUI()
                }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), false).show()
            }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
        }
        btnSetReminder.setOnClickListener(reminderAction)
        btnEditReminderIcon.setOnClickListener(reminderAction)

        btnDeleteReminderIcon.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.dialog_delete_reminder_title))
                .setMessage(getString(R.string.dialog_delete_reminder_msg))
                .setPositiveButton("Delete") { _, _ ->
                    selectedFollowUpDate = null
                    updateReminderUI()
                }.setNegativeButton("Cancel", null).show()
        }

        var appointmentDate: Date? = (lead["appointmentDate"] as? Timestamp)?.toDate()
        var appointmentLocation: String? = lead["appointmentLocation"] as? String

        fun updateAppointmentButtonUI() {
            if (appointmentDate != null) {
                val summarySdf = SimpleDateFormat("MMM dd h:mm a", Locale.getDefault())
                btnMarkAppointmentDetail?.text = "Appt: ${summarySdf.format(appointmentDate!!)}"
            } else {
                btnMarkAppointmentDetail?.text = "Schedule Appointment"
            }
        }
        updateAppointmentButtonUI()

        btnMarkAppointmentDetail?.setOnClickListener {
            val apptDialogView = layoutInflater.inflate(R.layout.dialog_appointment, null)
            val apptDialog = AlertDialog.Builder(this@FollowUpActivity).setView(apptDialogView).create()
            apptDialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

            val btnApptDate = apptDialogView.findViewById<Button>(R.id.btnApptDate)
            val btnApptTime = apptDialogView.findViewById<Button>(R.id.btnApptTime)
            val tvSelectedDateTime = apptDialogView.findViewById<TextView>(R.id.tvSelectedDateTime)
            
            val rgApptLocation = apptDialogView.findViewById<RadioGroup>(R.id.rgApptLocation)
            val rbOffice = apptDialogView.findViewById<RadioButton>(R.id.rbOffice)
            val rbCustom = apptDialogView.findViewById<RadioButton>(R.id.rbCustom)
            
            val llOfficeAddressConfig = apptDialogView.findViewById<LinearLayout>(R.id.llOfficeAddressConfig)
            val tvCurrentOffice = apptDialogView.findViewById<TextView>(R.id.tvCurrentOffice)
            val btnEditOffice = apptDialogView.findViewById<Button>(R.id.btnEditOffice)
            
            val tilCustomAddress = apptDialogView.findViewById<View>(R.id.tilCustomAddress)
            val etCustomAddress = apptDialogView.findViewById<EditText>(R.id.etCustomAddress)
            
            val btnCancelAppt = apptDialogView.findViewById<Button>(R.id.btnCancelAppt)
            val btnSaveAppt = apptDialogView.findViewById<Button>(R.id.btnSaveAppt)
            
            val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
            var defaultOfficeAddress = prefs.getString("default_office_address", "") ?: ""
            
            var tempCal: Calendar? = if (appointmentDate != null) Calendar.getInstance().apply { time = appointmentDate!! } else null
            
            val sdfDate = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
            val sdfTime = SimpleDateFormat("h:mm a", Locale.getDefault())

            fun updateDateTimeUI() {
                if (tempCal != null) {
                    tvSelectedDateTime?.text = "${sdfDate.format(tempCal!!.time)} at ${sdfTime.format(tempCal!!.time)}"
                } else {
                    tvSelectedDateTime?.text = "No date/time selected"
                }
            }
            
            fun updateOfficeUI() {
                if (defaultOfficeAddress.isNotEmpty()) {
                    tvCurrentOffice?.text = defaultOfficeAddress
                } else {
                    tvCurrentOffice?.text = "No address set"
                }
            }
            
            updateDateTimeUI()
            updateOfficeUI()

            rgApptLocation?.setOnCheckedChangeListener { _, checkedId ->
                if (checkedId == R.id.rbOffice) {
                    llOfficeAddressConfig?.visibility = View.VISIBLE
                    tilCustomAddress?.visibility = View.GONE
                } else {
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

        // Assign lambda AFTER all local dialog variables are declared so the closure
        // can correctly reference selectedFollowUpDate, appointmentDate, notesList, etc.
        applyVoiceLogToDialog = { json, spokenText ->
            if (json.has("phone") && !json.isNull("phone")) {
                editPhone.setText(json.getString("phone"))
            }
            if (json.has("email") && !json.isNull("email")) {
                editEmail.setText(json.getString("email"))
            }
            if (json.has("category") && !json.isNull("category")) {
                val cat = json.getString("category")
                cbRecruit.isChecked = cat.contains("Recruit", true)
                cbProspect.isChecked = cat.contains("Prospect", true)
                cbClient.isChecked = cat.contains("Client", true)
            }
            if (json.has("targetMarket") && !json.isNull("targetMarket")) {
                val tmArray = json.getJSONArray("targetMarket")
                for (i in 0 until tmArray.length()) {
                    val trait = tmArray.getString(i)
                    if (trait.contains("Married", true)) cbTmMarried.isChecked = true
                    if (trait.contains("Age", true)) cbTmAge.isChecked = true
                    if (trait.contains("Children", true)) cbTmChildren.isChecked = true
                    if (trait.contains("Homeowner", true)) cbTmHomeowner.isChecked = true
                    if (trait.contains("Occupation", true)) cbTmOccupation.isChecked = true
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
            notesList.add(0, DatedNote(SimpleDateFormat("MMM dd, yyyy hh:mm a", Locale.getDefault()).format(Date()), "[Voice Log]: $extractedNotes"))
            refreshNotesUI()
        }

        // Clear lambda on dismiss to prevent stale dialog view references from being
        // applied to a different lead if a delayed result arrives.
        dialog.setOnDismissListener {
            applyVoiceLogToDialog = null
            onVoiceLogComplete = null
        }

        btnMarkAppointmentDetail?.setOnLongClickListener {
            if (appointmentDate != null) {
                AlertDialog.Builder(this@FollowUpActivity)
                    .setTitle("Remove Appointment?")
                    .setMessage("Do you want to clear this scheduled appointment?")
                    .setPositiveButton("Remove") { _, _ ->
                        appointmentDate = null
                        appointmentLocation = null
                        updateAppointmentButtonUI()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            true
        }

        btnDefaultIntro.setOnClickListener {
            val firstName = editName.text.toString().split(" ").firstOrNull() ?: ""
            val defaultMsg = getSharedPreferences("settings", Context.MODE_PRIVATE).getString("default_intro_sms", null)
                ?: "Hi $NAME_TOKEN, just following up! Looking forward to connecting."
            etIntroText.setText(defaultMsg.replace(NAME_TOKEN, firstName))
        }

        btnManagePresets.setOnClickListener { showPresetsDialog(etIntroText, editName) }

        btnSendIntroAction.setOnClickListener {
            val msg = etIntroText.text.toString()
            val phone = editPhone.text.toString()
            if (msg.isNotEmpty() && phone.isNotEmpty()) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("sms:$phone")).apply { putExtra("sms_body", msg) })
            }
        }

        btnSave.setOnClickListener {
            val updatedCats = mutableListOf<String>()
            if (cbRecruit.isChecked) updatedCats.add(getString(R.string.category_recruit))
            if (cbProspect.isChecked) updatedCats.add(getString(R.string.category_prospect))
            if (cbClient.isChecked) {
                updatedCats.add(getString(R.string.category_client))
                if (cbInv.isChecked) updatedCats.add("Investment")
                if (cbLife.isChecked) updatedCats.add("Life Insurance")
            }
            
            val updatedNotes = notesList.joinToString("\n\n") { "[${it.date}]: ${it.content}" }
            val tmList = mutableListOf<String>()
            if (cbTmMarried.isChecked) tmList.add("Married")
            if (cbTmAge.isChecked) tmList.add("Age 25-55")
            if (cbTmChildren.isChecked) tmList.add("Children")
            if (cbTmHomeowner.isChecked) tmList.add("Homeowner")
            if (cbTmOccupation.isChecked) tmList.add("Occupation")

            val updates: Map<String, Any?> = hashMapOf(
                "name" to editName.text.toString(),
                "phone" to editPhone.text.toString(),
                "email" to editEmail.text.toString(),
                "address" to editAddress.text.toString(),
                "company" to editCompany.text.toString(),
                "jobTitle" to editJobTitle.text.toString(),
                "category" to updatedCats.joinToString(", "),
                "notes" to updatedNotes,
                "targetMarket" to tmList.joinToString(", "),
                "followUpDate" to selectedFollowUpDate?.let { Timestamp(it) },
                "birthday" to selectedBirthday?.let { Timestamp(it) },
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
                
                val phoneToNotify = editPhone.text.toString()
                if (appointmentDate != null && phoneToNotify.isNotEmpty()) {
                    ReminderReceiver.scheduleReminder(this, phoneToNotify, "Appointment: ${editName.text}", appointmentDate!!.time)
                } else if (selectedFollowUpDate != null && phoneToNotify.isNotEmpty()) {
                    ReminderReceiver.scheduleReminder(this, phoneToNotify, editName.text.toString(), selectedFollowUpDate!!.time)
                } else if (phoneToNotify.isNotEmpty()) {
                    ReminderReceiver.cancelReminder(this, phoneToNotify)
                }
                
                Toast.makeText(this, getString(R.string.msg_lead_updated), Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }
        
        btnAddCalendar.setOnClickListener {
            val phone = editPhone.text.toString()
            val name = editName.text.toString()
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
        AlertDialog.Builder(this).setTitle(getString(R.string.btn_presets_dropdown))
            .setItems(presets.toTypedArray()) { _, which ->
                val firstName = nameSource.text.toString().split(" ").firstOrNull() ?: ""
                target.setText(presets[which].replace(NAME_TOKEN, firstName))
            }.show()
    }

    private data class DatedNote(val date: String, val content: String)

    private fun parseNotes(raw: String): List<DatedNote> {
        if (raw.isBlank()) return emptyList()
        val regex = Regex("\\[(.*?)\\]: (.*?)(?=\\s*\\[|\$)", RegexOption.DOT_MATCHES_ALL)
        return regex.findAll(raw).map { DatedNote(it.groupValues[1], it.groupValues[2].trim()) }.toList()
    }

    private fun incrementDailyStat(field: String) {
        val userId = auth.currentUser?.uid ?: return
        val dateStr = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
        
        val statsPrefs = getSharedPreferences("daily_stats_local", Context.MODE_PRIVATE)
        val currentLocalCount = statsPrefs.getInt("${dateStr}_$field", 0)
        val newCount = currentLocalCount + 1
        statsPrefs.edit { putInt("${dateStr}_$field", newCount) }

        if (field == "followup_count") {
            val currentGoal = statsPrefs.getInt("${dateStr}_followup_goal", 5)
            updateFollowUpProgressUI(newCount, currentGoal)
        }

        db.collection("user_settings").document(userId)
            .collection("daily_stats").document(dateStr)
            .set(hashMapOf(field to FieldValue.increment(1)), SetOptions.merge())
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to sync stat to Firestore", e)
            }
    }

    private fun loadLocalStats() {
        val dateStr = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
        val statsPrefs = getSharedPreferences("daily_stats_local", Context.MODE_PRIVATE)
        val currentFollowUpCount = statsPrefs.getInt("${dateStr}_followup_count", 0)
        val currentFollowUpGoal = statsPrefs.getInt("${dateStr}_followup_goal", 5)
        updateFollowUpProgressUI(currentFollowUpCount, currentFollowUpGoal)
    }

    private fun updateFollowUpProgressUI(count: Int, goal: Int) {
        binding.progressFollowUps.max = if (goal > 0) goal else 1
        binding.progressFollowUps.progress = count
        binding.tvFollowUpCountDisplay.text = count.toString()
        binding.tvFollowUpGoalDisplay.text = "Goal: $goal"
    }

    private fun listenToStats() {
        val userId = auth.currentUser?.uid ?: return
        val dateStr = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
        statsListener = db.collection("user_settings").document(userId)
            .collection("daily_stats").document(dateStr)
            .addSnapshotListener { doc, e ->
                if (e != null) return@addSnapshotListener
                if (doc != null && doc.exists()) {
                    val count = doc.getLong("followup_count")?.toInt() ?: 0
                    val goal = doc.getLong("followup_goal")?.toInt() ?: 5
                    
                    val statsPrefs = getSharedPreferences("daily_stats_local", Context.MODE_PRIVATE)
                    statsPrefs.edit {
                        putInt("${dateStr}_followup_count", count)
                        putInt("${dateStr}_followup_goal", goal)
                    }
                    updateFollowUpProgressUI(count, goal)
                }
            }
    }

    private var onVoiceLogComplete: (() -> Unit)? = null

    private fun processVoiceLogDetailsWithAI(spokenText: String) {
        Toast.makeText(this, "Processing Voice Log...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Truncate very long transcriptions to avoid exceeding API token limits,
                // which can happen with long dictations.
                val maxChars = 15000
                val truncatedText = if (spokenText.length > maxChars) {
                    spokenText.substring(0, maxChars) + "\n...[TRUNCATED]"
                } else {
                    spokenText
                }
                val prompt = """
                    You are a CRM assistant. Extract data from the following voice transcription.
                    Return ONLY a strict JSON object with these exact keys:
                    - "notes" (string, summary of the interaction)
                    - "phone" (string, extract phone number or null if none)
                    - "email" (string, extract email address or null if none)
                    - "category" (string, choose ONE from: "Recruit", "Prospect", "Client", or null)
                    - "followUpDate" (string, format "yyyy-MM-dd HH:mm" or null if no date mentioned)
                    - "appointmentDate" (string, format "yyyy-MM-dd HH:mm" or null if they mention booking/scheduling a meeting)
                    - "appointmentLocation" (string, null if none mentioned)
                    - "targetMarket" (array of strings, extract applicable traits from: "Married", "Age 25-55", "Children", "Homeowner", "Occupation" or empty array)
                    
                    Transcription: "$truncatedText"
                    
                    Important: The output MUST be a valid JSON object. No markdown, no backticks.
                """.trimIndent()
                
                val response = GeminiApiClient.generativeModel.generateContent(prompt)
                val rawText = response.text ?: "{}"
                val fenced = Regex("```(?:json)?\\s*([\\s\\S]+?)```", RegexOption.IGNORE_CASE).find(rawText)
                val jsonString = (fenced?.groupValues?.get(1) ?: rawText).trim()
                val json = org.json.JSONObject(jsonString)
                
                withContext(Dispatchers.Main) {
                    applyVoiceLogToDialog?.invoke(json, spokenText)
                    onVoiceLogComplete?.invoke()
                    onVoiceLogComplete = null
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@FollowUpActivity, "AI Error: ${e.message}", Toast.LENGTH_LONG).show()
                    val fallbackJson = org.json.JSONObject().apply { put("notes", spokenText) }
                    applyVoiceLogToDialog?.invoke(fallbackJson, spokenText)
                    onVoiceLogComplete?.invoke()
                    onVoiceLogComplete = null
                }
            }
        }
    }

    private fun processVoiceLogListWithAI(leadId: String, spokenText: String) {
        Toast.makeText(this, "Processing Voice Log...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Truncate very long transcriptions to avoid exceeding API token limits,
                // which can happen with long dictations.
                val maxChars = 15000
                val truncatedText = if (spokenText.length > maxChars) {
                    spokenText.substring(0, maxChars) + "\n...[TRUNCATED]"
                } else {
                    spokenText
                }
                val prompt = """
                    You are a CRM assistant. Extract data from the following voice transcription.
                    Return ONLY a strict JSON object with these exact keys:
                    - "notes" (string, summary of the interaction)
                    - "phone" (string, extract phone number or null if none)
                    - "email" (string, extract email address or null if none)
                    - "category" (string, choose ONE from: "Recruit", "Prospect", "Client", or null)
                    - "followUpDate" (string, format "yyyy-MM-dd HH:mm" or null if no date mentioned)
                    - "appointmentDate" (string, format "yyyy-MM-dd HH:mm" or null if they mention booking/scheduling a meeting)
                    - "appointmentLocation" (string, null if none mentioned)
                    - "targetMarket" (array of strings, extract applicable traits from: "Married", "Age 25-55", "Children", "Homeowner", "Occupation" or empty array)
                    
                    Transcription: "$truncatedText"
                    
                    Important: The output MUST be a valid JSON object. No markdown, no backticks.
                """.trimIndent()
                
                val response = GeminiApiClient.generativeModel.generateContent(prompt)
                val rawText = response.text ?: "{}"
                val fenced = Regex("```(?:json)?\\s*([\\s\\S]+?)```", RegexOption.IGNORE_CASE).find(rawText)
                val jsonString = (fenced?.groupValues?.get(1) ?: rawText).trim()
                val json = org.json.JSONObject(jsonString)
                
                withContext(Dispatchers.Main) {
                    applyVoiceLogToListLead(leadId, json, spokenText)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@FollowUpActivity, "AI Error: ${e.message}", Toast.LENGTH_LONG).show()
                    val fallbackJson = org.json.JSONObject().apply { put("notes", spokenText) }
                    applyVoiceLogToListLead(leadId, fallbackJson, spokenText)
                }
            }
        }
    }

    private fun applyVoiceLogToListLead(leadId: String, json: org.json.JSONObject, spokenText: String) {
        val lead = allLeads.find { it["id"] == leadId } as? MutableMap<String, Any?> ?: return
        val updates = mutableMapOf<String, Any?>()

        if (json.has("phone") && !json.isNull("phone")) updates["phone"] = json.getString("phone")
        if (json.has("email") && !json.isNull("email")) updates["email"] = json.getString("email")
        if (json.has("category") && !json.isNull("category")) updates["category"] = json.getString("category")

        var extractedNotes = if (json.has("notes") && !json.isNull("notes")) json.getString("notes") else spokenText
        
        if (json.has("followUpDate") && !json.isNull("followUpDate")) {
            val dateStr = json.getString("followUpDate")
            try {
                val parsedDate = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).parse(dateStr)
                if (parsedDate != null) {
                    updates["followUpDate"] = Timestamp(parsedDate)
                    extractedNotes += "\n[Reminder Set: ${SimpleDateFormat("MMM dd h:mm a", Locale.getDefault()).format(parsedDate)}]"
                }
            } catch (e: Exception) { Log.e(TAG, "Failed to parse followUpDate: $dateStr", e) }
        }

        if (json.has("appointmentDate") && !json.isNull("appointmentDate")) {
            val dateStr = json.getString("appointmentDate")
            try {
                val parsedDate = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).parse(dateStr)
                if (parsedDate != null) {
                    updates["appointmentDate"] = Timestamp(parsedDate)
                    extractedNotes += "\n[Appointment Set: ${SimpleDateFormat("MMM dd h:mm a", Locale.getDefault()).format(parsedDate)}]"
                }
            } catch (e: Exception) { Log.e(TAG, "Failed to parse appointmentDate: $dateStr", e) }
        }
        if (json.has("appointmentLocation") && !json.isNull("appointmentLocation")) {
            val loc = json.getString("appointmentLocation")
            updates["appointmentLocation"] = loc
            extractedNotes += " at $loc"
        }

        if (json.has("targetMarket") && !json.isNull("targetMarket")) {
            val tmArray = json.getJSONArray("targetMarket")
            val newTmList = mutableListOf<String>()
            for (i in 0 until tmArray.length()) newTmList.add(tmArray.getString(i))
            
            val existingTmStr = lead["targetMarket"] as? String ?: ""
            val existingTm = existingTmStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toMutableSet()
            existingTm.addAll(newTmList)
            updates["targetMarket"] = existingTm.joinToString(", ")
        }

        val timestampStr = SimpleDateFormat("MMM dd, yyyy hh:mm a", Locale.getDefault()).format(Date())
        val formattedNote = "[$timestampStr]: [Voice Log]: $extractedNotes"
        val existingNotes = lead["notes"] as? String ?: ""
        updates["notes"] = if (existingNotes.isEmpty()) formattedNote else "$formattedNote\n\n$existingNotes"

        db.collection("leads").document(leadId).update(updates).addOnSuccessListener {
            // Instantly update local memory for snappy UI reload
            lead.putAll(updates)
            Toast.makeText(this, "Log added to lead!", Toast.LENGTH_SHORT).show()
            selectedLeads.clear()
            applyFilters()
            incrementDailyStat("followup_count")
        }.addOnFailureListener {
            Toast.makeText(this, "Failed to apply voice log.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        statsListener?.remove()
    }
}

class FollowUpAdapter(private val items: List<Map<String, Any>>, private val selected: MutableSet<String>, private val onSelectionChanged: () -> Unit, private val onPinClick: (String, Boolean) -> Unit, private val onClick: (Map<String, Any>) -> Unit) : RecyclerView.Adapter<FollowUpAdapter.VH>() {
    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.tvLeadName)
        val details: TextView = v.findViewById(R.id.tvLeadDetails)
        val pin: ImageButton = v.findViewById(R.id.btnPinLead)
        val call: ImageButton = v.findViewById(R.id.btnCallLead)
        val container: View = v.findViewById(R.id.leadItemContainer)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH = VH(LayoutInflater.from(parent.context).inflate(R.layout.item_follow_up, parent, false))
    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val id = item["id"] as String
        val name = item["name"] as? String ?: "Unknown"
        val phone = item["phone"] as? String ?: ""
        val category = item["category"] as? String ?: ""
        val dateStamp = item["followUpDate"] as? Timestamp
        val dateStr = if (dateStamp != null) SimpleDateFormat("MMM dd", Locale.getDefault()).format(dateStamp.toDate()) else "No Date"

        holder.name.text = name
        holder.details.text = "$category | Due: $dateStr"
        holder.container.setBackgroundColor(if (selected.contains(id)) Color.parseColor("#E3F2FD") else Color.TRANSPARENT)

        val isPinned = item["isPinned"] as? Boolean ?: false
        holder.pin.setImageResource(if (isPinned) android.R.drawable.star_on else android.R.drawable.star_off)
        holder.pin.setColorFilter(if (isPinned) android.graphics.Color.parseColor("#FFC107") else android.graphics.Color.LTGRAY)
        holder.pin.setOnClickListener {
            onPinClick(id, !isPinned)
        }

        holder.container.setOnClickListener {
            val currentPos = holder.adapterPosition
            if (currentPos != RecyclerView.NO_POSITION) {
                val currentItem = items[currentPos]
                if (selected.isNotEmpty()) {
                    toggleSelection(currentItem["id"] as String)
                } else {
                    onClick(currentItem)
                }
            }
        }
        holder.container.setOnLongClickListener {
            val currentPos = holder.adapterPosition
            if (currentPos != RecyclerView.NO_POSITION) {
                toggleSelection(items[currentPos]["id"] as String)
            }
            true
        }
        holder.call.setOnClickListener {
            if (phone.isNotEmpty()) {
                val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone"))
                holder.itemView.context.startActivity(intent)
            }
        }
    }

    private fun toggleSelection(id: String) {
        if (selected.contains(id)) selected.remove(id) else selected.add(id)
        notifyDataSetChanged()
        onSelectionChanged()
    }

    override fun getItemCount(): Int = items.size
}
