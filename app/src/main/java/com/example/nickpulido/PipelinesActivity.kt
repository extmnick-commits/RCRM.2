package com.nickpulido.rcrm

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import com.nickpulido.rcrm.ui.components.PipelineLead
import com.nickpulido.rcrm.ui.components.PipelineType
import com.nickpulido.rcrm.ui.components.PipelinesScreen
import com.nickpulido.rcrm.ui.components.RecruitStage
import com.nickpulido.rcrm.ui.components.SalesStage
import com.nickpulido.rcrm.domain.FollowUpRepository
import com.nickpulido.rcrm.domain.ActionType
import com.nickpulido.rcrm.ui.components.OutcomeSheet
import com.nickpulido.rcrm.ui.theme.RCRMTheme

class PipelinesActivity : ComponentActivity() {

    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()
    private var listener: ListenerRegistration? = null

    private var leads by mutableStateOf<List<PipelineLead>>(emptyList())
    private var searchQuery by mutableStateOf("")
    private var pipelineType by mutableStateOf(PipelineType.SALES)
    
    private val followUpRepository = FollowUpRepository()
    private var activeOutcomeLeadId by mutableStateOf<String?>(null)
    private var activeStageChange by mutableStateOf<Triple<String, PipelineType, String>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val currentUser = auth.currentUser
        if (currentUser == null) {
            finish()
            return
        }
        val userId = currentUser.uid

        listener = db.collection("leads")
            .whereEqualTo("ownerId", userId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e("PipelinesActivity", "Listen failed.", error)
                    return@addSnapshotListener
                }

                if (snapshot != null) {
                    val newLeads = snapshot.documents.mapNotNull { doc ->
                        val category = doc.getString("category") ?: ""
                        
                        // Parse target market points
                        val targetMarket = doc.getString("targetMarket") ?: ""
                        val tmList = targetMarket.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                        val tmPoints = tmList.size.coerceIn(0, 5)

                        // Fallback logic for stages
                        val salesStageStr = doc.getString("salesStage")
                        val salesStage = SalesStage.values().find { it.id == salesStageStr } ?: run {
                            if (category.contains("Client", ignoreCase = true)) SalesStage.IN_FORCE
                            else if (doc.getTimestamp("appointmentDate") != null) SalesStage.APPT_SET
                            else SalesStage.NEW_LEAD
                        }

                        val recruitStageStr = doc.getString("recruitStage")
                        val recruitStage = RecruitStage.values().find { it.id == recruitStageStr } ?: RecruitStage.PROSPECT

                        val rawNotes = doc.getString("notes") ?: ""
                        val lastNote = NoteUtils.getDisplayNote(rawNotes)
                        
                        val followUpDate = doc.getTimestamp("followUpDate")?.toDate()
                        val followUpLabel = followUpDate?.let {
                            java.text.SimpleDateFormat("MMM dd, yyyy", java.util.Locale.getDefault()).format(it)
                        }

                        val isPaused = doc.getBoolean("isPaused") ?: false
                        val isMissingPlan = followUpDate == null && !isPaused

                        val today = java.util.Calendar.getInstance().apply {
                            set(java.util.Calendar.HOUR_OF_DAY, 0)
                            set(java.util.Calendar.MINUTE, 0)
                            set(java.util.Calendar.SECOND, 0)
                            set(java.util.Calendar.MILLISECOND, 0)
                        }.time
                        val isOverdue = followUpDate != null && followUpDate.before(today)

                        val lastConnectedContactLong = doc.getLong("lastConnectedContact")
                        val lastConnectedContactLabel = lastConnectedContactLong?.let {
                            java.text.SimpleDateFormat("MMM dd, yyyy", java.util.Locale.getDefault()).format(java.util.Date(it))
                        }?.let { "Last connected: $it" }

                        // Filter by type logic will be handled in compose or here. We load all and filter in compose.
                        PipelineLead(
                            id = doc.id,
                            name = doc.getString("name") ?: "Unknown",
                            phone = doc.getString("phone") ?: "",
                            category = category,
                            targetMarketPoints = tmPoints,
                            salesStage = salesStage,
                            recruitStage = recruitStage,
                            lastNotePreview = lastNote,
                            followUpDateLabel = followUpLabel,
                            isPinned = doc.getBoolean("isPinned") ?: false,
                            isMissingPlan = isMissingPlan,
                            isOverdue = isOverdue,
                            lastConnectedContactLabel = lastConnectedContactLabel
                        )
                    }
                    leads = newLeads.sortedByDescending { it.isPinned }
                }
            }

        setContent {
            RCRMTheme {
                val filteredLeads = leads.filter {
                    val matchSearch = it.name.contains(searchQuery, ignoreCase = true) || it.phone.contains(searchQuery)
                    val matchType = if (pipelineType == PipelineType.SALES) {
                        !it.category.contains("Recruit", ignoreCase = true) || it.category.contains("Client", ignoreCase = true) || it.category.contains("Prospect", ignoreCase = true)
                    } else {
                        it.category.contains("Recruit", ignoreCase = true)
                    }
                    matchSearch && matchType
                }

                PipelinesScreen(
                    onBack = { finish() },
                    searchQuery = searchQuery,
                    onSearchQueryChange = { searchQuery = it },
                    pipelineType = pipelineType,
                    onPipelineTypeChange = { pipelineType = it },
                    leads = filteredLeads,
                    onLeadClick = { leadId ->
                        val intent = Intent(this, FollowUpActivity::class.java)
                        intent.putExtra("targetLeadId", leadId)
                        startActivity(intent)
                    },
                    onCallLead = { phone ->
                        if (phone.isNotBlank()) {
                            val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone"))
                            startActivity(intent)
                        }
                    },
                    onStageChange = { leadId, pType, newStageId ->
                        updateLeadStage(leadId, pType, newStageId)
                    }
                )

                activeStageChange?.let { (leadId, pType, newStageId) ->
                    OutcomeSheet(
                        leadId = leadId,
                        onDismiss = { activeStageChange = null },
                        onSave = { outcome, note, nextActionTitle, nextActionTime, nextActionType, pauseReason ->
                            val currentUserId = auth.currentUser?.uid ?: return@OutcomeSheet
                            val stageTitle = if (pType == PipelineType.SALES) {
                                SalesStage.values().find { it.id == newStageId }?.title ?: "New Stage"
                            } else {
                                RecruitStage.values().find { it.id == newStageId }?.title ?: "New Stage"
                            }
                            
                            followUpRepository.recordStageChange(
                                leadId = leadId,
                                ownerId = currentUserId,
                                pipelineType = pType,
                                newStageId = newStageId,
                                stageTitle = stageTitle,
                                suggestedNextActionTitle = nextActionTitle,
                                suggestedNextActionTime = nextActionTime
                            ) { success ->
                                // Also log the custom outcome if provided (unless it's just empty)
                                if (success && outcome.isNotEmpty()) {
                                    followUpRepository.recordOutcomeAndNextStep(
                                        leadId = leadId,
                                        ownerId = currentUserId,
                                        currentActionId = null,
                                        newOutcome = outcome,
                                        newNote = note,
                                        nextActionTitle = null, // Already handled in recordStageChange
                                        nextActionTime = null,
                                        nextActionType = nextActionType,
                                        pauseReason = pauseReason
                                    ) { }
                                }
                            }
                            activeStageChange = null
                        }
                    )
                }
            }
        }
    }

    private fun updateLeadStage(leadId: String, pType: PipelineType, newStageId: String) {
        activeStageChange = Triple(leadId, pType, newStageId)
    }

    override fun onDestroy() {
        super.onDestroy()
        listener?.remove()
    }
}
