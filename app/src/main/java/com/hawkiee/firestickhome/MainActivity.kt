package com.hawkiee.firestickhome

import android.app.Activity
import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** An installed app we can open. */
data class AppEntry(val pkg: String, val label: String)

/**
 * An app we want on the home screen. Matched by package name first, then by its label,
 * because the same app can have different package names on different devices.
 * [loose] = the label only has to contain one of [labels]; otherwise it must match exactly.
 */
data class Wanted(val name: String, val pkgs: List<String>, val labels: List<String>, val loose: Boolean)

class MainActivity : Activity() {

    companion object {
        const val OWNER_NAME = "Sean"

        // Stratford-upon-Avon
        const val WEATHER_URL =
            "https://api.open-meteo.com/v1/forecast?latitude=52.19&longitude=-1.71&current=temperature_2m,weather_code&timezone=Europe%2FLondon"
        const val WEATHER_REFRESH_MS = 30L * 60L * 1000L

        val FAVOURITES = listOf(
            Wanted("Netflix", listOf("com.netflix.ninja"), listOf("netflix"), loose = false),
            Wanted("NOW", listOf("com.nowtv.ui", "com.nowtv.ui.firetv"), listOf("now", "now tv"), loose = false),
            Wanted(
                "YouTube",
                listOf("com.amazon.firetv.youtube", "com.google.android.youtube.tv"),
                listOf("youtube"),
                loose = false
            ),
        )

        val KIDS = listOf(
            Wanted("CBeebies", emptyList(), listOf("cbeebies"), loose = true),
            Wanted("YouTube Kids", listOf("com.amazon.firetv.youtube.kids"), listOf("youtube kids"), loose = true),
            Wanted("BBC iPlayer", listOf("uk.co.bbc.iplayer"), listOf("iplayer"), loose = true),
            Wanted("Disney+", listOf("com.disney.disneyplus"), listOf("disney+"), loose = true),
        )

        // Things that should never appear as a tile.
        val HIDDEN = setOf("com.amazon.tv.launcher")

        // Colours from the Family Hub+ design.
        val BG = Color.parseColor("#14161F")
        val CARD = Color.parseColor("#262A3A")
        val CARD_DIM = Color.parseColor("#1F2230")
        val TEXT = Color.parseColor("#F4F1EA")
        val MUTED = Color.parseColor("#C9C3B6")
        val ACCENT = Color.parseColor("#F3C98B")
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences

    private lateinit var clockView: TextView
    private lateinit var dateView: TextView
    private lateinit var weatherView: TextView
    private lateinit var jumpRow: LinearLayout
    private lateinit var favRow: LinearLayout
    private lateinit var kidsSection: LinearLayout
    private lateinit var kidsRow: LinearLayout
    private lateinit var appsRow: LinearLayout
    private lateinit var allPanel: LinearLayout
    private lateinit var allGrid: LinearLayout
    private var allAppsTile: View? = null
    private lateinit var homeColumn: LinearLayout

    private var lastWeatherFetch = 0L
    private var recentTimes: Map<String, Long> = emptyMap()

    private val tick = object : Runnable {
        override fun run() {
            updateClock()
            if (System.currentTimeMillis() - lastWeatherFetch > WEATHER_REFRESH_MS) fetchWeather()
            main.postDelayed(this, 15_000L)
        }
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("home", Context.MODE_PRIVATE)
        window.decorView.setBackgroundColor(BG)
        setContentView(buildScreen())
    }

    override fun onResume() {
        super.onResume()
        allPanel.visibility = View.GONE
        homeColumn.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        buildRows()
        main.removeCallbacks(tick)
        main.post(tick)
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacks(tick)
    }

    // This is a home screen: Back has nowhere to go.
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (allPanel.visibility == View.VISIBLE) closeAllApps()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // ---------------------------------------------------------------- layout

    private fun buildScreen(): View {
        val root = FrameLayout(this)
        root.setBackgroundColor(BG)
        root.clipChildren = false

        val column = LinearLayout(this)
        homeColumn = column
        column.orientation = LinearLayout.VERTICAL
        column.clipChildren = false
        column.clipToPadding = false
        column.setPadding(dp(40), dp(24), dp(40), dp(20))
        root.addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Top bar: clock, date and greeting, weather, settings.
        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        top.clipChildren = false

        clockView = text("--:--", 40f, TEXT, light = true)
        top.addView(clockView)

        dateView = text("", 14f, MUTED)
        top.addView(dateView, margins(dp(14), 0, dp(14), 0))

        weatherView = text("Stratford", 13f, TEXT)
        weatherView.background = rounded(CARD_DIM, false, 999)
        weatherView.setPadding(dp(12), dp(6), dp(12), dp(6))
        top.addView(weatherView)

        val spacer = View(this)
        top.addView(spacer, LinearLayout.LayoutParams(0, 1, 1f))

        val settings = pill("Settings") { openSettings() }
        top.addView(settings)

        column.addView(top, fullWidth(0))

        // Jump back in: the last three apps opened, whatever they are.
        jumpRow = row()
        column.addView(section("Jump back in", jumpRow), fullWidth(dp(16)))

        // Favourites: always in the same place.
        favRow = row()
        column.addView(section("Favourites", favRow), fullWidth(dp(12)))

        // Kids.
        kidsRow = row()
        kidsSection = section("Kids", kidsRow)
        column.addView(kidsSection, fullWidth(dp(12)))

        // Five more apps and an "All apps" tile. No sideways scrolling.
        appsRow = row()
        column.addView(section("Apps", appsRow), fullWidth(dp(12)))

        // All apps: a full-screen grid on top, closed with Back.
        allPanel = LinearLayout(this)
        allPanel.orientation = LinearLayout.VERTICAL
        allPanel.setBackgroundColor(BG)
        allPanel.setPadding(dp(40), dp(24), dp(40), dp(0))
        allPanel.visibility = View.GONE
        allPanel.clipChildren = false
        val head = text("All apps", 22f, TEXT, bold = true)
        allPanel.addView(head)
        allPanel.addView(text("Hold OK on an app to hide it  ·  Back to close", 12f, MUTED), fullWidth(dp(2)))
        allGrid = LinearLayout(this)
        allGrid.orientation = LinearLayout.VERTICAL
        allGrid.clipChildren = false
        allGrid.clipToPadding = false
        allGrid.setPadding(dp(6), dp(12), dp(6), dp(24))
        val gridScroll = ScrollView(this)
        gridScroll.isVerticalScrollBarEnabled = false
        gridScroll.clipChildren = false
        gridScroll.clipToPadding = false
        gridScroll.addView(allGrid)
        allPanel.addView(gridScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).also { it.topMargin = dp(8) })
        root.addView(allPanel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        return root
    }

    private fun buildRows() {
        val apps = installedApps()
        val used = HashSet<String>()

        val favs = FAVOURITES.map { w -> Pair(w, find(w, apps)) }

        // Jump back in: the last three apps opened, any app at all.
        // Until there is any history, it shows the favourites instead.
        jumpRow.removeAllViews()
        val recent = ArrayList<AppEntry>()
        for (pkg in recentPackages()) {
            val app = apps[pkg] ?: continue
            if (HIDDEN.contains(pkg)) continue
            recent.add(app)
            if (recent.size == 3) break
        }
        for (p in favs) {
            if (recent.size == 3) break
            val app = p.second ?: continue
            if (recent.none { it.pkg == app.pkg }) recent.add(app)
        }
        recent.forEachIndexed { i, app ->
            val view = tile(app.label, lastOpenedText(app), CARD, TEXT, 22f) { launch(app.pkg) }
            val lp = LinearLayout.LayoutParams(0, dp(108), 1f)
            if (i < recent.size - 1) lp.marginEnd = dp(16)
            jumpRow.addView(view, lp)
        }

        // Favourites: fixed order, so they're always in the same place.
        favRow.removeAllViews()
        favs.forEachIndexed { i, p ->
            val w = p.first
            val app = p.second
            val view: View
            if (app == null) {
                view = tile(w.name, null, CARD_DIM, MUTED, 16f) {
                    toast("${w.name} isn't installed. Get it from the Appstore.")
                }
            } else {
                used.add(app.pkg)
                view = tile(w.name, null, CARD, TEXT, 16f) { launch(app.pkg) }
            }
            val lp = LinearLayout.LayoutParams(0, dp(50), 1f)
            if (i < favs.size - 1) lp.marginEnd = dp(12)
            favRow.addView(view, lp)
        }

        // Kids (only the ones that are installed)
        kidsRow.removeAllViews()
        val kids = KIDS.mapNotNull { w -> find(w, apps)?.let { Pair(w, it) } }
            .filter { !used.contains(it.second.pkg) }
        kids.forEachIndexed { i, p ->
            used.add(p.second.pkg)
            val view = tile(p.first.name, null, ACCENT, BG, 16f) { launch(p.second.pkg) }
            val lp = LinearLayout.LayoutParams(0, dp(50), 1f)
            if (i < kids.size - 1) lp.marginEnd = dp(12)
            kidsRow.addView(view, lp)
        }
        // Keep the row evenly sized when there are only one or two kids' apps.
        for (j in kids.size until 4) {
            kidsRow.addView(View(this), LinearLayout.LayoutParams(0, dp(50), 1f).also { it.marginStart = dp(12) })
        }
        kidsSection.visibility = if (kids.isEmpty()) View.GONE else View.VISIBLE

        // Apps
        // Apps: most recently used first, then A–Z. Five on the home screen, all in the grid.
        val hiddenByUser = prefs.getStringSet("hidden", emptySet()) ?: emptySet()
        val rest = apps.values
            .filter { !used.contains(it.pkg) && !HIDDEN.contains(it.pkg) && !hiddenByUser.contains(it.pkg) }
            .sortedWith(compareByDescending<AppEntry> { lastOpened(it) }.thenBy { it.label.lowercase(Locale.UK) })

        appsRow.removeAllViews()
        val onHome = rest.take(5)
        for (app in onHome) {
            appsRow.addView(appTile(app), LinearLayout.LayoutParams(0, dp(48), 1f).also { it.marginEnd = dp(10) })
        }
        for (j in onHome.size until 5) {
            appsRow.addView(View(this), LinearLayout.LayoutParams(0, dp(48), 1f).also { it.marginEnd = dp(10) })
        }
        val all = tile("All apps", null, CARD, TEXT, 14f) { openAllApps() }
        allAppsTile = all
        appsRow.addView(all, LinearLayout.LayoutParams(0, dp(48), 1f))

        // The All apps grid, five per row.
        allGrid.removeAllViews()
        val gridTiles = ArrayList<View>()
        for (app in rest) gridTiles.add(appTile(app))
        gridTiles.add(tile("Settings", null, CARD_DIM, TEXT, 14f) { openSettings() })
        if (hiddenByUser.isNotEmpty()) {
            gridTiles.add(tile("Show hidden (${hiddenByUser.size})", null, CARD_DIM, MUTED, 14f) {
                prefs.edit().remove("hidden").apply()
                buildRows()
            })
        }
        var gridRow: LinearLayout? = null
        gridTiles.forEachIndexed { i, v ->
            if (i % 5 == 0) {
                val r = row()
                allGrid.addView(r, fullWidth(if (i == 0) 0 else dp(14)))
                gridRow = r
            }
            gridRow?.addView(v, LinearLayout.LayoutParams(0, dp(56), 1f).also { if (i % 5 != 4) it.marginEnd = dp(14) })
        }
        val leftover = gridTiles.size % 5
        if (leftover != 0) {
            for (j in leftover until 5) {
                gridRow?.addView(View(this), LinearLayout.LayoutParams(0, dp(56), 1f).also { if (j != 4) it.marginEnd = dp(14) })
            }
        }

        if (allPanel.visibility == View.VISIBLE) {
            firstGridTile()?.requestFocus()
        } else {
            // Start on the most recently used app, so one press of OK gets you back in.
            jumpRow.getChildAt(0)?.requestFocus()
        }
    }

    /** An app tile: OK opens it, holding OK hides it. */
    private fun appTile(app: AppEntry): View {
        val view = tile(app.label, null, CARD_DIM, TEXT, 14f) { launch(app.pkg) }
        view.setOnLongClickListener {
            val next = HashSet(prefs.getStringSet("hidden", emptySet()) ?: emptySet())
            next.add(app.pkg)
            prefs.edit().putStringSet("hidden", next).apply()
            toast("Hid ${app.label}. \"Show hidden\" at the end of All apps brings it back.")
            buildRows()
            true
        }
        return view
    }

    private fun firstGridTile(): View? {
        val first = allGrid.getChildAt(0) as? LinearLayout ?: return null
        return first.getChildAt(0)
    }

    private fun openAllApps() {
        allPanel.visibility = View.VISIBLE
        // Keep the remote inside the grid while it's open.
        homeColumn.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        firstGridTile()?.requestFocus()
    }

    private fun closeAllApps() {
        allPanel.visibility = View.GONE
        homeColumn.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        allAppsTile?.requestFocus()
    }

    // ---------------------------------------------------------------- apps

    private fun installedApps(): Map<String, AppEntry> {
        val pm = packageManager
        val out = LinkedHashMap<String, AppEntry>()
        for (category in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val query = Intent(Intent.ACTION_MAIN).addCategory(category)
            @Suppress("DEPRECATION")
            val found = pm.queryIntentActivities(query, 0)
            for (info in found) {
                val pkg = info.activityInfo.packageName
                if (pkg == packageName || out.containsKey(pkg)) continue
                out[pkg] = AppEntry(pkg, info.loadLabel(pm).toString())
            }
        }
        return out
    }

    private fun find(w: Wanted, apps: Map<String, AppEntry>): AppEntry? {
        for (pkg in w.pkgs) {
            val hit = apps[pkg]
            if (hit != null) return hit
        }
        return apps.values.firstOrNull { app ->
            val label = app.label.lowercase(Locale.UK).trim()
            w.labels.any { wanted -> if (w.loose) label.contains(wanted) else label == wanted }
        }
    }

    private fun launch(pkg: String) {
        val pm = packageManager
        val intent = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg)
        if (intent == null) {
            toast("Couldn't open that app.")
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        prefs.edit().putLong("last_$pkg", System.currentTimeMillis()).apply()
        try {
            startActivity(intent)
        } catch (e: Exception) {
            toast("Couldn't open that app.")
        }
    }

    private fun openSettings() {
        try {
            startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            toast("Couldn't open Settings.")
        }
    }

    private fun lastOpened(app: AppEntry?): Long {
        if (app == null) return -1L
        return recentTimes[app.pkg] ?: prefs.getLong("last_${app.pkg}", 0L)
    }

    /**
     * Apps by when they were last opened, newest first.
     * Always knows about apps opened from this screen. If usage access has been granted
     * (one ADB command, see README), it also sees apps opened any other way.
     */
    private fun recentPackages(): List<String> {
        val times = HashMap<String, Long>()
        for ((key, value) in prefs.all) {
            if (key.startsWith("last_") && value is Long) times[key.removePrefix("last_")] = value
        }
        if (hasUsageAccess()) {
            try {
                val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
                val now = System.currentTimeMillis()
                val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, now - 14L * 24L * 3600L * 1000L, now)
                for (st in stats) {
                    if (st.totalTimeInForeground <= 0L) continue
                    val before = times[st.packageName] ?: 0L
                    if (st.lastTimeUsed > before) times[st.packageName] = st.lastTimeUsed
                }
            } catch (e: Exception) {
                // Fall back to what this screen knows.
            }
        }
        times.remove(packageName)
        recentTimes = times
        return times.entries.sortedByDescending { it.value }.map { it.key }
    }

