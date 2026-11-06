package com.nickpulido.rcrm

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth

class SettingsActivity : AppCompatActivity() {

    private val auth = FirebaseAuth.getInstance()
    private lateinit var homeSettingsPanel: HomeSettingsPanel

    private val calendarPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result[Manifest.permission.READ_CALENDAR] == true &&
            result[Manifest.permission.WRITE_CALENDAR] == true
        refreshCalendarStatus()
        if (granted) {
            try {
                startActivity(Intent(this, CalendarSettingsActivity::class.java))
            } catch (_: Exception) {
                Toast.makeText(this, R.string.error_generic, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        refreshNotificationStatus()
    }

    private val contactSyncPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        val userId = auth.currentUser?.uid
        val enabled = userId != null && ContactNoteSyncWorker.hasPermissions(this)
        if (userId != null) {
            getSharedPreferences(ContactNoteSyncWorker.PREFS, MODE_PRIVATE).edit()
                .putBoolean(ContactNoteSyncWorker.enabledKey(userId), enabled).apply()
        }
        homeSettingsPanel.refreshContactSyncSwitch()
        if (enabled) {
            ContactNoteSyncWorker.schedule(this)
        } else {
            Toast.makeText(this, R.string.contact_note_sync_permission_needed, Toast.LENGTH_LONG).show()
        }
    }

    private val backupLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { SettingsDataHelpers.writeBackupToFile(this, it) }
    }

    private val restoreLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { SettingsDataHelpers.readBackupFromFile(this, it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        SectionSwipeNavigation.install(this, 5)

        findViewById<TextView>(R.id.tvSettingsEmail)?.text =
            auth.currentUser?.email ?: getString(R.string.error_session_changed)

        findViewById<View>(R.id.cardCalendarSettings)?.setOnClickListener { openCalendarSettings() }
        findViewById<Button>(R.id.btnLinkCalendar)?.setOnClickListener { openCalendarSettings() }
        findViewById<Button>(R.id.btnNotificationPermission)?.setOnClickListener { openNotificationSettings() }

        findViewById<Button>(R.id.btnCleanDuplicates)?.setOnClickListener {
            Toast.makeText(this, "Open Contacts and use merge tools there.", Toast.LENGTH_LONG).show()
        }
        findViewById<Button>(R.id.btnBulkDeleteContacts)?.setOnClickListener {
            try {
                startActivity(Intent(this, ContactsActivity::class.java))
            } catch (_: Exception) {
                Toast.makeText(this, R.string.error_generic, Toast.LENGTH_SHORT).show()
            }
        }

        homeSettingsPanel = HomeSettingsPanel(
            activity = this,
            root = findViewById(android.R.id.content),
            callbacks = object : HomeSettingsPanel.Callbacks {
                override fun requestBackup() {
                    backupLauncher.launch("RCRM_Backup_${System.currentTimeMillis()}.json")
                }

                override fun requestRestore() {
                    restoreLauncher.launch(arrayOf("application/json", "*/*"))
                }

                override fun onSignOut() {
                    auth.signOut()
                    startActivity(Intent(this@SettingsActivity, LoginActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    })
                    finish()
                }

                override fun onSideKickWorkerFinished() = Unit

                override fun onContactSyncPermissionNeeded() {
                    contactSyncPermissionLauncher.launch(
                        arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
                    )
                }
            }
        )
        homeSettingsPanel.bind()

        refreshCalendarStatus()
        refreshNotificationStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshCalendarStatus()
        refreshNotificationStatus()
        if (::homeSettingsPanel.isInitialized) {
            homeSettingsPanel.refreshContactSyncSwitch()
        }
    }

    private fun openCalendarSettings() {
        if (GoogleCalendarManager.hasCalendarPermissions(this)) {
            try {
                startActivity(Intent(this, CalendarSettingsActivity::class.java))
            } catch (_: Exception) {
                Toast.makeText(this, R.string.error_generic, Toast.LENGTH_SHORT).show()
            }
        } else {
            calendarPermissionLauncher.launch(
                arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
            )
        }
    }

    private fun openNotificationSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            try {
                startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                })
            } catch (_: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = android.net.Uri.fromParts("package", packageName, null)
                    })
                } catch (_: Exception) {
                    Toast.makeText(this, R.string.error_generic, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun refreshCalendarStatus() {
        findViewById<TextView>(R.id.tvCalendarStatus)?.text = GoogleCalendarManager.settingsSummary(this)
        findViewById<Button>(R.id.btnLinkCalendar)?.text = if (GoogleCalendarManager.hasCalendarPermissions(this)) {
            getString(R.string.settings_open_calendar_picker)
        } else {
            getString(R.string.settings_calendar_link)
        }
    }

    private fun refreshNotificationStatus() {
        val notificationsEnabled = NotificationManagerCompat.from(this).areNotificationsEnabled()
        val digestEnabled = getSharedPreferences(AppSettingsController.PREFS_NAME, MODE_PRIVATE).getBoolean(
            DailyFollowUpWorker.KEY_ENABLED,
            DailyFollowUpWorker.DEFAULT_ENABLED
        )
        findViewById<TextView>(R.id.tvNotificationStatus)?.text = when {
            notificationsEnabled && digestEnabled -> getString(R.string.settings_notifications_on)
            notificationsEnabled -> getString(R.string.daily_digest_description)
            else -> getString(R.string.settings_notifications_off)
        }
    }
}
