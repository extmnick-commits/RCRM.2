package com.nickpulido.rcrm

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.nickpulido.rcrm.ui.components.PipelineLead
import com.nickpulido.rcrm.ui.components.PipelineType
import com.nickpulido.rcrm.ui.components.PipelinesScreen
import com.nickpulido.rcrm.ui.components.RecruitStage
import com.nickpulido.rcrm.ui.components.SalesStage
import com.nickpulido.rcrm.domain.FollowUpRepository
import com.nickpulido.rcrm.domain.OutcomeSavePlanner
import com.nickpulido.rcrm.domain.StageChangeSpec
import com.nickpulido.rcrm.ui.components.OutcomeSheet
import com.nickpulido.rcrm.ui.components.OutcomeSheetRequest
import com.nickpulido.rcrm.ui.components.appointmentChoicesFromLead
import com.nickpulido.rcrm.ui.components.readOutcomeRequest
import com.nickpulido.rcrm.ui.components.writeTo
import com.nickpulido.rcrm.ui.theme.RCRMTheme

class PipelinesActivity : ComponentActivity() {

    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()
    private var listener: ListenerRegistration? = null

    private var leads by mutableStateOf<List<PipelineLead>>(emptyList())
    private var searchQuery by mutableStateOf("")
    private var pipelineType by mutableStateOf(PipelineType.SALES)
    
    private val followUpRepository = FollowUpRepository()
    private var outcomeRequest by mutableStateOf<OutcomeSheetRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        outcomeRequest = readOutcomeRequest(savedInstanceState)

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
                        val isClosed = doc.getBoolean("isClosed") ?: false
                        val isMissingPlan = followUpDate == null && !isPaused && !isClosed

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
                            lastConnectedContactLabel = lastConnectedContactLabel,
                            followUpAtMillis = followUpDate?.time,
                            appointmentChoices = appointmentChoicesFromLead(
                                doc.get("appointments"),
                                doc.getTimestamp("appointmentDate")
                            )
                        )
                    }
                    leads = newLeads.sortedByDescending { it.isPinned }
                    enrichOutcomeRequest()
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

                outcomeRequest?.let { request ->
                    OutcomeSheet(
                        request = request,
                        onDismiss = { outcomeRequest = null },
                        onSave = { submission, done ->
                            val currentUserId = auth.currentUser?.uid
                            if (currentUserId.isNullOrBlank()) {
                                done(false, "Sign in again before saving.")
                                return@OutcomeSheet
                            }
                            followUpRepository.saveOutcome(
                                OutcomeSavePlanner.buildSaveRequest(
                                    submission = submission,
                                    leadId = request.leadId,
                                    ownerId = currentUserId,
                                    stage = request.stage,
                                    currentActionId = request.currentActionId,
                                    nowMillis = System.currentTimeMillis()
                                )
                            ) { success, error ->
                                if (isDestroyed) return@saveOutcome
                                if (success) {
                                    Toast.makeText(this@PipelinesActivity, "Saved", Toast.LENGTH_SHORT).show()
                                }
                                done(success, error)
                            }
                        }
                    )
                }
            }
        }
    }

    private fun updateLeadStage(leadId: String, pType: PipelineType, newStageId: String) {
        val lead = leads.find { it.id == leadId }
        val stageTitle = if (pType == PipelineType.SALES) {
            SalesStage.values().find { it.id == newStageId }?.title ?: "New stage"
        } else {
            RecruitStage.values().find { it.id == newStageId }?.title ?: "New stage"
        }
        outcomeRequest = OutcomeSheetRequest(
            leadId = leadId,
            contactName = OutcomeSavePlanner.outcomeContactName(lead?.name),
            subtitle = OutcomeSavePlanner.outcomeHeaderSubtitle(pType.label, stageTitle),
            currentActionId = null,
            existingFollowUpMillis = lead?.followUpAtMillis,
            appointments = lead?.appointmentChoices.orEmpty(),
            outcomeRequired = false,
            stage = StageChangeSpec(pType, newStageId, stageTitle)
        )
        followUpRepository.loadEarliestPendingActionId(leadId) { actionId ->
            val current = outcomeRequest ?: return@loadEarliestPendingActionId
            if (current.leadId == leadId && current.stage?.newStageId == newStageId && !actionId.isNullOrBlank()) {
                outcomeRequest = current.copy(currentActionId = actionId)
            }
        }
    }

    private fun enrichOutcomeRequest() {
        val current = outcomeRequest ?: return
        val lead = leads.find { it.id == current.leadId } ?: return
        val stageTitle = current.stage?.let { stage ->
            if (stage.pipelineType == PipelineType.SALES) {
                SalesStage.values().find { it.id == stage.newStageId }?.title
            } else {
                RecruitStage.values().find { it.id == stage.newStageId }?.title
            }
        }.orEmpty().ifBlank { current.stage?.stageTitle.orEmpty() }
        val pipelineLabel = current.stage?.pipelineType?.label
        val updated = current.copy(
            contactName = OutcomeSavePlanner.outcomeContactName(lead.name),
            subtitle = if (pipelineLabel.isNullOrBlank()) {
                current.subtitle
            } else {
                OutcomeSavePlanner.outcomeHeaderSubtitle(pipelineLabel, stageTitle)
            },
            existingFollowUpMillis = lead.followUpAtMillis,
            appointments = lead.appointmentChoices,
            stage = current.stage?.copy(stageTitle = stageTitle.ifBlank { current.stage.stageTitle })
        )
        if (
            updated.contactName != current.contactName ||
            updated.subtitle != current.subtitle ||
            updated.existingFollowUpMillis != current.existingFollowUpMillis ||
            updated.appointments != current.appointments ||
            updated.stage != current.stage
        ) {
            outcomeRequest = updated
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outcomeRequest?.writeTo(outState)
    }

    override fun onDestroy() {
        super.onDestroy()
        listener?.remove()
    }
}
