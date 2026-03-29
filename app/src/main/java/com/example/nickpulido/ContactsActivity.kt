package com.nickpulido.rcrm

import android.app.DatePickerDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.net.Uri
import android.os.Environment
import android.view.LayoutInflater
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
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class ContactsActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()
    private val allLeadsData = mutableListOf<Map<String, Any>>()
    private val displayLeadsData = mutableListOf<Map<String, Any>>()
    private lateinit var adapter: ContactsAdapter
    private lateinit var listView: ListView
    private lateinit var etSearch: EditText
    private lateinit var tvEmptyState: TextView
    
    private var currentCategoryFilter: String? = null

    private var cardImageUri: Uri? = null
    private var currentPhotoPath: String? = null
    private var progressDialog: AlertDialog? = null

    private var quickAddNameRef: EditText? = null
    private var quickAddPhoneRef: EditText? = null
    private var quickAddEmailRef: EditText? = null
    private var quickAddAddressRef: EditText? = null
    private var quickAddCompanyRef: EditText? = null
    private var quickAddTitleRef: EditText? = null
    private var quickAddNotesRef: EditText? = null

    private val scanCardLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.TakePicture()) { success ->
        if (success) { cardImageUri?.let { uri -> performCrop(uri) } }
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_contacts)

        listView = findViewById(R.id.listViewContacts)
        etSearch = findViewById(R.id.etSearchContacts)
        tvEmptyState = findViewById(R.id.tvEmptyState)
        val btnBack = findViewById<ImageButton>(R.id.btnBack)
        val fabAdd = findViewById<View>(R.id.fabAddContact)
        val btnFilter = findViewById<ImageButton>(R.id.btnFilterContacts)

        adapter = ContactsAdapter(this, displayLeadsData)
        listView.adapter = adapter

        btnBack.setOnClickListener { finish() }
        
        fabAdd.setOnClickListener { showAddContactDialog() }
        
        btnFilter.setOnClickListener { showFilterPopupMenu(it) }

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { filterContacts() }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        listView.setOnItemClickListener { _, _, position, _ ->
            val lead = displayLeadsData[position]
            showLeadDetailsDialog(lead)
        }

        loadContacts()
    }

    private fun loadContacts() {
        val userId = auth.currentUser?.uid ?: return
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
                allLeadsData.sortBy { (it["name"] as? String ?: "").lowercase() }
                filterContacts()
            }
    }

    private fun filterContacts() {
        val query = etSearch.text.toString().lowercase()
        displayLeadsData.clear()
        
        val filtered = allLeadsData.filter { lead ->
            val name = (lead["name"] as? String ?: "").lowercase()
            val phone = (lead["phone"] as? String ?: "").lowercase()
            val category = (lead["category"] as? String ?: "").lowercase()
            val notes = (lead["notes"] as? String ?: "").lowercase()
            
            val matchesQuery = query.isEmpty() || name.contains(query) || phone.contains(query) || category.contains(query) || notes.contains(query)
            val matchesCategory = currentCategoryFilter == null || category.contains(currentCategoryFilter!!.lowercase())
            
            matchesQuery && matchesCategory
        }
        
        displayLeadsData.addAll(filtered)
        adapter.notifyDataSetChanged()
        tvEmptyState.visibility = if (displayLeadsData.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showFilterPopupMenu(view: View) {
        val popup = PopupMenu(this, view)
        popup.menu.add("All Categories")
        popup.menu.add(getString(R.string.category_prospect))
        popup.menu.add(getString(R.string.category_recruit))
        popup.menu.add(getString(R.string.category_client))
        
        popup.setOnMenuItemClickListener { item ->
            currentCategoryFilter = if (item.title == "All Categories") null else item.title.toString()
            filterContacts()
            true
        }
        popup.show()
    }

    private fun showAddContactDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_quick_add, null)
        val dialog = AlertDialog.Builder(this).setView(dialogView).create()

        val nameInput = dialogView.findViewById<EditText>(R.id.editQuickName)
        val phoneInput = dialogView.findViewById<EditText>(R.id.editQuickPhone)
        val emailInput = dialogView.findViewById<EditText>(R.id.editQuickEmail)
        val addressInput = dialogView.findViewById<EditText>(R.id.editQuickAddress)
        val companyInput = dialogView.findViewById<EditText>(R.id.editQuickCompany)
        val titleInput = dialogView.findViewById<EditText>(R.id.editQuickJobTitle)
        val btnScanCard = dialogView.findViewById<Button>(R.id.btnScanCard)

        val cbRecruit = dialogView.findViewById<CheckBox>(R.id.cbRecruitQuick)
        val cbProspect = dialogView.findViewById<CheckBox>(R.id.cbProspectQuick)
        val cbClient = dialogView.findViewById<CheckBox>(R.id.cbClientQuick)
        val notesInput = dialogView.findViewById<EditText>(R.id.editQuickNotes)
        val btnSave = dialogView.findViewById<Button>(R.id.btnQuickSave)
        
        quickAddNameRef = nameInput
        quickAddPhoneRef = phoneInput
        quickAddEmailRef = emailInput
        quickAddAddressRef = addressInput
        quickAddCompanyRef = companyInput
        quickAddTitleRef = titleInput
        quickAddNotesRef = notesInput

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
                        val uri = androidx.core.content.FileProvider.getUriForFile(this, "${packageName}.fileprovider", photoFile)
                        cardImageUri = uri
                        scanCardLauncher.launch(uri)
                    } else {
                        pickGalleryLauncher.launch("image/*")
                    }
                }
                .show()
        }

        phoneInput.addTextChangedListener(android.telephony.PhoneNumberFormattingTextWatcher())

        btnSave.setOnClickListener {
            val rawName = nameInput.text.toString().trim()
            if (rawName.isEmpty()) {
                nameInput.error = "Name required"
                return@setOnClickListener
            }
            val name = rawName.split("\\s+".toRegex()).joinToString(" ") { word ->
                if (word.isNotEmpty()) word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() } else ""
            }

            val cats = mutableListOf<String>()
            if (cbRecruit.isChecked) cats.add(getString(R.string.category_recruit))
            if (cbProspect.isChecked) cats.add(getString(R.string.category_prospect))
            if (cbClient.isChecked) cats.add(getString(R.string.category_client))

            val rawAddress = addressInput?.text?.toString()?.trim() ?: ""
            val formattedAddress = rawAddress.split("\\s+".toRegex()).joinToString(" ") { word ->
                val cleanWord = word.replace(Regex("[^A-Za-z]"), "")
                if (cleanWord.length == 2) word.uppercase() else word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
            }

            val timestamp = SimpleDateFormat("MMM dd, yyyy hh:mm a", Locale.getDefault()).format(Date())
            val formattedNotes = if (notesInput.text.isNotEmpty()) {
                "[$timestamp]: ${notesInput.text}"
            } else {
                "[$timestamp]: Created contact"
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
                "email" to (emailInput?.text?.toString()?.trim() ?: ""),
                "address" to formattedAddress,
                "company" to (companyInput?.text?.toString()?.trim() ?: ""),
                "jobTitle" to (titleInput?.text?.toString()?.trim() ?: ""),
                "category" to cats.joinToString(", "),
                "targetMarket" to tmList.joinToString(", "),
                "notes" to formattedNotes,
                "followUpDate" to Timestamp(Date(System.currentTimeMillis() + 86400000)),
                "timestamp" to Timestamp.now(),
                "ownerId" to auth.currentUser?.uid
            )

            if (currentQuickBirthday != null) {
                leadData["birthday"] = currentQuickBirthday!!
            }

            db.collection("leads").add(leadData).addOnSuccessListener {
                incrementDailyStat("total_count")
                loadContacts()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun incrementDailyStat(field: String) {
        val userId = auth.currentUser?.uid ?: return
        val dateStr = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())

        // Update locally for immediate UI feedback when returning to MainActivity
        val statsPrefs = getSharedPreferences("daily_stats_local", Context.MODE_PRIVATE)
        val currentLocalCount = statsPrefs.getInt("${dateStr}_$field", 0)
        statsPrefs.edit { putInt("${dateStr}_$field", currentLocalCount + 1) }

        db.collection("user_settings").document(userId)
            .collection("daily_stats").document(dateStr)
            .set(hashMapOf(field to FieldValue.increment(1)), SetOptions.merge())
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
        val lines = text.split("\n").map { it.trim() }.filter { it.isNotEmpty() }

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
        if (finalPhone != null && quickAddPhoneRef?.text.isNullOrEmpty()) {
            quickAddPhoneRef?.setText(finalPhone)
        }

        val emailRegex = Regex("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}")
        val emailMatch = emailRegex.find(text)
        val extractedEmail = emailMatch?.value

        if (extractedEmail != null && quickAddEmailRef?.text.isNullOrEmpty()) {
            quickAddEmailRef?.setText(extractedEmail)
        }

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

        var detectedAddress: String? = null
        val addressKeywords = listOf(
            "street", "st.", " st ", "avenue", "ave.", " ave ", "boulevard", "blvd", 
            "road", "rd.", " rd ", "drive", "dr.", " dr ", "suite", "ste.", " ste ", "pkwy", "parkway", 
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
        
        if (detectedAddress != null && quickAddAddressRef?.text.isNullOrEmpty()) {
            quickAddAddressRef?.setText(detectedAddress)
        }
        if (possibleName != null && quickAddNameRef?.text.isNullOrEmpty()) {
            quickAddNameRef?.setText(possibleName)
        }
        
        val currentNotes = quickAddNotesRef?.text?.toString() ?: ""
        
        if (detectedCompany != null && quickAddCompanyRef?.text.isNullOrEmpty()) {
            quickAddCompanyRef?.setText(detectedCompany)
        }
        if (detectedTitle != null && quickAddTitleRef?.text.isNullOrEmpty()) {
            quickAddTitleRef?.setText(detectedTitle)
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
        quickAddNotesRef?.setText(newNotes.trim())
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
            if (file.exists()) file.delete()
            currentPhotoPath = null
        }
    }

    private fun showLeadDetailsDialog(lead: Map<String, Any>) {
        val intent = Intent(this, FollowUpActivity::class.java)
        intent.putExtra("targetLeadId", lead["__docId"] as? String)
        intent.putExtra("targetPhone", lead["phone"] as? String)
        startActivity(intent)
    }

    private class ContactsAdapter(context: Context, private val leads: List<Map<String, Any>>) :
        ArrayAdapter<Map<String, Any>>(context, 0, leads) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_lead_card, parent, false)
            val tvName = view.findViewById<TextView>(R.id.tvLeadName)
            val tvDetails = view.findViewById<TextView>(R.id.tvLeadDetails)
            val tvCategoryLabel = view.findViewById<TextView>(R.id.tvCategoryLabel)
            val btnCall = view.findViewById<ImageView>(R.id.btnCallIcon)
            view.findViewById<CheckBox>(R.id.cbSelectLead).visibility = View.GONE
            view.findViewById<LinearLayout>(R.id.llCompleteAction)?.visibility = View.GONE

            val lead = leads[position]
            val name = lead["name"] as? String ?: "Unknown"
            val phone = lead["phone"] as? String ?: ""
            val category = lead["category"] as? String ?: ""

            tvName.text = name
            tvDetails.text = phone.ifEmpty { "No phone number" }

            if (category.isNotEmpty()) {
                tvCategoryLabel.text = category
                tvCategoryLabel.visibility = View.VISIBLE
                
                val background = GradientDrawable()
                background.setColor(Color.parseColor("#E9ECEF"))
                background.cornerRadius = 16f
                tvCategoryLabel.background = background
                tvCategoryLabel.setTextColor(Color.parseColor("#495057"))
            } else {
                tvCategoryLabel.visibility = View.GONE
            }

            btnCall.setOnClickListener {
                if (phone.isNotEmpty()) {
                    context.startActivity(Intent(Intent.ACTION_DIAL, "tel:$phone".toUri()))
                }
            }
            return view
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
}
