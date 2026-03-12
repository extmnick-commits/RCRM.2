package com.nickpulido.rcrm // Updated package name

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager

class CallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // Security Fix: Verify intent action
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)

        // Detect when the call ends (IDLE state)
        if (state == TelephonyManager.EXTRA_STATE_IDLE) {
            // Deprecation Fix: Suppress warning for incoming number field
            @Suppress("DEPRECATION")
            val phoneNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)

            if (!phoneNumber.isNullOrEmpty()) {
                // This now correctly points to the SmartLogActivity in your new package
                val logIntent = Intent(context, SmartLogActivity::class.java).apply {
                    putExtra("INCOMING_NUMBER", phoneNumber)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                context.startActivity(logIntent)
            }
        }
    }
}