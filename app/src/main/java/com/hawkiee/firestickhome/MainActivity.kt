package com.hawkiee.firestickhome

import android.app.Activity
import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
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
import android.widget.ImageView
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
data class AppEntry(val pkg: String, val label: String, val info: ActivityInfo)

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

        // App drawer groups, in the order they appear.
        const val G_STREAM = "Streaming"
        const val G_KIDS = "Kids"
        const val G_MUSIC = "Music & audio"
        const val G_GAMES = "Games"
        const val G_TOOLS = "Tools & settings"
        const val G_AMAZON = "Amazon"
        const val G_OTHER = "Other"
        val GROUP_ORDER = listOf(G_STREAM, G_KIDS, G_MUSIC, G_GAMES, G_TOOLS, G_AMAZON, G_OTHER)

        // Matched against the app's name (lower case). First match wins, in the order of groupOf().
        val KIDS_WORDS = listOf("kids", "cbeebies", "cbbc", "nick jr", "pbs kids")
        val MUSIC_WORDS = listOf("spotify", "music", "audible", "bbc sounds", "tunein", "podcast", "deezer", "tidal", "radio")
        val GAME_WORDS = listOf("luna", "game", "xbox", "geforce", "steam link", "arcade")
        val STREAM_WORDS = listOf(
            "netflix", "youtube", "prime video", "disney", "iplayer", "itvx", "channel 4", "my5", "apple tv",
            "paramount", "plex", "pluto", "discovery", "britbox", "uktv", "jellyfin", "kodi", "stremio",
            "twitch", "dazn", "tnt sports", "sky ", "rakuten", "mubi", "crunchyroll", "hayu", "freely", "vlc",
            "mx player", "emby", "freevee", "tubi"
        )
        val TOOL_WORDS = listOf("downloader", "browser", "silk", "file", "vpn", "speed", "send files", "settings", "analiti")

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
    private lateinit var allPanel: LinearLayout
    private lateinit var allGrid: LinearLayout
    private var allAppsTile: View? = null
    private var firstGridRow: LinearLayout? = null
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

        clockView = text("--:--", 34f, TEXT, light = true)
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
        column.addView(section("Favourites", favRow), fullWidth(dp(10)))

        // Kids.
        kidsRow = row()
        kidsSection = section("Kids", kidsRow)
        column.addView(kidsSection, fullWidth(dp(10)))

        // Push the drawer handle to the bottom of the screen.
        column.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))

        // Drawer handle: landing on it (pressing down from the last row) slides the app drawer up.
        val handle = text("↓   All apps", 14f, MUTED, bold = true)
        handle.gravity = Gravity.CENTER
        handle.setPadding(dp(18), dp(8), dp(18), dp(8))
        handle.isFocusable = true
        handle.isClickable = true
        handle.background = rounded(BG, false, 999)
        handle.setOnClickListener { openAllApps() }
        handle.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) openAllApps() }
        allAppsTile = handle
        val handleLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        handleLp.gravity = Gravity.CENTER_HORIZONTAL
        column.addView(handle, handleLp)

        // All apps: a full-screen grid on top, closed with Back.
        allPanel = LinearLayout(this)
        allPanel.orientation = LinearLayout.VERTICAL
        allPanel.setBackgroundColor(BG)
        allPanel.setPadding(dp(40), dp(24), dp(40), dp(0))
        allPanel.visibility = View.GONE
        allPanel.clipChildren = false
        val head = text("All apps", 22f, TEXT, bold = true)
        allPanel.addView(head)
        allPanel.addView(text("Grouped, A–Z  ·  Hold OK on an app to hide it  ·  Up or Back to close", 12f, MUTED), fullWidth(dp(2)))
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
            val view = jumpTile(app)
            val lp = LinearLayout.LayoutParams(0, dp(104), 1f)
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
                view = tile(w.name + " · not installed", null, CARD_DIM, MUTED, 14f) {
                    toast("${w.name} isn't installed. Get it from the Appstore.")
                }
            } else {
                used.add(app.pkg)
                view = artTile(app, CARD)
            }
            val lp = LinearLayout.LayoutParams(0, dp(68), 1f)
            if (i < favs.size - 1) lp.marginEnd = dp(12)
            favRow.addView(view, lp)
        }

        // Kids (only the ones that are installed)
        kidsRow.removeAllViews()
        val kids = KIDS.mapNotNull { w -> find(w, apps)?.let { Pair(w, it) } }
            .filter { !used.contains(it.second.pkg) }
        kids.forEachIndexed { i, p ->
            used.add(p.second.pkg)
            val view = artTile(p.second, ACCENT)
            val lp = LinearLayout.LayoutParams(0, dp(68), 1f)
            if (i < kids.size - 1) lp.marginEnd = dp(12)
            kidsRow.addView(view, lp)
        }
        // Keep the row evenly sized when there are only one or two kids' apps.
        for (j in kids.size until 4) {
            kidsRow.addView(View(this), LinearLayout.LayoutParams(0, dp(68), 1f).also { it.marginStart = dp(12) })
        }
        kidsSection.visibility = if (kids.isEmpty()) View.GONE else View.VISIBLE

        // Apps
        // The app drawer: every app, A–Z, including the ones on the home screen.
        val hiddenByUser = prefs.getStringSet("hidden", emptySet()) ?: emptySet()
        val rest = apps.values
            .filter { !HIDDEN.contains(it.pkg) && !hiddenByUser.contains(it.pkg) }
            .sortedBy { it.label.lowercase(Locale.UK) }

        // The drawer, grouped (Streaming, Kids, Music…), A–Z within each group, five per row.
        allGrid.removeAllViews()
        firstGridRow = null
        val groups = LinkedHashMap<String, ArrayList<View>>()
        for (g in GROUP_ORDER) groups[g] = ArrayList()
        for (app in rest) groups.getValue(groupOf(app)).add(appTile(app))
        val tools = groups.getValue(G_TOOLS)
        tools.add(tile("Settings", null, CARD_DIM, TEXT, 14f) { openSettings() })
        if (hiddenByUser.isNotEmpty()) {
            tools.add(tile("Show hidden (${hiddenByUser.size})", null, CARD_DIM, MUTED, 14f) {
                prefs.edit().remove("hidden").apply()
                buildRows()
            })
        }
        for ((title, views) in groups) {
            if (views.isEmpty()) continue
            val heading = text(title, 15f, TEXT, bold = true)
            allGrid.addView(heading, fullWidth(if (allGrid.childCount == 0) 0 else dp(22)))
            var gridRow: LinearLayout? = null
            views.forEachIndexed { i, v ->
                if (i % 5 == 0) {
                    val r = row()
                    allGrid.addView(r, fullWidth(dp(10)))
                    if (firstGridRow == null) firstGridRow = r
                    gridRow = r
                }
                gridRow?.addView(v, LinearLayout.LayoutParams(0, dp(80), 1f).also { if (i % 5 != 4) it.marginEnd = dp(14) })
            }
            val leftover = views.size % 5
            if (leftover != 0) {
                for (j in leftover until 5) {
                    gridRow?.addView(View(this), LinearLayout.LayoutParams(0, dp(80), 1f).also { if (j != 4) it.marginEnd = dp(14) })
                }
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
        val view = artTile(app, CARD_DIM)
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

    private fun firstGridTile(): View? = firstGridRow?.getChildAt(0)

    /** Which drawer group an app belongs in: by name first, then by the category the app declares. */
    private fun groupOf(app: AppEntry): String {
        val l = app.label.lowercase(Locale.UK).trim()
        fun has(words: List<String>) = words.any { l.contains(it) }
        if (has(KIDS_WORDS)) return G_KIDS
        if (has(MUSIC_WORDS)) return G_MUSIC
        if (has(GAME_WORDS)) return G_GAMES
        if (l == "now" || l.startsWith("now ") || has(STREAM_WORDS)) return G_STREAM
        if (has(TOOL_WORDS)) return G_TOOLS
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            when (app.info.applicationInfo.category) {
                ApplicationInfo.CATEGORY_VIDEO -> return G_STREAM
                ApplicationInfo.CATEGORY_AUDIO -> return G_MUSIC
                ApplicationInfo.CATEGORY_GAME -> return G_GAMES
                ApplicationInfo.CATEGORY_PRODUCTIVITY -> return G_TOOLS
            }
        }
        if (app.pkg.startsWith("com.amazon.")) return G_AMAZON
        return G_OTHER
    }

    private fun openAllApps() {
        if (allPanel.visibility == View.VISIBLE) return
        allPanel.visibility = View.VISIBLE
        // Keep the remote inside the drawer while it's open.
        homeColumn.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        val h = resources.displayMetrics.heightPixels.toFloat()
        allPanel.translationY = h
        allPanel.animate().translationY(0f).setDuration(220L).start()
        firstGridTile()?.requestFocus()
    }

    /** The bottom-most visible tile on the home screen, where focus lands when the drawer closes. */
    private fun lastHomeTile(): View? {
        if (kidsSection.visibility == View.VISIBLE && kidsRow.childCount > 0) return kidsRow.getChildAt(0)
        return favRow.getChildAt(0) ?: jumpRow.getChildAt(0)
    }

    // Up from the drawer's first row closes it.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN &&
            event.keyCode == KeyEvent.KEYCODE_DPAD_UP &&
            allPanel.visibility == View.VISIBLE
        ) {
            val firstRow = firstGridRow
            val focused = currentFocus
            if (firstRow != null && focused != null && focused.parent == firstRow) {
                closeAllApps()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun closeAllApps() {
        homeColumn.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        lastHomeTile()?.requestFocus()
        val h = resources.displayMetrics.heightPixels.toFloat()
        allPanel.animate().translationY(h).setDuration(180L).withEndAction {
            allPanel.visibility = View.GONE
            allPanel.translationY = 0f
        }.start()
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
                out[pkg] = AppEntry(pkg, info.loadLabel(pm).toString(), info.activityInfo)
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
        s.addView(text(title, 14f, TEXT, bold = true), margins(0, 0, 0, dp(6)))
        s.addView(
            content,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        return s
    }

    // ---------------------------------------------------------------- app artwork

    /** The app's wide TV banner if it has one, else null. */
    private fun bannerOf(app: AppEntry): Drawable? {
        val pm = packageManager
        return try {
            app.info.loadBanner(pm) ?: pm.getApplicationBanner(app.pkg)
        } catch (e: Exception) {
            null
        }
    }

    private fun iconOf(app: AppEntry): Drawable? {
        return try {
            app.info.loadIcon(packageManager)
        } catch (e: Exception) {
            null
        }
    }

    /** Makes a view behave like a tile: ring and grow on focus, OK to open. */
    private fun makeTile(v: View, bg: Int, onClick: () -> Unit) {
        v.isFocusable = true
        v.isClickable = true
        v.background = rounded(bg, false)
        v.clipToOutline = true
        v.setOnClickListener { onClick() }
        v.setOnFocusChangeListener { view, hasFocus ->
            view.background = rounded(bg, hasFocus)
            val scale = if (hasFocus) 1.06f else 1f
            view.animate().scaleX(scale).scaleY(scale).setDuration(120L).start()
            view.translationZ = if (hasFocus) dp(8).toFloat() else 0f
        }
    }

    /**
     * A tile showing the app's banner. Apps without a banner get their icon and name.
     * The 3 dp inset leaves room for the white focus ring around the picture.
     */
    private fun artTile(app: AppEntry, bg: Int): View {
        val banner = bannerOf(app)
        if (banner != null) {
            val box = FrameLayout(this)
            box.setPadding(dp(3), dp(3), dp(3), dp(3))
            val img = ImageView(this)
            img.setImageDrawable(banner)
            img.scaleType = ImageView.ScaleType.FIT_CENTER
            img.clipToOutline = true
            img.background = rounded(Color.TRANSPARENT, false, 13)
            box.addView(img, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            box.contentDescription = app.label
            makeTile(box, bg) { launch(app.pkg) }
            return box
        }
        val box = LinearLayout(this)
        box.orientation = LinearLayout.HORIZONTAL
        box.gravity = Gravity.CENTER
        box.setPadding(dp(10), dp(6), dp(10), dp(6))
        val icon = iconOf(app)
        if (icon != null) {
            val img = ImageView(this)
            img.setImageDrawable(icon)
            img.scaleType = ImageView.ScaleType.FIT_CENTER
            box.addView(img, LinearLayout.LayoutParams(dp(32), dp(32)).also { it.marginEnd = dp(8) })
        }
        val fg = if (bg == ACCENT) BG else TEXT
        val label = text(app.label, 13f, fg, bold = true)
        label.maxLines = 1
        box.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        box.contentDescription = app.label
        makeTile(box, bg) { launch(app.pkg) }
        return box
    }

    /** A big Jump back in tile: banner on the left, name and when on the right. */
    private fun jumpTile(app: AppEntry): View {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.HORIZONTAL
        box.gravity = Gravity.CENTER_VERTICAL
        box.setPadding(dp(3), dp(3), dp(12), dp(3))
        val art = bannerOf(app) ?: iconOf(app)
        if (art != null) {
            val img = ImageView(this)
            img.setImageDrawable(art)
            img.scaleType = ImageView.ScaleType.FIT_CENTER
            img.clipToOutline = true
            img.background = rounded(Color.TRANSPARENT, false, 13)
            box.addView(img, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.1f))
        }
        val words = LinearLayout(this)
        words.orientation = LinearLayout.VERTICAL
        words.gravity = Gravity.CENTER_VERTICAL
        words.setPadding(dp(12), 0, 0, 0)
        val name = text(app.label, 17f, TEXT, bold = true)
        name.maxLines = 2
        words.addView(name)
        words.addView(text(lastOpenedText(app), 11f, MUTED))
        box.addView(words, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        box.contentDescription = app.label
        makeTile(box, CARD) { launch(app.pkg) }
        return box
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
