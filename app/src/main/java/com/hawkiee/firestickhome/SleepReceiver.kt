package com.hawkiee.firestickhome

import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/** Fired by the alarm the sleep timer sets: switches the screen off. */
class SleepReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        context.getSharedPreferences("home", Context.MODE_PRIVATE).edit().remove("sleep_at").apply()
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(context, SleepAdmin::class.java)
        if (dpm.isAdminActive(admin)) {
            try {
                dpm.lockNow()
            } catch (e: Exception) {
                // Nothing more we can do without the computer setup.
            }
        }
    }
}
