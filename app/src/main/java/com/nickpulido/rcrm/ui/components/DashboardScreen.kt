package com.nickpulido.rcrm.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nickpulido.rcrm.ui.theme.AdminText
import com.nickpulido.rcrm.ui.theme.DarkSpaceBlue
import com.nickpulido.rcrm.ui.theme.NeonPink
import com.nickpulido.rcrm.ui.theme.GlassFill
import com.nickpulido.rcrm.ui.theme.NeonCyan
import com.nickpulido.rcrm.ui.theme.NeonGreen
import com.nickpulido.rcrm.ui.theme.RCRMTheme
import com.nickpulido.rcrm.ui.theme.SpaceBottom
import com.nickpulido.rcrm.ui.theme.SpaceTop
import com.nickpulido.rcrm.ui.theme.TextMuted
import com.nickpulido.rcrm.ui.theme.TextPrimary
import com.nickpulido.rcrm.ui.theme.TextSecondary

data class DashboardLead(
    val docId: String,
    val name: String,
    val details: String,
    val phone: String,
    val reminderId: String? = null,
    val reminderTitle: String? = null,
    val reminderType: String? = null,
    val dueLabel: String? = null,
    val isOverdue: Boolean = false,
    val isPaused: Boolean = false,
    val isClosed: Boolean = false
)

@Composable
fun DashboardScreen(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    dateLabel: String,
    onHistoryClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onQuickAdd: () -> Unit,
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
    onCallLead: (DashboardLead) -> Unit,
    onTextLead: (DashboardLead) -> Unit,
    onRescheduleLead: (DashboardLead) -> Unit,
    overdueCount: Int,
    dueTodayCount: Int,
    onCompleteLead: (DashboardLead) -> Unit,
    modifier: Modifier = Modifier,
    contactCount: Int = 0,
    isLoading: Boolean = false,
    errorMessage: String? = null,
    onRetry: () -> Unit = {},
    upNext: UpNextUi? = null,
    onUpNextOpenAgenda: () -> Unit = {},
    onUpNextConfirm: () -> Unit = {},
    onUpNextPrevious: () -> Unit = {},
    onUpNextNext: () -> Unit = {},
    onUpNextJoinLocation: () -> Unit = {}
) {
    var showSearch by rememberSaveable { mutableStateOf(false) }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(SpaceTop, DarkSpaceBlue, SpaceBottom)
                )
            )
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 28.dp)
        ) {
            item {
                DashboardHeader(dateLabel, onHistoryClick, onSettingsClick) { showSearch = !showSearch }
                Spacer(Modifier.height(20.dp))
            }
            if (upNext != null) {
                item {
                    UpNextBanner(
                        upNext = upNext,
                        onOpenAgenda = onUpNextOpenAgenda,
                        onConfirmPrep = onUpNextConfirm,
                        onPrevious = onUpNextPrevious,
                        onNext = onUpNextNext,
                        onJoinLocation = onUpNextJoinLocation
                    )
                }
                item { Spacer(modifier = Modifier.height(20.dp)) }
            }
            item {
                DashboardSummary(
                    contactCount, overdueCount, dueTodayCount, onQuickAdd,
                    totalCount, totalGoal, followUpCount, followUpGoal,
                    onTotalGoalClick, onFollowUpGoalClick,
                    onTotalCountLongClick, onFollowUpCountLongClick
                )
                Spacer(Modifier.height(24.dp))
                if (showSearch || searchQuery.isNotEmpty()) {
                    DashboardSearchBar(searchQuery, onSearchQueryChange)
                    Spacer(Modifier.height(16.dp))
                }
            }
            item {
                FollowUpTabs(
                    selectedTab = selectedTab,
                    onTabSelected = onTabSelected
                )
            }
            item { Spacer(modifier = Modifier.height(10.dp)) }
            if (isLoading) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = NeonCyan,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Loading contacts...",
                            color = TextSecondary,
                            fontSize = 14.sp
                        )
                    }
                }
            } else if (errorMessage != null) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = errorMessage,
                            color = Color(0xFFFF5252),
                            fontSize = 14.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(onClick = onRetry) {
                            Text("Retry", color = NeonCyan, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            } else if (leads.isEmpty()) {
                item {
                    if (searchQuery.isNotBlank()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "No contacts match \"$searchQuery\"",
                                color = TextMuted,
                                fontSize = 14.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            TextButton(onClick = { onSearchQueryChange("") }) {
                                Text("Clear search", color = NeonCyan, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    } else {
                        Text(
                            text = when (selectedTab) {
                                0 -> "No urgent attention needed"
                                1 -> "You're caught up. No follow-ups due today."
                                2 -> "No upcoming tasks"
                                else -> "No tasks"
                            },
                            color = TextMuted,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(vertical = 24.dp, horizontal = 8.dp)
                        )
                    }
                }
            } else {
                items(leads, key = { lead -> lead.docId.ifBlank { "${lead.name}-${lead.details}" } }) { lead ->
                    LeadGlassCard(
                        lead = lead,
                        onClick = { onLeadClick(lead.docId) },
                        onCall = { onCallLead(lead) },
                        onText = { onTextLead(lead) },
                        onReschedule = { onRescheduleLead(lead) },
                        onComplete = { onCompleteLead(lead) }
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                }
            }
            if (adminLabel != null) {
                item { Spacer(modifier = Modifier.height(18.dp)) }
                item { AdminGlassPill(label = adminLabel, onClick = onAdminClick) }
            }
        }
    }
}

@Composable
private fun DashboardHeader(dateLabel: String, onHistoryClick: () -> Unit, onSettingsClick: () -> Unit,
                            onSearchClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Home", color = TextPrimary, fontSize = 34.sp, fontWeight = FontWeight.Bold,
                letterSpacing = (-0.8).sp, modifier = Modifier.semantics { heading() })
            Text(dateLabel, color = TextSecondary, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
        }
        IconButton(onClick = onSearchClick) {
            Icon(Icons.Filled.Search, contentDescription = "Show or hide contact search", tint = NeonCyan)
        }
        IconButton(onClick = onHistoryClick) {
            Icon(Icons.Filled.DateRange, contentDescription = "Activity history", tint = NeonCyan)
        }
        IconButton(onClick = onSettingsClick) {
            Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = NeonCyan)
        }
    }
}

