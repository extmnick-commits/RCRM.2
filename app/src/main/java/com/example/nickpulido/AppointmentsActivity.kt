package com.nickpulido.rcrm

import android.Manifest
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

class AppointmentsActivity : AppCompatActivity() {

    private lateinit var db: FirebaseFirestore
    private lateinit var auth: FirebaseAuth
    private lateinit var adapter: AgendaAdapter

    private val rows = mutableListOf<AgendaItem>()
    private val visibleMonth: Calendar = Calendar.getInstance().apply {
        set(Calendar.DAY_OF_MONTH, 1)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    private val monthLabelFormat = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
    private val dayFormat = SimpleDateFormat("EEE, MMM d", Locale.getDefault())
    private val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
    private var loadToken = 0

    private sealed interface PendingCalendarAction {
        data object ShowFilter : PendingCalendarAction
        data class OpenEvent(val item: AgendaItem) : PendingCalendarAction
    }

    private var pendingCalendarAction: PendingCalendarAction? = null

    private val calendarPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result[Manifest.permission.READ_CALENDAR] == true &&
            result[Manifest.permission.WRITE_CALENDAR] == true
        val pending = pendingCalendarAction
        pendingCalendarAction = null
        if (!granted && pending != null) {
            Toast.makeText(
                this,
                "Calendar permission is needed to read and sync Google Calendar.",
                Toast.LENGTH_LONG
            ).show()
        }
        loadAgenda()
        if (!granted) return@registerForActivityResult
        when (pending) {
            PendingCalendarAction.ShowFilter -> showCalendarFilterDialog()
            is PendingCalendarAction.OpenEvent -> openInGoogleCalendar(pending.item)
            null -> Unit
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyAppTheme()
        setContentView(R.layout.activity_appointments)

        db = FirebaseFirestore.getInstance()
        auth = FirebaseAuth.getInstance()

        val listView = findViewById<ListView>(R.id.listViewAppointments)
        adapter = AgendaAdapter(this, rows)
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, position, _ ->
            val item = rows.getOrNull(position) ?: return@setOnItemClickListener
            showEventActions(item)
        }

        findViewById<ImageButton>(R.id.btnBackAppointments).setOnClickListener { finish() }
        findViewById<View>(R.id.btnFilterCalendars).setOnClickListener {
            if (GoogleCalendarManager.hasCalendarPermissions(this)) {
                showCalendarFilterDialog()
            } else {
                pendingCalendarAction = PendingCalendarAction.ShowFilter
                requestCalendarPermissions()
            }
        }
        findViewById<View>(R.id.btnOpenGoogleCalendar).setOnClickListener {
            GoogleCalendarManager.openGoogleCalendarAtTime(this)
        }
        findViewById<View>(R.id.btnPrevMonth).setOnClickListener { shiftMonth(-1) }
        findViewById<View>(R.id.btnNextMonth).setOnClickListener { shiftMonth(1) }
        findViewById<View>(R.id.tvCalendarStatus).setOnClickListener {
            pendingCalendarAction = null
            requestCalendarPermissions()
        }

        if (savedInstanceState == null && !GoogleCalendarManager.hasCalendarPermissions(this)) {
            requestCalendarPermissions()
        }
    }

    override fun onResume() {
        super.onResume()
        loadAgenda()
    }

    private fun requestCalendarPermissions() {
        calendarPermissionLauncher.launch(
            arrayOf(
                Manifest.permission.READ_CALENDAR,
                Manifest.permission.WRITE_CALENDAR
            )
        )
    }

    private fun shiftMonth(delta: Int) {
        visibleMonth.add(Calendar.MONTH, delta)
        loadAgenda()
    }

