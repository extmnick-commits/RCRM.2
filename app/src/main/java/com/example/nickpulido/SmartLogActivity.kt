package com.nickpulido.rcrm

import android.annotation.SuppressLint
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.ContentProviderOperation
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
    private var phoneNumber: String = ""
    private var contactName: String = ""
    private var isNewContact: Boolean = true
    private var followUpDate: Date? = null
    private var accountDefaultSms: String? = null
    
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
                tvToggleAdvanced?.text = "Hide ▲"
            } else {
                containerAdvancedLogging?.visibility = View.GONE
                tvToggleAdvanced?.text = "Show ▼"
            }
        }

        val headerSmsIntro = findViewById<LinearLayout>(R.id.headerSmsIntro)
        val containerSmsIntro = findViewById<LinearLayout>(R.id.containerSmsIntro)
        val tvToggleSms = findViewById<TextView>(R.id.tvToggleSms)

        headerSmsIntro?.setOnClickListener {
            android.transition.TransitionManager.beginDelayedTransition(findViewById(android.R.id.content))
            if (containerSmsIntro?.visibility == View.GONE) {
                containerSmsIntro.visibility = View.VISIBLE
                tvToggleSms?.text = "Hide ▲"
            } else {
                containerSmsIntro?.visibility = View.GONE
                tvToggleSms?.text = "Show ▼"
            }
        }

        etContactName.setText(contactName)
        tvLogPhoneNumber.text = phoneNumber

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
                        etIntroText.setText(response.text?.trim() ?: "Could not generate message.")
                        btnAIGenerate.isEnabled = true
                        btnAIGenerate.text = "✨ AI Draft"
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "AI Draft Error", e)
                    withContext(Dispatchers.Main) {
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
            followUpDate = cal.time
            val sdf = SimpleDateFormat("MMM dd, yyyy hh:mm a", Locale.getDefault())
            Toast.makeText(this, "Reminder set: ${sdf.format(followUpDate!!)}", Toast.LENGTH_SHORT).show()
        }

        btn7pmToday.setOnLongClickListener {
            val isDark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
            val theme = if (isDark) android.R.style.Theme_DeviceDefault_Dialog_Alert else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert
            
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
            followUpDate = cal.time
            val sdf = SimpleDateFormat("MMM dd, yyyy hh:mm a", Locale.getDefault())
            Toast.makeText(this, "Reminder set: ${sdf.format(followUpDate!!)}", Toast.LENGTH_SHORT).show()
        }

        btn7pmTomorrow.setOnLongClickListener {
            val isDark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
            val theme = if (isDark) android.R.style.Theme_DeviceDefault_Dialog_Alert else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert
            
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

        btnSave.setOnClickListener {
            Log.d(TAG, "Save button clicked")
            val rawName = etContactName.text.toString().trim()
            val newName = rawName.split("\\s+".toRegex()).joinToString(" ") { word ->
                if (word.isNotEmpty()) word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() } else ""
            }
            if (newName != contactName) {
                saveOrUpdateSystemContact(newName, phoneNumber)
                contactName = newName
            }
        val cbTmMarried = findViewById<CheckBox>(R.id.cbTmMarried)
        val cbTmAge = findViewById<CheckBox>(R.id.cbTmAge)
        val cbTmChildren = findViewById<CheckBox>(R.id.cbTmChildren)
        val cbTmHomeowner = findViewById<CheckBox>(R.id.cbTmHomeowner)
        val cbTmOccupation = findViewById<CheckBox>(R.id.cbTmOccupation)
        performSaveLeadAction(cbRecruit, cbProspect, cbClient, cbInvestment, cbLifeInsurance, cbTmMarried, cbTmAge, cbTmChildren, cbTmHomeowner, cbTmOccupation, etContactEmail, etContactAddress, etContactCompany, etContactJobTitle, etNotes) {
                Toast.makeText(this, "Lead History Saved", Toast.LENGTH_SHORT).show()
                finish()
            }
        }

        btnSms.setOnClickListener {
            Log.d(TAG, "SMS button clicked")
            val rawName = etContactName.text.toString().trim()
            val newName = rawName.split("\\s+".toRegex()).joinToString(" ") { word ->
                if (word.isNotEmpty()) word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() } else ""
            }
            if (newName != contactName) {
                saveOrUpdateSystemContact(newName, phoneNumber)
                contactName = newName
            }
        val cbTmMarried2 = findViewById<CheckBox>(R.id.cbTmMarried)
        val cbTmAge2 = findViewById<CheckBox>(R.id.cbTmAge)
        val cbTmChildren2 = findViewById<CheckBox>(R.id.cbTmChildren)
        val cbTmHomeowner2 = findViewById<CheckBox>(R.id.cbTmHomeowner)
        val cbTmOccupation2 = findViewById<CheckBox>(R.id.cbTmOccupation)
        performSaveLeadAction(cbRecruit, cbProspect, cbClient, cbInvestment, cbLifeInsurance, cbTmMarried2, cbTmAge2, cbTmChildren2, cbTmHomeowner2, cbTmOccupation2, etContactEmail, etContactAddress, etContactCompany, etContactJobTitle, etNotes) {
                val message = etIntroText.text.toString()
                val smsIntent = Intent(Intent.ACTION_VIEW, "sms:$phoneNumber".toUri())
                smsIntent.putExtra("sms_body", message)
                startActivity(smsIntent)
                finish()
            }
        }
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
            DatePickerDialog(this, { _, year, month, day ->
                if (tempCal == null) tempCal = Calendar.getInstance()
                tempCal!!.set(year, month, day)
                updateDateTimeUI()
            }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
        }

        btnApptTime.setOnClickListener {
            val cal = tempCal ?: Calendar.getInstance()
            TimePickerDialog(this, { _, hour, minute ->
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

            appointmentDate = tempCal!!.time
            appointmentLocation = finalLoc
            
            val summarySdf = SimpleDateFormat("MMM dd h:mm a", Locale.getDefault())
            findViewById<Button>(R.id.btnMarkAppointment)?.text = "Appt: ${summarySdf.format(appointmentDate!!)}"
            
            Toast.makeText(this, "Appointment scheduled for saving", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showDateTimePicker(initialCal: Calendar) {
        val isDark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val theme = if (isDark) android.R.style.Theme_DeviceDefault_Dialog_Alert else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert
        
        DatePickerDialog(this, theme, { _, year, month, day ->
            initialCal.set(year, month, day)
            TimePickerDialog(this, theme, { _, hour, minute ->
                initialCal.set(Calendar.HOUR_OF_DAY, hour)
                initialCal.set(Calendar.MINUTE, minute)
                val selectedDate = initialCal.time
                followUpDate = selectedDate
                
                val sdf = SimpleDateFormat("MMM dd, yyyy hh:mm a", Locale.getDefault())
                val formattedDate = sdf.format(selectedDate)
                Toast.makeText(this, "Reminder set: $formattedDate", Toast.LENGTH_SHORT).show()
                
                findViewById<Button>(R.id.btnScheduleCustom)?.text = formattedDate
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

    private fun performSaveLeadAction(
        cbR: CheckBox, cbP: CheckBox, cbC: CheckBox, cbInv: CheckBox, cbLife: CheckBox,
        cbTmMarried: CheckBox?, cbTmAge: CheckBox?, cbTmChildren: CheckBox?, cbTmHomeowner: CheckBox?, cbTmOccupation: CheckBox?,
        etEmail: EditText, etAddress: EditText, etCompany: EditText, etTitle: EditText, etN: EditText,
        onSuccess: () -> Unit
    ) {
        val cats = mutableListOf<String>()
        if (cbR.isChecked) cats.add(getString(R.string.category_recruit))
        if (cbP.isChecked) cats.add(getString(R.string.category_prospect))
        if (cbC.isChecked) {
            cats.add(getString(R.string.category_client))
            if (cbInv.isChecked) cats.add("Investment")
            if (cbLife.isChecked) cats.add("Life Insurance")
        }

        val tmList = mutableListOf<String>()
        if (cbTmMarried?.isChecked == true) tmList.add("Married")
        if (cbTmAge?.isChecked == true) tmList.add("Age 25-55")
        if (cbTmChildren?.isChecked == true) tmList.add("Children")
        if (cbTmHomeowner?.isChecked == true) tmList.add("Homeowner")
        if (cbTmOccupation?.isChecked == true) tmList.add("Occupation")
        val targetMarket = tmList.joinToString(", ")

        val finalCategory = cats.joinToString(", ")
        val noteText = etN.text.toString()
        val emailText = etEmail.text.toString().trim()
        val rawAddress = etAddress.text.toString().trim()
        val formattedAddress = rawAddress.split("\\s+".toRegex()).joinToString(" ") { word ->
            val cleanWord = word.replace(Regex("[^A-Za-z]"), "")
            if (cleanWord.length == 2) word.uppercase() else word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
        }
        val companyText = etCompany.text.toString().trim()
        val titleText = etTitle.text.toString().trim()
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

                val time = SimpleDateFormat("MMM dd, yyyy hh:mm a", Locale.getDefault()).format(Date())
                
                if (existingLead == null) {
                    // REALLY a new contact for this user
                    Log.d(TAG, "New lead detected, incrementing goal")
                    val formattedNote = if (noteText.isNotEmpty()) "[$time]: $noteText" else ""
                    val data = hashMapOf(
                        "name" to contactName, 
                        "phone" to phoneNumber, 
                        "email" to emailText,
                        "address" to formattedAddress,
                        "company" to companyText,
                        "jobTitle" to titleText,
                        "category" to finalCategory,
                        "targetMarket" to targetMarket,
                        "notes" to formattedNote, 
                        "followUpDate" to followUpDate?.let { Timestamp(it) }, 
                        "appointmentDate" to appointmentDate?.let { Timestamp(it) },
                        "appointmentLocation" to appointmentLocation,
                        "timestamp" to Timestamp.now(),
                        "ownerId" to currentUserId,
                        "source" to "smart_log"
                    )
                    db.collection("leads").add(data)
                        .addOnSuccessListener { 
                            incrementDailyStat("total_count")
                            val phoneToNotify = phoneNumber.ifEmpty { contactName }
                            if (appointmentDate != null) {
                                ReminderReceiver.scheduleReminder(this, phoneToNotify, "Appointment: $contactName", appointmentDate!!.time)
                            } else if (followUpDate != null) {
                                ReminderReceiver.scheduleReminder(this, phoneToNotify, contactName, followUpDate!!.time)
                            }
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
                    val formatted = if (noteText.isNotEmpty()) "[$time]: $noteText" else ""
                    val combined = if (existingNotes.isNotEmpty() && formatted.isNotEmpty()) "$existingNotes\n\n$formatted" else formatted.ifEmpty { existingNotes }

                    val update = mutableMapOf<String, Any?>(
                        "name" to contactName,
                        "email" to emailText,
                        "address" to formattedAddress,
                        "company" to companyText,
                        "jobTitle" to titleText,
                        "category" to finalCategory,
                        "targetMarket" to targetMarket,
                        "notes" to combined, 
                        "timestamp" to Timestamp.now()
                    )
                    followUpDate?.let { update["followUpDate"] = Timestamp(it) }
                    appointmentDate?.let { update["appointmentDate"] = Timestamp(it) }
                    appointmentLocation?.let { update["appointmentLocation"] = it }
                    
                    
                    db.collection("leads").document(docId).update(update)
                        .addOnSuccessListener { 
                            if (noteText.isNotEmpty()) {
                                incrementDailyStat("followup_count")
                            }
                            val phoneToNotify = phoneNumber.ifEmpty { contactName }
                            if (appointmentDate != null) {
                                ReminderReceiver.scheduleReminder(this, phoneToNotify, "Appointment: $contactName", appointmentDate!!.time)
                            } else if (followUpDate != null) {
                                ReminderReceiver.scheduleReminder(this, phoneToNotify, contactName, followUpDate!!.time)
                            } else {
                                ReminderReceiver.cancelReminder(this, phoneToNotify)
                            }
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
}
