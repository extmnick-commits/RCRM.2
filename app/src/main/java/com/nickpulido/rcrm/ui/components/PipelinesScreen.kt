package com.nickpulido.rcrm.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nickpulido.rcrm.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PipelinesScreen(
    onBack: () -> Unit,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    pipelineType: PipelineType,
    onPipelineTypeChange: (PipelineType) -> Unit,
    leads: List<PipelineLead>,
    onLeadClick: (String) -> Unit,
    onCallLead: (String) -> Unit,
    onStageChange: (String, PipelineType, String) -> Unit,
    modifier: Modifier = Modifier
) {
    var isKanbanMode by remember { mutableStateOf(false) }
    
    val stages = if (pipelineType == PipelineType.SALES) {
        SalesStage.values().map { it.id to it.title }
    } else {
        RecruitStage.values().map { it.id to it.title }
    }
    
    val leadsByStage = stages.associate { stage ->
        stage.first to leads.filter { 
            if (pipelineType == PipelineType.SALES) it.salesStage.id == stage.first 
            else it.recruitStage.id == stage.first 
        }
    }

    val conversionCount = leadsByStage.values.last().size
    val totalActive = leads.size
    val conversionRate = if (totalActive > 0) (conversionCount * 100 / totalActive) else 0

    var selectedStageIndex by remember { mutableStateOf(0) }
    if (selectedStageIndex >= stages.size) selectedStageIndex = 0

    val pagerState = rememberPagerState(pageCount = { stages.size })
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(selectedStageIndex) {
        if (isKanbanMode && pagerState.currentPage != selectedStageIndex) {
            pagerState.animateScrollToPage(selectedStageIndex)
        }
    }

    LaunchedEffect(pagerState.currentPage) {
        if (isKanbanMode && selectedStageIndex != pagerState.currentPage) {
            selectedStageIndex = pagerState.currentPage
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(SpaceTop, DarkSpaceBlue, SpaceBottom)))
    ) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            // Top Header Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(imageVector = Icons.Filled.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                }
                Text(
                    text = "Visual Pipelines",
                    color = TextPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
            }
            
            // Search Bar (simplified for space)
            Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .frostedGlass(shape = RoundedCornerShape(26.dp), fillColor = GlassFill),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = "Search",
                        tint = TextSecondary,
                        modifier = Modifier.padding(start = 14.dp).size(20.dp)
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 10.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        if (searchQuery.isEmpty()) {
                            Text(
                                text = "Search pipelines...",
                                color = TextSecondary,
                                fontSize = 15.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        androidx.compose.foundation.text.BasicTextField(
                            value = searchQuery,
                            onValueChange = onSearchQueryChange,
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(color = TextPrimary, fontSize = 15.sp),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(NeonCyan),
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { focusManager.clearFocus() })
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))

            // Segmented Toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .height(48.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(GlassFill)
                    .then(Modifier.border(1.dp, GlassFrost, RoundedCornerShape(24.dp)))
            ) {
                PipelineType.values().forEach { type ->
                    val isSelected = pipelineType == type
                    val color = if (type == PipelineType.SALES) NeonCyan else NeonPurple
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(24.dp))
                            .background(if (isSelected) color.copy(alpha = 0.2f) else Color.Transparent)
                            .clickable { onPipelineTypeChange(type) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = type.label,
                            color = if (isSelected) color else TextSecondary,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Summary Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Total Active: $totalActive",
                    color = TextPrimary,
                    fontSize = 14.sp
                )
                Text(
                    text = "Conversion Rate: $conversionRate%",
                    color = if(pipelineType == PipelineType.SALES) NeonCyan else NeonPink,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            
            Spacer(modifier = Modifier.height(12.dp))

            // Stage Pills & View Toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LazyRow(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(stages.size) { index ->
                        val stage = stages[index]
                        val count = leadsByStage[stage.first]?.size ?: 0
                        val isSelected = selectedStageIndex == index
                        val accentColor = if(pipelineType == PipelineType.SALES) NeonCyan else NeonPink
                        
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (isSelected) accentColor.copy(alpha = 0.2f) else GlassPanel)
                                .border(1.dp, if (isSelected) accentColor else GlassFrost, RoundedCornerShape(16.dp))
                                .clickable { 
                                    selectedStageIndex = index 
                                    if(isKanbanMode) {
                                        coroutineScope.launch { pagerState.animateScrollToPage(index) }
                                    }
                                }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = "${stage.second} ($count)",
                                color = if (isSelected) accentColor else TextSecondary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(onClick = { isKanbanMode = !isKanbanMode }) {
                    Icon(
                        imageVector = if (isKanbanMode) Icons.Filled.List else Icons.Filled.Search,
                        contentDescription = "Toggle View",
                        tint = TextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (isKanbanMode) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    pageSpacing = 16.dp
                ) { page ->
                    val stage = stages[page]
                    val stageLeads = leadsByStage[stage.first] ?: emptyList()
                    StageColumn(
                        leads = stageLeads,
                        pipelineType = pipelineType,
                        stages = stages,
                        currentStageIndex = page,
                        onLeadClick = onLeadClick,
                        onCallLead = onCallLead,
                        onStageChange = onStageChange
                    )
                }
            } else {
                val stage = stages[selectedStageIndex]
                val stageLeads = leadsByStage[stage.first] ?: emptyList()
                Box(modifier = Modifier.weight(1f).padding(horizontal = 16.dp)) {
                    StageColumn(
                        leads = stageLeads,
                        pipelineType = pipelineType,
                        stages = stages,
                        currentStageIndex = selectedStageIndex,
                        onLeadClick = onLeadClick,
                        onCallLead = onCallLead,
                        onStageChange = onStageChange
                    )
                }
            }
        }
    }
}

