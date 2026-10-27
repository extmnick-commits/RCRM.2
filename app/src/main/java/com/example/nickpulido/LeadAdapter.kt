package com.nickpulido.rcrm

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.graphics.toColorInt
import androidx.core.net.toUri

class LeadAdapter(
    context: Context,
    private val leads: List<Map<String, Any>>
) : ArrayAdapter<Map<String, Any>>(context, 0, leads) {

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_lead_card, parent, false)
        val tvName = view.findViewById<TextView>(R.id.tvLeadName)
        val tvDetails = view.findViewById<TextView>(R.id.tvLeadDetails)
        val tvCategoryLabel = view.findViewById<TextView>(R.id.tvCategoryLabel)
        val btnCallIcon = view.findViewById<ImageView>(R.id.btnCallIcon)
        // CheckBox is not used in MainActivity's Hot List
        view.findViewById<View>(R.id.cbSelectLead)?.visibility = View.GONE

        val lead = leads[position]
        val name = lead["name"] as? String ?: context.getString(R.string.unknown_contact)
        val category = lead["category"] as? String ?: ""
        val notes = lead["notes"] as? String ?: ""

        tvName.text = context.getString(R.string.lead_name_format, name)
        
        if (category.isNotEmpty()) {
            tvCategoryLabel.text = category
            tvCategoryLabel.visibility = View.VISIBLE
            
            val (bgRes, textColor) = when {
                category.contains(context.getString(R.string.category_recruit), ignoreCase = true) -> 
                    R.drawable.bg_category_recruit to Color.parseColor("#3DFF9A")
                category.contains(context.getString(R.string.category_client), ignoreCase = true) -> 
                    R.drawable.bg_category_client to Color.parseColor("#B14CFF")
                else -> 
                    R.drawable.bg_category_prospect to Color.parseColor("#2EE7FF")
            }
            tvCategoryLabel.background = androidx.core.content.ContextCompat.getDrawable(context, bgRes)
            tvCategoryLabel.setTextColor(textColor)
        } else {
            tvCategoryLabel.visibility = View.GONE
        }

        tvDetails.text = NoteUtils.getDisplayNote(notes)

        btnCallIcon.setOnClickListener {
            val phone = lead["phone"] as? String
            if (!phone.isNullOrEmpty()) {
                val intent = Intent(Intent.ACTION_DIAL, "tel:$phone".toUri())
                context.startActivity(intent)
            } else {
                Toast.makeText(context, context.getString(R.string.msg_no_phone), Toast.LENGTH_SHORT).show()
            }
        }
        return view
    }
}
