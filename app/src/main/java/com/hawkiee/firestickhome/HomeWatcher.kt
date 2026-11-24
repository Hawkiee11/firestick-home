package com.hawkiee.firestickhome

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

/**
 * Fire OS won't let another app be Home. This service is told whenever Amazon's launcher opens a
 * window (at start-up, or when Home is pressed) and immediately brings Family Hub to the front.
 * Switched on once over ADB (see README). "Open on Home" in Family Hub settings turns it off.
 */
class HomeWatcher : AccessibilityService() {

    private var lastLaunch = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (event.packageName?.toString() != AMAZON_HOME) return
        val prefs = getSharedPreferences("home", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("home_takeover", true)) return
        val now = System.currentTimeMillis()
        if (now - lastLaunch < 800L) return
        lastLaunch = now
        val intent = Intent(this, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            // Nothing to do: Amazon's home stays up.
        }
    }

    override fun onInterrupt() {}

    companion object {
        const val AMAZON_HOME = "com.amazon.tv.launcher"
    }
}
