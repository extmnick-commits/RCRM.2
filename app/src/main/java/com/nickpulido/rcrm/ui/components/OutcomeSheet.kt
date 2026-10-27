package com.nickpulido.rcrm.ui.components

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Bundle
import android.text.format.DateFormat
import com.google.firebase.Timestamp
import com.nickpulido.rcrm.LeadAppointmentSync
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nickpulido.rcrm.domain.ActionType
import com.nickpulido.rcrm.domain.AppointmentChoice
import com.nickpulido.rcrm.domain.DueShortcut
import com.nickpulido.rcrm.domain.OutcomeSavePlanner
import com.nickpulido.rcrm.domain.OutcomeSubmission
import com.nickpulido.rcrm.domain.StageChangeSpec
import com.nickpulido.rcrm.domain.WorkflowLifecycle
import com.nickpulido.rcrm.ui.theme.DarkPanelBackground
import com.nickpulido.rcrm.ui.theme.DarkSpaceBlue
import com.nickpulido.rcrm.ui.theme.GlassFill
import com.nickpulido.rcrm.ui.theme.GlassPanel
import com.nickpulido.rcrm.ui.theme.NeonCyan
import com.nickpulido.rcrm.ui.theme.NeonPinkText
import com.nickpulido.rcrm.ui.theme.TextMuted
import com.nickpulido.rcrm.ui.theme.TextPrimary
import com.nickpulido.rcrm.ui.theme.TextSecondary
import java.util.Calendar
import java.util.TimeZone
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.statusBars

private val OutcomeLabels = listOf(
    "Connected",
    "No answer",
    "Voicemail left",
    "Message sent",
    "Meeting completed",
    "No-show",
    "Rescheduled",
    "Not interested"
)

private val ActionChoices = listOf(
    "Call" to ActionType.CALL,
    "Text" to ActionType.MESSAGE,
    "Email" to ActionType.EMAIL,
    "Meeting" to ActionType.MEETING,
    "Task" to ActionType.TO_DO
)

fun homeOutcomeSubtitle(category: String, salesStageId: String?, recruitStageId: String?): String {
    val recruiting = category.contains("Recruit", ignoreCase = true) &&
        !category.contains("Client", ignoreCase = true)
    return if (recruiting) {
        val title = RecruitStage.values().find { it.id == recruitStageId }?.title.orEmpty()
        OutcomeSavePlanner.outcomeHeaderSubtitle(PipelineType.RECRUITING.label, title)
    } else {
        val stored = SalesStage.values().find { it.id == salesStageId }?.title
        val title = stored ?: if (category.contains("Client", ignoreCase = true)) SalesStage.IN_FORCE.title else ""
        OutcomeSavePlanner.outcomeHeaderSubtitle(PipelineType.SALES.label, title)
    }
}

fun appointmentChoicesFromLead(rawAppointments: Any?, legacyStart: Timestamp?): List<AppointmentChoice> {
    return OutcomeSavePlanner.relevantAppointments(
        records = LeadAppointmentSync.readRecords(rawAppointments),
        legacyStartMillis = legacyStart?.toDate()?.time,
        nowMillis = System.currentTimeMillis()
    )
}

fun OutcomeSheetRequest.writeTo(out: Bundle) {
    out.putString(OUTCOME_LEAD_ID, leadId)
    out.putString(OUTCOME_NAME, contactName)
    out.putString(OUTCOME_SUBTITLE, subtitle)
    out.putString(OUTCOME_ACTION_ID, currentActionId)
    out.putLong(OUTCOME_FOLLOW_UP, existingFollowUpMillis ?: -1L)
    out.putBoolean(OUTCOME_REQUIRED, outcomeRequired)
    out.putString(OUTCOME_STAGE_PIPELINE, stage?.pipelineType?.name)
    out.putString(OUTCOME_STAGE_ID, stage?.newStageId)
    out.putString(OUTCOME_STAGE_TITLE, stage?.stageTitle)
}

