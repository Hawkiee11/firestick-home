package com.hawkiee.firestickhome

import android.accessibilityservice.AccessibilityService
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Fire OS won't let another app be Home. This service is told whenever Amazon's launcher opens a
 * window and, if what's now on screen is Amazon's main home page, brings Family Hub to the front.
 * Amazon's settings, search and Your Apps live in the same app, so it checks which screen is
 * actually showing (from usage events, granted over ADB) rather than trusting the event alone.
 * Switched on once over ADB (see README). "Open on Home button" in Family Hub settings turns it off.
 * Logs under the tag FamilyHubHome: adb logcat -d -s FamilyHubHome
 */
class HomeWatcher : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var lastLaunch = 0L
    private var lastEventClass = ""

    private val check = Runnable { decide() }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (event.packageName?.toString() != AMAZON_HOME) return
        val prefs = getSharedPreferences("home", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("home_takeover", true)) return
        lastEventClass = event.className?.toString() ?: ""
        Log.i(TAG, "window event: $lastEventClass")
        // Give the system a moment to record which screen resumed, then decide.
        handler.removeCallbacks(check)
        handler.postDelayed(check, 350L)
    }

    private fun decide() {
        val top = topActivity()
        Log.i(TAG, "on screen now: ${top ?: "unknown"} (event said $lastEventClass)")
        val isHome = if (top != null) {
            top.startsWith("$AMAZON_HOME/") && top.contains("HomeActivity")
        } else {
            lastEventClass.contains("HomeActivity")
        }
        if (!isHome) return
        val now = System.currentTimeMillis()
        if (now - lastLaunch < 800L) return
        lastLaunch = now
        Log.i(TAG, "Amazon home is showing: bringing Family Hub back")
        val intent = Intent(this, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "couldn't start Family Hub", e)
        }
    }

    /** "package/class" of the most recently resumed screen, or null without usage access. */
    private fun topActivity(): String? {
        return try {
            val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val events = usm.queryEvents(now - 60_000L, now + 1_000L)
            val e = UsageEvents.Event()
            var last: String? = null
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                @Suppress("DEPRECATION")
                if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    last = e.packageName + "/" + (e.className ?: "")
                }
            }
            last
        } catch (e: Exception) {
            null
        }
    }

    override fun onInterrupt() {}

    companion object {
        const val AMAZON_HOME = "com.amazon.tv.launcher"
        const val TAG = "FamilyHubHome"
    }
}
