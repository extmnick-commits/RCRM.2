package com.nickpulido.rcrm.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nickpulido.rcrm.ui.theme.AdminText
import com.nickpulido.rcrm.ui.theme.DarkSpaceBlue
import com.nickpulido.rcrm.ui.theme.GlassFill
import com.nickpulido.rcrm.ui.theme.NeonBlue
import com.nickpulido.rcrm.ui.theme.NeonCyan
import com.nickpulido.rcrm.ui.theme.NeonGreen
import com.nickpulido.rcrm.ui.theme.NeonPurple
import com.nickpulido.rcrm.ui.theme.RCRMTheme
import com.nickpulido.rcrm.ui.theme.SpaceBottom
import com.nickpulido.rcrm.ui.theme.SpaceHaze
import com.nickpulido.rcrm.ui.theme.SpaceTop
import com.nickpulido.rcrm.ui.theme.TextMuted
import com.nickpulido.rcrm.ui.theme.TextPrimary
import com.nickpulido.rcrm.ui.theme.TextSecondary

data class DashboardLead(
    val docId: String,
    val name: String,
    val details: String,
    val phone: String
)

@Composable
fun DashboardScreen(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    dateLabel: String,
    onHistoryClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onQuickAdd: () -> Unit,
    onAppointments: () -> Unit,
    onFollowUps: () -> Unit,
    onContacts: () -> Unit,
    onPipelines: () -> Unit,
    adminLabel: String?,
    onAdminClick: () -> Unit,
    totalCount: Int,
    totalGoal: Int,
    followUpCount: Int,
    followUpGoal: Int,
    onTotalGoalClick: () -> Unit,
    onFollowUpGoalClick: () -> Unit,
    onTotalCountLongClick: () -> Unit,
    onFollowUpCountLongClick: () -> Unit,
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
    leads: List<DashboardLead>,
    onLeadClick: (String) -> Unit,
    onCallLead: (String) -> Unit,
    onCompleteLead: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(SpaceTop, DarkSpaceBlue, SpaceBottom)
                )
            )
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(340.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, SpaceHaze.copy(alpha = 0.85f))
                    )
                )
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 28.dp)
        ) {
            item {
                DashboardSearchBar(
                    query = searchQuery,
                    onQueryChange = onSearchQueryChange,
                    onSettingsClick = onSettingsClick
                )
            }
            item { Spacer(modifier = Modifier.height(20.dp)) }
            item {
                DashboardHeader(
                    dateLabel = dateLabel,
                    onHistoryClick = onHistoryClick
                )
            }
            item { Spacer(modifier = Modifier.height(8.dp)) }
            item {
                QuickActionsGrid(
                    onQuickAdd = onQuickAdd,
                    onAppointments = onAppointments,
                    onFollowUps = onFollowUps,
                    onContacts = onContacts,
                    onPipelines = onPipelines
                )
            }
            if (adminLabel != null) {
                item { Spacer(modifier = Modifier.height(6.dp)) }
                item {
                    AdminGlassPill(
                        label = adminLabel,
                        onClick = onAdminClick
                    )
                }
            }
            item { Spacer(modifier = Modifier.height(16.dp)) }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    DashboardMeter(
                        title = "Contacts",
                        count = totalCount,
                        goal = totalGoal,
                        color = NeonCyan,
                        onClick = onTotalGoalClick,
                        onLongClick = onTotalCountLongClick,
                        modifier = Modifier.weight(1f)
                    )
                    DashboardMeter(
                        title = "Follow-Ups",
                        count = followUpCount,
                        goal = followUpGoal,
                        color = NeonGreen,
                        onClick = onFollowUpGoalClick,
                        onLongClick = onFollowUpCountLongClick,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            item { Spacer(modifier = Modifier.height(18.dp)) }
            item {
                FollowUpTabs(
                    selectedTab = selectedTab,
                    onTabSelected = onTabSelected
                )
            }
            item { Spacer(modifier = Modifier.height(8.dp)) }
            if (leads.isEmpty()) {
                item {
                    Text(
                        text = when (selectedTab) {
                            1 -> "Nothing scheduled tomorrow"
                            2 -> "No overdue follow-ups"
                            else -> "No follow-ups today"
                        },
                        color = TextMuted,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(vertical = 28.dp, horizontal = 8.dp)
                    )
                }
            } else {
                items(leads, key = { lead -> lead.docId.ifBlank { "${lead.name}-${lead.details}" } }) { lead ->
                    LeadGlassCard(
                        lead = lead,
                        onClick = { onLeadClick(lead.docId) },
                        onCall = { onCallLead(lead.phone) },
                        onComplete = { onCompleteLead(lead.docId) }
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                }
            }
        }
    }
}

