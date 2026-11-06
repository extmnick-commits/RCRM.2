package com.nickpulido.rcrm

import android.Manifest
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions

class HomeSettingsPanel(
    private val activity: AppCompatActivity,
    private val root: View,
    private val callbacks: Callbacks
) {

    interface Callbacks {
        fun requestBackup()
        fun requestRestore()
        fun onSignOut()
        fun onSideKickWorkerFinished()
        fun onContactSyncPermissionNeeded()
    }

    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()
    private val prefs = activity.getSharedPreferences(AppSettingsController.PREFS_NAME, Context.MODE_PRIVATE)

    private var sideKickHour = prefs.getInt("side_kick_briefing_hour", 8)
    private var sideKickMinute = prefs.getInt("side_kick_briefing_minute", 0)
    private var digestHour = prefs.getInt(DailyFollowUpWorker.KEY_HOUR, 8)
    private var digestMinute = prefs.getInt(DailyFollowUpWorker.KEY_MINUTE, 0)

    fun bind() {
        bindContactSync()
        bindAppearance()
        bindSmsPresets()
        bindAutomation()
        bindIgnoredContacts()
        bindDataMaintenance()
        bindSignOut()
    }

    fun refreshContactSyncSwitch() {
        val syncSwitch = root.findViewById<MaterialSwitch>(R.id.switchContactNoteSync) ?: return
        val syncUserId = auth.currentUser?.uid
        val syncPrefs = activity.getSharedPreferences(ContactNoteSyncWorker.PREFS, Context.MODE_PRIVATE)
        syncSwitch.isChecked = syncUserId != null &&
            syncPrefs.getBoolean(ContactNoteSyncWorker.enabledKey(syncUserId), false)
    }

    private fun bindContactSync() {
        val syncSwitch = root.findViewById<MaterialSwitch>(R.id.switchContactNoteSync) ?: return
        refreshContactSyncSwitch()
        syncSwitch.setOnCheckedChangeListener { _, enabled ->
            val userId = auth.currentUser?.uid
            if (userId == null) {
                syncSwitch.isChecked = false
            } else if (enabled && !ContactNoteSyncWorker.hasPermissions(activity)) {
                callbacks.onContactSyncPermissionNeeded()
            } else {
                val syncPrefs = activity.getSharedPreferences(ContactNoteSyncWorker.PREFS, Context.MODE_PRIVATE)
                syncPrefs.edit().putBoolean(ContactNoteSyncWorker.enabledKey(userId), enabled).apply()
                if (enabled) ContactNoteSyncWorker.schedule(activity) else ContactNoteSyncWorker.cancel(activity)
            }
        }
    }

    private fun bindAppearance() {
        val switchFollowSystem = root.findViewById<MaterialSwitch>(R.id.switchFollowSystemTheme) ?: return
        val switchDarkMode = root.findViewById<MaterialSwitch>(R.id.switchDarkMode) ?: return

        switchFollowSystem.isChecked = prefs.getBoolean("follow_system_theme", true)
        switchDarkMode.isChecked = prefs.getBoolean("dark_mode", false)
        switchDarkMode.isEnabled = !switchFollowSystem.isChecked

        switchFollowSystem.setOnCheckedChangeListener { _, isChecked ->
            switchDarkMode.isEnabled = !isChecked
            AppSettingsController.setThemePrefs(activity, isChecked, switchDarkMode.isChecked)
        }
        switchDarkMode.setOnCheckedChangeListener { _, isChecked ->
            if (!switchFollowSystem.isChecked) {
                AppSettingsController.setThemePrefs(activity, followSystem = false, darkMode = isChecked)
            }
        }
    }

    private fun bindSmsPresets() {
        val etNewPreset = root.findViewById<EditText>(R.id.etNewPreset) ?: return
        val btnInsertName = root.findViewById<Button>(R.id.btnInsertNameTagDialog) ?: return
        val btnDefaultFollowUp = root.findViewById<Button>(R.id.btnDefaultFollowUp) ?: return
        val btnSetAccountDefault = root.findViewById<Button>(R.id.btnSetAccountDefault) ?: return
        val btnAddPreset = root.findViewById<Button>(R.id.btnAddPreset) ?: return
        val listView = root.findViewById<ListView>(R.id.listViewPresets) ?: return

        val prefsPresets = activity.getSharedPreferences(AppSettingsController.PREFS_INTRO, Context.MODE_PRIVATE)
        val presets = (prefsPresets.getStringSet(AppSettingsController.PREFS_KEY, emptySet()) ?: emptySet()).toMutableList()
        val presetAdapter = ArrayAdapter(activity, android.R.layout.simple_list_item_1, presets)
        listView.adapter = presetAdapter

        btnInsertName.setOnClickListener {
            val start = etNewPreset.selectionStart
            etNewPreset.editableText.insert(start, AppSettingsController.NAME_TOKEN)
        }

        btnDefaultFollowUp.setOnClickListener {
            etNewPreset.setText(
                "Hi ${AppSettingsController.NAME_TOKEN}, just following up to see if you had any questions! Looking forward to connecting again."
            )
        }

        btnSetAccountDefault.setOnClickListener {
            val text = etNewPreset.text.toString().trim()
            if (text.isNotEmpty()) {
                val userId = auth.currentUser?.uid ?: return@setOnClickListener
                db.collection("user_settings").document(userId)
                    .set(mapOf("default_intro_sms" to text), SetOptions.merge())
                Toast.makeText(activity, "Account default updated", Toast.LENGTH_SHORT).show()
            }
        }

        btnAddPreset.setOnClickListener {
            val text = etNewPreset.text.toString().trim()
            if (text.isNotEmpty()) {
                presets.add(text)
                prefsPresets.edit { putStringSet(AppSettingsController.PREFS_KEY, presets.toSet()) }
                SettingsDataHelpers.syncPresetsToCloud(presets)
                presetAdapter.notifyDataSetChanged()
                etNewPreset.text.clear()
                Toast.makeText(activity, "Preset Added", Toast.LENGTH_SHORT).show()
            }
        }

        listView.setOnItemLongClickListener { _, _, position, _ ->
            AlertDialog.Builder(activity)
                .setTitle("Delete Preset?")
                .setPositiveButton("Delete") { _, _ ->
                    presets.removeAt(position)
                    prefsPresets.edit { putStringSet(AppSettingsController.PREFS_KEY, presets.toSet()) }
                    SettingsDataHelpers.syncPresetsToCloud(presets)
                    presetAdapter.notifyDataSetChanged()
                }
                .setNegativeButton("Cancel", null)
                .show()
            true
        }
    }

    private fun bindAutomation() {
        val switchSideKick = root.findViewById<MaterialSwitch>(R.id.switchSideKick) ?: return
        val switchDailyDigest = root.findViewById<MaterialSwitch>(R.id.switchDailyDigest) ?: return
        val btnSetSideKickTime = root.findViewById<Button>(R.id.btnSetSideKickTime)
        val btnSetDailyDigestTime = root.findViewById<Button>(R.id.btnSetDailyDigestTime)
        val btnTestDailyDigest = root.findViewById<Button>(R.id.btnTestDailyDigest)
        val progressDailyDigest = root.findViewById<ProgressBar>(R.id.progressDailyDigest)
        val btnForceRunSideKick = root.findViewById<Button>(R.id.btnForceRunSideKick)
        val progressSideKick = root.findViewById<ProgressBar>(R.id.progressSideKick)

        switchSideKick.isChecked = prefs.getBoolean("side_kick_enabled", false)
        switchDailyDigest.isChecked = prefs.getBoolean(
            DailyFollowUpWorker.KEY_ENABLED,
            DailyFollowUpWorker.DEFAULT_ENABLED
        )

        fun refreshSideKickTimeLabel() {
            btnSetSideKickTime?.text = activity.getString(
                R.string.side_kick_briefing_time,
                AppSettingsController.formatTime12Hour(sideKickHour, sideKickMinute)
            )
        }
        fun refreshDigestTimeLabel() {
            btnSetDailyDigestTime?.text = activity.getString(
                R.string.daily_digest_notification_time,
                AppSettingsController.formatTime12Hour(digestHour, digestMinute)
            )
        }

        refreshSideKickTimeLabel()
        refreshDigestTimeLabel()

        switchSideKick.setOnCheckedChangeListener { _, enabled ->
            AppSettingsController.setSideKickEnabled(activity, enabled)
        }
        switchDailyDigest.setOnCheckedChangeListener { _, enabled ->
            AppSettingsController.setDailyDigestEnabled(activity, enabled)
        }

        btnSetSideKickTime?.setOnClickListener {
            showTimePicker(sideKickHour, sideKickMinute) { hour, minute ->
                sideKickHour = hour
                sideKickMinute = minute
                AppSettingsController.persistSideKickTime(activity, hour, minute)
                refreshSideKickTimeLabel()
            }
        }

        root.findViewById<Button>(R.id.btnOpenBriefing)?.setOnClickListener {
            try {
                activity.startActivity(Intent(activity, SideKickActivity::class.java))
            } catch (_: Exception) {
                Toast.makeText(activity, R.string.error_generic, Toast.LENGTH_SHORT).show()
            }
        }

        btnSetDailyDigestTime?.setOnClickListener {
            showTimePicker(digestHour, digestMinute) { hour, minute ->
                digestHour = hour
                digestMinute = minute
                AppSettingsController.persistDigestTime(activity, hour, minute)
                refreshDigestTimeLabel()
            }
        }

        btnTestDailyDigest?.setOnClickListener {
            btnTestDailyDigest.isEnabled = false
            progressDailyDigest?.visibility = View.VISIBLE
            val request = OneTimeWorkRequestBuilder<DailyFollowUpWorker>().build()
            WorkManager.getInstance(activity).enqueue(request)
            WorkManager.getInstance(activity).getWorkInfoByIdLiveData(request.id).observe(activity) { workInfo ->
                if (workInfo != null && workInfo.state.isFinished) {
                    btnTestDailyDigest.isEnabled = true
                    progressDailyDigest?.visibility = View.GONE
                    Toast.makeText(activity, R.string.daily_digest_test_complete, Toast.LENGTH_SHORT).show()
                }
            }
        }

        btnForceRunSideKick?.setOnClickListener {
            btnForceRunSideKick.isEnabled = false
            progressSideKick?.visibility = View.VISIBLE
            val request = OneTimeWorkRequestBuilder<SideKickWorker>().build()
            WorkManager.getInstance(activity).enqueue(request)
            WorkManager.getInstance(activity).getWorkInfoByIdLiveData(request.id).observe(activity) { workInfo ->
                if (workInfo != null && workInfo.state.isFinished) {
                    btnForceRunSideKick.isEnabled = true
                    progressSideKick?.visibility = View.GONE
                    Toast.makeText(activity, "Side-Kick check complete!", Toast.LENGTH_SHORT).show()
                    callbacks.onSideKickWorkerFinished()
                }
            }
        }
    }

    private fun bindIgnoredContacts() {
        val containerIgnoredContacts = root.findViewById<LinearLayout>(R.id.containerIgnoredContacts) ?: return
        val headerIgnoredContacts = root.findViewById<LinearLayout>(R.id.headerIgnoredContacts) ?: return
        val cardIgnoredContacts = root.findViewById<View>(R.id.cardIgnoredContacts) ?: return
        val ivToggleIgnoredContacts = root.findViewById<View>(R.id.ivToggleIgnoredContacts)

        val ignoredContacts = (prefs.getStringSet(AppSettingsController.PREFS_IGNORED, emptySet()) ?: emptySet()).toMutableList()

        headerIgnoredContacts.setOnClickListener {
            if (cardIgnoredContacts.visibility == View.VISIBLE) {
                cardIgnoredContacts.visibility = View.GONE
                ivToggleIgnoredContacts?.animate()?.rotation(0f)?.setDuration(200)?.start()
            } else {
                cardIgnoredContacts.visibility = View.VISIBLE
                ivToggleIgnoredContacts?.animate()?.rotation(180f)?.setDuration(200)?.start()
            }
        }

        fun refreshIgnoredContacts() {
            containerIgnoredContacts.removeAllViews()

            val textColorPrimary = ContextCompat.getColor(activity, R.color.textColorPrimary)
            val textColorSecondary = ContextCompat.getColor(activity, R.color.textColorSecondary)
            val dividerColor = ContextCompat.getColor(activity, R.color.dividerColor)

            if (ignoredContacts.isEmpty()) {
                val tv = TextView(activity).apply {
                    text = activity.getString(R.string.msg_no_ignored_contacts)
                    gravity = android.view.Gravity.CENTER
                    setPadding(16, 16, 16, 16)
                    setTextColor(textColorSecondary)
                }
                containerIgnoredContacts.addView(tv)
            } else {
                for (number in ignoredContacts) {
                    val row = LinearLayout(activity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                        setPadding(0, 16, 0, 16)
                        gravity = android.view.Gravity.CENTER_VERTICAL
                    }
                    val tvNumber = TextView(activity).apply {
                        text = number
                        textSize = 14f
                        setTextColor(textColorPrimary)
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    val btnRemove = ImageButton(activity).apply {
                        setImageResource(android.R.drawable.ic_menu_delete)
                        setBackgroundColor(Color.TRANSPARENT)
                        setColorFilter(Color.parseColor("#DC3545"))
                        setOnClickListener {
                            ignoredContacts.remove(number)
                            prefs.edit { putStringSet(AppSettingsController.PREFS_IGNORED, ignoredContacts.toSet()) }
                            val userId = auth.currentUser?.uid
                            if (userId != null) {
                                db.collection("user_settings").document(userId)
                                    .set(mapOf("ignored_numbers" to ignoredContacts), SetOptions.merge())
                            }
                            refreshIgnoredContacts()
                        }
                    }
                    row.addView(tvNumber)
                    row.addView(btnRemove)

                    val divider = View(activity).apply {
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
                        setBackgroundColor(dividerColor)
                    }

                    containerIgnoredContacts.addView(row)
                    containerIgnoredContacts.addView(divider)
                }
            }
        }
        refreshIgnoredContacts()
    }

    private fun bindDataMaintenance() {
        root.findViewById<Button>(R.id.btnDeleteAllContacts)?.setOnClickListener {
            AlertDialog.Builder(activity)
                .setTitle("Danger Zone")
                .setMessage("Are you sure you want to delete ALL your contacts? This action is permanent and will sync to your account.")
                .setPositiveButton("Delete Everything") { _, _ ->
                    SettingsDataHelpers.performMassDelete(activity)
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        root.findViewById<Button>(R.id.btnExportContacts)?.setOnClickListener {
            SettingsDataHelpers.exportContactsToCsv(activity)
        }

        root.findViewById<Button>(R.id.btnBackupData)?.setOnClickListener {
            AlertDialog.Builder(activity)
                .setTitle("Backup Data")
                .setMessage("Export all your contacts and settings to a JSON file?")
                .setPositiveButton("Backup") { _, _ -> callbacks.requestBackup() }
                .setNegativeButton("Cancel", null)
                .show()
        }

        root.findViewById<Button>(R.id.btnRestoreData)?.setOnClickListener {
            AlertDialog.Builder(activity)
                .setTitle("Restore Data")
                .setMessage("Restore contacts and settings from a JSON file? This will ADD to your current contacts.")
                .setPositiveButton("Choose File") { _, _ -> callbacks.requestRestore() }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun bindSignOut() {
        root.findViewById<Button>(R.id.btnSettingsSignOut)?.setOnClickListener {
            callbacks.onSignOut()
        }
    }

    private fun showTimePicker(
        initialHour: Int,
        initialMinute: Int,
        onTimeSelected: (hour: Int, minute: Int) -> Unit
    ) {
        TimePickerDialog(
            activity,
            R.style.ThemeOverlay_RCRM_DatePicker,
            { _, hour, minute -> onTimeSelected(hour, minute) },
            initialHour,
            initialMinute,
            false
        ).show()
    }
}
