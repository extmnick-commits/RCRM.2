package com.nickpulido.rcrm

import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore

class SuperAdminDashboardActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()
    private val userDisplayList = mutableListOf<String>()
    private val userMetadataList = mutableListOf<Map<String, Any>>()
    private lateinit var adapter: ArrayAdapter<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_super_admin_dashboard)

        findViewById<Button>(R.id.btnMyAdminDashboard).setOnClickListener {
            startActivity(Intent(this, AdminDashboardActivity::class.java))
        }

        val listView = findViewById<ListView>(R.id.listViewAllUsers)
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, userDisplayList)
        listView.adapter = adapter

        listView.setOnItemClickListener { _, _, position, _ ->
            val user = userMetadataList[position]
            showManageUserDialog(user)
        }

        loadAllUsers()
    }

    private fun loadAllUsers() {
        db.collection("user_settings").get().addOnSuccessListener { snapshot ->
            userDisplayList.clear()
            userMetadataList.clear()
            for (doc in snapshot.documents) {
                val uid = doc.id
                val email = doc.getString("email") ?: "No Email"
                val name = doc.getString("name") ?: "Unknown"
                val role = doc.getString("role") ?: "agent"
                
                val displayName = if (name.isNotBlank()) name else "No Name"
                userDisplayList.add("$displayName\nEmail: $email | Role: ${role.uppercase()}")
                
                userMetadataList.add(mapOf(
                    "uid" to uid,
                    "email" to email,
                    "name" to name,
                    "role" to role
                ))
            }
            
            // Sort list alphabetically by email for a professional look
            userDisplayList.sort()
            userMetadataList.sortBy { it["email"] as? String }
            
            adapter.notifyDataSetChanged()
        }
    }

    private fun showManageUserDialog(user: Map<String, Any>) {
        val uid = user["uid"] as String
        val role = user["role"] as String
        val email = user["email"] as String

        val options = mutableListOf<String>()
        options.add("View User's Leads")
        options.add("Add to My Team")
        if (role == "admin") {
            options.add("Revoke Admin Role")
        } else {
            options.add("Make Admin")
        }
        options.add("Assign to an Admin's Team")

        AlertDialog.Builder(this)
            .setTitle("Manage User: $email")
            .setItems(options.toTypedArray()) { _, which ->
                when (options[which]) {
                    "View User's Leads" -> viewUserLeads(uid)
                    "Add to My Team" -> addToMyTeam(uid)
                    "Make Admin" -> updateRole(uid, "admin")
                    "Revoke Admin Role" -> updateRole(uid, "agent")
                    "Assign to an Admin's Team" -> showAssignAdminDialog(uid)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun viewUserLeads(targetUid: String) {
        val intent = Intent(this, FollowUpActivity::class.java)
        intent.putExtra("TARGET_USER_ID", targetUid)
        startActivity(intent)
    }

    private fun addToMyTeam(targetUid: String) {
        val myUid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        db.collection("user_settings").document(myUid)
            .update("managed_users", FieldValue.arrayUnion(targetUid))
            .addOnSuccessListener {
                Toast.makeText(this, "User added to your team!", Toast.LENGTH_SHORT).show()
            }
    }

    private fun updateRole(uid: String, newRole: String) {
        db.collection("user_settings").document(uid).update("role", newRole).addOnSuccessListener {
            Toast.makeText(this, "Role updated to $newRole", Toast.LENGTH_SHORT).show()
            loadAllUsers() // Refresh the display
        }
    }

    private fun showAssignAdminDialog(targetUid: String) {
        db.collection("user_settings").whereEqualTo("role", "admin").get().addOnSuccessListener { snapshot ->
            if (snapshot.isEmpty) {
                Toast.makeText(this, "No admins found in the system.", Toast.LENGTH_SHORT).show()
                return@addOnSuccessListener
            }
            
            val adminIds = snapshot.documents.map { it.id }
            val adminDisplays = snapshot.documents.map { it.getString("email") ?: "Unknown Admin" }.toTypedArray()
            
            AlertDialog.Builder(this).setTitle("Select Admin for this User").setItems(adminDisplays) { _, which ->
                val selectedAdminId = adminIds[which]
                db.collection("user_settings").document(selectedAdminId).update("managed_users", FieldValue.arrayUnion(targetUid))
                    .addOnSuccessListener { Toast.makeText(this, "User assigned successfully!", Toast.LENGTH_SHORT).show() }
            }.setNegativeButton("Cancel", null).show()
        }
    }
}