@Composable
private fun DashboardSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onSettingsClick: () -> Unit
) {
    val focusManager = LocalFocusManager.current
    val shape = RoundedCornerShape(26.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp)
            .frostedGlass(shape = shape, fillColor = GlassFill),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Filled.Search,
            contentDescription = "Search",
            tint = TextMuted,
            modifier = Modifier.padding(start = 14.dp).size(20.dp)
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            if (query.isEmpty()) {
                Text(
                    text = "Search contacts, tasks, or follow-ups...",
                    color = TextMuted,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = TextStyle(color = TextPrimary, fontSize = 14.sp),
                cursorBrush = SolidColor(NeonCyan),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() })
            )
        }
        Box(
            modifier = Modifier
                .padding(end = 6.dp)
                .size(36.dp)
                .clip(CircleShape)
                .clickable(role = Role.Button, onClick = onSettingsClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Build,
                contentDescription = "Settings",
                tint = TextSecondary,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun DashboardHeader(
    dateLabel: String,
    onHistoryClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "RCRM",
                color = TextPrimary,
                fontSize = 30.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-0.6).sp
            )
            Text(
                text = dateLabel,
                color = NeonCyan,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        HistoryPill(onClick = onHistoryClick)
    }
}

@Composable
private fun HistoryPill(onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier = Modifier
            .height(36.dp)
            .frostedGlass(
                shape = shape,
                fillColor = Color(0xCC101828),
                frostColor = Color.Transparent,
                borderBrush = Brush.horizontalGradient(listOf(NeonBlue, NeonPurple)),
                borderWidth = 1.25.dp
            )
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Filled.DateRange,
            contentDescription = null,
            tint = TextPrimary,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = "History",
            color = TextPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun AdminGlassPill(
    label: String,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(28.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .frostedGlass(shape = shape, fillColor = GlassFill)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = AdminText,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun FollowUpTabs(
    selectedTab: Int,
    onTabSelected: (Int) -> Unit
) {
    val labels = listOf("Today", "Tomorrow", "Overdue")
    Row(modifier = Modifier.fillMaxWidth()) {
        labels.forEachIndexed { index, label ->
            val selected = index == selectedTab
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable { onTabSelected(index) }
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = label,
                    color = if (selected) NeonCyan else TextSecondary,
                    fontSize = 15.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .height(3.dp)
                        .width(32.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (selected) NeonCyan else Color.Transparent)
                )
            }
        }
    }
}

@Composable
private fun LeadGlassCard(
    lead: DashboardLead,
    onClick: () -> Unit,
    onCall: () -> Unit,
    onComplete: () -> Unit
) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .frostedGlass(shape = shape, fillColor = GlassFill)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = lead.name,
                color = TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = lead.details,
                color = TextSecondary,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .size(52.dp)
                .neonGlow(
                    color = NeonGreen,
                    cornerRadius = 26.dp,
                    blurRadius = 7.dp,
                    strokeWidth = 2.dp,
                    alpha = 0.9f,
                    inset = 8.dp
                )
                .padding(8.dp)
                .clip(CircleShape)
                .background(Color(0xFF0E3A28))
                .clickable(role = Role.Button, onClick = onCall),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Phone,
                contentDescription = "Call",
                tint = NeonGreen,
                modifier = Modifier.size(18.dp)
            )
        }
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .clickable(role = Role.Button, onClick = onComplete),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "Complete follow-up",
                tint = TextSecondary,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun DashboardScreenPreview() {
    RCRMTheme {
        DashboardScreen(
            searchQuery = "",
            onSearchQueryChange = {},
            dateLabel = "Today",
            onHistoryClick = {},
            onSettingsClick = {},
            onQuickAdd = {},
            onAppointments = {},
            onFollowUps = {},
            onContacts = {},
            onPipelines = {},
            adminLabel = "Super Admin Panel",
            onAdminClick = {},
            totalCount = 1,
            totalGoal = 10,
            followUpCount = 0,
            followUpGoal = 5,
            onTotalGoalClick = {},
            onFollowUpGoalClick = {},
            onTotalCountLongClick = {},
            onFollowUpCountLongClick = {},
            selectedTab = 0,
            onTabSelected = {},
            leads = listOf(
                DashboardLead(
                    docId = "1",
                    name = "Dylan - finger print Nursing",
                    details = "Appt: Oct 06, 10:00 AM | 2095496004",
                    phone = "2095496004"
                )
            ),
            onLeadClick = {},
            onCallLead = {},
            onCompleteLead = {}
        )
    }
}
