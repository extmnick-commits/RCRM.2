package com.nickpulido.rcrm

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class AppointmentsActivity : AppCompatActivity() {

    private lateinit var db: FirebaseFirestore
    private lateinit var auth: FirebaseAuth
    private lateinit var adapter: HotListAdapter
    
    private val appointmentsData = mutableListOf<Map<String, Any>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        applyAppTheme()
        setContentView(R.layout.activity_appointments)

        db = FirebaseFirestore.getInstance()
        auth = FirebaseAuth.getInstance()
        
        val listView = findViewById<ListView>(R.id.listViewAppointments)
        
        // We reuse HotListAdapter but spoof the currentTab so it thinks it is in tab #3 (Appointments)
        adapter = HotListAdapter(this, appointmentsData) { docId: String ->
            // In the Appointments Tab, the complete button is usually hidden and intercepted
            // However, we just define a no-op handler here just in case. 
        }
        adapter.currentTab = 3 
        listView.adapter = adapter
        
        listView.setOnItemClickListener { _, _, position, _ ->
            val lead = appointmentsData[position]
            val intent = Intent(this, FollowUpActivity::class.java)
            intent.putExtra("targetLeadId", lead["__docId"] as? String)
            intent.putExtra("targetPhone", lead["phone"] as? String)
            startActivity(intent)
        }

        findViewById<ImageButton>(R.id.btnBackAppointments).setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        loadAppointments()
    }

    private fun loadAppointments() {
        val currentUserId = auth.currentUser?.uid
        if (currentUserId == null) {
            Toast.makeText(this, "Not authenticated", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val progressBar = findViewById<ProgressBar>(R.id.progressAppointments)
        val tvNoAppointments = findViewById<TextView>(R.id.tvNoAppointments)
        
        progressBar.visibility = View.VISIBLE
        tvNoAppointments.visibility = View.GONE
        appointmentsData.clear()

        // Fetch all leads for this user to filter manually (avoiding custom index requirements)
        db.collection("leads")
            .whereEqualTo("ownerId", currentUserId)
            .get()
            .addOnSuccessListener { snapshot ->
                progressBar.visibility = View.GONE
                
                for (doc in snapshot.documents) {
                    val map = doc.data?.toMutableMap() ?: continue
                    map["__docId"] = doc.id
                    map["id"] = doc.id
                    
                    val apptDate = map["appointmentDate"] as? Timestamp
                    if (apptDate != null) {
                        val apptTime = apptDate.toDate().time
                        // Filter for upcoming or today's appointments specifically:
                        if (apptTime >= System.currentTimeMillis() - 86400000) {
                            appointmentsData.add(map)
                        }
                    }
                }

                // Sort purely ascending (closest date first)
                appointmentsData.sortBy { (it["appointmentDate"] as? Timestamp)?.toDate()?.time ?: Long.MAX_VALUE }
                adapter.notifyDataSetChanged()
                
                if (appointmentsData.isEmpty()) {
                    tvNoAppointments.visibility = View.VISIBLE
                }
            }
            .addOnFailureListener { e ->
                progressBar.visibility = View.GONE
                tvNoAppointments.visibility = View.VISIBLE
                tvNoAppointments.text = "Error reading appointments: ${e.message}"
            }
    }

    private fun applyAppTheme() {
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val followSystem = prefs.getBoolean("follow_system_theme", true)
        val isDarkMode = prefs.getBoolean("dark_mode", false)

        if (followSystem) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        } else {
            if (isDarkMode) {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            } else {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            }
        }
    }
}
