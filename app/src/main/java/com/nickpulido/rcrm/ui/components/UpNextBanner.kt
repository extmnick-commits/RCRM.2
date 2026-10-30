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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nickpulido.rcrm.ui.theme.DarkSpaceBlue
import com.nickpulido.rcrm.ui.theme.GlassPanel
import com.nickpulido.rcrm.ui.theme.NeonCyan
import com.nickpulido.rcrm.ui.theme.NeonGreen
import com.nickpulido.rcrm.ui.theme.NeonPink
import com.nickpulido.rcrm.ui.theme.NeonPurple
import com.nickpulido.rcrm.ui.theme.RCRMTheme
import com.nickpulido.rcrm.ui.theme.TextPrimary
import com.nickpulido.rcrm.ui.theme.TextSecondary

data class UpNextUi(
    val eventId: Long = 0L,
    val startMillis: Long = 0L,
    val endMillis: Long = 0L,
    val headline: String,
    val title: String,
    val countdown: String,
    val timeRange: String,
    val calendarLabel: String,
    val calendarColor: Int,
    val currentIndex: Int = 0,
    val totalUpcomingCount: Int = 1,
    val isLive: Boolean = false,
    val liveBadge: String? = null,
    val leadName: String? = null,
    val locationChip: String? = null,
    val primaryActionLabel: String = "💬 Confirm"
)

@Composable
fun UpNextBanner(
    upNext: UpNextUi,
    onOpenAgenda: () -> Unit,
    onConfirmPrep: () -> Unit,
    modifier: Modifier = Modifier,
    onPrevious: () -> Unit = {},
    onNext: () -> Unit = {},
    onJoinLocation: () -> Unit = {}
) {
    val shape = RoundedCornerShape(20.dp)
    val canSwitch = upNext.totalUpcomingCount > 1
    Column(
        modifier = modifier
            .fillMaxWidth()
            .frostedGlass(
                shape = shape,
                fillColor = GlassPanel,
                borderBrush = Brush.horizontalGradient(
                    listOf(
                        if (upNext.isLive) NeonPink.copy(alpha = 0.85f) else NeonCyan.copy(alpha = 0.75f),
                        if (upNext.isLive) NeonPink.copy(alpha = 0.35f) else NeonCyan.copy(alpha = 0.25f)
                    )
                ),
                borderWidth = 1.dp
            )
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = upNext.headline.uppercase(),
                color = if (upNext.isLive) NeonPink else NeonCyan,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                modifier = Modifier
                    .weight(1f)
                    .semantics { heading() }
            )
            if (canSwitch) {
                SwitcherArrow(
                    label = "<",
                    description = "Previous upcoming event",
                    onClick = onPrevious
                )
                Text(
                    text = "${upNext.currentIndex + 1}/${upNext.totalUpcomingCount}",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
                SwitcherArrow(
                    label = ">",
                    description = "Next upcoming event",
                    onClick = onNext
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            if (upNext.isLive) {
                LiveNowBadge(text = upNext.liveBadge ?: "LIVE NOW")
            } else {
                CountdownPill(text = upNext.countdown)
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = upNext.title,
            color = TextPrimary,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = upNext.timeRange,
                color = TextSecondary,
                fontSize = 13.sp,
                maxLines = 1
            )
            Spacer(modifier = Modifier.width(10.dp))
            CalendarBadge(
                label = upNext.calendarLabel,
                color = Color(upNext.calendarColor),
                modifier = Modifier.weight(1f, fill = false)
            )
        }
        val lead = upNext.leadName?.trim().orEmpty()
        if (lead.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            LeadPill(name = lead)
        }
        val locationChip = upNext.locationChip?.trim().orEmpty()
        if (locationChip.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            LocationChip(label = locationChip, onClick = onJoinLocation)
        }
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            BannerAction(
                label = "Open Agenda",
                accent = NeonCyan,
                onClick = onOpenAgenda,
                modifier = Modifier.weight(1f)
            )
            BannerAction(
                label = upNext.primaryActionLabel,
                accent = NeonGreen,
                onClick = onConfirmPrep,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun SwitcherArrow(
    label: String,
    description: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(NeonCyan.copy(alpha = 0.12f))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = NeonCyan,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun LiveNowBadge(text: String) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(NeonPink.copy(alpha = 0.16f))
            .border(1.dp, NeonPink.copy(alpha = 0.9f), shape)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            text = text,
            color = NeonPink,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

@Composable
private fun CountdownPill(text: String) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(NeonGreen.copy(alpha = 0.14f))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            text = text,
            color = NeonGreen,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

@Composable
private fun LeadPill(name: String) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(NeonCyan.copy(alpha = 0.14f))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            text = "🔗 Lead: $name",
            color = NeonCyan,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun LocationChip(label: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(NeonPurple.copy(alpha = 0.14f))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(
            text = label,
            color = TextPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun CalendarBadge(label: String, color: Color, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = modifier
            .clip(shape)
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            color = TextPrimary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun BannerAction(
    label: String,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(shape)
            .background(accent.copy(alpha = 0.12f))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = TextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

@Preview(showBackground = true, widthDp = 390)
@Composable
private fun UpNextBannerPreview() {
    RCRMTheme {
        Box(modifier = Modifier.background(DarkSpaceBlue).padding(16.dp)) {
            UpNextBanner(
                upNext = UpNextUi(
                    headline = "Up Next Today",
                    title = "Appointment: Dylan Reyes",
                    countdown = "In 45m",
                    timeRange = "3:00 PM – 4:00 PM",
                    calendarLabel = "Work",
                    calendarColor = 0xFF4C8DFF.toInt(),
                    currentIndex = 0,
                    totalUpcomingCount = 3,
                    leadName = "Dylan Reyes",
                    locationChip = "📍 Location / Join"
                ),
                onOpenAgenda = {},
                onConfirmPrep = {}
            )
        }
    }
}

@Preview(showBackground = true, widthDp = 390)
@Composable
private fun UpNextBannerLivePreview() {
    RCRMTheme {
        Box(modifier = Modifier.background(DarkSpaceBlue).padding(16.dp)) {
            UpNextBanner(
                upNext = UpNextUi(
                    headline = "Happening Now",
                    title = "Appointment: Dylan Reyes",
                    countdown = "Now",
                    timeRange = "3:00 PM – 4:00 PM",
                    calendarLabel = "Work",
                    calendarColor = 0xFF4C8DFF.toInt(),
                    currentIndex = 0,
                    totalUpcomingCount = 2,
                    isLive = true,
                    liveBadge = "LIVE NOW • Ends in 18m",
                    leadName = "Dylan Reyes",
                    locationChip = "📍 Location / Join",
                    primaryActionLabel = "✅ Log Outcome"
                ),
                onOpenAgenda = {},
                onConfirmPrep = {}
            )
        }
    }
}
