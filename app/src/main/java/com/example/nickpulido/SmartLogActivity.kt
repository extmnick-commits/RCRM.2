package com.nickpulido.rcrm

import android.Manifest
import android.annotation.SuppressLint
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.chip.ChipGroup
import com.nickpulido.rcrm.domain.ActionType
import com.nickpulido.rcrm.domain.FollowUpRepository
import com.nickpulido.rcrm.domain.NextActionSpec
import com.nickpulido.rcrm.domain.OutcomeSavePlanner
import com.nickpulido.rcrm.domain.OutcomeSaveRequest
import com.nickpulido.rcrm.domain.SmartLogPlanner
import com.nickpulido.rcrm.domain.WorkflowLifecycle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

class SmartLogActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()
    private val followUpRepository = FollowUpRepository()
    private var logOperationId = UUID.randomUUID().toString()
    private var logTimestamp = System.currentTimeMillis()
    private var isSavingLog = false
    private var loadedContactId: String? = null
    private var savedProfileForOperation = false
    private var savedOutcomeForOperation = false
    private var savedAppointmentForOperation = false
    private var restoreDraft = false
    private var canEditDraft = true
    private var baselineProfile: Map<String, String>? = null
    private var baselineSms = ""

    private val dictateNoteLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val spoken = result.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!spoken.isNullOrBlank() && canEditDraft) {
                val input = findViewById<EditText>(R.id.etQuickNote)
                input.setText(listOf(input.text.toString().trim(), spoken.trim()).filter { it.isNotBlank() }.joinToString("\n"))
                input.setSelection(input.length())
            }
        }
    }
    private var phoneNumber: String = ""
    private var contactName: String = ""
    private var followUpDate: Date? = null
    private var accountDefaultSms: String? = null
    private var insRenewalDateStr: String? = null
    
    // Appointment variables
    private var appointmentDate: Date? = null
    private var appointmentLocation: String? = null
    private var defaultOfficeAddress: String = ""
    
    private var todayPresetHour: Int = 19
    private var todayPresetMinute: Int = 0
    private var tomorrowPresetHour: Int = 19
    private var tomorrowPresetMinute: Int = 0

    private lateinit var statsPrefs: SharedPreferences

    private var cardImageUri: Uri? = null
    private var currentPhotoPath: String? = null
    private var progressDialog: AlertDialog? = null

    private val scanCardLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.TakePicture()) { success ->
        if (success) {
            cardImageUri?.let { uri -> performCrop(uri) }
        }
    }

    private var croppedImageUri: Uri? = null

    private val pickGalleryLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) performCrop(uri)
    }

    private val cropLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            croppedImageUri?.let { processBusinessCard(it) }
        }
    }

    private var pendingCalendarAction: ((Boolean) -> Unit)? = null

    private val calendarPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result[Manifest.permission.READ_CALENDAR] == true &&
            result[Manifest.permission.WRITE_CALENDAR] == true
        val action = pendingCalendarAction
        pendingCalendarAction = null
        action?.invoke(granted)
    }

    private companion object {
        const val PREFS_NAME = "IntroPresets"
        const val PREFS_KEY = "saved_presets_list"
        const val PREFS_IGNORED = "ignored_contacts_list"
        const val NAME_TOKEN = "[NAME]"
        const val TAG = "SmartLogActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_smart_log)
        restoreDraft = savedInstanceState != null
        savedInstanceState?.let {
            logOperationId = it.getString("smart_log_operation") ?: logOperationId
            logTimestamp = it.getLong("smart_log_timestamp", logTimestamp)
            followUpDate = it.getLong("smart_follow_up").takeIf { value -> value > 0L }?.let(::Date)
            appointmentDate = it.getLong("smart_appointment").takeIf { value -> value > 0L }?.let(::Date)
            appointmentLocation = it.getString("smart_location")
            insRenewalDateStr = it.getString("smart_renewal")
            loadedContactId = it.getString("smart_contact_id")
            savedProfileForOperation = it.getBoolean("smart_profile_saved")
            savedOutcomeForOperation = it.getBoolean("smart_outcome_saved")
            savedAppointmentForOperation = it.getBoolean("smart_appointment_saved")
            val keys = it.getStringArrayList("smart_profile_keys")
            val values = it.getStringArrayList("smart_profile_values")
            if (keys != null && values != null) baselineProfile = keys.zip(values).toMap()
            baselineSms = it.getString("smart_baseline_sms").orEmpty()
        }
        val root = findViewById<View>(R.id.smartLogRoot)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            insets
        }
        ViewCompat.requestApplyInsets(root)
        onBackPressedDispatcher.addCallback(this) { requestLeaveSmartLog() }

        statsPrefs = getSharedPreferences("daily_stats_local", Context.MODE_PRIVATE)

        phoneNumber = intent.getStringExtra("INCOMING_NUMBER") ?: ""
        val (name, _) = getContactNameAndStatus(phoneNumber)
        contactName = name.takeUnless { it == getString(R.string.unknown_contact) }.orEmpty()

        val etContactName = findViewById<EditText>(R.id.etContactName)
        val tvLogPhoneNumber = findViewById<TextView>(R.id.tvLogPhoneNumber)
        val etContactEmail = findViewById<EditText>(R.id.etContactEmail)
        val etContactAddress = findViewById<EditText>(R.id.etContactAddress)
        val etContactCompany = findViewById<EditText>(R.id.etContactCompany)
        val etContactJobTitle = findViewById<EditText>(R.id.etContactJobTitle)
        val btnSkip = findViewById<Button>(R.id.btnSkipPersonal)
        val btnIgnore = findViewById<Button>(R.id.btnIgnoreCallLog)
        val cbRecruit = findViewById<CheckBox>(R.id.cbRecruit)
        val cbProspect = findViewById<CheckBox>(R.id.cbProspect)
        val cbClient = findViewById<CheckBox>(R.id.cbClient)
        val clientSubCategoryLayout = findViewById<LinearLayout>(R.id.clientSubCategoryLayout)
        val cbInvestment = findViewById<CheckBox>(R.id.cbInvestment)
        val cbLifeInsurance = findViewById<CheckBox>(R.id.cbLifeInsurance)
        val cardLifeInsuranceDetails = findViewById<View>(R.id.cardLifeInsuranceDetails)
        val cardInvestmentDetails = findViewById<View>(R.id.cardInvestmentDetails)
        val tvInsRenewalDate = findViewById<TextView>(R.id.tvInsRenewalDate)
        val btnSetInsRenewalDate = findViewById<Button>(R.id.btnSetInsRenewalDate)

        fun updateProductCardsVisibility() {
            val isClient = cbClient.isChecked
            clientSubCategoryLayout.visibility = if (isClient) View.VISIBLE else View.GONE
            if (!isClient) {
                cbInvestment.isChecked = false
                cbLifeInsurance.isChecked = false
            }
            cardLifeInsuranceDetails?.visibility = if (isClient && cbLifeInsurance.isChecked) View.VISIBLE else View.GONE
            cardInvestmentDetails?.visibility = if (isClient && cbInvestment.isChecked) View.VISIBLE else View.GONE
        }
        cbClient.setOnCheckedChangeListener { _, _ -> updateProductCardsVisibility() }
        cbLifeInsurance.setOnCheckedChangeListener { _, _ -> updateProductCardsVisibility() }
        cbInvestment.setOnCheckedChangeListener { _, _ -> updateProductCardsVisibility() }

        btnSetInsRenewalDate?.setOnClickListener {
            val cal = Calendar.getInstance()
            DatePickerDialog(this, { _, y, m, d ->
                insRenewalDateStr = String.format("%04d-%02d-%02d", y, m + 1, d)
                tvInsRenewalDate?.text = "Annual Review / Issue Date: $insRenewalDateStr"
            }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
        }
        val etNotes = findViewById<EditText>(R.id.etQuickNote)
        val etIntroText = findViewById<EditText>(R.id.etIntroText)
        val btnSave = findViewById<Button>(R.id.btnSaveLeadAction)
        val btnSms = findViewById<Button>(R.id.btnSendIntroAction)

        val btnManagePresets = findViewById<Button>(R.id.btnManagePresets)
        val btnDefaultIntro = findViewById<Button>(R.id.btnDefaultIntro)

        val headerAdvancedLogging = findViewById<LinearLayout>(R.id.headerAdvancedLogging)
        val containerAdvancedLogging = findViewById<LinearLayout>(R.id.containerAdvancedLogging)
        val tvToggleAdvanced = findViewById<TextView>(R.id.tvToggleAdvanced)

        headerAdvancedLogging?.setOnClickListener {
            android.transition.TransitionManager.beginDelayedTransition(findViewById(android.R.id.content))
            if (containerAdvancedLogging?.visibility == View.GONE) {
                containerAdvancedLogging.visibility = View.VISIBLE
                tvToggleAdvanced?.setText(R.string.sl_hide)
            } else {
                containerAdvancedLogging?.visibility = View.GONE
                tvToggleAdvanced?.setText(R.string.sl_show)
            }
        }

        val headerSmsIntro = findViewById<LinearLayout>(R.id.headerSmsIntro)
        val containerSmsIntro = findViewById<LinearLayout>(R.id.containerSmsIntro)
        val tvToggleSms = findViewById<TextView>(R.id.tvToggleSms)

        headerSmsIntro?.setOnClickListener {
            android.transition.TransitionManager.beginDelayedTransition(findViewById(android.R.id.content))
            if (containerSmsIntro?.visibility == View.GONE) {
                containerSmsIntro.visibility = View.VISIBLE
                tvToggleSms?.setText(R.string.sl_hide)
            } else {
                containerSmsIntro?.visibility = View.GONE
                tvToggleSms?.setText(R.string.sl_show)
            }
        }

        etContactName.setText(contactName)
        tvLogPhoneNumber.text = phoneNumber.ifBlank { getString(R.string.sl_unknown_phone) }

        // AI Draft Button
        val btnAIGenerate = findViewById<Button>(R.id.btnAIGenerate)
        btnAIGenerate.setOnClickListener {
            val currentName = etContactName.text.toString()
            val currentNote = etNotes.text.toString()
            
            btnAIGenerate.isEnabled = false
            btnAIGenerate.text = "Drafting..."
            
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val prompt = "You are an assistant for a Primerica agent. Draft a short, warm, and professional SMS message to $currentName. " +
                                 "Use the following notes for context. Do not mention the notes directly. " +
                                 "Keep it under 2 sentences and ready to send.\nNotes:\n$currentNote"
                    val response = GeminiApiClient.generativeModel.generateContent(prompt)
                    
                    withContext(Dispatchers.Main) {
                        if (isSavingLog || isFinishing || isDestroyed) return@withContext
                        etIntroText.setText(response.text?.trim() ?: "Could not generate message.")
                        btnAIGenerate.isEnabled = true
                        btnAIGenerate.text = "✨ AI Draft"
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "AI Draft Error", e)
                    withContext(Dispatchers.Main) {
                        if (isSavingLog || isFinishing || isDestroyed) return@withContext
                        Toast.makeText(this@SmartLogActivity, "AI Error: ${e.message}", Toast.LENGTH_LONG).show()
                        btnAIGenerate.isEnabled = true
                        btnAIGenerate.text = "✨ AI Draft"
                    }
                }
            }
        }

        val btnScanCard = findViewById<Button>(R.id.btnScanCardSmartLog)
        btnScanCard.setOnClickListener {
            val options = arrayOf("📷 Take Photo", "🖼️ Choose from Gallery")
            AlertDialog.Builder(this)
                .setTitle("Scan Business Card")
                .setItems(options) { _, which ->
                    if (which == 0) {
                        val photoFile = java.io.File(getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES), "card_${System.currentTimeMillis()}.jpg")
                        currentPhotoPath = photoFile.absolutePath
                        val uri = androidx.core.content.FileProvider.getUriForFile(this, "${packageName}.fileprovider", photoFile)
                        cardImageUri = uri
                        scanCardLauncher.launch(uri)
                    } else {
                        pickGalleryLauncher.launch("image/*")
                    }
                }
                .show()
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
            if (etIntroText.text.isBlank() && !restoreDraft) {
                applyDefaultIntro()
                baselineSms = etIntroText.text.toString()
            }
            updateFollowUpButtons()
        }

        btnSkip.setOnClickListener { requestLeaveSmartLog() }

        btnIgnore.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.sl_ignore_title)
                .setMessage(R.string.sl_ignore_message)
                .setPositiveButton(R.string.sl_ignore_confirm) { _, _ ->
                    if (phoneNumber.isNotBlank()) {
                        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
                        val ignored = prefs.getStringSet(PREFS_IGNORED, emptySet()).orEmpty().toMutableSet()
                        ignored.add(phoneNumber)
                        prefs.edit { putStringSet(PREFS_IGNORED, ignored) }
                        auth.currentUser?.uid?.let { userId ->
                            db.collection("user_settings").document(userId)
                                .set(mapOf("ignored_numbers" to FieldValue.arrayUnion(phoneNumber)), SetOptions.merge())
                        }
                        finish()
                    }
                }
                .setNegativeButton(android.R.string.cancel, null).show()
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
        val btnScheduleCustom = findViewById<Button>(R.id.btnScheduleCustom)
        val btnMarkAppointment = findViewById<Button>(R.id.btnMarkAppointment)
        
        btnMarkAppointment.setOnClickListener {
            showAppointmentDialog()
        }

        btnScheduleCustom.setOnClickListener {
            showDateTimePicker(Calendar.getInstance())
        }

        btnScheduleCustom.setOnLongClickListener {
            showDateTimePicker(Calendar.getInstance())
            true
        }

        btn7pmToday.setOnClickListener {
            val cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, todayPresetHour)
                set(Calendar.MINUTE, todayPresetMinute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            selectSmartFollowUp(cal.time)
        }

        btn7pmToday.setOnLongClickListener {
            val theme = R.style.ThemeOverlay_RCRM_DatePicker
            
            TimePickerDialog(this, theme, { _, hour, minute ->
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
            selectSmartFollowUp(cal.time)
        }

        btn7pmTomorrow.setOnLongClickListener {
            val theme = R.style.ThemeOverlay_RCRM_DatePicker
            
            TimePickerDialog(this, theme, { _, hour, minute ->
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

        btnSave.setOnClickListener { saveSmartLog(openText = false) }
        btnSms.setOnClickListener { saveSmartLog(openText = true) }
        findViewById<Button>(R.id.btnDictateSmartNote).setOnClickListener {
            try {
                dictateNoteLauncher.launch(Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                    putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, getString(R.string.sl_note))
                })
            } catch (_: Exception) {
                Toast.makeText(this, R.string.sl_dictation_unavailable, Toast.LENGTH_LONG).show()
            }
        }
        findViewById<Button>(R.id.btnMoreSmartDates).setOnClickListener {
            val more = findViewById<View>(R.id.containerSmartDates)
            more.visibility = if (more.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        findViewById<Button>(R.id.btnClearSmartReminder).setOnClickListener {
            followUpDate = null
            renderSmartNextStep()
        }
        findViewById<Button>(R.id.btnClearSmartAppointment).setOnClickListener {
            appointmentDate = null
            appointmentLocation = null
            renderSmartNextStep()
        }
        findViewById<ChipGroup>(R.id.chipSmartActionType).setOnCheckedStateChangeListener { _, ids ->
            val title = findViewById<EditText>(R.id.etSmartActionTitle)
            val defaults = setOf(getString(R.string.sl_default_action), getString(R.string.sl_call), getString(R.string.sl_text), getString(R.string.sl_task))
            if (title.text.isBlank() || title.text.toString() in defaults) {
                title.setText(when (ids.firstOrNull()) {
                    R.id.chipSmartText -> getString(R.string.sl_text)
                    R.id.chipSmartTodo -> getString(R.string.sl_task)
                    else -> getString(R.string.sl_default_action)
                })
            }
        }
        renderSmartNextStep()
        if (baselineProfile == null) baselineProfile = smartProfileDraft()
        loadSmartContact()
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

            val cropFile = java.io.File(getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES), "crop_${System.currentTimeMillis()}.jpg")
            val cropOutputUri = androidx.core.content.FileProvider.getUriForFile(this, "${packageName}.fileprovider", cropFile)
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
            val image = com.google.mlkit.vision.common.InputImage.fromFilePath(this, uri)
            val recognizer = com.google.mlkit.vision.text.TextRecognition.getClient(com.google.mlkit.vision.text.latin.TextRecognizerOptions.DEFAULT_OPTIONS)

            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    hideLoadingDialog()
                    parseBusinessCardText(visionText.text)
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

    private fun parseBusinessCardText(text: String) {
        val nameRef = findViewById<EditText>(R.id.etContactName)
        val phoneRef = findViewById<TextView>(R.id.tvLogPhoneNumber)
        val emailRef = findViewById<EditText>(R.id.etContactEmail)
        val addressRef = findViewById<EditText>(R.id.etContactAddress)
        val companyRef = findViewById<EditText>(R.id.etContactCompany)
        val titleRef = findViewById<EditText>(R.id.etContactJobTitle)
        val notesRef = findViewById<EditText>(R.id.etQuickNote)

        val lines = text.split("\n").map { it.trim() }.filter { it.isNotEmpty() }

        // --- 1. Smart Phone Number Selection ---
        val phoneRegex = Regex("(\\+?\\d{1,2}\\s?)?\\(?\\d{3}\\)?[\\s.-]?\\d{3}[\\s.-]?\\d{4}")
        var bestPhone: String? = null
        var fallbackPhone: String? = null

        for (line in lines) {
            val match = phoneRegex.find(line)
            if (match != null) {
                val lowerLine = line.lowercase()
                if (lowerLine.contains("f") || lowerLine.contains("fax")) continue
                if (fallbackPhone == null) fallbackPhone = match.value
                
                if (lowerLine.contains("m") || lowerLine.contains("c") || lowerLine.contains("cell") || lowerLine.contains("mobile") || lowerLine.contains("direct")) {
                    bestPhone = match.value
                }
            }
        }
        val finalPhone = bestPhone ?: fallbackPhone
        if (finalPhone != null && phoneNumber.isEmpty()) {
            phoneNumber = finalPhone
            phoneRef.text = finalPhone
        }

        // --- 2. Email Extraction ---
        val emailRegex = Regex("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}")
        val emailMatch = emailRegex.find(text)
        val extractedEmail = emailMatch?.value

        if (extractedEmail != null && emailRef.text.isNullOrEmpty()) {
            emailRef.setText(extractedEmail)
        }

        // --- 3. Smart Company & Title Extraction ---
        val titleKeywords = listOf("manager", "director", "president", "ceo", "engineer", "agent", "representative", "consultant", "founder", "owner", "specialist", "vp", "vice president", "associate", "broker", "realtor", "officer", "advisor")
        val companyKeywords = listOf("llc", "inc", "corp", "ltd", "company", "group", "solutions", "enterprises", "partners", "agency")

        var detectedTitle: String? = null
        var detectedCompany: String? = null
        var companyLineUsed: String? = null

        val genericDomains = listOf("gmail", "yahoo", "hotmail", "outlook", "aol", "icloud", "msn", "me", "live")
        if (extractedEmail != null) {
            val domainPart = extractedEmail.substringAfter("@").substringBeforeLast(".")
            if (domainPart.lowercase() !in genericDomains) {
                val companyLine = lines.firstOrNull { 
                    it.contains(domainPart, ignoreCase = true) && !it.contains("@") && !it.lowercase().contains("www.")
                }
                if (companyLine != null) {
                    detectedCompany = companyLine
                    companyLineUsed = companyLine
                } else {
                    detectedCompany = domainPart.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
                }
            }
        }

        // --- 4. Smart Name Extraction ---
        val possibleName = lines.firstOrNull { line ->
            val lowerLine = line.lowercase()
            !line.contains(Regex("\\d")) && 
            !line.contains("@") && 
            line.split(Regex("\\s+")).size in 2..4 &&
            !titleKeywords.any { lowerLine.contains(it) } &&
            !companyKeywords.any { lowerLine.contains(it) }
        }
        
        for (line in lines) {
            val lowerLine = line.lowercase()
            if (line == possibleName || line.contains("@") || line.contains(Regex("\\d"))) continue
            
            if (detectedTitle == null && titleKeywords.any { lowerLine.contains(it) }) {
                detectedTitle = line
            } else if (detectedCompany == null && companyKeywords.any { lowerLine.contains(it) }) {
                detectedCompany = line
                companyLineUsed = line
            }
        }

        // --- 5. Smart Address Extraction ---
        var detectedAddress: String? = null
        val addressKeywords = listOf(
            "street", "st.", " st ", "avenue", "ave.", " ave ", "boulevard", "blvd", 
            "road", "rd.", " rd ", "drive", "dr.", " dr ", "suite", "ste ", "pkwy", "parkway", 
            "lane", "ln.", "court", "ct.", "plaza", "way", "po box", "p.o. box", "floor", "fl.", "highway", "hwy",
            "bldg", "building", "terrace", "circle", "cir", "trail", "trl", "square", "sq"
        )
        val zipRegex = Regex("\\b\\d{5}(?:-\\d{4})?\\b")
        val stateZipRegex = Regex("\\b[A-Za-z]{2}[\\s,]+\\d{5}(?:-\\d{4})?\\b")
        val addressParts = mutableListOf<String>()
        
        for (i in lines.indices) {
            val line = lines[i]
            val lowerLine = line.lowercase()
            
            val startsWithNumber = Regex("^\\s*\\d+.*").matches(line)
            val isPoBox = lowerLine.contains("po box") || lowerLine.contains("p.o. box")
            val hasKeyword = addressKeywords.any { lowerLine.contains(it) }
            val hasZip = zipRegex.containsMatchIn(line)
            val hasStateZip = stateZipRegex.containsMatchIn(line)
            
            if ((startsWithNumber && hasKeyword) || isPoBox || hasStateZip) {
                detectedAddress = line
                addressParts.add(line)
                if (!hasZip) {
                    var lookahead = 1
                    while (i + lookahead < lines.size && lookahead <= 3) {
                        val nextLine = lines[i + lookahead]
                        val lowerNext = nextLine.lowercase()
                        val hasEmail = nextLine.contains("@")
                        val hasWeb = lowerNext.contains("www.") || lowerNext.contains(".com")
                        val hasPhone = Regex("(\\+?\\d{1,2}\\s?)?\\(?\\d{3}\\)?[\\s.-]?\\d{3}[\\s.-]?\\d{4}").containsMatchIn(nextLine)
                        if (hasEmail || hasWeb || hasPhone) break
                        detectedAddress += ", $nextLine"
                        addressParts.add(nextLine)
                        if (zipRegex.containsMatchIn(nextLine)) break
                        lookahead++
                    }
                }
                break
            }
        }
        if (detectedAddress != null && addressRef.text.isNullOrEmpty()) {
            addressRef.setText(detectedAddress)
        }
        
        val currentNameText = nameRef.text?.toString() ?: ""
        if (possibleName != null && (currentNameText.isEmpty() || currentNameText == getString(R.string.unknown_contact))) {
            val formattedName = possibleName.split("\\s+".toRegex()).joinToString(" ") { word ->
                if (word.isNotEmpty()) word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() } else ""
            }
            nameRef.setText(formattedName)
        }
        
        val currentNotes = notesRef.text.toString()
        
        if (detectedCompany != null && companyRef.text.isNullOrEmpty()) {
            companyRef.setText(detectedCompany)
        }
        if (detectedTitle != null && titleRef.text.isNullOrEmpty()) {
            titleRef.setText(detectedTitle)
        }
        
        val unusedLines = lines.toMutableList()
        if (finalPhone != null) unusedLines.removeAll { it.contains(finalPhone) }
        if (extractedEmail != null) unusedLines.removeAll { it.contains(extractedEmail) }
        if (possibleName != null) unusedLines.remove(possibleName)
        if (detectedTitle != null) unusedLines.remove(detectedTitle)
        if (companyLineUsed != null) unusedLines.remove(companyLineUsed)
        unusedLines.removeAll(addressParts)

        val leftoverText = unusedLines.joinToString("\n").trim()
        val parsedNotes = if (leftoverText.isNotEmpty()) "[Scanned Card Extra Info]:\n$leftoverText" else ""
        val newNotes = if (currentNotes.isEmpty()) parsedNotes else if (parsedNotes.isNotEmpty()) "$currentNotes\n\n$parsedNotes" else currentNotes
        notesRef.setText(newNotes.trim())
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
        progressDialog?.window?.setBackgroundDrawableResource(android.R.color.transparent)
        progressDialog?.show()
    }

    private fun hideLoadingDialog() {
        progressDialog?.dismiss()
        progressDialog = null
    }

    private fun deleteCurrentPhoto() {
        currentPhotoPath?.let { path ->
            val file = java.io.File(path)
            if (file.exists()) file.delete()
            currentPhotoPath = null
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
                defaultOfficeAddress = snapshot.getString("default_office_address") ?: ""
                
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

    private fun showAppointmentDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_appointment, null)
        val dialog = AlertDialog.Builder(this).setView(dialogView).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val btnApptDate = dialogView.findViewById<Button>(R.id.btnApptDate)
        val btnApptTime = dialogView.findViewById<Button>(R.id.btnApptTime)
        val tvSelectedDateTime = dialogView.findViewById<TextView>(R.id.tvSelectedDateTime)
        
        val rgApptLocation = dialogView.findViewById<RadioGroup>(R.id.rgApptLocation)
        val rbOffice = dialogView.findViewById<RadioButton>(R.id.rbOffice)
        val rbCustom = dialogView.findViewById<RadioButton>(R.id.rbCustom)
        
        val llOfficeAddressConfig = dialogView.findViewById<LinearLayout>(R.id.llOfficeAddressConfig)
        val tvCurrentOffice = dialogView.findViewById<TextView>(R.id.tvCurrentOffice)
        val btnEditOffice = dialogView.findViewById<Button>(R.id.btnEditOffice)
        
        val tilCustomAddress = dialogView.findViewById<View>(R.id.tilCustomAddress)
        val etCustomAddress = dialogView.findViewById<EditText>(R.id.etCustomAddress)
        
        val btnCancelAppt = dialogView.findViewById<Button>(R.id.btnCancelAppt)
        val btnSaveAppt = dialogView.findViewById<Button>(R.id.btnSaveAppt)
        
        var tempCal: Calendar? = if (appointmentDate != null) Calendar.getInstance().apply { time = appointmentDate!! } else null
        
        val sdfDate = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
        val sdfTime = SimpleDateFormat("h:mm a", Locale.getDefault())

        fun updateDateTimeUI() {
            if (tempCal != null) {
                tvSelectedDateTime.text = "${sdfDate.format(tempCal!!.time)} at ${sdfTime.format(tempCal!!.time)}"
            } else {
                tvSelectedDateTime.text = "No date/time selected"
            }
        }
        
        fun updateOfficeUI() {
            if (defaultOfficeAddress.isNotEmpty()) {
                tvCurrentOffice.text = defaultOfficeAddress
            } else {
                tvCurrentOffice.text = "No address set"
            }
        }
        
        updateDateTimeUI()
        updateOfficeUI()

        rgApptLocation.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId == R.id.rbOffice) {
                llOfficeAddressConfig.visibility = View.VISIBLE
                tilCustomAddress.visibility = View.GONE
            } else {
                llOfficeAddressConfig.visibility = View.GONE
                tilCustomAddress.visibility = View.VISIBLE
            }
        }
        
        if (appointmentLocation != null) {
            if (appointmentLocation == defaultOfficeAddress) {
                rbOffice.isChecked = true
            } else {
                rbCustom.isChecked = true
                etCustomAddress.setText(appointmentLocation)
                llOfficeAddressConfig.visibility = View.GONE
                tilCustomAddress.visibility = View.VISIBLE
            }
        } else {
            rbOffice.isChecked = true
            llOfficeAddressConfig.visibility = View.VISIBLE
            tilCustomAddress.visibility = View.GONE
        }

        btnEditOffice.setOnClickListener {
            val input = EditText(this).apply {
                setText(defaultOfficeAddress)
                setPadding(48, 24, 48, 24)
            }
            
            AlertDialog.Builder(this)
                .setTitle("Default Office Address")
                .setView(input)
                .setPositiveButton("Save") { _, _ ->
                    defaultOfficeAddress = input.text.toString().trim()
                    updateOfficeUI()
                    auth.currentUser?.uid?.let { userId ->
                        db.collection("user_settings").document(userId)
                            .set(mapOf("default_office_address" to defaultOfficeAddress), SetOptions.merge())
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        btnApptDate.setOnClickListener {
            val cal = tempCal ?: Calendar.getInstance()
            DatePickerDialog(this, R.style.ThemeOverlay_RCRM_DatePicker, { _, year, month, day ->
                if (tempCal == null) tempCal = Calendar.getInstance()
                tempCal!!.set(year, month, day)
                updateDateTimeUI()
            }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
        }

        btnApptTime.setOnClickListener {
            val cal = tempCal ?: Calendar.getInstance()
            TimePickerDialog(this, R.style.ThemeOverlay_RCRM_DatePicker, { _, hour, minute ->
                if (tempCal == null) tempCal = Calendar.getInstance()
                tempCal!!.set(Calendar.HOUR_OF_DAY, hour)
                tempCal!!.set(Calendar.MINUTE, minute)
                updateDateTimeUI()
            }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), false).show()
        }

        btnCancelAppt.setOnClickListener { dialog.dismiss() }

        btnSaveAppt.setOnClickListener {
            if (tempCal == null) {
                Toast.makeText(this, "Please select Date & Time", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (rbOffice.isChecked && defaultOfficeAddress.isEmpty()) {
                Toast.makeText(this, "Please set a default Office Address first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            val finalLoc = if (rbOffice.isChecked) defaultOfficeAddress else etCustomAddress.text.toString().trim()
            if (finalLoc.isEmpty()) {
                Toast.makeText(this, "Please provide a location", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (tempCal!!.timeInMillis <= System.currentTimeMillis()) {
                Toast.makeText(this, R.string.sl_past_date, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            appointmentDate = tempCal!!.time
            appointmentLocation = finalLoc
            savedAppointmentForOperation = false
            
            renderSmartNextStep()
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showDateTimePicker(initialCal: Calendar) {
        val theme = R.style.ThemeOverlay_RCRM_DatePicker
        
        DatePickerDialog(this, theme, { _, year, month, day ->
            initialCal.set(year, month, day)
            TimePickerDialog(this, theme, { _, hour, minute ->
                initialCal.set(Calendar.HOUR_OF_DAY, hour)
                initialCal.set(Calendar.MINUTE, minute)
                initialCal.set(Calendar.SECOND, 0)
                initialCal.set(Calendar.MILLISECOND, 0)
                selectSmartFollowUp(initialCal.time)
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
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

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
                        }.create().apply {
                            window?.setBackgroundDrawableResource(android.R.color.transparent)
                            show()
                        }
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

    private fun selectSmartFollowUp(date: Date) {
        if (date.time <= System.currentTimeMillis()) {
            showSmartError(getString(R.string.sl_past_date))
            showDateTimePicker(Calendar.getInstance().apply { add(Calendar.HOUR_OF_DAY, 1) })
            return
        }
        followUpDate = date
        val title = findViewById<EditText>(R.id.etSmartActionTitle)
        if (title.text.isBlank()) title.setText(R.string.sl_default_action)
        findViewById<View>(R.id.tvSmartSaveError).visibility = View.GONE
        renderSmartNextStep()
    }

    private fun renderSmartNextStep() {
        val format = SimpleDateFormat("EEE, MMM d · h:mm a", Locale.getDefault())
        findViewById<TextView>(R.id.tvSmartNextSummary).text = followUpDate?.let {
            getString(R.string.sl_reminder_due, format.format(it))
        } ?: getString(R.string.sl_no_reminder)
        findViewById<View>(R.id.containerSmartReminder).visibility = if (followUpDate == null) View.GONE else View.VISIBLE
        val appointmentSummary = findViewById<TextView>(R.id.tvSmartAppointmentSummary)
        appointmentSummary.visibility = if (appointmentDate == null) View.GONE else View.VISIBLE
        appointmentSummary.text = appointmentDate?.let {
            getString(if (savedAppointmentForOperation) R.string.sl_appointment_saved else R.string.sl_appointment_due, format.format(it)) + "\n" + appointmentLocation.orEmpty()
        }.orEmpty()
        findViewById<View>(R.id.btnClearSmartAppointment).visibility = if (savedAppointmentForOperation) View.GONE else appointmentSummary.visibility
        findViewById<Button>(R.id.btnMarkAppointment).apply {
            setText(R.string.sl_appointment)
            isEnabled = !isSavingLog && !savedAppointmentForOperation
        }
    }

    private fun selectedSmartOutcome(): String? = when (findViewById<ChipGroup>(R.id.chipSmartOutcome).checkedChipId) {
        R.id.chipSmartConnected -> "Connected"
        R.id.chipSmartNoAnswer -> "No answer"
        R.id.chipSmartVoicemail -> "Voicemail left"
        else -> null
    }

    private fun smartActionType(): ActionType = when (findViewById<ChipGroup>(R.id.chipSmartActionType).checkedChipId) {
        R.id.chipSmartText -> ActionType.MESSAGE
        R.id.chipSmartTodo -> ActionType.TO_DO
        else -> ActionType.CALL
    }

    private fun smartProfileDraft(): Map<String, String> {
        val fields = mapOf(
            "name" to R.id.etContactName, "email" to R.id.etContactEmail,
            "address" to R.id.etContactAddress, "company" to R.id.etContactCompany, "jobTitle" to R.id.etContactJobTitle,
            "insCarrier" to R.id.editInsCarrier, "insPolicyNumber" to R.id.editInsPolicyNumber,
            "insFaceAmount" to R.id.editInsFaceAmount, "insMonthlyPremium" to R.id.editInsMonthlyPremium,
            "insBeneficiaries" to R.id.editInsBeneficiaries, "invPlatform" to R.id.editInvPlatform,
            "invLumpSumRollover" to R.id.editInvLumpSum, "invMonthlyContribution" to R.id.editInvMonthlyContribution
        )
        val draft = fields.mapValues { (_, id) -> findViewById<EditText>(id).text.toString().trim() }.toMutableMap()
        val categories = listOf(R.id.cbRecruit to getString(R.string.category_recruit), R.id.cbProspect to getString(R.string.category_prospect),
            R.id.cbClient to getString(R.string.category_client), R.id.cbInvestment to "Investment", R.id.cbLifeInsurance to "Life Insurance")
        draft["category"] = categories.filter { findViewById<CheckBox>(it.first).isChecked }.joinToString(", ") { it.second }
        draft["targetMarket"] = listOf(R.id.cbTmMarried to "Married", R.id.cbTmAge to "Age 25-55", R.id.cbTmChildren to "Children",
            R.id.cbTmHomeowner to "Homeowner", R.id.cbTmOccupation to "Occupation")
            .filter { findViewById<CheckBox>(it.first).isChecked }.joinToString(", ") { it.second }
        mapOf("insPolicyType" to R.id.spinnerInsPolicyType, "insUnderwritingStatus" to R.id.spinnerInsStatus,
            "invAccountType" to R.id.spinnerInvAccountType, "invAccountStatus" to R.id.spinnerInvStatus).forEach { (key, id) ->
            val spinner = findViewById<Spinner>(id)
            if (spinner.selectedItemPosition > 0) draft[key] = spinner.selectedItem?.toString().orEmpty()
        }
        insRenewalDateStr?.let { draft["insRenewalDate"] = it }
        return draft
    }

    private fun loadSmartContact() {
        val owner = auth.currentUser?.uid ?: return
        db.collection("leads").whereEqualTo("ownerId", owner).get().addOnSuccessListener { snapshot ->
            if (isFinishing || isDestroyed || isSavingLog) return@addOnSuccessListener
            val matches = snapshot.documents.filter { SmartLogPlanner.phonesMatch(phoneNumber, it.getString("phone").orEmpty()) }
            val lead = matches.singleOrNull()
            loadedContactId = lead?.id
            findViewById<TextView>(R.id.tvSmartContactStatus).setText(if (lead == null) R.string.sl_new_contact else R.string.sl_existing_contact)
            if (lead == null || restoreDraft) return@addOnSuccessListener
            val nameInput = findViewById<EditText>(R.id.etContactName)
            if (nameInput.text.toString() == contactName) {
                contactName = lead.getString("name").orEmpty().ifBlank { contactName }
                nameInput.setText(contactName)
            }
            if (findViewById<View>(R.id.containerAdvancedLogging).visibility == View.VISIBLE) return@addOnSuccessListener
            val categories = lead.getString("category").orEmpty()
            listOf(R.id.cbRecruit to getString(R.string.category_recruit), R.id.cbProspect to getString(R.string.category_prospect),
                R.id.cbClient to getString(R.string.category_client), R.id.cbInvestment to "Investment", R.id.cbLifeInsurance to "Life Insurance")
                .forEach { (id, label) -> findViewById<CheckBox>(id).isChecked = categories.contains(label, ignoreCase = true) }
            val market = lead.getString("targetMarket").orEmpty()
            listOf(R.id.cbTmMarried to "Married", R.id.cbTmAge to "Age 25-55", R.id.cbTmChildren to "Children",
                R.id.cbTmHomeowner to "Homeowner", R.id.cbTmOccupation to "Occupation")
                .forEach { (id, label) -> findViewById<CheckBox>(id).isChecked = market.contains(label) }
            mapOf("email" to R.id.etContactEmail, "address" to R.id.etContactAddress, "company" to R.id.etContactCompany,
                "jobTitle" to R.id.etContactJobTitle, "insCarrier" to R.id.editInsCarrier, "insPolicyNumber" to R.id.editInsPolicyNumber,
                "insFaceAmount" to R.id.editInsFaceAmount, "insMonthlyPremium" to R.id.editInsMonthlyPremium,
                "insBeneficiaries" to R.id.editInsBeneficiaries, "invPlatform" to R.id.editInvPlatform,
                "invLumpSumRollover" to R.id.editInvLumpSum, "invMonthlyContribution" to R.id.editInvMonthlyContribution).forEach { (key, id) ->
                val input = findViewById<EditText>(id)
                if (input.text.isBlank()) input.setText(lead.getString(key).orEmpty())
            }
            baselineProfile = smartProfileDraft()
        }
    }

    private fun saveSmartLog(openText: Boolean) {
        if (isSavingLog) return
        val owner = auth.currentUser?.uid ?: run {
            showSmartError("Sign in again before saving.")
            return
        }
        val profile = smartProfileDraft()
        val name = profile["name"].orEmpty()
        val note = findViewById<EditText>(R.id.etQuickNote).text.toString().trim()
        val outcome = selectedSmartOutcome()
        val actionTitle = findViewById<EditText>(R.id.etSmartActionTitle).text.toString().trim()
        if (name.isBlank()) {
            findViewById<EditText>(R.id.etContactName).apply { error = getString(R.string.sl_name_required); requestFocus() }
            return
        }
        if (followUpDate != null && actionTitle.isBlank()) {
            findViewById<EditText>(R.id.etSmartActionTitle).apply { error = getString(R.string.sl_reminder_title_required); requestFocus() }
            return
        }
        if (!savedOutcomeForOperation && listOfNotNull(followUpDate, appointmentDate).any { it.time <= System.currentTimeMillis() }) {
            showSmartError(getString(R.string.sl_past_date)); return
        }
        val message = findViewById<EditText>(R.id.etIntroText).text.toString().trim()
        if (openText && (message.isBlank() || phoneNumber.isBlank())) {
            showSmartError("Add a message and phone number before opening a text."); return
        }
        contactName = name
        setSmartSaving(true)
        val finishSave: (String, Boolean) -> Unit = { leadId, isNew ->
            val saveActivity = {
                followUpRepository.saveOutcome(
                    OutcomeSaveRequest(
                        operationId = logOperationId, leadId = leadId, ownerId = owner,
                        outcome = outcome ?: "Call logged", note = note, activityType = ActionType.CALL,
                        stage = null, currentActionId = null, resolveCurrentActionIfMissing = false,
                        nextAction = followUpDate?.let { NextActionSpec(actionTitle, it.time, smartActionType()) },
                        clearExistingNextAction = false, lifecycle = WorkflowLifecycle.UNCHANGED,
                        lifecycleReason = null, nowMillis = logTimestamp
                    )
                ) { success, error ->
                    if (!success) {
                        setSmartSaving(false)
                        showSmartError(getString(R.string.sl_save_error))
                    } else {
                        savedOutcomeForOperation = true
                        followUpDate?.let { due ->
                            ReminderAlarmStore.scheduleAction(this, leadId,
                                OutcomeSavePlanner.nextActionDocumentId(logOperationId), actionTitle, name, phoneNumber, due.time)
                        }
                        recordSmartLogStat(leadId, if (isNew) "total_count" else "followup_count")
                        Toast.makeText(this, R.string.sl_saved, Toast.LENGTH_SHORT).show()
                        if (openText) {
                            try {
                                startActivity(Intent(Intent.ACTION_SENDTO, "smsto:$phoneNumber".toUri()).putExtra("sms_body", message))
                            } catch (_: Exception) {
                                Toast.makeText(this, R.string.sl_open_text_error, Toast.LENGTH_LONG).show()
                            }
                        }
                        finish()
                    }
                }
            }
            val start = appointmentDate?.time
            if (start == null || savedAppointmentForOperation) {
                saveActivity()
            } else {
                val appointmentId = "smart_$logOperationId"
                val ref = db.collection("leads").document(leadId)
                db.runTransaction { transaction ->
                    val fresh = transaction.get(ref)
                    val records = SmartLogPlanner.addAppointment(
                        leadId, LeadAppointmentSync.readRecords(fresh.get(AppointmentAgenda.FIELD_APPOINTMENTS)),
                        fresh.getTimestamp("appointmentDate")?.toDate()?.time, fresh.getString("appointmentLocation"),
                        GoogleCalendarManager.readStoredEventId(fresh.get("googleCalendarEventId")),
                        appointmentId, start, appointmentLocation, logTimestamp
                    )
                    transaction.set(ref, LeadAppointmentSync.fields(records, System.currentTimeMillis(), forUpdate = true), SetOptions.merge())
                    records.first { it.id == appointmentId }
                }.addOnSuccessListener { record ->
                    savedAppointmentForOperation = true
                    ReminderReceiver.scheduleReminder(this, AppointmentAgenda.appointmentReminderKey(appointmentId), "Appointment: $name", start)
                    syncSmartLogAppointment(leadId, record.eventId, appointmentId) { saveActivity() }
                }.addOnFailureListener {
                    setSmartSaving(false); showSmartError(getString(R.string.sl_save_error))
                }
            }
        }
        db.collection("leads").whereEqualTo("ownerId", owner).get().addOnSuccessListener { snapshot ->
            val matches = snapshot.documents.filter {
                SmartLogPlanner.phonesMatch(phoneNumber, it.getString("phone").orEmpty()) ||
                    (phoneNumber.isBlank() && it.id == loadedContactId)
            }
            if (matches.size > 1) {
                setSmartSaving(false)
                showSmartError("More than one contact has this number. Merge duplicates in Contacts before saving.")
                return@addOnSuccessListener
            }
            val existing = matches.singleOrNull()
            val ref = existing?.reference ?: db.collection("leads").document("smart_$logOperationId")
            loadedContactId = ref.id
            val update = SmartLogPlanner.profileUpdates(profile).toMutableMap()
            update["timestamp"] = Timestamp.now()
            if (existing == null) {
                update["ownerId"] = owner
                update["phone"] = phoneNumber
                update["source"] = "smart_log"
                update["smartLogCreatedBy"] = logOperationId
            }
            // Create directly: the repository rules do not permit reading a nonexistent lead.
            ref.set(update, SetOptions.merge()).addOnSuccessListener {
                savedProfileForOperation = true
                followUpRepository.migrateLegacyReminder(ref.id, owner) { migration ->
                    if (migration == null) {
                        setSmartSaving(false)
                        showSmartError(getString(R.string.sl_save_error))
                    } else {
                        if (migration.cancelLegacyPhoneAlarm) {
                            ReminderAlarmStore.cancelLegacyPhone(this, phoneNumber, ref.id)
                        }
                        migration.actionToCreate?.let { action ->
                            ReminderAlarmStore.scheduleAction(this, ref.id, action.id, action.title, name, phoneNumber, action.dueTime)
                        }
                        finishSave(ref.id, existing == null || existing.getString("smartLogCreatedBy") == logOperationId)
                    }
                }
            }.addOnFailureListener {
                setSmartSaving(false); showSmartError(getString(R.string.sl_save_error))
            }
        }.addOnFailureListener {
            setSmartSaving(false); showSmartError(getString(R.string.sl_save_error))
        }
    }

    private fun showSmartError(message: String) {
        findViewById<TextView>(R.id.tvSmartSaveError).apply {
            text = if (savedProfileForOperation) getString(R.string.sl_partial_save, message) else message
            visibility = View.VISIBLE
        }
    }

    private fun setSmartSaving(saving: Boolean) {
        isSavingLog = saving
        canEditDraft = !saving
        fun enable(view: View) {
            view.isEnabled = !saving
            if (view is ViewGroup) for (index in 0 until view.childCount) enable(view.getChildAt(index))
        }
        enable(findViewById(R.id.smartLogScroll))
        findViewById<Button>(R.id.btnSaveLeadAction).apply {
            isEnabled = !saving
            setText(if (saving) R.string.sl_saving else R.string.sl_save)
        }
        if (saving) findViewById<View>(R.id.tvSmartSaveError).visibility = View.GONE
        renderSmartNextStep()
    }

    private fun requestLeaveSmartLog() {
        if (isSavingLog) return
        val hasDraft = findViewById<EditText>(R.id.etQuickNote).text.isNotBlank() || selectedSmartOutcome() != null ||
            followUpDate != null || appointmentDate != null || savedProfileForOperation ||
            smartProfileDraft() != baselineProfile ||
            findViewById<EditText>(R.id.etIntroText).text.toString() != baselineSms
        if (!hasDraft) { finish(); return }
        AlertDialog.Builder(this).setTitle(R.string.sl_discard_title).setMessage(R.string.sl_discard_message)
            .setPositiveButton(R.string.sl_discard) { _, _ -> finish() }
            .setNegativeButton(R.string.sl_keep_editing, null).show()
    }

    private fun syncSmartLogAppointment(leadId: String, existingEventId: Long?, appointmentId: String, onDone: () -> Unit) {
        val startMillis = appointmentDate?.time ?: run { onDone(); return }
        if (leadId.isBlank()) { onDone(); return }
        val runSync = {
            GoogleCalendarManager.syncCrmAppointmentAsync(
                context = this,
                leadId = leadId,
                leadName = contactName.ifBlank { "Lead" },
                phone = phoneNumber,
                startMillis = startMillis,
                location = appointmentLocation,
                existingEventId = existingEventId,
                appointmentId = appointmentId
            ) { result ->
                when (result) {
                    is GoogleCalendarManager.AppointmentSyncResult.Linked -> {
                        LeadAppointmentWriter.commit(
                            db = db,
                            leadId = leadId,
                            mutationFor = { _, _, _, _ ->
                                AppointmentSync.AppointmentMutation.SetEventId(appointmentId, result.eventId)
                            },
                            onSuccess = { },
                            onFailure = {
                                Toast.makeText(
                                    this,
                                    "The calendar event was saved, but RCRM couldn't store its id.",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        )
                    }
                    is GoogleCalendarManager.AppointmentSyncResult.Failed -> {
                        Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
                    }
                    GoogleCalendarManager.AppointmentSyncResult.Cleared,
                    GoogleCalendarManager.AppointmentSyncResult.Skipped -> Unit
                }
                onDone()
            }
        }
        if (GoogleCalendarManager.hasCalendarPermissions(this)) {
            runSync()
        } else {
            pendingCalendarAction = { granted ->
                if (granted) {
                    runSync()
                } else {
                    Toast.makeText(
                        this,
                        "Appointment saved. Allow calendar access to sync with Google Calendar.",
                        Toast.LENGTH_LONG
                    ).show()
                    onDone()
                }
            }
            calendarPermissionLauncher.launch(
                arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
            )
        }
    }

    private fun recordSmartLogStat(leadId: String, field: String) {
        val owner = auth.currentUser?.uid ?: return
        val day = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date(logTimestamp))
        val activity = db.collection("leads").document(leadId).collection("activity_records")
            .document(OutcomeSavePlanner.activityDocumentId(logOperationId))
        val stats = db.collection("user_settings").document(owner).collection("daily_stats").document(day)
        db.runTransaction { transaction ->
            val saved = transaction.get(activity)
            if (!saved.exists() || saved.getBoolean("smartLogStatsCounted") == true) return@runTransaction false
            transaction.set(stats, mapOf(field to FieldValue.increment(1)), SetOptions.merge())
            transaction.update(activity, "smartLogStatsCounted", true)
            true
        }.addOnSuccessListener { counted ->
            if (counted) {
                val key = "${day}_$field"
                statsPrefs.edit { putInt(key, statsPrefs.getInt(key, 0) + 1) }
            }
        }.addOnFailureListener { Log.w(TAG, "Couldn't update Smart Log progress", it) }
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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        cardImageUri?.let { outState.putParcelable("CARD_IMAGE_URI", it) }
        croppedImageUri?.let { outState.putParcelable("CROPPED_IMAGE_URI", it) }
        currentPhotoPath?.let { outState.putString("CURRENT_PHOTO_PATH", it) }
        outState.putString("smart_log_operation", logOperationId)
        outState.putLong("smart_log_timestamp", logTimestamp)
        outState.putLong("smart_follow_up", followUpDate?.time ?: 0L)
        outState.putLong("smart_appointment", appointmentDate?.time ?: 0L)
        outState.putString("smart_location", appointmentLocation)
        outState.putString("smart_renewal", insRenewalDateStr)
        outState.putString("smart_contact_id", loadedContactId)
        outState.putBoolean("smart_profile_saved", savedProfileForOperation)
        outState.putBoolean("smart_outcome_saved", savedOutcomeForOperation)
        outState.putBoolean("smart_appointment_saved", savedAppointmentForOperation)
        outState.putInt("smart_details_visibility", findViewById<View>(R.id.containerAdvancedLogging).visibility)
        outState.putInt("smart_sms_visibility", findViewById<View>(R.id.containerSmsIntro).visibility)
        baselineProfile?.let { baseline ->
            outState.putStringArrayList("smart_profile_keys", ArrayList(baseline.keys))
            outState.putStringArrayList("smart_profile_values", ArrayList(baseline.values))
        }
        outState.putString("smart_baseline_sms", baselineSms)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        @Suppress("DEPRECATION")
        cardImageUri = savedInstanceState.getParcelable("CARD_IMAGE_URI")
        @Suppress("DEPRECATION")
        croppedImageUri = savedInstanceState.getParcelable("CROPPED_IMAGE_URI")
        currentPhotoPath = savedInstanceState.getString("CURRENT_PHOTO_PATH")
        findViewById<View>(R.id.containerAdvancedLogging).visibility = savedInstanceState.getInt("smart_details_visibility", View.GONE)
        findViewById<View>(R.id.containerSmsIntro).visibility = savedInstanceState.getInt("smart_sms_visibility", View.GONE)
        findViewById<TextView>(R.id.tvToggleAdvanced).setText(if (findViewById<View>(R.id.containerAdvancedLogging).visibility == View.VISIBLE) R.string.sl_hide else R.string.sl_show)
        findViewById<TextView>(R.id.tvToggleSms).setText(if (findViewById<View>(R.id.containerSmsIntro).visibility == View.VISIBLE) R.string.sl_hide else R.string.sl_show)
        renderSmartNextStep()
    }
}
