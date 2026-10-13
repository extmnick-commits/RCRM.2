package com.nickpulido.rcrm.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nickpulido.rcrm.ui.theme.AppointmentsFillBottom
import com.nickpulido.rcrm.ui.theme.AppointmentsFillTop
import com.nickpulido.rcrm.ui.theme.ContactsFillBottom
import com.nickpulido.rcrm.ui.theme.ContactsFillTop
import com.nickpulido.rcrm.ui.theme.FollowUpsFillBottom
import com.nickpulido.rcrm.ui.theme.FollowUpsFillTop
import com.nickpulido.rcrm.ui.theme.NeonBlue
import com.nickpulido.rcrm.ui.theme.NeonBlueText
import com.nickpulido.rcrm.ui.theme.NeonGreen
import com.nickpulido.rcrm.ui.theme.NeonGreenText
import com.nickpulido.rcrm.ui.theme.NeonPink
import com.nickpulido.rcrm.ui.theme.NeonPinkText
import com.nickpulido.rcrm.ui.theme.PrimaryGradientEnd
import com.nickpulido.rcrm.ui.theme.PrimaryGradientMid
import com.nickpulido.rcrm.ui.theme.PrimaryGradientStart
import com.nickpulido.rcrm.ui.theme.QuickAddTop

private val ActionCorner = 18.dp
private val GlowGutter = 10.dp
private val ActionVisualHeight = 76.dp

@Composable
fun QuickActionsGrid(
    onQuickAdd: () -> Unit,
    onAppointments: () -> Unit,
    onFollowUps: () -> Unit,
    onContacts: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            NeonActionButton(
                label = "Quick Add /\nImport",
                icon = Icons.Filled.Add,
                glowColor = NeonBlue,
                tint = Color.White,
                background = Brush.horizontalGradient(
                    listOf(PrimaryGradientStart, PrimaryGradientMid, PrimaryGradientEnd)
                ),
                sheen = Brush.verticalGradient(
                    listOf(
                        QuickAddTop.copy(alpha = 0.55f),
                        Color.Transparent,
                        Color.Black.copy(alpha = 0.22f)
                    )
                ),
                glowAlpha = 1f,
                onClick = onQuickAdd,
                modifier = Modifier.weight(1f)
            )
            NeonActionButton(
                label = "Appointments",
                icon = Icons.Filled.DateRange,
                glowColor = NeonGreen,
                tint = NeonGreenText,
                background = Brush.verticalGradient(listOf(AppointmentsFillTop, AppointmentsFillBottom)),
                glowAlpha = 0.95f,
                onClick = onAppointments,
                modifier = Modifier.weight(1f)
            )
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            NeonActionButton(
                label = "Follow-Ups",
                icon = Icons.Filled.Notifications,
                glowColor = NeonPink,
                tint = NeonPinkText,
                background = Brush.verticalGradient(listOf(FollowUpsFillTop, FollowUpsFillBottom)),
                glowAlpha = 0.95f,
                onClick = onFollowUps,
                modifier = Modifier.weight(1f)
            )
            NeonActionButton(
                label = "Contacts",
                icon = Icons.Filled.Person,
                glowColor = NeonBlue,
                tint = NeonBlueText,
                background = Brush.verticalGradient(listOf(ContactsFillTop, ContactsFillBottom)),
                glowAlpha = 0.62f,
                onClick = onContacts,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun NeonActionButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    glowColor: Color,
    tint: Color,
    background: Brush,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    sheen: Brush? = null,
    glowAlpha: Float = 0.9f
) {
    val shape = RoundedCornerShape(ActionCorner)
    Box(
        modifier = modifier
            .height(ActionVisualHeight + GlowGutter * 2)
            .neonGlow(
                color = glowColor,
                cornerRadius = ActionCorner,
                blurRadius = 13.dp,
                strokeWidth = 3.dp,
                alpha = glowAlpha,
                inset = GlowGutter
            )
            .padding(GlowGutter)
            .clip(shape)
            .background(background)
            .border(
                width = 1.25.dp,
                brush = Brush.verticalGradient(
                    listOf(
                        glowColor.copy(alpha = 0.95f),
                        glowColor.copy(alpha = 0.28f),
                        glowColor.copy(alpha = 0.72f)
                    )
                ),
                shape = shape
            )
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.CenterStart
    ) {
        if (sheen != null) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .padding(1.5.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(sheen)
            )
        } else {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .padding(1.5.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.White.copy(alpha = 0.10f),
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.20f)
                            )
                        )
                    )
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = label,
                modifier = Modifier.weight(1f),
                color = tint,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                lineHeight = 18.sp,
                maxLines = 2,
                softWrap = true,
                overflow = TextOverflow.Visible,
                style = TextStyle(
                    platformStyle = PlatformTextStyle(includeFontPadding = false)
                )
            )
        }
    }
}
