package com.nickpulido.rcrm

import android.os.Bundle
import android.content.Intent
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import java.util.Calendar

data class TeamMemberStats(
    val uid: String,
    val name: String,
    val email: String,
    var dailyLeads: Int = 0,
    var weeklyLeads: Int = 0,
    var monthlyLeads: Int = 0,
    var dailyCalls: Int = 0,
    var weeklyCalls: Int = 0,
    var monthlyCalls: Int = 0,
    var dailyTexts: Int = 0,
    var weeklyTexts: Int = 0,
    var monthlyTexts: Int = 0,
    var statsLoaded: Boolean = false
)

class AdminDashboardActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()
    private val teamList = mutableListOf<TeamMemberStats>()
    private lateinit var adapter: TeamMemberAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_admin_dashboard)

        val listView = findViewById<ListView>(R.id.listViewTeam)
        adapter = TeamMemberAdapter(this, teamList)
        listView.adapter = adapter

        listView.setOnItemClickListener { _, _, position, _ ->
            val memberId = teamList[position].uid
            val intent = Intent(this, FollowUpActivity::class.java)
            intent.putExtra("TARGET_USER_ID", memberId)
            startActivity(intent)
        }

        findViewById<FloatingActionButton>(R.id.fabAddMember).setOnClickListener {
            showAddMemberDialog()
        }

        loadTeamMembers()
    }

    private fun showAddMemberDialog() {
        val input = EditText(this)
        input.hint = "Agent's Email Address"
        input.inputType = android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS

        AlertDialog.Builder(this)
            .setTitle("Add Team Member")
            .setMessage("Enter the registered email of the user you want to add to your team:")
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                val email = input.text.toString().trim().lowercase()
                if (email.isNotEmpty()) {
                    addMemberByEmail(email)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addMemberByEmail(email: String) {
        val adminId = auth.currentUser?.uid ?: return
        
        db.collection("user_settings").whereEqualTo("email", email).get()
            .addOnSuccessListener { querySnapshot ->
                if (querySnapshot.isEmpty) {
                    Toast.makeText(this, "No user found with that email.", Toast.LENGTH_SHORT).show()
                    return@addOnSuccessListener
                }
                
                val newMemberUid = querySnapshot.documents.first().id
                
                db.collection("user_settings").document(adminId)
                    .update("managed_users", FieldValue.arrayUnion(newMemberUid))
                    .addOnSuccessListener {
                        Toast.makeText(this, "Team member added!", Toast.LENGTH_SHORT).show()
                        loadTeamMembers()
                    }
            }
            .addOnFailureListener {
                Toast.makeText(this, "Failed to look up user.", Toast.LENGTH_SHORT).show()
            }
    }

    private fun loadTeamMembers() {
        val adminId = auth.currentUser?.uid ?: return
        db.collection("user_settings").document(adminId).get()
            .addOnSuccessListener { snapshot ->
                @Suppress("UNCHECKED_CAST")
                val managedUsers = snapshot.get("managed_users") as? List<String> ?: emptyList()
                teamList.clear()
                
                // Filter out any empty strings that might have been saved in Firebase
                val validUsers = managedUsers.filter { it.isNotBlank() }
                
                if (validUsers.isEmpty()) {
                    Toast.makeText(this, "No users currently managed.", Toast.LENGTH_SHORT).show()
                    adapter.notifyDataSetChanged()
                } else {
                    validUsers.forEach { uid ->
                        db.collection("user_settings").document(uid).get().addOnSuccessListener { userSnap ->
                            val email = userSnap.getString("email") ?: "Unknown Email"
                            val name = userSnap.getString("name") ?: "Agent"
                            
                            val member = TeamMemberStats(uid, name, email)
                            teamList.add(member)
                            adapter.notifyDataSetChanged()
                            
                            loadMemberStats(member)
                        }.addOnFailureListener {
                            val member = TeamMemberStats(uid, "User ($uid)", "Unknown")
                            teamList.add(member)
                            adapter.notifyDataSetChanged()
                        }
                    }
                }
            }
            .addOnFailureListener {
                Toast.makeText(this, "Failed to load team.", Toast.LENGTH_SHORT).show()
            }
    }

    private fun loadMemberStats(member: TeamMemberStats) {
        db.collection("leads").whereEqualTo("ownerId", member.uid).get()
            .addOnSuccessListener { snaps ->
                var daily = 0
                var weekly = 0
                var monthly = 0
                
                var dCalls = 0
                var wCalls = 0
                var mCalls = 0
                
                var dTexts = 0
                var wTexts = 0
                var mTexts = 0

                val today = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                
                val weekAgo = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -7) }
                val monthAgo = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -30) }
                
                val sdf = java.text.SimpleDateFormat("MMM dd, yyyy hh:mm a", java.util.Locale.getDefault())

                for (doc in snaps) {
                    val ts = doc.getTimestamp("timestamp")?.toDate()?.time ?: 0L
                    if (ts >= today.timeInMillis) daily++
                    if (ts >= weekAgo.timeInMillis) weekly++
                    if (ts >= monthAgo.timeInMillis) monthly++
                    
                    val rawNotes = doc.getString("notes") ?: ""
                    val blocks = rawNotes.split("\n\n")
                    for (block in blocks) {
                        val called = block.contains("[C:true")
                        val texted = block.contains("T:true]")
                        
                        if (called || texted) {
                            val dateRegex = Regex("^\\[?([A-Z][a-z]{2} \\d{2}, (?:\\d{4} )?\\d{2}:\\d{2}(?: [AaPp][Mm])?)\\]?")
                            val dateMatch = dateRegex.find(block.trim())
                            val dateStr = dateMatch?.groupValues?.getOrNull(1)
                            
                            val noteTime = try {
                                if (dateStr != null) sdf.parse(dateStr)?.time ?: 0L else 0L
                            } catch(e: Exception) { 0L }
                            
                            if (noteTime > 0) {
                                if (called) {
                                    if (noteTime >= today.timeInMillis) dCalls++
                                    if (noteTime >= weekAgo.timeInMillis) wCalls++
                                    if (noteTime >= monthAgo.timeInMillis) mCalls++
                                }
                                if (texted) {
                                    if (noteTime >= today.timeInMillis) dTexts++
                                    if (noteTime >= weekAgo.timeInMillis) wTexts++
                                    if (noteTime >= monthAgo.timeInMillis) mTexts++
                                }
                            }
                        }
                    }
                }
                
                member.dailyLeads = daily
                member.weeklyLeads = weekly
                member.monthlyLeads = monthly
                member.dailyCalls = dCalls
                member.weeklyCalls = wCalls
                member.monthlyCalls = mCalls
                member.dailyTexts = dTexts
                member.weeklyTexts = wTexts
                member.monthlyTexts = mTexts
                member.statsLoaded = true
                adapter.notifyDataSetChanged()
            }
    }
}

