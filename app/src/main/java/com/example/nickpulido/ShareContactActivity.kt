package com.nickpulido.rcrm

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.nickpulido.rcrm.domain.SharedContactItem
import com.nickpulido.rcrm.domain.VCardParser

class ShareContactActivity : AppCompatActivity() {

    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_share_contact)

        val root = findViewById<View>(R.id.shareContactRoot)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                bars.left + view.paddingLeft,
                bars.top + view.paddingTop,
                bars.right + view.paddingRight,
                bars.bottom + view.paddingBottom
            )
            insets
        }

        findViewById<Button>(R.id.btnShareCancel).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnShareDone).setOnClickListener { finish() }

        val contacts = readSharedContacts(intent)
        val contact = contacts.firstOrNull { it.phones.isNotEmpty() }
        if (contacts.isEmpty()) {
            showStatus("This item does not contain a supported contact card. Try Share Contact from Contacts or Phone.")
            return
        }
        if (contact == null) {
            showStatus("The shared contact has no phone number. Make sure the phone field is included when sharing.")
            return
        }

        renderReview(contact)
    }

    private fun readSharedContacts(intent: Intent?): List<SharedContactItem> {
        if (intent == null) return emptyList()
        @Suppress("DEPRECATION")
        val streamUri: Uri? = intent.getParcelableExtra(Intent.EXTRA_STREAM) ?: intent.data
        if (streamUri != null) {
            val text = runCatching {
                contentResolver.openInputStream(streamUri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
            if (!text.isNullOrBlank()) {
                return VCardParser.parse(text)
            }
        }
        val extraText = intent.getStringExtra(Intent.EXTRA_TEXT)
        if (!extraText.isNullOrBlank() && extraText.contains("BEGIN:VCARD", ignoreCase = true)) {
            return VCardParser.parse(extraText)
        }
        return emptyList()
    }

    private fun showStatus(message: String) {
        findViewById<View>(R.id.cardShareStatus).visibility = View.VISIBLE
        findViewById<View>(R.id.containerShareReview).visibility = View.GONE
        findViewById<TextView>(R.id.tvShareStatus).text = message
    }

    private fun renderReview(contact: SharedContactItem) {
        findViewById<View>(R.id.cardShareStatus).visibility = View.GONE
        findViewById<View>(R.id.containerShareReview).visibility = View.VISIBLE

        val etName = findViewById<EditText>(R.id.etShareContactName)
        etName.setText(contact.name)

        val metaParts = listOf(contact.company, contact.jobTitle, contact.email, contact.address)
            .filter { it.isNotBlank() }
        val tvMeta = findViewById<TextView>(R.id.tvShareMeta)
        if (metaParts.isNotEmpty()) {
            tvMeta.text = metaParts.joinToString(" · ")
            tvMeta.visibility = View.VISIBLE
        }

        val tvHint = findViewById<TextView>(R.id.tvSharePhoneHint)
        tvHint.text = if (contact.phones.size > 1) {
            "Choose the main number to open in Smart Log. All shared numbers will be saved."
        } else {
            "Tap the number to open in Smart Log, or save directly to RCRM."
        }

        val layoutPhones = findViewById<LinearLayout>(R.id.layoutSharePhones)
        layoutPhones.removeAllViews()
        contact.phones.forEach { entry ->
            val button = Button(this, null, 0, R.style.SmartLogButton).apply {
                text = "${entry.label}: ${entry.number}"
                isAllCaps = false
                setOnClickListener {
                    val editedName = etName.text.toString().trim().ifEmpty { contact.name }
                    openInSmartLog(contact.copy(name = editedName), entry.number)
                }
            }
            layoutPhones.addView(button)
        }

        val btnSaveDirect = findViewById<Button>(R.id.btnShareSaveDirect)
        val userId = auth.currentUser?.uid
        if (userId == null) {
            btnSaveDirect.text = "Save to Inbox (Sign in to Import)"
        }
        btnSaveDirect.setOnClickListener {
            val editedName = etName.text.toString().trim().ifEmpty { contact.name }
            val updated = contact.copy(name = editedName)
            if (userId == null) {
                SharedContactInbox.add(this, updated)
                showStatus("Contact saved to RCRM inbox. Open RCRM and sign in to finish importing.")
                return@setOnClickListener
            }
            btnSaveDirect.isEnabled = false
            btnSaveDirect.text = "Saving..."
            SharedContactInbox.importToFirestore(this, db, userId, listOf(updated)) { imported, error ->
                if (error != null) {
                    btnSaveDirect.isEnabled = true
                    btnSaveDirect.text = "Save Contact to RCRM"
                    Toast.makeText(this, error, Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(
                        this,
                        if (imported > 0) "Contact saved to RCRM" else "Contact already in RCRM",
                        Toast.LENGTH_SHORT
                    ).show()
                    finish()
                }
            }
        }
    }

    private fun openInSmartLog(contact: SharedContactItem, mainPhone: String) {
        val userId = auth.currentUser?.uid
        if (userId == null) {
            SharedContactInbox.add(this, contact)
            showStatus("Contact saved to RCRM inbox. Open RCRM and sign in to finish importing.")
            return
        }
        startActivity(Intent(this, SmartLogActivity::class.java).apply {
            putExtra("INCOMING_NUMBER", mainPhone)
            putExtra(SmartLogActivity.EXTRA_SHARED_NAME, contact.name)
            putExtra(SmartLogActivity.EXTRA_SHARED_EMAIL, contact.email)
            putExtra(SmartLogActivity.EXTRA_SHARED_COMPANY, contact.company)
            putExtra(SmartLogActivity.EXTRA_SHARED_TITLE, contact.jobTitle)
            putExtra(SmartLogActivity.EXTRA_SHARED_ADDRESS, contact.address)
            putExtra(SmartLogActivity.EXTRA_SHARED_PHONES_JSON, SharedContactInbox.encodePhones(contact.phones))
        })
        finish()
    }
}
