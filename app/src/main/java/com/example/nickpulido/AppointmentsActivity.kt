package com.nickpulido.rcrm

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class AppointmentsActivity : AppCompatActivity() {

    private lateinit var db: FirebaseFirestore
    private lateinit var auth: FirebaseAuth
    private lateinit var agendaList: RecyclerView
    private lateinit var dayStrip: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var agendaAdapter: AgendaAdapter
    private lateinit var dayStripAdapter: DayStripAdapter

    private val displayRows = mutableListOf<AgendaRow>()
    private var monthItems: List<AgendaItem> = emptyList()
    private var loadedLeads: List<MutableMap<String, Any>> = emptyList()
    private var dayCells: List<DayCell> = emptyList()

    private val visibleMonth: Calendar = Calendar.getInstance().apply {
        set(Calendar.DAY_OF_MONTH, 1)
        zeroTime(this)
    }
    private val monthLabelFormat = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
    private val dayFormat = SimpleDateFormat("EEE, MMM d", Locale.getDefault())
    private val dowFormat = SimpleDateFormat("EEE", Locale.getDefault())
    private val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
    private val followUpFormat = SimpleDateFormat("MMM dd, yyyy h:mm a", Locale.getDefault())
    private var loadToken = 0
    private var monthFilter = MonthFilter.UPCOMING
    private var sourceFilter = SourceFilter.ALL_EVENTS
    private var selectedDayMillis: Long? = null
    private var snapDayStrip = true
    private var failedSync: AppointmentSync.SyncOperation? = null

    private var contactNameTarget: EditText? = null
    private var contactPhoneTarget: EditText? = null
    private var pendingCalendarSync: AppointmentSync.SyncOperation? = null

    private sealed interface PendingCalendarAction {
        data object ShowFilter : PendingCalendarAction
        data object CreateAppointment : PendingCalendarAction
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
        val waitingSync = pendingCalendarSync
        if (!granted) {
            pendingCalendarSync = null
            if (pending != null || waitingSync != null) {
                Toast.makeText(
                    this,
                    "Calendar permission is needed to read and sync Google Calendar.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        loadAgenda()
        if (!granted) return@registerForActivityResult
        if (waitingSync != null) {
            pendingCalendarSync = null
            pushAppointmentToCalendar(
                leadId = waitingSync.leadId,
                leadName = waitingSync.leadName,
                phone = waitingSync.phone,
                startMillis = waitingSync.intendedStartMillis,
                location = waitingSync.location,
                existingEventId = waitingSync.eventId,
                appointmentId = waitingSync.appointmentId,
                allowInsert = waitingSync.eventId == null,
                soleAppointment = waitingSync.soleAppointment,
                retry = waitingSync
            )
        }
        when (pending) {
            PendingCalendarAction.ShowFilter -> showCalendarFilterDialog()
            PendingCalendarAction.CreateAppointment -> showNewAppointmentDialog()
            is PendingCalendarAction.OpenEvent -> openInGoogleCalendar(pending.item)
            null -> Unit
        }
    }

    private val contactsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            pickContactLauncher.launch(null)
        } else {
            Toast.makeText(
                this,
                "Contacts permission is needed to link a contact.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private val pickContactLauncher = registerForActivityResult(
        ActivityResultContracts.PickContact()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        val (name, phone) = readPickedContact(uri)
        if (name.isNotBlank()) contactNameTarget?.setText(name)
        if (phone.isNotBlank()) contactPhoneTarget?.setText(phone)
        if (name.isBlank() && phone.isBlank()) {
            Toast.makeText(this, "Couldn't read that contact.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyAppTheme()
        setContentView(R.layout.activity_appointments)

        if (savedInstanceState != null) {
            visibleMonth.timeInMillis = savedInstanceState.getLong(STATE_MONTH, visibleMonth.timeInMillis)
            zeroTime(visibleMonth)
            visibleMonth.set(Calendar.DAY_OF_MONTH, 1)
            monthFilter = if (savedInstanceState.getString(STATE_FILTER) == MonthFilter.ALL.name) {
                MonthFilter.ALL
            } else {
                MonthFilter.UPCOMING
            }
            sourceFilter = if (savedInstanceState.getString(STATE_SOURCE) == SourceFilter.CRM_ONLY.name) {
                SourceFilter.CRM_ONLY
            } else {
                SourceFilter.ALL_EVENTS
            }
            if (savedInstanceState.getBoolean(STATE_HAS_DAY)) {
                selectedDayMillis = savedInstanceState.getLong(STATE_DAY)
            }
        }

        db = FirebaseFirestore.getInstance()
        auth = FirebaseAuth.getInstance()

        agendaList = findViewById(R.id.rvAppointments)
        dayStrip = findViewById(R.id.rvDayStrip)
        emptyView = findViewById(R.id.tvNoAppointments)
        progressBar = findViewById(R.id.progressAppointments)

        agendaAdapter = AgendaAdapter()
        agendaList.layoutManager = LinearLayoutManager(this)
        agendaList.adapter = agendaAdapter
        agendaList.itemAnimator = null

        dayStripAdapter = DayStripAdapter()
        dayStrip.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        dayStrip.adapter = dayStripAdapter
        dayStrip.itemAnimator = null

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
        findViewById<View>(R.id.btnJumpToday).setOnClickListener { jumpToToday() }
        findViewById<View>(R.id.btnFilterUpcoming).setOnClickListener {
            monthFilter = MonthFilter.UPCOMING
            selectedDayMillis = null
            styleFilterChips()
            renderAgenda(scroll = true)
        }
        findViewById<View>(R.id.btnFilterAllMonth).setOnClickListener {
            monthFilter = MonthFilter.ALL
            selectedDayMillis = null
            styleFilterChips()
            renderAgenda(scroll = true)
        }
        findViewById<View>(R.id.btnFilterAllEvents).setOnClickListener {
            sourceFilter = SourceFilter.ALL_EVENTS
            styleFilterChips()
            renderAgenda(scroll = false)
        }
        findViewById<View>(R.id.btnFilterCrmOnly).setOnClickListener {
            sourceFilter = SourceFilter.CRM_ONLY
            styleFilterChips()
            renderAgenda(scroll = false)
        }
        findViewById<View>(R.id.tvSyncStatus).setOnClickListener { retryFailedSync() }
        applyWindowInsets()
        findViewById<View>(R.id.btnShowFullMonth).setOnClickListener {
            selectedDayMillis = null
            renderAgenda(scroll = true)
        }
        findViewById<View>(R.id.btnNewAppointment).setOnClickListener { showNewAppointmentDialog() }
        findViewById<View>(R.id.tvCalendarStatus).setOnClickListener {
            pendingCalendarAction = null
            requestCalendarPermissions()
        }

        styleFilterChips()
        bindDayStrip(emptyList(), snap = true)

        if (savedInstanceState == null && !GoogleCalendarManager.hasCalendarPermissions(this)) {
            requestCalendarPermissions()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(STATE_MONTH, visibleMonth.timeInMillis)
        outState.putString(STATE_FILTER, monthFilter.name)
        outState.putString(STATE_SOURCE, sourceFilter.name)
        outState.putBoolean(STATE_HAS_DAY, selectedDayMillis != null)
        selectedDayMillis?.let { outState.putLong(STATE_DAY, it) }
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
        val zone = TimeZone.getDefault()
        val next = AppointmentAgenda.shiftVisibleMonth(currentNav(), delta, zone)
        applyNav(next)
        snapDayStrip = true
        loadAgenda()
    }

    private fun jumpToToday() {
        val zone = TimeZone.getDefault()
        val now = System.currentTimeMillis()
        val sameMonth = AppointmentAgenda.startOfMonthContaining(visibleMonth.timeInMillis, zone) ==
            AppointmentAgenda.startOfMonthContaining(now, zone)
        applyNav(AppointmentAgenda.jumpToToday(currentNav(), now, zone))
        snapDayStrip = true
        styleFilterChips()
        updateSelectedBanner()
        if (sameMonth && monthItems.isNotEmpty()) {
            findViewById<TextView>(R.id.tvMonthLabel).text = monthLabelFormat.format(visibleMonth.time)
            renderAgenda(scroll = true)
        } else {
            loadAgenda()
        }
    }

    private fun currentNav(): AppointmentAgenda.CalendarNav {
        return AppointmentAgenda.CalendarNav(
            monthStartMillis = visibleMonth.timeInMillis,
            selectedDayMillis = selectedDayMillis,
            monthWindow = if (monthFilter == MonthFilter.ALL) {
                AppointmentAgenda.MonthWindow.ALL
            } else {
                AppointmentAgenda.MonthWindow.UPCOMING
            },
            source = if (sourceFilter == SourceFilter.CRM_ONLY) {
                AppointmentAgenda.EventSource.CRM_ONLY
            } else {
                AppointmentAgenda.EventSource.ALL_EVENTS
            }
        )
    }

    private fun applyNav(nav: AppointmentAgenda.CalendarNav) {
        visibleMonth.timeInMillis = nav.monthStartMillis
        zeroTime(visibleMonth)
        visibleMonth.set(Calendar.DAY_OF_MONTH, 1)
        selectedDayMillis = nav.selectedDayMillis
        monthFilter = if (nav.monthWindow == AppointmentAgenda.MonthWindow.ALL) {
            MonthFilter.ALL
        } else {
            MonthFilter.UPCOMING
        }
        sourceFilter = if (nav.source == AppointmentAgenda.EventSource.CRM_ONLY) {
            SourceFilter.CRM_ONLY
        } else {
            SourceFilter.ALL_EVENTS
        }
    }

    private fun applyWindowInsets() {
        val root = findViewById<View>(R.id.appointmentsRoot)
        val actionBar = findViewById<View>(R.id.appointmentActionBar)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(bars.left, bars.top, bars.right, 0)
            actionBar.setPadding(dp(16), dp(8), dp(16), bars.bottom + dp(12))
            insets
        }
        actionBar.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val bottomPad = actionBar.height + dp(8)
            if (::agendaList.isInitialized && agendaList.paddingBottom != bottomPad) {
                agendaList.setPadding(
                    agendaList.paddingLeft,
                    agendaList.paddingTop,
                    agendaList.paddingRight,
                    bottomPad
                )
            }
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun monthRange(): Pair<Long, Long> {
        val start = (visibleMonth.clone() as Calendar).apply {
            set(Calendar.DAY_OF_MONTH, 1)
            zeroTime(this)
        }
        val end = (start.clone() as Calendar).apply { add(Calendar.MONTH, 1) }
        return start.timeInMillis to end.timeInMillis
    }

    private fun loadAgenda() {
        val token = ++loadToken
        val (startMillis, endMillis) = monthRange()
        findViewById<TextView>(R.id.tvMonthLabel).text = monthLabelFormat.format(visibleMonth.time)
        updateSelectedBanner()
        styleFilterChips()
        bindDayStrip(emptyList(), snap = true)

        val statusView = findViewById<TextView>(R.id.tvCalendarStatus)
        progressBar.visibility = View.VISIBLE
        emptyView.visibility = View.GONE
        displayRows.clear()
        agendaAdapter.notifyDataSetChanged()
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
                loadedLeads = leads
                lifecycleScope.launch(Dispatchers.IO) {
                    val items = assembleAgenda(leads, startMillis, endMillis)
                    withContext(Dispatchers.Main) {
                        if (token != loadToken || isDestroyed) return@withContext
                        progressBar.visibility = View.GONE
                        monthItems = items
                        renderAgenda(scroll = true)
                    }
                }
            }
            .addOnFailureListener { error ->
                if (token != loadToken || isDestroyed) return@addOnFailureListener
                progressBar.visibility = View.GONE
                monthItems = emptyList()
                displayRows.clear()
                agendaAdapter.notifyDataSetChanged()
                emptyView.visibility = View.VISIBLE
                emptyView.text = "Couldn't load appointments: ${error.message}"
            }
    }

    private fun renderAgenda(scroll: Boolean) {
        val built = buildDisplayRows(monthItems)
        displayRows.clear()
        displayRows.addAll(built)
        agendaAdapter.notifyDataSetChanged()
        bindDayStrip(monthItems, snapDayStrip)
        snapDayStrip = false
        updateSelectedBanner()
        styleFilterChips()
        emptyView.visibility = if (displayRows.isEmpty()) View.VISIBLE else View.GONE
        emptyView.text = emptyMessage()
        if (scroll) {
            scrollAgendaTo(scrollTargetMillis())
        }
    }

    private fun emptyMessage(): String {
        return AppointmentAgenda.emptyMessage(
            monthWindow = if (monthFilter == MonthFilter.ALL) {
                AppointmentAgenda.MonthWindow.ALL
            } else {
                AppointmentAgenda.MonthWindow.UPCOMING
            },
            source = if (sourceFilter == SourceFilter.CRM_ONLY) {
                AppointmentAgenda.EventSource.CRM_ONLY
            } else {
                AppointmentAgenda.EventSource.ALL_EVENTS
            },
            daySelected = selectedDayMillis != null
        )
    }

    private suspend fun assembleAgenda(
        leads: List<MutableMap<String, Any>>,
        startMillis: Long,
        endMillis: Long
    ): List<AgendaItem> {
        val zone = TimeZone.getDefault()
        val now = System.currentTimeMillis()
        val names = mutableListOf<String>()
        val nameToId = LinkedHashMap<String, String>()
        val appointments = mutableListOf<AppointmentAgenda.CrmAppointmentRef>()
        val migrations = mutableListOf<Pair<String, List<AppointmentAgenda.AppointmentRecord>>>()
        leads.forEach { lead ->
            val name = (lead["name"] as? String)?.trim().orEmpty()
            val leadId = lead["__docId"] as? String ?: return@forEach
            val ownerId = lead["ownerId"] as? String
            val key = name.lowercase(Locale.getDefault())
            if (name.length >= 2 && !nameToId.containsKey(key)) {
                nameToId[key] = leadId
                names += name
            }
            val parsed = LeadAppointmentSync.readRecords(lead["appointments"])
            val legacyStart = (lead["appointmentDate"] as? Timestamp)?.toDate()?.time
            val legacyLocation = (lead["appointmentLocation"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
            val legacyEventId = GoogleCalendarManager.readStoredEventId(lead["googleCalendarEventId"])
            val migrated = AppointmentAgenda.migrateAppointments(
                leadId = leadId,
                existing = parsed,
                legacyStartMillis = legacyStart,
                legacyLocation = legacyLocation,
                legacyEventId = legacyEventId,
                nowMillis = now
            )
            if (migrated != parsed && AppointmentAgenda.ownerCanEdit(ownerId, auth.currentUser?.uid)) {
                migrations += leadId to migrated
            }
            val phone = (lead["phone"] as? String)?.trim().orEmpty()
            migrated.forEach { record ->
                appointments += AppointmentAgenda.CrmAppointmentRef(
                    leadId = leadId,
                    ownerId = ownerId,
                    appointmentId = record.id,
                    leadName = name.ifBlank { "Lead" },
                    phone = phone,
                    location = record.location,
                    startMillis = record.startMillis,
                    eventId = record.eventId,
                    status = record.status,
                    originalInstanceTime = record.originalInstanceTime
                )
            }
        }
        if (migrations.isNotEmpty()) {
            val migratedLeadIds = migrations.map { it.first }
            withContext(Dispatchers.Main) {
                if (!isDestroyed) persistMigrations(migratedLeadIds, now)
            }
        }

        val events = mutableListOf<SyncedCalendarEvent>()
        if (GoogleCalendarManager.hasCalendarPermissions(this)) {
            events += GoogleCalendarManager.getEventsInRange(
                context = this,
                startMillis = startMillis,
                endMillis = endMillis,
                selectedCalendarIds = GoogleCalendarManager.getVisibleCalendarIds(this)
            )
            val seenIds = events.map { it.eventId }.toMutableSet()
            appointments.mapNotNull { it.eventId }.distinct().forEach { eventId ->
                if (eventId in seenIds) return@forEach
                val lookedUp = GoogleCalendarManager.lookupEvent(this, eventId) ?: return@forEach
                if (lookedUp.recurring && lookedUp.originalInstanceTime == null) return@forEach
                events += lookedUp
                seenIds += lookedUp.eventId
            }
        }
        val suggestions = LinkedHashMap<Long, String>()
        events.forEach { event ->
            if (!event.leadId.isNullOrBlank() || !event.explicitAppointmentId.isNullOrBlank()) return@forEach
            val matchedName = GoogleCalendarManager.matchEventToLeadName(event.title, names) ?: return@forEach
            val matchedId = nameToId[matchedName.lowercase(Locale.getDefault())] ?: return@forEach
            suggestions[event.eventId] = matchedId
        }
        val reconciled = AppointmentAgenda.reconcile(
            appointments = appointments,
            events = events.map { it.toCalendarRef() },
            suggestedLeadIdsByEventId = suggestions,
            rangeStart = startMillis,
            rangeEnd = endMillis,
            zone = zone
        )
        return reconciled.map { entry -> entry.toAgendaItem() }
    }

    private fun persistMigrations(
        leadIds: List<String>,
        nowMillis: Long,
        onSaved: () -> Unit = {}
    ) {
        leadIds.distinct().forEach { leadId ->
            LeadAppointmentWriter.commit(
                db = db,
                leadId = leadId,
                mutationFor = { _, start, location, eventId ->
                    AppointmentSync.AppointmentMutation.Migrate(
                        legacyStartMillis = start,
                        legacyLocation = location,
                        legacyEventId = eventId,
                        nowMillis = nowMillis
                    )
                },
                onSuccess = { if (!isDestroyed) onSaved() },
                onFailure = { error ->
                    if (isDestroyed) return@commit
                    showSyncFailure(
                        AppointmentSync.operation(
                            kind = AppointmentSync.SyncOperation.Kind.MIGRATE_HISTORY,
                            leadId = leadId,
                            message = "Couldn't save appointment history. Tap to retry."
                        )
                    )
                    android.util.Log.w("AppointmentsActivity", "Appointment migration failed", error)
                }
            )
        }
    }

    private fun buildDisplayRows(items: List<AgendaItem>): List<AgendaRow> {
        val zone = TimeZone.getDefault()
        val todayStart = startOfLocalDay(System.currentTimeMillis())
        val filtered = AppointmentAgenda.visibleEntries(
            entries = items.map { it.toEntry() },
            monthWindow = if (monthFilter == MonthFilter.ALL) {
                AppointmentAgenda.MonthWindow.ALL
            } else {
                AppointmentAgenda.MonthWindow.UPCOMING
            },
            source = if (sourceFilter == SourceFilter.CRM_ONLY) {
                AppointmentAgenda.EventSource.CRM_ONLY
            } else {
                AppointmentAgenda.EventSource.ALL_EVENTS
            },
            selectedDayMillis = selectedDayMillis,
            todayStartMillis = todayStart,
            zone = zone
        )
        val visibleKeys = filtered.map { entryKey(it) }.toSet()
        val shown = items.filter { entryKey(it.toEntry()) in visibleKeys }
        val rows = mutableListOf<AgendaRow>()
        shown.groupBy { eventDayStart(it) }.toSortedMap().forEach { (day, dayItems) ->
            val isToday = day == todayStart
            val label = if (isToday) {
                "TODAY • ${dayFormat.format(Date(day))}"
            } else {
                dayFormat.format(Date(day))
            }
            rows += AgendaRow.DayHeader(day, label, isToday)
            dayItems.sortedBy { it.startMillis }.forEach { rows += AgendaRow.Event(it) }
        }
        return rows
    }

    private fun scrollTargetMillis(): Long? {
        selectedDayMillis?.let { return it }
        val (monthStart, monthEnd) = monthRange()
        val today = startOfLocalDay(System.currentTimeMillis())
        val days = displayRows.mapNotNull { row ->
            (row as? AgendaRow.DayHeader)?.dayStartMillis
        }
        if (days.isEmpty()) return null
        if (today < monthStart || today >= monthEnd) return days.first()
        if (days.any { it == today }) return today
        return days.firstOrNull { it > today } ?: days.last()
    }

    private fun scrollAgendaTo(dayMillis: Long?) {
        if (dayMillis == null || displayRows.isEmpty()) return
        val index = displayRows.indexOfFirst { row ->
            when (row) {
                is AgendaRow.DayHeader -> row.dayStartMillis == dayMillis
                is AgendaRow.Event -> eventDayStart(row.item) == dayMillis
            }
        }
        if (index >= 0) {
            (agendaList.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(index, 0)
        }
    }

    private fun bindDayStrip(items: List<AgendaItem>, snap: Boolean) {
        val dotted = if (sourceFilter == SourceFilter.CRM_ONLY) items.filter { it.explicitCrm } else items
        dayCells = buildDayCells(dotted)
        dayStripAdapter.submit(dayCells)
        if (!snap) return
        val today = startOfLocalDay(System.currentTimeMillis())
        val index = AppointmentAgenda.dayStripAnchorIndex(
            dayCells.map { it.dayStartMillis },
            today,
            selectedDayMillis
        )
        dayStrip.stopScroll()
        dayStrip.post {
            if (isDestroyed || dayCells.isEmpty()) return@post
            (dayStrip.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(index, dp(12))
        }
    }

    private fun buildDayCells(items: List<AgendaItem>): List<DayCell> {
        val today = startOfLocalDay(System.currentTimeMillis())
        val cursor = (visibleMonth.clone() as Calendar).apply {
            set(Calendar.DAY_OF_MONTH, 1)
            zeroTime(this)
        }
        val daysInMonth = cursor.getActualMaximum(Calendar.DAY_OF_MONTH)
        val byDay = items.groupBy { eventDayStart(it) }
        return (1..daysInMonth).map { day ->
            cursor.set(Calendar.DAY_OF_MONTH, day)
            val start = cursor.timeInMillis
            DayCell(
                dayStartMillis = start,
                dowLabel = dowFormat.format(cursor.time),
                dayNumber = day.toString(),
                isToday = start == today,
                selected = start == selectedDayMillis,
                dotColors = byDay[start].orEmpty().map { it.color }.distinct().take(3)
            )
        }
    }

    private fun onDayStripClicked(dayStart: Long) {
        selectedDayMillis = dayStart
        snapDayStrip = true
        renderAgenda(scroll = true)
    }

    private fun styleFilterChips() {
        val primary = ContextCompat.getColor(this, R.color.textColorPrimary)
        val secondary = ContextCompat.getColor(this, R.color.textColorSecondary)
        styleChip(findViewById(R.id.btnFilterUpcoming), monthFilter == MonthFilter.UPCOMING, primary, secondary)
        styleChip(findViewById(R.id.btnFilterAllMonth), monthFilter == MonthFilter.ALL, primary, secondary)
        styleChip(findViewById(R.id.btnFilterAllEvents), sourceFilter == SourceFilter.ALL_EVENTS, primary, secondary)
        styleChip(findViewById(R.id.btnFilterCrmOnly), sourceFilter == SourceFilter.CRM_ONLY, primary, secondary)
    }

    private fun styleChip(view: TextView, selected: Boolean, primary: Int, secondary: Int) {
        if (selected) {
            view.setBackgroundResource(R.drawable.bg_neon_cyan_button)
            view.setTextColor(primary)
        } else {
            view.setBackgroundResource(R.drawable.bg_frosted_pill_input)
            view.setTextColor(secondary)
        }
    }

    private fun updateSelectedBanner() {
        val row = findViewById<View>(R.id.rowSelectedDay)
        val label = findViewById<TextView>(R.id.tvSelectedDayLabel)
        val day = selectedDayMillis
        if (day == null) {
            row.visibility = View.GONE
            return
        }
        row.visibility = View.VISIBLE
        label.text = dayFormat.format(Date(day))
    }

    private fun showEventActions(item: AgendaItem) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_calendar_event_actions, null)
        val dialog = glassDialog(dialogView)

        dialogView.findViewById<TextView>(R.id.tvEventActionTitle).text = item.title
        dialogView.findViewById<TextView>(R.id.tvEventActionWhen).text = item.whenLabel
        val detailView = dialogView.findViewById<TextView>(R.id.tvEventActionDetail)
        if (item.detailText.isBlank()) {
            detailView.visibility = View.GONE
        } else {
            detailView.visibility = View.VISIBLE
            detailView.text = item.detailText
        }

        dialogView.findViewById<View>(R.id.btnViewInGoogleCalendar).setOnClickListener {
            dialog.dismiss()
            openInGoogleCalendar(item)
        }

        val openLead = dialogView.findViewById<View>(R.id.btnOpenLeadInRcrm)
        val saveOrLink = dialogView.findViewById<View>(R.id.btnSaveOrLinkLead)
        if (!item.explicitCrm || item.leadId.isNullOrBlank()) {
            openLead.visibility = View.GONE
            saveOrLink.visibility = View.VISIBLE
            saveOrLink.setOnClickListener {
                dialog.dismiss()
                showSaveLeadDialog(item, thenNote = false)
            }
        } else {
            openLead.visibility = View.VISIBLE
            saveOrLink.visibility = View.GONE
            openLead.setOnClickListener {
                dialog.dismiss()
                openLeadInRcrm(item.leadId)
            }
        }

        val outcomes = dialogView.findViewById<View>(R.id.outcomeActions)
        val canRecordOutcome = item.explicitCrm && !item.appointmentId.isNullOrBlank() && !item.leadId.isNullOrBlank()
        outcomes.visibility = if (canRecordOutcome) View.VISIBLE else View.GONE
        if (canRecordOutcome) {
            dialogView.findViewById<View>(R.id.btnOutcomeCompleted).setOnClickListener {
                dialog.dismiss()
                recordOutcome(item, AppointmentAgenda.Outcome.COMPLETED, null)
            }
            dialogView.findViewById<View>(R.id.btnOutcomeNoShow).setOnClickListener {
                dialog.dismiss()
                recordOutcome(item, AppointmentAgenda.Outcome.NO_SHOW, null)
            }
            dialogView.findViewById<View>(R.id.btnOutcomeCanceled).setOnClickListener {
                dialog.dismiss()
                recordOutcome(item, AppointmentAgenda.Outcome.CANCELED, null)
            }
            dialogView.findViewById<View>(R.id.btnOutcomeReschedule).setOnClickListener {
                dialog.dismiss()
                pickFollowUpDate { millis ->
                    recordOutcome(item, null, millis)
                }
            }
        }

        dialogView.findViewById<View>(R.id.btnQuickNoteFollowUp).setOnClickListener {
            dialog.dismiss()
            if (item.leadId.isNullOrBlank()) {
                showSaveLeadDialog(item, thenNote = true)
            } else {
                showQuickNoteDialog(item)
            }
        }
        dialogView.findViewById<View>(R.id.btnCloseEventActions).setOnClickListener { dialog.dismiss() }

        dialog.show()
        sizeDialog(dialog)
    }

    private fun showSaveLeadDialog(item: AgendaItem, thenNote: Boolean) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_save_calendar_lead, null)
        val dialog = glassDialog(dialogView)
        val nameInput = dialogView.findViewById<EditText>(R.id.etSaveLeadName)
        val phoneInput = dialogView.findViewById<EditText>(R.id.etSaveLeadPhone)
        nameInput.setText(suggestedLeadName(item.title))
        if (thenNote) {
            dialogView.findViewById<TextView>(R.id.tvSaveLeadSubtitle).text =
                "Save this event as a lead first, then add the note or follow-up."
        }
        contactNameTarget = nameInput
        contactPhoneTarget = phoneInput
        dialog.setOnDismissListener {
            if (contactNameTarget === nameInput) {
                contactNameTarget = null
                contactPhoneTarget = null
            }
        }

        dialogView.findViewById<View>(R.id.btnPickDeviceContact).setOnClickListener {
            launchContactPicker()
        }
        dialogView.findViewById<View>(R.id.btnCancelSaveLead).setOnClickListener { dialog.dismiss() }
        dialogView.findViewById<View>(R.id.btnLinkExistingLead).setOnClickListener {
            dialog.dismiss()
            showLinkExistingLeadDialog(item, thenNote)
        }
        val saveButton = dialogView.findViewById<View>(R.id.btnSaveNewLead)
        saveButton.setOnClickListener {
            val name = nameInput.text.toString().trim()
            if (name.isEmpty()) {
                Toast.makeText(this, "Enter a lead name.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            saveButton.isEnabled = false
            val phone = phoneInput.text.toString().trim()
            createLead(
                name = name,
                phone = phone,
                startMillis = item.startMillis,
                location = item.location,
                eventId = item.eventId,
                note = "Saved from Google Calendar: ${item.title}",
                onCreated = { leadId ->
                    dialog.dismiss()
                    val linked = item.copy(leadId = leadId, leadName = name, phone = phone)
                    Toast.makeText(this, "Lead saved", Toast.LENGTH_SHORT).show()
                    if (thenNote) showQuickNoteDialog(linked) else loadAgenda()
                },
                onFailed = { saveButton.isEnabled = true }
            )
        }

        dialog.show()
        sizeDialog(dialog)
    }

    private fun showLinkExistingLeadDialog(item: AgendaItem, thenNote: Boolean) {
        val leads = leadChoices()
        if (leads.isEmpty()) {
            Toast.makeText(this, "No RCRM leads to link yet.", Toast.LENGTH_LONG).show()
            return
        }
        val dialogView = layoutInflater.inflate(R.layout.dialog_link_existing_lead, null)
        val dialog = glassDialog(dialogView)
        val shown = leads.toMutableList()
        val list = dialogView.findViewById<RecyclerView>(R.id.rvLinkLeads)
        val adapter = LinkLeadAdapter(shown) { choice ->
            dialog.dismiss()
            linkEventToLead(
                leadId = choice.id,
                leadName = choice.name,
                phone = choice.phone,
                item = item,
                note = "Linked calendar event: ${item.title}",
                onDone = {
                    val linked = item.copy(leadId = choice.id, leadName = choice.name, phone = choice.phone)
                    Toast.makeText(this, "Linked to ${choice.name}", Toast.LENGTH_SHORT).show()
                    if (thenNote) showQuickNoteDialog(linked) else loadAgenda()
                }
            )
        }
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        dialogView.findViewById<EditText>(R.id.etLinkLeadFilter).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString()?.trim()?.lowercase(Locale.getDefault()).orEmpty()
                shown.clear()
                shown.addAll(
                    leads.filter { lead ->
                        query.isEmpty() ||
                            lead.name.lowercase(Locale.getDefault()).contains(query) ||
                            lead.phone.lowercase(Locale.getDefault()).contains(query)
                    }
                )
                adapter.notifyDataSetChanged()
            }
        })
        dialogView.findViewById<View>(R.id.btnCancelLinkLead).setOnClickListener { dialog.dismiss() }
        dialog.show()
        sizeDialog(dialog)
    }

    private fun showQuickNoteDialog(item: AgendaItem) {
        val leadId = item.leadId
        if (leadId.isNullOrBlank()) {
            showSaveLeadDialog(item, thenNote = true)
            return
        }
        val dialogView = layoutInflater.inflate(R.layout.dialog_quick_note_followup, null)
        val dialog = glassDialog(dialogView)
        dialogView.findViewById<TextView>(R.id.tvQuickNoteEvent).text = "${item.title}\n${item.whenLabel}"
        val noteInput = dialogView.findViewById<EditText>(R.id.etQuickNote)
        val followUpLabel = dialogView.findViewById<TextView>(R.id.tvFollowUpSelection)
        var followUpMillis: Long? = null
        followUpLabel.setOnClickListener {
            if (followUpMillis != null) {
                followUpMillis = null
                followUpLabel.text = "No follow-up change"
            }
        }
        dialogView.findViewById<View>(R.id.btnSetFollowUp).setOnClickListener {
            pickFollowUpDate { millis ->
                followUpMillis = millis
                followUpLabel.text = "Follow-up: ${followUpFormat.format(Date(millis))}"
            }
        }
        dialogView.findViewById<View>(R.id.btnCancelQuickNote).setOnClickListener { dialog.dismiss() }
        val saveButton = dialogView.findViewById<View>(R.id.btnSaveQuickNote)
        saveButton.setOnClickListener {
            val note = noteInput.text.toString().trim()
            val followUp = followUpMillis
            if (note.isEmpty() && followUp == null) {
                Toast.makeText(this, "Add a note or set a follow-up.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            saveButton.isEnabled = false
            saveQuickNote(
                leadId = leadId,
                leadName = item.leadName ?: item.title,
                phone = item.phone.orEmpty(),
                note = note,
                followUpMillis = followUp,
                onDone = { dialog.dismiss() },
                onFailed = { saveButton.isEnabled = true }
            )
        }
        dialog.show()
        sizeDialog(dialog)
    }

    private fun showNewAppointmentDialog() {
        val prefs = getSharedPreferences("user_settings", MODE_PRIVATE)
        var officeAddress = prefs.getString("default_office_address", "") ?: ""
        val dialogView = layoutInflater.inflate(R.layout.dialog_appointment, null)
        val root = dialogView as LinearLayout
        (root.getChildAt(0) as? TextView)?.text = "+ New Appointment"
        val nameInput = pillField("Lead name", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        val phoneInput = pillField("Phone (optional)", InputType.TYPE_CLASS_PHONE)
        root.addView(nameInput, 1)
        root.addView(phoneInput, 2)

        val dialog = glassDialog(dialogView)
        val btnApptDate = dialogView.findViewById<Button>(R.id.btnApptDate)
        val btnApptTime = dialogView.findViewById<Button>(R.id.btnApptTime)
        val tvSelectedDateTime = dialogView.findViewById<TextView>(R.id.tvSelectedDateTime)
        val rgApptLocation = dialogView.findViewById<RadioGroup>(R.id.rgApptLocation)
        val rbOffice = dialogView.findViewById<RadioButton>(R.id.rbOffice)
        val llOfficeAddressConfig = dialogView.findViewById<LinearLayout>(R.id.llOfficeAddressConfig)
        val tvCurrentOffice = dialogView.findViewById<TextView>(R.id.tvCurrentOffice)
        val btnEditOffice = dialogView.findViewById<Button>(R.id.btnEditOffice)
        val tilCustomAddress = dialogView.findViewById<View>(R.id.tilCustomAddress)
        val etCustomAddress = dialogView.findViewById<EditText>(R.id.etCustomAddress)
        val btnCancelAppt = dialogView.findViewById<Button>(R.id.btnCancelAppt)
        val btnSaveAppt = dialogView.findViewById<Button>(R.id.btnSaveAppt)

        var tempCal: Calendar? = defaultAppointmentCalendar()
        val sdfDate = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
        val sdfTime = SimpleDateFormat("h:mm a", Locale.getDefault())

        fun updateDateTimeUI() {
            val selected = tempCal
            if (selected != null) {
                tvSelectedDateTime.text = "${sdfDate.format(selected.time)} at ${sdfTime.format(selected.time)}"
            } else {
                tvSelectedDateTime.text = "No date/time selected"
            }
        }

        fun updateOfficeUI() {
            tvCurrentOffice.text = officeAddress.ifBlank { "No address set" }
        }

        updateDateTimeUI()
        updateOfficeUI()
        rbOffice.isChecked = true
        llOfficeAddressConfig.visibility = View.VISIBLE
        tilCustomAddress.visibility = View.GONE

        rgApptLocation.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId == R.id.rbOffice) {
                llOfficeAddressConfig.visibility = View.VISIBLE
                tilCustomAddress.visibility = View.GONE
            } else {
                llOfficeAddressConfig.visibility = View.GONE
                tilCustomAddress.visibility = View.VISIBLE
            }
        }

        btnEditOffice.setOnClickListener {
            val input = EditText(this).apply {
                setText(officeAddress)
                setPadding(dp(16), dp(12), dp(16), dp(12))
            }
            AlertDialog.Builder(this)
                .setTitle("Default Office Address")
                .setView(input)
                .setPositiveButton("Save") { _, _ ->
                    officeAddress = input.text.toString().trim()
                    prefs.edit().putString("default_office_address", officeAddress).apply()
                    updateOfficeUI()
                    auth.currentUser?.uid?.let { userId ->
                        db.collection("user_settings").document(userId)
                            .set(mapOf("default_office_address" to officeAddress), SetOptions.merge())
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        btnApptDate.setOnClickListener {
            val cal = tempCal ?: Calendar.getInstance()
            DatePickerDialog(
                this,
                android.R.style.Theme_DeviceDefault_Dialog_Alert,
                { _, year, month, day ->
                    if (tempCal == null) tempCal = Calendar.getInstance()
                    tempCal?.set(year, month, day)
                    updateDateTimeUI()
                },
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH),
                cal.get(Calendar.DAY_OF_MONTH)
            ).show()
        }

        btnApptTime.setOnClickListener {
            val cal = tempCal ?: Calendar.getInstance()
            TimePickerDialog(
                this,
                android.R.style.Theme_DeviceDefault_Dialog_Alert,
                { _, hour, minute ->
                    if (tempCal == null) tempCal = Calendar.getInstance()
                    tempCal?.set(Calendar.HOUR_OF_DAY, hour)
                    tempCal?.set(Calendar.MINUTE, minute)
                    updateDateTimeUI()
                },
                cal.get(Calendar.HOUR_OF_DAY),
                cal.get(Calendar.MINUTE),
                false
            ).show()
        }

        btnCancelAppt.setOnClickListener { dialog.dismiss() }
        btnSaveAppt.setOnClickListener {
            val name = nameInput.text.toString().trim()
            if (name.isEmpty()) {
                Toast.makeText(this, "Enter a lead name.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val selected = tempCal
            if (selected == null) {
                Toast.makeText(this, "Please select Date & Time", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (rbOffice.isChecked && officeAddress.isEmpty()) {
                Toast.makeText(this, "Please set a default Office Address first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val location = if (rbOffice.isChecked) officeAddress else etCustomAddress.text.toString().trim()
            if (location.isEmpty()) {
                Toast.makeText(this, "Please provide a location", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            btnSaveAppt.isEnabled = false
            val startMillis = selected.timeInMillis
            val phone = phoneInput.text.toString().trim()
            createLead(
                name = name,
                phone = phone,
                startMillis = startMillis,
                location = location,
                eventId = null,
                note = "Appointment created from calendar",
                onCreated = {
                    dialog.dismiss()
                    focusOnAppointment(startMillis)
                    if (GoogleCalendarManager.hasCalendarPermissions(this)) {
                        Toast.makeText(this, "Appointment saved", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(
                            this,
                            "Appointment saved in RCRM. Allow calendar access to add it to Google Calendar.",
                            Toast.LENGTH_LONG
                        ).show()
                        requestCalendarPermissions()
                    }
                },
                onFailed = { btnSaveAppt.isEnabled = true }
            )
        }

        auth.currentUser?.uid?.let { userId ->
            db.collection("user_settings").document(userId).get()
                .addOnSuccessListener { doc ->
                    if (!dialog.isShowing || isDestroyed) return@addOnSuccessListener
                    val remote = doc.getString("default_office_address")
                    if (!remote.isNullOrBlank()) {
                        officeAddress = remote
                        prefs.edit().putString("default_office_address", remote).apply()
                        updateOfficeUI()
                    }
                }
        }

        dialog.show()
        sizeDialog(dialog)
    }

    private fun createLead(
        name: String,
        phone: String,
        startMillis: Long,
        location: String?,
        eventId: Long?,
        note: String,
        onCreated: (String) -> Unit,
        onFailed: () -> Unit
    ) {
        val userId = auth.currentUser?.uid
        if (userId == null) {
            Toast.makeText(this, "Not authenticated", Toast.LENGTH_SHORT).show()
            onFailed()
            return
        }
        val existing = findLeadByPhone(phone)
        if (existing != null) {
            linkEventToLead(
                leadId = existing.id,
                leadName = existing.name,
                phone = existing.phone.ifBlank { phone },
                item = blankAgendaItem(
                    title = name,
                    startMillis = startMillis,
                    eventId = eventId,
                    leadId = existing.id,
                    leadName = existing.name,
                    phone = existing.phone.ifBlank { phone },
                    location = location
                ),
                note = note,
                onDone = { onCreated(existing.id) },
                onFailed = onFailed
            )
            return
        }

        val now = System.currentTimeMillis()
        val record = AppointmentAgenda.AppointmentRecord(
            id = AppointmentAgenda.newAppointmentId(),
            startMillis = startMillis,
            location = location?.trim()?.takeIf { it.isNotEmpty() },
            eventId = eventId?.takeIf { it > 0L },
            status = AppointmentAgenda.AppointmentStatus.SCHEDULED,
            originalInstanceTime = null,
            updatedAtMillis = now
        )
        val data = hashMapOf<String, Any>(
            "name" to name,
            "phone" to phone,
            "email" to "",
            "address" to "",
            "company" to "",
            "jobTitle" to "",
            "category" to getString(R.string.category_prospect),
            "targetMarket" to "",
            "notes" to NoteUtils.formatNewNote(note),
            "timestamp" to Timestamp.now(),
            "ownerId" to userId,
            "source" to "google_calendar"
        )
        LeadAppointmentSync.fields(listOf(record), now, forUpdate = false).forEach { (key, value) ->
            if (value != null) data[key] = value
        }

        db.collection("leads").add(data)
            .addOnSuccessListener { ref ->
                applyReminder(record, phone.ifBlank { ref.id }, name)
                pushAppointmentToCalendar(
                    leadId = ref.id,
                    leadName = name,
                    phone = phone,
                    startMillis = startMillis,
                    location = location,
                    existingEventId = eventId,
                    appointmentId = record.id,
                    soleAppointment = true,
                    retry = calendarRetry(
                        leadId = ref.id,
                        appointmentId = record.id,
                        leadName = name,
                        phone = phone,
                        startMillis = startMillis,
                        location = location,
                        eventId = eventId,
                        baseUpdatedAtMillis = record.updatedAtMillis,
                        soleAppointment = true
                    )
                )
                onCreated(ref.id)
            }
            .addOnFailureListener {
                Toast.makeText(this, "Couldn't save lead. Nothing was added.", Toast.LENGTH_LONG).show()
                onFailed()
            }
    }

    private fun linkEventToLead(
        leadId: String,
        leadName: String,
        phone: String,
        item: AgendaItem,
        note: String,
        onDone: () -> Unit,
        onFailed: () -> Unit = {}
    ) {
        val now = System.currentTimeMillis()
        val createdId = item.appointmentId?.takeIf { it.isNotBlank() } ?: AppointmentAgenda.newAppointmentId()
        LeadAppointmentWriter.commit(
            db = db,
            leadId = leadId,
            mutationFor = { fresh, legacyStart, legacyLocation, legacyEventId ->
                val migrated = AppointmentAgenda.migrateAppointments(
                    leadId = leadId,
                    existing = fresh,
                    legacyStartMillis = legacyStart,
                    legacyLocation = legacyLocation,
                    legacyEventId = legacyEventId,
                    nowMillis = now
                )
                val matched = if (!item.appointmentId.isNullOrBlank()) {
                    migrated.firstOrNull { it.id == item.appointmentId }
                } else {
                    migrated.filter { item.eventId != null && it.eventId == item.eventId }.singleOrNull()
                }
                val baseUpdatedAt = matched?.updatedAtMillis ?: 0L
                val record = if (matched != null) {
                    matched.copy(
                        startMillis = item.startMillis,
                        location = item.location?.takeIf { it.isNotBlank() } ?: matched.location,
                        eventId = item.eventId ?: matched.eventId,
                        status = AppointmentAgenda.AppointmentStatus.SCHEDULED,
                        updatedAtMillis = now
                    )
                } else {
                    AppointmentAgenda.AppointmentRecord(
                        id = createdId,
                        startMillis = item.startMillis,
                        location = item.location?.trim()?.takeIf { it.isNotEmpty() },
                        eventId = item.eventId?.takeIf { it > 0L },
                        status = AppointmentAgenda.AppointmentStatus.SCHEDULED,
                        originalInstanceTime = null,
                        updatedAtMillis = now
                    )
                }
                AppointmentSync.AppointmentMutation.Upsert(record, baseUpdatedAt)
            },
            extras = { data ->
                val ownerId = data["ownerId"] as? String
                if (!AppointmentAgenda.ownerCanEdit(ownerId, auth.currentUser?.uid)) {
                    throw IllegalStateException("That lead belongs to another account.")
                }
                mapOf(
                    "timestamp" to Timestamp.now(),
                    "notes" to appendNote((data["notes"] as? String).orEmpty(), note)
                )
            },
            onSuccess = { result ->
                if (isDestroyed) return@commit
                if (result.keptNewer) {
                    Toast.makeText(this, "A newer appointment change was kept.", Toast.LENGTH_LONG).show()
                    onFailed()
                    return@commit
                }
                val record = result.records.firstOrNull { it.id == result.targetId }
                if (record == null) {
                    Toast.makeText(this, "Couldn't link that lead. No duplicate was saved.", Toast.LENGTH_LONG).show()
                    onFailed()
                    return@commit
                }
                applyReminder(record, phone.ifBlank { leadId }, leadName)
                pushAppointmentToCalendar(
                    leadId = leadId,
                    leadName = leadName,
                    phone = phone,
                    startMillis = record.startMillis,
                    location = record.location,
                    existingEventId = record.eventId,
                    appointmentId = record.id,
                    soleAppointment = result.records.size == 1,
                    retry = calendarRetry(
                        leadId = leadId,
                        appointmentId = record.id,
                        leadName = leadName,
                        phone = phone,
                        startMillis = record.startMillis,
                        location = record.location,
                        eventId = record.eventId,
                        baseUpdatedAtMillis = record.updatedAtMillis,
                        soleAppointment = result.records.size == 1
                    )
                )
                onDone()
            },
            onFailure = { error ->
                val message = error.message?.takeIf { it.contains("another account") }
                    ?: "Couldn't link that lead. No duplicate was saved."
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                onFailed()
            }
        )
    }

    private fun saveQuickNote(
        leadId: String,
        leadName: String,
        phone: String,
        note: String,
        followUpMillis: Long?,
        onDone: () -> Unit,
        onFailed: () -> Unit = {}
    ) {
        db.collection("leads").document(leadId).get()
            .addOnSuccessListener { doc ->
                if (!doc.exists()) {
                    Toast.makeText(this, "That lead is no longer available.", Toast.LENGTH_LONG).show()
                    onFailed()
                    return@addOnSuccessListener
                }
                val updates = mutableMapOf<String, Any>("timestamp" to Timestamp.now())
                if (note.isNotBlank()) {
                    updates["notes"] = appendNote(doc.getString("notes").orEmpty(), note)
                }
                if (followUpMillis != null) {
                    updates["followUpDate"] = Timestamp(Date(followUpMillis))
                }
                doc.reference.update(updates)
                    .addOnSuccessListener {
                        if (followUpMillis != null) {
                            ReminderReceiver.scheduleReminder(
                                this,
                                phone.ifBlank { leadId },
                                leadName,
                                followUpMillis
                            )
                        }
                        Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
                        loadAgenda()
                        onDone()
                    }
                    .addOnFailureListener {
                        Toast.makeText(this, "Couldn't save that note.", Toast.LENGTH_LONG).show()
                        onFailed()
                    }
            }
            .addOnFailureListener {
                Toast.makeText(this, "Couldn't save that note.", Toast.LENGTH_LONG).show()
                onFailed()
            }
    }

    private fun pushAppointmentToCalendar(
        leadId: String,
        leadName: String,
        phone: String,
        startMillis: Long,
        location: String?,
        existingEventId: Long?,
        appointmentId: String,
        allowInsert: Boolean = true,
        soleAppointment: Boolean = false,
        retry: AppointmentSync.SyncOperation
    ) {
        if (appointmentId.isBlank()) return
        val operation = retry.copy(
            kind = AppointmentSync.SyncOperation.Kind.RESCHEDULE,
            leadId = leadId,
            appointmentId = appointmentId,
            leadName = leadName,
            phone = phone,
            intendedStartMillis = startMillis,
            location = location,
            eventId = existingEventId,
            soleAppointment = soleAppointment
        )
        if (!GoogleCalendarManager.hasCalendarPermissions(this)) {
            pendingCalendarSync = operation
            showSyncFailure(
                operation.copy(message = "Appointment saved in RCRM. Allow calendar access, then tap to sync it.")
            )
            return
        }
        GoogleCalendarManager.syncCrmAppointmentAsync(
            context = this,
            leadId = leadId,
            leadName = leadName,
            phone = phone.ifBlank { null },
            startMillis = startMillis,
            location = location,
            existingEventId = existingEventId,
            appointmentId = appointmentId,
            allowInsert = allowInsert,
            soleAppointmentForLead = soleAppointment
        ) { result ->
            if (isDestroyed) return@syncCrmAppointmentAsync
            when (result) {
                is GoogleCalendarManager.AppointmentSyncResult.Linked -> {
                    clearSyncFailure()
                    storeLinkedEventId(leadId, appointmentId, result.eventId, leadName, phone)
                }
                GoogleCalendarManager.AppointmentSyncResult.Cleared -> {
                    clearSyncFailure()
                    loadAgenda()
                }
                GoogleCalendarManager.AppointmentSyncResult.Skipped -> Unit
                is GoogleCalendarManager.AppointmentSyncResult.Failed -> {
                    showSyncFailure(operation.copy(message = "${result.message} Tap to retry."))
                }
            }
        }
    }

    private fun storeLinkedEventId(
        leadId: String,
        appointmentId: String,
        eventId: Long,
        leadName: String,
        phone: String
    ) {
        LeadAppointmentWriter.commit(
            db = db,
            leadId = leadId,
            mutationFor = { _, _, _, _ ->
                AppointmentSync.AppointmentMutation.SetEventId(appointmentId, eventId)
            },
            onSuccess = { result ->
                if (isDestroyed) return@commit
                if (result.targetId == null) {
                    showSyncFailure(
                        AppointmentSync.operation(
                            kind = AppointmentSync.SyncOperation.Kind.SAVE_EVENT_LINK,
                            leadId = leadId,
                            appointmentId = appointmentId,
                            leadName = leadName,
                            phone = phone,
                            eventId = eventId,
                            message = "The calendar event was saved, but that appointment is no longer on the lead. Tap to retry."
                        )
                    )
                    return@commit
                }
                loadAgenda()
            },
            onFailure = {
                if (!isDestroyed) {
                    showSyncFailure(
                        AppointmentSync.operation(
                            kind = AppointmentSync.SyncOperation.Kind.SAVE_EVENT_LINK,
                            leadId = leadId,
                            appointmentId = appointmentId,
                            leadName = leadName,
                            phone = phone,
                            eventId = eventId,
                            message = "The calendar event was saved, but RCRM couldn't store its id. Tap to retry."
                        )
                    )
                }
            }
        )
    }

    private fun recordOutcome(
        item: AgendaItem,
        outcome: AppointmentAgenda.Outcome?,
        newStartMillis: Long?
    ) {
        val leadId = item.leadId ?: return
        val appointmentId = item.appointmentId ?: return
        if (!item.explicitCrm) return
        if (outcome == null && newStartMillis == null) return
        val now = System.currentTimeMillis()
        val readUpdatedAt = longArrayOf(0L)
        LeadAppointmentWriter.commit(
            db = db,
            leadId = leadId,
            mutationFor = { fresh, legacyStart, legacyLocation, legacyEventId ->
                val migrated = AppointmentAgenda.migrateAppointments(
                    leadId = leadId,
                    existing = fresh,
                    legacyStartMillis = legacyStart,
                    legacyLocation = legacyLocation,
                    legacyEventId = legacyEventId,
                    nowMillis = now
                )
                val current = migrated.firstOrNull { it.id == appointmentId }
                    ?: throw IllegalStateException("That appointment is no longer on the lead.")
                readUpdatedAt[0] = current.updatedAtMillis
                if (newStartMillis != null) {
                    AppointmentSync.AppointmentMutation.Reschedule(
                        appointmentId = appointmentId,
                        newStartMillis = newStartMillis,
                        nowMillis = now,
                        rememberInstance = item.recurringMaster,
                        baseUpdatedAtMillis = current.updatedAtMillis
                    )
                } else {
                    AppointmentSync.AppointmentMutation.SaveOutcome(
                        appointmentId = appointmentId,
                        outcome = outcome ?: throw IllegalStateException("Couldn't save that appointment."),
                        nowMillis = now,
                        baseUpdatedAtMillis = current.updatedAtMillis
                    )
                }
            },
            extras = { data ->
                if (!AppointmentAgenda.ownerCanEdit(data["ownerId"] as? String, auth.currentUser?.uid)) {
                    throw IllegalStateException("That lead belongs to another account.")
                }
                emptyMap()
            },
            onSuccess = { result ->
                if (isDestroyed) return@commit
                val record = result.records.firstOrNull { it.id == appointmentId }
                if (record == null) {
                    Toast.makeText(this, "That appointment is no longer on the lead.", Toast.LENGTH_LONG).show()
                    return@commit
                }
                if (result.keptNewer || (newStartMillis != null && record.startMillis != newStartMillis)) {
                    Toast.makeText(this, "A newer appointment change was kept.", Toast.LENGTH_LONG).show()
                    loadAgenda()
                    return@commit
                }
                val leadName = item.leadName ?: item.title
                val phone = item.phone.orEmpty()
                applyReminder(record, phone.ifBlank { leadId }, leadName)
                val baseUpdatedAt = record.updatedAtMillis
                when {
                    outcome == AppointmentAgenda.Outcome.CANCELED -> {
                        val eventId = record.eventId ?: item.eventId
                        if (eventId != null) {
                            cancelCalendarEvent(
                                AppointmentSync.operation(
                                    kind = AppointmentSync.SyncOperation.Kind.CANCEL_CALENDAR_EVENT,
                                    leadId = leadId,
                                    appointmentId = appointmentId,
                                    leadName = leadName,
                                    phone = phone,
                                    location = record.location,
                                    eventId = eventId,
                                    outcome = outcome,
                                    baseUpdatedAtMillis = baseUpdatedAt,
                                    canWrite = item.canWrite,
                                    recurringMaster = item.recurringMaster,
                                    soleAppointment = result.records.size == 1,
                                    message = "Couldn't cancel the calendar event. Tap to retry."
                                ),
                                eventId
                            )
                        }
                    }
                    newStartMillis != null -> {
                        val eventId = record.eventId ?: item.eventId
                        val plan = AppointmentAgenda.planCalendarWrite(
                            eventId = eventId,
                            canWrite = item.canWrite,
                            recurringMaster = item.recurringMaster,
                            allowInsert = eventId == null
                        )
                        val retry = calendarRetry(
                            leadId = leadId,
                            appointmentId = appointmentId,
                            leadName = leadName,
                            phone = phone,
                            startMillis = newStartMillis,
                            location = record.location,
                            eventId = eventId,
                            baseUpdatedAtMillis = baseUpdatedAt,
                            canWrite = item.canWrite,
                            recurringMaster = item.recurringMaster,
                            rememberInstance = item.recurringMaster,
                            soleAppointment = result.records.size == 1
                        )
                        when (plan) {
                            is AppointmentAgenda.CalendarWritePlan.Blocked -> showSyncFailure(
                                retry.copy(message = plan.message)
                            )
                            else -> pushAppointmentToCalendar(
                                leadId = leadId,
                                leadName = leadName,
                                phone = phone,
                                startMillis = newStartMillis,
                                location = record.location,
                                existingEventId = eventId,
                                appointmentId = appointmentId,
                                allowInsert = plan is AppointmentAgenda.CalendarWritePlan.InsertNew,
                                soleAppointment = result.records.size == 1,
                                retry = retry
                            )
                        }
                    }
                }
                Toast.makeText(this, "Appointment updated", Toast.LENGTH_SHORT).show()
                loadAgenda()
                showQuickNoteDialog(
                    item.copy(
                        startMillis = record.startMillis,
                        leadId = leadId,
                        appointmentId = appointmentId,
                        explicitCrm = true
                    )
                )
            },
            onFailure = { error ->
                val message = error.message.orEmpty()
                if (message.contains("no longer") || message.contains("another account")) {
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                    return@commit
                }
                val leadName = item.leadName ?: item.title
                val phone = item.phone.orEmpty()
                if (newStartMillis != null) {
                    showSyncFailure(
                        calendarRetry(
                            leadId = leadId,
                            appointmentId = appointmentId,
                            leadName = leadName,
                            phone = phone,
                            startMillis = newStartMillis,
                            location = item.location,
                            eventId = item.eventId,
                            baseUpdatedAtMillis = readUpdatedAt[0],
                            canWrite = item.canWrite,
                            recurringMaster = item.recurringMaster,
                            rememberInstance = item.recurringMaster,
                            message = "Couldn't save that appointment. Tap to retry."
                        )
                    )
                } else if (outcome != null) {
                    showSyncFailure(
                        AppointmentSync.operation(
                            kind = AppointmentSync.SyncOperation.Kind.SAVE_OUTCOME,
                            leadId = leadId,
                            appointmentId = appointmentId,
                            leadName = leadName,
                            phone = phone,
                            location = item.location,
                            eventId = item.eventId,
                            outcome = outcome,
                            baseUpdatedAtMillis = readUpdatedAt[0],
                            canWrite = item.canWrite,
                            recurringMaster = item.recurringMaster,
                            message = "Couldn't save that appointment. Tap to retry."
                        )
                    )
                }
            }
        )
    }

    private fun calendarRetry(
        leadId: String,
        appointmentId: String,
        leadName: String,
        phone: String,
        startMillis: Long,
        location: String?,
        eventId: Long?,
        baseUpdatedAtMillis: Long,
        canWrite: Boolean = true,
        recurringMaster: Boolean = false,
        rememberInstance: Boolean = false,
        soleAppointment: Boolean = false,
        message: String = "Couldn't update the calendar event. Tap to retry."
    ): AppointmentSync.SyncOperation {
        return AppointmentSync.operation(
            kind = AppointmentSync.SyncOperation.Kind.RESCHEDULE,
            leadId = leadId,
            appointmentId = appointmentId,
            leadName = leadName,
            phone = phone,
            intendedStartMillis = startMillis,
            location = location,
            eventId = eventId,
            baseUpdatedAtMillis = baseUpdatedAtMillis,
            canWrite = canWrite,
            recurringMaster = recurringMaster,
            rememberInstance = rememberInstance,
            soleAppointment = soleAppointment,
            message = message
        )
    }

    private fun applyReminder(
        record: AppointmentAgenda.AppointmentRecord,
        phoneOrLeadId: String,
        leadName: String
    ) {
        val change = AppointmentAgenda.reminderChange(
            record,
            AppointmentAgenda.legacyAppointmentReminderKey(phoneOrLeadId)
        )
        change.cancelKeys.forEach { key -> ReminderReceiver.cancelReminder(this, key) }
        val whenMillis = change.scheduleAtMillis
        val key = change.scheduleKey
        if (key != null && whenMillis != null) {
            ReminderReceiver.scheduleReminder(this, key, "Appointment: $leadName", whenMillis)
        }
    }

    private fun showSyncFailure(failure: AppointmentSync.SyncOperation) {
        failedSync = failure
        val view = findViewById<TextView>(R.id.tvSyncStatus)
        view.text = failure.message
        view.visibility = View.VISIBLE
    }

    private fun clearSyncFailure() {
        failedSync = null
        findViewById<TextView>(R.id.tvSyncStatus).visibility = View.GONE
    }

    private fun retryFailedSync() {
        val pending = failedSync ?: return
        db.collection("leads").document(pending.leadId).get()
            .addOnSuccessListener { doc ->
                if (isDestroyed) return@addOnSuccessListener
                if (!doc.exists()) {
                    Toast.makeText(this, "That lead is no longer available.", Toast.LENGTH_LONG).show()
                    return@addOnSuccessListener
                }
                val fresh = LeadAppointmentSync.readRecords(doc.get(AppointmentAgenda.FIELD_APPOINTMENTS))
                when (val directive = AppointmentSync.retryDirective(pending, fresh)) {
                    is AppointmentSync.RetryDirective.CancelEvent ->
                        cancelCalendarEvent(pending, directive.eventId)
                    is AppointmentSync.RetryDirective.PushCalendar -> pushAppointmentToCalendar(
                        leadId = pending.leadId,
                        leadName = pending.leadName,
                        phone = pending.phone,
                        startMillis = directive.startMillis,
                        location = pending.location,
                        existingEventId = directive.eventId,
                        appointmentId = pending.appointmentId,
                        allowInsert = directive.allowInsert,
                        soleAppointment = pending.soleAppointment,
                        retry = pending.copy(
                            intendedStartMillis = directive.startMillis,
                            eventId = directive.eventId
                        )
                    )
                    AppointmentSync.RetryDirective.Remigrate -> persistMigrations(
                        listOf(pending.leadId),
                        System.currentTimeMillis()
                    ) {
                        clearSyncFailure()
                        Toast.makeText(this, "Appointment history saved", Toast.LENGTH_SHORT).show()
                        loadAgenda()
                    }
                    AppointmentSync.RetryDirective.NothingToDo -> {
                        clearSyncFailure()
                        loadAgenda()
                    }
                    is AppointmentSync.RetryDirective.Apply -> LeadAppointmentWriter.commit(
                        db = db,
                        leadId = pending.leadId,
                        mutationFor = { _, start, location, eventId ->
                            val mutation = directive.mutation
                            if (mutation is AppointmentSync.AppointmentMutation.Migrate) {
                                AppointmentSync.AppointmentMutation.Migrate(
                                    legacyStartMillis = start,
                                    legacyLocation = location,
                                    legacyEventId = eventId,
                                    nowMillis = System.currentTimeMillis()
                                )
                            } else {
                                mutation
                            }
                        },
                        onSuccess = { result -> handleRetryWrite(pending, result) },
                        onFailure = {
                            Toast.makeText(
                                this,
                                "Retry failed. The appointment was not duplicated.",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    )
                }
            }
            .addOnFailureListener {
                Toast.makeText(this, "Retry failed. The appointment was not duplicated.", Toast.LENGTH_LONG).show()
            }
    }

    private fun handleRetryWrite(pending: AppointmentSync.SyncOperation, result: AppointmentSync.MutationResult) {
        if (isDestroyed) return
        if (result.keptNewer) {
            clearSyncFailure()
            Toast.makeText(this, "A newer appointment change was kept.", Toast.LENGTH_LONG).show()
            loadAgenda()
            return
        }
        val record = result.records.firstOrNull { it.id == pending.appointmentId }
        when (pending.kind) {
            AppointmentSync.SyncOperation.Kind.RESCHEDULE -> {
                val start = pending.intendedStartMillis
                if (record != null && record.startMillis != start) {
                    clearSyncFailure()
                    loadAgenda()
                    return
                }
                if (record != null) {
                    applyReminder(record, pending.phone.ifBlank { pending.leadId }, pending.leadName)
                }
                pushAppointmentToCalendar(
                    leadId = pending.leadId,
                    leadName = pending.leadName,
                    phone = pending.phone,
                    startMillis = start,
                    location = record?.location ?: pending.location,
                    existingEventId = record?.eventId ?: pending.eventId,
                    appointmentId = pending.appointmentId,
                    allowInsert = (record?.eventId ?: pending.eventId) == null,
                    soleAppointment = result.records.size == 1,
                    retry = pending.copy(intendedStartMillis = start, eventId = record?.eventId ?: pending.eventId)
                )
            }
            AppointmentSync.SyncOperation.Kind.SAVE_OUTCOME -> {
                if (pending.outcome == AppointmentAgenda.Outcome.CANCELED) {
                    val eventId = record?.eventId ?: pending.eventId
                    if (eventId != null) {
                        cancelCalendarEvent(
                            pending.copy(
                                kind = AppointmentSync.SyncOperation.Kind.CANCEL_CALENDAR_EVENT,
                                eventId = eventId,
                                intendedStartMillis = 0L
                            ),
                            eventId
                        )
                        return
                    }
                }
                clearSyncFailure()
                Toast.makeText(this, "Appointment updated", Toast.LENGTH_SHORT).show()
                loadAgenda()
            }
            AppointmentSync.SyncOperation.Kind.MIGRATE_HISTORY -> {
                clearSyncFailure()
                Toast.makeText(this, "Appointment history saved", Toast.LENGTH_SHORT).show()
                loadAgenda()
            }
            AppointmentSync.SyncOperation.Kind.SAVE_EVENT_LINK -> {
                if (result.targetId == null) {
                    showSyncFailure(
                        pending.copy(message = "The calendar event was saved, but that appointment is no longer on the lead.")
                    )
                } else {
                    clearSyncFailure()
                    loadAgenda()
                }
            }
            AppointmentSync.SyncOperation.Kind.CANCEL_CALENDAR_EVENT -> {
                clearSyncFailure()
                loadAgenda()
            }
        }
    }

    private fun cancelCalendarEvent(pending: AppointmentSync.SyncOperation, eventId: Long) {
        val operation = pending.copy(
            kind = AppointmentSync.SyncOperation.Kind.CANCEL_CALENDAR_EVENT,
            eventId = eventId,
            intendedStartMillis = 0L
        )
        if (!GoogleCalendarManager.hasCalendarPermissions(this)) {
            showSyncFailure(
                operation.copy(message = "Appointment saved in RCRM. Allow calendar access, then tap to cancel the calendar event.")
            )
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val cancelResult = GoogleCalendarManager.markEventCanceled(
                context = this@AppointmentsActivity,
                eventId = eventId,
                leadId = operation.leadId,
                appointmentId = operation.appointmentId,
                soleAppointmentForLead = operation.soleAppointment
            )
            withContext(Dispatchers.Main) {
                if (isDestroyed) return@withContext
                if (cancelResult is GoogleCalendarManager.AppointmentSyncResult.Failed) {
                    showSyncFailure(operation.copy(message = "${cancelResult.message} Tap to retry."))
                } else {
                    clearSyncFailure()
                    loadAgenda()
                }
            }
        }
    }

    private fun focusOnAppointment(startMillis: Long) {
        val day = startOfLocalDay(startMillis)
        visibleMonth.timeInMillis = day
        visibleMonth.set(Calendar.DAY_OF_MONTH, 1)
        selectedDayMillis = day
        if (day < startOfLocalDay(System.currentTimeMillis())) {
            monthFilter = MonthFilter.ALL
        }
        styleFilterChips()
        loadAgenda()
    }

    private fun openLeadInRcrm(leadId: String) {
        startActivity(Intent(this, FollowUpActivity::class.java).apply {
            putExtra("targetLeadId", leadId)
            data = Uri.parse("rcrm://lead/$leadId")
        })
    }

    private fun launchContactPicker() {
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            pickContactLauncher.launch(null)
        } else {
            contactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
        }
    }

    @android.annotation.SuppressLint("Range")
    private fun readPickedContact(uri: Uri): Pair<String, String> {
        var name = ""
        var phone = ""
        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use
                name = cursor.getString(cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)).orEmpty()
                val contactId = cursor.getString(cursor.getColumnIndex(ContactsContract.Contacts._ID)).orEmpty()
                val hasPhone = cursor.getString(
                    cursor.getColumnIndex(ContactsContract.Contacts.HAS_PHONE_NUMBER)
                )?.toIntOrNull() ?: 0
                if (hasPhone > 0 && contactId.isNotBlank()) {
                    contentResolver.query(
                        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                        arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                        "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                        arrayOf(contactId),
                        null
                    )?.use { phoneCursor ->
                        if (phoneCursor.moveToFirst()) {
                            phone = phoneCursor.getString(0).orEmpty()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't read that contact.", Toast.LENGTH_SHORT).show()
        }
        return name to phone
    }

    private fun pickFollowUpDate(onPicked: (Long) -> Unit) {
        val initial = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 9)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val theme = android.R.style.Theme_DeviceDefault_Dialog_Alert
        DatePickerDialog(this, theme, { _, year, month, day ->
            initial.set(year, month, day)
            TimePickerDialog(this, theme, { _, hour, minute ->
                initial.set(Calendar.HOUR_OF_DAY, hour)
                initial.set(Calendar.MINUTE, minute)
                initial.set(Calendar.SECOND, 0)
                initial.set(Calendar.MILLISECOND, 0)
                onPicked(initial.timeInMillis)
            }, initial.get(Calendar.HOUR_OF_DAY), initial.get(Calendar.MINUTE), false).show()
        }, initial.get(Calendar.YEAR), initial.get(Calendar.MONTH), initial.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun openInGoogleCalendar(item: AgendaItem) {
        val eventId = item.eventId
        if (eventId != null && eventId > 0L) {
            GoogleCalendarManager.openEventInGoogleCalendar(this, eventId, item.startMillis, item.endMillis)
            return
        }
        GoogleCalendarManager.openGoogleCalendarAtTime(this, item.startMillis)
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
        var chosenDefault = GoogleCalendarManager.getDefaultCalendarId(this)
            ?: calendars.firstOrNull { it.isPrimary && it.canWrite }?.id
            ?: calendars.firstOrNull { it.canWrite }?.id
        val hideIds = GoogleCalendarManager.redundantReadOnlyCalendarIds(calendars)
        val checked = mutableMapOf<Long, Boolean>()
        calendars.forEach { calendar ->
            checked[calendar.id] = savedVisible == null || savedVisible.contains(calendar.id)
        }

        val dialogView = layoutInflater.inflate(R.layout.dialog_calendar_picker, null)
        val list = dialogView.findViewById<LinearLayout>(R.id.calendarPickerList)
        val hideSwitch = dialogView.findViewById<SwitchCompat>(R.id.switchHideDuplicateCalendars)
        val scroll = dialogView.findViewById<ScrollView>(R.id.calendarPickerScroll)
        val radioButtons = mutableListOf<RadioButton>()
        val primaryText = ContextCompat.getColor(this, R.color.textColorPrimary)
        val secondaryText = ContextCompat.getColor(this, R.color.textColorSecondary)

        fun capPickerHeight() {
            val widthSpec = View.MeasureSpec.makeMeasureSpec(
                (resources.displayMetrics.widthPixels * 0.92f).toInt(),
                View.MeasureSpec.AT_MOST
            )
            scroll.measure(widthSpec, View.MeasureSpec.UNSPECIFIED)
            val maxHeight = dp(320)
            scroll.layoutParams = scroll.layoutParams.apply {
                height = if (scroll.measuredHeight > maxHeight) maxHeight else ViewGroup.LayoutParams.WRAP_CONTENT
            }
        }

        fun render(hideEnabled: Boolean) {
            list.removeAllViews()
            radioButtons.clear()
            val ordered = calendars.sortedWith(
                compareBy<GoogleSubCalendar> { it.displayName.trim().lowercase(Locale.getDefault()) }
                    .thenBy { it.accountName.trim().lowercase(Locale.getDefault()) }
            )
            val shown = if (hideEnabled) ordered.filter { it.id !in hideIds } else ordered
            val groups = shown.groupBy { it.displayName.trim().lowercase(Locale.getDefault()) }
            shown.forEach { calendar ->
                val key = calendar.displayName.trim().lowercase(Locale.getDefault())
                val group = groups[key].orEmpty()
                if (group.size > 1 && group.first().id == calendar.id) {
                    list.addView(TextView(this).apply {
                        text = "${calendar.displayName} · ${group.size} accounts"
                        setTextColor(ContextCompat.getColor(this@AppointmentsActivity, R.color.colorInfo))
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                        setTypeface(typeface, Typeface.BOLD)
                        setPadding(0, dp(10), 0, dp(2))
                    })
                }
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(6), 0, dp(6))
                }
                val check = CheckBox(this).apply {
                    isChecked = checked[calendar.id] != false
                    setOnCheckedChangeListener { _, isChecked -> checked[calendar.id] = isChecked }
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
                labels.addView(TextView(this).apply {
                    text = calendar.displayName
                    setTextColor(primaryText)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                })
                labels.addView(TextView(this).apply {
                    text = calendarAccountLabel(calendar, calendar.id in hideIds)
                    setTextColor(secondaryText)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                })
                val radio = RadioButton(this).apply {
                    isChecked = calendar.id == chosenDefault && calendar.canWrite
                    isEnabled = calendar.canWrite
                    contentDescription = "Save new RCRM appointments to ${calendar.displayName} (${calendar.accountName})"
                }
                radioButtons += radio
                radio.setOnClickListener {
                    if (!radio.isEnabled) return@setOnClickListener
                    chosenDefault = calendar.id
                    radioButtons.forEach { button -> button.isChecked = button == radio }
                }
                row.addView(check)
                row.addView(dot)
                row.addView(labels)
                row.addView(radio)
                list.addView(row)
            }
            capPickerHeight()
        }

        hideSwitch.isChecked = GoogleCalendarManager.hideDuplicateHolidayCalendars(this)
        render(hideSwitch.isChecked)
        hideSwitch.setOnCheckedChangeListener { _, isChecked -> render(isChecked) }

        val dialog = glassDialog(dialogView)
        dialogView.findViewById<View>(R.id.btnCancelCalendars).setOnClickListener { dialog.dismiss() }
        dialogView.findViewById<View>(R.id.btnApplyCalendars).setOnClickListener {
            val visible = calendars.mapNotNull { calendar ->
                if (checked[calendar.id] == true) calendar.id else null
            }.toSet()
            val defaultId = calendars.firstOrNull { it.id == chosenDefault && it.canWrite }?.id
                ?: calendars.firstOrNull { it.canWrite }?.id
            GoogleCalendarManager.saveVisibleCalendarIds(this, visible)
            GoogleCalendarManager.saveHideDuplicateHolidayCalendars(this, hideSwitch.isChecked)
            if (defaultId != null) {
                GoogleCalendarManager.saveDefaultCalendarId(this, defaultId)
            }
            dialog.dismiss()
            loadAgenda()
        }
        dialog.show()
        sizeDialog(dialog)
    }

    private fun formatWhen(startMillis: Long, endMillis: Long, allDay: Boolean): String {
        if (allDay) {
            return "${dayFormat.format(Date(eventDayStart(startMillis, allDay = true)))} · All day"
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

    private fun eventDayStart(item: AgendaItem): Long = eventDayStart(item.startMillis, item.allDay)

    private fun eventDayStart(startMillis: Long, allDay: Boolean): Long {
        if (!allDay) return startOfLocalDay(startMillis)
        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = startMillis }
        return Calendar.getInstance().apply {
            set(Calendar.YEAR, utc.get(Calendar.YEAR))
            set(Calendar.MONTH, utc.get(Calendar.MONTH))
            set(Calendar.DAY_OF_MONTH, utc.get(Calendar.DAY_OF_MONTH))
            zeroTime(this)
        }.timeInMillis
    }

    private fun startOfLocalDay(millis: Long): Long {
        return Calendar.getInstance().apply {
            timeInMillis = millis
            zeroTime(this)
        }.timeInMillis
    }

    private fun defaultAppointmentCalendar(): Calendar {
        val base = selectedDayMillis ?: startOfLocalDay(System.currentTimeMillis())
        val now = Calendar.getInstance()
        var hour = now.get(Calendar.HOUR_OF_DAY) + 1
        if (hour > 20) hour = 9
        return Calendar.getInstance().apply {
            timeInMillis = base
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
    }

    private fun suggestedLeadName(title: String): String {
        var name = title.trim()
        if (name.equals("(No title)", ignoreCase = true)) return ""
        val prefix = "appointment:"
        if (name.lowercase(Locale.getDefault()).startsWith(prefix)) {
            name = name.substring(prefix.length).trim()
        }
        return name
    }

    private fun calendarLabel(displayName: String, accountName: String): String {
        val name = displayName.trim().ifBlank { "Calendar" }
        val account = accountName.trim()
        return if (account.isEmpty()) name else "$name · $account"
    }

    private fun calendarAccountLabel(calendar: GoogleSubCalendar, duplicate: Boolean): String {
        val bits = mutableListOf(
            calendar.accountName.ifBlank { "On this device" },
            if (calendar.canWrite) "Writable" else "Read only"
        )
        if (calendar.isPrimary) bits += "Primary"
        if (duplicate) bits += "Duplicate"
        return bits.joinToString(" · ")
    }

    private fun leadChoices(): List<LeadChoice> {
        return loadedLeads.mapNotNull { lead ->
            val id = lead["__docId"] as? String ?: return@mapNotNull null
            LeadChoice(
                id = id,
                name = (lead["name"] as? String)?.trim().orEmpty().ifBlank { "Lead" },
                phone = (lead["phone"] as? String)?.trim().orEmpty()
            )
        }.sortedBy { it.name.lowercase(Locale.getDefault()) }
    }

    private fun findLeadByPhone(phone: String): LeadChoice? {
        val needle = phone.filter { it.isDigit() }
        if (needle.length < 7) return null
        return leadChoices().firstOrNull { it.phone.filter { digit -> digit.isDigit() } == needle }
    }

    private fun appendNote(existing: String, content: String): String {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return existing
        val formatted = NoteUtils.formatNewNote(trimmed)
        return if (existing.isBlank()) formatted else "$existing\n\n$formatted"
    }

    private fun pillField(hint: String, inputType: Int): EditText {
        return EditText(this).apply {
            this.hint = hint
            this.inputType = inputType
            setTextColor(ContextCompat.getColor(this@AppointmentsActivity, R.color.textColorPrimary))
            setHintTextColor(ContextCompat.getColor(this@AppointmentsActivity, R.color.textColorSecondary))
            background = ContextCompat.getDrawable(this@AppointmentsActivity, R.drawable.bg_frosted_pill_input)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        }
    }

    private fun glassDialog(view: View): AlertDialog {
        val dialog = AlertDialog.Builder(this).setView(view).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        return dialog
    }

    private fun sizeDialog(dialog: AlertDialog) {
        val width = (resources.displayMetrics.widthPixels * 0.92f).toInt()
        dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
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

    private fun blankAgendaItem(
        title: String,
        startMillis: Long,
        eventId: Long?,
        leadId: String,
        leadName: String,
        phone: String,
        location: String?
    ): AgendaItem {
        return AgendaItem(
            title = title,
            whenLabel = "",
            meta = "",
            startMillis = startMillis,
            endMillis = startMillis + 60L * 60L * 1000L,
            color = 0,
            eventId = eventId,
            leadId = leadId,
            leadName = leadName,
            phone = phone,
            location = location,
            allDay = false,
            accountName = ""
        )
    }

    private fun SyncedCalendarEvent.toCalendarRef(): AppointmentAgenda.CalendarEventRef {
        return AppointmentAgenda.CalendarEventRef(
            eventId = eventId,
            title = title,
            startMillis = startMillis,
            endMillis = endMillis,
            allDay = allDay,
            calendarDisplayName = calendarDisplayName,
            accountName = accountName,
            color = calendarColor,
            canWrite = canWrite,
            recurring = recurring,
            originalInstanceTime = originalInstanceTime,
            originalEventId = originalEventId,
            explicitLeadId = leadId,
            explicitAppointmentId = explicitAppointmentId,
            canceled = canceled
        )
    }

    private fun AppointmentAgenda.AgendaEntry.toAgendaItem(): AgendaItem {
        val statusText = AppointmentAgenda.statusLabel(status)
        val whenText = buildString {
            append(formatWhen(startMillis, endMillis, allDay))
            if (statusText != null) {
                append(" · ")
                append(statusText)
            }
        }
        val storedLabel = storedStartMillis?.let { followUpFormat.format(Date(it)) }
        return AgendaItem(
            title = title,
            whenLabel = whenText,
            meta = AppointmentAgenda.shortCalendarLabel(calendarDisplayName, explicitCrm),
            startMillis = startMillis,
            endMillis = endMillis,
            color = color,
            eventId = eventId,
            leadId = leadId,
            leadName = leadName,
            phone = phone,
            location = location,
            allDay = allDay,
            accountName = accountName,
            explicitCrm = explicitCrm,
            appointmentId = appointmentId,
            suggestedLeadId = suggestedLeadId,
            detailText = AppointmentAgenda.eventDetail(this, storedLabel),
            canWrite = canWrite,
            recurring = recurring,
            recurringMaster = recurringMaster,
            status = status,
            ownerId = ownerId,
            timeDrifted = timeDrifted,
            storedStartMillis = storedStartMillis,
            calendarDisplayName = calendarDisplayName
        )
    }

    private fun AgendaItem.toEntry(): AppointmentAgenda.AgendaEntry {
        return AppointmentAgenda.AgendaEntry(
            title = title,
            startMillis = startMillis,
            endMillis = endMillis,
            allDay = allDay,
            color = color,
            eventId = eventId,
            leadId = leadId,
            ownerId = ownerId,
            appointmentId = appointmentId,
            leadName = leadName,
            phone = phone,
            location = location,
            explicitCrm = explicitCrm,
            suggestedLeadId = suggestedLeadId,
            calendarDisplayName = calendarDisplayName,
            accountName = accountName,
            canWrite = canWrite,
            recurring = recurring,
            recurringMaster = recurringMaster,
            storedStartMillis = storedStartMillis,
            timeDrifted = timeDrifted,
            status = status
        )
    }

    private fun entryKey(entry: AppointmentAgenda.AgendaEntry): String {
        return listOf(
            entry.appointmentId.orEmpty(),
            entry.eventId?.toString().orEmpty(),
            entry.startMillis.toString(),
            entry.title
        ).joinToString("|")
    }

    private enum class MonthFilter { UPCOMING, ALL }

    private enum class SourceFilter { ALL_EVENTS, CRM_ONLY }

    private data class LeadChoice(
        val id: String,
        val name: String,
        val phone: String
    )

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
        val allDay: Boolean,
        val accountName: String,
        val explicitCrm: Boolean = false,
        val appointmentId: String? = null,
        val suggestedLeadId: String? = null,
        val detailText: String = "",
        val canWrite: Boolean = true,
        val recurring: Boolean = false,
        val recurringMaster: Boolean = false,
        val status: AppointmentAgenda.AppointmentStatus? = null,
        val ownerId: String? = null,
        val timeDrifted: Boolean = false,
        val storedStartMillis: Long? = null,
        val calendarDisplayName: String = ""
    )

    private data class DayCell(
        val dayStartMillis: Long,
        val dowLabel: String,
        val dayNumber: String,
        val isToday: Boolean,
        val selected: Boolean,
        val dotColors: List<Int>
    )

    private sealed class AgendaRow {
        data class DayHeader(
            val dayStartMillis: Long,
            val label: String,
            val isToday: Boolean
        ) : AgendaRow()

        data class Event(val item: AgendaItem) : AgendaRow()
    }

    private inner class AgendaAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount(): Int = displayRows.size

        override fun getItemViewType(position: Int): Int {
            return if (displayRows[position] is AgendaRow.DayHeader) VIEW_HEADER else VIEW_EVENT
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == VIEW_HEADER) {
                HeaderHolder(inflater.inflate(R.layout.item_agenda_day_header, parent, false))
            } else {
                EventHolder(inflater.inflate(R.layout.item_calendar_event, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = displayRows[position]) {
                is AgendaRow.DayHeader -> (holder as HeaderHolder).bind(row)
                is AgendaRow.Event -> (holder as EventHolder).bind(row.item)
            }
        }

        private inner class HeaderHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val label: TextView = view.findViewById(R.id.tvAgendaDayHeader)

            fun bind(row: AgendaRow.DayHeader) {
                label.text = row.label
                if (row.isToday) {
                    label.setBackgroundResource(R.drawable.bg_frosted_pill_input)
                    label.setTextColor(ContextCompat.getColor(this@AppointmentsActivity, R.color.colorInfo))
                } else {
                    label.background = null
                    label.setTextColor(ContextCompat.getColor(this@AppointmentsActivity, R.color.textColorSecondary))
                }
            }
        }

        private inner class EventHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val colorBar: View = view.findViewById(R.id.viewCalendarColor)
            private val title: TextView = view.findViewById(R.id.tvEventTitle)
            private val whenLabel: TextView = view.findViewById(R.id.tvEventWhen)
            private val meta: TextView = view.findViewById(R.id.tvEventMeta)

            fun bind(item: AgendaItem) {
                colorBar.background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 8f * resources.displayMetrics.density
                    setColor(item.color)
                }
                title.text = item.title
                whenLabel.text = item.whenLabel
                meta.text = item.meta
                meta.visibility = if (item.meta.isBlank()) View.GONE else View.VISIBLE
                itemView.contentDescription = listOf(item.title, item.whenLabel, item.meta)
                    .filter { it.isNotBlank() }
                    .joinToString(", ")
                itemView.setOnClickListener { showEventActions(item) }
            }
        }
    }

    private inner class DayStripAdapter : RecyclerView.Adapter<DayStripAdapter.Holder>() {
        private val cells = mutableListOf<DayCell>()

        fun submit(next: List<DayCell>) {
            cells.clear()
            cells.addAll(next)
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = cells.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_day_strip_cell, parent, false)
            return Holder(view)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(cells[position])
        }

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            private val root: View = view.findViewById(R.id.dayStripCell)
            private val dow: TextView = view.findViewById(R.id.tvStripDow)
            private val number: TextView = view.findViewById(R.id.tvStripDay)
            private val dots = listOf<View>(
                view.findViewById(R.id.stripDot1),
                view.findViewById(R.id.stripDot2),
                view.findViewById(R.id.stripDot3)
            )

            fun bind(cell: DayCell) {
                dow.text = cell.dowLabel
                number.text = cell.dayNumber
                root.contentDescription = "${cell.dowLabel} ${cell.dayNumber}"
                val primary = ContextCompat.getColor(this@AppointmentsActivity, R.color.textColorPrimary)
                val secondary = ContextCompat.getColor(this@AppointmentsActivity, R.color.textColorSecondary)
                val info = ContextCompat.getColor(this@AppointmentsActivity, R.color.colorInfo)
                when {
                    cell.selected -> {
                        root.setBackgroundResource(R.drawable.bg_neon_cyan_button)
                        dow.setTextColor(primary)
                        number.setTextColor(info)
                    }
                    cell.isToday -> {
                        root.setBackgroundResource(R.drawable.bg_frosted_pill_input)
                        dow.setTextColor(info)
                        number.setTextColor(info)
                    }
                    else -> {
                        root.background = null
                        dow.setTextColor(secondary)
                        number.setTextColor(primary)
                    }
                }
                dots.forEachIndexed { index, dot ->
                    val color = cell.dotColors.getOrNull(index)
                    if (color == null) {
                        dot.visibility = View.INVISIBLE
                    } else {
                        dot.visibility = View.VISIBLE
                        dot.background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(color)
                        }
                    }
                }
                root.setOnClickListener { onDayStripClicked(cell.dayStartMillis) }
            }
        }
    }

    private class LinkLeadAdapter(
        private val leads: List<LeadChoice>,
        private val onClick: (LeadChoice) -> Unit
    ) : RecyclerView.Adapter<LinkLeadAdapter.Holder>() {
        override fun getItemCount(): Int = leads.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_link_lead_row, parent, false)
            return Holder(view)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(leads[position])
        }

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            private val name: TextView = view.findViewById(R.id.tvLinkLeadName)
            private val phone: TextView = view.findViewById(R.id.tvLinkLeadPhone)

            fun bind(lead: LeadChoice) {
                name.text = lead.name
                phone.text = lead.phone.ifBlank { "No phone" }
                itemView.setOnClickListener { onClick(lead) }
            }
        }
    }

    companion object {
        private const val VIEW_HEADER = 0
        private const val VIEW_EVENT = 1
        private const val STATE_MONTH = "appointments_month"
        private const val STATE_FILTER = "appointments_filter"
        private const val STATE_DAY = "appointments_day"
        private const val STATE_HAS_DAY = "appointments_has_day"
        private const val STATE_SOURCE = "appointments_source"

        private fun zeroTime(calendar: Calendar) {
            calendar.set(Calendar.HOUR_OF_DAY, 0)
            calendar.set(Calendar.MINUTE, 0)
            calendar.set(Calendar.SECOND, 0)
            calendar.set(Calendar.MILLISECOND, 0)
        }
    }
}
