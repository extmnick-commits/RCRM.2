package com.nickpulido.rcrm

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.firebase.Timestamp
import com.nickpulido.rcrm.domain.ReminderPlanner
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class FollowUpAdapter(
    private val leads: List<Map<String, Any>>,
    private val onPinToggle: (String, Boolean) -> Unit,
    private val onLeadClick: (Map<String, Any>) -> Unit
) : RecyclerView.Adapter<FollowUpAdapter.FollowUpViewHolder>() {

    class FollowUpViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val cardFollowUp: MaterialCardView = view.findViewById(R.id.cardFollowUp)
        val tvLeadName: TextView = view.findViewById(R.id.tvLeadName)
        val tvCategoryLabel: TextView = view.findViewById(R.id.tvCategoryLabel)
        val tvDueBadge: TextView = view.findViewById(R.id.tvDueBadge)
        val tvLeadDetails: TextView = view.findViewById(R.id.tvLeadDetails)
        val btnPinLead: ImageButton = view.findViewById(R.id.btnPinLead)
        val btnCallLead: ImageView = view.findViewById(R.id.btnCallLead)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FollowUpViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_follow_up, parent, false)
        return FollowUpViewHolder(view)
    }

    override fun onBindViewHolder(holder: FollowUpViewHolder, position: Int) {
        val context = holder.itemView.context
        val lead = leads[position]
        val name = lead["name"] as? String ?: "Unknown"
        val category = lead["category"] as? String ?: ""
        val phone = lead["phone"] as? String ?: ""
        val isPinned = lead["isPinned"] as? Boolean ?: false
        val isPaused = lead["isPaused"] as? Boolean == true
        val isClosed = lead["isClosed"] as? Boolean == true

        val reminderTitle = lead["reminderTitle"] as? String
        val reminderDueTime = lead["reminderDueTime"] as? Long
        val followUpDate = lead["followUpDate"] as? Timestamp
        val sdf = SimpleDateFormat("MMM dd, h:mm a", Locale.getDefault())

        val dueMillis: Long? = when {
            reminderDueTime != null && reminderDueTime > 0L -> reminderDueTime
            followUpDate != null -> followUpDate.toDate().time
            else -> null
        }

        val dueLabel: String?
        val isOverdue: Boolean
        val now = System.currentTimeMillis()

        if (dueMillis != null) {
            isOverdue = dueMillis < now
            dueLabel = if (reminderDueTime != null && reminderDueTime > 0L) {
                ReminderPlanner.dueLabel(reminderDueTime, now)
            } else {
                "Due: ${sdf.format(dueMillis)}"
            }
        } else {
            isOverdue = false
            dueLabel = null
        }

        holder.tvLeadName.text = name

        // Category Badge
        val primaryCategory = category.split(",").firstOrNull()?.trim().orEmpty()
        if (primaryCategory.isNotBlank()) {
            holder.tvCategoryLabel.text = primaryCategory
            holder.tvCategoryLabel.visibility = View.VISIBLE
            val (bgRes, textColor) = when {
                primaryCategory.contains(context.getString(R.string.category_recruit), ignoreCase = true) ->
                    R.drawable.bg_category_recruit to Color.parseColor("#3DFF9A")
                primaryCategory.contains(context.getString(R.string.category_client), ignoreCase = true) ->
                    R.drawable.bg_category_client to Color.parseColor("#B14CFF")
                else ->
                    R.drawable.bg_category_prospect to Color.parseColor("#2EE7FF")
            }
            holder.tvCategoryLabel.background = ContextCompat.getDrawable(context, bgRes)
            holder.tvCategoryLabel.setTextColor(textColor)
        } else {
            holder.tvCategoryLabel.visibility = View.GONE
        }

        // Due Badge & Card Stroke Highlight
        if (dueLabel != null) {
            holder.tvDueBadge.text = dueLabel
            holder.tvDueBadge.visibility = View.VISIBLE
            if (isOverdue) {
                holder.tvDueBadge.background = ContextCompat.getDrawable(context, R.drawable.bg_category_client)
                holder.tvDueBadge.setTextColor(Color.parseColor("#FF3B8D"))
                holder.cardFollowUp.strokeColor = Color.parseColor("#99FF3B8D")
            } else {
                holder.tvDueBadge.background = ContextCompat.getDrawable(context, R.drawable.bg_category_prospect)
                holder.tvDueBadge.setTextColor(Color.parseColor("#2EE7FF"))
                holder.cardFollowUp.strokeColor = Color.parseColor("#802EE7FF")
            }
        } else {
            holder.tvDueBadge.visibility = View.GONE
            holder.cardFollowUp.strokeColor = Color.parseColor("#664C8DFF")
        }

        // Subtitle / Notes / Phone details
        val detailsList = mutableListOf<String>()
        if (!reminderTitle.isNullOrBlank()) {
            detailsList.add(reminderTitle)
        }
        if (phone.isNotBlank()) {
            detailsList.add(phone)
        }
        if (isPaused) detailsList.add("PAUSED")
        if (isClosed) detailsList.add("CLOSED")

        holder.tvLeadDetails.text = if (detailsList.isNotEmpty()) detailsList.joinToString(" • ") else "Follow-up required"

        // Pin Button
        holder.btnPinLead.setImageResource(if (isPinned) android.R.drawable.star_on else android.R.drawable.star_off)
        holder.btnPinLead.setColorFilter(if (isPinned) Color.parseColor("#FFB14CFF") else Color.parseColor("#669AA6BD"))
        holder.btnPinLead.setOnClickListener {
            val id = lead["id"] as? String ?: return@setOnClickListener
            onPinToggle(id, !isPinned)
        }

        // Call Button
        holder.btnCallLead.setOnClickListener {
            if (phone.isNotEmpty()) {
                try {
                    val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone"))
                    context.startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(context, "No dialer available", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(context, "No phone number", Toast.LENGTH_SHORT).show()
            }
        }

        holder.itemView.setOnClickListener {
            onLeadClick(lead)
        }

        holder.itemView.setOnLongClickListener {
            val id = lead["id"] as? String ?: return@setOnLongClickListener true
            onPinToggle(id, !isPinned)
            true
        }
    }

    override fun getItemCount() = leads.size
}