class TeamMemberAdapter(context: Context, private val members: List<TeamMemberStats>) :
    ArrayAdapter<TeamMemberStats>(context, 0, members) {

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_team_member, parent, false)
        val member = members[position]
        
        val tvName = view.findViewById<TextView>(R.id.tvMemberName)
        val tvEmail = view.findViewById<TextView>(R.id.tvMemberEmail)
        
        val tvDailyLeads = view.findViewById<TextView>(R.id.tvDailyLeads)
        val tvWeeklyLeads = view.findViewById<TextView>(R.id.tvWeeklyLeads)
        val tvMonthlyLeads = view.findViewById<TextView>(R.id.tvMonthlyLeads)
        
        val tvDailyCalls = view.findViewById<TextView>(R.id.tvDailyCalls)
        val tvWeeklyCalls = view.findViewById<TextView>(R.id.tvWeeklyCalls)
        val tvMonthlyCalls = view.findViewById<TextView>(R.id.tvMonthlyCalls)
        
        val tvDailyTexts = view.findViewById<TextView>(R.id.tvDailyTexts)
        val tvWeeklyTexts = view.findViewById<TextView>(R.id.tvWeeklyTexts)
        val tvMonthlyTexts = view.findViewById<TextView>(R.id.tvMonthlyTexts)
        
        tvName.text = member.name
        tvEmail.text = member.email
        
        if (member.statsLoaded) {
            tvDailyLeads.text = member.dailyLeads.toString()
            tvWeeklyLeads.text = member.weeklyLeads.toString()
            tvMonthlyLeads.text = member.monthlyLeads.toString()
            
            tvDailyCalls.text = member.dailyCalls.toString()
            tvWeeklyCalls.text = member.weeklyCalls.toString()
            tvMonthlyCalls.text = member.monthlyCalls.toString()
            
            tvDailyTexts.text = member.dailyTexts.toString()
            tvWeeklyTexts.text = member.weeklyTexts.toString()
            tvMonthlyTexts.text = member.monthlyTexts.toString()
        } else {
            listOf(tvDailyLeads, tvWeeklyLeads, tvMonthlyLeads, tvDailyCalls, tvWeeklyCalls, tvMonthlyCalls, tvDailyTexts, tvWeeklyTexts, tvMonthlyTexts).forEach { 
                it.text = "..." 
            }
        }
        
        return view
    }
}