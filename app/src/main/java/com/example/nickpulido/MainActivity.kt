package com.nickpulido.rcrm

import android.Manifest
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.work.*
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.tabs.TabLayout
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()
    private lateinit var prefs: SharedPreferences
    private lateinit var statsPrefs: SharedPreferences

    private lateinit var tvTotalLeads: TextView
    private lateinit var tvTotalLeadsGoal: TextView
    private lateinit var progressTotalLeads: com.google.android.material.progressindicator.CircularProgressIndicator

    private lateinit var progressFollowUps: com.google.android.material.progressindicator.CircularProgressIndicator
    private lateinit var tvFollowUpCountDisplay: TextView
    private lateinit var tvFollowUpGoalDisplay: TextView

    private lateinit var adapter: HotListAdapter
    private val hotListData = mutableListOf<Map<String, Any>>()
    private val allLeadsData = mutableListOf<Map<String, Any>>()
    
    private var selectedDate = Date()
    private val dateKeyFormat = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
    private var statsListener: ListenerRegistration? = null
    
    private var defaultTotalGoal = 10
    private var defaultFollowUpGoal = 5

    private var currentTotalCount = 0
    private var currentTotalGoal = 10
    private var currentFollowUpCount = 0
    private var currentFollowUpGoal = 5

    private var currentTabPosition = 0

    private val backupLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { writeBackupToFile(it) }
    }

    private val restoreLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { readBackupFromFile(it) }
    }

    private val pickContactLauncher = registerForActivityResult(ActivityResultContracts.PickContact()) { uri ->
        uri?.let { processPickedContact(it) }
    }

    private companion object {
        const val PREFS_INTRO = "IntroPresets"
        const val PREFS_KEY = "saved_presets_list"
        const val PREFS_IGNORED = "ignored_contacts_list"
        const val SETTINGS_PREFS = "settings"
        const val NAME_TOKEN = "[NAME]"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // --- Apply Theme from Prefs Before setContentView ---
        prefs = getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
        applyAppTheme()
        
        setContentView(R.layout.activity_main)

        if (auth.currentUser == null) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        statsPrefs = getSharedPreferences("daily_stats_local", Context.MODE_PRIVATE)

        tvTotalLeads = findViewById(R.id.tvTotalLeads)
        tvTotalLeadsGoal = findViewById(R.id.tvTotalLeadsGoal)
        progressTotalLeads = findViewById(R.id.progressTotalLeads)

        progressFollowUps = findViewById(R.id.progressFollowUps)
        tvFollowUpCountDisplay = findViewById(R.id.tvFollowUpCountDisplay)
        tvFollowUpGoalDisplay = findViewById(R.id.tvFollowUpGoalDisplay)

        val listView = findViewById<ListView>(R.id.follow_up_list_view)
        adapter = HotListAdapter(this, hotListData) { docId ->
            db.collection("leads").document(docId)
                .update("followUpDate", null)
                .addOnSuccessListener {
                    Toast.makeText(this, "Follow-up marked complete", Toast.LENGTH_SHORT).show()
                    loadHotList()
                }
        }
        listView.adapter = adapter

        val tabLayout = findViewById<TabLayout>(R.id.tabLayoutFollowUps)
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                currentTabPosition = tab.position
                filterHotList(findViewById<EditText>(R.id.search_bar).text.toString())
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        listView.setOnItemClickListener { _, _, position, _ ->
            val lead = hotListData[position]
            val intent = Intent(this, FollowUpActivity::class.java)
            intent.putExtra("targetPhone", lead["phone"] as? String)
            startActivity(intent)
        }

        findViewById<ImageButton>(R.id.btnSettings).setOnClickListener { showSettingsDialog() }
        findViewById<Button>(R.id.btnQuickAdd).setOnClickListener { showQuickAddDialog() }
        findViewById<Button>(R.id.btnImportContact).setOnClickListener { pickContactLauncher.launch(null) }
        findViewById<Button>(R.id.btnFollowUpList).setOnClickListener { 
            startActivity(Intent(this, FollowUpActivity::class.java)) 
        }
        findViewById<Button>(R.id.btn_view_contacts).setOnClickListener {
            startActivity(Intent(this, ContactsActivity::class.java))
        }

        findViewById<Button>(R.id.btnPickDate).setOnClickListener { showDatePicker() }

        // --- Manual Adjustments (Long Press) ---
        findViewById<View>(R.id.cardTotalLeads).setOnLongClickListener {
            showAdjustCountDialog("total_count", "Manual Contacts Adjustment", currentTotalCount)
            true
        }
        findViewById<View>(R.id.cardTotalLeads).setOnClickListener {
            showGoalDialog("total_goal", "Daily New Leads Goal", currentTotalGoal)
        }

        findViewById<View>(R.id.cardFollowUpProgress).setOnLongClickListener {
            showAdjustCountDialog("followup_count", "Manual Follow-Ups Adjustment", currentFollowUpCount)
            true
        }
        findViewById<View>(R.id.cardFollowUpProgress).setOnClickListener {
            showGoalDialog("followup_goal", "Daily Follow-up Goal", currentFollowUpGoal)
        }
        // ----------------------------------------

        findViewById<EditText>(R.id.search_bar).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterHotList(s.toString())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        checkPermissions()
        loadLocalStats()
        loadAccountSettings()
        loadHotList()
        startStatsListener()
        
        if (prefs.getBoolean("side_kick_enabled", false)) {
            scheduleSideKickWorker()
        }
    }

    private fun applyAppTheme() {
        val followSystem = prefs.getBoolean("follow_system_theme", true)
        val isDarkMode = prefs.getBoolean("dark_mode", false)

        if (followSystem) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        } else {
            if (isDarkMode) {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            } else {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        loadLocalStats()
        loadHotList() // Refresh reminders when returning to main screen
    }

    private fun loadLocalStats() {
        val dateStr = dateKeyFormat.format(selectedDate)
        currentTotalGoal = statsPrefs.getInt("${dateStr}_total_goal", defaultTotalGoal)
        currentTotalCount = statsPrefs.getInt("${dateStr}_total_count", 0)
        currentFollowUpGoal = statsPrefs.getInt("${dateStr}_followup_goal", defaultFollowUpGoal)
        currentFollowUpCount = statsPrefs.getInt("${dateStr}_followup_count", 0)
        refreshProgressUI()
        
        // Update date display
        val displayFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
        findViewById<TextView>(R.id.tvSelectedDateDisplay).text = if (dateKeyFormat.format(Date()) == dateStr) "Today" else displayFormat.format(selectedDate)
    }

    private fun saveLocalStat(key: String, value: Int) {
        val dateStr = dateKeyFormat.format(selectedDate)
        statsPrefs.edit { putInt("${dateStr}_$key", value) }
    }

    private fun loadAccountSettings() {
        val currentUser = auth.currentUser ?: return
        val userId = currentUser.uid
        val userEmail = currentUser.email ?: ""
        
        // Automatically save the user's email so Admins can find them
        if (userEmail.isNotEmpty()) {
            db.collection("user_settings").document(userId)
                .set(mapOf("email" to userEmail.lowercase()), SetOptions.merge())
        }
        
        val prefsForPresets = getSharedPreferences(PREFS_INTRO, Context.MODE_PRIVATE)
        val lastUser = prefsForPresets.getString("last_logged_in_user", userId)
        if (lastUser != userId) {
            prefsForPresets.edit { 
                remove(PREFS_KEY) 
                putString("last_logged_in_user", userId)
            }
            prefs.edit { remove(PREFS_IGNORED) }
        } else if (!prefsForPresets.contains("last_logged_in_user")) {
            prefsForPresets.edit { putString("last_logged_in_user", userId) }
        }

        db.collection("user_settings").document(userId).addSnapshotListener { snapshot, e ->
            if (e != null) return@addSnapshotListener
            if (snapshot != null && snapshot.exists()) {
                defaultTotalGoal = snapshot.getLong("total_goal")?.toInt() ?: 10
                defaultFollowUpGoal = snapshot.getLong("followup_goal")?.toInt() ?: 5
                
                @Suppress("UNCHECKED_CAST")
                val cloudPresets = snapshot.get("sms_presets") as? List<String>
                if (cloudPresets != null) {
                    getSharedPreferences(PREFS_INTRO, Context.MODE_PRIVATE).edit {
                        putStringSet(PREFS_KEY, cloudPresets.toSet())
                    }
                }

                @Suppress("UNCHECKED_CAST")
                val cloudIgnored = snapshot.get("ignored_numbers") as? List<String>
                if (cloudIgnored != null) {
                    prefs.edit { putStringSet(PREFS_IGNORED, cloudIgnored.toSet()) }
                }

                val dateStr = dateKeyFormat.format(Date())
                if (!statsPrefs.contains("${dateStr}_total_goal")) {
                    currentTotalGoal = defaultTotalGoal
                }
                if (!statsPrefs.contains("${dateStr}_followup_goal")) {
                    currentFollowUpGoal = defaultFollowUpGoal
                }
                refreshProgressUI()
                
                // Check User Role (Defaults to regular "agent" if not found)
                val role = snapshot.getString("role") ?: "agent"
                val btnAdmin = findViewById<Button>(R.id.btnAdminDashboard)
                if (role == "admin" || role == "super_admin") {
                    btnAdmin?.visibility = View.VISIBLE
                    btnAdmin?.text = if (role == "super_admin") "Super Admin Panel" else "Admin Dashboard"
                    btnAdmin?.setOnClickListener {
                        if (role == "super_admin") {
                            startActivity(Intent(this@MainActivity, SuperAdminDashboardActivity::class.java))
                        } else {
                            startActivity(Intent(this@MainActivity, AdminDashboardActivity::class.java))
                        }
                    }
                } else {
                    btnAdmin?.visibility = View.GONE
                }
            }
        }
    }

    private fun startStatsListener() {
        val userId = auth.currentUser?.uid ?: return
        val dateStr = dateKeyFormat.format(selectedDate)
        statsListener?.remove()
        statsListener = db.collection("user_settings").document(userId)
            .collection("daily_stats").document(dateStr).addSnapshotListener { doc, _ ->
                if (doc != null && doc.exists()) {
                    currentTotalGoal = doc.getLong("total_goal")?.toInt() ?: currentTotalGoal
                    currentTotalCount = doc.getLong("total_count")?.toInt() ?: 0
                    currentFollowUpGoal = doc.getLong("followup_goal")?.toInt() ?: currentFollowUpGoal
                    currentFollowUpCount = doc.getLong("followup_count")?.toInt() ?: 0
                    
                    saveLocalStat("total_goal", currentTotalGoal)
                    saveLocalStat("total_count", currentTotalCount)
                    saveLocalStat("followup_goal", currentFollowUpGoal)
                    saveLocalStat("followup_count", currentFollowUpCount)
                }
                refreshProgressUI()
            }
    }

    private fun refreshProgressUI() {
        progressTotalLeads.max = if (currentTotalGoal > 0) currentTotalGoal else 1
        progressTotalLeads.progress = currentTotalCount
        tvTotalLeads.text = currentTotalCount.toString()
        tvTotalLeadsGoal.text = "Goal: $currentTotalGoal"

        progressFollowUps.max = if (currentFollowUpGoal > 0) currentFollowUpGoal else 1
        progressFollowUps.progress = currentFollowUpCount
        tvFollowUpCountDisplay.text = currentFollowUpCount.toString()
        tvFollowUpGoalDisplay.text = "Goal: $currentFollowUpGoal"
    }

    private fun showGoalDialog(prefKey: String, title: String, currentVal: Int) {
        val input = EditText(this)
        input.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        input.setText(currentVal.toString())

        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage("Set your target goal:")
            .setView(input)
            .setPositiveButton("Set") { _, _ ->
                val goal = input.text.toString().toIntOrNull() ?: currentVal
                if (prefKey == "total_goal") {
                    currentTotalGoal = goal
                    saveLocalStat("total_goal", goal)
                    updateCloudGoal("total_goal", goal)
                } else {
                    currentFollowUpGoal = goal
                    saveLocalStat("followup_goal", goal)
                    updateCloudGoal("followup_goal", goal)
                }
                refreshProgressUI()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateCloudGoal(key: String, value: Int) {
        val userId = auth.currentUser?.uid ?: return
        db.collection("user_settings").document(userId).set(mapOf(key to value), SetOptions.merge())
        
        val dateStr = dateKeyFormat.format(selectedDate)
        db.collection("user_settings").document(userId)
            .collection("daily_stats").document(dateStr).set(mapOf(key to value), SetOptions.merge())
    }

    private fun showAdjustCountDialog(prefKey: String, title: String, currentVal: Int) {
        val input = EditText(this)
        input.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        input.setText(currentVal.toString())

        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage("Manually adjust today's count:")
            .setView(input)
            .setPositiveButton("Update") { _, _ ->
                val newVal = input.text.toString().toIntOrNull() ?: currentVal
                if (prefKey == "total_count") {
                    currentTotalCount = newVal
                    saveLocalStat("total_count", newVal)
                    updateCloudCount("total_count", newVal)
                } else {
                    currentFollowUpCount = newVal
                    saveLocalStat("followup_count", newVal)
                    updateCloudCount("followup_count", newVal)
                }
                refreshProgressUI()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateCloudCount(key: String, value: Int) {
        val userId = auth.currentUser?.uid ?: return
        val dateStr = dateKeyFormat.format(selectedDate)
        db.collection("user_settings").document(userId)
            .collection("daily_stats").document(dateStr).set(mapOf(key to value), SetOptions.merge())
    }

    private fun showDatePicker() {
        val cal = Calendar.getInstance()
        cal.time = selectedDate
        DatePickerDialog(this, { _, year, month, day ->
            cal.set(year, month, day)
            selectedDate = cal.time
            loadLocalStats()
            startStatsListener()
            loadHotList()
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun showSettingsDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_settings, null)
        val switchSideKick = view.findViewById<MaterialSwitch>(R.id.switchSideKick)
        val switchFollowSystem = view.findViewById<MaterialSwitch>(R.id.switchFollowSystemTheme)
        val switchDarkMode = view.findViewById<MaterialSwitch>(R.id.switchDarkMode)
        
        val etNewPreset = view.findViewById<EditText>(R.id.etNewPreset)
        val btnInsertName = view.findViewById<Button>(R.id.btnInsertNameTagDialog)
        val btnDefaultFollowUp = view.findViewById<Button>(R.id.btnDefaultFollowUp)
        val btnSetAccountDefault = view.findViewById<Button>(R.id.btnSetAccountDefault)
        val btnAddPreset = view.findViewById<Button>(R.id.btnAddPreset)
        val listView = view.findViewById<ListView>(R.id.listViewPresets)
        val btnSignOut = view.findViewById<Button>(R.id.btnDialogSignOut)
        
        val btnDeleteAll = view.findViewById<Button>(R.id.btnDeleteAllContacts)
        val btnExportContacts = view.findViewById<Button>(R.id.btnExportContacts)
        val btnBackupData = view.findViewById<Button>(R.id.btnBackupData)
        val btnRestoreData = view.findViewById<Button>(R.id.btnRestoreData)
        val containerIgnoredContacts = view.findViewById<LinearLayout>(R.id.containerIgnoredContacts)
        
        switchSideKick.isChecked = prefs.getBoolean("side_kick_enabled", false)
        switchFollowSystem.isChecked = prefs.getBoolean("follow_system_theme", true)
        switchDarkMode.isChecked = prefs.getBoolean("dark_mode", false)
        
        switchDarkMode.isEnabled = !switchFollowSystem.isChecked

        switchFollowSystem.setOnCheckedChangeListener { _, isChecked ->
            switchDarkMode.isEnabled = !isChecked
        }

        // --- Ignored Contacts Logic ---
        val ignoredContacts = (prefs.getStringSet(PREFS_IGNORED, emptySet()) ?: emptySet()).toMutableList()
        
        fun refreshIgnoredContacts() {
            containerIgnoredContacts.removeAllViews()
            if (ignoredContacts.isEmpty()) {
                val tv = TextView(this).apply {
                    text = getString(R.string.msg_no_ignored_contacts)
                    gravity = android.view.Gravity.CENTER
                    setPadding(16, 16, 16, 16)
                    setTextColor(Color.parseColor("#666666"))
                }
                containerIgnoredContacts.addView(tv)
            } else {
                for (number in ignoredContacts) {
                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        setPadding(0, 16, 0, 16)
                        gravity = android.view.Gravity.CENTER_VERTICAL
                    }
                    val tvNumber = TextView(this).apply {
                        text = number
                        textSize = 14f
                        setTextColor(Color.parseColor("#2A2A2A"))
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    val btnRemove = ImageButton(this).apply {
                        setImageResource(android.R.drawable.ic_menu_delete)
                        setBackgroundColor(Color.TRANSPARENT)
                        setColorFilter(Color.parseColor("#DC3545"))
                        setOnClickListener {
                            ignoredContacts.remove(number)
                            prefs.edit { putStringSet(PREFS_IGNORED, ignoredContacts.toSet()) }
                            val userId = auth.currentUser?.uid
                            if (userId != null) {
                                db.collection("user_settings").document(userId).set(mapOf("ignored_numbers" to ignoredContacts), SetOptions.merge())
                            }
                            refreshIgnoredContacts()
                        }
                    }
                    row.addView(tvNumber)
                    row.addView(btnRemove)
                    
                    val divider = View(this).apply {
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
                        setBackgroundColor(Color.parseColor("#DDDDDD"))
                    }
                    
                    containerIgnoredContacts.addView(row)
                    containerIgnoredContacts.addView(divider)
                }
            }
        }
        refreshIgnoredContacts()

        // --- SMS Presets Logic ---
        val prefsPresets = getSharedPreferences(PREFS_INTRO, Context.MODE_PRIVATE)
        val presets = (prefsPresets.getStringSet(PREFS_KEY, emptySet()) ?: emptySet()).toMutableList()
        val presetAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, presets)
        listView.adapter = presetAdapter

        btnInsertName.setOnClickListener {
            val start = etNewPreset.selectionStart
            etNewPreset.editableText.insert(start, NAME_TOKEN)
        }

        btnDefaultFollowUp.setOnClickListener {
            etNewPreset.setText("Hi $NAME_TOKEN, just following up to see if you had any questions! Looking forward to connecting again.")
        }

        btnSetAccountDefault.setOnClickListener {
            val text = etNewPreset.text.toString().trim()
            if (text.isNotEmpty()) {
                val userId = auth.currentUser?.uid ?: return@setOnClickListener
                db.collection("user_settings").document(userId).set(mapOf("default_intro_sms" to text), SetOptions.merge())
                Toast.makeText(this, "Account default updated", Toast.LENGTH_SHORT).show()
            }
        }

        btnAddPreset.setOnClickListener {
            val text = etNewPreset.text.toString().trim()
            if (text.isNotEmpty()) {
                presets.add(text)
                prefsPresets.edit { putStringSet(PREFS_KEY, presets.toSet()) }
                syncPresetsToCloud(presets)
                presetAdapter.notifyDataSetChanged()
                etNewPreset.text.clear()
                Toast.makeText(this, "Preset Added", Toast.LENGTH_SHORT).show()
            }
        }

        listView.setOnItemLongClickListener { _, _, position, _ ->
            AlertDialog.Builder(this)
                .setTitle("Delete Preset?")
                .setPositiveButton("Delete") { _, _ ->
                    presets.removeAt(position)
                    prefsPresets.edit { putStringSet(PREFS_KEY, presets.toSet()) }
                    syncPresetsToCloud(presets)
                    presetAdapter.notifyDataSetChanged()
                }
                .setNegativeButton("Cancel", null)
                .show()
            true
        }

        btnSignOut.setOnClickListener {
            auth.signOut()
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }
        
        btnDeleteAll.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Danger Zone")
                .setMessage("Are you sure you want to delete ALL your contacts? This action is permanent and will sync to your account.")
                .setPositiveButton("Delete Everything") { _, _ -> performMassDelete() }
                .setNegativeButton("Cancel", null)
                .show()
        }

        btnExportContacts.setOnClickListener { exportContactsToCSV() }

        btnBackupData.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Backup Data")
                .setMessage("Export all your contacts and settings to a JSON file?")
                .setPositiveButton("Backup") { _, _ -> startBackup() }
                .setNegativeButton("Cancel", null)
                .show()
        }

        btnRestoreData.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Restore Data")
                .setMessage("Restore contacts and settings from a JSON file? This will ADD to your current contacts.")
                .setPositiveButton("Choose File") { _, _ -> restoreLauncher.launch(arrayOf("application/json", "*/*")) }
                .setNegativeButton("Cancel", null)
                .show()
        }

        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setView(view)
            .setPositiveButton("Save") { _, _ ->
                val sideKickEnabled = switchSideKick.isChecked
                val followSystem = switchFollowSystem.isChecked
                val darkMode = switchDarkMode.isChecked
                
                val oldFollowSystem = prefs.getBoolean("follow_system_theme", true)
                val oldDarkMode = prefs.getBoolean("dark_mode", false)

                prefs.edit { 
                    putBoolean("side_kick_enabled", sideKickEnabled)
                    putBoolean("follow_system_theme", followSystem)
                    putBoolean("dark_mode", darkMode)
                }
                
                if (sideKickEnabled) scheduleSideKickWorker() else WorkManager.getInstance(this).cancelUniqueWork("SideKickWorker")
                
                if (followSystem != oldFollowSystem || darkMode != oldDarkMode) {
                    applyAppTheme()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startBackup() {
        backupLauncher.launch("RCRM_Backup_${System.currentTimeMillis()}.json")
    }

    private fun writeBackupToFile(uri: Uri) {
        val userId = auth.currentUser?.uid ?: return
        Toast.makeText(this, "Fetching data for backup...", Toast.LENGTH_SHORT).show()

        db.collection("leads").whereEqualTo("ownerId", userId).get().addOnSuccessListener { snapshot ->
            val leadsList = snapshot.documents.map { it.data ?: emptyMap<String, Any>() }
            
            db.collection("user_settings").document(userId).get().addOnCompleteListener { task ->
                val settingsData = if (task.isSuccessful) {
                    task.result?.data ?: emptyMap<String, Any>()
                } else {
                    emptyMap<String, Any>()
                }
                
                val backupMap = mapOf(
                    "leads" to leadsList,
                    "settings" to settingsData
                )
                
                try {
                    val backupDataToSave = toJsonObject(backupMap).toString(4)
                    contentResolver.openOutputStream(uri)?.use {
                        it.write(backupDataToSave.toByteArray())
                    }
                    Toast.makeText(this, "Backup saved!", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "Error generating backup: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.addOnFailureListener { e ->
            Toast.makeText(this, "Failed to fetch leads: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun readBackupFromFile(uri: Uri) {
        try {
            val content = contentResolver.openInputStream(uri)?.bufferedReader().use { it?.readText() }
            if (content != null) {
                val json = org.json.JSONObject(content)
                restoreData(json)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to read backup file: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun restoreData(json: org.json.JSONObject) {
        val userId = auth.currentUser?.uid ?: return
        
        try {
            val writes = mutableListOf<Pair<com.google.firebase.firestore.DocumentReference, Map<String, Any>>>()

            if (json.has("leads")) {
                val leadsArr = json.getJSONArray("leads")
                for (i in 0 until leadsArr.length()) {
                    val leadObj = leadsArr.getJSONObject(i)
                    @Suppress("UNCHECKED_CAST")
                    val map = unwrapJsonValue(leadObj) as? Map<String, Any> ?: continue
                    val mutableMap = map.toMutableMap()
                    mutableMap["ownerId"] = userId
                    writes.add(Pair(db.collection("leads").document(), mutableMap))
                }
            }
            
            var settingsMap: Map<String, Any>? = null
            if (json.has("settings")) {
                val settingsObj = json.getJSONObject("settings")
                @Suppress("UNCHECKED_CAST")
                settingsMap = unwrapJsonValue(settingsObj) as? Map<String, Any>
            }

            val chunks = writes.chunked(490)
            var completedChunks = 0
            var hasError = false

            fun finishRestore() {
                if (settingsMap != null && settingsMap.isNotEmpty()) {
                    db.collection("user_settings").document(userId)
                        .set(settingsMap, SetOptions.merge())
                        .addOnCompleteListener {
                            Toast.makeText(this, "Restore successful!", Toast.LENGTH_SHORT).show()
                            loadAccountSettings()
                            loadHotList()
                        }
                } else {
                    Toast.makeText(this, "Restore successful!", Toast.LENGTH_SHORT).show()
                    loadAccountSettings()
                    loadHotList()
                }
            }

            if (chunks.isEmpty()) {
                finishRestore()
                return
            }

            for (chunk in chunks) {
                val batch = db.batch()
                for ((ref, data) in chunk) {
                    batch.set(ref, data)
                }
                batch.commit().addOnSuccessListener {
                    if (hasError) return@addOnSuccessListener
                    completedChunks++
                    if (completedChunks == chunks.size) {
                        finishRestore()
                    }
                }.addOnFailureListener { e ->
                    if (!hasError) {
                        hasError = true
                        Toast.makeText(this, "Restore failed: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Invalid backup format: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun toJsonObject(map: Map<String, Any?>): org.json.JSONObject {
        val json = org.json.JSONObject()
        for ((k, v) in map) {
            json.put(k, wrapJsonValue(v))
        }
        return json
    }

    @Suppress("UNCHECKED_CAST")
    private fun wrapJsonValue(value: Any?): Any? {
        return when (value) {
            is Map<*, *> -> toJsonObject(value as Map<String, Any?>)
            is List<*> -> {
                val array = org.json.JSONArray()
                for (item in value) {
                    array.put(wrapJsonValue(item))
                }
                array
            }
            is Timestamp -> {
                val tsMap = mapOf("_type" to "timestamp", "seconds" to value.seconds, "nanoseconds" to value.nanoseconds)
                toJsonObject(tsMap)
            }
            is Number, is String, is Boolean -> value
            null -> org.json.JSONObject.NULL
            else -> value.toString()
        }
    }

    private fun unwrapJsonValue(value: Any): Any? {
        return when (value) {
            is org.json.JSONObject -> {
                val map = mutableMapOf<String, Any?>()
                val keys = value.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    map[k] = unwrapJsonValue(value.get(k))
                }
                if (map["_type"] == "timestamp") {
                    val seconds = (map["seconds"] as? Number)?.toLong() ?: 0L
                    val nanos = (map["nanoseconds"] as? Number)?.toInt() ?: 0
                    Timestamp(seconds, nanos)
                } else {
                    map
                }
            }
            is org.json.JSONArray -> {
                val list = mutableListOf<Any?>()
                for (i in 0 until value.length()) {
                    list.add(unwrapJsonValue(value.get(i)))
                }
                list
            }
            org.json.JSONObject.NULL -> null
            else -> value
        }
    }

    private fun exportContactsToCSV() {
        val userId = auth.currentUser?.uid ?: return
        db.collection("leads").whereEqualTo("ownerId", userId).get().addOnSuccessListener { snapshot ->
            if (snapshot.isEmpty) {
                Toast.makeText(this, "No contacts to export", Toast.LENGTH_SHORT).show()
                return@addOnSuccessListener
            }

            try {
                val csvContent = StringBuilder()
                csvContent.append("Name,Phone,Category,Notes,Follow-Up Date\n")
                
                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

                for (doc in snapshot.documents) {
                    val name = doc.getString("name")?.replace(",", " ") ?: ""
                    val phone = doc.getString("phone") ?: ""
                    val category = doc.getString("category")?.replace(",", " ") ?: ""
                    val notes = doc.getString("notes")?.replace(",", " ")?.replace("\n", " ") ?: ""
                    val followUpDate = doc.getTimestamp("followUpDate")?.toDate()?.let { sdf.format(it) } ?: ""
                    
                    csvContent.append("\"$name\",\"$phone\",\"$category\",\"$notes\",\"$followUpDate\"\n")
                }

                val fileName = "RCRM_Contacts_Export_${System.currentTimeMillis()}.csv"
                val file = File(getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), fileName)
                FileOutputStream(file).use { it.write(csvContent.toString().toByteArray()) }

                val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/csv"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(intent, "Export CSV"))

            } catch (e: Exception) {
                Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun performMassDelete() {
        val userId = auth.currentUser?.uid ?: return
        db.collection("leads").whereEqualTo("ownerId", userId).get().addOnSuccessListener { snapshot ->
            val batch = db.batch()
            for (doc in snapshot.documents) {
                batch.delete(doc.reference)
            }
            batch.commit().addOnSuccessListener {
                Toast.makeText(this, "All contacts deleted successfully", Toast.LENGTH_SHORT).show()
                loadHotList()
            }
        }
    }

    private fun syncPresetsToCloud(list: List<String>) {
        val userId = auth.currentUser?.uid ?: return
        db.collection("user_settings").document(userId).set(mapOf("sms_presets" to list), SetOptions.merge())
    }

    private fun scheduleSideKickWorker() {
        val request = PeriodicWorkRequestBuilder<SideKickWorker>(1, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("SideKickWorker", ExistingPeriodicWorkPolicy.KEEP, request)
    }

    private fun checkPermissions() {
        val perms = arrayOf(Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_CONTACTS)
        val missing = perms.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 101)
        }
    }

    @android.annotation.SuppressLint("Range")
    private fun processPickedContact(uri: Uri) {
        var name = ""
        var phone = ""
        var notes = ""
        
        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    name = cursor.getString(cursor.getColumnIndex(android.provider.ContactsContract.Contacts.DISPLAY_NAME)) ?: ""
                    val contactId = cursor.getString(cursor.getColumnIndex(android.provider.ContactsContract.Contacts._ID))
                    val hasPhone = cursor.getString(cursor.getColumnIndex(android.provider.ContactsContract.Contacts.HAS_PHONE_NUMBER))?.toIntOrNull() ?: 0
                    
                    if (hasPhone > 0) {
                        contentResolver.query(
                            android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                            null,
                            "${android.provider.ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                            arrayOf(contactId),
                            null
                        )?.use { phoneCursor ->
                            if (phoneCursor.moveToFirst()) {
                                phone = phoneCursor.getString(phoneCursor.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER)) ?: ""
                            }
                        }
                    }

                    contentResolver.query(
                        android.provider.ContactsContract.Data.CONTENT_URI,
                        null,
                        "${android.provider.ContactsContract.Data.CONTACT_ID} = ? AND ${android.provider.ContactsContract.Data.MIMETYPE} = ?",
                        arrayOf(contactId, android.provider.ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE),
                        null
                    )?.use { noteCursor ->
                        if (noteCursor.moveToFirst()) {
                            notes = noteCursor.getString(noteCursor.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Note.NOTE)) ?: ""
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to read contact", Toast.LENGTH_SHORT).show()
        }

        showQuickAddDialog(name, phone, notes)
    }

    private fun showQuickAddDialog(prefilledName: String = "", prefilledPhone: String = "", prefilledNotes: String = "") {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_quick_add, null)
        val dialog = AlertDialog.Builder(this).setView(dialogView).create()

        val nameInput = dialogView.findViewById<EditText>(R.id.editQuickName)
        val phoneInput = dialogView.findViewById<EditText>(R.id.editQuickPhone)
        if (prefilledName.isNotEmpty()) nameInput.setText(prefilledName)
        if (prefilledPhone.isNotEmpty()) phoneInput.setText(prefilledPhone)

        val cbRecruit = dialogView.findViewById<CheckBox>(R.id.cbRecruitQuick)
        val cbProspect = dialogView.findViewById<CheckBox>(R.id.cbProspectQuick)
        val cbClient = dialogView.findViewById<CheckBox>(R.id.cbClientQuick)
        val notesInput = dialogView.findViewById<EditText>(R.id.editQuickNotes)
        if (prefilledNotes.isNotEmpty()) notesInput.setText(prefilledNotes)

        val btnSave = dialogView.findViewById<Button>(R.id.btnQuickSave)

        btnSave.setOnClickListener {
            val name = nameInput.text.toString().trim()
            if (name.isEmpty()) return@setOnClickListener

            val cats = mutableListOf<String>()
            if (cbRecruit.isChecked) cats.add(getString(R.string.category_recruit))
            if (cbProspect.isChecked) cats.add(getString(R.string.category_prospect))
            if (cbClient.isChecked) cats.add(getString(R.string.category_client))

            val timestamp = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault()).format(Date())
            val leadData = hashMapOf(
                "name" to name,
                "phone" to phoneInput.text.toString(),
                "category" to cats.joinToString(", "),
                "notes" to "[$timestamp]: ${notesInput.text.ifEmpty { "Created lead" }}",
                "followUpDate" to Timestamp(Date(System.currentTimeMillis() + 86400000)),
                "timestamp" to FieldValue.serverTimestamp(),
                "ownerId" to auth.currentUser?.uid
            )

            db.collection("leads").add(leadData).addOnSuccessListener {
                incrementDailyStat("total_count")
                Toast.makeText(this, "Lead added!", Toast.LENGTH_SHORT).show()
                loadHotList()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun incrementDailyStat(field: String) {
        val userId = auth.currentUser?.uid ?: return
        val dateStr = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())

        // Update locally for immediate UI feedback
        val currentLocalCount = statsPrefs.getInt("${dateStr}_$field", 0)
        statsPrefs.edit { putInt("${dateStr}_$field", currentLocalCount + 1) }
        if (field == "total_count" && dateKeyFormat.format(selectedDate) == dateStr) {
            currentTotalCount = currentLocalCount + 1
            refreshProgressUI()
        }

        db.collection("user_settings").document(userId)
            .collection("daily_stats").document(dateStr)
            .set(hashMapOf(field to FieldValue.increment(1)), SetOptions.merge())
    }

    private fun loadHotList() {
        val userId = auth.currentUser?.uid ?: return
        db.collection("leads")
            .whereEqualTo("ownerId", userId)
            .get()
            .addOnSuccessListener { snapshot ->
                allLeadsData.clear()
                for (doc in snapshot.documents) {
                    val data = doc.data?.toMutableMap() ?: continue
                    data["__docId"] = doc.id
                    allLeadsData.add(data)
                }
                filterHotList(findViewById<EditText>(R.id.search_bar).text.toString())
            }
    }

    private fun filterHotList(query: String) {
        hotListData.clear()
        
        val sdf = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
        val selectedDateStr = sdf.format(selectedDate)
        
        val tomorrowCal = Calendar.getInstance()
        tomorrowCal.time = selectedDate
        tomorrowCal.add(Calendar.DAY_OF_YEAR, 1)
        val tomorrowDateStr = sdf.format(tomorrowCal.time)

        val filtered = allLeadsData.filter { lead ->
            val name = lead["name"] as? String ?: ""
            val phone = lead["phone"] as? String ?: ""
            val matchesSearch = name.contains(query, true) || phone.contains(query, true)
            
            if (!matchesSearch) return@filter false
            
            val followUpDate = lead["followUpDate"] as? Timestamp
            val leadDateStr = followUpDate?.toDate()?.let { sdf.format(it) } ?: ""
            
            when (currentTabPosition) {
                0 -> leadDateStr == selectedDateStr // Today only
                1 -> leadDateStr == tomorrowDateStr // Tomorrow
                2 -> leadDateStr.isNotEmpty() && leadDateStr < selectedDateStr // Overdue
                else -> true
            }
        }
        hotListData.addAll(filtered)
        adapter.notifyDataSetChanged()
    }
}

class HotListAdapter(
    private val context: Context, 
    private val data: List<Map<String, Any>>,
    private val onCompleteLead: (String) -> Unit
) : BaseAdapter() {
    override fun getCount(): Int = data.size
    override fun getItem(position: Int) = data[position]
    override fun getItemId(position: Int) = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_lead_card, parent, false)
        val lead = data[position]
        
        val name = lead["name"] as? String ?: "Unknown"
        val phone = lead["phone"] as? String ?: ""
        val category = lead["category"] as? String ?: ""
        val notes = lead["notes"] as? String ?: ""
        val followUpDate = lead["followUpDate"] as? Timestamp
        
        view.findViewById<TextView>(R.id.tvLeadName).text = name
        
        val sdf = SimpleDateFormat("MMM dd, yyyy @ hh:mm a", Locale.getDefault())
        val dateStr = followUpDate?.toDate()?.let { sdf.format(it) } ?: ""
        
        val tvCategoryLabel = view.findViewById<TextView>(R.id.tvCategoryLabel)
        if (category.isNotEmpty()) {
            tvCategoryLabel.visibility = View.VISIBLE
            tvCategoryLabel.text = category
            
            val bgColor = when {
                category.contains(context.getString(R.string.category_recruit), ignoreCase = true) -> 
                    Color.parseColor("#4CAF50")
                category.contains(context.getString(R.string.category_client), ignoreCase = true) -> 
                    Color.parseColor("#9C27B0")
                else -> Color.parseColor("#007BFF")
            }
            
            val background = GradientDrawable()
            background.setColor(bgColor)
            background.cornerRadius = 16f
            tvCategoryLabel.background = background
            tvCategoryLabel.setTextColor(Color.WHITE)
        } else {
            tvCategoryLabel.visibility = View.GONE
        }

        val latestNote = notes.substringBefore("\n\n").replace(Regex("^\\[.*?\\]: "), "")
        val fallbackNote = if (latestNote.isEmpty()) "No notes available" else latestNote
        
        val detailsText = if (dateStr.isNotEmpty()) {
            "Due: $dateStr\n$fallbackNote"
        } else {
            fallbackNote
        }
        
        view.findViewById<TextView>(R.id.tvLeadDetails).text = detailsText
        
        val llCompleteAction = view.findViewById<LinearLayout>(R.id.llCompleteAction)
        llCompleteAction?.setOnClickListener {
            val docId = lead["__docId"] as? String ?: return@setOnClickListener
            onCompleteLead(docId)
        }

        val btnCallIcon = view.findViewById<ImageView>(R.id.btnCallIcon)
        btnCallIcon.setOnClickListener {
            if (phone.isNotEmpty()) {
                context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")))
            } else {
                Toast.makeText(context, context.getString(R.string.msg_no_phone), Toast.LENGTH_SHORT).show()
            }
        }
        
        return view
    }
}
