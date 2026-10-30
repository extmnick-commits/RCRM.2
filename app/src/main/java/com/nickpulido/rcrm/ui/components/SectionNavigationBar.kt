package com.nickpulido.rcrm.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nickpulido.rcrm.ui.theme.NeonCyan
import com.nickpulido.rcrm.ui.theme.TextSecondary

/** Shared by the five native activity destinations. */
@Composable
fun SectionNavigationBar(current: Int, onNavigate: (Int) -> Unit) {
    val labels = listOf("Home", "Appointments", "Follow-ups", "Pipelines", "Contacts")
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)
            .frostedGlass(RoundedCornerShape(24.dp)).selectableGroup().padding(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        labels.forEachIndexed { index, label ->
            val selected = current == index
            val tint = if (selected) NeonCyan else TextSecondary
            Column(
                Modifier.weight(if (index == 1) 1.25f else 1f)
                    .clip(RoundedCornerShape(18.dp))
                    .background(if (selected) NeonCyan.copy(alpha = 0.12f) else Color.Transparent)
                    .selectable(selected = selected, role = Role.Tab, onClick = { onNavigate(index) })
                    .heightIn(min = 60.dp).padding(horizontal = 2.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (index == 3) {
                    Canvas(Modifier.size(24.dp)) {
                        listOf(0.4f, 0.65f, 0.95f).forEachIndexed { bar, height ->
                            drawRoundRect(tint, Offset(size.width * (0.08f + bar * 0.31f), size.height * (1f - height)),
                                Size(size.width * 0.23f, size.height * height), CornerRadius(2.dp.toPx()))
                        }
                    }
                } else {
                    val icon = when (index) {
                        0 -> Icons.Filled.Home
                        1 -> Icons.Filled.DateRange
                        2 -> Icons.Filled.List
                        else -> Icons.Filled.Person
                    }
                    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
                }
                Text(label, color = tint, fontSize = 11.sp, textAlign = TextAlign.Center,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium)
            }
        }
    }
}