@Composable
fun StageColumn(
    leads: List<PipelineLead>,
    pipelineType: PipelineType,
    stages: List<Pair<String, String>>,
    currentStageIndex: Int,
    onLeadClick: (String) -> Unit,
    onCallLead: (String) -> Unit,
    onStageChange: (String, PipelineType, String) -> Unit
) {
    if (leads.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No leads in this stage.", color = TextMuted, fontSize = 14.sp)
        }
    } else {
        LazyColumn(
            contentPadding = PaddingValues(top = 8.dp, bottom = 80.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(leads) { lead ->
                LeadPipelineCard(
                    lead = lead,
                    pipelineType = pipelineType,
                    stages = stages,
                    currentStageIndex = currentStageIndex,
                    onClick = { onLeadClick(lead.id) },
                    onCall = { onCallLead(lead.phone) },
                    onStageChange = onStageChange
                )
            }
        }
    }
}

@Composable
fun LeadPipelineCard(
    lead: PipelineLead,
    pipelineType: PipelineType,
    stages: List<Pair<String, String>>,
    currentStageIndex: Int,
    onClick: () -> Unit,
    onCall: () -> Unit,
    onStageChange: (String, PipelineType, String) -> Unit
) {
    val accentColor = if(pipelineType == PipelineType.SALES) NeonCyan else NeonPurple
    val tmColor = when (lead.targetMarketPoints) {
        in 4..5 -> NeonGreen
        3 -> Color(0xFFFFA726) // Orange
        else -> NeonPink
    }
    val tmPct = (lead.targetMarketPoints * 20)

    GlassCard(
        onClick = onClick,
        contentPadding = PaddingValues(16.dp),
        modifier = Modifier.fillMaxWidth().neonGlow(color = accentColor, alpha = 0.1f)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${lead.name} ${if (lead.isPinned) "🔥" else ""}",
                    color = TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(tmColor.copy(alpha = 0.2f))
                        .border(1.dp, tmColor.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "${lead.targetMarketPoints}/5 TM • $tmPct%",
                        color = tmColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = lead.lastNotePreview,
                color = TextSecondary,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!lead.followUpDateLabel.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Follow-up: ${lead.followUpDateLabel}",
                    color = NeonBlue,
                    fontSize = 12.sp
                )
            }
            if (lead.isMissingPlan) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Needs a next step",
                    color = NeonPink,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            } else if (lead.isOverdue) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Overdue",
                    color = NeonPink,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            if (!lead.lastConnectedContactLabel.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = lead.lastConnectedContactLabel,
                    color = TextSecondary,
                    fontSize = 11.sp
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            
            // Quick Actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Prev Button
                if (currentStageIndex > 0) {
                    IconButton(
                        onClick = { onStageChange(lead.id, pipelineType, stages[currentStageIndex - 1].first) },
                        modifier = Modifier.size(32.dp).clip(CircleShape).background(GlassPanel)
                    ) {
                        Icon(Icons.Filled.KeyboardArrowLeft, contentDescription = "Prev Stage", tint = TextPrimary)
                    }
                } else {
                    Spacer(modifier = Modifier.size(32.dp))
                }

                // Stage Dropdown (simulated with a click to open a dialog ideally, but simplified here)
                // Just showing current stage text for now
                Text(
                    text = stages[currentStageIndex].second,
                    color = accentColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )

                // Next Button
                if (currentStageIndex < stages.size - 1) {
                    IconButton(
                        onClick = { onStageChange(lead.id, pipelineType, stages[currentStageIndex + 1].first) },
                        modifier = Modifier.size(32.dp).clip(CircleShape).background(GlassPanel)
                    ) {
                        Icon(Icons.Filled.KeyboardArrowRight, contentDescription = "Next Stage", tint = TextPrimary)
                    }
                } else {
                    Spacer(modifier = Modifier.size(32.dp))
                }

                // Call
                IconButton(
                    onClick = onCall,
                    modifier = Modifier.size(32.dp).clip(CircleShape).background(Color(0xFF0E3A28))
                ) {
                    Icon(Icons.Filled.Call, contentDescription = "Call", tint = NeonGreen, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}
