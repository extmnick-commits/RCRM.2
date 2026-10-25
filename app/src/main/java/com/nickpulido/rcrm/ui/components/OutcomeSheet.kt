package com.nickpulido.rcrm.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nickpulido.rcrm.domain.ActionType
import com.nickpulido.rcrm.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OutcomeSheet(
    leadId: String,
    onDismiss: () -> Unit,
    onSave: (outcome: String, note: String, nextActionTitle: String?, nextActionTime: Long?, nextActionType: ActionType, pauseReason: String?) -> Unit
) {
    var outcome by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var nextActionTitle by remember { mutableStateOf("") }
    var isPaused by remember { mutableStateOf(false) }
    var pauseReason by remember { mutableStateOf("") }

    // Mock options for nextActionTime - in a real app this would use a DatePicker
    val defaultNextTime = System.currentTimeMillis() + 86400000L // 1 day from now

    val outcomes = listOf(
        "Connected", "No answer", "Voicemail left", 
        "Message sent", "Meeting completed", "No-show", 
        "Rescheduled", "Not interested"
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = DarkSpaceBlue,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 10.dp)
        ) {
            Text(
                text = "Log Outcome & Next Step",
                color = TextPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(16.dp))

            // Outcomes FlowRow (simulated with standard layout for now)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                outcomes.chunked(3).forEach { rowOutcomes ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        rowOutcomes.forEach { opt ->
                            val selected = outcome == opt
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (selected) NeonCyan else GlassFill)
                                    .clickable { outcome = opt }
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    text = opt,
                                    color = if (selected) Color.Black else TextPrimary,
                                    fontSize = 14.sp
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            
            BasicTextField(
                value = note,
                onValueChange = { note = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(GlassFill)
                    .padding(12.dp),
                textStyle = TextStyle(color = TextPrimary, fontSize = 15.sp),
                cursorBrush = SolidColor(NeonCyan),
                decorationBox = { innerTextField ->
                    if (note.isEmpty()) {
                        Text("Add a note (optional)...", color = TextMuted, fontSize = 15.sp)
                    }
                    innerTextField()
                }
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = isPaused,
                    onCheckedChange = { isPaused = it },
                    colors = CheckboxDefaults.colors(checkedColor = NeonPurple)
                )
                Text("Pause / Close Workflow", color = TextPrimary, fontSize = 15.sp)
            }

            if (isPaused) {
                BasicTextField(
                    value = pauseReason,
                    onValueChange = { pauseReason = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(GlassFill)
                        .padding(12.dp),
                    textStyle = TextStyle(color = TextPrimary, fontSize = 15.sp),
                    cursorBrush = SolidColor(NeonCyan),
                    decorationBox = { innerTextField ->
                        if (pauseReason.isEmpty()) {
                            Text("Reason (e.g. Not interested, closed)...", color = TextMuted, fontSize = 15.sp)
                        }
                        innerTextField()
                    }
                )
            } else {
                BasicTextField(
                    value = nextActionTitle,
                    onValueChange = { nextActionTitle = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(GlassFill)
                        .padding(12.dp),
                    textStyle = TextStyle(color = TextPrimary, fontSize = 15.sp),
                    cursorBrush = SolidColor(NeonCyan),
                    decorationBox = { innerTextField ->
                        if (nextActionTitle.isEmpty()) {
                            Text("Next action (e.g. Follow up call)...", color = TextMuted, fontSize = 15.sp)
                        }
                        innerTextField()
                    }
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = {
                    onSave(
                        outcome,
                        note,
                        if (isPaused) null else nextActionTitle.ifBlank { "Follow Up" },
                        if (isPaused) null else defaultNextTime,
                        ActionType.CALL,
                        if (isPaused) pauseReason else null
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                colors = ButtonDefaults.buttonColors(containerColor = NeonGreen),
                shape = RoundedCornerShape(25.dp),
                enabled = outcome.isNotEmpty()
            ) {
                Text("Save Outcome", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
            
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
