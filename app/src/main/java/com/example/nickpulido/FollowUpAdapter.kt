package com.nickpulido.rcrm

import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.Timestamp
import com.nickpulido.rcrm.ui.components.GlassCard
import com.nickpulido.rcrm.ui.theme.NeonCyan
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

        val followUpDate = lead["followUpDate"] as? Timestamp
        val sdf = SimpleDateFormat("MMM dd", Locale.getDefault())
        val dateStr = followUpDate?.toDate()?.let { "Due: ${sdf.format(it)}" } ?: ""

        val detailsParts = mutableListOf<String>()
        if (category.isNotEmpty()) {
            detailsParts.add(category.split(",").first().trim())
        }
        if (dateStr.isNotEmpty()) {
            detailsParts.add(dateStr)
        }

        val details = if (detailsParts.isNotEmpty()) detailsParts.joinToString(" | ") else "No details"
        val isPinned = lead["isPinned"] as? Boolean ?: false
        val phone = lead["phone"] as? String ?: ""

        holder.composeView.setContent {
            RCRMTheme {
                GlassCard(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
                    onClick = { onLeadClick(lead) }
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = name, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(text = details, fontSize = 13.sp, color = TextSecondary)
                        }
                        IconButton(onClick = {
                            if (phone.isNotEmpty()) {
                                val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone"))
                                holder.itemView.context.startActivity(intent)
                            } else {
                                Toast.makeText(holder.itemView.context, "No phone number", Toast.LENGTH_SHORT).show()
                            }
                        }) {
                            Icon(
                                painter = painterResource(android.R.drawable.ic_menu_call),
                                contentDescription = "Call",
                                tint = NeonCyan
                            )
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