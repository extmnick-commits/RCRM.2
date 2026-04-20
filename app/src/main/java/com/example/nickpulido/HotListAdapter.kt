package com.nickpulido.rcrm

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.google.firebase.Timestamp
import java.text.SimpleDateFormat
import java.util.Locale

class HotListAdapter(
    private val context: Context,
    private val leads: List<Map<String, Any>>,
    private val onComplete: (String) -> Unit
) : BaseAdapter() {

    var currentTab: Int = 0

    override fun getCount(): Int = leads.size

    override fun getItem(position: Int): Any = leads[position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_lead_card, parent, false)
        val lead = leads[position]
        
        val tvLeadName = view.findViewById<TextView>(R.id.tvLeadName)
        val tvLeadDetails = view.findViewById<TextView>(R.id.tvLeadDetails)
        val tvCategoryLabel = view.findViewById<TextView>(R.id.tvCategoryLabel)
        val btnCallIcon = view.findViewById<ImageView>(R.id.btnCallIcon)
        val btnCompleteIcon = view.findViewById<ImageView>(R.id.btnCompleteIcon)
        
        tvLeadName.text = lead["name"] as? String ?: "Unknown Lead"
        
        val category = lead["category"] as? String ?: ""
        if (category.isNotEmpty()) {
            tvCategoryLabel.visibility = View.VISIBLE
            tvCategoryLabel.text = category.split(",").firstOrNull()?.trim() ?: ""
        } else {
            tvCategoryLabel.visibility = View.GONE
        }

        val followUpDate = lead["followUpDate"] as? Timestamp
        val apptDate = lead["appointmentDate"] as? Timestamp
        
        val sdf = SimpleDateFormat("MMM dd, h:mm a", Locale.getDefault())
        val details = mutableListOf<String>()
        
        if (apptDate != null) {
            details.add("Appt: ${sdf.format(apptDate.toDate())}")
        } else if (followUpDate != null) {
            details.add("Due: ${sdf.format(followUpDate.toDate())}")
        }
        
        val phone = lead["phone"] as? String ?: ""
        if (phone.isNotEmpty()) {
            details.add(phone)
        } else {
            details.add("No Phone")
        }
        
        tvLeadDetails.text = details.joinToString(" | ")
        
        btnCallIcon.setOnClickListener {
            if (phone.isNotEmpty() && phone != "No Phone") {
                val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone"))
                context.startActivity(intent)
            } else {
                Toast.makeText(context, "No phone number", Toast.LENGTH_SHORT).show()
            }
        }
        
        // Only show the complete icon if we are not in the appointments tab (currentTab = 3)
        if (currentTab != 3) {
            btnCompleteIcon.visibility = View.VISIBLE
            btnCompleteIcon.setOnClickListener {
                val docId = lead["__docId"] as? String
                if (docId != null) {
                    onComplete(docId)
                }
            }
        } else {
            btnCompleteIcon.visibility = View.GONE
        }
        
        return view
    }
}