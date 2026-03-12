package com.example.rcrm

import android.annotation.SuppressLint
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
import androidx.core.content.edit // Fixes the .edit warning
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

    // Organized constants for a clean, premium codebase
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

        // View Binding
        val tvHeader = findViewById<TextView>(R.id.tvLogHeader)
        val btnSkip = findViewById<Button>(R.id.btnSkipPersonal)
        val cbRecruit = findViewById<CheckBox>(R.id.cbRecruit)
        val cbProspect = findViewById<CheckBox>(R.id.cbProspect)
        val cbClient = findViewById<CheckBox>(R.id.cbClient)
        val etNotes = findViewById<EditText>(R.id.etQuickNote)
        val etIntroText = findViewById<EditText>(R.id.etIntroText)
        val btnSave = findViewById<Button>(R.id.btnSaveLeadAction)
        val btnSms = findViewById<Button>(R.id.btnSendIntroAction)

        val btnInsertNameTag = findViewById<Button>(R.id.btnInsertNameTag)
        val btnManagePresets = findViewById<Button>(R.id.btnManagePresets)
        val btnSaveAsPreset = findViewById<Button>(R.id.btnSaveAsPreset)

        tvHeader.text = getString(R.string.log_call_header, contactName, phoneNumber)

        // Set default text with name replacement
        val firstName = contactName.split(" ").firstOrNull() ?: contactName
        etIntroText.setText(getString(R.string.sms_intro_message, firstName))

        btnSkip.setOnClickListener { finish() }

        // --- Follow-Up Management ---
        findViewById<Button>(R.id.btnPlus1Week).setOnClickListener {
            followUpDate = Date(System.currentTimeMillis() + 7L * 24 * 60 * 60 * 1000)
            Toast.makeText(this, getString(R.string.msg_set_1_week), Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnPlus2Weeks).setOnClickListener {
            followUpDate = Date(System.currentTimeMillis() + 14L * 24 * 60 * 60 * 1000)
            Toast.makeText(this, getString(R.string.msg_set_2_weeks), Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnPlus1Month).setOnClickListener {
            followUpDate = Date(System.currentTimeMillis() + 30L * 24 * 60 * 60 * 1000)
            Toast.makeText(this, getString(R.string.msg_set_1_month), Toast.LENGTH_SHORT).show()
        }

        // --- Token Logic: Drop [NAME] at cursor position ---
        btnInsertNameTag.setOnClickListener {
            val start = etIntroText.selectionStart
            etIntroText.editableText.insert(start, NAME_TOKEN)
        }

        // --- Preset Management ---
        btnSaveAsPreset.setOnClickListener {
            val newPreset = etIntroText.text.toString().trim()
            if (newPreset.isNotEmpty()) {
                val presets = getPresetsLocally().toMutableList()
                presets.add(newPreset)
                saveAllPresetsLocally(presets)
                Toast.makeText(this, getString(R.string.msg_preset_saved), Toast.LENGTH_SHORT).show()
            }
        }

        btnManagePresets.setOnClickListener { showPresetManagerDialog(etIntroText) }

        // --- Primary Actions (Auto-Save Enabled) ---
        btnSave.setOnClickListener {
            performSaveLeadAction(cbRecruit, cbProspect, cbClient, etNotes) {
                btnSave.isEnabled = false
                Toast.makeText(this, "Lead History Saved", Toast.LENGTH_SHORT).show()
            }
        }

        btnSms.setOnClickListener {
            performSaveLeadAction(cbRecruit, cbProspect, cbClient, etNotes) {
                val message = etIntroText.text.toString()
                val intent = Intent(Intent.ACTION_VIEW, "sms:$phoneNumber".toUri())
                intent.putExtra("sms_body", message)
                startActivity(intent)
                finish()
            }
        }
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
                        // FIX: Explicitly set text size to clear the 'sp' reference error
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
                    // Premium Detail: Confirmation before delete
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
                if (documents.isEmpty) {
                    val data = hashMapOf(
                        "name" to contactName, "phone" to phoneNumber, "category" to finalCategory,
                        "notes" to noteText, "followUpDate" to followUpDate, "timestamp" to Timestamp.now()
                    )
                    db.collection("leads").add(data).addOnSuccessListener { onSuccess() }
                } else {
                    val doc = documents.documents[0]
                    val existing = doc.getString("notes") ?: ""
                    val time = SimpleDateFormat("[MMM dd, HH:mm]", Locale.getDefault()).format(Date())
                    val formatted = if (noteText.isNotEmpty()) "$time: $noteText" else ""
                    val combined = if (existing.isNotEmpty() && formatted.isNotEmpty()) "$existing\n$formatted" else formatted.ifEmpty { existing }

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