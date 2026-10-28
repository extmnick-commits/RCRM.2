package com.nickpulido.rcrm

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class SideKickActivity : AppCompatActivity() {
    private val rows = mutableListOf<BriefingRow>()
    private lateinit var adapter: BriefingAdapter
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_side_kick)
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        status = findViewById(R.id.tvNoSuggestions)
        val list = findViewById<ListView>(R.id.listViewSideKick)
        adapter = BriefingAdapter(this, rows)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ ->
            val intent = Intent(this, FollowUpActivity::class.java)
                .putExtra("targetLeadId", rows[position].leadId)
            startActivity(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        loadBriefing()
    }

    private fun loadBriefing() {
        val userId = FirebaseAuth.getInstance().currentUser?.uid
        if (userId == null) {
            rows.clear()
            adapter.notifyDataSetChanged()
            showStatus(getString(R.string.briefing_sign_in))
            return
        }
        rows.clear()
        adapter.notifyDataSetChanged()
        showStatus(getString(R.string.briefing_loading))
        FirebaseFirestore.getInstance().collection("leads")
            .whereEqualTo("ownerId", userId).get()
            .addOnSuccessListener { documents ->
                val now = System.currentTimeMillis()
                val zone = TimeZone.getDefault()
                val start = AppointmentAgenda.startOfDay(now, zone)
                val end = Calendar.getInstance(zone).apply {
                    timeInMillis = start
                    add(Calendar.DAY_OF_YEAR, 1)
                }.timeInMillis
                val time = SimpleDateFormat("h:mm a", Locale.getDefault())
                val result = mutableListOf<BriefingRow>()
                for (doc in documents) {
                    if (doc.getBoolean("isClosed") == true) continue
                    val name = doc.getString("name").orEmpty().ifBlank { getString(R.string.briefing_unknown_lead) }
                    val records = AppointmentAgenda.migrateAppointments(
                        leadId = doc.id,
                        existing = LeadAppointmentSync.readRecords(doc.get(AppointmentAgenda.FIELD_APPOINTMENTS)),
                        legacyStartMillis = doc.getTimestamp("appointmentDate")?.toDate()?.time,
                        legacyLocation = doc.getString("appointmentLocation"),
                        legacyEventId = null,
                        nowMillis = now
                    )
                    AppointmentAgenda.digestAppointmentStarts(records, start, end).forEach { date ->
                        result.add(BriefingRow(doc.id, name, getString(R.string.briefing_appointment, time.format(Date(date))), 0, date))
                    }
                    if (doc.getBoolean("isPaused") != true) {
                        val due = doc.getTimestamp("followUpDate")?.toDate()?.time
                        if (due == null) {
                            result.add(BriefingRow(doc.id, name, getString(R.string.briefing_missing_plan), 1, Long.MAX_VALUE))
                        } else if (due < end) {
                            val detail = if (due < start) getString(R.string.briefing_overdue)
                                else getString(R.string.briefing_follow_up, time.format(Date(due)))
                            result.add(BriefingRow(doc.id, name, detail, 1, due))
                        }
                        val suggestion = doc.getString("sideKickSuggestion").orEmpty()
                        val coached = doc.getTimestamp("lastCoachedAt")?.toDate()?.time ?: 0L
                        if (suggestion.isNotBlank() && coached >= now - 24 * 60 * 60 * 1000L && coached <= now) {
                            result.add(BriefingRow(doc.id, name, suggestion, 2, -coached))
                        }
                    }
                }
                rows.clear()
                rows.addAll(result.sortedWith(compareBy<BriefingRow> { it.section }.thenBy { it.sortTime }))
                adapter.notifyDataSetChanged()
                if (rows.isEmpty()) showStatus(getString(R.string.briefing_empty)) else status.visibility = View.GONE
            }
            .addOnFailureListener {
                rows.clear()
                adapter.notifyDataSetChanged()
                showStatus(getString(R.string.briefing_error))
            }
    }

    private fun showStatus(message: String) {
        status.text = message
        status.visibility = View.VISIBLE
    }
}

private data class BriefingRow(val leadId: String, val name: String, val detail: String, val section: Int, val sortTime: Long)

private class BriefingAdapter(context: Context, private val rows: List<BriefingRow>) :
    ArrayAdapter<BriefingRow>(context, 0, rows) {
    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_sidekick_lead, parent, false)
        val row = rows[position]
        view.findViewById<TextView>(R.id.tvSideKickLeadName).text = row.name
        view.findViewById<TextView>(R.id.tvSideKickSuggestion).text = row.detail
        val accent = when (row.section) {
            0 -> Color.parseColor("#2EE7FF")
            1 -> Color.parseColor("#3DFF9A")
            else -> Color.parseColor("#D198FF")
        }
        view.findViewById<ImageView>(R.id.ivBriefingIcon).apply {
            setBackgroundResource(when (row.section) {
                0 -> R.drawable.bg_icon_circle_cyan
                1 -> R.drawable.bg_icon_circle_green
                else -> R.drawable.bg_icon_circle_purple
            })
            setImageResource(when (row.section) {
                0 -> android.R.drawable.ic_menu_today
                1 -> android.R.drawable.ic_menu_call
                else -> android.R.drawable.ic_menu_info_details
            })
            setColorFilter(accent)
        }
        view.findViewById<TextView>(R.id.tvSideKickCoachedAt).apply {
            setTextColor(accent)
            text = context.getString(when (row.section) {
                0 -> R.string.briefing_section_appointments
                1 -> R.string.briefing_section_follow_ups
                else -> R.string.briefing_section_suggestions
            })
            visibility = View.VISIBLE
        }
        return view
    }
}
