package com.example.rcrm

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.firebase.firestore.FirebaseFirestore

class MainActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()
    private lateinit var tvTotalLeads: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 1. Initial Setup
        checkPermissions()
        tvTotalLeads = findViewById(R.id.tvTotalLeads)

        // 2. Buttons
        val btnQuickAdd = findViewById<Button>(R.id.btnQuickAdd)
        val btnFollowUpList = findViewById<Button>(R.id.btnFollowUpList)

        // 3. Logic: Add New Lead (The Popup)
        btnQuickAdd.setOnClickListener {
            showNewLeadDialog()
        }

        // --- THE ONLY CHANGE IS HERE ---
        // This launches the new Follow Up Screen
        btnFollowUpList.setOnClickListener {
            val intent = Intent(this, FollowUpActivity::class.java)
            startActivity(intent)
        }

        // 4. Update stats when the app opens
        updateStatsCount()
    }

    // --- Modern Lead Entry Popup ---
    private fun showNewLeadDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Log New Prospect")

        // Vertical layout for the inputs
        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(60, 40, 60, 10)

        val nameInput = EditText(this)
        nameInput.hint = "Prospect Name"
        layout.addView(nameInput)

        val phoneInput = EditText(this)
        phoneInput.hint = "Phone Number"
        layout.addView(phoneInput)

        // Special input for business type
        val typeInput = EditText(this)
        typeInput.hint = "Business Type (Primerica, DJ, etc.)"
        layout.addView(typeInput)

        builder.setView(layout)

        builder.setPositiveButton("Save Lead") { _, _ ->
            val leadName = nameInput.text.toString()
            val leadPhone = phoneInput.text.toString()
            val leadType = typeInput.text.toString()

            if (leadName.isNotEmpty() && leadType.isNotEmpty()) {
                val leadData = hashMapOf(
                    "name" to leadName,
                    "phone" to leadPhone,
                    "type" to leadType,
                    "timestamp" to com.google.firebase.Timestamp.now()
                )

                db.collection("leads").add(leadData)
                    .addOnSuccessListener {
                        Toast.makeText(this, "Saved $leadName to Cloud!", Toast.LENGTH_SHORT).show()
                        updateStatsCount() // Update the counter on the screen
                    }
                    .addOnFailureListener {
                        Toast.makeText(this, "Error saving lead", Toast.LENGTH_SHORT).show()
                    }
            } else {
                Toast.makeText(this, "Name and Type required", Toast.LENGTH_SHORT).show()
            }
        }

        builder.setNegativeButton("Cancel", null)
        builder.show()
    }

    // --- Automatic Stats Update ---
    private fun updateStatsCount() {
        // Go get the count of ALL documents in the 'leads' collection
        db.collection("leads").get()
            .addOnSuccessListener { result ->
                // Update the bold number on the screen
                tvTotalLeads.text = result.size().toString()
            }
            .addOnFailureListener {
                Toast.makeText(this, "Error updating stats", Toast.LENGTH_SHORT).show()
            }
    }

    private fun checkPermissions() {
        val permissions = arrayOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_CONTACTS
        )

        val permissionsToRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsToRequest.toTypedArray(), 1)
        }
    }
}