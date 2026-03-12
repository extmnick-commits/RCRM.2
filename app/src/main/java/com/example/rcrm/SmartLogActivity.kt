package com.example.rcrm

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import java.util.*

class SmartLogActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()
    private var phoneNumber: String = ""
    private var contactName: String = ""
    private var followUpDate: Date? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_smart_log)

        phoneNumber = intent.getStringExtra("INCOMING_NUMBER") ?: ""
        contactName = getContactName(phoneNumber)

        val tvHeader = findViewById<TextView>(R.id.tvLogHeader)
        val btnSkip = findViewById<Button>(R.id.btnSkipPersonal)
        val rgCategory = findViewById<RadioGroup>(R.id.rgCategory)
        val etNotes = findViewById<EditText>(R.id.etQuickNote)
        val btn1Week = findViewById<Button>(R.id.btnPlus1Week)
        val btn1Month = findViewById<Button>(R.id.btnPlus1Month)
        val btnSave = findViewById<Button>(R.id.btnSaveLeadAction)
        val btnSms = findViewById<Button>(R.id.btnSendIntroAction)

        tvHeader.text = getString(R.string.log_call_header, contactName, phoneNumber)

        btnSkip.setOnClickListener { finish() }

        btn1Week.setOnClickListener {
            followUpDate = Date(System.currentTimeMillis() + 7L * 24 * 60 * 60 * 1000)
            Toast.makeText(this, getString(R.string.msg_set_1_week), Toast.LENGTH_SHORT).show()
        }

        btn1Month.setOnClickListener {
            followUpDate = Date(System.currentTimeMillis() + 30L * 24 * 60 * 60 * 1000)
            Toast.makeText(this, getString(R.string.msg_set_1_month), Toast.LENGTH_SHORT).show()
        }

        btnSave.setOnClickListener {
            val selectedId = rgCategory.checkedRadioButtonId
            val category = findViewById<RadioButton>(selectedId).text.toString()

            val leadData = hashMapOf(
                "name" to contactName,
                "phone" to phoneNumber,
                "category" to category,
                "notes" to etNotes.text.toString(),
                "followUpDate" to followUpDate,
                "timestamp" to Timestamp.now()
            )

            db.collection("leads").add(leadData).addOnSuccessListener {
                Toast.makeText(this, getString(R.string.msg_lead_saved_simple), Toast.LENGTH_SHORT).show()
                btnSave.isEnabled = false
                btnSms.isEnabled = true
            }
        }

        btnSms.setOnClickListener {
            sendIntroText(contactName, phoneNumber)
            finish()
        }
    }

    @SuppressLint("Range")
    private fun getContactName(phone: String): String {
        // FIXED: Using resource string for "Unknown Contact"
        val unknown = getString(R.string.unknown_contact)
        if (phone.isEmpty()) return unknown

        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phone))
        val projection = arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME)
        var name = unknown

        contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) name = cursor.getString(0)
        }
        return name
    }

    private fun sendIntroText(name: String, phone: String) {
        val firstName = name.split(" ").firstOrNull() ?: name
        val message = getString(R.string.sms_intro_message, firstName)
        val intent = Intent(Intent.ACTION_VIEW, "sms:$phone".toUri())
        intent.putExtra("sms_body", message)
        startActivity(intent)
    }
}