package com.nickpulido.rcrm

import android.annotation.SuppressLint
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.net.toUri
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import java.text.SimpleDateFormat
import java.util.*

class SmartLogActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()
    private var phoneNumber: String = ""
    private var contactName: String = ""
    private var followUpDate: Date? = null

    private companion object {
        const val PREFS_NAME = "IntroPresets"
        const val PREFS_KEY = "saved_presets_list"
        const val NAME_TOKEN = "[NAME]"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_smart_log)

        phoneNumber = intent.getStringExtra("INCOMING_NUMBER") ?: ""
        contactName = getContactName(phoneNumber)

        val tvHeader = findViewById<TextView>(R.id.tvLogHeader)
        val btnSkip = findViewById<Button>(R.id.btnSkipPersonal)
        val cbRecruit = findViewById<CheckBox>(R.id.cbRecruit)
        val cbProspect = findViewById<CheckBox>(R.id.cbProspect)
        val cbClient = findViewById<CheckBox>(R.id.cbClient)
        val etNotes = findViewById<EditText>(R.id.etQuickNote)
        val etIntroText = findViewById<EditText>(R.id.etIntroText)
        val btnSave = findViewById<Button>(R.id.btnSaveLeadAction)
        val btnSms = findViewById<Button>(R.id.btnSendIntroAction)

        val btnManagePresets = findViewById<Button>(R.id.btnManagePresets)
        val btnDefaultIntro = findViewById<Button>(R.id.btnDefaultIntro)

        tvHeader.text = getString(R.string.log_call_header, contactName, phoneNumber)

        fun applyDefaultIntro() {
            val firstName = contactName.split(" ").firstOrNull() ?: contactName
            etIntroText.setText(getString(R.string.sms_intro_message, firstName))
        }
        applyDefaultIntro()

        btnSkip.setOnClickListener { finish() }

        findViewById<Button>(R.id.btnPlus1Week).setOnClickListener {
            val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 7) }
            showDateTimePicker(cal)
        }
        findViewById<Button>(R.id.btnPlus2Weeks).setOnClickListener {
            val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 14) }
            showDateTimePicker(cal)
        }
        findViewById<Button>(R.id.btnPlus1Month).setOnClickListener {
            val cal = Calendar.getInstance().apply { add(Calendar.MONTH, 1) }
            showDateTimePicker(cal)
        }

        btnManagePresets.setOnClickListener { showPresetManagerDialog(etIntroText) }
        btnDefaultIntro.setOnClickListener { applyDefaultIntro() }

        btnSave.setOnClickListener {
            performSaveLeadAction(cbRecruit, cbProspect, cbClient, etNotes) {
                Toast.makeText(this, "Lead History Saved", Toast.LENGTH_SHORT).show()
                val intent = Intent(this, FollowUpActivity::class.java)
                intent.putExtra("TARGET_PHONE", phoneNumber)
                startActivity(intent)
                finish()
            }
        }

        btnSms.setOnClickListener {
            performSaveLeadAction(cbRecruit, cbProspect, cbClient, etNotes) {
                val message = etIntroText.text.toString()
                val smsIntent = Intent(Intent.ACTION_VIEW, "sms:$phoneNumber".toUri())
                smsIntent.putExtra("sms_body", message)
                startActivity(smsIntent)
                
                val followUpIntent = Intent(this, FollowUpActivity::class.java)
                followUpIntent.putExtra("TARGET_PHONE", phoneNumber)
                startActivity(followUpIntent)
                finish()
            }
        }
    }

    private fun showDateTimePicker(initialCal: Calendar) {
        DatePickerDialog(this, { _, year, month, day ->
            initialCal.set(year, month, day)
            TimePickerDialog(this, { _, hour, minute ->
                initialCal.set(Calendar.HOUR_OF_DAY, hour)
                initialCal.set(Calendar.MINUTE, minute)
                followUpDate = initialCal.time
                
                val sdf = SimpleDateFormat("MMM dd, hh:mm a", Locale.getDefault())
                Toast.makeText(this, "Reminder set: ${sdf.format(followUpDate)}", Toast.LENGTH_SHORT).show()
            }, initialCal.get(Calendar.HOUR_OF_DAY), initialCal.get(Calendar.MINUTE), false).show()
        }, initialCal.get(Calendar.YEAR), initialCal.get(Calendar.MONTH), initialCal.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun showPresetManagerDialog(etTarget: EditText) {
        val presets = getPresetsLocally().toMutableList()
        val builder = AlertDialog.Builder(this)
        builder.setTitle(getString(R.string.dialog_manage_presets))

        if (presets.isEmpty()) {
            builder.setMessage(getString(R.string.msg_no_presets))
            builder.setPositiveButton("OK", null)
        } else {
            val adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, presets) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val view = super.getView(position, convertView, parent)
                    (view as TextView).apply {
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                        setPadding(40, 40, 40, 40)
                    }
                    return view
                }
            }

            val listView = ListView(this)
            listView.adapter = adapter
            builder.setView(listView)
            val dialog = builder.create()

            listView.setOnItemClickListener { _, _, position, _ ->
                val rawPreset = presets[position]
                val firstName = contactName.split(" ").firstOrNull() ?: contactName
                etTarget.setText(rawPreset.replace(NAME_TOKEN, firstName))
                dialog.dismiss()
            }

            listView.setOnItemLongClickListener { _, _, position, _ ->
                showEditDeleteOptions(presets, position) {
                    adapter.notifyDataSetChanged()
                    if (presets.isEmpty()) dialog.dismiss()
                }
                true
            }
            dialog.show()
        }
    }

    private fun showEditDeleteOptions(presets: MutableList<String>, position: Int, onUpdate: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle("Preset Actions")
            .setItems(arrayOf(getString(R.string.menu_edit_preset), getString(R.string.menu_delete_preset))) { _, which ->
                if (which == 0) {
                    val input = EditText(this)
                    input.setText(presets[position])
                    AlertDialog.Builder(this)
                        .setTitle(getString(R.string.menu_edit_preset))
                        .setView(input)
                        .setPositiveButton(getString(R.string.btn_save)) { _, _ ->
                            presets[position] = input.text.toString()
                            saveAllPresetsLocally(presets)
                            onUpdate()
                        }.show()
                } else {
                    AlertDialog.Builder(this)
                        .setTitle("Confirm Delete")
                        .setMessage("Are you sure you want to remove this preset?")
                        .setPositiveButton("Delete") { _, _ ->
                            presets.removeAt(position)
                            saveAllPresetsLocally(presets)
                            onUpdate()
                        }.setNegativeButton("Cancel", null).show()
                }
            }.show()
    }

    private fun performSaveLeadAction(cbR: CheckBox, cbP: CheckBox, cbC: CheckBox, etN: EditText, onSuccess: () -> Unit) {
        val cats = mutableListOf<String>()
        if (cbR.isChecked) cats.add(getString(R.string.category_recruit))
        if (cbP.isChecked) cats.add(getString(R.string.category_prospect))
        if (cbC.isChecked) cats.add(getString(R.string.category_client))

        val finalCategory = cats.joinToString(", ")
        val noteText = etN.text.toString()

        db.collection("leads").whereEqualTo("phone", phoneNumber).get()
            .addOnSuccessListener { documents ->
                val time = SimpleDateFormat("[MMM dd, hh:mm a]", Locale.getDefault()).format(Date())
                if (documents.isEmpty) {
                    val formattedNote = if (noteText.isNotEmpty()) "$time: $noteText" else ""
                    val data = hashMapOf(
                        "name" to contactName, "phone" to phoneNumber, "category" to finalCategory,
                        "notes" to formattedNote, "followUpDate" to followUpDate, "timestamp" to Timestamp.now()
                    )
                    db.collection("leads").add(data).addOnSuccessListener { onSuccess() }
                } else {
                    val doc = documents.documents[0]
                    val existing = doc.getString("notes") ?: ""
                    val formatted = if (noteText.isNotEmpty()) "$time: $noteText" else ""
                    val combined = if (existing.isNotEmpty() && formatted.isNotEmpty()) "$existing\n\n$formatted" else formatted.ifEmpty { existing }

                    val update = mutableMapOf<String, Any>("category" to finalCategory, "notes" to combined, "timestamp" to Timestamp.now())
                    followUpDate?.let { update["followUpDate"] = it }
                    db.collection("leads").document(doc.id).update(update).addOnSuccessListener { onSuccess() }
                }
            }
    }

    private fun saveAllPresetsLocally(list: List<String>) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putStringSet(PREFS_KEY, list.toSet())
        }
    }

    private fun getPresetsLocally(): Set<String> {
        return getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getStringSet(PREFS_KEY, emptySet()) ?: emptySet()
    }

    @SuppressLint("Range")
    private fun getContactName(phone: String): String {
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
}