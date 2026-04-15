package com.nickpulido.rcrm

import com.nickpulido.rcrm.R
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.firebase.Timestamp
import java.text.SimpleDateFormat
import java.util.Locale

class HotListAdapter(
    private val context: Context,
    private val data: List<Map<String, Any>>,
    private val onCompleteLead: (String) -> Unit
) : BaseAdapter() {
    var currentTab: Int = 0

    override fun getCount(): Int = data.size
    override fun getItem(position: Int) = data[position]
    override fun getItemId(position: Int) = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_lead_card, parent, false)
        val lead = data[position]

        val name = lead["name"] as? String ?: "Unknown"
        val phone = lead["phone"] as? String ?: ""
        val category = lead["category"] as? String ?: ""
        val notes = lead["notes"] as? String ?: ""
        val followUpDate = lead["followUpDate"] as? Timestamp

        view.findViewById<TextView>(R.id.tvLeadName).text = name

        val sdf = SimpleDateFormat("MMM dd, yyyy hh:mm a", Locale.getDefault())
        val dateStr = followUpDate?.toDate()?.let { sdf.format(it) } ?: ""

        val tvCategoryLabel = view.findViewById<TextView>(R.id.tvCategoryLabel)
        if (category.isNotEmpty()) {
            tvCategoryLabel.visibility = View.VISIBLE
            tvCategoryLabel.text = category

            val bgColor = when {
                category.contains(context.getString(R.string.category_recruit), ignoreCase = true) ->
                    Color.parseColor("#4CAF50")
                category.contains(context.getString(R.string.category_client), ignoreCase = true) ->
                    Color.parseColor("#9C27B0")
                else -> Color.parseColor("#007BFF")
            }

            val background = GradientDrawable()
            background.setColor(bgColor)
            background.cornerRadius = 16f
            tvCategoryLabel.background = background
            tvCategoryLabel.setTextColor(Color.WHITE)
        } else {
            tvCategoryLabel.visibility = View.GONE
        }

        val latestNote = notes.substringBefore("\n\n").replace(Regex("^\\[.*?\\]: "), "")
        val fallbackNote = if (latestNote.isEmpty()) "No notes available" else latestNote

        val btnExportCalendar = view.findViewById<ImageView>(R.id.btnExportCalendar)
        val appointmentDate = lead["appointmentDate"] as? Timestamp
        val appointmentLocation = lead["appointmentLocation"] as? String ?: ""

        if (currentTab == 3 && appointmentDate != null) {
            val apptSdf = SimpleDateFormat("MMM dd, yyyy 'at' hh:mm a", Locale.getDefault())
            val apptStr = apptSdf.format(appointmentDate.toDate())
            val detailsText = android.text.SpannableStringBuilder("Appt: $apptStr\nLoc: $appointmentLocation\n$fallbackNote")
            detailsText.setSpan(
                android.text.style.ForegroundColorSpan(Color.parseColor("#4CAF50")),
                0,
                "Appt: $apptStr".length,
                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            view.findViewById<TextView>(R.id.tvLeadDetails).text = detailsText

            if (btnExportCalendar != null) {
                btnExportCalendar.visibility = View.VISIBLE
                btnExportCalendar.setOnClickListener {
                    val intent = Intent(Intent.ACTION_INSERT)
                        .setData(android.provider.CalendarContract.Events.CONTENT_URI)
                        .putExtra(android.provider.CalendarContract.Events.TITLE, "Meeting with $name")
                        .putExtra(android.provider.CalendarContract.Events.EVENT_LOCATION, appointmentLocation)
                        .putExtra(android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME, appointmentDate.toDate().time)
                        .putExtra(android.provider.CalendarContract.EXTRA_EVENT_END_TIME, appointmentDate.toDate().time + 3600000)
                    context.startActivity(intent)
                }
            }

            val btnCompleteIcon = view.findViewById<ImageView>(R.id.btnCompleteIcon)
            if (btnCompleteIcon != null) btnCompleteIcon.visibility = View.GONE
            val llCompleteAction = view.findViewById<LinearLayout>(R.id.llCompleteAction)
            llCompleteAction?.setOnClickListener(null)

        } else {
            if (btnExportCalendar != null) btnExportCalendar.visibility = View.GONE

            if (dateStr.isNotEmpty()) {
                val detailsText = android.text.SpannableStringBuilder("Due: $dateStr\n$fallbackNote")
                if (followUpDate != null && followUpDate.toDate().time < System.currentTimeMillis()) {
                    detailsText.setSpan(
                        android.text.style.ForegroundColorSpan(Color.parseColor("#FF5252")),
                        0,
                        "Due: $dateStr".length,
                        android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
                view.findViewById<TextView>(R.id.tvLeadDetails).text = detailsText
            } else {
                view.findViewById<TextView>(R.id.tvLeadDetails).text = fallbackNote
            }

            val llCompleteAction = view.findViewById<LinearLayout>(R.id.llCompleteAction)
            llCompleteAction?.setOnClickListener {
                val docId = lead["__docId"] as? String ?: return@setOnClickListener
                onCompleteLead(docId)
            }

            val btnCompleteIcon = view.findViewById<ImageView>(R.id.btnCompleteIcon)
            if (btnCompleteIcon != null) {
                if (followUpDate != null && followUpDate.toDate().time < System.currentTimeMillis()) {
                    btnCompleteIcon.visibility = View.VISIBLE
                    btnCompleteIcon.setOnClickListener {
                        val docId = lead["__docId"] as? String ?: return@setOnClickListener
                        onCompleteLead(docId)
                    }
                } else {
                    btnCompleteIcon.visibility = View.GONE
                }
            }
        }

        val btnCallIcon = view.findViewById<ImageView>(R.id.btnCallIcon)
        btnCallIcon.setOnClickListener {
            if (phone.isNotEmpty()) {
                context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")))
            } else {
                Toast.makeText(context, context.getString(R.string.msg_no_phone), Toast.LENGTH_SHORT).show()
            }
        }

        return view
    }
}