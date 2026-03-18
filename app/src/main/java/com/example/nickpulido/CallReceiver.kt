package com.nickpulido.rcrm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager

class CallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)

        if (state == TelephonyManager.EXTRA_STATE_IDLE) {
            @Suppress("DEPRECATION")
            val phoneNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)

            if (!phoneNumber.isNullOrEmpty()) {
                // Check if this number is ignored
                val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                val ignored = prefs.getStringSet("ignored_contacts_list", emptySet()) ?: emptySet()
                
                if (ignored.contains(phoneNumber)) {
                    return // Do nothing for ignored contacts
                }

                val logIntent = Intent(context, SmartLogActivity::class.java).apply {
                    putExtra("INCOMING_NUMBER", phoneNumber)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                context.startActivity(logIntent)
            }
        }
    }
}