    private fun monthRange(): Pair<Long, Long> {
        val start = (visibleMonth.clone() as Calendar).apply {
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val end = (start.clone() as Calendar).apply { add(Calendar.MONTH, 1) }
        return start.timeInMillis to end.timeInMillis
    }

    private fun loadAgenda() {
        val token = ++loadToken
        val (startMillis, endMillis) = monthRange()
        findViewById<TextView>(R.id.tvMonthLabel).text = monthLabelFormat.format(visibleMonth.time)

        val progressBar = findViewById<ProgressBar>(R.id.progressAppointments)
        val emptyView = findViewById<TextView>(R.id.tvNoAppointments)
        val statusView = findViewById<TextView>(R.id.tvCalendarStatus)
        progressBar.visibility = View.VISIBLE
        emptyView.visibility = View.GONE
        statusView.visibility = if (GoogleCalendarManager.hasCalendarPermissions(this)) {
            View.GONE
        } else {
            View.VISIBLE
        }

        val currentUserId = auth.currentUser?.uid
        if (currentUserId == null) {
            Toast.makeText(this, "Not authenticated", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        db.collection("leads")
            .whereEqualTo("ownerId", currentUserId)
            .get()
            .addOnSuccessListener { snapshot ->
                if (token != loadToken || isDestroyed) return@addOnSuccessListener
                val leads = snapshot.documents.mapNotNull { doc ->
                    val map = doc.data?.toMutableMap() ?: return@mapNotNull null
                    map["__docId"] = doc.id
                    map
                }
                lifecycleScope.launch(Dispatchers.IO) {
                    val items = assembleAgenda(leads, startMillis, endMillis)
                    withContext(Dispatchers.Main) {
                        if (token != loadToken || isDestroyed) return@withContext
                        progressBar.visibility = View.GONE
                        rows.clear()
                        rows.addAll(items)
                        adapter.notifyDataSetChanged()
                        emptyView.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
                        emptyView.text = "No appointments or calendar events this month"
                    }
                }
            }
            .addOnFailureListener { error ->
                if (token != loadToken || isDestroyed) return@addOnFailureListener
                progressBar.visibility = View.GONE
                emptyView.visibility = View.VISIBLE
                emptyView.text = "Couldn't load appointments: ${error.message}"
            }
    }

    private fun assembleAgenda(
        leads: List<MutableMap<String, Any>>,
        startMillis: Long,
        endMillis: Long
    ): List<AgendaItem> {
        val names = mutableListOf<String>()
        val nameToId = LinkedHashMap<String, String>()
        leads.forEach { lead ->
            val name = (lead["name"] as? String)?.trim().orEmpty()
            val id = lead["__docId"] as? String ?: return@forEach
            val key = name.lowercase(Locale.getDefault())
            if (name.length >= 2 && !nameToId.containsKey(key)) {
                nameToId[key] = id
                names += name
            }
        }

        val crmItems = mutableListOf<AgendaItem>()
        leads.forEach { lead ->
            val appointment = lead["appointmentDate"] as? Timestamp ?: return@forEach
            val start = appointment.toDate().time
            if (start < startMillis || start >= endMillis) return@forEach
            val leadId = lead["__docId"] as? String ?: return@forEach
            val name = (lead["name"] as? String)?.trim().orEmpty().ifEmpty { "Lead" }
            val phone = (lead["phone"] as? String)?.trim().orEmpty()
            val location = (lead["appointmentLocation"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
            val eventId = GoogleCalendarManager.readStoredEventId(lead["googleCalendarEventId"])
            val end = start + 60L * 60L * 1000L
            crmItems += AgendaItem(
                title = name,
                whenLabel = formatWhen(start, end, allDay = false),
                meta = listOfNotNull("RCRM", location).joinToString(" · "),
                startMillis = start,
                endMillis = end,
                color = GoogleCalendarManager.displayColor(0),
                eventId = eventId,
                leadId = leadId,
                leadName = name,
                phone = phone,
                location = location,
                allDay = false
            )
        }

        val googleItems = mutableListOf<AgendaItem>()
        if (GoogleCalendarManager.hasCalendarPermissions(this)) {
            val events = GoogleCalendarManager.getEventsInRange(
                context = this,
                startMillis = startMillis,
                endMillis = endMillis,
                selectedCalendarIds = GoogleCalendarManager.getVisibleCalendarIds(this)
            )
            events.forEach { event ->
                val closeCrm = crmItems.indexOfFirst { item ->
                    val sameEvent = item.eventId != null && item.eventId == event.eventId
                    val sameLead = !event.leadId.isNullOrBlank() && event.leadId == item.leadId
                    (sameEvent || sameLead) && abs(item.startMillis - event.startMillis) <= 15L * 60L * 1000L
                }
                if (closeCrm >= 0) {
                    val current = crmItems[closeCrm]
                    crmItems[closeCrm] = current.copy(
                        color = event.calendarColor,
                        eventId = event.eventId,
                        meta = listOfNotNull(
                            "RCRM",
                            event.calendarDisplayName.takeIf { it.isNotBlank() },
                            current.location?.takeIf { it.isNotBlank() }
                        ).joinToString(" · ")
                    )
                    return@forEach
                }

                val matchedName = if (event.leadId.isNullOrBlank()) {
                    GoogleCalendarManager.matchEventToLeadName(event.title, names)
                } else {
                    null
                }
                val matchedId = event.leadId ?: matchedName?.let { nameToId[it.lowercase(Locale.getDefault())] }
                googleItems += AgendaItem(
                    title = event.title.ifBlank { "(No title)" },
                    whenLabel = formatWhen(event.startMillis, event.endMillis, event.allDay),
                    meta = event.calendarDisplayName.ifBlank { "Calendar" },
                    startMillis = event.startMillis,
                    endMillis = event.endMillis,
                    color = event.calendarColor,
                    eventId = event.eventId,
                    leadId = matchedId,
                    leadName = matchedName,
                    phone = null,
                    location = null,
                    allDay = event.allDay
                )
            }
        }

        return (crmItems + googleItems).sortedBy { it.startMillis }
    }

    private fun showEventActions(item: AgendaItem) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_calendar_event_actions, null)
        val dialog = AlertDialog.Builder(this).setView(dialogView).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        dialogView.findViewById<TextView>(R.id.tvEventActionTitle).text = item.title
        val detail = buildString {
            append(item.whenLabel)
            if (item.meta.isNotBlank()) {
                append("\n")
                append(item.meta)
            }
        }
        dialogView.findViewById<TextView>(R.id.tvEventActionWhen).text = detail

        dialogView.findViewById<View>(R.id.btnViewInGoogleCalendar).setOnClickListener {
            dialog.dismiss()
            openInGoogleCalendar(item)
        }

        val openLead = dialogView.findViewById<View>(R.id.btnOpenLeadInRcrm)
        if (item.leadId.isNullOrBlank()) {
            openLead.visibility = View.GONE
        } else {
            openLead.visibility = View.VISIBLE
            openLead.setOnClickListener {
                dialog.dismiss()
                startActivity(Intent(this, FollowUpActivity::class.java).apply {
                    putExtra("targetLeadId", item.leadId)
                    data = Uri.parse("rcrm://lead/${item.leadId}")
                })
            }
        }
        dialogView.findViewById<View>(R.id.btnCloseEventActions).setOnClickListener { dialog.dismiss() }

        dialog.show()
        val width = (resources.displayMetrics.widthPixels * 0.92f).toInt()
        dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun openInGoogleCalendar(item: AgendaItem) {
        val eventId = item.eventId
        if (eventId != null && eventId > 0L) {
            GoogleCalendarManager.openEventInGoogleCalendar(this, eventId, item.startMillis, item.endMillis)
            return
        }
        val leadId = item.leadId
        if (leadId.isNullOrBlank()) {
            GoogleCalendarManager.openGoogleCalendarAtTime(this, item.startMillis)
            return
        }
        if (!GoogleCalendarManager.hasCalendarPermissions(this)) {
            pendingCalendarAction = PendingCalendarAction.OpenEvent(item)
            requestCalendarPermissions()
            return
        }
        GoogleCalendarManager.syncCrmAppointmentAsync(
            context = this,
            leadId = leadId,
            leadName = item.leadName ?: item.title,
            phone = item.phone,
            startMillis = item.startMillis,
            location = item.location,
            existingEventId = null
        ) { result ->
            if (isDestroyed) return@syncCrmAppointmentAsync
            if (result is GoogleCalendarManager.AppointmentSyncResult.Linked) {
                db.collection("leads").document(leadId).update("googleCalendarEventId", result.eventId)
                GoogleCalendarManager.openEventInGoogleCalendar(
                    this,
                    result.eventId,
                    item.startMillis,
                    item.endMillis
                )
                loadAgenda()
            } else {
                GoogleCalendarManager.openGoogleCalendarAtTime(this, item.startMillis)
            }
        }
    }

    private fun showCalendarFilterDialog() {
        lifecycleScope.launch(Dispatchers.IO) {
            val calendars = GoogleCalendarManager.getUserCalendars(this@AppointmentsActivity)
            withContext(Dispatchers.Main) {
                if (isDestroyed) return@withContext
                if (calendars.isEmpty()) {
                    Toast.makeText(
                        this@AppointmentsActivity,
                        "No calendars found on this device.",
                        Toast.LENGTH_LONG
                    ).show()
                    return@withContext
                }
                presentCalendarFilter(calendars)
            }
        }
    }

    private fun presentCalendarFilter(calendars: List<GoogleSubCalendar>) {
        val savedVisible = GoogleCalendarManager.getVisibleCalendarIds(this)
        val savedDefault = GoogleCalendarManager.getDefaultCalendarId(this)
            ?: calendars.firstOrNull { it.isPrimary && it.canWrite }?.id
            ?: calendars.firstOrNull { it.canWrite }?.id

        val dialogView = layoutInflater.inflate(R.layout.dialog_calendar_picker, null)
        val list = dialogView.findViewById<LinearLayout>(R.id.calendarPickerList)
        val checks = mutableListOf<CheckBox>()
        val radios = mutableListOf<RadioButton>()
        val primaryText = ContextCompat.getColor(this, R.color.textColorPrimary)
        val secondaryText = ContextCompat.getColor(this, R.color.textColorSecondary)

        calendars.forEach { calendar ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(6))
            }
            val check = CheckBox(this).apply {
                isChecked = savedVisible == null || savedVisible.contains(calendar.id)
            }
            val dot = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(12), dp(12)).apply {
                    marginStart = dp(4)
                    marginEnd = dp(10)
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(calendar.color)
                }
            }
            val labels = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val nameView = TextView(this).apply {
                text = calendar.displayName
                setTextColor(primaryText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            }
            val accountView = TextView(this).apply {
                text = if (calendar.canWrite) {
                    calendar.accountName.ifBlank { "On this device" }
                } else {
                    "${calendar.accountName.ifBlank { "On this device" }} · Read only"
                }
                setTextColor(secondaryText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            }
            labels.addView(nameView)
            labels.addView(accountView)
            val radio = RadioButton(this).apply {
                isChecked = calendar.id == savedDefault && calendar.canWrite
                isEnabled = calendar.canWrite
                contentDescription = "Save new RCRM appointments to ${calendar.displayName}"
            }
            checks += check
            radios += radio
            row.addView(check)
            row.addView(dot)
            row.addView(labels)
            row.addView(radio)
            list.addView(row)
        }

        radios.forEachIndexed { index, radio ->
            radio.setOnClickListener {
                if (!radio.isEnabled) return@setOnClickListener
                radios.forEachIndexed { other, button -> button.isChecked = other == index }
            }
        }

        val scroll = dialogView.findViewById<ScrollView>(R.id.calendarPickerScroll)
        val widthSpec = View.MeasureSpec.makeMeasureSpec(
            (resources.displayMetrics.widthPixels * 0.92f).toInt(),
            View.MeasureSpec.AT_MOST
        )
        scroll.measure(widthSpec, View.MeasureSpec.UNSPECIFIED)
        val maxHeight = dp(320)
        if (scroll.measuredHeight > maxHeight) {
            scroll.layoutParams = scroll.layoutParams.apply { height = maxHeight }
        }

        val dialog = AlertDialog.Builder(this).setView(dialogView).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialogView.findViewById<View>(R.id.btnCancelCalendars).setOnClickListener { dialog.dismiss() }
        dialogView.findViewById<View>(R.id.btnApplyCalendars).setOnClickListener {
            val visible = calendars.mapIndexedNotNull { index, calendar ->
                if (checks[index].isChecked) calendar.id else null
            }.toSet()
            val chosen = calendars.getOrNull(radios.indexOfFirst { it.isChecked })
            val defaultId = chosen?.takeIf { it.canWrite }?.id
                ?: calendars.firstOrNull { it.canWrite }?.id
            GoogleCalendarManager.saveVisibleCalendarIds(this, visible)
            if (defaultId != null) {
                GoogleCalendarManager.saveDefaultCalendarId(this, defaultId)
            }
            dialog.dismiss()
            loadAgenda()
        }
        dialog.show()
        val width = (resources.displayMetrics.widthPixels * 0.92f).toInt()
        dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun formatWhen(startMillis: Long, endMillis: Long, allDay: Boolean): String {
        if (allDay) {
            val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = startMillis }
            val local = Calendar.getInstance().apply {
                set(Calendar.YEAR, utc.get(Calendar.YEAR))
                set(Calendar.MONTH, utc.get(Calendar.MONTH))
                set(Calendar.DAY_OF_MONTH, utc.get(Calendar.DAY_OF_MONTH))
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            return "${dayFormat.format(local.time)} · All day"
        }
        val start = Date(startMillis)
        val end = Date(if (endMillis > startMillis) endMillis else startMillis + 60L * 60L * 1000L)
        val startDay = dayFormat.format(start)
        val endDay = dayFormat.format(end)
        return if (startDay == endDay) {
            "$startDay · ${timeFormat.format(start)} – ${timeFormat.format(end)}"
        } else {
            "$startDay ${timeFormat.format(start)} – $endDay ${timeFormat.format(end)}"
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun applyAppTheme() {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val followSystem = prefs.getBoolean("follow_system_theme", true)
        val isDarkMode = prefs.getBoolean("dark_mode", false)
        if (followSystem) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        } else if (isDarkMode) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        } else {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        }
    }

    private data class AgendaItem(
        val title: String,
        val whenLabel: String,
        val meta: String,
        val startMillis: Long,
        val endMillis: Long,
        val color: Int,
        val eventId: Long?,
        val leadId: String?,
        val leadName: String?,
        val phone: String?,
        val location: String?,
        val allDay: Boolean
    )

    private class AgendaAdapter(
        private val context: android.content.Context,
        private val items: List<AgendaItem>
    ) : BaseAdapter() {
        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = items[position].eventId ?: position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView ?: LayoutInflater.from(context)
                .inflate(R.layout.item_calendar_event, parent, false)
            val item = items[position]
            view.findViewById<View>(R.id.viewCalendarColor).background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 8f * context.resources.displayMetrics.density
                setColor(item.color)
            }
            view.findViewById<TextView>(R.id.tvEventTitle).text = item.title
            view.findViewById<TextView>(R.id.tvEventWhen).text = item.whenLabel
            view.findViewById<TextView>(R.id.tvEventMeta).text = item.meta
            return view
        }
    }
}
