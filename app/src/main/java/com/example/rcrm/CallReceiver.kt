package com.example.rcrm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.widget.Toast

class CallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)

        // This detects when the call ends (IDLE state)
        if (state == TelephonyManager.EXTRA_STATE_IDLE) {
            val phoneNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)

            // For now, we'll just show a quick message.
            // Later, this will open your RCRM logging screen.
            Toast.makeText(context, "Call Ended. Ready to log lead?", Toast.LENGTH_LONG).show()
        }
    }
}