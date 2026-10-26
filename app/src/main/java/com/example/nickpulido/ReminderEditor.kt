package com.nickpulido.rcrm

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.nickpulido.rcrm.domain.ActionType
import com.nickpulido.rcrm.domain.FollowUpRepository
import com.nickpulido.rcrm.domain.NextAction
import com.nickpulido.rcrm.domain.ReminderPlanner
import java.util.Calendar
import java.util.TimeZone
import java.util.UUID

internal enum class ReminderEditorMode {
    ADD,
    EDIT,
    RESCHEDULE
}

internal fun AppCompatActivity.showReminderEditor(
    repository: FollowUpRepository,
    leadId: String,
    ownerId: String,
    contactName: String,
    phone: String,
    existing: NextAction?,
    mode: ReminderEditorMode
) {
    val dialog = BottomSheetDialog(this)
    val content = layoutInflater.inflate(R.layout.dialog_add_reminder, null)
    dialog.setContentView(content)
    dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

    val header = content.findViewById<TextView>(R.id.tvAddReminderHeader)
    val contactNameView = content.findViewById<TextView>(R.id.tvAddReminderContact)
    val titleInput = content.findViewById<EditText>(R.id.etReminderTitle)
    val titleErrorView = content.findViewById<TextView>(R.id.tvReminderTitleError)
    val notesInput = content.findViewById<EditText>(R.id.etReminderNotes)
    val btnToggleNotes = content.findViewById<View>(R.id.btnToggleNotes)
    val ivNotesChevron = content.findViewById<android.widget.ImageView>(R.id.ivNotesChevron)
    val tvNotesLabel = content.findViewById<TextView>(R.id.tvNotesLabel)
    val typeLayout = content.findViewById<LinearLayout>(R.id.layoutReminderTypes)
    val dateView = content.findViewById<TextView>(R.id.tvReminderDate)
    val timeView = content.findViewById<TextView>(R.id.tvReminderTime)
    val todayButton = content.findViewById<View>(R.id.btnDueToday)
    val tomorrowButton = content.findViewById<View>(R.id.btnDueTomorrow)
    val errorView = content.findViewById<TextView>(R.id.tvReminderSaveError)
    val saveButton = content.findViewById<MaterialButton>(R.id.btnSaveReminder)

    val zone = TimeZone.getDefault()
    val locale = java.util.Locale.getDefault()
    val draftId = existing?.id ?: UUID.randomUUID().toString()
    var selectedTime = existing?.dueTime?.takeIf { it > 0L }
        ?: ReminderPlanner.defaultDueMillis(System.currentTimeMillis(), zone)
    selectedTime = ReminderPlanner.normalizeDueMillis(selectedTime, zone)
    var selectedType = existing?.type ?: ActionType.TO_DO
    val choices = ReminderPlanner.reminderTypeChoices(existing?.type)
    val typeButtons = linkedMapOf<ActionType, MaterialButton>()

    header.text = when (mode) {
        ReminderEditorMode.ADD -> "Add Reminder"
        ReminderEditorMode.EDIT -> "Edit Reminder"
        ReminderEditorMode.RESCHEDULE -> "Reschedule Reminder"
    }
    val saveLabel = when (mode) {
        ReminderEditorMode.ADD -> "Save Reminder"
        ReminderEditorMode.EDIT -> "Save Changes"
        ReminderEditorMode.RESCHEDULE -> "Save Reminder"
    }
    
    contactNameView.text = contactName.ifBlank { "Contact" }
    
    saveButton.text = saveLabel
    titleInput.setText(existing?.title.orEmpty())
    notesInput.setText(existing?.notes.orEmpty())

    var isNotesExpanded = existing?.notes?.isNotBlank() == true
    fun updateNotesVisibility() {
        if (isNotesExpanded) {
            notesInput.visibility = View.VISIBLE
            tvNotesLabel.text = "Notes"
            ivNotesChevron.setImageResource(android.R.drawable.arrow_up_float)
        } else {
            notesInput.visibility = View.GONE
            tvNotesLabel.text = "Add notes"
            ivNotesChevron.setImageResource(android.R.drawable.arrow_down_float)
        }
    }
    updateNotesVisibility()
    btnToggleNotes.setOnClickListener {
        isNotesExpanded = !isNotesExpanded
        updateNotesVisibility()
    }

    titleInput.addTextChangedListener(object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            titleErrorView.visibility = View.GONE
            errorView.visibility = View.GONE
        }
        override fun afterTextChanged(s: android.text.Editable?) {}
    })

    fun styleType(type: ActionType, selected: Boolean) {
        val button = typeButtons[type] ?: return
        if (selected) {
            button.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#1C2C48"))
            button.strokeColor = ColorStateList.valueOf(Color.parseColor("#2EE7FF"))
            button.strokeWidth = dp(2)
            button.setTextColor(Color.parseColor("#2EE7FF"))
        } else {
            button.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#243656"))
            button.strokeColor = ColorStateList.valueOf(Color.parseColor("#66243656"))
            button.strokeWidth = dp(1)
            button.setTextColor(Color.parseColor("#8E99B0"))
        }
    }

    fun refreshTime() {
        dateView.text = ReminderPlanner.formatDate(selectedTime, zone, locale)
        timeView.text = ReminderPlanner.formatTime(selectedTime, zone, locale)
    }

    fun selectType(type: ActionType) {
        selectedType = type
        choices.forEach { styleType(it, it == type) }
        
        // Auto-generate title if empty or matching a default pattern
        val currentTitle = titleInput.text?.toString()?.trim().orEmpty()
        val cName = contactName.ifBlank { "Contact" }.split(" ").firstOrNull() ?: "Contact"
        val isDefault = currentTitle.isEmpty() || currentTitle.matches(Regex("^(Call|Message|Email) .*"))
        
        if (isDefault) {
            val prefix = when (type) {
                ActionType.CALL -> "Call"
                ActionType.MESSAGE -> "Message"
                ActionType.EMAIL -> "Email"
                else -> null
            }
            if (prefix != null) {
                titleInput.setText("$prefix $cName")
                titleInput.setSelection(titleInput.text.length)
            } else if (currentTitle.isEmpty()) {
                titleInput.setText("")
            }
        }
    }

    choices.chunked(2).forEach { rowTypes ->
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }
        rowTypes.forEachIndexed { index, type ->
            val button = MaterialButton(this).apply {
                text = ReminderPlanner.typeLabel(type)
                isAllCaps = false
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
                minHeight = dp(48)
                insetTop = 0
                insetBottom = 0
                cornerRadius = dp(18)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (index == 0 && rowTypes.size > 1) marginEnd = dp(8)
                }
                setOnClickListener { selectType(type) }
            }
            typeButtons[type] = button
            row.addView(button)
        }
        if (rowTypes.size == 1) {
            row.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
            })
        }
        typeLayout.addView(row)
    }
    selectType(if (selectedType in choices) selectedType else ActionType.TO_DO)
    refreshTime()

    todayButton.setOnClickListener {
        selectedTime = ReminderPlanner.todayShortcutMillis(System.currentTimeMillis(), zone)
        refreshTime()
    }
    tomorrowButton.setOnClickListener {
        selectedTime = ReminderPlanner.tomorrowShortcutMillis(System.currentTimeMillis(), zone)
        refreshTime()
    }
    dateView.setOnClickListener {
        val calendar = Calendar.getInstance(zone).apply { timeInMillis = selectedTime }
        DatePickerDialog(
            this,
            { _, year, month, day ->
                calendar.set(Calendar.YEAR, year)
                calendar.set(Calendar.MONTH, month)
                calendar.set(Calendar.DAY_OF_MONTH, day)
                selectedTime = ReminderPlanner.normalizeDueMillis(calendar.timeInMillis, zone)
                refreshTime()
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        ).show()
    }
    timeView.setOnClickListener {
        val calendar = Calendar.getInstance(zone).apply { timeInMillis = selectedTime }
        TimePickerDialog(
            this,
            { _, hour, minute ->
                calendar.set(Calendar.HOUR_OF_DAY, hour)
                calendar.set(Calendar.MINUTE, minute)
                calendar.set(Calendar.SECOND, 0)
                calendar.set(Calendar.MILLISECOND, 0)
                selectedTime = calendar.timeInMillis
                refreshTime()
            },
            calendar.get(Calendar.HOUR_OF_DAY),
            calendar.get(Calendar.MINUTE),
            false
        ).show()
    }

    var saving = false
    saveButton.setOnClickListener {
        if (saving) return@setOnClickListener
        val title = titleInput.text?.toString().orEmpty()
        val notes = notesInput.text?.toString().orEmpty()
        val normalized = ReminderPlanner.normalizeDueMillis(selectedTime, zone)
        val titleError = ReminderPlanner.validateTitle(title)
        val previous = existing?.dueTime?.let { ReminderPlanner.normalizeDueMillis(it, zone) }
        val timeError = if (existing == null || previous != normalized) {
            ReminderPlanner.validateDueMillis(normalized, System.currentTimeMillis())
        } else {
            null
        }
        
        if (titleError != null) {
            titleErrorView.text = titleError
            titleErrorView.visibility = View.VISIBLE
            titleInput.requestFocus()
            return@setOnClickListener
        } else {
            titleErrorView.visibility = View.GONE
        }
        
        if (timeError != null) {
            errorView.text = timeError
            errorView.visibility = View.VISIBLE
            return@setOnClickListener
        }
        
        saving = true
        saveButton.isEnabled = false
        saveButton.text = "Saving..."
        errorView.visibility = View.GONE
        val now = System.currentTimeMillis()
        val finish: (Boolean, String?) -> Unit = finish@{ success, error ->
            saving = false
            saveButton.isEnabled = true
            saveButton.text = saveLabel
            if (!success) {
                errorView.text = error ?: "Couldn't save. Check your connection and try again."
                errorView.visibility = View.VISIBLE
                return@finish
            }
            ReminderAlarmStore.scheduleAction(
                context = this,
                leadId = leadId,
                actionId = draftId,
                title = title.trim(),
                contactName = contactName.ifBlank { "Contact" },
                phone = phone,
                dueTime = normalized
            )
            val access = ReminderAlarmStore.alertAccess(this)
            val message = when {
                !access.notificationsGranted ->
                    "Reminder saved. Notifications are off, so it won't alert you."
                !access.exactAlarmsGranted ->
                    "Reminder saved. Exact alarms are off, so the alert may be delayed."
                else -> "Reminder saved"
            }
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }
        val dueSame = existing != null &&
            ReminderPlanner.normalizeDueMillis(existing.dueTime, zone) == normalized
        val textSame = existing != null &&
            title.trim() == existing.title.trim() &&
            notes.trim() == existing.notes.trim() &&
            selectedType == existing.type
        when {
            existing == null -> repository.createReminder(
                leadId, ownerId, draftId, title, notes, selectedType, normalized, now, zone, finish
            )
            mode == ReminderEditorMode.EDIT && dueSame -> repository.editReminder(
                leadId, ownerId, existing.id, title, notes, selectedType, now, zone, finish
            )
            mode == ReminderEditorMode.RESCHEDULE && textSame -> repository.rescheduleReminder(
                leadId, ownerId, existing.id, normalized, now, zone, finish
            )
            else -> repository.updateReminder(
                leadId, ownerId, existing.id, title, notes, selectedType, normalized, now, zone, finish
            )
        }
    }

    val access = ReminderAlarmStore.alertAccess(this)
    if (!access.notificationsGranted && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
            4401
        )
    }

    dialog.setOnShowListener {
        val sheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
        if (sheet != null) {
            val behavior = BottomSheetBehavior.from(sheet)
            behavior.skipCollapsed = true
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
            behavior.isGestureInsetBottomIgnored = true
        }
    }
    dialog.show()
}

private fun AppCompatActivity.dp(value: Int): Int {
    return (value * resources.displayMetrics.density).toInt()
}
