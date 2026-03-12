package com.example.rcrm

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.google.firebase.firestore.FirebaseFirestore
import java.util.*

class MainActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()
    private lateinit var tvTotalLeads: TextView
    private lateinit var hotListCount: TextView
    private lateinit var hotListView: ListView
    private lateinit var searchBar: EditText

    private val allLeadsData = mutableListOf<Map<String, Any>>()
    private val displayLeadsData = mutableListOf<Map<String, Any>>()
    private lateinit var hotListAdapter: LeadAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvTotalLeads = findViewById(R.id.tvTotalLeads)
        hotListCount = findViewById(R.id.hot_list_count)
        hotListView = findViewById(R.id.hot_list_view)
        searchBar = findViewById(R.id.search_bar)

        val btnQuickAdd = findViewById<Button>(R.id.btnQuickAdd)
        val btnFollowUpList = findViewById<Button>(R.id.btnFollowUpList)
        val btnContacts = findViewById<Button>(R.id.btn_view_contacts)

        hotListAdapter = LeadAdapter(this, displayLeadsData)
        hotListView.adapter = hotListAdapter

        checkPermissions()
        searchBar.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { filterLeads(s.toString()) }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        btnQuickAdd.setOnClickListener { showNewLeadDialog() }
        btnFollowUpList.setOnClickListener {
            startActivity(Intent(this, FollowUpActivity::class.java))
        }
        btnContacts.setOnClickListener {
            Toast.makeText(this, getString(R.string.btn_contacts), Toast.LENGTH_SHORT).show()
        }

        refreshDashboard()
    }

    private fun refreshDashboard() {
        db.collection("leads").get().addOnSuccessListener { result ->
            tvTotalLeads.text = result.size().toString()
            loadHotList(result.documents.map { it.data!! })
        }
    }

    private fun loadHotList(allData: List<Map<String, Any>>) {
        allLeadsData.clear()
        displayLeadsData.clear()
        var dueCount = 0
        val today = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
        }.time

        for (data in allData) {
            allLeadsData.add(data)
            val followUp = (data["followUpDate"] as? com.google.firebase.Timestamp)?.toDate()
            if (followUp != null && followUp.before(today)) {
                displayLeadsData.add(data)
                dueCount++
            }
        }
        hotListCount.text = dueCount.toString()
        hotListAdapter.notifyDataSetChanged()
    }

    private fun filterLeads(query: String) {
        displayLeadsData.clear()
        if (query.isEmpty()) {
            refreshDashboard()
            return
        }
        val filtered = allLeadsData.filter {
            it["name"].toString().lowercase().contains(query.lowercase()) ||
                    it["notes"].toString().lowercase().contains(query.lowercase())
        }
        displayLeadsData.addAll(filtered)
        hotListAdapter.notifyDataSetChanged()
    }

    private fun showNewLeadDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle(getString(R.string.dialog_title_quick_add))

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 40, 60, 10)
        }

        val nameInput = EditText(this).apply {
            hint = getString(R.string.hint_prospect_name)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                setAutofillHints(View.AUTOFILL_HINT_NAME)
            }
        }
        val phoneInput = EditText(this).apply {
            hint = getString(R.string.hint_phone_number)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                setAutofillHints(View.AUTOFILL_HINT_PHONE)
            }
        }
        val categoryInput = EditText(this).apply { hint = getString(R.string.hint_category) }
        val notesInput = EditText(this).apply { hint = getString(R.string.hint_notes) }

        layout.addView(nameInput); layout.addView(phoneInput); layout.addView(categoryInput); layout.addView(notesInput)
        builder.setView(layout)

        builder.setPositiveButton(getString(R.string.btn_save)) { _, _ ->
            val tomorrow = Date(System.currentTimeMillis() + 86400000)
            val leadData = hashMapOf(
                "name" to nameInput.text.toString(),
                "phone" to phoneInput.text.toString(),
                "category" to categoryInput.text.toString(),
                "notes" to notesInput.text.toString(),
                "followUpDate" to tomorrow,
                "timestamp" to com.google.firebase.Timestamp.now()
            )
            db.collection("leads").add(leadData).addOnSuccessListener {
                Toast.makeText(this, getString(R.string.msg_lead_saved), Toast.LENGTH_SHORT).show()
                refreshDashboard()
            }
        }
        builder.show()
    }

    private fun checkPermissions() {
        val permissions = arrayOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.SEND_SMS
        )
        val toRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (toRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, toRequest.toTypedArray(), 1)
        }

        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                "package:$packageName".toUri()
            )
            Toast.makeText(this, "Please allow 'Display over other apps' for automatic call logging.", Toast.LENGTH_LONG).show()
            startActivity(intent)
        }
    }
}

class LeadAdapter(context: Context, private val leads: List<Map<String, Any>>) :
    ArrayAdapter<Map<String, Any>>(context, 0, leads) {
    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_lead_card, parent, false)
        val tvName = view.findViewById<TextView>(R.id.tvLeadName)
        val tvDetails = view.findViewById<TextView>(R.id.tvLeadDetails)
        val btnCallIcon = view.findViewById<ImageView>(R.id.btnCallIcon)

        val lead = leads[position]
        val name = lead["name"] as? String ?: context.getString(R.string.unknown_contact)
        val category = lead["category"] as? String ?: context.getString(R.string.category_prospect)
        val notes = lead["notes"] as? String ?: ""

        tvName.text = context.getString(R.string.lead_name_format, name)
        tvDetails.text = if (notes.isNotEmpty()) {
            context.getString(R.string.lead_details_format, category, notes)
        } else {
            category
        }

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