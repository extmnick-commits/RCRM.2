package com.nickpulido.rcrm

import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.Timestamp
import com.nickpulido.rcrm.domain.ReminderPlanner
import com.nickpulido.rcrm.ui.components.GlassCard
import com.nickpulido.rcrm.ui.theme.NeonCyan
import com.nickpulido.rcrm.ui.theme.NeonGreen
import com.nickpulido.rcrm.ui.theme.RCRMTheme
import com.nickpulido.rcrm.ui.theme.TextPrimary
import com.nickpulido.rcrm.ui.theme.TextSecondary
import java.text.SimpleDateFormat
import java.util.Locale

class FollowUpAdapter(
    private val leads: List<Map<String, Any>>,
    private val onPinToggle: (String, Boolean) -> Unit,
    private val onLeadClick: (Map<String, Any>) -> Unit
) : RecyclerView.Adapter<FollowUpAdapter.FollowUpViewHolder>() {

    inner class FollowUpViewHolder(val composeView: ComposeView) : RecyclerView.ViewHolder(composeView)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FollowUpViewHolder {
        val composeView = ComposeView(parent.context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        return FollowUpViewHolder(composeView)
    }

    override fun onBindViewHolder(holder: FollowUpViewHolder, position: Int) {
        val lead = leads[position]
        val name = lead["name"] as? String ?: "Unknown"
        val category = lead["category"] as? String ?: ""
        val phone = lead["phone"] as? String ?: ""
        val isPinned = lead["isPinned"] as? Boolean ?: false
        val isPaused = lead["isPaused"] as? Boolean == true
        val isClosed = lead["isClosed"] as? Boolean == true

        val reminderTitle = lead["reminderTitle"] as? String
        val reminderType = lead["reminderType"] as? String
        val reminderDueTime = lead["reminderDueTime"] as? Long

        val followUpDate = lead["followUpDate"] as? Timestamp
        val sdf = SimpleDateFormat("MMM dd, h:mm a", Locale.getDefault())

        val dueLabel: String?
        val isOverdue: Boolean
        if (reminderDueTime != null && reminderDueTime > 0L) {
            dueLabel = ReminderPlanner.dueLabel(reminderDueTime, System.currentTimeMillis())
            isOverdue = dueLabel.startsWith("Overdue")
        } else if (followUpDate != null) {
            dueLabel = "Due: ${sdf.format(followUpDate.toDate())}"
            isOverdue = followUpDate.toDate().time < System.currentTimeMillis()
        } else {
            dueLabel = null
            isOverdue = false
        }

        val primaryDetail = when {
            !reminderTitle.isNullOrBlank() -> reminderTitle
            category.isNotBlank() -> category.split(",").first().trim()
            else -> "Follow-up required"
        }

        holder.composeView.setContent {
            RCRMTheme {
                GlassCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                    onClick = { onLeadClick(lead) }
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = name,
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                if (isPaused) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(Color(0x33FFB300))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = "PAUSED",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFFFFB300)
                                        )
                                    }
                                }
                                if (isClosed) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(Color(0x338E99B0))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = "CLOSED",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF8E99B0)
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = primaryDetail,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = TextSecondary,
                                maxLines = 1
                            )
                            if (dueLabel != null || phone.isNotBlank()) {
                                Spacer(modifier = Modifier.height(2.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (dueLabel != null) {
                                        Text(
                                            text = dueLabel,
                                            fontSize = 12.sp,
                                            fontWeight = if (isOverdue) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isOverdue) Color(0xFFFF5252) else NeonCyan
                                        )
                                        if (phone.isNotBlank()) {
                                            Text(
                                                text = " • ",
                                                fontSize = 12.sp,
                                                color = TextSecondary
                                            )
                                        }
                                    }
                                    if (phone.isNotBlank()) {
                                        Text(
                                            text = phone,
                                            fontSize = 12.sp,
                                            color = TextSecondary
                                        )
                                    }
                                }
                            }
                        }
                        IconButton(onClick = {
                            if (phone.isNotEmpty()) {
                                try {
                                    val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone"))
                                    holder.itemView.context.startActivity(intent)
                                } catch (e: Exception) {
                                    Toast.makeText(holder.itemView.context, "No dialer available", Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                Toast.makeText(holder.itemView.context, "No phone number", Toast.LENGTH_SHORT).show()
                            }
                        }) {
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .background(Color(0xFF0E3A28), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    painter = painterResource(android.R.drawable.ic_menu_call),
                                    contentDescription = "Call",
                                    tint = NeonGreen
                                )
                            }
                        }
                        IconButton(onClick = {
                            val id = lead["id"] as? String ?: return@IconButton
                            onPinToggle(id, !isPinned)
                        }) {
                            Icon(
                                painter = painterResource(if (isPinned) android.R.drawable.star_on else android.R.drawable.star_off),
                                contentDescription = "Pin",
                                tint = if (isPinned) NeonCyan else TextSecondary
                            )
                        }
                    }
                }
            }
        }

        holder.composeView.setOnLongClickListener {
            val id = lead["id"] as? String ?: return@setOnLongClickListener true
            onPinToggle(id, !isPinned)
            true
        }
    }

    override fun getItemCount() = leads.size
}