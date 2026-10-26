package com.nickpulido.rcrm.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nickpulido.rcrm.ui.theme.GlassFill
import com.nickpulido.rcrm.ui.theme.NeonCyan
import com.nickpulido.rcrm.ui.theme.NeonGreen
import com.nickpulido.rcrm.ui.theme.TextPrimary
import com.nickpulido.rcrm.ui.theme.TextSecondary

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HeaderGoalBadge(
    count: Int,
    goal: Int,
    color: Color,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val progress = if (goal > 0) (count.toFloat() / goal).coerceIn(0f, 1f) else 0f
    val shape = RoundedCornerShape(18.dp)

    Row(
        modifier = modifier
            .height(36.dp)
            .frostedGlass(
                shape = shape,
                fillColor = Color(0xCC101828),
                frostColor = Color.Transparent,
                borderBrush = Brush.horizontalGradient(
                    listOf(color.copy(alpha = 0.8f), color.copy(alpha = 0.3f))
                ),
                borderWidth = 1.dp
            )
            .combinedClickable(
                role = Role.Button,
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(start = 6.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        GlowingProgressIndicator(
            progress = progress,
            color = color,
            indicatorSize = 24.dp,
            strokeWidth = 2.5.dp,
            glowRadius = 3.dp
        ) {
            Text(
                text = count.toString(),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
        }
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = "/$goal",
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = TextSecondary
        )
    }
}

@Composable
fun DashboardMeter(
    title: String,
    count: Int,
    goal: Int,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = {}
) {
    val progress = if (goal > 0) (count.toFloat() / goal).coerceIn(0f, 1f) else 0f

    GlassCard(
        modifier = modifier.fillMaxWidth(),
        onClick = onClick,
        onLongClick = onLongClick,
        fillColor = GlassFill
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
        ) {
            GlowingProgressIndicator(
                progress = progress,
                color = color
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = count.toString(),
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        text = "Goal: $goal",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = TextSecondary
            )
        }
    }
}

@Composable
fun CompactDashboardMeter(
    title: String,
    count: Int,
    goal: Int,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = {}
) {
    val progress = if (goal > 0) (count.toFloat() / goal).coerceIn(0f, 1f) else 0f

    GlassCard(
        modifier = modifier.fillMaxWidth(),
        onClick = onClick,
        onLongClick = onLongClick,
        fillColor = GlassFill
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            GlowingProgressIndicator(
                progress = progress,
                color = color,
                indicatorSize = 42.dp,
                strokeWidth = 4.dp,
                glowRadius = 5.dp
            ) {
                Text(
                    text = count.toString(),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    text = title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "Goal: $goal",
                    fontSize = 11.sp,
                    color = TextSecondary,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
fun CompactGoalDialsBar(
    totalCount: Int,
    totalGoal: Int,
    followUpCount: Int,
    followUpGoal: Int,
    onTotalGoalClick: () -> Unit,
    onFollowUpGoalClick: () -> Unit,
    onTotalCountLongClick: () -> Unit,
    onFollowUpCountLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        CompactDashboardMeter(
            title = "Contacts",
            count = totalCount,
            goal = totalGoal,
            color = NeonCyan,
            onClick = onTotalGoalClick,
            onLongClick = onTotalCountLongClick,
            modifier = Modifier.weight(1f)
        )
        CompactDashboardMeter(
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
