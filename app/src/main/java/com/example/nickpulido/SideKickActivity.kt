package com.nickpulido.rcrm

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import java.util.Calendar
import java.util.Date

class SideKickActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()
    private val sideKickLeads = mutableListOf<Map<String, Any>>()
    private lateinit var adapter: SideKickAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_side_kick)

        val listView = findViewById<ListView>(R.id.listViewSideKick)
        adapter = SideKickAdapter(this, sideKickLeads)
        listView.adapter = adapter

        listView.setOnItemClickListener { _, _, position, _ ->
            val lead = sideKickLeads[position]
            val phone = lead["phone"] as? String
            if (phone != null) {
                val intent = Intent(this, FollowUpActivity::class.java)
                intent.putExtra("targetPhone", phone)
                startActivity(intent)
            }
        }

        loadSideKickLeads()
    }

    private fun loadSideKickLeads() {
        val userId = auth.currentUser?.uid ?: return

        // Get timestamp for 24 hours ago
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.HOUR, -24)
        val yesterday = calendar.time

        db.collection("leads")
            .whereEqualTo("ownerId", userId)
            .whereGreaterThan("lastCoachedAt", Timestamp(yesterday))
            .orderBy("lastCoachedAt", Query.Direction.DESCENDING)
            .get()
            .addOnSuccessListener { documents ->
                sideKickLeads.clear()
                for (document in documents) {
                    val data = document.data.toMutableMap()
                    data["__docId"] = document.id
                    // Only add if there's actually a suggestion
                    if (data["sideKickSuggestion"] != null && (data["sideKickSuggestion"] as String).isNotBlank()) {
                        sideKickLeads.add(data)
                    }
                }
                adapter.notifyDataSetChanged()

                if (sideKickLeads.isEmpty()) {
                    findViewById<TextView>(R.id.tvNoSuggestions).visibility = View.VISIBLE
                } else {
                    findViewById<TextView>(R.id.tvNoSuggestions).visibility = View.GONE
                }
            }
    }
}

class SideKickAdapter(context: Context, private val leads: List<Map<String, Any>>) :
    ArrayAdapter<Map<String, Any>>(context, 0, leads) {

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_sidekick_lead, parent, false)
        val tvName = view.findViewById<TextView>(R.id.tvSideKickLeadName)
        val tvSuggestion = view.findViewById<TextView>(R.id.tvSideKickSuggestion)
        val tvCoachedAt = view.findViewById<TextView>(R.id.tvSideKickCoachedAt)

        val lead = leads[position]
        val name = lead["name"] as? String ?: "Unknown Lead"
        val suggestion = lead["sideKickSuggestion"] as? String ?: "No suggestion available."
        val coachedAt = lead["lastCoachedAt"] as? Timestamp

        tvName.text = name
        tvSuggestion.text = suggestion

        if (coachedAt != null) {
            val timeAgo = android.text.format.DateUtils.getRelativeTimeSpanString(
                coachedAt.toDate().time,
                System.currentTimeMillis(),
                android.text.format.DateUtils.MINUTE_IN_MILLIS
            )
            tvCoachedAt.text = "Suggested $timeAgo"
            tvCoachedAt.visibility = View.VISIBLE
        } else {
            tvCoachedAt.visibility = View.GONE
        }

        return view
    }
}