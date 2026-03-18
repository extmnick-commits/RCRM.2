package com.nickpulido.rcrm

import android.annotation.SuppressLint
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.ContentProviderOperation
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.net.toUri
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import java.text.SimpleDateFormat
import java.util.*

class SmartLogActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()
    private var phoneNumber: String = ""
    private var contactName: String = ""
    private var isNewContact: Boolean = true
    private var followUpDate: Date? = null
    private var accountDefaultSms: String? = null
    
    private var todayPresetHour: Int = 19
    private var todayPresetMinute: Int = 0
    private var tomorrowPresetHour: Int = 19
    private var tomorrowPresetMinute: Int = 0

    private lateinit var statsPrefs: SharedPreferences

    private companion object {
        const val PREFS_NAME = "IntroPresets"
        const val PREFS_KEY = "saved_presets_list"
        const val PREFS_IGNORED = "ignored_contacts_list"
        const val NAME_TOKEN = "[NAME]"
        const val TAG = "SmartLogActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_smart_log)

        statsPrefs = getSharedPreferences("daily_stats_local", Context.MODE_PRIVATE)

        phoneNumber = intent.getStringExtra("INCOMING_NUMBER") ?: ""
        val (name, exists) = getContactNameAndStatus(phoneNumber)
        contactName = name
        isNewContact = !exists

        val etContactName = findViewById<EditText>(R.id.etContactName)
        val tvLogPhoneNumber = findViewById<TextView>(R.id.tvLogPhoneNumber)
        val btnSkip = findViewById<Button>(R.id.btnSkipPersonal)
        val btnIgnore = findViewById<Button>(R.id.btnIgnoreCallLog)
        val cbRecruit = findViewById<CheckBox>(R.id.cbRecruit)
        val cbProspect = findViewById<CheckBox>(R.id.cbProspect)
        val cbClient = findViewById<CheckBox>(R.id.cbClient)
        val clientSubCategoryLayout = findViewById<LinearLayout>(R.id.clientSubCategoryLayout)
        val cbInvestment = findViewById<CheckBox>(R.id.cbInvestment)
        val cbLifeInsurance = findViewById<CheckBox>(R.id.cbLifeInsurance)
        val etNotes = findViewById<EditText>(R.id.etQuickNote)
        val etIntroText = findViewById<EditText>(R.id.etIntroText)
        val btnSave = findViewById<Button>(R.id.btnSaveLeadAction)
        val btnSms = findViewById<Button>(R.id.btnSendIntroAction)

        val btnManagePresets = findViewById<Button>(R.id.btnManagePresets)
        val btnDefaultIntro = findViewById<Button>(R.id.btnDefaultIntro)

        etContactName.setText(contactName)
        tvLogPhoneNumber.text = phoneNumber

        cbClient.setOnCheckedChangeListener { _, isChecked ->
            clientSubCategoryLayout.visibility = if (isChecked) View.VISIBLE else View.GONE
            if (!isChecked) {
                cbInvestment.isChecked = false
                cbLifeInsurance.isChecked = false
            }
        }

        fun applyDefaultIntro() {
            val currentName = etContactName.text.toString()
            val firstName = currentName.split(" ").firstOrNull() ?: currentName
            val baseMsg = accountDefaultSms?.takeIf { it.isNotBlank() } ?: "Hi $NAME_TOKEN, just following up to see if you had any questions! Looking forward to connecting again."
            etIntroText.setText(baseMsg.replace(NAME_TOKEN, firstName))
        }

        // Load local defaults for immediate UI
        val sharedPrefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        todayPresetHour = sharedPrefs.getInt("today_preset_hour", 19)
        todayPresetMinute = sharedPrefs.getInt("today_preset_minute", 0)
        tomorrowPresetHour = sharedPrefs.getInt("tomorrow_preset_hour", 19)
        tomorrowPresetMinute = sharedPrefs.getInt("tomorrow_preset_minute", 0)
        updateFollowUpButtons()

        loadAccountSettings { 
            applyDefaultIntro()
            updateFollowUpButtons()
        }

        btnSkip.setOnClickListener { finish() }

        btnIgnore.setOnClickListener {
            if (phoneNumber.isNotEmpty()) {
                val userId = auth.currentUser?.uid
                if (userId != null) {
                    // Save locally for immediate effect in CallReceiver
                    val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
                    val ignored = prefs.getStringSet(PREFS_IGNORED, mutableSetOf())?.toMutableSet() ?: mutableSetOf()
                    ignored.add(phoneNumber)
                    prefs.edit { putStringSet(PREFS_IGNORED, ignored) }

                    // Sync to Firestore
                    db.collection("user_settings").document(userId)
                        .update("ignored_numbers", FieldValue.arrayUnion(phoneNumber))
                        .addOnFailureListener {
                            db.collection("user_settings").document(userId)
                                .set(mapOf("ignored_numbers" to listOf(phoneNumber)), SetOptions.merge())
                        }

                    Toast.makeText(this, "Contact ignored from future logs", Toast.LENGTH_SHORT).show()
                    finish()
                } else {
                    Toast.makeText(this, "Error: Must be logged in to sync settings", Toast.LENGTH_SHORT).show()
                }
            }
        }

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

        val btn7pmToday = findViewById<Button>(R.id.btn7pmToday)
        val btn7pmTomorrow = findViewById<Button>(R.id.btn7pmTomorrow)

        btn7pmToday.setOnClickListener {
            val cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, todayPresetHour)
                set(Calendar.MINUTE, todayPresetMinute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            followUpDate = cal.time
            val sdf = SimpleDateFormat("MMM dd, hh:mm a", Locale.getDefault())
            Toast.makeText(this, "Reminder set: ${sdf.format(followUpDate!!)}", Toast.LENGTH_SHORT).show()
        }

        btn7pmToday.setOnLongClickListener {
            TimePickerDialog(this, { _, hour, minute ->
                todayPresetHour = hour
                todayPresetMinute = minute
                updateFollowUpButtons()
                
                getSharedPreferences("settings", Context.MODE_PRIVATE).edit {
                    putInt("today_preset_hour", todayPresetHour)
                    putInt("today_preset_minute", todayPresetMinute)
                }
                
                auth.currentUser?.uid?.let { userId ->
                    db.collection("user_settings").document(userId)
                        .set(mapOf("today_preset_hour" to todayPresetHour, "today_preset_minute" to todayPresetMinute), SetOptions.merge())
                }
                Toast.makeText(this, getString(R.string.msg_preset_updated), Toast.LENGTH_SHORT).show()
            }, todayPresetHour, todayPresetMinute, false).show()
            true
        }

        btn7pmTomorrow.setOnClickListener {
            val cal = Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, 1)
                set(Calendar.HOUR_OF_DAY, tomorrowPresetHour)
                set(Calendar.MINUTE, tomorrowPresetMinute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            followUpDate = cal.time
            val sdf = SimpleDateFormat("MMM dd, hh:mm a", Locale.getDefault())
            Toast.makeText(this, "Reminder set: ${sdf.format(followUpDate!!)}", Toast.LENGTH_SHORT).show()
        }

        btn7pmTomorrow.setOnLongClickListener {
            TimePickerDialog(this, { _, hour, minute ->
                tomorrowPresetHour = hour
                tomorrowPresetMinute = minute
                updateFollowUpButtons()
                
                getSharedPreferences("settings", Context.MODE_PRIVATE).edit {
                    putInt("tomorrow_preset_hour", tomorrowPresetHour)
                    putInt("tomorrow_preset_minute", tomorrowPresetMinute)
                }
                
                auth.currentUser?.uid?.let { userId ->
                    db.collection("user_settings").document(userId)
                        .set(mapOf("tomorrow_preset_hour" to tomorrowPresetHour, "tomorrow_preset_minute" to tomorrowPresetMinute), SetOptions.merge())
                }
                Toast.makeText(this, getString(R.string.msg_preset_updated), Toast.LENGTH_SHORT).show()
            }, tomorrowPresetHour, tomorrowPresetMinute, false).show()
            true
        }

        btnManagePresets.setOnClickListener { showPresetManagerDialog(etIntroText, etContactName) }
        btnDefaultIntro.setOnClickListener { applyDefaultIntro() }

        btnSave.setOnClickListener {
            Log.d(TAG, "Save button clicked")
            val newName = etContactName.text.toString()
            if (newName != contactName) {
                saveOrUpdateSystemContact(newName, phoneNumber)
                contactName = newName
            }
            performSaveLeadAction(cbRecruit, cbProspect, cbClient, cbInvestment, cbLifeInsurance, etNotes) {
                Toast.makeText(this, "Lead History Saved", Toast.LENGTH_SHORT).show()
                finish()
            }
        }

        btnSms.setOnClickListener {
            Log.d(TAG, "SMS button clicked")
            val newName = etContactName.text.toString()
            if (newName != contactName) {
                saveOrUpdateSystemContact(newName, phoneNumber)
                contactName = newName
            }
            performSaveLeadAction(cbRecruit, cbProspect, cbClient, cbInvestment, cbLifeInsurance, etNotes) {
                val message = etIntroText.text.toString()
                val smsIntent = Intent(Intent.ACTION_VIEW, "sms:$phoneNumber".toUri())
                smsIntent.putExtra("sms_body", message)
                startActivity(smsIntent)
                finish()
            }
        }
    }

    private fun loadAccountSettings(onLoaded: () -> Unit) {
        val userId = auth.currentUser?.uid ?: return
        
        val prefsForPresets = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastUser = prefsForPresets.getString("last_logged_in_user", userId)
        if (lastUser != userId) {
            prefsForPresets.edit { 
                remove(PREFS_KEY) 
                remove("local_account_default_sms")
                putString("last_logged_in_user", userId)
            }
        } else if (!prefsForPresets.contains("last_logged_in_user")) {
            prefsForPresets.edit { putString("last_logged_in_user", userId) }
        }

        accountDefaultSms = prefsForPresets.getString("local_account_default_sms", null)

        db.collection("user_settings").document(userId).get().addOnSuccessListener { snapshot ->
            if (snapshot.exists()) {
                accountDefaultSms = snapshot.getString("default_intro_sms")
                
                prefsForPresets.edit {
                    putString("local_account_default_sms", accountDefaultSms)
                }
                
                todayPresetHour = snapshot.getLong("today_preset_hour")?.toInt() ?: 19
                todayPresetMinute = snapshot.getLong("today_preset_minute")?.toInt() ?: 0
                tomorrowPresetHour = snapshot.getLong("tomorrow_preset_hour")?.toInt() ?: 19
                tomorrowPresetMinute = snapshot.getLong("tomorrow_preset_minute")?.toInt() ?: 0

                getSharedPreferences("settings", Context.MODE_PRIVATE).edit {
                    putInt("today_preset_hour", todayPresetHour)
                    putInt("today_preset_minute", todayPresetMinute)
                    putInt("tomorrow_preset_hour", tomorrowPresetHour)
                    putInt("tomorrow_preset_minute", tomorrowPresetMinute)
                }
                
                @Suppress("UNCHECKED_CAST")
                val cloudPresets = snapshot.get("sms_presets") as? List<String>
                if (cloudPresets != null) {
                    getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                        putStringSet(PREFS_KEY, cloudPresets.toSet())
                    }
                }

                @Suppress("UNCHECKED_CAST")
                val cloudIgnored = snapshot.get("ignored_numbers") as? List<String>
                if (cloudIgnored != null) {
                    getSharedPreferences("settings", Context.MODE_PRIVATE).edit {
                        putStringSet(PREFS_IGNORED, cloudIgnored.toSet())
                    }
                }
            }
            onLoaded()
        }.addOnFailureListener {
            onLoaded()
        }
    }

    private fun showDateTimePicker(initialCal: Calendar) {
        DatePickerDialog(this, { _, year, month, day ->
            initialCal.set(year, month, day)
            TimePickerDialog(this, { _, hour, minute ->
                initialCal.set(Calendar.HOUR_OF_DAY, hour)
                initialCal.set(Calendar.MINUTE, minute)
                val selectedDate = initialCal.time
                followUpDate = selectedDate
                
                val sdf = SimpleDateFormat("MMM dd, hh:mm a", Locale.getDefault())
                Toast.makeText(this, "Reminder set: ${sdf.format(selectedDate)}", Toast.LENGTH_SHORT).show()
            }, initialCal.get(Calendar.HOUR_OF_DAY), initialCal.get(Calendar.MINUTE), false).show()
        }, initialCal.get(Calendar.YEAR), initialCal.get(Calendar.MONTH), initialCal.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun showPresetManagerDialog(etTarget: EditText, etNameSource: EditText) {
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
                val currentName = etNameSource.text.toString()
                val firstName = currentName.split(" ").firstOrNull() ?: currentName
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

    private fun performSaveLeadAction(cbR: CheckBox, cbP: CheckBox, cbC: CheckBox, cbInv: CheckBox, cbLife: CheckBox, etN: EditText, onSuccess: () -> Unit) {
        val cats = mutableListOf<String>()
        if (cbR.isChecked) cats.add(getString(R.string.category_recruit))
        if (cbP.isChecked) cats.add(getString(R.string.category_prospect))
        if (cbC.isChecked) {
            cats.add(getString(R.string.category_client))
            if (cbInv.isChecked) cats.add("Investment")
            if (cbLife.isChecked) cats.add("Life Insurance")
        }

        val finalCategory = cats.joinToString(", ")
        val noteText = etN.text.toString()
        val currentUserId = auth.currentUser?.uid

        if (currentUserId == null) {
            Log.e(TAG, "No user logged in")
            Toast.makeText(this, "Error: You must be logged in to save leads", Toast.LENGTH_LONG).show()
            return
        }

        // Normalize phone number for more accurate matching
        val normalizedIncoming = phoneNumber.replace(Regex("[^0-9]"), "")

        db.collection("leads")
            .whereEqualTo("ownerId", currentUserId)
            .get()
            .addOnSuccessListener { snapshots ->
                // Check for existing contact manually to handle formatting differences
                val existingLead = snapshots.documents.find { doc ->
                    val storedPhone = doc.getString("phone") ?: ""
                    val storedName = doc.getString("name") ?: ""
                    val normalizedStored = storedPhone.replace(Regex("[^0-9]"), "")
                    
                    val phoneMatches = normalizedIncoming.isNotEmpty() && normalizedStored.isNotEmpty() && normalizedStored == normalizedIncoming
                    val rawPhoneMatches = phoneNumber.isNotEmpty() && storedPhone.isNotEmpty() && storedPhone == phoneNumber
                    val nameMatches = contactName.isNotEmpty() && storedName.equals(contactName, ignoreCase = true)
                    
                    if (phoneNumber.isNotEmpty()) {
                        phoneMatches || rawPhoneMatches
                    } else {
                        nameMatches
                    }
                }

                val time = SimpleDateFormat("[MMM dd, hh:mm a]", Locale.getDefault()).format(Date())
                
                if (existingLead == null) {
                    // REALLY a new contact for this user
                    Log.d(TAG, "New lead detected, incrementing goal")
                    val formattedNote = if (noteText.isNotEmpty()) "$time: $noteText" else ""
                    val data = hashMapOf(
                        "name" to contactName, 
                        "phone" to phoneNumber, 
                        "category" to finalCategory,
                        "notes" to formattedNote, 
                        "followUpDate" to followUpDate?.let { Timestamp(it) }, 
                        "timestamp" to Timestamp.now(),
                        "ownerId" to currentUserId,
                        "source" to "smart_log"
                    )
                    db.collection("leads").add(data)
                        .addOnSuccessListener { 
                            incrementDailyStat("total_count")
                            onSuccess() 
                        }
                        .addOnFailureListener { e ->
                            Log.e(TAG, "Failed to add lead", e)
                            Toast.makeText(this, "Failed to save lead", Toast.LENGTH_SHORT).show()
                        }
                } else {
                    // Lead exists, just update it
                    Log.d(TAG, "Existing lead found, updating notes only")
                    val docId = existingLead.id
                    val existingNotes = existingLead.getString("notes") ?: ""
                    val formatted = if (noteText.isNotEmpty()) "$time: $noteText" else ""
                    val combined = if (existingNotes.isNotEmpty() && formatted.isNotEmpty()) "$existingNotes\n\n$formatted" else formatted.ifEmpty { existingNotes }

                    val update = mutableMapOf<String, Any?>(
                        "name" to contactName,
                        "category" to finalCategory, 
                        "notes" to combined, 
                        "timestamp" to Timestamp.now()
                    )
                    followUpDate?.let { update["followUpDate"] = Timestamp(it) }
                    
                    db.collection("leads").document(docId).update(update)
                        .addOnSuccessListener { 
                            onSuccess() 
                        }
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Error checking for existing lead", e)
            }
    }

    private fun incrementDailyStat(field: String) {
        val userId = auth.currentUser?.uid ?: return
        val dateStr = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
        
        // 1. Update Local Prefs for immediate UI feedback in MainActivity
        val currentLocalCount = statsPrefs.getInt("${dateStr}_$field", 0)
        statsPrefs.edit { putInt("${dateStr}_$field", currentLocalCount + 1) }
        Log.d(TAG, "Incremented local stat $field to ${currentLocalCount + 1}")

        // 2. Sync to Firestore
        db.collection("user_settings").document(userId)
            .collection("daily_stats").document(dateStr)
            .set(hashMapOf(field to FieldValue.increment(1)), SetOptions.merge())
            .addOnSuccessListener {
                Log.d(TAG, "Successfully synced increment to Firestore")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to sync increment to Firestore", e)
            }
    }

    private fun saveOrUpdateSystemContact(name: String, phone: String) {
        val contactId = getContactId(phone)
        val ops = arrayListOf<ContentProviderOperation>()

        if (contactId == null) {
            ops.add(ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                .build())
            ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
                .build())
            ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, phone)
                .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                .build())
        } else {
            ops.add(ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI)
                .withSelection(
                    "${ContactsContract.Data.CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=?",
                    arrayOf(contactId.toString(), ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                )
                .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
                .build())
        }

        try {
            contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
        } catch (e: Exception) {
            Log.e(TAG, "Error saving/updating system contact", e)
        }
    }

    private fun getContactId(phone: String): Long? {
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phone))
        val projection = arrayOf(ContactsContract.PhoneLookup._ID)
        try {
            contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    return cursor.getLong(0)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting contact ID", e)
        }
        return null
    }

    private fun saveAllPresetsLocally(list: List<String>) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putStringSet(PREFS_KEY, list.toSet())
        }
        syncPresetsToCloud(list)
    }

    private fun syncPresetsToCloud(list: List<String>) {
        val userId = auth.currentUser?.uid ?: return
        db.collection("user_settings").document(userId)
            .set(mapOf("sms_presets" to list), SetOptions.merge())
    }

    private fun getPresetsLocally(): Set<String> {
        return getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getStringSet(PREFS_KEY, emptySet()) ?: emptySet()
    }

    private fun updateFollowUpButtons() {
        val btnToday = findViewById<Button>(R.id.btn7pmToday) ?: return
        val btnTomorrow = findViewById<Button>(R.id.btn7pmTomorrow) ?: return
        
        val todayCal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, todayPresetHour)
            set(Calendar.MINUTE, todayPresetMinute)
        }
        val tomorrowCal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, tomorrowPresetHour)
            set(Calendar.MINUTE, tomorrowPresetMinute)
        }
        
        val sdfToday = SimpleDateFormat(if (todayPresetMinute == 0) "h a" else "h:mm a", Locale.getDefault())
        val todayTimeStr = sdfToday.format(todayCal.time)
        
        val sdfTomorrow = SimpleDateFormat(if (tomorrowPresetMinute == 0) "h a" else "h:mm a", Locale.getDefault())
        val tomorrowTimeStr = sdfTomorrow.format(tomorrowCal.time)
        
        btnToday.text = getString(R.string.btn_preset_today_format, todayTimeStr)
        btnTomorrow.text = getString(R.string.btn_preset_tomorrow_format, tomorrowTimeStr)
    }

    @SuppressLint("Range")
    private fun getContactNameAndStatus(phone: String): Pair<String, Boolean> {
        val unknown = getString(R.string.unknown_contact)
        if (phone.isEmpty()) return Pair(unknown, false)
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phone))
        val projection = arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME)
        var name = unknown
        var exists = false
        try {
            contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    name = cursor.getString(0)
                    exists = true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying contact name", e)
        }
        return Pair(name, exists)
    }
}