fun readOutcomeRequest(saved: Bundle?): OutcomeSheetRequest? {
    val leadId = saved?.getString(OUTCOME_LEAD_ID) ?: return null
    val pipelineName = saved.getString(OUTCOME_STAGE_PIPELINE).orEmpty()
    val stageId = saved.getString(OUTCOME_STAGE_ID).orEmpty()
    val stageTitle = saved.getString(OUTCOME_STAGE_TITLE).orEmpty()
    val stage = if (pipelineName.isBlank() || stageId.isBlank()) {
        null
    } else {
        val pipeline = runCatching { PipelineType.valueOf(pipelineName) }.getOrNull() ?: return null
        StageChangeSpec(pipeline, stageId, stageTitle)
    }
    val followUp = saved.getLong(OUTCOME_FOLLOW_UP, -1L)
    return OutcomeSheetRequest(
        leadId = leadId,
        contactName = saved.getString(OUTCOME_NAME) ?: "Contact",
        subtitle = saved.getString(OUTCOME_SUBTITLE).orEmpty(),
        currentActionId = saved.getString(OUTCOME_ACTION_ID)?.takeIf { it.isNotBlank() },
        existingFollowUpMillis = followUp.takeIf { it >= 0L },
        appointments = emptyList(),
        outcomeRequired = saved.getBoolean(OUTCOME_REQUIRED, stage == null),
        stage = stage
    )
}

private const val OUTCOME_LEAD_ID = "outcome_lead_id"
private const val OUTCOME_NAME = "outcome_name"
private const val OUTCOME_SUBTITLE = "outcome_subtitle"
private const val OUTCOME_ACTION_ID = "outcome_action_id"
private const val OUTCOME_FOLLOW_UP = "outcome_follow_up"
private const val OUTCOME_REQUIRED = "outcome_required"
private const val OUTCOME_STAGE_PIPELINE = "outcome_stage_pipeline"
private const val OUTCOME_STAGE_ID = "outcome_stage_id"
private const val OUTCOME_STAGE_TITLE = "outcome_stage_title"

