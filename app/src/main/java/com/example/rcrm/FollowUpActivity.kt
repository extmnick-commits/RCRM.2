package com.example.rcrm

import android.app.AlertDialog
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.firestore.FirebaseFirestore

class FollowUpActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()
    private val displayList = mutableListOf<String>()
    private val documentIds = mutableListOf<String>()
    private lateinit var adapter: ArrayAdapter<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_follow_up)

        val listView = findViewById<ListView>(R.id.listViewLeads)

        // Sets up a simple list view
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, displayList)
        listView.adapter = adapter

        loadLeadsFromCloud()

        // When you tap a lead, open the Edit/Notes popup
        listView.setOnItemClickListener { _, _, position, _ ->
            val selectedDocId = documentIds[position]
            val selectedName = displayList[position].substringBefore(" -")
            showEditLeadDialog(selectedDocId, selectedName)
        }
    }

    private fun loadLeadsFromCloud() {
        db.collection("leads")
            .get()
            .addOnSuccessListener { documents ->
                displayList.clear()
                documentIds.clear()
                for (document in documents) {
                    val name = document.getString("name") ?: "Unknown"
                    val type = document.getString("type") ?: "Lead"
                    displayList.add("$name - $type")
                    documentIds.add(document.id)
                }
                adapter.notifyDataSetChanged()
            }
            .addOnFailureListener {
                Toast.makeText(this, "Failed to load leads", Toast.LENGTH_SHORT).show()
            }
    }

    private fun showEditLeadDialog(docId: String, currentName: String) {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Update $currentName")

        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(60, 40, 60, 10)

        val notesInput = EditText(this)
        notesInput.hint = "Add Follow-up Notes here..."
        layout.addView(notesInput)

        builder.setView(layout)

        builder.setPositiveButton("Save Notes") { _, _ ->
            val newNotes = notesInput.text.toString()

            // Update the specific lead in Firebase
            db.collection("leads").document(docId)
                .update("notes", newNotes)
                .addOnSuccessListener {
                    Toast.makeText(this, "Notes added!", Toast.LENGTH_SHORT).show()
                }
        }

        builder.setNeutralButton("Set Reminder") { _, _ ->
            Toast.makeText(this, "Reminder feature coming next!", Toast.LENGTH_SHORT).show()
        }

        builder.setNegativeButton("Cancel", null)
        builder.show()
    }
}