@Composable
private fun DashboardSummary(
    contacts: Int, overdue: Int, today: Int, onQuickAdd: () -> Unit,
    totalCount: Int, totalGoal: Int, followUpCount: Int, followUpGoal: Int,
    onTotalGoalClick: () -> Unit, onFollowUpGoalClick: () -> Unit,
    onTotalCountLongClick: () -> Unit, onFollowUpCountLongClick: () -> Unit
) {
    GlassCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(20.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SummaryMetric("Contacts", contacts, NeonCyan, Modifier.weight(1f))
                SummaryMetric("Overdue", overdue, NeonPink, Modifier.weight(1f))
                SummaryMetric("Today", today, NeonGreen, Modifier.weight(1f))
            }
            Button(
                onClick = onQuickAdd, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = DarkSpaceBlue)
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Quick add lead", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                DailyGoal("Contacts made", totalCount, totalGoal, NeonCyan, onTotalGoalClick,
                    onTotalCountLongClick, Modifier.weight(1f))
                DailyGoal("Follow-ups made", followUpCount, followUpGoal, NeonGreen, onFollowUpGoalClick,
                    onFollowUpCountLongClick, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun SummaryMetric(label: String, count: Int, color: Color, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(count.toString(), color = color, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Text(label, color = TextSecondary, fontSize = 13.sp)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DailyGoal(label: String, count: Int, goal: Int, color: Color,
                      onClick: () -> Unit, onLongClick: () -> Unit, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(8.dp)).combinedClickable(
        role = Role.Button, onClickLabel = "Edit goal", onLongClickLabel = "Adjust count",
        onClick = onClick, onLongClick = onLongClick
    ).heightIn(min = 48.dp).padding(vertical = 4.dp)) {
        Text("$count / $goal", color = color, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Text(label, color = TextSecondary, fontSize = 12.sp)
    }
}

@Composable
private fun DashboardSearchBar(query: String, onQueryChange: (String) -> Unit) {
    val focusManager = LocalFocusManager.current
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
        .frostedGlass(RoundedCornerShape(14.dp)), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Search, contentDescription = null, tint = TextSecondary,
            modifier = Modifier.padding(start = 14.dp).size(20.dp))
        Box(Modifier.weight(1f).padding(horizontal = 10.dp), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) Text("Search contacts", color = TextSecondary, fontSize = 15.sp)
            BasicTextField(
                value = query, onValueChange = onQueryChange, singleLine = true,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Search contacts" },
                textStyle = TextStyle(color = TextPrimary, fontSize = 15.sp),
                cursorBrush = SolidColor(NeonCyan),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() })
            )
        }
        if (query.isNotEmpty()) IconButton(onClick = { onQueryChange("") }) {
            Icon(Icons.Filled.Close, contentDescription = "Clear search", tint = TextSecondary)
        }
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
    val labels = listOf("Needs Attention", "Today", "Upcoming")
    Row(modifier = Modifier.fillMaxWidth().selectableGroup()) {
        labels.forEachIndexed { index, label ->
            val selected = index == selectedTab
            Column(
                modifier = Modifier
                    .weight(1f)
                    .selectable(selected = selected, role = Role.Tab, onClick = { onTabSelected(index) })
                    .heightIn(min = 48.dp)
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = label,
                    color = if (selected) NeonCyan else TextSecondary,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .height(3.dp)
                        .fillMaxWidth(0.85f)
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
    onText: () -> Unit,
    onReschedule: () -> Unit,
    onComplete: () -> Unit
) {
    var menuExpanded by remember(lead.docId) { mutableStateOf(false) }
    GlassCard(modifier = Modifier.fillMaxWidth(), onClick = onClick, contentPadding = PaddingValues(16.dp)) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(lead.name, color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(lead.details, color = if (lead.isOverdue) NeonPink else TextSecondary,
                        fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    if (lead.isPaused || lead.isClosed) {
                        Text(if (lead.isPaused) "Paused" else "Closed", color = TextSecondary, fontSize = 12.sp)
                    }
                }
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "More actions for ${lead.name}", tint = TextSecondary)
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(text = { Text("Reschedule") }, onClick = { menuExpanded = false; onReschedule() })
                        DropdownMenuItem(text = { Text("Open contact") }, onClick = { menuExpanded = false; onClick() })
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LeadAction("Call", Icons.Filled.Phone, onCall, lead.phone.isNotBlank())
                LeadAction("Text", Icons.Filled.Email, onText, lead.phone.isNotBlank())
            }
            LeadAction("Log outcome", Icons.Filled.Edit, onComplete)
        }
    }
}

@Composable
private fun LeadAction(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector,
                       onClick: () -> Unit, enabled: Boolean = true) {
    TextButton(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = NeonCyan,
            containerColor = NeonCyan.copy(alpha = 0.10f), disabledContentColor = TextMuted),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium)
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
            adminLabel = "Super Admin Panel",
            onAdminClick = {},
            contactCount = 16,
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
            onTextLead = {},
            onRescheduleLead = {},
            overdueCount = 1,
            dueTodayCount = 2,
            onCompleteLead = {},
            upNext = UpNextUi(
                headline = "Up Next Today",
                title = "Appointment: Dylan Reyes",
                countdown = "In 45m",
                timeRange = "3:00 PM – 4:00 PM",
                calendarLabel = "Work",
                calendarColor = 0xFF4C8DFF.toInt()
            )
        )
    }
}

