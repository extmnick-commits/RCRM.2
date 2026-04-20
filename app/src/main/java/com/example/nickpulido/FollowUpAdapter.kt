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
import java.text.SimpleDateFormat
import java.util.Locale

class FollowUpAdapter(
    private val leads: List<Map<String, Any>>,
    private val onPinToggle: (String, Boolean) -> Unit,
    private val onLeadClick: (Map<String, Any>) -> Unit
) : RecyclerView.Adapter<FollowUpAdapter.FollowUpViewHolder>() {

    inner class FollowUpViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvLeadName: TextView = view.findViewById(R.id.tvLeadName)
        val tvLeadDetails: TextView = view.findViewById(R.id.tvLeadDetails)
        val btnPin: ImageButton = view.findViewById(R.id.btnPinLead)
        val btnCall: ImageButton = view.findViewById(R.id.btnCallLead)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FollowUpViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_follow_up, parent, false)
        return FollowUpViewHolder(view)
    }

    override fun onBindViewHolder(holder: FollowUpViewHolder, position: Int) {
        val lead = leads[position]
        holder.tvLeadName.text = lead["name"] as? String ?: "Unknown"

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

        holder.tvLeadDetails.text = if (detailsParts.isNotEmpty()) detailsParts.joinToString(" | ") else "No details"

        val isPinned = lead["isPinned"] as? Boolean ?: false
        holder.btnPin.setImageResource(if (isPinned) android.R.drawable.star_on else android.R.drawable.star_off)

        holder.btnPin.setOnClickListener {
            val id = lead["id"] as? String ?: return@setOnClickListener
            onPinToggle(id, !isPinned)
        }

        val phone = lead["phone"] as? String ?: ""
        holder.btnCall.setOnClickListener {
            if (phone.isNotEmpty()) {
                val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone"))
                holder.itemView.context.startActivity(intent)
            } else {
                Toast.makeText(holder.itemView.context, "No phone number", Toast.LENGTH_SHORT).show()
            }
        }
        
        holder.itemView.setOnClickListener {
            onLeadClick(lead)
        }
        
        // The user can still long-press the item to pin, which is a good UX fallback.
        holder.itemView.setOnLongClickListener {
            val id = lead["id"] as? String ?: return@setOnLongClickListener true
            onPinToggle(id, !isPinned)
            true
        }
    }

    override fun getItemCount() = leads.size
}