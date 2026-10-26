package com.nickpulido.rcrm

import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.Timestamp
import com.nickpulido.rcrm.domain.ReminderPlanner
import java.text.SimpleDateFormat
import java.util.Locale

class FollowUpAdapter(
    private val leads: List<Map<String, Any>>,
    private val onPinToggle: (String, Boolean) -> Unit,
    private val onLeadClick: (Map<String, Any>) -> Unit
) : RecyclerView.Adapter<FollowUpAdapter.FollowUpViewHolder>() {

    class FollowUpViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvLeadName: TextView = view.findViewById(R.id.tvLeadName)
        val tvLeadDetails: TextView = view.findViewById(R.id.tvLeadDetails)
        val btnPinLead: ImageButton = view.findViewById(R.id.btnPinLead)
        val btnCallLead: ImageButton = view.findViewById(R.id.btnCallLead)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FollowUpViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_follow_up, parent, false)
        return FollowUpViewHolder(view)
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
        val reminderDueTime = lead["reminderDueTime"] as? Long
        val followUpDate = lead["followUpDate"] as? Timestamp
        val sdf = SimpleDateFormat("MMM dd, h:mm a", Locale.getDefault())

        val dueLabel: String?
        if (reminderDueTime != null && reminderDueTime > 0L) {
            dueLabel = ReminderPlanner.dueLabel(reminderDueTime, System.currentTimeMillis())
        } else if (followUpDate != null) {
            dueLabel = "Due: ${sdf.format(followUpDate.toDate())}"
        } else {
            dueLabel = null
        }

        val primaryDetail = when {
            !reminderTitle.isNullOrBlank() -> reminderTitle
            category.isNotBlank() -> category.split(",").first().trim()
            else -> "Follow-up required"
        }

        val detailsText = mutableListOf<String>()
        detailsText.add(primaryDetail)
        if (dueLabel != null) detailsText.add(dueLabel)
        if (phone.isNotBlank()) detailsText.add(phone)
        if (isPaused) detailsText.add("PAUSED")
        if (isClosed) detailsText.add("CLOSED")

        holder.tvLeadName.text = name
        holder.tvLeadDetails.text = detailsText.joinToString(" • ")

        holder.btnPinLead.setImageResource(if (isPinned) android.R.drawable.star_on else android.R.drawable.star_off)
        holder.btnPinLead.setOnClickListener {
            val id = lead["id"] as? String ?: return@setOnClickListener
            onPinToggle(id, !isPinned)
        }

        holder.btnCallLead.setOnClickListener {
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