    private fun hasUsageAccess(): Boolean {
        return try {
            val ops = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            @Suppress("DEPRECATION")
            val mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName)
            mode == AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            false
        }
    }

    private fun lastOpenedText(app: AppEntry): String {
        val t = lastOpened(app)
        if (t <= 0L) return "Press OK to open"
        val then = Calendar.getInstance()
        then.timeInMillis = t
        val now = Calendar.getInstance()
        val sameDay = then.get(Calendar.YEAR) == now.get(Calendar.YEAR) &&
            then.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)
        return if (sameDay) {
            "Last watched " + SimpleDateFormat("HH:mm", Locale.UK).format(Date(t))
        } else {
            "Last watched " + SimpleDateFormat("EEEE", Locale.UK).format(Date(t))
        }
    }

    // ---------------------------------------------------------------- clock and weather

    private fun updateClock() {
        val now = Date()
        clockView.text = SimpleDateFormat("HH:mm", Locale.UK).format(now)
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val greeting = when {
            hour < 5 -> "Late night"
            hour < 12 -> "Morning"
            hour < 18 -> "Afternoon"
            else -> "Evening"
        }
        dateView.text = SimpleDateFormat("EEE d MMM", Locale.UK).format(now) + "  ·  " + greeting + ", " + OWNER_NAME
    }

    private fun fetchWeather() {
        lastWeatherFetch = System.currentTimeMillis()
        Thread {
            try {
                val conn = URL(WEATHER_URL).openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                val current = JSONObject(body).getJSONObject("current")
                val temp = Math.round(current.getDouble("temperature_2m"))
                val code = current.getInt("weather_code")
                val line = "Stratford  $temp°  " + describeWeather(code)
                main.post { weatherView.text = line }
            } catch (e: Exception) {
                // No weather is fine; try again next refresh.
                lastWeatherFetch = 0L
            }
        }.start()
    }

    private fun describeWeather(code: Int): String = when (code) {
        0 -> "Clear"
        1, 2 -> "Partly cloudy"
        3 -> "Cloudy"
        45, 48 -> "Fog"
        51, 53, 55, 56, 57 -> "Drizzle"
        61, 63, 65, 66, 67, 80, 81, 82 -> "Rain"
        71, 73, 75, 77, 85, 86 -> "Snow"
        95, 96, 99 -> "Storms"
        else -> ""
    }

    // ---------------------------------------------------------------- view helpers

    private fun dp(v: Int): Int = Math.round(v * resources.displayMetrics.density)

    private fun fullWidth(topMargin: Int): LinearLayout.LayoutParams {
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = topMargin
        return lp
    }

    private fun margins(start: Int, top: Int, end: Int, bottom: Int): LinearLayout.LayoutParams {
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.marginStart = start
        lp.topMargin = top
        lp.marginEnd = end
        lp.bottomMargin = bottom
        return lp
    }

    private fun text(s: String, sp: Float, color: Int, bold: Boolean = false, light: Boolean = false): TextView {
        val tv = TextView(this)
        tv.text = s
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        tv.setTextColor(color)
        tv.typeface = when {
            bold -> Typeface.create("sans-serif", Typeface.BOLD)
            light -> Typeface.create("sans-serif-light", Typeface.NORMAL)
            else -> Typeface.create("sans-serif", Typeface.NORMAL)
        }
        tv.maxLines = 2
        tv.ellipsize = TextUtils.TruncateAt.END
        return tv
    }

    private fun rounded(color: Int, focused: Boolean, radiusDp: Int = 16): GradientDrawable {
        val d = GradientDrawable()
        d.setColor(color)
        d.cornerRadius = dp(radiusDp).toFloat()
        if (focused) d.setStroke(dp(3), Color.WHITE)
        return d
    }

    private fun row(): LinearLayout {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        r.clipChildren = false
        r.clipToPadding = false
        return r
    }

    private fun section(title: String, content: View): LinearLayout {
        val s = LinearLayout(this)
        s.orientation = LinearLayout.VERTICAL
        s.clipChildren = false
        s.clipToPadding = false
        s.addView(text(title, 14f, TEXT, bold = true), margins(0, 0, 0, dp(8)))
        s.addView(
            content,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        return s
    }

    /** A focusable card. It grows and gets a white ring when the remote lands on it. */
    private fun tile(title: String, sub: String?, bg: Int, fg: Int, sizeSp: Float, onClick: () -> Unit): View {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.gravity = if (sub != null) Gravity.BOTTOM or Gravity.START else Gravity.CENTER
        box.setPadding(dp(12), dp(6), dp(12), dp(8))
        box.isFocusable = true
        box.isClickable = true
        box.background = rounded(bg, false)
        box.setOnClickListener { onClick() }
        box.setOnFocusChangeListener { v, hasFocus ->
            v.background = rounded(bg, hasFocus)
            val scale = if (hasFocus) 1.06f else 1f
            v.animate().scaleX(scale).scaleY(scale).setDuration(120L).start()
            v.translationZ = if (hasFocus) dp(8).toFloat() else 0f
        }
        if (sub != null) box.addView(text(sub, 11f, MUTED))
        val label = text(title, sizeSp, fg, bold = true)
        if (sub == null) {
            label.gravity = Gravity.CENTER
            label.maxLines = 1
        }
        box.addView(label)
        return box
    }

    private fun pill(title: String, onClick: () -> Unit): View {
        val tv = text(title, 13f, TEXT, bold = true)
        tv.setPadding(dp(14), dp(8), dp(14), dp(8))
        tv.isFocusable = true
        tv.isClickable = true
        tv.background = rounded(CARD, false, 12)
        tv.setOnClickListener { onClick() }
        tv.setOnFocusChangeListener { v, hasFocus -> v.background = rounded(CARD, hasFocus, 12) }
        return tv
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }
}
