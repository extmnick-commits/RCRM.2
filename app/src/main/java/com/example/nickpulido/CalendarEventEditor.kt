package com.nickpulido.rcrm

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.graphics.Rect
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.nickpulido.rcrm.domain.CalendarEditDraft
import com.nickpulido.rcrm.domain.CalendarEditSession
import com.nickpulido.rcrm.domain.CalendarEditSnapshot
import com.nickpulido.rcrm.domain.CalendarEventEdit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Edits only the simple events allowed by CalendarEventEdit; other events stay with the calendar app. */
class CalendarEventEditor(
    private val activity: AppCompatActivity,
    private val original: CalendarEditSnapshot,
    restored: CalendarEditDraft? = null,
    private val onSaved: (CalendarEditDraft) -> Unit,
    private val onGoogle: () -> Unit,
    private val onDismiss: () -> Unit
) {
    private val view = activity.layoutInflater.inflate(R.layout.dialog_edit_calendar_event, null)
    private val dialog = AlertDialog.Builder(activity).setView(view).create()
    private val title = view.findViewById<EditText>(R.id.calendarEditTitle)
    private val location = view.findViewById<EditText>(R.id.calendarEditLocation)
    private val description = view.findViewById<EditText>(R.id.calendarEditDescription)
    private val startButton = view.findViewById<Button>(R.id.calendarEditStart)
    private val endButton = view.findViewById<Button>(R.id.calendarEditEnd)
    private val saveButton = view.findViewById<Button>(R.id.calendarEditSave)
    private val googleButton = view.findViewById<Button>(R.id.calendarEditGoogle)
    private val cancelButton = view.findViewById<Button>(R.id.calendarEditCancel)
    private val error = view.findViewById<TextView>(R.id.calendarEditError)
    private val format = SimpleDateFormat("EEE, MMM d, yyyy · h:mm a", Locale.getDefault())
    private var start = (restored ?: CalendarEventEdit.draft(original)).startMillis
    private var end = (restored ?: CalendarEventEdit.draft(original)).endMillis
    private var saving = false
    private var transientDialog: android.app.Dialog? = null

    fun session(): CalendarEditSession? = if (dialog.isShowing) CalendarEditSession(original, draft()) else null

    fun dismissForLifecycle() {
        transientDialog?.dismiss()
        dialog.dismiss()
    }

    private fun draft() = CalendarEditDraft(title.text.toString(), start, end,
        location.text.toString(), description.text.toString())

    init {
        val draft = restored ?: CalendarEventEdit.draft(original)
        title.setText(draft.title)
        location.setText(draft.location)
        description.setText(draft.description)
        view.findViewById<TextView>(R.id.calendarEditZone).text = activity.getString(
            R.string.calendar_edit_zone, TimeZone.getDefault().displayName
        )
        renderTimes()
        startButton.setOnClickListener {
            pickTime(start) { chosen ->
                val duration = (end - start).coerceAtLeast(60_000L)
                start = chosen
                end = chosen + duration
                renderTimes()
            }
        }
        endButton.setOnClickListener { pickTime(end) { end = it; renderTimes() } }
        saveButton.setOnClickListener { save() }
        cancelButton.setOnClickListener { leave { dialog.dismiss() } }
        googleButton.setOnClickListener { leave { dialog.dismiss(); onGoogle() } }
        dialog.setCanceledOnTouchOutside(false)
        dialog.setCancelable(false)
        dialog.setOnKeyListener { _, code, event ->
            if (code == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) leave { dialog.dismiss() }
                true
            } else false
        }
    }

    fun show() {
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            setLayout((activity.resources.displayMetrics.widthPixels * .95f).toInt(),
                (activity.resources.displayMetrics.heightPixels * .88f).toInt())
        }
        val padding = (16 * activity.resources.displayMetrics.density).toInt()
        val keyboardListener = android.view.ViewTreeObserver.OnGlobalLayoutListener {
            val frame = Rect()
            view.getWindowVisibleDisplayFrame(frame)
            val position = IntArray(2)
            view.getLocationOnScreen(position)
            val overlap = (position[1] + view.height - frame.bottom).coerceAtLeast(0)
            val bottom = padding + overlap
            if (view.paddingBottom != bottom) view.setPadding(padding, padding, padding, bottom)
        }
        view.viewTreeObserver.addOnGlobalLayoutListener(keyboardListener)
        dialog.setOnDismissListener {
            if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnGlobalLayoutListener(keyboardListener)
            onDismiss()
        }
    }

    private fun renderTimes() {
        startButton.text = activity.getString(R.string.calendar_edit_start, format.format(Date(start)))
        endButton.text = activity.getString(R.string.calendar_edit_end, format.format(Date(end)))
    }

    private fun pickTime(initial: Long, onPicked: (Long) -> Unit) {
        val calendar = Calendar.getInstance().apply { timeInMillis = initial }
        val picker = DatePickerDialog(activity, { _, year, month, day ->
            calendar.set(year, month, day)
            val timePicker = TimePickerDialog(activity, { _, hour, minute ->
                calendar.set(Calendar.HOUR_OF_DAY, hour)
                calendar.set(Calendar.MINUTE, minute)
                calendar.set(Calendar.SECOND, 0)
                calendar.set(Calendar.MILLISECOND, 0)
                onPicked(calendar.timeInMillis)
            }, calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE), false)
            transientDialog = timePicker
            timePicker.show()
        }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH))
        transientDialog = picker
        picker.show()
    }

    private fun leave(action: () -> Unit) {
        if (saving) return
        if (draft() == CalendarEventEdit.draft(original)) { action(); return }
        transientDialog = AlertDialog.Builder(activity).setTitle(R.string.calendar_edit_discard_title)
            .setMessage(R.string.calendar_edit_discard_message)
            .setPositiveButton(R.string.calendar_edit_discard) { _, _ -> action() }
            .setNegativeButton(R.string.calendar_edit_keep, null).show()
    }

    private fun setSaving(value: Boolean) {
        saving = value
        fun enable(child: View) {
            child.isEnabled = !value
            if (child is ViewGroup) for (index in 0 until child.childCount) enable(child.getChildAt(index))
        }
        enable(view)
        saveButton.setText(if (value) R.string.calendar_edit_saving else R.string.calendar_edit_save)
    }

    private fun save() {
        if (saving) return
        val draft = draft()
        when (CalendarEventEdit.validate(draft)) {
            CalendarEventEdit.Error.TITLE -> {
                title.error = activity.getString(R.string.calendar_edit_name_required)
                title.requestFocus()
                return
            }
            CalendarEventEdit.Error.END_TIME -> {
                error.setText(R.string.calendar_edit_end_required)
                error.visibility = View.VISIBLE
                return
            }
            null -> Unit
        }
        setSaving(true)
        error.visibility = View.GONE
        activity.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { CalendarEventStore.save(activity, original, draft) }
            if (activity.isDestroyed || !dialog.isShowing) return@launch
            setSaving(false)
            when (result) {
                CalendarEventStore.SaveResult.SAVED, CalendarEventStore.SaveResult.UNCHANGED -> {
                    Toast.makeText(activity, R.string.calendar_edit_saved, Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                    onSaved(draft)
                }
                else -> {
                    error.setText(when (result) {
                        CalendarEventStore.SaveResult.CONFLICT -> R.string.calendar_edit_conflict
                        CalendarEventStore.SaveResult.USE_CALENDAR -> R.string.calendar_edit_external_needed
                        else -> R.string.calendar_edit_failed
                    })
                    error.visibility = View.VISIBLE
                }
            }
        }
    }
}
