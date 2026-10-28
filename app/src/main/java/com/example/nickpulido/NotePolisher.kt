package com.nickpulido.rcrm

import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import android.view.LayoutInflater
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object NotePolisher {
    fun bind(activity: AppCompatActivity, button: Button, note: EditText) {
        button.setOnClickListener {
            val original = note.text.toString()
            if (original.isBlank()) {
                Toast.makeText(activity, R.string.polish_note_empty, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (original.length > 6000) {
                Toast.makeText(activity, R.string.polish_note_too_long, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val startPolishing = {
            button.isEnabled = false
            button.setText(R.string.polish_note_working)
            activity.lifecycleScope.launch {
                try {
                    val polished = withContext(Dispatchers.IO) {
                        GemmaNoteModel.polish(activity.applicationContext, original) { percent ->
                            activity.runOnUiThread {
                                if (button.isAttachedToWindow) {
                                    button.text = if (percent == null) activity.getString(R.string.polish_note_working)
                                    else activity.getString(R.string.polish_note_download_progress, percent)
                                }
                            }
                        }
                    }
                    if (!button.isAttachedToWindow || activity.isFinishing || activity.isDestroyed) return@launch
                    val previewView = LayoutInflater.from(activity).inflate(R.layout.dialog_polished_note, null)
                    val preview = previewView.findViewById<EditText>(R.id.etPolishedNote).apply {
                        setText(polished)
                        setSelection(length())
                    }
                    val dialog = AlertDialog.Builder(activity).setView(previewView).create()
                    previewView.findViewById<Button>(R.id.btnKeepOriginalNote).setOnClickListener { dialog.dismiss() }
                    previewView.findViewById<Button>(R.id.btnUsePolishedNote).setOnClickListener {
                            if (note.isAttachedToWindow && note.isEnabled && note.text.toString() == original) {
                                val revised = preview.text.toString()
                                if (revised.isNotBlank()) {
                                    note.setText(revised)
                                    note.setSelection(note.length())
                                }
                            } else {
                                Toast.makeText(activity, R.string.polish_note_changed, Toast.LENGTH_LONG).show()
                            }
                            dialog.dismiss()
                    }
                    dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
                    dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                    dialog.show()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (button.isAttachedToWindow && !activity.isFinishing && !activity.isDestroyed) {
                        Toast.makeText(activity, R.string.polish_note_unavailable, Toast.LENGTH_LONG).show()
                    }
                } finally {
                    button.isEnabled = true
                    button.setText(R.string.polish_note)
                }
            }
            }
            if (GemmaNoteModel.isDownloaded(activity)) {
                startPolishing()
            } else {
                AlertDialog.Builder(activity)
                    .setTitle(R.string.polish_note_download_title)
                    .setMessage(R.string.polish_note_download_message)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.polish_note_download_action) { _, _ ->
                        if (note.isAttachedToWindow && note.text.toString() == original) startPolishing()
                    }
                    .show()
            }
        }
    }
}
