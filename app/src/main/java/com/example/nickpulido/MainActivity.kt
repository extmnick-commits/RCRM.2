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
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    private var hasPulsedTotalGoal = false
    private var hasPulsedFollowUpGoal = false

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

    private var cardImageUri: Uri? = null
    private var quickAddNameRef: EditText? = null
    private var quickAddPhoneRef: EditText? = null
    private var quickAddEmailRef: EditText? = null
    private var quickAddAddressRef: EditText? = null
    private var quickAddNotesRef: EditText? = null
    private var quickAddCompanyRef: EditText? = null
    private var quickAddTitleRef: EditText? = null
    
    private var quickAddCbRecruitRef: CheckBox? = null
    private var quickAddCbProspectRef: CheckBox? = null
    private var quickAddCbClientRef: CheckBox? = null
    
    private var quickAddCbTmMarriedRef: CheckBox? = null
    private var quickAddCbTmAgeRef: CheckBox? = null
    private var quickAddCbTmChildrenRef: CheckBox? = null
    private var quickAddCbTmHomeownerRef: CheckBox? = null
    private var quickAddCbTmOccupationRef: CheckBox? = null
    
    private var quickAddApptDate: Date? = null
    private var quickAddApptLocation: String? = null
    private var quickAddFollowUpDate: Date? = null
    
    private var currentPhotoPath: String? = null
    private var progressDialog: AlertDialog? = null

    private val scanCardLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) {
            cardImageUri?.let { uri ->
                performCrop(uri)
            }
        }
    }

    private var croppedImageUri: Uri? = null

    private val pickGalleryLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) performCrop(uri)
    }

    private val cropLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            croppedImageUri?.let { processBusinessCard(it) }
        }
    }

    private val speechRecognizerLauncherQuickAdd = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val data = result.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)
            val spokenText = data?.get(0) ?: ""
            if (spokenText.isNotEmpty()) {
                processVoiceLogQuickAddWithAI(spokenText)
            }
        }
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
                    val lead = hotListData.find { it["__docId"] == docId }
                    val phone = lead?.get("phone") as? String
                    if (!phone.isNullOrEmpty()) {
                        ReminderReceiver.cancelReminder(this, phone)
                    }
                    Toast.makeText(this, "Follow-up marked complete", Toast.LENGTH_SHORT).show()
                    loadHotList()
                }
        }
        listView.adapter = adapter

        val tabLayout = findViewById<TabLayout>(R.id.tabLayoutFollowUps)
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                currentTabPosition = tab.position
                (adapter as? HotListAdapter)?.currentTab = currentTabPosition
                filterHotList(findViewById<EditText>(R.id.search_bar).text.toString())
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        listView.setOnItemClickListener { _, _, position, _ ->
            val lead = hotListData[position]
            val intent = Intent(this, FollowUpActivity::class.java)
            intent.putExtra("targetLeadId", lead["id"] as? String)
            intent.putExtra("targetPhone", lead["phone"] as? String)
            startActivity(intent)
        }

        findViewById<ImageButton>(R.id.btnSettings).setOnClickListener { showSettingsDialog() }
        findViewById<Button>(R.id.btnQuickAdd).setOnClickListener { showQuickAddDialog() }
        findViewById<Button>(R.id.btnAppointments).setOnClickListener { 
            startActivity(Intent(this, AppointmentsActivity::class.java)) 
        }
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

        if (currentTotalCount >= currentTotalGoal && currentTotalGoal > 0 && !hasPulsedTotalGoal) {
            hasPulsedTotalGoal = true
            pulseView(progressTotalLeads)
        } else if (currentTotalCount < currentTotalGoal) {
            hasPulsedTotalGoal = false
        }

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
        
        val isDark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val theme = if (isDark) android.R.style.Theme_DeviceDefault_Dialog_Alert else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert
        
        DatePickerDialog(this, theme, { _, year, month, day ->
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
        val btnForceRunSideKick = view.findViewById<Button>(R.id.btnForceRunSideKick)
        val progressSideKick = view.findViewById<ProgressBar>(R.id.progressSideKick)
        
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
                    loadHotList()
                }
            }
        }

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
        val briefingHour = prefs.getInt("side_kick_briefing_hour", 8)
        val briefingMinute = prefs.getInt("side_kick_briefing_minute", 0)

        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, briefingHour)
            set(Calendar.MINUTE, briefingMinute)
            set(Calendar.SECOND, 0)
            if (before(Calendar.getInstance())) {
                add(Calendar.DATE, 1)
            }
        }
        val initialDelay = (calendar.timeInMillis - System.currentTimeMillis()).coerceAtLeast(0)

        val request = PeriodicWorkRequestBuilder<SideKickWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        // Use REPLACE to ensure the latest schedule is always used
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("SideKickWorker", ExistingPeriodicWorkPolicy.REPLACE, request)
    }

    private fun checkPermissions() {
        val perms = mutableListOf(Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_CONTACTS, Manifest.permission.RECORD_AUDIO)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        
        val missing = perms.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 101)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        cardImageUri?.let { outState.putParcelable("CARD_IMAGE_URI", it) }
        croppedImageUri?.let { outState.putParcelable("CROPPED_IMAGE_URI", it) }
        currentPhotoPath?.let { outState.putString("CURRENT_PHOTO_PATH", it) }
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        @Suppress("DEPRECATION")
        cardImageUri = savedInstanceState.getParcelable("CARD_IMAGE_URI")
        @Suppress("DEPRECATION")
        croppedImageUri = savedInstanceState.getParcelable("CROPPED_IMAGE_URI")
        currentPhotoPath = savedInstanceState.getString("CURRENT_PHOTO_PATH")
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

        quickAddApptDate = null
        quickAddApptLocation = null
        quickAddFollowUpDate = null

        val nameInput = dialogView.findViewById<EditText>(R.id.editQuickName)
        val phoneInput = dialogView.findViewById<EditText>(R.id.editQuickPhone)
        val emailInput = dialogView.findViewById<EditText>(R.id.editQuickEmail)
        val addressInput = dialogView.findViewById<EditText>(R.id.editQuickAddress)
        val companyInput = dialogView.findViewById<EditText>(R.id.editQuickCompany)
        val titleInput = dialogView.findViewById<EditText>(R.id.editQuickJobTitle)
        val btnScanCard = dialogView.findViewById<Button>(R.id.btnScanCard)
        
        phoneInput.addTextChangedListener(android.telephony.PhoneNumberFormattingTextWatcher())
        
        if (prefilledName.isNotEmpty()) nameInput.setText(prefilledName)
        if (prefilledPhone.isNotEmpty()) phoneInput.setText(prefilledPhone)

        val cbRecruit = dialogView.findViewById<CheckBox>(R.id.cbRecruitQuick)
        val cbProspect = dialogView.findViewById<CheckBox>(R.id.cbProspectQuick)
        val cbClient = dialogView.findViewById<CheckBox>(R.id.cbClientQuick)
        val notesInput = dialogView.findViewById<EditText>(R.id.editQuickNotes)
        if (prefilledNotes.isNotEmpty()) notesInput.setText(prefilledNotes)

        quickAddNameRef = nameInput
        quickAddPhoneRef = phoneInput
        quickAddEmailRef = emailInput
        quickAddAddressRef = addressInput
        quickAddNotesRef = notesInput
        quickAddCompanyRef = companyInput
        quickAddTitleRef = titleInput

        quickAddCbRecruitRef = cbRecruit
        quickAddCbProspectRef = cbProspect
        quickAddCbClientRef = cbClient

        val cbTmMarried = dialogView.findViewById<CheckBox>(R.id.cbTmMarriedQuick)
        val cbTmAge = dialogView.findViewById<CheckBox>(R.id.cbTmAgeQuick)
        val cbTmChildren = dialogView.findViewById<CheckBox>(R.id.cbTmChildrenQuick)
        val cbTmHomeowner = dialogView.findViewById<CheckBox>(R.id.cbTmHomeownerQuick)
        val cbTmOccupation = dialogView.findViewById<CheckBox>(R.id.cbTmOccupationQuick)
        val dialTargetMarket = dialogView.findViewById<com.google.android.material.progressindicator.CircularProgressIndicator>(R.id.dialTargetMarketQuick)
        val tvTargetMarketScore = dialogView.findViewById<TextView>(R.id.tvTargetMarketScoreQuick)

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
        
        // Store reference so voice-fill can trigger the dial redraw after programmatic checkbox changes
        quickAddCbTmMarriedRef = cbTmMarried
        quickAddCbTmAgeRef = cbTmAge
        quickAddCbTmChildrenRef = cbTmChildren
        quickAddCbTmHomeownerRef = cbTmHomeowner
        quickAddCbTmOccupationRef = cbTmOccupation
        
        updateTargetMarketDial()

        var currentQuickBirthday: String? = null
        val tvQuickBirthdayDisplay = dialogView.findViewById<TextView>(R.id.tvQuickBirthdayDisplay)
        val btnQuickSetBirthday = dialogView.findViewById<Button>(R.id.btnQuickSetBirthday)

        fun updateQuickBirthdayUI() {
            if (currentQuickBirthday != null) {
                try {
                    val displayFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
                    val parsedDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(currentQuickBirthday!!)
                    tvQuickBirthdayDisplay?.text = "Birthday: ${parsedDate?.let { displayFormat.format(it) } ?: currentQuickBirthday}"
                } catch (e: Exception) {
                    tvQuickBirthdayDisplay?.text = "Birthday: $currentQuickBirthday"
                }
                btnQuickSetBirthday?.text = "📅 Edit Birthday"
            } else {
                tvQuickBirthdayDisplay?.text = "Birthday: None"
                btnQuickSetBirthday?.text = "📅 Add Birthday"
            }
        }
        updateQuickBirthdayUI()

        btnQuickSetBirthday?.setOnClickListener {
            val cal = Calendar.getInstance()
            if (currentQuickBirthday != null) {
                val parts = currentQuickBirthday!!.split("-")
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
                currentQuickBirthday = "$year-$monthStr-$dayStr"
                updateQuickBirthdayUI()
            }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
        }

        btnScanCard?.setOnClickListener {
            val options = arrayOf("📷 Take Photo", "🖼️ Choose from Gallery")
            AlertDialog.Builder(this)
                .setTitle("Scan Business Card")
                .setItems(options) { _, which ->
                    if (which == 0) {
                        val photoFile = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "card_${System.currentTimeMillis()}.jpg")
                        currentPhotoPath = photoFile.absolutePath
                        val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", photoFile)
                        cardImageUri = uri
                        scanCardLauncher.launch(uri)
                    } else {
                        pickGalleryLauncher.launch("image/*")
                    }
                }
                .show()
        }
        
        val scanCardParent = btnScanCard?.parent as? LinearLayout
        if (scanCardParent != null) {
            scanCardParent.orientation = LinearLayout.HORIZONTAL
            btnScanCard.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 4 }
            
            val btnVoiceLog = Button(this).apply {
                text = "🎤 Dictate"
                backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#FF9800"))
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = 4 }
            }
            scanCardParent.addView(btnVoiceLog)
            
            btnVoiceLog.setOnClickListener {
                val intent = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                intent.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                intent.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                intent.putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Speak your lead details...")
                try {
                    speechRecognizerLauncherQuickAdd.launch(intent)
                } catch (e: Exception) {
                    Toast.makeText(this, "Speech recognition not available", Toast.LENGTH_SHORT).show()
                }
            }
        }

        val btnQuickAddImport = dialogView.findViewById<Button>(R.id.btnQuickAddImport)
        btnQuickAddImport?.setOnClickListener {
            pickContactLauncher.launch(null)
        }

        val btnSave = dialogView.findViewById<Button>(R.id.btnQuickSave)

        btnSave.setOnClickListener {
            val rawName = nameInput.text.toString().trim()
            if (rawName.isEmpty()) return@setOnClickListener
            val name = rawName.split("\\s+".toRegex()).joinToString(" ") { word ->
                if (word.isNotEmpty()) word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() } else ""
            }

            val cats = mutableListOf<String>()
            if (cbRecruit.isChecked) cats.add(getString(R.string.category_recruit))
            if (cbProspect.isChecked) cats.add(getString(R.string.category_prospect))
            if (cbClient.isChecked) cats.add(getString(R.string.category_client))

            val rawAddress = addressInput.text.toString().trim()
            val formattedAddress = rawAddress.split("\\s+".toRegex()).joinToString(" ") { word ->
                val cleanWord = word.replace(Regex("[^A-Za-z]"), "")
                if (cleanWord.length == 2) word.uppercase() else word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
            }
            
            val tmList = mutableListOf<String>()
            if (cbTmMarried.isChecked) tmList.add("Married")
            if (cbTmAge.isChecked) tmList.add("Age 25-55")
            if (cbTmChildren.isChecked) tmList.add("Children")
            if (cbTmHomeowner.isChecked) tmList.add("Homeowner")
            if (cbTmOccupation.isChecked) tmList.add("Occupation")

            val leadData = hashMapOf<String, Any?>(
                "name" to name,
                "phone" to phoneInput.text.toString(),
                "email" to emailInput.text.toString().trim(),
                "address" to formattedAddress,
                "company" to companyInput.text.toString().trim(),
                "jobTitle" to titleInput.text.toString().trim(),
                "category" to cats.joinToString(", "),
                "targetMarket" to tmList.joinToString(", "),
                "notes" to NoteUtils.formatNewNote(notesInput.text.toString().ifEmpty { "Created lead" }),
                "followUpDate" to (quickAddFollowUpDate?.let { Timestamp(it) } ?: Timestamp(Date(System.currentTimeMillis() + 86400000))),
                "timestamp" to FieldValue.serverTimestamp(),
                "ownerId" to auth.currentUser?.uid
            )

            if (currentQuickBirthday != null) {
                leadData["birthday"] = currentQuickBirthday!!
            }
        
        if (quickAddApptDate != null) leadData["appointmentDate"] = Timestamp(quickAddApptDate!!)
        if (quickAddApptLocation != null) leadData["appointmentLocation"] = quickAddApptLocation

            db.collection("leads").add(leadData).addOnSuccessListener {
                incrementDailyStat("total_count")
                val phoneToNotify = phoneInput.text.toString()
                if (phoneToNotify.isNotEmpty()) {
                if (quickAddApptDate != null) {
                    ReminderReceiver.scheduleReminder(this, phoneToNotify, "Appointment: $name", quickAddApptDate!!.time)
                } else {
                    val timeInMillis = System.currentTimeMillis() + 86400000
                    ReminderReceiver.scheduleReminder(this, phoneToNotify, name, timeInMillis)
                }
                }
                Toast.makeText(this, "Lead added!", Toast.LENGTH_SHORT).show()
                loadHotList()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun performCrop(sourceUri: Uri) {
        try {
            val cropIntent = Intent("com.android.camera.action.CROP")
            cropIntent.setDataAndType(sourceUri, "image/*")
            cropIntent.putExtra("crop", "true")
            cropIntent.putExtra("aspectX", 7)
            cropIntent.putExtra("aspectY", 4)
            cropIntent.putExtra("scale", true)
            cropIntent.putExtra("return-data", false)

            val cropFile = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "crop_${System.currentTimeMillis()}.jpg")
            val cropOutputUri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", cropFile)
            croppedImageUri = cropOutputUri

            cropIntent.putExtra(android.provider.MediaStore.EXTRA_OUTPUT, cropOutputUri)
            cropIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)

            val resInfoList = packageManager.queryIntentActivities(cropIntent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
            if (resInfoList.isEmpty()) {
                processBusinessCard(sourceUri)
                return
            }

            for (resolveInfo in resInfoList) {
                val packageName = resolveInfo.activityInfo.packageName
                grantUriPermission(packageName, sourceUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                grantUriPermission(packageName, cropOutputUri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            cropLauncher.launch(cropIntent)
        } catch (e: Exception) {
            processBusinessCard(sourceUri)
        }
    }

    private fun processBusinessCard(uri: Uri) {
        showLoadingDialog()
        try {
            // Fully qualified path imports to bypass import collisions
            val image = com.google.mlkit.vision.common.InputImage.fromFilePath(this, uri)
            val recognizer = com.google.mlkit.vision.text.TextRecognition.getClient(com.google.mlkit.vision.text.latin.TextRecognizerOptions.DEFAULT_OPTIONS)

            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    hideLoadingDialog()
                        val parsedCard = BusinessCardParser().parse(visionText.text)
                        
                        if (parsedCard.phone != null && quickAddPhoneRef?.text.isNullOrEmpty()) quickAddPhoneRef?.setText(parsedCard.phone)
                        if (parsedCard.email != null && quickAddEmailRef?.text.isNullOrEmpty()) quickAddEmailRef?.setText(parsedCard.email)
                        if (parsedCard.address != null && quickAddAddressRef?.text.isNullOrEmpty()) quickAddAddressRef?.setText(parsedCard.address)
                        if (parsedCard.name != null && quickAddNameRef?.text.isNullOrEmpty()) quickAddNameRef?.setText(parsedCard.name)
                        if (parsedCard.company != null && quickAddCompanyRef?.text.isNullOrEmpty()) quickAddCompanyRef?.setText(parsedCard.company)
                        if (parsedCard.title != null && quickAddTitleRef?.text.isNullOrEmpty()) quickAddTitleRef?.setText(parsedCard.title)
                        
                        if (parsedCard.extraNotes != null) {
                            val currentNotes = quickAddNotesRef?.text?.toString() ?: ""
                            val newNotes = if (currentNotes.isEmpty()) parsedCard.extraNotes else "${currentNotes}\n\n${parsedCard.extraNotes}"
                            quickAddNotesRef?.setText(newNotes.trim())
                        }
                    deleteCurrentPhoto()
                }
                .addOnFailureListener { e ->
                    hideLoadingDialog()
                    Toast.makeText(this, "Failed to read text: ${e.message}", Toast.LENGTH_SHORT).show()
                    deleteCurrentPhoto()
                }
        } catch (e: Exception) {
            hideLoadingDialog()
            Toast.makeText(this, "Error processing image", Toast.LENGTH_SHORT).show()
            deleteCurrentPhoto()
        }
    }

    private fun showLoadingDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setCancelable(false)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(50, 50, 50, 50)
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        
        container.addView(ProgressBar(this))
        
        container.addView(TextView(this).apply {
            text = "Analyzing Business Card..."
            setPadding(50, 0, 0, 0)
            textSize = 16f
        })
        
        builder.setView(container)
        progressDialog = builder.create()
        progressDialog?.show()
    }

    private fun hideLoadingDialog() {
        progressDialog?.dismiss()
        progressDialog = null
    }

    private fun deleteCurrentPhoto() {
        currentPhotoPath?.let { path ->
            val file = File(path)
            if (file.exists()) {
                file.delete()
            }
            currentPhotoPath = null
        }
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

    private fun processVoiceLogQuickAddWithAI(spokenText: String) {
        val originalNotes = quickAddNotesRef?.text.toString()
        quickAddNotesRef?.setText("Processing voice log...")
        
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val prompt = """
                    You are a CRM assistant. Extract data from the following voice transcription.
                    Return ONLY a strict JSON object with these exact keys:
                    - "name" (string, null if none)
                    - "phone" (string, extract phone number or null if none)
                    - "email" (string, extract email address or null if none)
                    - "notes" (string, summary of the interaction)
                    - "category" (string, choose ONE from: "Recruit", "Prospect", "Client", or null)
                    - "company" (string, null if none)
                    - "jobTitle" (string, null if none)
                    - "followUpDate" (string, format "yyyy-MM-dd HH:mm" or null if no date mentioned)
                    - "appointmentDate" (string, format "yyyy-MM-dd HH:mm" or null if they mention booking/scheduling a meeting)
                    - "appointmentLocation" (string, null if none mentioned)
                    - "targetMarket" (array of strings, extract applicable traits from: "Married", "Age 25-55", "Children", "Homeowner", "Occupation" or empty array)
                    
                    Transcription: "$spokenText"
                    
                    Important: The output MUST be a valid JSON object. No markdown, no backticks.
                """.trimIndent()
                
                val response = GeminiApiClient.generativeModel.generateContent(prompt)
                val rawText = response.text ?: "{}"
                val fenced = Regex("```(?:json)?\\s*([\\s\\S]+?)```", RegexOption.IGNORE_CASE).find(rawText)
                val jsonString = (fenced?.groupValues?.get(1) ?: rawText).trim()
                val json = org.json.JSONObject(jsonString)
                
                withContext(Dispatchers.Main) {
                    applyQuickAddVoiceLogJson(json, spokenText, originalNotes)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "AI Error: ${e.message}", Toast.LENGTH_LONG).show()
                    quickAddNotesRef?.setText(NoteUtils.wrapVoiceLog(originalNotes, spokenText))
                }
            }
        }
    }

    private fun applyQuickAddVoiceLogJson(json: org.json.JSONObject, spokenText: String, originalNotes: String) {
        if (json.has("name") && !json.isNull("name")) {
            quickAddNameRef?.setText(json.getString("name"))
        }
        if (json.has("phone") && !json.isNull("phone")) {
            quickAddPhoneRef?.setText(json.getString("phone"))
        }
        if (json.has("email") && !json.isNull("email")) {
            quickAddEmailRef?.setText(json.getString("email"))
        }
        if (json.has("company") && !json.isNull("company")) {
            quickAddCompanyRef?.setText(json.getString("company"))
        }
        if (json.has("jobTitle") && !json.isNull("jobTitle")) {
            quickAddTitleRef?.setText(json.getString("jobTitle"))
        }
        
        if (json.has("category") && !json.isNull("category")) {
            val cat = json.getString("category")
            quickAddCbRecruitRef?.isChecked = cat.contains("Recruit", true)
            quickAddCbProspectRef?.isChecked = cat.contains("Prospect", true)
            quickAddCbClientRef?.isChecked = cat.contains("Client", true)
        }
        
        if (json.has("targetMarket") && !json.isNull("targetMarket")) {
            val tmArray = json.getJSONArray("targetMarket")
            for (i in 0 until tmArray.length()) {
                val trait = tmArray.getString(i)
                if (trait.contains("Married", true)) quickAddCbTmMarriedRef?.isChecked = true
                if (trait.contains("Age", true)) quickAddCbTmAgeRef?.isChecked = true
                if (trait.contains("Children", true)) quickAddCbTmChildrenRef?.isChecked = true
                if (trait.contains("Homeowner", true)) quickAddCbTmHomeownerRef?.isChecked = true
                if (trait.contains("Occupation", true)) quickAddCbTmOccupationRef?.isChecked = true
            }
        }
        
        var extractedNotes = if (json.has("notes") && !json.isNull("notes")) json.getString("notes") else spokenText
        
        if (json.has("followUpDate") && !json.isNull("followUpDate")) {
            val dateStr = json.getString("followUpDate")
            try {
                val format = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                val parsedDate = format.parse(dateStr)
                if (parsedDate != null) {
                    quickAddFollowUpDate = parsedDate
                    extractedNotes += "\n[Reminder Set: ${SimpleDateFormat("MMM dd h:mm a", Locale.getDefault()).format(parsedDate)}]"
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to parse followUpDate: $dateStr", e)
            }
        }
        
        if (json.has("appointmentDate") && !json.isNull("appointmentDate")) {
            val dateStr = json.getString("appointmentDate")
            try {
                val format = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                val parsedDate = format.parse(dateStr)
                if (parsedDate != null) {
                    quickAddApptDate = parsedDate
                    val summarySdf = SimpleDateFormat("MMM dd h:mm a", Locale.getDefault())
                    extractedNotes += "\n[Appointment Set: ${summarySdf.format(parsedDate)}]"
                    Toast.makeText(this, "Appointment captured!", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to parse appointmentDate: $dateStr", e)
            }
        }
        
        if (json.has("appointmentLocation") && !json.isNull("appointmentLocation")) {
            quickAddApptLocation = json.getString("appointmentLocation")
            extractedNotes += " at ${quickAddApptLocation}"
        }
        
        quickAddNotesRef?.setText(NoteUtils.wrapVoiceLog(originalNotes, extractedNotes))
    }
}
<<<<<<< HEAD
=======

class HotListAdapter(
    private val context: Context, 
    private val data: List<Map<String, Any>>,
    private val onCompleteLead: (String) -> Unit
) : BaseAdapter() {
    var currentTab: Int = 0
    
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
        
        val sdf = SimpleDateFormat("MMM dd, yyyy hh:mm a", Locale.getDefault())
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

        val fallbackNote = NoteUtils.getDisplayNote(notes)
        
        val btnExportCalendar = view.findViewById<ImageView>(R.id.btnExportCalendar)
        val appointmentDate = lead["appointmentDate"] as? Timestamp
        val appointmentLocation = lead["appointmentLocation"] as? String ?: ""

        if (currentTab == 3 && appointmentDate != null) {
            val apptSdf = SimpleDateFormat("MMM dd, yyyy 'at' hh:mm a", Locale.getDefault())
            val apptStr = apptSdf.format(appointmentDate.toDate())
            val detailsText = android.text.SpannableStringBuilder("Appt: $apptStr\nLoc: $appointmentLocation\n$fallbackNote")
            detailsText.setSpan(
                android.text.style.ForegroundColorSpan(Color.parseColor("#4CAF50")),
                0,
                "Appt: $apptStr".length,
                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            view.findViewById<TextView>(R.id.tvLeadDetails).text = detailsText
            
            if (btnExportCalendar != null) {
                btnExportCalendar.visibility = View.VISIBLE
                btnExportCalendar.setOnClickListener {
                    val intent = Intent(Intent.ACTION_INSERT)
                        .setData(android.provider.CalendarContract.Events.CONTENT_URI)
                        .putExtra(android.provider.CalendarContract.Events.TITLE, "Meeting with $name")
                        .putExtra(android.provider.CalendarContract.Events.EVENT_LOCATION, appointmentLocation)
                        .putExtra(android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME, appointmentDate.toDate().time)
                        .putExtra(android.provider.CalendarContract.EXTRA_EVENT_END_TIME, appointmentDate.toDate().time + 3600000)
                    context.startActivity(intent)
                }
            }
            
            val btnCompleteIcon = view.findViewById<ImageView>(R.id.btnCompleteIcon)
            if (btnCompleteIcon != null) btnCompleteIcon.visibility = View.GONE
            val llCompleteAction = view.findViewById<LinearLayout>(R.id.llCompleteAction)
            llCompleteAction?.setOnClickListener(null)
            
        } else {
            if (btnExportCalendar != null) btnExportCalendar.visibility = View.GONE

            if (dateStr.isNotEmpty()) {
                val detailsText = android.text.SpannableStringBuilder("Due: $dateStr\n$fallbackNote")
                if (followUpDate != null && followUpDate.toDate().time < System.currentTimeMillis()) {
                    detailsText.setSpan(
                        android.text.style.ForegroundColorSpan(Color.parseColor("#FF5252")),
                        0,
                        "Due: $dateStr".length,
                        android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
                view.findViewById<TextView>(R.id.tvLeadDetails).text = detailsText
            } else {
                view.findViewById<TextView>(R.id.tvLeadDetails).text = fallbackNote
            }
            
            val llCompleteAction = view.findViewById<LinearLayout>(R.id.llCompleteAction)
            llCompleteAction?.setOnClickListener {
                val docId = lead["__docId"] as? String ?: return@setOnClickListener
                onCompleteLead(docId)
            }
    
            val btnCompleteIcon = view.findViewById<ImageView>(R.id.btnCompleteIcon)
            if (btnCompleteIcon != null) {
                if (followUpDate != null && followUpDate.toDate().time < System.currentTimeMillis()) {
                    btnCompleteIcon.visibility = View.VISIBLE
                    btnCompleteIcon.setOnClickListener {
                        val docId = lead["__docId"] as? String ?: return@setOnClickListener
                        onCompleteLead(docId)
                    }
                } else {
                    btnCompleteIcon.visibility = View.GONE
                }
            }
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
>>>>>>> 10f49980ef87626cf8289fce954b01c759d3ea39
