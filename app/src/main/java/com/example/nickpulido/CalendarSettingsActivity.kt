package com.nickpulido.rcrm

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class CalendarSettingsActivity : AppCompatActivity() {

    private var calendars: List<GoogleSubCalendar> = emptyList()
    private var settingsChanged = false

    private val calendarPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result[Manifest.permission.READ_CALENDAR] == true &&
            result[Manifest.permission.WRITE_CALENDAR] == true
        if (granted) {
            settingsChanged = true
            reloadCalendars()
        } else {
            Toast.makeText(this, R.string.settings_calendar_off, Toast.LENGTH_LONG).show()
            renderAll()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_calendar_settings)

        val root = findViewById<View>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        findViewById<MaterialToolbar>(R.id.toolbarCalendarSettings)?.apply {
            setNavigationOnClickListener { finishWithResult() }
        }

        onBackPressedDispatcher.addCallback(this) { finishWithResult() }

        findViewById<View>(R.id.btnLinkGoogleCalendar)?.setOnClickListener {
            if (GoogleCalendarManager.hasCalendarPermissions(this)) {
                reloadCalendars()
            } else {
                calendarPermissionLauncher.launch(
                    arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
                )
            }
        }

        findViewById<View>(R.id.btnOpenAccountSettings)?.setOnClickListener {
            openAccountSettings()
        }

        findViewById<MaterialSwitch>(R.id.switchHideDuplicateCalendars)?.setOnCheckedChangeListener { _, enabled ->
            GoogleCalendarManager.saveHideDuplicateHolidayCalendars(this, enabled)
            settingsChanged = true
        }

        reloadCalendars()
    }

    override fun onResume() {
        super.onResume()
        if (GoogleCalendarManager.hasCalendarPermissions(this)) {
            reloadCalendars()
        } else {
            renderAll()
        }
    }

    private fun reloadCalendars() {
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                GoogleCalendarManager.getUserCalendars(this@CalendarSettingsActivity)
            }
            if (isDestroyed) return@launch
            calendars = loaded
            GoogleCalendarManager.ensureDefaultGoogleCalendar(this@CalendarSettingsActivity)
            renderAll()
        }
    }

    private fun renderAll() {
        val hasAccess = GoogleCalendarManager.hasCalendarPermissions(this)
        val hasGoogle = hasAccess && GoogleCalendarManager.hasGoogleAccount(this)

        renderLinkSection(hasAccess, hasGoogle)
        renderAccessSection(hasAccess)

        val options = findViewById<View>(R.id.sectionCalendarOptions)
        if (!hasAccess) {
            options?.visibility = View.GONE
            return
        }
        options?.visibility = View.VISIBLE

        findViewById<MaterialSwitch>(R.id.switchHideDuplicateCalendars)?.isChecked =
            GoogleCalendarManager.hideDuplicateHolidayCalendars(this)

        renderDefaultCalendarPicker()
        renderVisibilityList()
    }

    private fun renderLinkSection(hasAccess: Boolean, hasGoogle: Boolean) {
        val status = findViewById<TextView>(R.id.tvCalendarLinkStatus)
        val help = findViewById<TextView>(R.id.tvCalendarLinkHelp)
        val linkButton = findViewById<View>(R.id.btnLinkGoogleCalendar)
        val accountsButton = findViewById<View>(R.id.btnOpenAccountSettings)

        when {
            hasGoogle -> {
                status?.visibility = View.VISIBLE
                status?.text = getString(R.string.settings_calendar_linked)
                status?.setTextColor(ContextCompat.getColor(this, R.color.colorSuccess))
                help?.visibility = View.GONE
                linkButton?.visibility = View.GONE
                accountsButton?.visibility = View.GONE
            }
            hasAccess -> {
                status?.visibility = View.GONE
                help?.visibility = View.VISIBLE
                help?.text = getString(R.string.settings_calendar_google_account_help)
                linkButton?.visibility = View.GONE
                accountsButton?.visibility = View.VISIBLE
            }
            else -> {
                status?.visibility = View.GONE
                help?.visibility = View.VISIBLE
                help?.text = getString(R.string.settings_calendar_link_help)
                linkButton?.visibility = View.VISIBLE
                accountsButton?.visibility = View.GONE
            }
        }
    }

    private fun renderAccessSection(hasAccess: Boolean) {
        val accessView = findViewById<TextView>(R.id.tvCalendarAccessStatus)
        accessView?.text = if (hasAccess) {
            getString(R.string.settings_calendar_access_on)
        } else {
            getString(R.string.settings_calendar_access_off)
        }
        if (hasAccess) {
            accessView?.setTextColor(ContextCompat.getColor(this, R.color.colorSuccess))
        } else {
            accessView?.setTextColor(ContextCompat.getColor(this, R.color.textColorSecondary))
        }
    }

    private fun renderDefaultCalendarPicker() {
        val spinner = findViewById<Spinner>(R.id.spinnerDefaultCalendar)
        val emptyView = findViewById<TextView>(R.id.tvNoWritableCalendars)
        val writable = calendars.filter { it.canWrite }.sortedBy { it.displayName.lowercase(Locale.getDefault()) }

        if (writable.isEmpty()) {
            spinner?.visibility = View.GONE
            emptyView?.visibility = View.VISIBLE
            return
        }
        spinner?.visibility = View.VISIBLE
        emptyView?.visibility = View.GONE

        val labels = writable.map { calendar ->
            if (calendar.accountName.isBlank()) {
                calendar.displayName
            } else {
                "${calendar.displayName} · ${calendar.accountName}"
            }
        }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        spinner?.adapter = adapter

        val selectedId = GoogleCalendarManager.getDefaultCalendarId(this)
            ?: writable.firstOrNull { GoogleCalendarManager.isGoogleAccount(it) }?.id
            ?: writable.first().id
        val selectedIndex = writable.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)
        spinner?.setSelection(selectedIndex, false)

        spinner?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            private var ignoreFirst = true
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (ignoreFirst) {
                    ignoreFirst = false
                    return
                }
                val calendar = writable.getOrNull(position) ?: return
                GoogleCalendarManager.saveDefaultCalendarId(this@CalendarSettingsActivity, calendar.id)
                settingsChanged = true
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun renderVisibilityList() {
        val list = findViewById<LinearLayout>(R.id.calendarVisibilityList)
        val empty = findViewById<TextView>(R.id.tvCalendarListEmpty)
        list?.removeAllViews()

        if (calendars.isEmpty()) {
            empty?.visibility = View.VISIBLE
            return
        }
        empty?.visibility = View.GONE

        val ordered = calendars.sortedWith(
            compareBy<GoogleSubCalendar> { it.displayName.trim().lowercase(Locale.getDefault()) }
                .thenBy { it.accountName.trim().lowercase(Locale.getDefault()) }
        )
        val primaryText = ContextCompat.getColor(this, R.color.textColorPrimary)
        val secondaryText = ContextCompat.getColor(this, R.color.textColorSecondary)

        ordered.forEach { calendar ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, dp(8))
            }
            val dot = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply {
                    marginEnd = dp(10)
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(GoogleCalendarManager.displayColor(calendar.color))
                }
            }
            val labels = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            labels.addView(TextView(this).apply {
                text = calendar.displayName
                setTextColor(primaryText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTypeface(typeface, Typeface.BOLD)
            })
            if (calendar.accountName.isNotBlank()) {
                labels.addView(TextView(this).apply {
                    text = calendar.accountName
                    setTextColor(secondaryText)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                })
            }
            val toggle = MaterialSwitch(this).apply {
                isChecked = GoogleCalendarManager.isCalendarVisible(this@CalendarSettingsActivity, calendar.id, calendars)
                setOnCheckedChangeListener { _, checked ->
                    GoogleCalendarManager.setCalendarVisible(
                        this@CalendarSettingsActivity,
                        calendar.id,
                        checked,
                        calendars
                    )
                    settingsChanged = true
                }
            }
            row.addView(dot)
            row.addView(labels)
            row.addView(toggle)
            list?.addView(row)
        }
    }

    private fun openAccountSettings() {
        try {
            startActivity(Intent(Settings.ACTION_SYNC_SETTINGS))
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_ADD_ACCOUNT))
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = android.net.Uri.fromParts("package", packageName, null)
                })
            }
        }
    }

    private fun finishWithResult() {
        if (settingsChanged) {
            setResult(RESULT_OK)
        }
        finish()
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}
