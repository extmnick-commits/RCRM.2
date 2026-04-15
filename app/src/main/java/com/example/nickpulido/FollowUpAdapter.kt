package com.nickpulido.rcrm

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
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
        val tvCategoryLabel: TextView = view.findViewById(R.id.tvCategoryLabel)
        val tvLeadDetails: TextView = view.findViewById(R.id.tvLeadDetails)
        val btnPin: ImageView? = view.findViewById(R.id.btnCompleteIcon)
        val btnCall: ImageView? = view.findViewById(R.id.btnCallIcon)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FollowUpViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_lead_card, parent, false)
        return FollowUpViewHolder(view)
    }

    override fun onBindViewHolder(holder: FollowUpViewHolder, position: Int) {
        val lead = leads[position]
        holder.tvLeadName.text = lead["name"] as? String ?: "Unknown"

        val category = lead["category"] as? String ?: ""
        if (category.isNotEmpty()) {
            holder.tvCategoryLabel.visibility = View.VISIBLE
            holder.tvCategoryLabel.text = category
            
            val bgColor = when {
                category.contains("Recruit", ignoreCase = true) -> Color.parseColor("#4CAF50")
                category.contains("Client", ignoreCase = true) -> Color.parseColor("#9C27B0")
                else -> Color.parseColor("#007BFF")
            }
            val bg = GradientDrawable()
            bg.setColor(bgColor)
            bg.cornerRadius = 16f
            holder.tvCategoryLabel.background = bg
            holder.tvCategoryLabel.setTextColor(Color.WHITE)
        } else {
            holder.tvCategoryLabel.visibility = View.GONE
        }

        val notes = lead["notes"] as? String ?: ""
        val fallbackNote = notes.substringBefore("\n\n").replace(Regex("^\\[.*?\\]: "), "").ifEmpty { "No notes available" }
        
        val followUpDate = lead["followUpDate"] as? Timestamp
        val sdf = SimpleDateFormat("MMM dd, yyyy hh:mm a", Locale.getDefault())
        val dateStr = followUpDate?.toDate()?.let { sdf.format(it) } ?: ""

        if (dateStr.isNotEmpty()) {
            holder.tvLeadDetails.text = "Due: $dateStr\n$fallbackNote"
        } else {
            holder.tvLeadDetails.text = fallbackNote
        }

        val isPinned = lead["isPinned"] as? Boolean ?: false
        
        // Repurposing complete icon logic for pinning, otherwise fallback to long pressing the item
        holder.btnPin?.visibility = View.VISIBLE
        holder.btnPin?.alpha = if (isPinned) 1.0f else 0.3f 
        holder.btnPin?.setOnClickListener {
            val id = lead["id"] as? String ?: return@setOnClickListener
            onPinToggle(id, !isPinned)
        }

        val phone = lead["phone"] as? String ?: ""
        holder.btnCall?.setOnClickListener {
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
        
        holder.itemView.setOnLongClickListener {
            val id = lead["id"] as? String ?: return@setOnLongClickListener true
            onPinToggle(id, !isPinned)
            true
        }
    }

    override fun getItemCount() = leads.size
}