data class OutcomeSheetRequest(
    val leadId: String,
    val contactName: String,
    val subtitle: String,
    val currentActionId: String?,
    val existingFollowUpMillis: Long?,
    val appointments: List<AppointmentChoice>,
    val outcomeRequired: Boolean,
    val stage: StageChangeSpec?
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OutcomeSheet(
    request: OutcomeSheetRequest,
    onDismiss: () -> Unit,
    onSave: (OutcomeSubmission, (success: Boolean, errorMessage: String?) -> Unit) -> Unit
) {
    val stateKey = request.leadId + "|" + (request.stage?.newStageId ?: "")
    var outcome by rememberSaveable(stateKey) { mutableStateOf("") }
    var note by rememberSaveable(stateKey) { mutableStateOf("") }
    var scheduleNext by rememberSaveable(stateKey) { mutableStateOf(false) }
    var nextTitle by rememberSaveable(stateKey) { mutableStateOf("") }
    var dueMillis by rememberSaveable(stateKey) { mutableStateOf(0L) }
    var actionTypeName by rememberSaveable(stateKey) { mutableStateOf(ActionType.CALL.name) }
    var clearExisting by rememberSaveable(stateKey) { mutableStateOf(false) }
    var lifecycleName by rememberSaveable(stateKey) { mutableStateOf(WorkflowLifecycle.UNCHANGED.name) }
    var lifecycleReason by rememberSaveable(stateKey) { mutableStateOf("") }
    var appointmentId by rememberSaveable(stateKey) { mutableStateOf("") }
    var showLifecycle by rememberSaveable(stateKey) { mutableStateOf(false) }
    var operationId by rememberSaveable(stateKey) { mutableStateOf(java.util.UUID.randomUUID().toString()) }
    var isSaving by remember(stateKey) { mutableStateOf(false) }
    var errorMessage by rememberSaveable(stateKey) { mutableStateOf("") }
    var failedSave by rememberSaveable(stateKey) { mutableStateOf(false) }
    val blockDismiss = remember(stateKey) { mutableStateOf(false) }
    blockDismiss.value = isSaving

    val actionType = ActionChoices.firstOrNull { it.second.name == actionTypeName }?.second ?: ActionType.CALL
    val lifecycle = runCatching { WorkflowLifecycle.valueOf(lifecycleName) }.getOrDefault(WorkflowLifecycle.UNCHANGED)
    val contactName = OutcomeSavePlanner.outcomeContactName(request.contactName)
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { target ->
            !blockDismiss.value || target != SheetValue.Hidden
        }
    )

    ModalBottomSheet(
        onDismissRequest = {
            if (!blockDismiss.value) onDismiss()
        },
        sheetState = sheetState,
        containerColor = Color.Transparent,
        scrimColor = Color.Black.copy(alpha = 0.55f),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = { BottomSheetDefaults.DragHandle(color = TextMuted) },
        contentWindowInsets = { WindowInsets.statusBars.only(WindowInsetsSides.Top) }
    ) {
        var bottomPad by remember { mutableStateOf(0.dp) }
        val overlapView = LocalView.current
        val overlapDensity = LocalDensity.current
        val imeBottom = WindowInsets.ime.getBottom(overlapDensity)
        val navBottom = WindowInsets.navigationBars.getBottom(overlapDensity)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .onGloballyPositioned {
                    val next = contentBottomOverlap(overlapView, overlapDensity, imeBottom, navBottom)
                    if (next != bottomPad) bottomPad = next
                }
                .padding(bottom = bottomPad)
                    .frostedGlass(
                        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                        fillColor = DarkPanelBackground.copy(alpha = 0.96f)
                    )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "Log outcome",
                        color = TextPrimary,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = contactName,
                        color = TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (request.subtitle.isNotBlank()) {
                        Text(
                            text = request.subtitle,
                            color = TextSecondary,
                            fontSize = 13.sp
                        )
                    }
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp)
                ) {
                    Spacer(modifier = Modifier.height(12.dp))
                    FieldLabel(if (request.outcomeRequired) "Outcome" else "Outcome (optional)")
                    Spacer(modifier = Modifier.height(8.dp))
                    AdaptiveSingleSelect(
                        labels = OutcomeLabels,
                        selected = outcome,
                        allowDeselect = true,
                        onSelect = { outcome = it }
                    )

                    Spacer(modifier = Modifier.height(16.dp))
                    FieldLabel("Note")
                    Spacer(modifier = Modifier.height(8.dp))
                    GlassField(
                        value = note,
                        onValueChange = { note = it },
                        placeholder = "What happened",
                        minHeight = 96.dp,
                        singleLine = false,
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Default
                    )

                    if (lifecycle == WorkflowLifecycle.UNCHANGED) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Schedule next action",
                                color = TextPrimary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f)
                            )
                            Switch(
                                checked = scheduleNext,
                                onCheckedChange = { enabled ->
                                    scheduleNext = enabled
                                    if (enabled) {
                                        clearExisting = false
                                        if (nextTitle.isBlank()) nextTitle = "Follow up"
                                    }
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = DarkSpaceBlue,
                                    checkedTrackColor = NeonCyan,
                                    uncheckedThumbColor = TextSecondary,
                                    uncheckedTrackColor = GlassFill,
                                    uncheckedBorderColor = TextMuted
                                )
                            )
                        }

                        if (scheduleNext) {
                            Spacer(modifier = Modifier.height(12.dp))
                            FieldLabel("Next action")
                            Spacer(modifier = Modifier.height(8.dp))
                            GlassField(
                                value = nextTitle,
                                onValueChange = { nextTitle = it },
                                placeholder = "Follow up",
                                minHeight = 48.dp,
                                singleLine = true,
                                capitalization = KeyboardCapitalization.Sentences,
                                imeAction = ImeAction.Next
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            FieldLabel("Action type")
                            Spacer(modifier = Modifier.height(8.dp))
                            AdaptiveSingleSelect(
                                labels = ActionChoices.map { it.first },
                                selected = ActionChoices.firstOrNull { it.second == actionType }?.first ?: "Call",
                                allowDeselect = false,
                                onSelect = { label ->
                                    actionTypeName = ActionChoices.first { it.first == label }.second.name
                                }
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            FieldLabel("Due date/time")
                            Spacer(modifier = Modifier.height(8.dp))
                            DueShortcuts(
                                dueMillis = dueMillis.takeIf { it > 0L },
                                onDueChange = { dueMillis = it ?: 0L }
                            )
                            if (request.appointments.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(12.dp))
                                FieldLabel("Existing appointments")
                                Spacer(modifier = Modifier.height(8.dp))
                                AppointmentChoices(
                                    appointments = request.appointments,
                                    selectedId = appointmentId,
                                    onSelect = { choice ->
                                        val selected = appointmentId == choice.id && choice.id.isNotEmpty() && dueMillis == choice.startMillis
                                        if (selected) {
                                            appointmentId = ""
                                        } else {
                                            appointmentId = choice.id
                                            dueMillis = choice.startMillis
                                            actionTypeName = ActionType.MEETING.name
                                            if (nextTitle.isBlank() || nextTitle == "Follow up") {
                                                nextTitle = "Appointment"
                                            }
                                        }
                                    }
                                )
                            }
                        } else if (request.existingFollowUpMillis != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Current due date stays unless you clear it.",
                                color = TextMuted,
                                fontSize = 13.sp
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            GlassButton(onClick = { clearExisting = !clearExisting }) {
                                Text(
                                    text = if (clearExisting) {
                                        "Keep existing next action"
                                    } else {
                                        "Clear existing next action"
                                    },
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                            if (clearExisting) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "The due date moves to any other open task, or clears if this was the only one.",
                                    color = TextSecondary,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    } else {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "No new action is created. The due date is cleared and other open tasks stay on the contact.",
                            color = TextSecondary,
                            fontSize = 13.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    GlassButton(onClick = { showLifecycle = !showLifecycle }) {
                        Text(
                            text = if (showLifecycle) "Hide pause and close" else "Pause or close",
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                    if (showLifecycle) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Pause keeps the contact in the pipeline and hides the due date. Close marks the workflow finished.",
                            color = TextSecondary,
                            fontSize = 13.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        AdaptiveSingleSelect(
                            labels = listOf("Pause", "Close"),
                            selected = when (lifecycle) {
                                WorkflowLifecycle.PAUSE -> "Pause"
                                WorkflowLifecycle.CLOSE -> "Close"
                                WorkflowLifecycle.UNCHANGED -> ""
                            },
                            allowDeselect = true,
                            onSelect = { label ->
                                lifecycleName = when (label) {
                                    "Pause" -> WorkflowLifecycle.PAUSE.name
                                    "Close" -> WorkflowLifecycle.CLOSE.name
                                    else -> WorkflowLifecycle.UNCHANGED.name
                                }
                                if (label == "Pause" || label == "Close") {
                                    scheduleNext = false
                                    clearExisting = false
                                }
                            }
                        )
                        if (lifecycle != WorkflowLifecycle.UNCHANGED) {
                            Spacer(modifier = Modifier.height(12.dp))
                            FieldLabel("Reason")
                            Spacer(modifier = Modifier.height(8.dp))
                            GlassField(
                                value = lifecycleReason,
                                onValueChange = { lifecycleReason = it },
                                placeholder = "Optional reason",
                                minHeight = 48.dp,
                                singleLine = true,
                                capitalization = KeyboardCapitalization.Sentences,
                                imeAction = ImeAction.Done
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .padding(top = 8.dp, bottom = 12.dp)
                ) {
                    if (errorMessage.isNotBlank()) {
                        Text(
                            text = errorMessage,
                            color = NeonPinkText,
                            fontSize = 13.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    Box(modifier = Modifier.alpha(if (isSaving) 0.7f else 1f)) {
                        GradientButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                if (isSaving) return@GradientButton
                                val problem = OutcomeSavePlanner.outcomeFormError(
                                    outcomeRequired = request.outcomeRequired,
                                    outcome = outcome,
                                    scheduleNext = scheduleNext && lifecycle == WorkflowLifecycle.UNCHANGED,
                                    nextTitle = nextTitle,
                                    dueMillis = dueMillis.takeIf { it > 0L },
                                    lifecycle = lifecycle
                                )
                                if (problem != null) {
                                    errorMessage = problem
                                    failedSave = false
                                    return@GradientButton
                                }
                                isSaving = true
                                errorMessage = ""
                                val submission = OutcomeSubmission(
                                    operationId = operationId,
                                    outcome = outcome,
                                    note = note,
                                    scheduleNext = scheduleNext && lifecycle == WorkflowLifecycle.UNCHANGED,
                                    nextTitle = nextTitle,
                                    nextTime = dueMillis.takeIf { it > 0L },
                                    nextType = actionType,
                                    clearExisting = clearExisting && !scheduleNext && lifecycle == WorkflowLifecycle.UNCHANGED,
                                    lifecycle = lifecycle,
                                    lifecycleReason = lifecycleReason,
                                    linkedAppointmentId = appointmentId.ifBlank { null }
                                )
                                onSave(submission) { success, message ->
                                    isSaving = false
                                    val draft = com.nickpulido.rcrm.domain.OutcomeDraft(
                                        outcome = outcome,
                                        note = note,
                                        nextTitle = nextTitle,
                                        dueMillis = dueMillis.takeIf { it > 0L },
                                        actionType = actionType,
                                        scheduleNext = scheduleNext,
                                        clearExisting = clearExisting,
                                        lifecycle = lifecycle,
                                        lifecycleReason = lifecycleReason,
                                        appointmentId = appointmentId.ifBlank { null },
                                        operationId = operationId
                                    )
                                    val result = OutcomeSavePlanner.afterSave(success, message, draft)
                                    if (result.dismiss) {
                                        onDismiss()
                                    } else {
                                        failedSave = true
                                        errorMessage = result.errorMessage.orEmpty()
                                    }
                                }
                            }
                        ) {
                            Box(
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                if (isSaving) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(22.dp),
                                        color = TextPrimary,
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Text(
                                        text = if (failedSave) "Retry save" else "Save",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 16.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

private fun contentBottomOverlap(
    view: android.view.View,
    density: androidx.compose.ui.unit.Density,
    imePx: Int,
    navPx: Int
): Dp {
    val content = view.rootView.findViewById(android.R.id.content) ?: view
    val visible = android.graphics.Rect()
    content.getWindowVisibleDisplayFrame(visible)
    val location = IntArray(2)
    content.getLocationOnScreen(location)
    val contentBottom = location[1] + content.height
    val overlap = (contentBottom - visible.bottom).coerceAtLeast(0)
    val uncoveredInset = maxOf(imePx, navPx)
    val padPx = if (overlap > 0) minOf(overlap, uncoveredInset.coerceAtLeast(overlap)) else 0
    return with(density) { padPx.toDp() }
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text = text,
        color = TextSecondary,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold
    )
}

@Composable
private fun GlassField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    minHeight: Dp,
    singleLine: Boolean,
    capitalization: KeyboardCapitalization,
    imeAction: ImeAction
) {
    val shape = RoundedCornerShape(14.dp)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .frostedGlass(shape = shape, fillColor = GlassFill)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        textStyle = TextStyle(color = TextPrimary, fontSize = 15.sp),
        cursorBrush = SolidColor(NeonCyan),
        singleLine = singleLine,
        keyboardOptions = KeyboardOptions(
            capitalization = capitalization,
            imeAction = imeAction
        ),
        decorationBox = { inner ->
            Box(contentAlignment = Alignment.TopStart) {
                if (value.isEmpty()) {
                    Text(text = placeholder, color = TextMuted, fontSize = 15.sp)
                }
                inner()
            }
        }
    )
}

@Composable
private fun DueShortcuts(
    dueMillis: Long?,
    onDueChange: (Long?) -> Unit
) {
    val context = LocalContext.current
    val zone = TimeZone.getDefault()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        GlassButton(
            modifier = Modifier.heightIn(min = 48.dp),
            onClick = {
                onDueChange(OutcomeSavePlanner.dueMillisForShortcut(DueShortcut.TODAY, System.currentTimeMillis(), zone))
            }
        ) {
            Text("Today", maxLines = 1, softWrap = false)
        }
        GlassButton(
            modifier = Modifier.heightIn(min = 48.dp),
            onClick = {
                onDueChange(OutcomeSavePlanner.dueMillisForShortcut(DueShortcut.TOMORROW, System.currentTimeMillis(), zone))
            }
        ) {
            Text("Tomorrow", maxLines = 1, softWrap = false)
        }
        GlassButton(
            modifier = Modifier.heightIn(min = 48.dp),
            onClick = {
                val calendar = Calendar.getInstance()
                if (dueMillis != null) calendar.timeInMillis = dueMillis
                DatePickerDialog(
                    context,
                    com.nickpulido.rcrm.R.style.ThemeOverlay_RCRM_DatePicker,
                    { _, year, month, day ->
                        TimePickerDialog(
                            context,
                            com.nickpulido.rcrm.R.style.ThemeOverlay_RCRM_DatePicker,
                            { _, hour, minute ->
                                calendar.set(Calendar.YEAR, year)
                                calendar.set(Calendar.MONTH, month)
                                calendar.set(Calendar.DAY_OF_MONTH, day)
                                calendar.set(Calendar.HOUR_OF_DAY, hour)
                                calendar.set(Calendar.MINUTE, minute)
                                calendar.set(Calendar.SECOND, 0)
                                calendar.set(Calendar.MILLISECOND, 0)
                                onDueChange(calendar.timeInMillis)
                            },
                            calendar.get(Calendar.HOUR_OF_DAY),
                            calendar.get(Calendar.MINUTE),
                            DateFormat.is24HourFormat(context)
                        ).show()
                    },
                    calendar.get(Calendar.YEAR),
                    calendar.get(Calendar.MONTH),
                    calendar.get(Calendar.DAY_OF_MONTH)
                ).show()
            }
        ) {
            Text("Pick date", maxLines = 1, softWrap = false)
        }
    }
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = if (dueMillis != null) {
            OutcomeSavePlanner.formatDue(dueMillis, zone)
        } else {
            "No due date selected"
        },
        color = if (dueMillis != null) NeonCyan else TextMuted,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium
    )
}

@Composable
private fun AppointmentChoices(
    appointments: List<AppointmentChoice>,
    selectedId: String?,
    onSelect: (AppointmentChoice) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        appointments.forEach { choice ->
            val selected = choice.id == selectedId
            val shape = RoundedCornerShape(14.dp)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .frostedGlass(
                        shape = shape,
                        fillColor = if (selected) NeonCyan.copy(alpha = 0.16f) else GlassFill,
                        borderBrush = if (selected) {
                            Brush.linearGradient(listOf(NeonCyan, NeonCyan.copy(alpha = 0.7f)))
                        } else {
                            Brush.verticalGradient(
                                listOf(Color.White.copy(alpha = 0.28f), Color.White.copy(alpha = 0.08f))
                            )
                        }
                    )
                    .selectable(
                        selected = selected,
                        role = Role.RadioButton,
                        onClick = { onSelect(choice) }
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Text(
                    text = choice.label,
                    color = if (selected) NeonCyan else TextPrimary,
                    fontSize = 14.sp,
                    maxLines = 2
                )
            }
        }
    }
}

@Composable
private fun AdaptiveSingleSelect(
    labels: List<String>,
    selected: String,
    allowDeselect: Boolean,
    onSelect: (String) -> Unit
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .selectableGroup()
    ) {
        val gap = 8.dp
        val horizontalPadding = 12.dp
        val density = LocalDensity.current
        val minCell = 48.dp
        val textMeasurer = rememberTextMeasurer()
        val style = TextStyle(
            color = TextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
        )
        val widths = labels.map { label ->
            textMeasurer.measure(
                text = AnnotatedString(label),
                style = style,
                softWrap = false,
                maxLines = 1
            ).size.width
        }
        val availablePx = constraints.maxWidth
        val gapPx = with(density) { gap.roundToPx() }
        val padPx = with(density) { (horizontalPadding * 2).roundToPx() }
        val minCellPx = with(density) { minCell.roundToPx() }
        val columns = OutcomeSavePlanner.outcomeColumnCount(
            textWidthsPx = widths,
            availableWidthPx = availablePx,
            horizontalPaddingPx = padPx,
            gapPx = gapPx,
            minCellPx = minCellPx
        )
        val fits = OutcomeSavePlanner.choiceLabelsFit(
            textWidthsPx = widths,
            availableWidthPx = availablePx,
            horizontalPaddingPx = padPx,
            gapPx = gapPx,
            minCellPx = minCellPx
        )
        Column(
            modifier = Modifier
                .then(if (fits) Modifier.fillMaxWidth() else Modifier.horizontalScroll(rememberScrollState())),
            verticalArrangement = Arrangement.spacedBy(gap)
        ) {
            labels.chunked(columns).forEach { row ->
                Row(
                    modifier = Modifier.then(if (fits) Modifier.fillMaxWidth() else Modifier),
                    horizontalArrangement = Arrangement.spacedBy(gap)
                ) {
                    row.forEach { label ->
                        OutcomeChoice(
                            label = label,
                            selected = label == selected,
                            fill = fits,
                            onClick = {
                                if (label == selected && allowDeselect) onSelect("") else onSelect(label)
                            }
                        )
                    }
                    if (fits) {
                        repeat(columns - row.size) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.OutcomeChoice(
    label: String,
    selected: Boolean,
    fill: Boolean,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = Modifier
            .then(if (fill) Modifier.weight(1f) else Modifier)
            .heightIn(min = 48.dp)
            .frostedGlass(
                shape = shape,
                fillColor = if (selected) NeonCyan.copy(alpha = 0.16f) else GlassFill,
                borderBrush = if (selected) {
                    Brush.linearGradient(listOf(NeonCyan, NeonCyan.copy(alpha = 0.75f)))
                } else {
                    Brush.verticalGradient(
                        listOf(Color.White.copy(alpha = 0.28f), Color.White.copy(alpha = 0.08f))
                    )
                }
            )
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onClick
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label.replace('-', '\u2011'),
            color = if (selected) NeonCyan else TextPrimary,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Visible
        )
    }
}

