package com.nickpulido.rcrm

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import java.text.SimpleDateFormat
import java.util.*

class ContactsActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()
    private val allLeadsData = mutableListOf<Map<String, Any>>()
    private val displayLeadsData = mutableListOf<Map<String, Any>>()
    private lateinit var adapter: ContactsAdapter
    private lateinit var listView: ListView
    private lateinit var etSearch: EditText
    private lateinit var tvEmptyState: TextView
    
    private var currentCategoryFilter: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_contacts)

        listView = findViewById(R.id.listViewContacts)
        etSearch = findViewById(R.id.etSearchContacts)
        tvEmptyState = findViewById(R.id.tvEmptyState)
        val btnBack = findViewById<ImageButton>(R.id.btnBack)
        val fabAdd = findViewById<View>(R.id.fabAddContact)
        val btnFilter = findViewById<ImageButton>(R.id.btnFilterContacts)

        adapter = ContactsAdapter(this, displayLeadsData)
        listView.adapter = adapter

        btnBack.setOnClickListener { finish() }
        
        fabAdd.setOnClickListener { showAddContactDialog() }
        
        btnFilter.setOnClickListener { showFilterPopupMenu(it) }

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { filterContacts() }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        listView.setOnItemClickListener { _, _, position, _ ->
            val lead = displayLeadsData[position]
            showLeadDetailsDialog(lead)
        }

        loadContacts()
    }

    private fun loadContacts() {
        val userId = auth.currentUser?.uid ?: return
        db.collection("leads")
            .whereEqualTo("ownerId", userId)
            .get()
            .addOnSuccessListener { documents ->
                allLeadsData.clear()
                for (document in documents) {
                    val data = document.data.toMutableMap()
                    data["__docId"] = document.id
                    allLeadsData.add(data)
                }
                allLeadsData.sortBy { (it["name"] as? String ?: "").lowercase() }
                filterContacts()
            }
    }

    private fun filterContacts() {
        val query = etSearch.text.toString().lowercase()
        displayLeadsData.clear()
        
        val filtered = allLeadsData.filter { lead ->
            val name = (lead["name"] as? String ?: "").lowercase()
            val phone = (lead["phone"] as? String ?: "").lowercase()
            val category = (lead["category"] as? String ?: "").lowercase()
            val notes = (lead["notes"] as? String ?: "").lowercase()
            
            val matchesQuery = query.isEmpty() || name.contains(query) || phone.contains(query) || category.contains(query) || notes.contains(query)
            val matchesCategory = currentCategoryFilter == null || category.contains(currentCategoryFilter!!.lowercase())
            
            matchesQuery && matchesCategory
        }
        
        displayLeadsData.addAll(filtered)
        adapter.notifyDataSetChanged()
        tvEmptyState.visibility = if (displayLeadsData.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showFilterPopupMenu(view: View) {
        val popup = PopupMenu(this, view)
        popup.menu.add("All Categories")
        popup.menu.add(getString(R.string.category_prospect))
        popup.menu.add(getString(R.string.category_recruit))
        popup.menu.add(getString(R.string.category_client))
        
        popup.setOnMenuItemClickListener { item ->
            currentCategoryFilter = if (item.title == "All Categories") null else item.title.toString()
            filterContacts()
            true
        }
        popup.show()
    }

    private fun showAddContactDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_quick_add, null)
        val dialog = AlertDialog.Builder(this).setView(dialogView).create()

        val nameInput = dialogView.findViewById<EditText>(R.id.editQuickName)
        val phoneInput = dialogView.findViewById<EditText>(R.id.editQuickPhone)
        val cbRecruit = dialogView.findViewById<CheckBox>(R.id.cbRecruitQuick)
        val cbProspect = dialogView.findViewById<CheckBox>(R.id.cbProspectQuick)
        val cbClient = dialogView.findViewById<CheckBox>(R.id.cbClientQuick)
        val notesInput = dialogView.findViewById<EditText>(R.id.editQuickNotes)
        val btnSave = dialogView.findViewById<Button>(R.id.btnQuickSave)

        btnSave.setOnClickListener {
            val name = nameInput.text.toString().trim()
            if (name.isEmpty()) {
                nameInput.error = "Name required"
                return@setOnClickListener
            }

            val cats = mutableListOf<String>()
            if (cbRecruit.isChecked) cats.add(getString(R.string.category_recruit))
            if (cbProspect.isChecked) cats.add(getString(R.string.category_prospect))
            if (cbClient.isChecked) cats.add(getString(R.string.category_client))

            val timestamp = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault()).format(Date())
            val formattedNotes = if (notesInput.text.isNotEmpty()) {
                "[$timestamp]: ${notesInput.text}"
            } else {
                "[$timestamp]: Created contact"
            }

            val leadData = hashMapOf(
                "name" to name,
                "phone" to phoneInput.text.toString(),
                "category" to cats.joinToString(", "),
                "notes" to formattedNotes,
                "followUpDate" to Timestamp(Date(System.currentTimeMillis() + 86400000)),
                "timestamp" to Timestamp.now(),
                "ownerId" to auth.currentUser?.uid
            )

            db.collection("leads").add(leadData).addOnSuccessListener {
                incrementDailyStat("total_count")
                loadContacts()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun incrementDailyStat(field: String) {
        val userId = auth.currentUser?.uid ?: return
        val dateStr = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
        db.collection("user_settings").document(userId)
            .collection("daily_stats").document(dateStr)
            .set(hashMapOf(field to FieldValue.increment(1)), SetOptions.merge())
    }

    private fun showLeadDetailsDialog(lead: Map<String, Any>) {
        val intent = Intent(this, FollowUpActivity::class.java)
        intent.putExtra("targetPhone", lead["phone"] as? String)
        startActivity(intent)
    }

    private class ContactsAdapter(context: Context, private val leads: List<Map<String, Any>>) :
        ArrayAdapter<Map<String, Any>>(context, 0, leads) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_lead_card, parent, false)
            val tvName = view.findViewById<TextView>(R.id.tvLeadName)
            val tvDetails = view.findViewById<TextView>(R.id.tvLeadDetails)
            val tvCategoryLabel = view.findViewById<TextView>(R.id.tvCategoryLabel)
            val btnCall = view.findViewById<ImageView>(R.id.btnCallIcon)
            view.findViewById<CheckBox>(R.id.cbSelectLead).visibility = View.GONE

            val lead = leads[position]
            val name = lead["name"] as? String ?: "Unknown"
            val phone = lead["phone"] as? String ?: ""
            val category = lead["category"] as? String ?: ""

            tvName.text = name
            tvDetails.text = phone.ifEmpty { "No phone number" }

            if (category.isNotEmpty()) {
                tvCategoryLabel.text = category
                tvCategoryLabel.visibility = View.VISIBLE
                
                val background = GradientDrawable()
                background.setColor(Color.parseColor("#E9ECEF"))
                background.cornerRadius = 16f
                tvCategoryLabel.background = background
                tvCategoryLabel.setTextColor(Color.parseColor("#495057"))
            } else {
                tvCategoryLabel.visibility = View.GONE
            }

            btnCall.setOnClickListener {
                if (phone.isNotEmpty()) {
                    context.startActivity(Intent(Intent.ACTION_DIAL, "tel:$phone".toUri()))
                }
            }
            return view
        }
    }
}
