package com.nickpulido.rcrm

import android.app.AlarmManager
import android.app.DatePickerDialog
import android.app.PendingIntent
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.CalendarContract
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.work.*
import com.google.android.material.materialswitch.MaterialSwitch
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

class FollowUpActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()
    private val allLeadsData = mutableListOf<Map<String, Any>>()
    private val displayLeadsData = mutableListOf<Map<String, Any>>()
    private lateinit var adapter: FollowUpAdapter
    private var currentSearchQuery = ""
    private var currentCategoryFilter: String? = null
    private var targetPhone: String? = null
    private var targetUserId: String? = null
    
    private val selectedDocIds = mutableSetOf<String>()
    private var isSelectionMode = false

    private var accountDefaultSms: String? = null
    private var defaultGoal: Int = 10
    private var defaultFollowUpGoal: Int = 5
    private val dateKeyFormat = SimpleDateFormat("yyyyMMdd", Locale.getDefault())

    private lateinit var progressFollowUps: com.google.android.material.progressindicator.CircularProgressIndicator
    private lateinit var tvFollowUpCountDisplay: TextView
    private lateinit var tvFollowUpGoalDisplay: TextView
    
    private lateinit var statsPrefs: SharedPreferences
    private lateinit var settingsPrefs: SharedPreferences
    private var statsListener: ListenerRegistration? = null
    
    private var currentFollowUpCount = 0
    private var currentFollowUpGoal = 5
    private var hasPulsedFollowUpGoal = false

    private val backupLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { writeBackupToFile(it) }
    }

    private val restoreLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { readBackupFromFile(it) }
    }

    private companion object {
        const val PREFS_INTRO = "IntroPresets"
        const val PREFS_SETTINGS = "settings"
        const val KEY_SIDE_KICK = "side_kick_enabled"
        const val PREFS_KEY = "saved_presets_list"
        const val PREFS_IGNORED = "ignored_contacts_list"
        const val NAME_TOKEN = "[NAME]"
        const val TAG = "FollowUpActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        settingsPrefs = getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
        applyAppTheme()

        setContentView(R.layout.activity_follow_up)

        if (auth.currentUser == null) {
            finish()
            return
        }

        statsPrefs = getSharedPreferences("daily_stats_local", Context.MODE_PRIVATE)

        progressFollowUps = findViewById(R.id.progressFollowUps)
        tvFollowUpCountDisplay = findViewById(R.id.tvFollowUpCountDisplay)
        tvFollowUpGoalDisplay = findViewById(R.id.tvFollowUpGoalDisplay)

        targetPhone = intent.getStringExtra("targetPhone")
        targetUserId = intent.getStringExtra("TARGET_USER_ID")

        val listView = findViewById<ListView>(R.id.listViewFollowUps)
        adapter = FollowUpAdapter(this, displayLeadsData) { docId, isChecked ->
            if (isChecked) selectedDocIds.add(docId) else selectedDocIds.remove(docId)
            updateSelectionTitle()
        }
        listView.adapter = adapter

        listView.setOnItemClickListener { _, _, position, _ ->
            if (!isSelectionMode) {
                val lead = displayLeadsData[position]
                showLeadDetailsDialog(lead, lead["__docId"] as String)
            }
        }

        findViewById<EditText>(R.id.etSearchLeads).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                currentSearchQuery = s.toString()
                applyFilters()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        findViewById<ImageButton>(R.id.btnFilterCategory).setOnClickListener { showFilterPopupMenu(it) }
        findViewById<ImageButton>(R.id.btnSettings).setOnClickListener { showSettingsDialog() }

        findViewById<View>(R.id.cardFollowUpProgress).setOnClickListener {
            showGoalDialog("followup_goal", "Daily Follow-up Goal", currentFollowUpGoal)
        }

        findViewById<Button>(R.id.btnBulkDelete).setOnClickListener {
            if (selectedDocIds.isEmpty()) {
                Toast.makeText(this, "No leads selected", Toast.LENGTH_SHORT).show()
            } else {
                AlertDialog.Builder(this)
                    .setTitle("Delete Selected?")
                    .setMessage("Are you sure you want to delete ${selectedDocIds.size} leads?")
                    .setPositiveButton("Delete") { _, _ -> performBulkDelete() }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }

        findViewById<Button>(R.id.btnCancelBulk).setOnClickListener {
            exitSelectionMode()
        }
        
        if (targetUserId != null) {
            findViewById<TextView>(R.id.tvFollowUpHeader).text = "Viewing Team Member Leads"
        }

        loadLocalStats()
        loadLeadsFromCloud()
        loadAccountSettings()
        startStatsListener()
    }

    private fun applyAppTheme() {
        val followSystem = settingsPrefs.getBoolean("follow_system_theme", true)
        val isDarkMode = settingsPrefs.getBoolean("dark_mode", false)

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

    private fun loadLocalStats() {
        val dateStr = dateKeyFormat.format(Date())
        currentFollowUpGoal = statsPrefs.getInt("${dateStr}_followup_goal", defaultFollowUpGoal)
        currentFollowUpCount = statsPrefs.getInt("${dateStr}_followup_count", 0)
        refreshProgressUI()
    }

    private fun saveLocalStat(key: String, value: Int) {
        val dateStr = dateKeyFormat.format(Date())
        statsPrefs.edit { putInt("${dateStr}_$key", value) }
    }

    private fun loadAccountSettings() {
        val userId = auth.currentUser?.uid ?: return
        
        val prefsForPresets = getSharedPreferences(PREFS_INTRO, Context.MODE_PRIVATE)
        val lastUser = prefsForPresets.getString("last_logged_in_user", userId)
        if (lastUser != userId) {
            prefsForPresets.edit { 
                remove(PREFS_KEY) 
                remove("local_account_default_sms")
                putString("last_logged_in_user", userId)
            }
            settingsPrefs.edit { remove(PREFS_IGNORED) }
        } else if (!prefsForPresets.contains("last_logged_in_user")) {
            prefsForPresets.edit { putString("last_logged_in_user", userId) }
        }

        accountDefaultSms = prefsForPresets.getString("local_account_default_sms", null)

        db.collection("user_settings").document(userId).addSnapshotListener { snapshot, e ->
            if (e != null) return@addSnapshotListener
            if (snapshot != null && snapshot.exists()) {
                accountDefaultSms = snapshot.getString("default_intro_sms")
                
                getSharedPreferences(PREFS_INTRO, Context.MODE_PRIVATE).edit {
                    putString("local_account_default_sms", accountDefaultSms)
                }
                
                defaultGoal = snapshot.getLong("total_goal")?.toInt() ?: 10
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
                    settingsPrefs.edit { putStringSet(PREFS_IGNORED, cloudIgnored.toSet()) }
                }

                val dateStr = dateKeyFormat.format(Date())
                if (!statsPrefs.contains("${dateStr}_followup_goal")) {
                    currentFollowUpGoal = defaultFollowUpGoal
                    refreshProgressUI()
                }
            }
        }
    }

    private fun startStatsListener() {
        val userId = auth.currentUser?.uid ?: return
        val dateStr = dateKeyFormat.format(Date())
        statsListener?.remove()
        statsListener = db.collection("user_settings").document(userId)
            .collection("daily_stats").document(dateStr).addSnapshotListener { doc, _ ->
                if (doc != null && doc.exists()) {
                    val fsFollowUpGoal = doc.getLong("followup_goal")?.toInt() ?: defaultFollowUpGoal
                    val fsFollowUpCount = doc.getLong("followup_count")?.toInt() ?: 0

                    currentFollowUpGoal = maxOf(currentFollowUpGoal, fsFollowUpGoal)
                    currentFollowUpCount = maxOf(currentFollowUpCount, fsFollowUpCount)
                    
                    saveLocalStat("followup_goal", currentFollowUpGoal)
                    saveLocalStat("followup_count", currentFollowUpCount)
                }
                refreshProgressUI()
            }
    }

    private fun refreshProgressUI() {
        progressFollowUps.max = if (currentFollowUpGoal > 0) currentFollowUpGoal else 1
        progressFollowUps.progress = currentFollowUpCount
        tvFollowUpCountDisplay.text = currentFollowUpCount.toString()
        tvFollowUpGoalDisplay.text = "Goal: $currentFollowUpGoal"

        if (currentFollowUpCount >= currentFollowUpGoal && currentFollowUpGoal > 0 && !hasPulsedFollowUpGoal) {
            hasPulsedFollowUpGoal = true
            pulseView(progressFollowUps)
        } else if (currentFollowUpCount < currentFollowUpGoal) {
            hasPulsedFollowUpGoal = false
        }
    }

    private fun pulseView(view: View) {
        val scaleX = android.animation.ObjectAnimator.ofFloat(view, "scaleX", 1f, 1.2f, 1f)
        val scaleY = android.animation.ObjectAnimator.ofFloat(view, "scaleY", 1f, 1.2f, 1f)
        scaleX.duration = 300
        scaleY.duration = 300
        scaleX.repeatCount = 1
        scaleY.repeatCount = 1
        val animatorSet = android.animation.AnimatorSet()
        animatorSet.playTogether(scaleX, scaleY)
        animatorSet.start()

        explodeConfetti(view)
    }

    private fun explodeConfetti(anchor: View) {
        val root = findViewById<ViewGroup>(android.R.id.content)
        val anchorLocation = IntArray(2)
        val rootLocation = IntArray(2)
        anchor.getLocationInWindow(anchorLocation)
        root.getLocationInWindow(rootLocation)

        val startX = (anchorLocation[0] - rootLocation[0]) + anchor.width / 2f
        val startY = (anchorLocation[1] - rootLocation[1]) + anchor.height / 2f

        val random = java.util.Random()
        val colors = intArrayOf(
            Color.parseColor("#FF5252"), Color.parseColor("#FF4081"), Color.parseColor("#E040FB"),
            Color.parseColor("#7C4DFF"), Color.parseColor("#536DFE"), Color.parseColor("#448AFF"),
            Color.parseColor("#40C4FF"), Color.parseColor("#18FFFF"), Color.parseColor("#64FFDA"),
            Color.parseColor("#69F0AE"), Color.parseColor("#B2FF59"), Color.parseColor("#EEFF41"),
            Color.parseColor("#FFFF00"), Color.parseColor("#FFD740"), Color.parseColor("#FFAB40")
        )

        for (i in 0..40) {
            val particle = View(this)
            val size = random.nextInt(20) + 15
            particle.layoutParams = ViewGroup.LayoutParams(size, size)

            val shape = GradientDrawable()
            shape.shape = if (random.nextBoolean()) GradientDrawable.OVAL else GradientDrawable.RECTANGLE
            shape.setColor(colors[random.nextInt(colors.size)])
            particle.background = shape

            particle.x = startX - size / 2f
            particle.y = startY - size / 2f
            particle.elevation = 10f
            root.addView(particle)

            val angle = random.nextDouble() * 2 * Math.PI
            val distance = random.nextInt(400) + 150
            val endX = startX + (Math.cos(angle) * distance).toFloat()
            val endY = startY + (Math.sin(angle) * distance).toFloat() + 200f // Dropdown gravity effect

            particle.animate()
                .x(endX)
                .y(endY)
                .rotation(random.nextInt(720).toFloat())
                .alpha(0f)
                .setDuration((random.nextInt(500) + 600).toLong())
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .withEndAction { root.removeView(particle) }
                .start()
        }
    }

    private fun showGoalDialog(prefKey: String, title: String, currentVal: Int) {
        val input = EditText(this)
        input.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        input.setText(currentVal.toString())

        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage("Set your follow-up target for today:")
            .setView(input)
            .setPositiveButton("Set") { _, _ ->
                val goal = input.text.toString().toIntOrNull() ?: currentVal
                currentFollowUpGoal = goal
                saveLocalStat("followup_goal", goal)
                refreshProgressUI()
                saveDailyGoalToFirestore(prefKey, goal)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun saveDailyGoalToFirestore(key: String, value: Int) {
        val userId = auth.currentUser?.uid ?: return
        val dateStr = dateKeyFormat.format(Date())
        db.collection("user_settings").document(userId)
            .collection("daily_stats").document(dateStr)
            .set(hashMapOf(key to value), SetOptions.merge())
    }

    private fun incrementDailyStatLocal(field: String) {
        if (field == "followup_count") {
            currentFollowUpCount++
            saveLocalStat("followup_count", currentFollowUpCount)
            refreshProgressUI()
        }
    }

    private fun incrementDailyStatFirestore(field: String) {
        val userId = targetUserId ?: auth.currentUser?.uid ?: return
        val dateStr = dateKeyFormat.format(Date())
        db.collection("user_settings").document(userId)
            .collection("daily_stats").document(dateStr)
            .set(hashMapOf(field to FieldValue.increment(1)), SetOptions.merge())
    }

    private fun enterSelectionMode() {
        isSelectionMode = true
        selectedDocIds.clear()
        adapter.setSelectionMode(true)
        findViewById<LinearLayout>(R.id.bulkActionBar).visibility = View.VISIBLE
        updateSelectionTitle()
    }

    private fun exitSelectionMode() {
        isSelectionMode = false
        selectedDocIds.clear()
        adapter.setSelectionMode(false)
        findViewById<LinearLayout>(R.id.bulkActionBar).visibility = View.GONE
        if (targetUserId != null) {
            findViewById<TextView>(R.id.tvFollowUpHeader).text = "Viewing Team Member Leads"
        } else {
            findViewById<TextView>(R.id.tvFollowUpHeader).text = getString(R.string.follow_up_header)
        }
    }

    private fun updateSelectionTitle() {
        findViewById<TextView>(R.id.tvFollowUpHeader).text = "${selectedDocIds.size} Selected"
    }

    private fun performBulkDelete() {
        val batch = db.batch()
        selectedDocIds.forEach { docId ->
            batch.delete(db.collection("leads").document(docId))
        }
        batch.commit().addOnSuccessListener {
            Toast.makeText(this, "Deleted successfully", Toast.LENGTH_SHORT).show()
            exitSelectionMode()
            loadLeadsFromCloud()
        }.addOnFailureListener {
            Toast.makeText(this, "Error deleting leads", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showFilterPopupMenu(view: View) {
        val popup = PopupMenu(this, view)
        popup.menu.add("All Categories")
        popup.menu.add("💡 Side-Kick Tips")
        popup.menu.add("⏰ Overdue")
        popup.menu.add(getString(R.string.category_recruit))
        popup.menu.add(getString(R.string.category_prospect))
        popup.menu.add(getString(R.string.category_client))

        popup.setOnMenuItemClickListener { item ->
            currentCategoryFilter = if (item.title == "All Categories") null else item.title.toString()
            applyFilters()
            true
        }
        popup.show()
    }

    private fun applyFilters() {
        displayLeadsData.clear()
        val currentTime = System.currentTimeMillis()
        for (lead in allLeadsData) {
            val name = (lead["name"] as? String ?: "").lowercase()
            val category = (lead["category"] as? String ?: "").lowercase()
            val sideKickTip = lead["sideKickSuggestion"] as? String ?: ""
            
            val matchesSearch = name.contains(currentSearchQuery.lowercase())

            val matchesCategory = if (currentCategoryFilter == "💡 Side-Kick Tips") {
                sideKickTip.isNotEmpty()
            } else if (currentCategoryFilter == "⏰ Overdue") {
                val followUpTs = lead["followUpDate"] as? Timestamp
                followUpTs != null && followUpTs.toDate().time < currentTime
            } else {
                currentCategoryFilter == null || category.contains(currentCategoryFilter!!.lowercase())
            }

            if (matchesSearch && matchesCategory) {
                displayLeadsData.add(lead)
            }
        }
        adapter.notifyDataSetChanged()
    }

    private fun showSettingsDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_settings, null)
        
        val switchSideKick = view.findViewById<MaterialSwitch>(R.id.switchSideKick)
        val btnForceRunSideKick = view.findViewById<Button>(R.id.btnForceRunSideKick)
        val progressSideKick = view.findViewById<ProgressBar>(R.id.progressSideKick)
        val switchFollowSystem = view.findViewById<MaterialSwitch>(R.id.switchFollowSystemTheme)
        val switchDarkMode = view.findViewById<MaterialSwitch>(R.id.switchDarkMode)
        
        val etNewPreset = view.findViewById<EditText>(R.id.etNewPreset)
        val btnInsertName = view.findViewById<Button>(R.id.btnInsertNameTagDialog)
        val btnDefaultFollowUp = view.findViewById<Button>(R.id.btnDefaultFollowUp)
        val btnSetAccountDefault = view.findViewById<Button>(R.id.btnSetAccountDefault)
        val btnAddPreset = view.findViewById<Button>(R.id.btnAddPreset)
        val listView = view.findViewById<ListView>(R.id.listViewPresets)
        
        val btnCleanDuplicates = view.findViewById<Button>(R.id.btnCleanDuplicates)
        val btnBulkDeleteContacts = view.findViewById<Button>(R.id.btnBulkDeleteContacts)
        val btnDeleteAllContacts = view.findViewById<Button>(R.id.btnDeleteAllContacts)
        val btnExportContacts = view.findViewById<Button>(R.id.btnExportContacts)
        val btnBackupData = view.findViewById<Button>(R.id.btnBackupData)
        val btnRestoreData = view.findViewById<Button>(R.id.btnRestoreData)
        val containerIgnoredContacts = view.findViewById<LinearLayout>(R.id.containerIgnoredContacts)
        
        switchFollowSystem.isChecked = settingsPrefs.getBoolean("follow_system_theme", true)
        switchDarkMode.isChecked = settingsPrefs.getBoolean("dark_mode", false)
        switchDarkMode.isEnabled = !switchFollowSystem.isChecked
        switchFollowSystem.setOnCheckedChangeListener { _, isChecked ->
            switchDarkMode.isEnabled = !isChecked
        }

        btnForceRunSideKick?.setOnClickListener {
            btnForceRunSideKick.isEnabled = false
            progressSideKick?.visibility = View.VISIBLE
            val request = OneTimeWorkRequestBuilder<SideKickWorker>().build()
            WorkManager.getInstance(this).enqueue(request)
            
            WorkManager.getInstance(this).getWorkInfoByIdLiveData(request.id).observe(this) { workInfo ->
                if (workInfo != null && workInfo.state.isFinished) {
                    btnForceRunSideKick.isEnabled = true
                    progressSideKick?.visibility = View.GONE
                    Toast.makeText(this, "Side-Kick check complete!", Toast.LENGTH_SHORT).show()
                    loadLeadsFromCloud()
                }
            }
        }

        switchSideKick.isChecked = settingsPrefs.getBoolean(KEY_SIDE_KICK, false)

        // --- Ignored Contacts Logic ---
        val ignoredContacts = (settingsPrefs.getStringSet(PREFS_IGNORED, emptySet()) ?: emptySet()).toMutableList()
        
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
                            settingsPrefs.edit { putStringSet(PREFS_IGNORED, ignoredContacts.toSet()) }
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
                saveAccountDefaultSms(text)
            } else {
                Toast.makeText(this, "Enter text first", Toast.LENGTH_SHORT).show()
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
                .setTitle("Preset Actions")
                .setItems(arrayOf("Set as Account Default", getString(R.string.menu_delete_preset))) { _, which ->
                    if (which == 0) {
                        saveAccountDefaultSms(presets[position])
                    } else {
                        presets.removeAt(position)
                        prefsPresets.edit { putStringSet(PREFS_KEY, presets.toSet()) }
                        syncPresetsToCloud(presets)
                        presetAdapter.notifyDataSetChanged()
                    }
                }
                .show()
            true
        }

        btnCleanDuplicates.setOnClickListener { findAndMergeDuplicates() }
        btnBulkDeleteContacts.setOnClickListener { enterSelectionMode() }
        
        btnDeleteAllContacts.setOnClickListener {
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
                
                val oldFollowSystem = settingsPrefs.getBoolean("follow_system_theme", true)
                val oldDarkMode = settingsPrefs.getBoolean("dark_mode", false)

                settingsPrefs.edit { 
                    putBoolean(KEY_SIDE_KICK, sideKickEnabled)
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
                            loadLeadsFromCloud()
                        }
                } else {
                    Toast.makeText(this, "Restore successful!", Toast.LENGTH_SHORT).show()
                    loadAccountSettings()
                    loadLeadsFromCloud()
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
        val userId = targetUserId ?: auth.currentUser?.uid ?: return
        db.collection("leads").whereEqualTo("ownerId", userId).get().addOnSuccessListener { snapshot ->
            if (snapshot.isEmpty) {
                Toast.makeText(this, "No contacts to export", Toast.LENGTH_SHORT).show()
                return@addOnSuccessListener
            }

            try {
                val csvContent = StringBuilder()
                csvContent.append("Name,Phone,Email,Address,Company,Job Title,Category,Notes,Follow-Up Date\n")
                
                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

                for (doc in snapshot.documents) {
                    val name = doc.getString("name")?.replace(",", " ") ?: ""
                    val phone = doc.getString("phone") ?: ""
                    val email = doc.getString("email")?.replace(",", " ") ?: ""
                    val address = doc.getString("address")?.replace(",", " ") ?: ""
                    val company = doc.getString("company")?.replace(",", " ") ?: ""
                    val title = doc.getString("jobTitle")?.replace(",", " ") ?: ""
                    val category = doc.getString("category")?.replace(",", " ") ?: ""
                    val notes = doc.getString("notes")?.replace(",", " ")?.replace("\n", " ") ?: ""
                    val followUpDate = doc.getTimestamp("followUpDate")?.toDate()?.let { sdf.format(it) } ?: ""
                    
                    csvContent.append("\"$name\",\"$phone\",\"$email\",\"$address\",\"$company\",\"$title\",\"$category\",\"$notes\",\"$followUpDate\"\n")
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
        val userId = targetUserId ?: auth.currentUser?.uid ?: return
        db.collection("leads").whereEqualTo("ownerId", userId).get().addOnSuccessListener { snapshot ->
            val batch = db.batch()
            for (doc in snapshot.documents) {
                batch.delete(doc.reference)
            }
            batch.commit().addOnSuccessListener {
                Toast.makeText(this, "All contacts deleted successfully", Toast.LENGTH_SHORT).show()
                loadLeadsFromCloud()
            }
        }
    }

    private fun scheduleSideKickWorker() {
        val workRequest = PeriodicWorkRequestBuilder<SideKickWorker>(4, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "SideKickWorker",
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )
    }

    private fun saveAccountDefaultSms(text: String) {
        val userId = auth.currentUser?.uid ?: return
        
        accountDefaultSms = text
        getSharedPreferences(PREFS_INTRO, Context.MODE_PRIVATE).edit {
            putString("local_account_default_sms", text)
        }
        
        db.collection("user_settings").document(userId)
            .set(mapOf("default_intro_sms" to text), SetOptions.merge())
            .addOnSuccessListener {
                Toast.makeText(this, "Account default updated", Toast.LENGTH_SHORT).show()
            }
    }

    private fun findAndMergeDuplicates() {
        val duplicates = mutableMapOf<String, MutableList<Map<String, Any>>>()
        for (lead in allLeadsData) {
            val name = (lead["name"] as? String ?: "").lowercase().trim()
            if (name.isNotEmpty()) {
                duplicates.getOrPut(name) { mutableListOf() }.add(lead)
            }
        }

        val groupsToMerge = duplicates.filter { it.value.size > 1 }
        if (groupsToMerge.isEmpty()) {
            Toast.makeText(this, "No duplicates found", Toast.LENGTH_SHORT).show()
            return
        }

        showMergeDuplicatesDialog(groupsToMerge.values.toList())
    }

    private fun showMergeDuplicatesDialog(groups: List<List<Map<String, Any>>>) {
        var currentGroupIndex = 0

        fun processNextGroup() {
            if (currentGroupIndex >= groups.size) {
                Toast.makeText(this, "All duplicates processed", Toast.LENGTH_SHORT).show()
                loadLeadsFromCloud()
                return
            }

            val group = groups[currentGroupIndex]
            val names = group.map { it["name"] as? String ?: "Unknown" }
            val phones = group.map { it["phone"] as? String ?: "No Phone" }
            
            val items = names.zip(phones).map { "${it.first} (${it.second})" }.toTypedArray()

            AlertDialog.Builder(this)
                .setTitle("Merge Duplicates (${currentGroupIndex + 1}/${groups.size})")
                .setItems(items) { _, which ->
                    val masterLead = group[which]
                    val otherLeads = group.filterIndexed { index, _ -> index != which }
                    mergeLeads(masterLead, otherLeads) {
                        currentGroupIndex++
                        processNextGroup()
                    }
                }
                .setNeutralButton("Skip") { _, _ ->
                    currentGroupIndex++
                    processNextGroup()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        processNextGroup()
    }

    private fun mergeLeads(master: Map<String, Any>, others: List<Map<String, Any>>, onComplete: () -> Unit) {
        val masterId = master["__docId"] as String
        val masterNotes = master["notes"] as? String ?: ""
        val otherNotes = others.joinToString("\n\n") { it["notes"] as? String ?: "" }
        val mergedNotes = if (otherNotes.isEmpty()) masterNotes else "$masterNotes\n\n--- Merged Notes ---\n\n$otherNotes"

        val batch = db.batch()
        batch.update(db.collection("leads").document(masterId), "notes", mergedNotes)
        others.forEach { lead ->
            val id = lead["__docId"] as String
            batch.delete(db.collection("leads").document(id))
        }

        batch.commit().addOnSuccessListener {
            onComplete()
        }.addOnFailureListener {
            Toast.makeText(this, "Failed to merge", Toast.LENGTH_SHORT).show()
            onComplete()
        }
    }

    private fun loadLeadsFromCloud() {
        val userId = targetUserId ?: auth.currentUser?.uid ?: return
        
        // Ensure users only load their own leads
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

                // Sort leads so the most recently added ones appear at the top
                allLeadsData.sortByDescending { lead ->
                    (lead["timestamp"] as? Timestamp)?.seconds ?: 0L
                }
                applyFilters()
                
                targetPhone?.let { phone ->
                    val targetIndex = displayLeadsData.indexOfFirst { it["phone"] == phone }
                    if (targetIndex != -1) {
                        val targetLead = displayLeadsData[targetIndex]
                        showLeadDetailsDialog(targetLead, targetLead["__docId"] as String)
                        targetPhone = null
                    }
                }
            }
    }

    private fun showLeadDetailsDialog(lead: Map<String, Any>, docId: String) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_lead_details, null)
        val dialog = AlertDialog.Builder(this).setView(dialogView).create()

        val editName = dialogView.findViewById<EditText>(R.id.editDetailName)
        val editPhone = dialogView.findViewById<EditText>(R.id.editDetailPhone)
        val editEmail = dialogView.findViewById<EditText>(R.id.editDetailEmail)
        val btnEmailDetail = dialogView.findViewById<ImageButton>(R.id.btnEmailDetail)
        val editAddress = dialogView.findViewById<EditText>(R.id.editDetailAddress)
        val btnMapDetail = dialogView.findViewById<ImageButton>(R.id.btnMapDetail)
        val editCompany = dialogView.findViewById<EditText>(R.id.editDetailCompany)
        val editJobTitle = dialogView.findViewById<EditText>(R.id.editDetailJobTitle)
        val btnSmsDetail = dialogView.findViewById<ImageButton>(R.id.btnSmsDetail)
        val btnCallDetail = dialogView.findViewById<ImageButton>(R.id.btnCallDetail)
        val cbRecruit = dialogView.findViewById<CheckBox>(R.id.cbRecruitDetail)
        val cbProspect = dialogView.findViewById<CheckBox>(R.id.cbProspectDetail)
        val cbClient = dialogView.findViewById<CheckBox>(R.id.cbClientDetail)
        val clientSubCategoryLayout = dialogView.findViewById<LinearLayout>(R.id.clientSubCategoryLayoutDetail)
        val cbInvestment = dialogView.findViewById<CheckBox>(R.id.cbInvestmentDetail)
        val cbLifeInsurance = dialogView.findViewById<CheckBox>(R.id.cbLifeInsuranceDetail)
        val notesContainer = dialogView.findViewById<LinearLayout>(R.id.notesContainer)

        val cbTmMarried = dialogView.findViewById<CheckBox>(R.id.cbTmMarried)
        val cbTmAge = dialogView.findViewById<CheckBox>(R.id.cbTmAge)
        val cbTmChildren = dialogView.findViewById<CheckBox>(R.id.cbTmChildren)
        val cbTmHomeowner = dialogView.findViewById<CheckBox>(R.id.cbTmHomeowner)
        val cbTmOccupation = dialogView.findViewById<CheckBox>(R.id.cbTmOccupation)
        val dialTargetMarket = dialogView.findViewById<com.google.android.material.progressindicator.CircularProgressIndicator>(R.id.dialTargetMarket)
        val tvTargetMarketScore = dialogView.findViewById<TextView>(R.id.tvTargetMarketScore)

        val btnAddDatedNote = dialogView.findViewById<Button>(R.id.btnAddDatedNote)
        val btnSetReminder = dialogView.findViewById<Button>(R.id.btnSetReminder)
        val btnAddCalendar = dialogView.findViewById<Button>(R.id.btnAddCalendar)
        val btnSaveChanges = dialogView.findViewById<Button>(R.id.btnSaveChanges)

        val tvBirthdayDisplay = dialogView.findViewById<TextView>(R.id.tvBirthdayDisplay)
        val btnSetBirthday = dialogView.findViewById<Button>(R.id.btnSetBirthday)

        val containerTargetMarketCheckboxes = dialogView.findViewById<LinearLayout>(R.id.containerTargetMarketCheckboxes)
        val tvToggleTargetMarket = dialogView.findViewById<TextView>(R.id.tvToggleTargetMarket)
        val headerTargetMarket = dialogView.findViewById<LinearLayout>(R.id.headerTargetMarket)

        val containerExtendedDetails = dialogView.findViewById<LinearLayout>(R.id.containerExtendedDetails)
        val tvToggleExtended = dialogView.findViewById<TextView>(R.id.tvToggleExtended)
        val headerExtendedDetails = dialogView.findViewById<LinearLayout>(R.id.headerExtendedDetails)
        
        val containerSmsIntro = dialogView.findViewById<LinearLayout>(R.id.containerSmsIntro)
        val tvToggleSms = dialogView.findViewById<TextView>(R.id.tvToggleSms)
        val headerSmsIntro = dialogView.findViewById<LinearLayout>(R.id.headerSmsIntro)
        
        val tvToggleNotes = dialogView.findViewById<TextView>(R.id.tvToggleNotes)
        var isNotesExpanded = false

        headerTargetMarket?.setOnClickListener {
            android.transition.TransitionManager.beginDelayedTransition(dialogView as ViewGroup)
            if (containerTargetMarketCheckboxes?.visibility == View.GONE) {
                containerTargetMarketCheckboxes.visibility = View.VISIBLE
                tvToggleTargetMarket?.text = "Hide ▲"
            } else {
                containerTargetMarketCheckboxes?.visibility = View.GONE
                tvToggleTargetMarket?.text = "Edit ▼"
            }
        }

        headerExtendedDetails?.setOnClickListener {
            android.transition.TransitionManager.beginDelayedTransition(dialogView as ViewGroup)
            if (containerExtendedDetails?.visibility == View.GONE) {
                containerExtendedDetails.visibility = View.VISIBLE
                tvToggleExtended?.text = "Hide ▲"
            } else {
                containerExtendedDetails?.visibility = View.GONE
                tvToggleExtended?.text = "Show ▼"
            }
        }
        
        headerSmsIntro?.setOnClickListener {
            android.transition.TransitionManager.beginDelayedTransition(dialogView as ViewGroup)
            if (containerSmsIntro?.visibility == View.GONE) {
                containerSmsIntro.visibility = View.VISIBLE
                tvToggleSms?.text = "Hide ▲"
            } else {
                containerSmsIntro?.visibility = View.GONE
                tvToggleSms?.text = "Show ▼"
            }
        }

        val etIntroText = dialogView.findViewById<EditText>(R.id.etIntroText)
        val btnDefaultIntro = dialogView.findViewById<Button>(R.id.btnDefaultIntro)
        val btnManagePresets = dialogView.findViewById<Button>(R.id.btnManagePresets)
        val btnSendIntroAction = dialogView.findViewById<Button>(R.id.btnSendIntroAction)

        val tvReminderValue = dialogView.findViewById<TextView>(R.id.tvReminderValue)
        val btnEditReminderIcon = dialogView.findViewById<ImageButton>(R.id.btnEditReminderIcon)
        val btnDeleteReminderIcon = dialogView.findViewById<ImageButton>(R.id.btnDeleteReminderIcon)

        editPhone.addTextChangedListener(android.telephony.PhoneNumberFormattingTextWatcher())

        editName.setText(lead["name"] as? String ?: "")
        editPhone.setText(lead["phone"] as? String ?: "")
        editEmail.setText(lead["email"] as? String ?: "")
        editAddress.setText(lead["address"] as? String ?: "")
        editCompany.setText(lead["company"] as? String ?: "")
        editJobTitle.setText(lead["jobTitle"] as? String ?: "")
        
        btnCallDetail.setOnClickListener {
            val phone = editPhone.text.toString().trim()
            if (phone.isNotEmpty()) {
                val intent = Intent(Intent.ACTION_DIAL, "tel:$phone".toUri())
                startActivity(intent)
            } else {
                Toast.makeText(this, "No phone number", Toast.LENGTH_SHORT).show()
            }
        }

        btnSmsDetail?.setOnClickListener {
            val phone = editPhone.text.toString().trim()
            if (phone.isNotEmpty()) {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("sms:$phone"))
                startActivity(intent)
            } else {
                Toast.makeText(this, "No phone number", Toast.LENGTH_SHORT).show()
            }
        }

        btnEmailDetail?.setOnClickListener {
            val email = editEmail.text.toString().trim()
            if (email.isNotEmpty()) {
                val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$email"))
                try {
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(this, "No email app found", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "No email address provided", Toast.LENGTH_SHORT).show()
            }
        }

        btnMapDetail?.setOnClickListener {
            val address = editAddress.text.toString().trim()
            if (address.isNotEmpty()) {
                val uri = Uri.parse("geo:0,0?q=${Uri.encode(address)}")
                val mapIntent = Intent(Intent.ACTION_VIEW, uri)
                mapIntent.setPackage("com.google.android.apps.maps")
                try {
                    startActivity(mapIntent)
                } catch (e: Exception) {
                    // Fallback to any map app or browser if Google Maps isn't installed
                    val fallbackIntent = Intent(Intent.ACTION_VIEW, uri)
                    try {
                        startActivity(fallbackIntent)
                    } catch (e2: Exception) {
                        Toast.makeText(this, "No map application found", Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                Toast.makeText(this, "No address provided", Toast.LENGTH_SHORT).show()
            }
        }

        val category = lead["category"] as? String ?: ""
        cbRecruit.isChecked = category.contains(getString(R.string.category_recruit), ignoreCase = true)
        cbProspect.isChecked = category.contains(getString(R.string.category_prospect), ignoreCase = true)
        cbClient.isChecked = category.contains(getString(R.string.category_client), ignoreCase = true)

        if (cbClient.isChecked) {
            clientSubCategoryLayout.visibility = View.VISIBLE
            cbInvestment.isChecked = category.contains("Investment", ignoreCase = true)
            cbLifeInsurance.isChecked = category.contains("Life Insurance", ignoreCase = true)
        }

        cbClient.setOnCheckedChangeListener { _, isChecked ->
            clientSubCategoryLayout.visibility = if (isChecked) View.VISIBLE else View.GONE
            if (!isChecked) {
                cbInvestment.isChecked = false
                cbLifeInsurance.isChecked = false
            }
        }

        val targetMarket = lead["targetMarket"] as? String ?: ""
        cbTmMarried.isChecked = targetMarket.contains("Married", ignoreCase = true)
        cbTmAge.isChecked = targetMarket.contains("Age 25-55", ignoreCase = true)
        cbTmChildren.isChecked = targetMarket.contains("Children", ignoreCase = true)
        cbTmHomeowner.isChecked = targetMarket.contains("Homeowner", ignoreCase = true)
        cbTmOccupation.isChecked = targetMarket.contains("Occupation", ignoreCase = true)

        fun updateTargetMarketDial() {
            var points = 0
            if (cbTmMarried.isChecked) points++
            if (cbTmAge.isChecked) points++
            if (cbTmChildren.isChecked) points++
            if (cbTmHomeowner.isChecked) points++
            if (cbTmOccupation.isChecked) points++

            val percentage = when (points) {
                1 -> 2
                2 -> 9
                3 -> 19
                4 -> 42
                5 -> 95
                else -> 0
            }

            dialTargetMarket.progress = percentage
            tvTargetMarketScore.text = "$percentage%"
            
            val color = when {
                percentage >= 80 -> Color.parseColor("#4CAF50")
                percentage >= 40 -> Color.parseColor("#FF9800")
                else -> Color.parseColor("#F44336")
            }
            dialTargetMarket.setIndicatorColor(color)
            tvTargetMarketScore.setTextColor(color)
        }

        cbTmMarried.setOnCheckedChangeListener { _, _ -> updateTargetMarketDial() }
        cbTmAge.setOnCheckedChangeListener { _, _ -> updateTargetMarketDial() }
        cbTmChildren.setOnCheckedChangeListener { _, _ -> updateTargetMarketDial() }
        cbTmHomeowner.setOnCheckedChangeListener { _, _ -> updateTargetMarketDial() }
        cbTmOccupation.setOnCheckedChangeListener { _, _ -> updateTargetMarketDial() }
        
        updateTargetMarketDial()

        var currentBirthday: String? = lead["birthday"] as? String

        fun updateBirthdayUI() {
            if (currentBirthday != null) {
                try {
                    val displayFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
                    val parsedDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(currentBirthday!!)
                    tvBirthdayDisplay?.text = "Birthday: ${parsedDate?.let { displayFormat.format(it) } ?: currentBirthday}"
                } catch (e: Exception) {
                    tvBirthdayDisplay?.text = "Birthday: $currentBirthday"
                }
                btnSetBirthday?.text = "📅 Edit Birthday"
            } else {
                tvBirthdayDisplay?.text = "Birthday: None"
                btnSetBirthday?.text = "📅 Add Birthday"
            }
        }
        updateBirthdayUI()

        btnSetBirthday?.setOnClickListener {
            val cal = Calendar.getInstance()
            if (currentBirthday != null) {
                val parts = currentBirthday!!.split("-")
                if (parts.size >= 3) {
                    cal.set(Calendar.YEAR, parts[0].toIntOrNull() ?: cal.get(Calendar.YEAR))
                    cal.set(Calendar.MONTH, (parts[1].toIntOrNull() ?: 1) - 1)
                    cal.set(Calendar.DAY_OF_MONTH, parts[2].toIntOrNull() ?: 1)
                }
            }
            
            val isDark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
            val theme = if (isDark) android.R.style.Theme_DeviceDefault_Dialog_Alert else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert
            
            DatePickerDialog(this, theme, { _, year, month, day ->
                val monthStr = (month + 1).toString().padStart(2, '0')
                val dayStr = day.toString().padStart(2, '0')
                currentBirthday = "$year-$monthStr-$dayStr"
                updateBirthdayUI()
                
                // Instantly save to cloud so it is never accidentally discarded!
                db.collection("leads").document(docId).update("birthday", currentBirthday)
            }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
        }

        fun applyDefaultIntro() {
            val currentName = editName.text.toString()
            val firstName = currentName.split(" ").firstOrNull() ?: currentName
            val baseMsg = accountDefaultSms?.takeIf { it.isNotBlank() } ?: "Hi $NAME_TOKEN, just following up to see if you had any questions! Looking forward to connecting again."
            etIntroText.setText(baseMsg.replace(NAME_TOKEN, firstName))
        }
        applyDefaultIntro()

        btnDefaultIntro.setOnClickListener { applyDefaultIntro() }
        btnManagePresets.setOnClickListener { showPresetManagerForDialog(etIntroText, editName.text.toString()) }
        
        btnSendIntroAction.setOnClickListener {
            val phone = editPhone.text.toString()
            if (phone.isNotEmpty()) {
                val message = etIntroText.text.toString()
                val smsIntent = Intent(Intent.ACTION_VIEW, "sms:$phone".toUri())
                smsIntent.putExtra("sms_body", message)
                startActivity(smsIntent)
            } else {
                Toast.makeText(this, "No phone number", Toast.LENGTH_SHORT).show()
            }
        }

        val rawNotes = lead["notes"] as? String ?: ""
        val noteList = parseNotes(rawNotes)
        
        fun refreshNotesUI() {
            notesContainer.removeAllViews()
            
            if (noteList.size > 1) {
                tvToggleNotes?.visibility = View.VISIBLE
                tvToggleNotes?.text = if (isNotesExpanded) "Hide History ▲" else "View All (${noteList.size}) ▼"
            } else {
                tvToggleNotes?.visibility = View.GONE
            }
            
            val notesToShow = if (isNotesExpanded || noteList.size <= 1) noteList else noteList.take(1)
            
            for (note in notesToShow) {
                val noteView = LayoutInflater.from(this).inflate(R.layout.item_note, notesContainer, false)
                val tvDate = noteView.findViewById<TextView>(R.id.tvNoteDate)
                val etContent = noteView.findViewById<EditText>(R.id.etNoteContent)
                val btnDelete = noteView.findViewById<ImageButton>(R.id.btnDeleteNote)
                val cbNoteCalled = noteView.findViewById<CheckBox>(R.id.cbNoteCalled)
                val cbNoteTexted = noteView.findViewById<CheckBox>(R.id.cbNoteTexted)
                
                tvDate.text = note.date
                etContent.setText(note.content)
                cbNoteCalled.isChecked = note.called
                cbNoteTexted.isChecked = note.texted

                cbNoteCalled.setOnCheckedChangeListener { _, isChecked -> note.called = isChecked }
                cbNoteTexted.setOnCheckedChangeListener { _, isChecked -> note.texted = isChecked }
                
                etContent.addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        note.content = s.toString()
                    }
                    override fun afterTextChanged(s: Editable?) {}
                })

                btnDelete.setOnClickListener {
                    AlertDialog.Builder(this)
                        .setTitle("Delete Note?")
                        .setMessage("Are you sure you want to remove this specific note?")
                        .setPositiveButton("Delete") { _, _ ->
                            noteList.remove(note)
                            refreshNotesUI()
                        }
                        .setNegativeButton("Cancel", null)
                    .show()
                }
                
                notesContainer.addView(noteView)
            }
        }
        refreshNotesUI()

        tvToggleNotes?.setOnClickListener {
            android.transition.TransitionManager.beginDelayedTransition(dialogView as ViewGroup)
            isNotesExpanded = !isNotesExpanded
            refreshNotesUI()
        }

        var noteAddedThisSession = false
        btnAddDatedNote.setOnClickListener {
            val timestamp = SimpleDateFormat("MMM dd, yyyy hh:mm a", Locale.getDefault()).format(Date())
            noteList.add(0, NoteItem(timestamp, ""))
            noteAddedThisSession = true
            isNotesExpanded = true
            refreshNotesUI()
        }

        btnAddCalendar.setOnClickListener {
            val intent = Intent(Intent.ACTION_INSERT).apply {
                data = CalendarContract.Events.CONTENT_URI
                putExtra(CalendarContract.Events.TITLE, getString(R.string.calendar_call_title, editName.text.toString()))
                val startTime = (lead["followUpDate"] as? Timestamp)?.toDate()?.time ?: System.currentTimeMillis()
                putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startTime)
                putExtra(CalendarContract.EXTRA_EVENT_END_TIME, startTime + 3600000)
            }
            startActivity(intent)
        }

        var newFollowUpDate: Date? = (lead["followUpDate"] as? Timestamp)?.toDate()

        fun updateReminderUI() {
            if (newFollowUpDate != null && newFollowUpDate!!.after(Date())) {
                val format = SimpleDateFormat("MMM dd, yyyy hh:mm a", Locale.getDefault())
                tvReminderValue.text = format.format(newFollowUpDate!!)
                btnEditReminderIcon.visibility = View.VISIBLE
                btnDeleteReminderIcon.visibility = View.VISIBLE
                btnSetReminder.text = getString(R.string.btn_change_reminder)
            } else {
                tvReminderValue.text = getString(R.string.reminder_none)
                btnEditReminderIcon.visibility = View.GONE
                btnDeleteReminderIcon.visibility = View.GONE
                btnSetReminder.text = getString(R.string.btn_set_reminder)
            }
        }
        updateReminderUI()

        val onSetReminderClicked = View.OnClickListener {
            val cal = Calendar.getInstance()
            newFollowUpDate?.let { if (it.after(Date())) cal.time = it }
            
            val isDark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
            val theme = if (isDark) android.R.style.Theme_DeviceDefault_Dialog_Alert else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert
            
            DatePickerDialog(this, theme, { _, year, month, day ->
                cal.set(year, month, day)
                TimePickerDialog(this, theme, { _, hour, minute ->
                    cal.set(Calendar.HOUR_OF_DAY, hour)
                    cal.set(Calendar.MINUTE, minute)
                    newFollowUpDate = cal.time
                    updateReminderUI()
                    scheduleFollowUpNotification(newFollowUpDate!!, editName.text.toString(), editPhone.text.toString())
                }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), false).show()
            }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
        }

        btnSetReminder.setOnClickListener(onSetReminderClicked)
        btnEditReminderIcon.setOnClickListener(onSetReminderClicked)

        btnDeleteReminderIcon.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.dialog_delete_reminder_title))
                .setMessage(getString(R.string.dialog_delete_reminder_msg))
                .setPositiveButton("Delete") { _, _ ->
                    newFollowUpDate = null
                    updateReminderUI()
                    Toast.makeText(this, getString(R.string.msg_reminder_removed), Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        btnSaveChanges.setOnClickListener {
            val cats = mutableListOf<String>()
            if (cbRecruit.isChecked) cats.add(getString(R.string.category_recruit))
            if (cbProspect.isChecked) cats.add(getString(R.string.category_prospect))
            if (cbClient.isChecked) {
                cats.add(getString(R.string.category_client))
                if (cbInvestment.isChecked) cats.add("Investment")
                if (cbLifeInsurance.isChecked) cats.add("Life Insurance")
            }
            
            val tmList = mutableListOf<String>()
            if (cbTmMarried.isChecked) tmList.add("Married")
            if (cbTmAge.isChecked) tmList.add("Age 25-55")
            if (cbTmChildren.isChecked) tmList.add("Children")
            if (cbTmHomeowner.isChecked) tmList.add("Homeowner")
            if (cbTmOccupation.isChecked) tmList.add("Occupation")

            val finalFollowUpDate = newFollowUpDate ?: run {
                val cal = Calendar.getInstance()
                cal.add(Calendar.DAY_OF_YEAR, 1)
                cal.time
            }

            val rawAddress = editAddress.text.toString().trim()
            val formattedAddress = rawAddress.split("\\s+".toRegex()).joinToString(" ") { word ->
                val cleanWord = word.replace(Regex("[^A-Za-z]"), "")
                if (cleanWord.length == 2) word.uppercase() else word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
            }

            val rawName = editName.text.toString().trim()
            val formattedName = rawName.split("\\s+".toRegex()).joinToString(" ") { word ->
                if (word.isNotEmpty()) word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() } else ""
            }

            val updatedData = mutableMapOf<String, Any>(
                "name" to formattedName,
                "phone" to editPhone.text.toString(),
                "email" to editEmail.text.toString().trim(),
                "address" to formattedAddress,
                "company" to editCompany.text.toString().trim(),
                "jobTitle" to editJobTitle.text.toString().trim(),
                "category" to cats.joinToString(", "),
                "targetMarket" to tmList.joinToString(", "),
                "notes" to serializeNotes(noteList),
                "followUpDate" to Timestamp(finalFollowUpDate),
                "sideKickSuggestion" to FieldValue.delete() // Clears the tip once the user takes action
            )

            if (currentBirthday != null) {
                updatedData["birthday"] = currentBirthday!!
            } else {
                updatedData["birthday"] = FieldValue.delete()
            }

            db.collection("leads").document(docId).update(updatedData).addOnSuccessListener {
                if (noteAddedThisSession) {
                    incrementDailyStatLocal("followup_count")
                    incrementDailyStatFirestore("followup_count")
                }
                scheduleFollowUpNotification(finalFollowUpDate, editName.text.toString(), editPhone.text.toString())
                Toast.makeText(this, "Lead Updated", Toast.LENGTH_SHORT).show()
                refreshProgressUI()
                loadLeadsFromCloud()
                dialog.dismiss()
            }
        }

        dialog.show()
    }

    private fun scheduleFollowUpNotification(date: Date, name: String, phone: String) {
        // Prevent alarms from firing immediately for past dates
        if (date.before(Date())) return

        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, ReminderReceiver::class.java).apply {
            putExtra("LEAD_NAME", name)
            putExtra("LEAD_PHONE", phone)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            this,
            phone.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, date.time, pendingIntent)
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, date.time, pendingIntent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scheduling alarm", e)
            alarmManager.set(AlarmManager.RTC_WAKEUP, date.time, pendingIntent)
        }
    }

    private fun showPresetManagerForDialog(etTarget: EditText, name: String) {
        val presets = getPresetsLocally().toList()
        if (presets.isEmpty()) {
            Toast.makeText(this, "No presets saved", Toast.LENGTH_SHORT).show()
            return
        }
        val firstName = name.split(" ").firstOrNull() ?: name
        AlertDialog.Builder(this)
            .setTitle("Select Preset")
            .setItems(presets.toTypedArray()) { _, which ->
                etTarget.setText(presets[which].replace(NAME_TOKEN, firstName))
            }.show()
    }

    private data class NoteItem(val date: String, var content: String, var called: Boolean = false, var texted: Boolean = false)

    private fun parseNotes(raw: String): MutableList<NoteItem> {
        val list = mutableListOf<NoteItem>()
        val blocks = raw.split("\n\n")
        for (block in blocks) {
            var trimmed = block.trim()
            if (trimmed.isEmpty()) continue
            
            while (trimmed.startsWith("[Legacy Note]:", ignoreCase = true)) {
                trimmed = trimmed.substringAfter(":", "").trim()
            }
            
            var called = false
            var texted = false
            val metaRegex = Regex("\\[C:(true|false)\\|T:(true|false)\\]")
            val metaMatch = metaRegex.find(trimmed)
            if (metaMatch != null) {
                called = metaMatch.groupValues[1] == "true"
                texted = metaMatch.groupValues[2] == "true"
                trimmed = trimmed.replace(metaMatch.value, "").trim()
            }

            val dateRegex = Regex("^\\[?([A-Z][a-z]{2} \\d{2}, (?:\\d{4} )?\\d{2}:\\d{2}(?: [AaPp][Mm])?)\\]?:?\\s*(.*)", RegexOption.DOT_MATCHES_ALL)
            val dateMatch = dateRegex.find(trimmed)
            
            if (dateMatch != null) {
                list.add(NoteItem(dateMatch.groupValues[1], dateMatch.groupValues[2].trim(), called, texted))
            } else if (trimmed.isNotEmpty()) {
                list.add(NoteItem("Legacy Note", trimmed, called, texted))
            }
        }
        return list
    }

    private fun serializeNotes(list: List<NoteItem>): String {
        return list.joinToString("\n\n") { "[${it.date}] [C:${it.called}|T:${it.texted}]: ${it.content}" }
    }

    private fun saveAllPresetsLocally(list: List<String>) {
        getSharedPreferences(PREFS_INTRO, Context.MODE_PRIVATE).edit { putStringSet(PREFS_KEY, list.toSet()) }
        syncPresetsToCloud(list)
    }

    private fun syncPresetsToCloud(list: List<String>) {
        val userId = auth.currentUser?.uid ?: return
        db.collection("user_settings").document(userId)
            .set(mapOf("sms_presets" to list), SetOptions.merge())
    }

    private fun getPresetsLocally(): Set<String> {
        return getSharedPreferences(PREFS_INTRO, Context.MODE_PRIVATE).getStringSet(PREFS_KEY, emptySet()) ?: emptySet()
    }

    override fun onDestroy() {
        super.onDestroy()
        statsListener?.remove()
    }
}

class FollowUpAdapter(
    context: Context,
    private val leads: List<Map<String, Any>>,
    private val onSelectionChanged: (String, Boolean) -> Unit
) : ArrayAdapter<Map<String, Any>>(context, 0, leads) {

    private var selectionMode = false

    fun setSelectionMode(enabled: Boolean) {
        selectionMode = enabled
        notifyDataSetChanged()
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_lead_card, parent, false)
        val tvName = view.findViewById<TextView>(R.id.tvLeadName)
        val tvDetails = view.findViewById<TextView>(R.id.tvLeadDetails)
        val tvCategoryLabel = view.findViewById<TextView>(R.id.tvCategoryLabel)
        val tvNewBadge = view.findViewById<TextView>(R.id.tvNewBadge)
        val btnCallIcon = view.findViewById<ImageView>(R.id.btnCallIcon)
        val cbSelectLead = view.findViewById<CheckBox>(R.id.cbSelectLead)
        val llCompleteAction = view.findViewById<LinearLayout>(R.id.llCompleteAction)

        val lead = leads[position]
        val docId = lead["__docId"] as? String ?: ""
        val name = lead["name"] as? String ?: context.getString(R.string.unknown_contact)
        val category = lead["category"] as? String ?: ""
        val notes = lead["notes"] as? String ?: ""
        val sideKickSuggestion = lead["sideKickSuggestion"] as? String ?: ""

        val birthday = lead["birthday"] as? String
        var nameText = context.getString(R.string.lead_name_format, name)
        if (birthday != null) {
            val todayStr = SimpleDateFormat("-MM-dd", Locale.getDefault()).format(Date())
            if (birthday.endsWith(todayStr)) {
                nameText += " 🎂"
            }
        }
        tvName.text = nameText
        
        if (category.isNotEmpty()) {
            tvCategoryLabel.text = category
            tvCategoryLabel.visibility = View.VISIBLE
            
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

        val timestamp = lead["timestamp"] as? Timestamp
        val createdDate = timestamp?.toDate()?.let { SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(it) } ?: "Unknown"
        
        if (timestamp != null && tvNewBadge != null) {
            val now = System.currentTimeMillis()
            val createdTime = timestamp.toDate().time
            // 24 hours in milliseconds
            if ((now - createdTime) <= 24 * 60 * 60 * 1000L) {
                tvNewBadge.visibility = View.VISIBLE
                val badgeBg = GradientDrawable().apply {
                    setColor(Color.parseColor("#FF5252")) // Bright red/orange to catch attention
                    cornerRadius = 16f
                }
                tvNewBadge.background = badgeBg
            } else {
                tvNewBadge.visibility = View.GONE
            }
        } else {
            tvNewBadge?.visibility = View.GONE
        }

        val followUpTs = lead["followUpDate"] as? Timestamp
        val followUpDate = followUpTs?.toDate()?.let { SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(it) } ?: "None"

        val latestNote = notes.substringBefore("\n\n").replace(Regex("^\\[.*?\\]: "), "").trim()

        val detailsText = android.text.SpannableStringBuilder()
        if (sideKickSuggestion.isNotEmpty()) detailsText.append("💡 Tip: $sideKickSuggestion\n\n")
        
        val followUpStart = detailsText.length
        detailsText.append("⏰ Follow-up: $followUpDate\n")
        
        if (followUpTs != null && followUpTs.toDate().time < System.currentTimeMillis()) {
            detailsText.setSpan(
                android.text.style.ForegroundColorSpan(Color.parseColor("#FF5252")),
                followUpStart,
                detailsText.length,
                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        
        detailsText.append("📝 Note: ${latestNote.ifEmpty { "None" }}\n")
        detailsText.append("📅 Added: $createdDate")
        
        tvDetails.text = detailsText

        // Hide the complete action on this page entirely
        llCompleteAction?.visibility = View.GONE

        if (selectionMode) {
            cbSelectLead.visibility = View.VISIBLE
            btnCallIcon.visibility = View.GONE
            cbSelectLead.setOnCheckedChangeListener(null)
            cbSelectLead.isChecked = false
            cbSelectLead.setOnCheckedChangeListener { _, isChecked ->
                onSelectionChanged(docId, isChecked)
            }
        } else {
            cbSelectLead.visibility = View.GONE
            btnCallIcon.visibility = View.VISIBLE
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
